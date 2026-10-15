package org.nhrc.grants.operations

import com.fasterxml.jackson.databind.ObjectMapper
import org.nhrc.grants.config.PlatformAccess
import org.nhrc.grants.config.PlatformIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.transaction.annotation.Transactional
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class ManagedField(val name:String,val label:String,val help:String="",val section:String="Record details",val required:Boolean=false,val hidden:Boolean=false)
data class ManagedForm(val title:String,val description:String,val enabled:Boolean,val readers:List<String>,val writers:List<String>,val fields:List<ManagedField>,val revision:Long,val reason:String)

@RestController
@RequestMapping("/api/operations/form-administration")
class FormsAdministrationController(private val jdbc:JdbcTemplate,private val catalog:OperationalCatalog,private val identity:PlatformIdentity,private val mapper:ObjectMapper) {
 private fun admin(){if(identity.current().roles.none {it in setOf("ADMIN","SUPERADMIN")})throw ResponseStatusException(HttpStatus.FORBIDDEN,"Form administration requires an Administrator")}
 @GetMapping fun list():List<Map<String,Any>> {admin();return catalog.baseDefinitions.map { d ->
   val roles=jdbc.queryForList("select code from roles order by name",String::class.java).filter { d.group in PlatformAccess.groups(setOf(it)) || d.key in setOf("support","compliance","agreements") && it in d.writers }
   val config=jdbc.queryForList("select revision,enabled,readers from form_administration where form_key=?",d.key).firstOrNull()
   val effective=catalog.get(d.key).let {it.copy(writers=if(d.key=="events")emptyList() else it.writers.filter {role -> role in roles})}
   mapOf("definition" to effective,"availableWriters" to (if(d.key=="events")emptyList<String>() else d.writers.filter {it in roles}),"base" to d,"availableReaders" to roles,"enabled" to (config?.get("enabled") ?: true),"revision" to (config?.get("revision") ?: 0L),"readers" to (config?.get("readers")?.let {mapper.readValue(it.toString(),List::class.java)} ?: roles))
 }}
 @GetMapping("/{key}/history") fun history(@PathVariable key:String):List<Map<String,Any?>> {admin();catalog.get(key);return jdbc.queryForList("select h.action,h.note,h.occurred_at,u.display_name actor from operational_history h left join users u on u.id=h.actor_id where h.dataset='form-administration' and h.record_id=? order by occurred_at desc",UUID.nameUUIDFromBytes(key.toByteArray()))}
 @PutMapping("/{key}") @Transactional fun save(@PathVariable key:String,@RequestBody r:ManagedForm):Map<String,Any> {
   admin();val base=catalog.baseDefinitions.firstOrNull {it.key==key} ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Form not found")
   jdbc.queryForList("select pg_advisory_xact_lock(?)",UUID.nameUUIDFromBytes(key.toByteArray()).mostSignificantBits)
   val old=jdbc.queryForList("select * from form_administration where form_key=? for update",key).firstOrNull()
   if(((old?.get("revision") as? Number)?.toLong() ?: 0L) != r.revision)throw ResponseStatusException(HttpStatus.CONFLICT,"The form changed. Reload before saving.")
   require(r.title.isNotBlank() && r.title.length<=180 && '_' !in r.title && r.description.length<=2000 && r.reason.isNotBlank() && r.reason.length<=2000){"Provide a readable title, description and change reason"}
   val allowedReaders=jdbc.queryForList("select code from roles",String::class.java).filter {base.group in PlatformAccess.groups(setOf(it)) || base.key in setOf("support","compliance","agreements") && it in base.writers}
   require(r.readers.all {it in allowedReaders} && r.writers.all {it in base.writers && it in r.readers} && (base.key!="events" || r.writers.isEmpty())){"Choose eligible roles, give editors view access, and keep system events read only"}
   require(r.fields.map {it.name}.toSet()==base.fields.map {it.name}.toSet() && r.fields.size==base.fields.size){"Keep the form's validated record fields"}
   r.fields.forEach { f -> val b=base.fields.first {it.name==f.name};require(f.label.isNotBlank() && f.label.length<=180 && '_' !in f.label && f.help.length<=2000 && f.section.length<=180 && '_' !in f.section){"Use readable field labels and guidance"};require(!b.required || f.required && !f.hidden){"Required record fields must remain visible and required"};require(!f.hidden || !f.required && b.reference.isBlank() && b.name!=base.stateField){"Linked records, workflow status and required fields must remain visible"} }
   val fields=mapper.writeValueAsString(r.fields)
   jdbc.update("insert into form_administration(form_key,title,description,enabled,readers,writers,fields,revision,updated_by) values (?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?) on conflict(form_key) do update set title=excluded.title,description=excluded.description,enabled=excluded.enabled,readers=excluded.readers,writers=excluded.writers,fields=excluded.fields,revision=excluded.revision,updated_by=excluded.updated_by,updated_at=now()",key,r.title,r.description,r.enabled,mapper.writeValueAsString(r.readers.distinct()),mapper.writeValueAsString(r.writers.distinct()),fields,r.revision+1,identity.current().id)
   jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,before_data,after_data,note) values ('form-administration',?,?,'FORM_CONFIGURATION_CHANGED',?::jsonb,?::jsonb,?)",UUID.nameUUIDFromBytes(key.toByteArray()),identity.current().id,mapper.writeValueAsString(old?.mapValues {it.value?.toString()} ?: emptyMap<String,String>()),mapper.writeValueAsString(r),r.reason)
   return mapOf("saved" to true,"revision" to r.revision+1)
 }
}


