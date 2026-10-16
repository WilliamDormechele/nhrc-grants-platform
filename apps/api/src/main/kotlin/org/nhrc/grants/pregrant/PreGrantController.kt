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
class PreGrantController(private val jdbc:JdbcTemplate,private val identity:org.nhrc.grants.config.PlatformIdentity){
 @GetMapping("/applications/{id}/workspace")
 fun workspace(@PathVariable id:UUID):Map<String,Any>{
  val app=jdbc.queryForMap("""select a.*,u.display_name lead_researcher,ou.display_name owner_name,f.name funder from applications a left join users u on u.id=a.lead_researcher_id left join users ou on ou.id=a.owner_user_id left join opportunities o on o.id=a.opportunity_id left join funders f on f.id=o.funder_id where a.id=?""",id)
  return mapOf(
   "application" to app,
   "approvals" to jdbc.queryForList("select a.*,d.display_name decided_by_name,u.display_name assigned_user_name from approvals a left join users d on d.id=a.decided_by left join users u on u.id=a.assigned_user_id where a.entity_type='APPLICATION' and a.entity_id=? order by a.created_at desc,a.id desc",id),
   "requirements" to jdbc.queryForList("select r.*,u.display_name owner from application_requirements r left join users u on u.id=r.owner_user_id where r.application_id=? order by r.required desc,r.due_at nulls last,r.created_at",id),
   "team" to jdbc.queryForList("select t.*,u.display_name internal_name from application_team_members t left join users u on u.id=t.user_id where t.application_id=? and t.active=true order by t.created_at",id),
   "assignments" to jdbc.queryForList("select aa.*,u.display_name researcher from application_assignments aa join users u on u.id=aa.researcher_id where aa.application_id=? order by aa.assigned_at desc",id),
   "portals" to jdbc.queryForList("select p.*,u.display_name owner from funder_portal_records p left join users u on u.id=p.owner_user_id where p.application_id=? order by p.created_at",id),
   "qualityChecks" to jdbc.queryForList("select q.*,u.display_name checked_by_name from application_quality_checks q left join users u on u.id=q.checked_by where q.application_id=? order by q.check_type",id),
   "communications" to jdbc.queryForList("select * from funder_communications where application_id=? order by communication_at desc",id),
   "documents" to jdbc.queryForList("select * from application_documents where application_id=? order by document_type,version_no desc",id),
   "budget" to jdbc.queryForList("select *,quantity*unit_cost calculated_cost from application_budget_lines where application_id=? order by year_no,category",id),
   "reviewHistory" to jdbc.queryForList("select h.id,h.occurred_at,h.note,h.before_data->>'status' previous_status,h.after_data->>'status' status,r.review_type,u.display_name actor from operational_history h join application_reviews r on r.id=h.record_id left join users u on u.id=h.actor_id where h.dataset='application-reviews' and r.application_id=? order by h.occurred_at desc,h.id desc",id),
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
  val opportunity=jdbc.queryForMap("select discovery_source_id,discovery_review_status from opportunities where id=?",r.opportunityId)
  if(opportunity["discovery_source_id"]!=null && opportunity["discovery_review_status"]!="ACCEPTED")
   throw ResponseStatusException(HttpStatus.CONFLICT,"Externally discovered opportunity must be accepted by NHRC before expressions of interest are recorded")
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
  val actor=identity.current()
  if(actor.roles==setOf("RESEARCHER") && jdbc.queryForObject("select count(*) from application_requirements r join applications a on a.id=r.application_id where r.id=? and (a.owner_user_id=? or a.lead_researcher_id=? or exists(select 1 from application_team_members t where t.application_id=a.id and t.user_id=? and t.active=true))",Long::class.java,id,actor.id,actor.id,actor.id)!=1L)
    throw ResponseStatusException(HttpStatus.FORBIDDEN,"This requirement is outside your assigned application")
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
  if(r.note.isNullOrBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Record the quality-check evidence or rationale")
  val n=jdbc.update("update application_quality_checks set status=?,checked_by=?,checked_at=case when ?='PENDING' then null else now() end,note=? where id=?",r.status,r.checkedBy,r.status,r.note,id)
  if(n==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Quality check not found")
  return mapOf("id" to id,"status" to r.status)
 }

 @PatchMapping("/reviews/{id}") @Transactional
 fun review(@PathVariable id:UUID,@RequestBody r:ReviewDecision):Map<String,Any>{
  if(r.status !in setOf("PENDING","APPROVED","RETURNED","REJECTED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid review status")
  val assigned=jdbc.queryForObject("select reviewer_id from application_reviews where id=? for update",UUID::class.java,id)
  if(assigned!=identity.current().id) throw ResponseStatusException(HttpStatus.FORBIDDEN,"Only the assigned reviewer can record this review")
  if(r.comments.isNullOrBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Record the review findings and decision reason")
  val previous=jdbc.queryForObject("select to_jsonb(r)::text from application_reviews r where id=?",String::class.java,id)
  val n=jdbc.update("update application_reviews set status=?,reviewer_id=coalesce(?,reviewer_id),comments=?,completed_at=case when ?='PENDING' then null else now() end where id=?",r.status,r.reviewerId,r.comments,r.status,id)
  if(n==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Review not found")
  jdbc.update("insert into operational_history(dataset,record_id,actor_id,action,note,before_data,after_data) select 'application-reviews',r.id,?,'REVIEW_DECISION',?,?::jsonb,to_jsonb(r) from application_reviews r where r.id=?",identity.current().id,r.comments,previous,id)
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
  val current=jdbc.queryForObject("select stage from applications where id=? for update",String::class.java,id)
    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found")
  if(current!="INSTITUTIONAL_APPROVAL") throw ResponseStatusException(HttpStatus.CONFLICT,"Application must reach institutional approval before submission")
  if(r.acknowledgement.isNullOrBlank() || r.proofStorageKey.isNullOrBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Record the submission acknowledgement and attach its proof")
  if(jdbc.queryForObject("select count(*) from application_reviews where application_id=? and status<>'APPROVED'",Long::class.java,id)!!>0L)throw ResponseStatusException(HttpStatus.CONFLICT,"Complete the outstanding internal reviews before submission")
  if(!org.nhrc.grants.workflow.ApplicationDecisions.approved(jdbc,id,"INSTITUTIONAL")) throw ResponseStatusException(HttpStatus.CONFLICT,"A named institutional approval is required before submission")
  if(!org.nhrc.grants.workflow.ApplicationDecisions.allApproved(jdbc,id))throw ResponseStatusException(HttpStatus.CONFLICT,"Resolve the latest returned, rejected or pending application decisions before submission")
  val incomplete=jdbc.queryForObject("select count(*) from application_requirements where application_id=? and required=true and status not in ('COMPLETE','NOT_APPLICABLE')",Long::class.java,id)?:0
  if(incomplete>0L) throw ResponseStatusException(HttpStatus.CONFLICT,"Complete or justify the required application checklist first")
  val unanswered=jdbc.queryForObject("""select count(*) from form_definitions f cross join lateral jsonb_array_elements(f.schema_json->'fields') q left join institutional_form_responses r on r.form_id=f.id and r.application_id=? where f.entity_type='APPLICATION' and f.active=true and coalesce((q->>'required')::boolean,false)=true and (r.values_json is null or (r.values_json->(q->>'name')) is null or nullif(trim(r.values_json->>(q->>'name')),'') is null)""",Long::class.java,id)?:0
  if(unanswered>0) throw ResponseStatusException(HttpStatus.CONFLICT,"Complete the required institutional form responses before submission")
  val outstanding=jdbc.queryForObject("select count(*) from application_quality_checks where application_id=? and status not in ('PASSED','NOT_APPLICABLE')",Long::class.java,id)?:0
  if(outstanding>0) throw ResponseStatusException(HttpStatus.CONFLICT,"Final quality checks are incomplete")
  val pendingApproval=jdbc.queryForObject("select count(*) from approvals where entity_type='APPLICATION' and entity_id=? and status='PENDING'",Long::class.java,id)?:0
  if(pendingApproval>0) throw ResponseStatusException(HttpStatus.CONFLICT,"Application has pending institutional approvals")
  jdbc.update("""update applications set stage='SUBMITTED',submitted_at=?,submission_acknowledgement=?,submission_proof_storage_key=?,updated_at=now() where id=?""",r.submittedAt,r.acknowledgement,r.proofStorageKey,id)
  jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,changed_by,note) values (?,?,'SUBMITTED',?,'Submission recorded')",id,current,identity.current().id)
  return mapOf("id" to id,"stage" to "SUBMITTED","submittedAt" to r.submittedAt)
 }

 @PutMapping("/applications/{id}/handover") @Transactional
 fun handover(@PathVariable id:UUID,@RequestBody r:HandoverUpdate):Map<String,Any>{
  if(r.status !in setOf("PREPARING","READY","ACCEPTED","RETURNED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid handover status")
  val actor=identity.current()
  val award=jdbc.queryForList("select id from awards where application_id=?",id).firstOrNull()?.get("id")
    ?: throw ResponseStatusException(HttpStatus.CONFLICT,"Convert the successful application to an award before handover")
  if(r.awardId!=award)throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Use the award linked to this application")
  val previous=jdbc.queryForList("select * from award_handovers where application_id=? for update",id).firstOrNull()
  if(previous?.get("status")=="ACCEPTED")throw ResponseStatusException(HttpStatus.CONFLICT,"This handover has already been accepted")
  if(r.status in setOf("ACCEPTED","RETURNED")) {
    if("POST_AWARD_OFFICER" !in actor.roles)throw ResponseStatusException(HttpStatus.FORBIDDEN,"The receiving post-award officer must decide the handover")
    if(previous?.get("status")!="READY")throw ResponseStatusException(HttpStatus.CONFLICT,"The grants officer must mark the handover ready first")
    if(previous["prepared_by"]==actor.id)throw ResponseStatusException(HttpStatus.FORBIDDEN,"The preparing officer cannot accept their own handover")
  } else if("GRANTS_OFFICER" !in actor.roles)throw ResponseStatusException(HttpStatus.FORBIDDEN,"The grants officer prepares the handover")
  if(r.status in setOf("READY","ACCEPTED") && !(r.proposalComplete&&r.budgetComplete&&r.awardDocumentsComplete&&r.approvalsComplete&&r.correspondenceComplete))
    throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Confirm each handover component before marking it ready or accepted")
  if(r.note.isNullOrBlank())throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Record the handover evidence or return reason")
  val preparedBy=if(r.status in setOf("ACCEPTED","RETURNED"))previous?.get("prepared_by") else actor.id
  val acceptedBy=if(r.status=="ACCEPTED")actor.id else null
  jdbc.update("""insert into award_handovers(application_id,award_id,status,proposal_complete,budget_complete,award_documents_complete,approvals_complete,correspondence_complete,prepared_by,accepted_by,prepared_at,accepted_at,note)
      values (?,?,?,?,?,?,?,?,?,?,case when ? in ('READY','ACCEPTED') then now() else null end,case when ?='ACCEPTED' then now() else null end,?)
      on conflict (application_id) do update set award_id=excluded.award_id,status=excluded.status,proposal_complete=excluded.proposal_complete,budget_complete=excluded.budget_complete,award_documents_complete=excluded.award_documents_complete,approvals_complete=excluded.approvals_complete,correspondence_complete=excluded.correspondence_complete,prepared_by=excluded.prepared_by,accepted_by=excluded.accepted_by,prepared_at=coalesce(award_handovers.prepared_at,excluded.prepared_at),accepted_at=excluded.accepted_at,note=excluded.note""",
      id,r.awardId,r.status,r.proposalComplete,r.budgetComplete,r.awardDocumentsComplete,r.approvalsComplete,r.correspondenceComplete,preparedBy,acceptedBy,r.status,r.status,r.note)
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
