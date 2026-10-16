package org.nhrc.grants.executive

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/executive")
class ExecutiveController(private val jdbc:JdbcTemplate){
 @GetMapping("/pipeline") fun pipeline():List<Map<String,Any?>> = jdbc.queryForList("select stage,count(*) count from applications group by stage order by count(*) desc")
 @GetMapping("/funding") fun funding():Map<String,Any> = jdbc.queryForMap("select coalesce(sum(total_award),0) total_award,coalesce(sum(nhrc_allocation),0) nhrc_allocation,coalesce(sum(partner_allocation),0) partner_allocation from awards where status not in ('CLOSED','CANCELLED')")
 @GetMapping("/funders") fun funders():List<Map<String,Any?>> = jdbc.queryForList("select f.name,count(a.id) awards,coalesce(sum(a.nhrc_allocation),0) nhrc_allocation from funders f left join awards a on a.funder_id=f.id and a.status not in ('CLOSED','CANCELLED') group by f.id order by nhrc_allocation desc limit 20")
}
