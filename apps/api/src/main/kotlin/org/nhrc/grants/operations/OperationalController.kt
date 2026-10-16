package org.nhrc.grants.operations

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.nhrc.grants.config.PlatformIdentity
import org.nhrc.grants.config.PlatformAccess
import org.nhrc.grants.config.GrantUser
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.sql.Array
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.security.MessageDigest

data class DomainField(val name:String,val label:String,val type:String,val help:String="",val reference:String="",val choices:List<String> = emptyList(),val section:String="",val required:Boolean=false,val maxLength:Int=12000,val dbType:String="text",val defaultValue:Any?=null,val hidden:Boolean=false)
data class DomainDefinition(val key:String,val table:String,val group:String,val pages:List<String>,val title:String,val description:String,val fields:List<DomainField>,val columns:List<String>,val states:List<String>,val writers:List<String>,val create:Boolean=true,val filter:Map<String,String> = emptyMap(),val stateField:String="",val idField:String="id")
data class RecordWrite(val values:Map<String,Any?>,val etag:String?=null,val note:String?=null,val requestId:UUID?=null)
data class RecordAction(val state:String,val etag:String,val note:String)

@Service
class OperationalCatalog(private val mapper:ObjectMapper,private val jdbc:JdbcTemplate) {
 val baseDefinitions:List<DomainDefinition> = mapper.readValue(ClassPathResource("operational-catalog.json").inputStream,object:TypeReference<List<DomainDefinition>>() {})
 init {baseDefinitions.forEach { d -> require(d.table.matches(Regex("[a-z_]+")));d.fields.forEach { require(it.name.matches(Regex("[a-z_]+"))) } }}
 val definitions:List<DomainDefinition> get() {
   val overrides=jdbc.queryForList("select * from form_administration").associateBy {it["form_key"].toString()}
   return baseDefinitions.map { d -> val o=overrides[d.key] ?: return@map d
     val fields=mapper.readTree(o["fields"].toString()).associateBy {it.path("name").asText()}
     val order=fields.keys.toList()
     d.copy(title=o["title"].toString(),description=o["description"].toString(),writers=mapper.readTree(o["writers"].toString()).map {it.asText()},create=d.create && o["enabled"]==true,
       fields=d.fields.map {f -> val c=fields[f.name];if(c==null)f else f.copy(label=c.path("label").asText(),help=c.path("help").asText(),section=c.path("section").asText(),required=f.required || c.path("required").asBoolean(),hidden=c.path("hidden").asBoolean())}.sortedBy {order.indexOf(it.name)})
   }
 }
 fun allowed(key:String,roles:Set<String>,write:Boolean):Boolean {
   if("SUPERADMIN" in roles && (!write || get(key).group in setOf("Administration","People & Organisation","IT & Security")))return true
   val o=jdbc.queryForList("select enabled,readers from form_administration where form_key=?",key).firstOrNull() ?: return true
   if(write && o["enabled"]!=true)return false
   return mapper.readTree(o["readers"].toString()).any {it.asText() in roles}
 }
 fun editable(key:String,roles:Set<String>):Boolean = allowed(key,roles,true) && (get(key).writers.any {it in roles} || "SUPERADMIN" in roles && get(key).group in setOf("Administration","People & Organisation","IT & Security")) && key !in setOf("events","positions")
 fun hiddenPages(roles:Set<String>):List<String> = baseDefinitions.flatMap {it.pages}.distinct().filter {page -> baseDefinitions.filter {page in it.pages}.all {!allowed(it.key,roles,false)}}
 fun legacyAllowed(path:String,method:String,roles:Set<String>):Boolean {
   val key=FormRoutePermissions.key(path) ?: return true
   if(jdbc.queryForObject("select count(*) from form_administration where form_key=?",Long::class.java,key)==0L)return true
   if(!allowed(key,roles,method !in setOf("GET","HEAD","OPTIONS")))return false
   // Existing authority checks still decide workflow decisions; this layer never grants them.
   if(method in setOf("GET","HEAD","OPTIONS") || path.endsWith("/decision") || path.endsWith("/action"))return true
   return editable(key,roles)
 }
 fun get(key:String)=definitions.firstOrNull { it.key==key } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Workspace not found")
}

@RestController
@RequestMapping("/api/operations")
class OperationalController(private val jdbc:JdbcTemplate,private val catalog:OperationalCatalog,private val identity:PlatformIdentity,private val mapper:ObjectMapper,private val closure:AwardClosureReadiness,private val preGrant:org.nhrc.grants.pregrant.PreGrantController) {
 private fun fail(message:String):Nothing=throw ResponseStatusException(HttpStatus.BAD_REQUEST,message)
 private fun check(d:DomainDefinition,write:Boolean=false):GrantUser {
   val actor=identity.current()
   val readAllowed=d.group in PlatformAccess.groups(actor.roles) || d.key in setOf("support","compliance","agreements") && actor.roles.any { role -> role in catalog.baseDefinitions.first {it.key==d.key}.writers }
   if(!readAllowed || !catalog.allowed(d.key,actor.roles,write) || write && actor.roles.none { it in d.writers } && !("SUPERADMIN" in actor.roles && d.group in setOf("Administration","People & Organisation","IT & Security"))) throw ResponseStatusException(HttpStatus.FORBIDDEN,"Your role does not permit this workspace action")
   return actor
 }
 private fun normalize(value:Any?):Any?=when(value) {
   is Array -> (value.array as kotlin.Array<*>).toList()
   is org.postgresql.util.PGobject -> if(value.type in setOf("json","jsonb")) mapper.readTree(value.value) else value.value
   is java.sql.Date -> value.toLocalDate().toString()
   is java.sql.Timestamp -> value.toInstant().toString()
   is UUID -> value.toString()
   else -> value
 }
 private fun row(r:Map<String,Any?>)=r.mapValues { normalize(it.value) }
 private fun record(d:DomainDefinition,id:UUID,lock:Boolean=false):Map<String,Any?> = jdbc.queryForList("select t.*,md5(to_jsonb(t)::text) _etag from ${d.table} t where ${d.idField}=?"+(if(lock) " for update" else ""),id).firstOrNull()?.let(::row)
   ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Record no longer exists")
 private fun canEdit(d:DomainDefinition,actor:GrantUser)=d.key !in setOf("events","positions") && catalog.allowed(d.key,actor.roles,true) && (actor.roles.any { it in d.writers } || "SUPERADMIN" in actor.roles && d.group in setOf("Administration","People & Organisation","IT & Security"))
 private fun allowedStates(d:DomainDefinition,r:Map<String,Any?>,actor:GrantUser):List<String> {
   if(!catalog.allowed(d.key,actor.roles,true))return emptyList()
   val current=r[d.stateField]?.toString() ?: ""
   if(d.stateField.isBlank() || current in setOf("CLOSED","CANCELLED","REJECTED","RETIRED","COMPLETE","ACCEPTED","VERIFIED") || d.key=="financial-approvals" && current=="APPROVED")return emptyList()
   val next=d.states.getOrNull(d.states.indexOf(current)+1) ?: return emptyList()
   val decisionRoles=when {
     d.key=="requisitions" && next=="APPROVED" -> setOf("DIRECTOR","FINANCE_APPROVER")
     d.key in setOf("financial-approvals","financial-reports") && next=="APPROVED" -> setOf("FINANCE_APPROVER","DIRECTOR")
     next in setOf("ACCEPTED","VERIFIED") -> setOf("GRANTS_OFFICER","POST_AWARD_OFFICER","GOVERNANCE_OFFICER","FINANCE_APPROVER")
     else -> d.writers.toSet()
   }
   if(actor.roles.none { it in decisionRoles })return emptyList()
   if(d.key=="financial-approvals") {
     if(r["assigned_user_id"]!=null && r["assigned_user_id"].toString()!=actor.id.toString())return emptyList()
     if(jdbc.queryForObject("select count(*) from operational_history where dataset=? and record_id=? and action='CREATED' and actor_id=?",Long::class.java,d.key,UUID.fromString(r[d.idField].toString()),actor.id)!!>0L)return emptyList()
   }
   if(actor.roles==setOf("RESEARCHER") && runCatching {ownerCheck(d,r,actor)}.isFailure)return emptyList()
   return (listOf(next)+d.states.filter { it in setOf("RETURNED","REJECTED","CANCELLED","REQUIRES_ACTION","REQUIRES_REVIEW") }).distinct()
 }
 private fun ownerCheck(d:DomainDefinition,r:Map<String,Any?>,actor:GrantUser) {
   if(actor.roles!=setOf("RESEARCHER")) return
   if(d.key=="awards" && r["principal_investigator_id"]?.toString()==actor.id.toString())return
   if(d.key=="researchers") { if(r["user_id"]?.toString()!=actor.id.toString()) throw ResponseStatusException(HttpStatus.FORBIDDEN,"You can edit only your own researcher profile");return }
   val awardId=r["award_id"]?.toString()
   if(awardId!=null && jdbc.queryForObject("select count(*) from awards where id=? and principal_investigator_id=?",Long::class.java,UUID.fromString(awardId),actor.id)==1L) return
   if(r["owner_user_id"]?.toString()==actor.id.toString() || r["user_id"]?.toString()==actor.id.toString()) return
   if(d.key=="support") return
   throw ResponseStatusException(HttpStatus.FORBIDDEN,"This record is outside your assigned work")
 }
 @GetMapping("/form-visibility") fun visibility():Map<String,Any> = mapOf("hiddenPages" to catalog.hiddenPages(identity.current().roles))
 @GetMapping("/catalog") fun definitions():List<DomainDefinition> {val actor=identity.current();return catalog.definitions.filter { runCatching { check(it) }.isSuccess }}
 @GetMapping("/{key}") fun list(@PathVariable key:String,@RequestParam(defaultValue="") q:String,@RequestParam(defaultValue="") status:String,@RequestParam(defaultValue="0") page:Int,@RequestParam(defaultValue="25") size:Int,@RequestParam(defaultValue="") sort:String,@RequestParam(defaultValue="asc") direction:String):Map<String,Any> {
   val d=catalog.get(key);val actor=check(d);require(page>=0 && size in 1..100)
   val term="%${q.take(200)}%";val search=mutableListOf("to_jsonb(t)::text ilike ?");val args=mutableListOf<Any>(term)
   if(q.isNotBlank())d.fields.filter {it.type=="reference"}.forEach {field ->
     val target=when(field.reference){
       "entity" -> "(select id,name,'PARTNER' kind from partners union all select id,name,'SUPPLIER' kind from suppliers)" to "case when linked.kind=t.entity_type then linked.name else '' end"
       "subawards" -> "award_partners" to "(select concat_ws(' ',a.reference,a.title,p.name) from awards a join partners p on p.id=linked.partner_id where a.id=linked.award_id)"
       "awards" -> "awards" to "concat_ws(' ',linked.reference,linked.title)"
       "applications" -> "applications" to "concat_ws(' ',linked.reference,linked.title)"
       "users" -> "users" to "linked.display_name"
       "roles" -> "roles" to "concat_ws(' ',linked.name,linked.code)"
       "partners" -> "partners" to "linked.name"
       "funders" -> "funders" to "linked.name"
       "suppliers" -> "suppliers" to "linked.name"
       else -> null
     }
     if(target!=null){search.add("exists(select 1 from ${target.first} linked where linked.id=t.${field.name} and ${target.second} ilike ?)");args.add(term)}
   }
   val where=mutableListOf("("+search.joinToString(" or ")+")")
   if(actor.roles==setOf("RESEARCHER")) {
     when {
       d.key=="awards" -> {where.add("t.principal_investigator_id=?");args.add(actor.id)}
       d.fields.any { it.name=="award_id" } -> {where.add("exists(select 1 from awards a where a.id=t.award_id and a.principal_investigator_id=?)");args.add(actor.id)}
       d.key=="declarations" -> {where.add("t.user_id=?");args.add(actor.id)}
     }
   }
   if(status.isNotBlank() && d.stateField.isNotBlank()){where.add("t.${d.stateField}=?");args.add(status)}
   val condition=where.joinToString(" and ")
   val sortField=sort.ifBlank { d.idField };if(sortField !in d.fields.map { it.name }+d.columns+d.idField || direction !in setOf("asc","desc"))fail("Choose an available column and sort direction")
   val total=jdbc.queryForObject("select count(*) from ${d.table} t where $condition",Long::class.java,*args.toTypedArray())?:0
   val records=jdbc.queryForList("select t.*,md5(to_jsonb(t)::text) _etag from ${d.table} t where $condition order by t.$sortField $direction nulls last, t.${d.idField} limit ? offset ?",*(args+listOf(size,page*size)).toTypedArray()).map(::row).map { it+mapOf("_actions" to allowedStates(d,it,actor)) }
   return mapOf("definition" to d,"rows" to records,"total" to total,"page" to page,"size" to size,"canEdit" to canEdit(d,actor),"canCreate" to (d.create && canEdit(d,actor)),"actorId" to actor.id)
 }

 @GetMapping("/awards/{id}/handover") fun awardHandover(@PathVariable id:UUID):Map<String,Any?> {
   val d=catalog.get("awards");val actor=check(d);val award=record(d,id);ownerCheck(d,award,actor)
   return mapOf("applicationId" to award["application_id"],"handover" to jdbc.queryForList("select h.*,p.display_name prepared_by_name,a.display_name accepted_by_name from award_handovers h left join users p on p.id=h.prepared_by left join users a on a.id=h.accepted_by where h.award_id=?",id).firstOrNull()?.let(::row))
 }
 @PutMapping("/awards/{id}/handover") @Transactional fun saveAwardHandover(@PathVariable id:UUID,@RequestBody request:org.nhrc.grants.pregrant.HandoverUpdate):Map<String,Any> {
   val d=catalog.get("awards");val actor=check(d);if(!catalog.allowed(d.key,actor.roles,true))throw ResponseStatusException(HttpStatus.FORBIDDEN,"This award form is restricted")
   val award=record(d,id,true);val application=award["application_id"]?.toString()?:fail("This award has no linked application handover")
   return preGrant.handover(UUID.fromString(application),request.copy(awardId=id))
 }
 @GetMapping("/awards/{id}/closure-readiness") fun closureReadiness(@PathVariable id:UUID):Map<String,Any> {
   val d=catalog.get("awards");val actor=check(d);ownerCheck(d,record(d,id),actor)
   val checks=closure.checks(id);return mapOf("checks" to checks,"ready" to checks.all {it["complete"]==true})
 }
 @GetMapping("/{key}/{id}") fun linkedRecord(@PathVariable key:String,@PathVariable id:UUID):Map<String,Any?> {val d=catalog.get(key);val actor=check(d);val result=record(d,id);ownerCheck(d,result,actor);return result+mapOf("_actions" to allowedStates(d,result,actor))}
 @GetMapping("/{key}/views") fun views(@PathVariable key:String):List<Map<String,Any?>> {val actor=check(catalog.get(key));return jdbc.queryForList("select id,name,settings,updated_at from personal_register_views where dataset=? and user_id=? order by name",key,actor.id).map(::row)}
 @PostMapping("/{key}/views") fun saveView(@PathVariable key:String,@RequestBody body:Map<String,Any?>):Map<String,Any?> {
   val d=catalog.get(key);val actor=check(d);val name=body["name"]?.toString()?.trim().orEmpty();if(name.length !in 1..80)fail("Name this view using 1–80 characters")
   val settings=body["settings"] as? Map<*,*> ?: fail("Choose view settings")
   val columns=(settings["columns"] as? List<*>)?.map {it.toString()} ?: d.columns
   if(columns.isEmpty() || columns.size>60 || columns.any {it !in d.fields.map {f->f.name}+d.columns})fail("Choose available columns")
   val sort=settings["sort"]?.toString().orEmpty();val direction=settings["direction"]?.toString()?:"asc"
   if(sort.isNotBlank() && sort !in d.fields.map {it.name}+d.columns+d.idField || direction !in setOf("asc","desc"))fail("Choose valid sorting")
   val clean=mapOf("columns" to columns.distinct(),"q" to settings["q"]?.toString().orEmpty().take(200),"status" to settings["status"]?.toString().orEmpty().take(80),"sort" to sort,"direction" to direction)
   val id=UUID.randomUUID();jdbc.update("insert into personal_register_views(id,user_id,dataset,name,settings) values (?,?,?,?,?::jsonb) on conflict(user_id,dataset,name) do update set settings=excluded.settings,updated_at=now()",id,actor.id,key,name,mapper.writeValueAsString(clean))
   return views(key).first {it["name"]==name}
 }
 @DeleteMapping("/{key}/views/{viewId}") fun deleteView(@PathVariable key:String,@PathVariable viewId:UUID):Map<String,Any> {val actor=check(catalog.get(key));if(jdbc.update("delete from personal_register_views where id=? and dataset=? and user_id=?",viewId,key,actor.id)==0)throw ResponseStatusException(HttpStatus.NOT_FOUND,"Saved view not found");return mapOf("removed" to true)}
 @GetMapping("/{key}/{id}/discussion") fun discussion(@PathVariable key:String,@PathVariable id:UUID):List<Map<String,Any?>> {val d=catalog.get(key);val actor=check(d);ownerCheck(d,record(d,id),actor);return jdbc.queryForList("select n.id,n.body,n.resolved_at,n.created_at,n.author_id,u.display_name author from record_discussions n join users u on u.id=n.author_id where n.dataset=? and n.record_id=? order by n.created_at,n.id",key,id).map(::row)}
 @PostMapping("/{key}/{id}/discussion") @Transactional fun addDiscussion(@PathVariable key:String,@PathVariable id:UUID,@RequestBody body:Map<String,String>):Map<String,Any?> {val d=catalog.get(key);val actor=check(d,true);ownerCheck(d,record(d,id),actor);val text=body["body"]?.trim().orEmpty();if(text.length !in 1..8000)fail("Enter a discussion note of 1–8,000 characters");val noteId=UUID.randomUUID();jdbc.update("insert into record_discussions(id,dataset,record_id,author_id,body) values (?,?,?,?,?)",noteId,key,id,actor.id,text);history(d,id,actor,"DISCUSSION_ADDED",emptyMap(),emptyMap(),"Discussion note added");return mapOf("id" to noteId)}
 @PostMapping("/{key}/{id}/discussion/{noteId}/resolve") @Transactional fun resolveDiscussion(@PathVariable key:String,@PathVariable id:UUID,@PathVariable noteId:UUID):Map<String,Any> {val d=catalog.get(key);val actor=check(d,true);ownerCheck(d,record(d,id),actor);val count=jdbc.update("update record_discussions set resolved_at=now() where id=? and dataset=? and record_id=? and author_id=? and resolved_at is null",noteId,key,id,actor.id);if(count==0)throw ResponseStatusException(HttpStatus.FORBIDDEN,"Only the author can resolve an open note");history(d,id,actor,"DISCUSSION_RESOLVED",emptyMap(),emptyMap(),"Author resolved a discussion note");return mapOf("resolved" to true)}

 @GetMapping("/{key}/{id}/history") fun history(@PathVariable key:String,@PathVariable id:UUID):List<Map<String,Any?>> {val d=catalog.get(key);val actor=check(d);ownerCheck(d,record(d,id),actor);return jdbc.queryForList("select h.action,h.note,h.occurred_at,u.display_name actor from operational_history h left join users u on u.id=h.actor_id where h.dataset=? and h.record_id=? order by occurred_at desc",key,id).map(::row)}
 @GetMapping("/options/{key}") fun options(@PathVariable key:String):List<Map<String,Any?>> {
   identity.current()
   if(key !in setOf("users","roles","applications") && !catalog.allowed(key,identity.current().roles,false))throw ResponseStatusException(HttpStatus.FORBIDDEN,"This form is restricted")
   val table=when(key){"users" -> "users";"roles" -> "roles";"applications" -> "applications";else -> catalog.get(key).table}
   if(key=="users") return jdbc.queryForList("select id,display_name,email,display_name label,active from users where active=true order by display_name").map(::row)
   if(key=="roles") return jdbc.queryForList("select id,name label,code from roles order by name").map(::row)
   if(key=="applications") return jdbc.queryForList("select id,reference||' · '||title label,stage from applications order by reference limit 1000").map(::row)
   if(key=="subawards"){check(catalog.get(key));return jdbc.queryForList("select ap.id,a.reference||' · '||p.name label from award_partners ap join awards a on a.id=ap.award_id join partners p on p.id=ap.partner_id order by a.reference,p.name").map(::row)}
   val d=catalog.get(key);check(d)
   val columns=d.fields.map { it.name }
   val title=listOf("name","title","reference","item_description","description","service_name").firstOrNull { it in columns } ?: d.idField
   return jdbc.queryForList("select ${d.idField} id,coalesce($title::text,${d.idField}::text) label from $table order by $title limit 1000").map(::row)
 }
 private fun convert(f:DomainField,v:Any?):Any? {
   if(f.dbType=="_text" && (v is List<*> && v.isEmpty() || v is String && v.isBlank()))return ""
   if(v==null || v is String && v.isBlank()) {if(f.required) fail("${f.label} is required");return null}
   val text=v.toString().trim()
   if(text.length>f.maxLength) fail("${f.label} exceeds ${f.maxLength} characters")
   if(f.choices.isNotEmpty() && text !in f.choices) fail("Choose a valid ${f.label.lowercase()}")
   if(f.type=="email" && !text.matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))) fail("Enter a valid ${f.label.lowercase()}")
   if(f.type=="url" && !text.startsWith("https://") && !text.startsWith("http://")) fail("${f.label} must be a complete web address")
   if(f.type=="document" && !text.startsWith("doc:") && !text.startsWith("https://")) fail("Upload ${f.label.lowercase()} or provide a verified HTTPS evidence link")
   if(f.type=="document" && text.startsWith("doc:") && jdbc.queryForObject("select count(*) from managed_documents where id=?",Long::class.java,UUID.fromString(text.removePrefix("doc:")))!=1L) fail("Document was not found")
   return try { when(f.dbType) {
     "uuid" -> UUID.fromString(text)
     "numeric" -> BigDecimal(text).also { if(it.signum()<0) fail("${f.label} cannot be negative");if(it.precision()>19) fail("${f.label} is too large") }
     "int4" -> text.toInt().also { if(it<0) fail("${f.label} cannot be negative") }
     "int8" -> text.toLong()
     "date" -> LocalDate.parse(text)
     "timestamptz" -> OffsetDateTime.parse(if(text.length==16) text+":00Z" else if(text.length==19) text+"Z" else text)
     "bool" -> if(v is Boolean) v else text.toBooleanStrict()
     "_text" -> (if(v is List<*>) v.map { it.toString().trim() } else text.split(',').map { it.trim() }).filter { it.isNotBlank() }.joinToString("|")
     "jsonb" -> mapper.writeValueAsString(mapper.readTree(if(v is String) v else mapper.writeValueAsString(v))).also { if(!mapper.readTree(it).isObject) fail("${f.label} must be a JSON object") }
     else -> text
   }} catch(ex:ResponseStatusException){throw ex} catch(ex:Exception){fail("${f.label} has an invalid value")}
 }
 private fun validate(d:DomainDefinition,values:Map<String,Any?>,old:Map<String,Any?> = emptyMap()) {
   val v=old+values
   for((from,to) in listOf("start_date" to "end_date","effective_date" to "expiry_date","period_start" to "period_end","valid_from" to "valid_until","starts_at" to "expires_at","forecast_date" to "period_end","advanced_date" to "retirement_due_date")) {
     if(v[from]!=null && v[to]!=null && v[from].toString()>v[to].toString()) fail("The end date cannot precede the start date")
   }
   fun n(k:String)=v[k]?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
   if(d.key=="awards" && n("nhrc_allocation")+n("partner_allocation")>n("total_award")) fail("NHRC and partner allocations exceed the signed award")
   if(d.key=="forms") {
     val schema=mapper.readTree(v["schema_json"].toString());val fields=schema.path("fields")
     if(!fields.isArray || fields.size()==0 || fields.size()>100 || fields.any {it.path("label").asText().isBlank() || it.path("name").asText().isBlank() || it.path("type").asText() !in setOf("text","textarea","number","date","select","checkbox")}) fail("Define 1–100 labelled form questions with valid answer formats")
     if(fields.map {it.path("name").asText()}.distinct().size!=fields.size())fail("Each form question needs a unique identity")
     fields.forEach { q ->
       if('_' in q.path("label").asText() || q.path("label").asText().length>180 || q.path("help").asText().length>2000)fail("Use readable question labels and guidance within their length limits")
       if(q.path("type").asText()=="select" && (!q.path("choices").isArray || q.path("choices").size()==0 || q.path("choices").any {it.asText().isBlank()} || q.path("choices").map {it.asText()}.distinct().size!=q.path("choices").size()))fail("Choice questions need a list of distinct, non-empty responses")
     }
   }
   if(d.key=="awards" && old.isNotEmpty()) {
     val allocated=jdbc.queryForObject("select coalesce(sum(approved_allocation),0) from award_partners where award_id=?",BigDecimal::class.java,UUID.fromString(old["id"].toString()))?:BigDecimal.ZERO
     if(n("partner_allocation")<allocated)fail("The parent partner budget cannot be lower than signed subaward allocations")
   }
   if(d.key=="subawards") {
     val awardId=UUID.fromString(v["award_id"].toString())
     val award=jdbc.queryForMap("select status,currency,partner_allocation from awards where id=? for update",awardId)
     if(old.isNotEmpty() && (old["award_id"].toString()!=v["award_id"].toString() || old["partner_id"].toString()!=v["partner_id"].toString()))fail("Keep the original award and partner; create a separate allocation for another partner")
     val used=jdbc.queryForObject("select coalesce(sum(approved_allocation),0) from award_partners where award_id=? and id<>?",BigDecimal::class.java,awardId,old["id"]?.toString()?.let(UUID::fromString) ?: UUID(0,0))?:BigDecimal.ZERO
     if(used+n("approved_allocation")>(award["partner_allocation"] as BigDecimal))fail("Partner allocations exceed the parent award partner budget")
     val advanced=if(old.isEmpty())BigDecimal.ZERO else jdbc.queryForObject("select coalesce(sum(amount_advanced),0) from partner_advances where award_partner_id=?",BigDecimal::class.java,UUID.fromString(old["id"].toString()))?:BigDecimal.ZERO
     if(n("approved_allocation")<advanced)fail("The signed allocation cannot be lower than advances already recorded")
     if(award["status"]=="CLOSED" && (old.isEmpty() || n("approved_allocation").compareTo(old["approved_allocation"].toString().toBigDecimal())!=0 || old["currency"]!=v["currency"]))fail("Financial terms of a closed award cannot be changed")
     if(v["agreement_status"]=="ACTIVE") {
       val partner=jdbc.queryForMap("select due_diligence_status,due_diligence_valid_until from partners where id=?",UUID.fromString(v["partner_id"].toString()))
       if(partner["due_diligence_status"]!="APPROVED" || partner["due_diligence_valid_until"]?.toString()?.let {LocalDate.parse(it).isBefore(LocalDate.now())}==true)fail("Complete current approved partner due diligence before activating the subaward")
       if(jdbc.queryForObject("select count(*) from contracts where award_id=? and partner_id=? and contract_type='SUBAWARD' and status='EXECUTED'",Long::class.java,awardId,UUID.fromString(v["partner_id"].toString()))==0L)fail("Record the executed partner subaward agreement before activation")
     }
   }
   if(d.key=="advances") {
     val allocationId=UUID.fromString(v["award_partner_id"].toString())
     if(old.isNotEmpty() && old["award_partner_id"].toString()!=allocationId.toString())fail("Keep the advance linked to its original award and partner")
     val allocation=jdbc.queryForMap("select ap.*,a.status award_status,a.currency award_currency,p.due_diligence_status,p.due_diligence_valid_until from award_partners ap join awards a on a.id=ap.award_id join partners p on p.id=ap.partner_id where ap.id=?",allocationId)
     val used=jdbc.queryForObject("select coalesce(sum(amount_advanced),0) from partner_advances where award_partner_id=? and id<>?",BigDecimal::class.java,allocationId,old["id"]?.toString()?.let(UUID::fromString) ?: UUID(0,0))?:BigDecimal.ZERO
     if(used+n("amount_advanced")>(allocation["approved_allocation"] as BigDecimal))fail("Total partner advances exceed the signed subaward allocation")
     val increasing=old.isEmpty() || n("amount_advanced")>old["amount_advanced"].toString().toBigDecimal()
     if(increasing) {
       if(allocation["award_status"]!="ACTIVE")fail("Activate the parent award before recording a new or increased advance")
       if(allocation["agreement_status"]!="ACTIVE")fail("Activate the signed partner agreement before recording an advance")
       if(allocation["due_diligence_status"]!="APPROVED" || allocation["due_diligence_valid_until"]?.toString()?.let {LocalDate.parse(it).isBefore(LocalDate.now())}==true)fail("Current approved partner due diligence is required before an advance")
     }
     if(old["status"]=="RETIRED" && n("amount_accounted").compareTo(n("amount_advanced"))!=0)fail("A retired advance must remain fully accounted for")
   }
   if(d.key=="advances" && n("amount_accounted")>n("amount_advanced")) fail("Accounted amount exceeds the advance")
   if(d.key=="receipts" && v["received_amount"]!=null && v["received_date"]==null) fail("Record the actual receipt date")
   if(d.key=="expenditure" && v["reconciled"]==true && v["finance_reference"].isNullOrBlankValue()) fail("A ledger reference is required for reconciled expenditure")
   if(d.key=="compliance" && v["award_id"]==null && v["application_id"]==null) fail("Link the approval to an application or award")
   if(d.key=="delegations" && v["delegator_id"].toString()==v["delegate_id"].toString()) fail("The substantive and acting officer must be different")
   val linkedAward=v["award_id"] ?: if(d.key=="advances" && v["award_partner_id"]!=null)jdbc.queryForObject("select award_id from award_partners where id=?",UUID::class.java,UUID.fromString(v["award_partner_id"].toString())) else null
   if(linkedAward!=null && v["currency"]!=null) {
     val currency=jdbc.queryForObject("select currency from awards where id=?",String::class.java,UUID.fromString(linkedAward.toString()))
     if(currency!=v["currency"].toString()) fail("Use the linked award currency ($currency)")
   }
   if(linkedAward!=null && d.key in setOf("receipts","expenditure","commitments","advances") && jdbc.queryForObject("select status from awards where id=? for update",String::class.java,UUID.fromString(linkedAward.toString()))=="CLOSED")
     throw ResponseStatusException(HttpStatus.CONFLICT,"This award is closed. Financial postings and changes are blocked to preserve its verified closure.")
   if(d.key=="awards" && old["status"]=="CLOSED" && listOf("total_award","nhrc_allocation","partner_allocation","currency").any {values.containsKey(it)&&values[it]?.toString()?.toBigDecimalOrNull()?.compareTo(old[it]?.toString()?.toBigDecimalOrNull()?:BigDecimal.ZERO)!=0 && values[it]?.toString()!=old[it]?.toString()})
     throw ResponseStatusException(HttpStatus.CONFLICT,"The financial terms of a closed award cannot be changed.")
   if(d.key=="orders") {
     val req=jdbc.queryForMap("select status,currency,amount from procurement_requisitions where id=?",UUID.fromString(v["requisition_id"].toString()))
     if(req["status"]!="APPROVED") fail("Approve the requisition before issuing a purchase order")
     if(req["currency"]!=v["currency"] || n("amount")>(req["amount"] as BigDecimal)) fail("Order currency and amount must fit the approved requisition")
     if(v["supplier_id"]==null) fail("Select the approved supplier")
     if(jdbc.queryForObject("select due_diligence_status from suppliers where id=?",String::class.java,UUID.fromString(v["supplier_id"].toString()))!="APPROVED") fail("Complete supplier due diligence before issuing an order")
   }
 }
 private fun updatePosition(d:DomainDefinition,old:Map<String,Any?>,new:Map<String,Any?>) {
   if(d.key=="awards") {
     val allocation=new["nhrc_allocation"]?.toString()?.toBigDecimalOrNull()?:BigDecimal.ZERO
     jdbc.update("insert into award_financial_positions(award_id,approved_budget) values (?,?) on conflict(award_id) do update set approved_budget=excluded.approved_budget,updated_at=now()",UUID.fromString(new["id"].toString()),allocation)
     return
   }
   if(d.key !in setOf("receipts","expenditure","commitments")) return
   fun contribution(r:Map<String,Any?>):BigDecimal {
     val key=if(d.key=="receipts") "received_amount" else "amount"
     if(d.key=="expenditure" && r["reconciled"]!=true || d.key=="commitments" && r["status"]!="OPEN") return BigDecimal.ZERO
     return r[key]?.toString()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
   }
   val column=when(d.key){"receipts" -> "cash_received";"expenditure" -> "expenditure";else -> "commitments"}
   val award=UUID.fromString(new["award_id"].toString())
   if(old.isNotEmpty() && old["award_id"].toString()!=new["award_id"].toString()) fail("A financial transaction cannot be moved to another award")
   val delta=contribution(new)-contribution(old)
   jdbc.update("insert into award_financial_positions(award_id) values (?) on conflict do nothing",award)
   jdbc.queryForMap("select * from award_financial_positions where award_id=? for update",award)
   jdbc.update("update award_financial_positions set $column=$column+?,updated_at=now() where award_id=?",delta,award)
 }
 private fun history(d:DomainDefinition,id:UUID,actor:GrantUser,action:String,old:Map<String,Any?>,new:Map<String,Any?>,note:String?) {
   jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,before_data,after_data,note) values (?,?,?,?,?::jsonb,?::jsonb,?)",d.key,id,actor.id,action,mapper.writeValueAsString(old),mapper.writeValueAsString(new),note)
 }
 @PostMapping("/{key}") @Transactional fun create(@PathVariable key:String,@RequestBody request:RecordWrite):Map<String,Any?> = save(key,null,request)
 @PutMapping("/{key}/{id}") @Transactional fun edit(@PathVariable key:String,@PathVariable id:UUID,@RequestBody request:RecordWrite):Map<String,Any?> = save(key,id,request)
 private fun save(key:String,id:UUID?,request:RecordWrite):Map<String,Any?> {
   val d=catalog.get(key);val actor=check(d,true)
   if(d.key in setOf("events","positions"))throw ResponseStatusException(HttpStatus.FORBIDDEN,if(d.key=="positions")"Financial positions are calculated from the award, receipts, reconciled expenditure and commitments. Update the underlying records instead." else "Operational events cannot be overwritten")
   val hash=MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(java.util.TreeMap(request.values))).joinToString(""){"%02x".format(it)}
   if(request.requestId!=null) {
     jdbc.queryForList("select pg_advisory_xact_lock(?)",request.requestId.mostSignificantBits)
     val receipt=jdbc.queryForList("select request_hash,response::text response from operational_request_receipts where request_id=? and dataset=? and actor_id=?",request.requestId,key,actor.id).firstOrNull()
     if(receipt!=null) {
       if(receipt["request_hash"]!=hash)throw ResponseStatusException(HttpStatus.CONFLICT,"This request identifier has already been used for different changes")
       return mapper.readValue(receipt["response"].toString(),object:TypeReference<Map<String,Any?>>() {})
     }
   }
   if(id==null && !d.create) fail("Create this record through its linked grant workflow")
   // Serialise partner budget writes by parent award before locking individual records.
   if(d.key in setOf("awards","subawards","advances")) {
     val parent=when(d.key){
       "awards"->id
       "subawards"->request.values["award_id"]?.toString()?.let(UUID::fromString) ?: id?.let {jdbc.queryForObject("select award_id from award_partners where id=?",UUID::class.java,it)}
       else->{val allocation=request.values["award_partner_id"]?.toString()?.let(UUID::fromString) ?: id?.let {jdbc.queryForObject("select award_partner_id from partner_advances where id=?",UUID::class.java,it)};allocation?.let {jdbc.queryForObject("select award_id from award_partners where id=?",UUID::class.java,it)}}
     }
     if(parent!=null)jdbc.queryForList("select pg_advisory_xact_lock(?)",parent.mostSignificantBits)
   }
   val old=if(id!=null) record(d,id,true) else emptyMap()
   if(id!=null && request.etag!=old["_etag"]) throw ResponseStatusException(HttpStatus.CONFLICT,"This record changed while you were editing. Refresh it before saving.")
   if(id!=null) ownerCheck(d,old,actor)
   if(request.values.keys.any { name -> d.fields.none { it.name==name } }) fail("The form includes a field that cannot be edited")
   val values=linkedMapOf<String,Any?>()
   d.fields.forEach { f -> if(request.values.containsKey(f.name)) values[f.name]=convert(f,request.values[f.name] ?: if(id==null) f.defaultValue else null) else if(id==null && f.required) values[f.name]=convert(f,f.defaultValue) }
   if(id==null && d.key !in setOf("support","opportunities")) ownerCheck(d,values,actor)
   d.fields.filter {it.required}.forEach {f -> if((values[f.name] ?: old[f.name]).isNullOrBlankValue())fail("${f.label} is required")}
   if(d.key=="forms" && id!=null) {
     values["version_no"]=(old["version_no"] as Number).toInt()+1
     if(values.containsKey("schema_json") && jdbc.queryForObject("select count(*) from institutional_form_responses where form_id=?",Long::class.java,id)!!>0L) {
       val previous=QuestionnaireSchema.fields(mapper.readTree(old["schema_json"].toString()),mapper)
       val next=mapper.readTree(values["schema_json"].toString()).path("fields")
       require(previous.all {p -> next.any {n -> n.path("name")==p.path("name") && n.path("type")==p.path("type") && (p.path("type").asText()!="select" || p.path("choices").all {choice -> n.path("choices").contains(choice)})}}){"Saved responses exist. Preserve answered questions and choices, or copy this as a new form before removing them"}
     }
   }
   if(id==null)values.entries.removeIf {it.value==null}
   if(values.isEmpty()) fail("No changes were provided")
   if(d.key=="reconciliations" && values["ledger_amount"]!=null && values["platform_amount"]!=null) values["variance"]=(values["ledger_amount"] as BigDecimal)-(values["platform_amount"] as BigDecimal)
   validate(d,values,old)
   val rid=id ?: UUID.randomUUID()
   fun expression(name:String)=when(d.fields.find { it.name==name }?.dbType){"_text" -> "string_to_array(?, '|')";"jsonb" -> "?::jsonb";else -> "?"}
   if(id==null) jdbc.update("insert into ${d.table} (${d.idField},${values.keys.joinToString(",")}) values (?,${values.keys.joinToString(",") { expression(it) }})",*(listOf(rid)+values.values).toTypedArray())
   else jdbc.update("update ${d.table} set ${values.keys.joinToString(",") { "$it=${expression(it)}" }} where ${d.idField}=?",*(values.values+rid).toTypedArray())
   val new=record(d,rid);updatePosition(d,old,new);history(d,rid,actor,if(id==null) "CREATED" else "UPDATED",old,new,request.note)
   if(request.requestId!=null)jdbc.update("insert into operational_request_receipts(request_id,dataset,actor_id,request_hash,response) values (?,?,?,?,?::jsonb)",request.requestId,key,actor.id,hash,mapper.writeValueAsString(new))
   return new
 }
 fun preflight(key:String,values:Map<String,Any?>):Map<String,Any?> {
   val d=catalog.get(key);check(d,true);if(!d.create) fail("This register cannot be imported")
   val clean=linkedMapOf<String,Any?>()
   d.fields.forEach { f ->clean[f.name]=convert(f,values[f.name] ?: f.defaultValue) }
   validate(d,clean)
   return values
 }
 @PostMapping("/{key}/{id}/action") @Transactional fun action(@PathVariable key:String,@PathVariable id:UUID,@RequestBody r:RecordAction):Map<String,Any?> {
   val d=catalog.get(key);val actor=check(d);if(!catalog.allowed(d.key,actor.roles,true))throw ResponseStatusException(HttpStatus.FORBIDDEN,"This form is retired or restricted");val old=record(d,id,true);ownerCheck(d,old,actor)
   if(r.state !in allowedStates(d,old,actor))throw ResponseStatusException(HttpStatus.FORBIDDEN,"This workflow decision requires the assigned authority")
   if(old["_etag"]!=r.etag) throw ResponseStatusException(HttpStatus.CONFLICT,"Record changed. Refresh before recording this action.")
   if(r.note.isBlank()) fail("Record the decision reason or evidence")
   if(r.state !in d.states || d.stateField.isBlank()) fail("This state is not valid for the workflow")
   val current=old[d.stateField]?.toString() ?: ""
   val next=d.states.getOrNull(d.states.indexOf(current)+1)
   if(r.state!=next && r.state !in setOf("RETURNED","REJECTED","CANCELLED","REQUIRES_ACTION","REQUIRES_REVIEW")) throw ResponseStatusException(HttpStatus.CONFLICT,"Follow the next workflow step")
   if(current==r.state || current in setOf("CLOSED","CANCELLED","REJECTED","RETIRED")) throw ResponseStatusException(HttpStatus.CONFLICT,"This workflow is already closed")
   if(d.key=="financial-approvals" && r.state=="APPROVED" && actor.roles.none { it in setOf("FINANCE_APPROVER","DIRECTOR") }) throw ResponseStatusException(HttpStatus.FORBIDDEN,"A Finance Approver must decide this request")
   if(d.key=="financial-approvals" && old["assigned_user_id"]!=null && old["assigned_user_id"].toString()!=actor.id.toString()) throw ResponseStatusException(HttpStatus.FORBIDDEN,"This decision is assigned to another officer")
   val evidence=listOf("storage_key","evidence_storage_key","certificate_storage_key").firstOrNull { field -> d.fields.any { it.name==field } }
   if(r.state in setOf("EXECUTED","SUBMITTED","ACCEPTED","COMPLETE","VERIFIED") && evidence!=null && old[evidence].isNullOrBlankValue()) fail("Attach the supporting document before recording this state")
   if(d.key=="reconciliations" && r.state=="RECONCILED" && old["variance"]?.toString()?.toBigDecimalOrNull()?.compareTo(BigDecimal.ZERO)!=0) fail("Resolve the reconciliation variance first")
   if(d.key=="diligence" && r.state=="APPROVED" && (old["findings"].isNullOrBlankValue() || old["expires_at"]==null)) fail("Record the findings and validity date before approving due diligence")
   if(d.key=="awards" && r.state=="ACTIVE") {
     if(old["start_date"]==null || old["end_date"]==null || old["principal_investigator_id"]==null) fail("Set award dates and principal investigator before activation")
     if(jdbc.queryForObject("select count(*) from contracts where award_id=? and contract_type='FUNDING' and status='EXECUTED'",Long::class.java,id)==0L) fail("Record the executed funding agreement before activation")
   }
   if(d.key=="awards" && r.state=="CLOSED") {
     val pending=closure.checks(id).filter {it["complete"]!=true}
     if(pending.isNotEmpty())fail("Award closure is blocked: "+pending.joinToString("; "){it["label"].toString()+": "+it["guidance"]})
   }
   if(d.key=="advances" && r.state=="RETIRED" && old["amount_accounted"].toString().toBigDecimal()!=old["amount_advanced"].toString().toBigDecimal())fail("Account for the full advance before recording retirement")
   jdbc.update("update ${d.table} set ${d.stateField}=? where ${d.idField}=?",r.state,id)
   if(d.key=="diligence" && r.state in setOf("APPROVED","CONDITIONAL","REJECTED")) {
     val table=when(old["entity_type"]){"PARTNER" -> "partners";"SUPPLIER" -> "suppliers";else -> fail("Choose a valid organisation category")}
     jdbc.update("update $table set due_diligence_status=?,due_diligence_valid_until=?,due_diligence_review_id=? where id=?",r.state,old["expires_at"]?.let { LocalDate.parse(it.toString()) },id,UUID.fromString(old["entity_id"].toString()))
   }
   if(d.key=="financial-approvals")jdbc.update("update financial_approvals set decided_by=?,decided_at=now(),decision_note=? where id=?",actor.id,r.note,id)
   if(d.key=="financial-reports" && r.state=="APPROVED")jdbc.update("update financial_reports set authorised_by=? where id=?",actor.id,id)
   if(d.key in setOf("reports","financial-reports","deliverables")) {
     if(r.state=="SUBMITTED")jdbc.update("update ${d.table} set submitted_at=now() where id=?",id)
     if(r.state=="ACCEPTED")jdbc.update("update ${d.table} set accepted_at=now() where id=?",id)
   }
   if(d.key=="awards" && r.state=="CLOSED" && old["application_id"]!=null) {
     val application=UUID.fromString(old["application_id"].toString())
     val stage=jdbc.queryForObject("select stage from applications where id=? for update",String::class.java,application)
     if(stage!="CLOSED") {
       jdbc.update("update applications set stage='CLOSED',updated_at=now() where id=?",application)
       jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,changed_by,note) values (?,?,'CLOSED',?,?)",application,stage,actor.id,"Linked award closed: "+r.note)
     }
   }
   if(d.key=="closeout" && r.state=="COMPLETE")jdbc.update("update closeout_items set completed_at=now() where id=?",id)
   if(d.key=="diligence" && r.state in setOf("APPROVED","CONDITIONAL","REJECTED"))jdbc.update("update due_diligence_reviews set reviewed_by=?,reviewed_at=now() where id=?",actor.id,id)
   val new=record(d,id);updatePosition(d,old,new);history(d,id,actor,"STATE_CHANGED",old,new,r.note);return new
 }
 private fun Any?.isNullOrBlankValue()=this==null || this.toString().isBlank()
 @PostMapping("/documents") @Transactional fun upload(@RequestParam file:MultipartFile):Map<String,Any> {
   val actor=identity.current();if(actor.roles==setOf("AUDITOR")) throw ResponseStatusException(HttpStatus.FORBIDDEN,"Auditors cannot upload evidence")
   if(file.isEmpty || file.size>10*1024*1024) fail("Select a document up to 10 MB")
   val name=(file.originalFilename ?: "document").substringAfterLast('/').substringAfterLast('\\').replace(Regex("[\\r\\n]"),"").take(255)
   if(name.substringAfterLast('.').lowercase() !in setOf("pdf","docx","xlsx","csv","txt","png","jpg","jpeg")) fail("Use a PDF, Word, Excel, CSV, text or image document")
   val bytes=file.bytes;val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)};val id=UUID.randomUUID()
   jdbc.update("insert into managed_documents(id,file_name,media_type,size_bytes,sha256,uploaded_by,content) values (?,?,?,?,?,?,?)",id,name,file.contentType ?: "application/octet-stream",bytes.size,sha,actor.id,bytes)
   return mapOf("id" to id,"storageKey" to "doc:$id","name" to name,"size" to bytes.size,"sha256" to sha)
 }
 @GetMapping("/documents/{id}") fun download(@PathVariable id:UUID,@RequestParam(defaultValue="auto") format:String):ResponseEntity<ByteArray> {
   val actor=identity.current();val d=jdbc.queryForMap("select file_name,content,uploaded_by from managed_documents where id=?",id)
   var allowed=d["uploaded_by"]==actor.id
   if(!allowed) {
     catalog.definitions.filter { runCatching {check(it)}.isSuccess }.forEach { domain ->
       domain.fields.filter { it.type=="document" }.forEach { field ->
         jdbc.queryForList("select * from ${domain.table} where ${field.name}=? limit 20","doc:$id").map(::row).forEach { linked ->if(actor.roles!=setOf("RESEARCHER") || runCatching {ownerCheck(domain,linked,actor)}.isSuccess)allowed=true }
       }
     }
     val links=jdbc.queryForList("select application_id from application_documents where storage_key=?","doc:$id")
     if(links.isNotEmpty() && actor.roles.any { it in setOf("GRANTS_OFFICER","DIRECTOR","ADMIN","SUPERADMIN","AUDITOR") })allowed=true
     if(!allowed && actor.roles.contains("RESEARCHER"))links.forEach { link ->if(jdbc.queryForObject("select count(*) from applications a where a.id=? and (a.lead_researcher_id=? or a.owner_user_id=? or exists(select 1 from application_reviews r where r.application_id=a.id and r.reviewer_id=?))",Long::class.java,link["application_id"],actor.id,actor.id,actor.id)==1L)allowed=true }
   }
   if(!allowed)throw ResponseStatusException(HttpStatus.FORBIDDEN,"This document is outside your assigned work")
   if(format !in setOf("auto","original"))fail("Choose the standard download or original upload")
   return DocumentDownloads.response(d["file_name"].toString(),d["content"] as ByteArray,format=="original")
 }
}



