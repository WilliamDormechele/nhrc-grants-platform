package org.nhrc.grants.finance

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/finance")
class FinanceRegistersController(private val jdbc:JdbcTemplate){
 @GetMapping("/expenditure") fun expenditure(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q?:""}%"; return jdbc.queryForList("select e.*,a.reference award_reference from expenditures e join awards a on a.id=e.award_id where lower(coalesce(e.description,'')) like lower(?) or lower(coalesce(e.finance_reference,'')) like lower(?) order by e.transaction_date desc limit 300",t,t) }
 @GetMapping("/commitments") fun commitments(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select c.*,a.reference award_reference from commitments c join awards a on a.id=c.award_id where (?='' or c.status=?) order by c.expected_date nulls last limit 300",s,s) }
 @GetMapping("/partner-advances") fun advances(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select pa.*,p.name partner_name,a.reference award_reference from partner_advances pa join award_partners ap on ap.id=pa.award_partner_id join partners p on p.id=ap.partner_id join awards a on a.id=ap.award_id where (?='' or pa.status=?) order by pa.retirement_due_date nulls last limit 300",s,s) }
}
