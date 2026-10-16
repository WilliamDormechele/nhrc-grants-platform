package org.nhrc.grants.governance

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/governance")
class GovernanceController(private val jdbc:JdbcTemplate){
 @GetMapping("/compliance") fun compliance(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select * from compliance_records where (?='' or status=?) order by renewal_due_date nulls last,expiry_date nulls last limit 300",s,s) }
 @GetMapping("/declarations") fun declarations(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select d.*,u.display_name from declarations d join users u on u.id=d.user_id where (?='' or d.status=?) order by d.declared_at desc limit 300",s,s) }
 @GetMapping("/due-diligence") fun diligence(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select * from due_diligence_reviews where (?='' or status=?) order by expires_at nulls last,created_at desc limit 300",s,s) }
}
