package org.nhrc.grants.performance

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/performance")
class PerformanceController(private val jdbc:JdbcTemplate){
 @GetMapping("/reports") fun reports(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select r.*,a.reference award_reference,a.title award_title from reports r join awards a on a.id=r.award_id where (?='' or r.status=?) order by r.due_date limit 300",s,s) }
 @GetMapping("/outputs") fun outputs(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q?:""}%"; return jdbc.queryForList("select o.*,a.reference award_reference from research_outputs o join awards a on a.id=o.award_id where lower(o.title) like lower(?) order by o.output_date desc nulls last limit 300",t) }
 @GetMapping("/impact") fun impact(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q?:""}%"; return jdbc.queryForList("select i.*,a.reference award_reference from impact_records i join awards a on a.id=i.award_id where lower(i.title) like lower(?) order by i.impact_date desc nulls last limit 300",t) }
 @GetMapping("/closeout") fun closeout(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select c.*,a.reference award_reference,a.title award_title from closeout_items c join awards a on a.id=c.award_id where (?='' or c.status=?) order by c.due_date nulls last limit 300",s,s) }
}
