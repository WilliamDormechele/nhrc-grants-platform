package org.nhrc.grants.operations

import com.fasterxml.jackson.databind.ObjectMapper
import org.nhrc.grants.config.PlatformIdentity
import org.nhrc.grants.config.PlatformAccess
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.bind.annotation.*
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

data class QuestionnaireAccess(val readers:List<String>,val writers:List<String>,val revision:Long,val reason:String)

@Service
class QuestionnairePermissions(private val jdbc:JdbcTemplate,private val mapper:ObjectMapper) {
 fun roles():List<String> = jdbc.queryForList("select code from roles order by name",String::class.java).filter {"Pre-Award" in PlatformAccess.groups(setOf(it))}
 fun allowed(id:UUID,roles:Set<String>,write:Boolean):Boolean {
  if(!write && "SUPERADMIN" in roles)return true
  val policy=jdbc.queryForList("select readers,writers from questionnaire_permissions where form_id=?",id).firstOrNull() ?: return true
  return mapper.readTree(policy[if(write)"writers" else "readers"].toString()).any {it.asText() in roles}
 }
}

@RestController
@RequestMapping("/api/operations/questionnaire-permissions")
class QuestionnairePermissionsController(private val jdbc:JdbcTemplate,private val identity:PlatformIdentity,private val permissions:QuestionnairePermissions,private val mapper:ObjectMapper) {
 private fun admin(){if(identity.current().roles.none {it in setOf("ADMIN","SUPERADMIN")})throw ResponseStatusException(HttpStatus.FORBIDDEN,"Form permission administration requires an Administrator")}
 private fun form(id:UUID){if(jdbc.queryForObject("select count(*) from form_definitions where id=? and entity_type='APPLICATION'",Long::class.java,id)!=1L)throw ResponseStatusException(HttpStatus.NOT_FOUND,"Choose a connected institutional proposal form")}
 @GetMapping("/{id}") fun read(@PathVariable id:UUID):Map<String,Any> {admin();form(id);val roles=permissions.roles();val old=jdbc.queryForList("select readers,writers,revision from questionnaire_permissions where form_id=?",id).firstOrNull();return mapOf("availableReaders" to roles,"availableWriters" to roles.filter {it!="AUDITOR"},"readers" to (old?.get("readers")?.let {mapper.readTree(it.toString()).map {v->v.asText()}} ?: roles),"writers" to (old?.get("writers")?.let {mapper.readTree(it.toString()).map {v->v.asText()}} ?: roles.filter {it!="AUDITOR"}),"revision" to (old?.get("revision") ?: 0L),"history" to jdbc.queryForList("select h.note,h.occurred_at,u.display_name actor from operational_history h left join users u on u.id=h.actor_id where h.dataset='questionnaire-permissions' and h.record_id=? order by h.occurred_at desc",id))}
 @PutMapping("/{id}") @Transactional fun save(@PathVariable id:UUID,@RequestBody r:QuestionnaireAccess):Map<String,Any> {
  admin();form(id);jdbc.queryForMap("select id from form_definitions where id=? for update",id)
  val old=jdbc.queryForList("select * from questionnaire_permissions where form_id=?",id).firstOrNull()
  if(((old?.get("revision") as? Number)?.toLong() ?: 0L)!=r.revision)throw ResponseStatusException(HttpStatus.CONFLICT,"Permissions changed. Reload before saving.")
  require(r.reason.isNotBlank() && r.reason.length<=2000 && r.readers.all {it in permissions.roles()} && r.writers.all {it in r.readers && it!="AUDITOR"}){"Choose valid roles, give editors view access, and record a change reason"}
  if(r.writers.isEmpty() && jdbc.queryForObject("select count(*) from form_definitions where id=? and active=true and exists(select 1 from jsonb_array_elements(schema_json->'fields') q where coalesce((q->>'required')::boolean,false))",Long::class.java,id)!!>0L)throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Keep at least one editor for a published required questionnaire, or retire the questionnaire first")
  jdbc.update("insert into questionnaire_permissions(form_id,readers,writers,revision,updated_by) values (?,?::jsonb,?::jsonb,?,?) on conflict(form_id) do update set readers=excluded.readers,writers=excluded.writers,revision=excluded.revision,updated_by=excluded.updated_by,updated_at=now()",id,mapper.writeValueAsString(r.readers.distinct()),mapper.writeValueAsString(r.writers.distinct()),r.revision+1,identity.current().id)
  jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,before_data,after_data,note) values ('questionnaire-permissions',?,?,'FORM_ACCESS_CHANGED',?::jsonb,?::jsonb,?)",id,identity.current().id,mapper.writeValueAsString(old?.mapValues {it.value?.toString()} ?: emptyMap<String,String>()),mapper.writeValueAsString(r),r.reason)
  return mapOf("saved" to true,"revision" to r.revision+1)
 }
}

