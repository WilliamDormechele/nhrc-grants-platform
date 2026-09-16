package org.nhrc.grants.award

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/award-management/closeout")
class AwardCloseoutController(private val jdbc:JdbcTemplate){
 @GetMapping fun list(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:"";return jdbc.queryForList("select c.*,a.reference award_reference,a.title award_title from closeout_items c join awards a on a.id=c.award_id where (?='' or c.status=?) order by c.due_date nulls last limit 300",s,s) }
}
