package org.nhrc.grants.award

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/award-management/due-diligence")
class DueDiligenceController(private val jdbc:JdbcTemplate){
 @GetMapping fun list(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:"";return jdbc.queryForList("select * from due_diligence_reviews where (?='' or status=?) order by expires_at nulls last,created_at desc limit 300",s,s) }
}
