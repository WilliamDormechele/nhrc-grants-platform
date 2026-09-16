package org.nhrc.grants.approval

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class ApprovalDecision(val decision:String,val decidedBy:UUID?,val note:String?)
@RestController
@RequestMapping("/api/approvals")
class ApprovalController(private val jdbc:JdbcTemplate){
 @GetMapping fun list(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select * from approvals where (?='' or status=?) order by created_at desc limit 300",s,s) }
 @PostMapping("/{id}/decision") @Transactional fun decide(@PathVariable id:UUID,@RequestBody r:ApprovalDecision):Map<String,Any>{
  if(r.decision !in setOf("APPROVED","RETURNED","REJECTED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid decision")
  val changed=jdbc.update("update approvals set status=?,decided_by=?,decided_at=now(),decision_note=? where id=? and status='PENDING'",r.decision,r.decidedBy,r.note,id)
  if(changed==0) throw ResponseStatusException(HttpStatus.CONFLICT,"Approval is not pending")
  return mapOf("id" to id,"status" to r.decision)
 }
}
