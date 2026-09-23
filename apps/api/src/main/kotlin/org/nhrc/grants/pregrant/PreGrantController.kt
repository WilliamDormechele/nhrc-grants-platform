package org.nhrc.grants.pregrant

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

data class EoiCreate(val opportunityId:UUID,val researcherId:UUID,val proposedRole:String="PI",val teamSummary:String?=null,val note:String?=null)
data class RequirementUpdate(val status:String,val ownerUserId:UUID?=null,val dueAt:OffsetDateTime?=null,val note:String?=null,val evidenceStorageKey:String?=null)
data class PortalCreate(val portalName:String,val portalUrl:String?=null,val portalApplicationReference:String?=null,val ownerUserId:UUID?=null,val portalDeadlineAt:OffsetDateTime?=null,val requirementsNote:String?=null)
data class QualityDecision(val status:String,val checkedBy:UUID?=null,val note:String?=null)
data class CommunicationCreate(val communicationType:String,val direction:String,val subject:String,val communicationAt:OffsetDateTime,val contactName:String?=null,val contactEmail:String?=null,val summary:String?=null,val evidenceStorageKey:String?=null,val recordedBy:UUID?=null)

@RestController
@RequestMapping("/api/pregrant")
class PreGrantController(private val jdbc:JdbcTemplate){
 @GetMapping("/applications/{id}/workspace")
 fun workspace(@PathVariable id:UUID):Map<String,Any>{
  val app=jdbc.queryForMap("""select a.*,u.display_name lead_researcher,f.name funder from applications a left join users u on u.id=a.lead_researcher_id left join opportunities o on o.id=a.opportunity_id left join funders f on f.id=o.funder_id where a.id=?""",id)
  return mapOf("application" to app,
   "requirements" to jdbc.queryForList("select r.*,u.display_name owner from application_requirements r left join users u on u.id=r.owner_user_id where r.application_id=? order by r.required desc,r.due_at nulls last,r.created_at",id),
   "team" to jdbc.queryForList("select t.*,u.display_name internal_name from application_team_members t left join users u on u.id=t.user_id where t.application_id=? and t.active=true order by t.created_at",id),
   "portals" to jdbc.queryForList("select p.*,u.display_name owner from funder_portal_records p left join users u on u.id=p.owner_user_id where p.application_id=? order by p.created_at",id),
   "qualityChecks" to jdbc.queryForList("select q.*,u.display_name checked_by_name from application_quality_checks q left join users u on u.id=q.checked_by where q.application_id=? order by q.id",id),
   "communications" to jdbc.queryForList("select * from funder_communications where application_id=? order by communication_at desc",id))
 }

 @GetMapping("/expressions-of-interest")
 fun eois(@RequestParam(required=false) opportunityId:UUID?) = if(opportunityId==null)
  jdbc.queryForList("""select e.*,o.title opportunity,u.display_name researcher from pregrant_expressions_of_interest e join opportunities o on o.id=e.opportunity_id join users u on u.id=e.researcher_id order by e.submitted_at desc""")
 else jdbc.queryForList("""select e.*,o.title opportunity,u.display_name researcher from pregrant_expressions_of_interest e join opportunities o on o.id=e.opportunity_id join users u on u.id=e.researcher_id where e.opportunity_id=? order by e.submitted_at desc""",opportunityId)

 @PostMapping("/expressions-of-interest") @Transactional
 fun createEoi(@RequestBody r:EoiCreate):Map<String,Any>{
  val id=UUID.randomUUID()
  jdbc.update("insert into pregrant_expressions_of_interest(id,opportunity_id,researcher_id,proposed_role,team_summary,note) values (?,?,?,?,?,?)",id,r.opportunityId,r.researcherId,r.proposedRole,r.teamSummary,r.note)
  return mapOf("id" to id,"status" to "SUBMITTED")
 }

 @PatchMapping("/requirements/{id}") @Transactional
 fun updateRequirement(@PathVariable id:UUID,@RequestBody r:RequirementUpdate):Map<String,Any>{
  if(r.status !in setOf("NOT_STARTED","IN_PROGRESS","COMPLETE","NOT_APPLICABLE","BLOCKED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid requirement status")
  val n=jdbc.update("""update application_requirements set status=?,owner_user_id=coalesce(?,owner_user_id),due_at=coalesce(?,due_at),note=coalesce(?,note),evidence_storage_key=coalesce(?,evidence_storage_key),completed_at=case when ?='COMPLETE' then now() else null end where id=?""",r.status,r.ownerUserId,r.dueAt,r.note,r.evidenceStorageKey,r.status,id)
  if(n==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Requirement not found")
  return mapOf("id" to id,"status" to r.status)
 }

 @PostMapping("/applications/{id}/portals") @Transactional
 fun addPortal(@PathVariable id:UUID,@RequestBody r:PortalCreate):Map<String,Any>{
  val pid=UUID.randomUUID()
  jdbc.update("insert into funder_portal_records(id,application_id,portal_name,portal_url,portal_application_reference,owner_user_id,portal_deadline_at,requirements_note) values (?,?,?,?,?,?,?,?)",pid,id,r.portalName,r.portalUrl,r.portalApplicationReference,r.ownerUserId,r.portalDeadlineAt,r.requirementsNote)
  return mapOf("id" to pid,"registrationStatus" to "NOT_STARTED")
 }

 @PatchMapping("/quality-checks/{id}") @Transactional
 fun quality(@PathVariable id:UUID,@RequestBody r:QualityDecision):Map<String,Any>{
  if(r.status !in setOf("PENDING","PASSED","FAILED","NOT_APPLICABLE")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid quality-check status")
  val n=jdbc.update("update application_quality_checks set status=?,checked_by=?,checked_at=now(),note=? where id=?",r.status,r.checkedBy,r.note,id)
  if(n==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Quality check not found")
  return mapOf("id" to id,"status" to r.status)
 }

 @PostMapping("/applications/{id}/communications") @Transactional
 fun communication(@PathVariable id:UUID,@RequestBody r:CommunicationCreate):Map<String,Any>{
  if(r.direction !in setOf("INBOUND","OUTBOUND")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid direction")
  val cid=UUID.randomUUID()
  jdbc.update("insert into funder_communications(id,application_id,communication_type,direction,subject,communication_at,contact_name,contact_email,summary,evidence_storage_key,recorded_by) values (?,?,?,?,?,?,?,?,?,?,?)",cid,id,r.communicationType,r.direction,r.subject,r.communicationAt,r.contactName,r.contactEmail,r.summary,r.evidenceStorageKey,r.recordedBy)
  return mapOf("id" to cid)
 }

 @GetMapping("/requirements")
 fun requirements()=jdbc.queryForList("""select r.*,a.reference application_reference,a.title application_title,u.display_name owner from application_requirements r join applications a on a.id=r.application_id left join users u on u.id=r.owner_user_id order by case r.status when 'BLOCKED' then 0 when 'IN_PROGRESS' then 1 when 'NOT_STARTED' then 2 else 3 end,r.due_at nulls last""")

 @GetMapping("/portals")
 fun portals()=jdbc.queryForList("""select p.*,a.reference application_reference,a.title application_title,u.display_name owner from funder_portal_records p join applications a on a.id=p.application_id left join users u on u.id=p.owner_user_id order by p.portal_deadline_at nulls last,p.created_at desc""")

 @GetMapping("/quality-checks")
 fun qualityChecks()=jdbc.queryForList("""select q.*,a.reference application_reference,a.title application_title,u.display_name checked_by_name from application_quality_checks q join applications a on a.id=q.application_id left join users u on u.id=q.checked_by order by a.reference,q.check_type""")

 @GetMapping("/communications")
 fun communications()=jdbc.queryForList("""select c.*,a.reference application_reference,a.title application_title from funder_communications c join applications a on a.id=c.application_id order by c.communication_at desc""")

 @GetMapping("/handovers")
 fun handovers()=jdbc.queryForList("""select h.*,a.reference application_reference,a.title application_title,w.reference award_reference from award_handovers h join applications a on a.id=h.application_id left join awards w on w.id=h.award_id order by coalesce(h.accepted_at,h.prepared_at) desc nulls last""")

 @GetMapping("/analytics")
 fun analytics():Map<String,Any> = mapOf(
  "opportunities" to jdbc.queryForObject("select count(*) from opportunities",Long::class.java)!!,
  "applicationsInDevelopment" to jdbc.queryForObject("select count(*) from applications where stage not in ('SUBMITTED','OUTCOME_RECORDED','AWARDED','CLOSED')",Long::class.java)!!,
  "submitted" to jdbc.queryForObject("select count(*) from applications where submitted_at is not null",Long::class.java)!!,
  "awarded" to jdbc.queryForObject("select count(*) from applications where funder_outcome='AWARDED'",Long::class.java)!!,
  "fundingRequested" to jdbc.queryForObject("select coalesce(sum(funder_amount),0) from application_budget_lines",java.math.BigDecimal::class.java)!!
 )
}
