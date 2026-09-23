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
data class TeamMemberCreate(val userId:UUID?=null,val externalName:String?=null,val externalEmail:String?=null,val organisation:String?=null,val roleCode:String,val responsibility:String?=null)
data class ReviewDecision(val status:String,val reviewerId:UUID?=null,val comments:String?=null)
data class SubmissionRecord(val submittedAt:OffsetDateTime=OffsetDateTime.now(),val acknowledgement:String?=null,val proofStorageKey:String?=null)
data class HandoverUpdate(
 val status:String="PREPARING",
 val awardId:UUID?=null,
 val proposalComplete:Boolean=false,
 val budgetComplete:Boolean=false,
 val awardDocumentsComplete:Boolean=false,
 val approvalsComplete:Boolean=false,
 val correspondenceComplete:Boolean=false,
 val preparedBy:UUID?=null,
 val acceptedBy:UUID?=null,
 val note:String?=null
)

@RestController
@RequestMapping("/api/pregrant")
class PreGrantController(private val jdbc:JdbcTemplate){
 @GetMapping("/applications/{id}/workspace")
 fun workspace(@PathVariable id:UUID):Map<String,Any>{
  val app=jdbc.queryForMap("""select a.*,u.display_name lead_researcher,ou.display_name owner_name,f.name funder from applications a left join users u on u.id=a.lead_researcher_id left join users ou on ou.id=a.owner_user_id left join opportunities o on o.id=a.opportunity_id left join funders f on f.id=o.funder_id where a.id=?""",id)
  return mapOf(
   "application" to app,
   "requirements" to jdbc.queryForList("select r.*,u.display_name owner from application_requirements r left join users u on u.id=r.owner_user_id where r.application_id=? order by r.required desc,r.due_at nulls last,r.created_at",id),
   "team" to jdbc.queryForList("select t.*,u.display_name internal_name from application_team_members t left join users u on u.id=t.user_id where t.application_id=? and t.active=true order by t.created_at",id),
   "portals" to jdbc.queryForList("select p.*,u.display_name owner from funder_portal_records p left join users u on u.id=p.owner_user_id where p.application_id=? order by p.created_at",id),
   "qualityChecks" to jdbc.queryForList("select q.*,u.display_name checked_by_name from application_quality_checks q left join users u on u.id=q.checked_by where q.application_id=? order by q.check_type",id),
   "communications" to jdbc.queryForList("select * from funder_communications where application_id=? order by communication_at desc",id),
   "documents" to jdbc.queryForList("select * from application_documents where application_id=? order by document_type,version_no desc",id),
   "budget" to jdbc.queryForList("select *,quantity*unit_cost calculated_cost from application_budget_lines where application_id=? order by year_no,category",id),
   "reviews" to jdbc.queryForList("select r.*,u.display_name reviewer from application_reviews r left join users u on u.id=r.reviewer_id where r.application_id=? order by r.created_at",id),
   "stageHistory" to jdbc.queryForList("select h.*,u.display_name changed_by_name from application_stage_history h left join users u on u.id=h.changed_by where h.application_id=? order by h.changed_at desc",id),
   "handover" to jdbc.queryForList("select * from award_handovers where application_id=?",id).firstOrNull().orEmpty()
  )
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

 @PostMapping("/applications/{id}/team") @Transactional
 fun addTeamMember(@PathVariable id:UUID,@RequestBody r:TeamMemberCreate):Map<String,Any>{
  if(r.userId==null && r.externalName.isNullOrBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Internal user or external name is required")
  if(r.roleCode.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Role is required")
  val memberId=UUID.randomUUID()
  jdbc.update("""insert into application_team_members(id,application_id,user_id,external_name,external_email,organisation,role_code,responsibility) values (?,?,?,?,?,?,?,?)""",memberId,id,r.userId,r.externalName,r.externalEmail,r.organisation,r.roleCode,r.responsibility)
  return mapOf("id" to memberId,"status" to "ACTIVE")
 }

 @PatchMapping("/requirements/{id}") @Transactional
 fun updateRequirement(@PathVariable id:UUID,@RequestBody r:RequirementUpdate):Map<String,Any>{
  if(r.status !in setOf("NOT_STARTED","IN_PROGRESS","COMPLETE","NOT_APPLICABLE","BLOCKED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid requirement status")
  val n=jdbc.update("""update application_requirements set status=?,owner_user_id=coalesce(?,owner_user_id),due_at=coalesce(?,due_at),note=coalesce(?,note),evidence_storage_key=coalesce(?,evidence_storage_key),completed_at=case when ?='COMPLETE' then now() when ?<>'COMPLETE' then null else completed_at end where id=?""",r.status,r.ownerUserId,r.dueAt,r.note,r.evidenceStorageKey,r.status,r.status,id)
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
  val n=jdbc.update("update application_quality_checks set status=?,checked_by=?,checked_at=case when ?='PENDING' then null else now() end,note=? where id=?",r.status,r.checkedBy,r.status,r.note,id)
  if(n==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Quality check not found")
  return mapOf("id" to id,"status" to r.status)
 }

 @PatchMapping("/reviews/{id}") @Transactional
 fun review(@PathVariable id:UUID,@RequestBody r:ReviewDecision):Map<String,Any>{
  if(r.status !in setOf("PENDING","APPROVED","RETURNED","REJECTED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid review status")
  val n=jdbc.update("update application_reviews set status=?,reviewer_id=coalesce(?,reviewer_id),comments=?,completed_at=case when ?='PENDING' then null else now() end where id=?",r.status,r.reviewerId,r.comments,r.status,id)
  if(n==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Review not found")
  return mapOf("id" to id,"status" to r.status)
 }

 @PostMapping("/applications/{id}/communications") @Transactional
 fun communication(@PathVariable id:UUID,@RequestBody r:CommunicationCreate):Map<String,Any>{
  if(r.direction !in setOf("INBOUND","OUTBOUND")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid direction")
  val cid=UUID.randomUUID()
  jdbc.update("insert into funder_communications(id,application_id,communication_type,direction,subject,communication_at,contact_name,contact_email,summary,evidence_storage_key,recorded_by) values (?,?,?,?,?,?,?,?,?,?,?)",cid,id,r.communicationType,r.direction,r.subject,r.communicationAt,r.contactName,r.contactEmail,r.summary,r.evidenceStorageKey,r.recordedBy)
  return mapOf("id" to cid)
 }

 @PostMapping("/applications/{id}/submission") @Transactional
 fun recordSubmission(@PathVariable id:UUID,@RequestBody r:SubmissionRecord):Map<String,Any>{
  val current=jdbc.queryForObject("select stage from applications where id=?",String::class.java,id)
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found")
  if(current!="INSTITUTIONAL_APPROVAL") throw ResponseStatusException(HttpStatus.CONFLICT,"Application must reach institutional approval before submission")
  val outstanding=jdbc.queryForObject("select count(*) from application_quality_checks where application_id=? and status not in ('PASSED','NOT_APPLICABLE')",Long::class.java,id)?:0
  if(outstanding>0) throw ResponseStatusException(HttpStatus.CONFLICT,"Final quality checks are incomplete")
  val pendingApproval=jdbc.queryForObject("select count(*) from approvals where entity_type='APPLICATION' and entity_id=? and status='PENDING'",Long::class.java,id)?:0
  if(pendingApproval>0) throw ResponseStatusException(HttpStatus.CONFLICT,"Application has pending institutional approvals")
  jdbc.update("""update applications set stage='SUBMITTED',submitted_at=?,submission_acknowledgement=?,submission_proof_storage_key=?,updated_at=now() where id=?""",r.submittedAt,r.acknowledgement,r.proofStorageKey,id)
  jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,note) values (?,?,'SUBMITTED','Submission recorded')",id,current)
  return mapOf("id" to id,"stage" to "SUBMITTED","submittedAt" to r.submittedAt)
 }

 @PutMapping("/applications/{id}/handover") @Transactional
 fun handover(@PathVariable id:UUID,@RequestBody r:HandoverUpdate):Map<String,Any>{
  if(r.status !in setOf("PREPARING","READY","ACCEPTED","RETURNED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid handover status")
  jdbc.update("""insert into award_handovers(application_id,award_id,status,proposal_complete,budget_complete,award_documents_complete,approvals_complete,correspondence_complete,prepared_by,accepted_by,prepared_at,accepted_at,note)
      values (?,?,?,?,?,?,?,?,?,?,case when ? in ('READY','ACCEPTED') then now() else null end,case when ?='ACCEPTED' then now() else null end,?)
      on conflict (application_id) do update set award_id=excluded.award_id,status=excluded.status,proposal_complete=excluded.proposal_complete,budget_complete=excluded.budget_complete,award_documents_complete=excluded.award_documents_complete,approvals_complete=excluded.approvals_complete,correspondence_complete=excluded.correspondence_complete,prepared_by=excluded.prepared_by,accepted_by=excluded.accepted_by,prepared_at=coalesce(award_handovers.prepared_at,excluded.prepared_at),accepted_at=excluded.accepted_at,note=excluded.note""",
      id,r.awardId,r.status,r.proposalComplete,r.budgetComplete,r.awardDocumentsComplete,r.approvalsComplete,r.correspondenceComplete,r.preparedBy,r.acceptedBy,r.status,r.status,r.note)
  return mapOf("applicationId" to id,"status" to r.status)
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
