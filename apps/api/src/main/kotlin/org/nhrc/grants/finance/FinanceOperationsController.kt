package org.nhrc.grants.finance

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/finance/operations")
class FinanceOperationsController(private val jdbc:JdbcTemplate){
 @GetMapping("/reports") fun reports(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select f.*,a.reference award_reference from financial_reports f join awards a on a.id=f.award_id where (?='' or f.status=?) order by f.due_date limit 300",s,s) }
 @GetMapping("/forecasts") fun forecasts():List<Map<String,Any?>> = jdbc.queryForList("select f.*,a.reference award_reference from financial_forecasts f join awards a on a.id=f.award_id order by f.forecast_date desc limit 300")
 @GetMapping("/reconciliations") fun reconciliations(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select r.*,a.reference award_reference from reconciliations r join awards a on a.id=r.award_id where (?='' or r.status=?) order by r.period_end desc limit 300",s,s) }
 @GetMapping("/approvals") fun approvals(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select f.*,a.reference award_reference from financial_approvals f left join awards a on a.id=f.award_id where (?='' or f.status=?) order by f.created_at desc limit 300",s,s) }
}
