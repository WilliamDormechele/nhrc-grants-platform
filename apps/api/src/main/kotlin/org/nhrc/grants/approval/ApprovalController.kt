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
class ApprovalController(private val jdbc:JdbcTemplate){
 @GetMapping
 fun list(@RequestParam(required=false) status:String?):List<Map<String,Any?>> {
  val s=status?:""
  return jdbc.queryForList("""select a.*,rq.display_name requested_by_name,ass.display_name assigned_user_name,dec.display_name decided_by_name
      from approvals a
      left join users rq on rq.id=a.requested_by
      left join users ass on ass.id=a.assigned_user_id
      left join users dec on dec.id=a.decided_by
      where (?='' or a.status=?)
      order by a.created_at desc limit 300""",s,s)
 }

 @PostMapping @Transactional
 fun create(@RequestBody r:ApprovalCreate):Map<String,Any>{
  if(r.entityType.isBlank()||r.approvalType.isBlank()) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Entity type and approval type are required")
  if(r.sequenceNo<1) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Sequence must be positive")
  val id=UUID.randomUUID()
  jdbc.update("""insert into approvals(id,entity_type,entity_id,approval_type,sequence_no,required_role_code,assigned_user_id,requested_by,status)
      values (?,?,?,?,?,?,?,?,'PENDING')""",id,r.entityType.uppercase(),r.entityId,r.approvalType.uppercase(),r.sequenceNo,r.requiredRoleCode,r.assignedUserId,r.requestedBy)
  return mapOf("id" to id,"status" to "PENDING")
 }

 @PostMapping("/{id}/decision") @Transactional
 fun decide(@PathVariable id:UUID,@RequestBody r:ApprovalDecision):Map<String,Any>{
  if(r.decision !in setOf("APPROVED","RETURNED","REJECTED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid decision")
  val approval=jdbc.queryForMap("select requested_by,status from approvals where id=?",id)
  if(approval["status"]!="PENDING") throw ResponseStatusException(HttpStatus.CONFLICT,"Approval is not pending")
  val requestedBy=approval["requested_by"] as UUID?
  if(r.decidedBy!=null && requestedBy!=null && r.decidedBy==requestedBy) throw ResponseStatusException(HttpStatus.CONFLICT,"Requester cannot decide their own approval")
  val changed=jdbc.update("update approvals set status=?,decided_by=?,decided_at=now(),decision_note=? where id=? and status='PENDING'",r.decision,r.decidedBy,r.note,id)
  if(changed==0) throw ResponseStatusException(HttpStatus.CONFLICT,"Approval is not pending")
  return mapOf("id" to id,"status" to r.decision)
 }
}
