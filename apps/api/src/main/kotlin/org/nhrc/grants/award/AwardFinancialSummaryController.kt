package org.nhrc.grants.award

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/award-management/financial-summary")
class AwardFinancialSummaryController(private val jdbc:JdbcTemplate){
 @GetMapping fun list():List<Map<String,Any?>> = jdbc.queryForList("select a.id,a.reference,a.title,a.currency,p.approved_budget,p.cash_received,p.expenditure,p.commitments,(p.approved_budget-p.expenditure-p.commitments) uncommitted_budget,p.reconciled_at from awards a join award_financial_positions p on p.award_id=a.id order by a.end_date nulls last limit 300")
}
