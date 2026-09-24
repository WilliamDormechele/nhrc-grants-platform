package org.nhrc.grants.workflow

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

data class ApplicationCreate(val opportunityId:UUID?,val title:String,val deadlineAt:OffsetDateTime?,val ownerUserId:UUID?=null)

@RestController
@RequestMapping("/api/applications")
class ApplicationWriteController(private val jdbc:JdbcTemplate){
 @PostMapping @Transactional
 fun create(@RequestBody r:ApplicationCreate):Map<String,Any>{
  if(r.title.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Application title is required")
  if(r.opportunityId!=null){
   val opportunity=jdbc.queryForMap("select discovery_source_id,discovery_review_status from opportunities where id=?",r.opportunityId)
   if(opportunity["discovery_source_id"]!=null && opportunity["discovery_review_status"]!="ACCEPTED"){
    throw ResponseStatusException(HttpStatus.CONFLICT,"Externally discovered opportunity must be accepted by NHRC before application registration")
   }
  }

  val id=UUID.randomUUID()
  val ref="APP-${java.time.Year.now().value}-${id.toString().take(8).uppercase()}"
  jdbc.update("insert into applications(id,reference,opportunity_id,title,owner_user_id,deadline_at) values (?,?,?,?,?,?)",id,ref,r.opportunityId,r.title,r.ownerUserId,r.deadlineAt)
  jdbc.update("insert into application_stage_history(application_id,to_stage,note,changed_by) values (?,'DISCOVERED','Application created',?)",id,r.ownerUserId)

  val requirements=listOf(
   "TECHNICAL" to "Technical proposal / concept note",
   "WORKPLAN" to "Work plan",
   "BUDGET" to "Budget and justification",
   "CV" to "Required CVs / biosketches",
   "LETTER" to "Letters of support / commitment",
   "INSTITUTIONAL" to "Required institutional documents",
   "APPROVAL" to "Required internal approvals",
   "CERTIFICATION" to "Required certifications",
   "PORTAL" to "Funder portal requirements",
   "SUBMISSION" to "Submission readiness and proof"
  )
  requirements.forEach { (type,title) ->
   jdbc.update("insert into application_requirements(application_id,requirement_type,title,required,status) values (?,?,?,true,'NOT_STARTED')",id,type,title)
  }

  val checks=listOf(
   "COMPLETENESS" to "All required sections and attachments complete",
   "CONSISTENCY" to "Narrative, work plan and budget are consistent",
   "FUNDER_COMPLIANCE" to "Funder instructions and eligibility requirements satisfied",
   "ATTACHMENTS" to "Required attachments are present and current",
   "APPROVALS" to "Required institutional approvals completed",
   "SUBMISSION_READY" to "Authorised final version ready for submission"
  )
  checks.forEach { (type,title) ->
   jdbc.update("insert into application_quality_checks(application_id,check_type,title,status) values (?,?,?,'PENDING')",id,type,title)
  }

  return mapOf("id" to id,"reference" to ref,"stage" to "DISCOVERED")
 }
}
