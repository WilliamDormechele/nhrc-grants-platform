package org.nhrc.grants.approval

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class ApprovalCreate(
 val entityType:String,
 val entityId:UUID,
 val approvalType:String,
 val sequenceNo:Int=1,
 val requiredRoleCode:String?=null,
 val assignedUserId:UUID?=null,
 val requestedBy:UUID?=null
)
data class ApprovalDecision(val decision:String,val decidedBy:UUID?,val note:String?)

@RestController
@RequestMapping("/api/approvals")
class ApprovalController(private val jdbc:JdbcTemplate,private val identity:org.nhrc.grants.config.PlatformIdentity){
 @GetMapping
 fun list(@RequestParam(required=false) status:String?):List<Map<String,Any?>> {
  val s=status?:""
  return jdbc.queryForList("""select a.*,rq.display_name requested_by_name,ass.display_name assigned_user_name,dec.display_name decided_by_name,coalesce(app.reference,aw.reference) entity_reference,coalesce(app.title,aw.title) entity_title
      from approvals a
      left join users rq on rq.id=a.requested_by
      left join users ass on ass.id=a.assigned_user_id
      left join users dec on dec.id=a.decided_by
      left join applications app on a.entity_type='APPLICATION' and app.id=a.entity_id
      left join awards aw on a.entity_type='AWARD' and aw.id=a.entity_id
      where (?='' or a.status=?)
      order by a.created_at desc limit 300""",s,s)
 }

 @PostMapping @Transactional
 fun create(@RequestBody r:ApprovalCreate):Map<String,Any>{
  if(r.entityType.isBlank()||r.approvalType.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Entity type and approval type are required")
  if(r.sequenceNo<1) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Sequence must be positive")
  val requiredRole=if(r.approvalType=="FINANCE_CLEARANCE") "FINANCE_APPROVER" else "DIRECTOR"
  if(r.entityType!="APPLICATION" || r.approvalType !in setOf("DIRECTOR_DECISION","INSTITUTIONAL","FINANCE_CLEARANCE"))throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose a supported application decision")
  if(r.requiredRoleCode!=null && r.requiredRoleCode!=requiredRole)throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Use the required institutional decision authority")
  if(r.assignedUserId!=null && jdbc.queryForObject("select count(*) from users u where u.id=? and u.active=true and exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id and r.code=? and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now()))",Long::class.java,r.assignedUserId,requiredRole)!=1L)throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an active "+requiredRole.lowercase().replace('_',' ')+" for this decision")
  val stage=jdbc.queryForObject("select stage from applications where id=? for update",String::class.java,r.entityId)
  val allowed=when(r.approvalType){"DIRECTOR_DECISION"->stage=="DIRECTOR_DECISION";"INSTITUTIONAL"->stage=="INSTITUTIONAL_APPROVAL";else->stage in setOf("PREPARATION","INTERNAL_REVIEW","INSTITUTIONAL_APPROVAL")}
  if(!allowed)throw ResponseStatusException(HttpStatus.CONFLICT,"Request this decision at its application workflow stage")
  if(jdbc.queryForObject("select count(*) from approvals where entity_type='APPLICATION' and entity_id=? and approval_type=? and status='PENDING'",Long::class.java,r.entityId,r.approvalType)!!>0L)throw ResponseStatusException(HttpStatus.CONFLICT,"This application already has a pending request for this decision")
  val id=UUID.randomUUID()
  jdbc.update("""insert into approvals(id,entity_type,entity_id,approval_type,sequence_no,required_role_code,assigned_user_id,requested_by,status)
      values (?,?,?,?,?,?,?,?,'PENDING')""",id,r.entityType.uppercase(),r.entityId,r.approvalType.uppercase(),r.sequenceNo,r.requiredRoleCode ?: if(r.approvalType=="FINANCE_CLEARANCE") "FINANCE_APPROVER" else "DIRECTOR",r.assignedUserId,identity.current().id)
  return mapOf("id" to id,"status" to "PENDING")
 }

 @PostMapping("/{id}/decision") @Transactional
 fun decide(@PathVariable id:UUID,@RequestBody r:ApprovalDecision):Map<String,Any>{
  if(r.decision !in setOf("APPROVED","RETURNED","REJECTED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid decision")
  val approval=jdbc.queryForMap("select requested_by,status,assigned_user_id,required_role_code from approvals where id=? for update",id)
  if(approval["status"]!="PENDING") throw ResponseStatusException(HttpStatus.CONFLICT,"Approval is not pending")
  val actor=identity.current()
  if(approval["assigned_user_id"]!=null && approval["assigned_user_id"]!=actor.id) throw ResponseStatusException(HttpStatus.FORBIDDEN,"This approval is assigned to another officer")
  if(approval["required_role_code"]!=null && approval["required_role_code"].toString() !in actor.roles) throw ResponseStatusException(HttpStatus.FORBIDDEN,"The required approval authority must decide this request")
  if(r.note.isNullOrBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Record the decision reason")
  val requestedBy=approval["requested_by"] as UUID?
  if(requestedBy!=null && actor.id==requestedBy) throw ResponseStatusException(HttpStatus.CONFLICT,"Requester cannot decide their own approval")
  val changed=jdbc.update("update approvals set status=?,decided_by=?,decided_at=now(),decision_note=? where id=? and status='PENDING'",r.decision,actor.id,r.note,id)
  if(changed==0) throw ResponseStatusException(HttpStatus.CONFLICT,"Approval is not pending")
  return mapOf("id" to id,"status" to r.decision)
 }
}
