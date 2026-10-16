package org.nhrc.grants.operations

import com.fasterxml.jackson.databind.ObjectMapper
import org.nhrc.grants.config.PlatformIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class FormResponseSave(val values:Map<String,Any?>,val revision:Long)

@RestController
@RequestMapping("/api/operations/application-forms")
class ApplicationFormsController(private val jdbc:JdbcTemplate,private val identity:PlatformIdentity,private val mapper:ObjectMapper,private val permissions:QuestionnairePermissions) {
 private fun access(id:UUID,write:Boolean=false) {
   val actor=identity.current()
   if(actor.roles.any { it in setOf("GRANTS_OFFICER","DIRECTOR","ADMIN") })return
   if(!write && jdbc.queryForObject("select count(*) from application_reviews where application_id=? and reviewer_id=?",Long::class.java,id,actor.id)!!>0L)return
   val owned=jdbc.queryForObject("select count(*) from applications a where id=? and (owner_user_id=? or lead_researcher_id=? or exists(select 1 from application_team_members t where t.application_id=a.id and t.user_id=? and t.active=true))",Long::class.java,id,actor.id,actor.id,actor.id)==1L
   if(!owned)throw ResponseStatusException(HttpStatus.FORBIDDEN,"This application is outside your assigned work")
 }
 @GetMapping("/{id}") fun read(@PathVariable id:UUID):List<Map<String,Any?>> {
   access(id)
   return jdbc.queryForList("select f.id,f.name,f.version_no,f.schema_json::text schema,r.values_json::text answers,coalesce(r.revision,0) revision from form_definitions f left join institutional_form_responses r on r.form_id=f.id and r.application_id=? where f.entity_type='APPLICATION' and f.active=true order by f.name",id)
    .filter {r -> permissions.allowed(r["id"] as UUID,identity.current().roles,false)}
    .map { r ->r+mapOf("canEdit" to (permissions.allowed(r["id"] as UUID,identity.current().roles,true) && runCatching {access(id,true)}.isSuccess),"schema" to QuestionnaireSchema.normalize(mapper.readTree(r["schema"].toString()),mapper),"answers" to mapper.readTree(r["answers"]?.toString() ?: "{}")) }
 }
 @PutMapping("/{id}/{formId}") @Transactional fun save(@PathVariable id:UUID,@PathVariable formId:UUID,@RequestBody r:FormResponseSave):Map<String,Any> {
   access(id,true);if(!permissions.allowed(formId,identity.current().roles,true))throw ResponseStatusException(HttpStatus.FORBIDDEN,"Your role cannot edit this institutional form");jdbc.queryForMap("select id from applications where id=? for update",id)
   val source=jdbc.queryForObject("select schema_json::text from form_definitions where id=? and entity_type='APPLICATION' and active=true",String::class.java,formId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Form is unavailable")
   val fields=QuestionnaireSchema.fields(mapper.readTree(source),mapper);require(fields.isArray && fields.size()<=100){"The institutional form needs valid question definitions"}
   val known=fields.map { it.path("name").asText() }.toSet();require(r.values.keys.all { it in known }){"The form contains an unrecognised question"}
   fields.forEach { q ->
     val key=q.path("name").asText();val label=q.path("label").asText();val value=r.values[key]
     require(!q.path("required").asBoolean() || value!=null && value.toString().isNotBlank()){"$label is required"}
     if(value!=null && value.toString().isNotBlank()) {
       require(value.toString().length<=12000){"$label exceeds its response limit"}
       when(q.path("type").asText()){
         "number" ->require(value.toString().toBigDecimalOrNull()!=null){"$label must be a number"}
         "date" ->require(runCatching {java.time.LocalDate.parse(value.toString())}.isSuccess){"$label must be a valid date"}
         "select" ->require(q.path("choices").map {it.asText()}.contains(value.toString())){"Choose a valid response for $label"}
         "checkbox" ->require(value is Boolean){"$label must be Yes or No"}
       }
     }
   }
   val old=jdbc.queryForList("select revision from institutional_form_responses where application_id=? and form_id=?",id,formId).firstOrNull()
   if(((old?.get("revision") as? Number)?.toLong() ?: 0L)!=r.revision)throw ResponseStatusException(HttpStatus.CONFLICT,"This form response changed. Reload it before saving.")
   jdbc.update("insert into institutional_form_responses(application_id,form_id,values_json,revision,saved_by) values (?,?,?::jsonb,?,?) on conflict(application_id,form_id) do update set values_json=excluded.values_json,revision=excluded.revision,saved_by=excluded.saved_by,saved_at=now()",id,formId,mapper.writeValueAsString(r.values),r.revision+1,identity.current().id)
   return mapOf("revision" to r.revision+1)
 }
}
