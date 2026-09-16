package org.nhrc.grants.workflow

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime
import java.util.UUID

data class ApplicationCreate(val opportunityId:UUID?,val title:String,val deadlineAt:OffsetDateTime?,val ownerUserId:UUID?=null)
@RestController
@RequestMapping("/api/applications")
class ApplicationWriteController(private val jdbc:JdbcTemplate){
 @PostMapping @Transactional fun create(@RequestBody r:ApplicationCreate):Map<String,Any>{
  val id=UUID.randomUUID(); val ref="APP-${java.time.Year.now().value}-${id.toString().take(8).uppercase()}"
  jdbc.update("insert into applications(id,reference,opportunity_id,title,owner_user_id,deadline_at) values (?,?,?,?,?,?)",id,ref,r.opportunityId,r.title,r.ownerUserId,r.deadlineAt)
  jdbc.update("insert into application_stage_history(application_id,to_stage,note,changed_by) values (?,'DISCOVERED','Application created',?)",id,r.ownerUserId)
  return mapOf("id" to id,"reference" to ref,"stage" to "DISCOVERED")
 }
}
