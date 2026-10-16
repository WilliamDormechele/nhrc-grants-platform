package org.nhrc.grants.finance

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/finance")
class FinanceController(private val jdbc: JdbcTemplate) {
    @GetMapping("/summary")
    fun summary(): Map<String,Any> {
        val row=jdbc.queryForMap("""
          select coalesce(sum(approved_budget),0) approved_budget,
                 coalesce(sum(cash_received),0) cash_received,
                 coalesce(sum(expenditure),0) expenditure,
                 coalesce(sum(commitments),0) commitments,
                 coalesce(sum(approved_budget-expenditure-commitments),0) uncommitted
          from award_financial_positions
        """.trimIndent())
        return row
    }

    @GetMapping("/receipts")
    fun receipts(@RequestParam(required=false) awardId: UUID?): List<Map<String,Any?>> = if(awardId==null)
        jdbc.queryForList("select * from fund_receipts order by coalesce(received_date,expected_date) desc nulls last limit 300")
    else jdbc.queryForList("select * from fund_receipts where award_id=? order by coalesce(received_date,expected_date) desc",awardId)
}

@RestController
@RequestMapping("/api/procurement")
class ProcurementController(private val jdbc: JdbcTemplate) {
    @GetMapping("/requisitions")
    fun requisitions(@RequestParam(required=false) q:String?,@RequestParam(required=false) status:String?):List<Map<String,Any?>>{
        val term="%${q ?: ""}%"; val s=status ?: ""
        return jdbc.queryForList("""
          select r.id,r.reference,r.description,r.amount,r.currency,r.status,r.requested_at,a.reference award_reference
          from procurement_requisitions r join awards a on a.id=r.award_id
          where (lower(r.reference) like lower(?) or lower(r.description) like lower(?)) and (?='' or r.status=?)
          order by r.requested_at desc limit 300
        """.trimIndent(),term,term,s,s)
    }
}

@RestController
@RequestMapping("/api/laboratory")
class LaboratoryController(private val jdbc: JdbcTemplate) {
    @GetMapping("/items")
    fun items(@RequestParam(required=false) q:String?,@RequestParam(required=false) type:String?):List<Map<String,Any?>>{
        val term="%${q ?: ""}%"; val t=type ?: ""
        return jdbc.queryForList("""
          select l.*,a.reference award_reference from laboratory_items l left join awards a on a.id=l.award_id
          where lower(l.name) like lower(?) and (?='' or l.item_type=?)
          order by coalesce(l.expiry_date,l.calibration_due_date,l.maintenance_due_date) nulls last limit 300
        """.trimIndent(),term,t,t)
    }
}
