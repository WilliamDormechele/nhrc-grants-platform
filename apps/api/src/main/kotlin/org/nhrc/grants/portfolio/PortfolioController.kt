package org.nhrc.grants.portfolio

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(private val jdbc:JdbcTemplate){
 @GetMapping("/ending") fun ending():List<Map<String,Any?>> = jdbc.queryForList("select reference,title,end_date,currency,nhrc_allocation,status from awards where status not in ('CLOSED','CANCELLED') and end_date<=current_date+365 order by end_date limit 300")
 @GetMapping("/conversion") fun conversion():List<Map<String,Any?>> = jdbc.queryForList("select stage,count(*) records from applications group by stage order by min(created_at)")
 @GetMapping("/concentration") fun concentration():List<Map<String,Any?>> = jdbc.queryForList("select f.name,coalesce(sum(a.nhrc_allocation),0) value from awards a join funders f on f.id=a.funder_id where a.status not in ('CLOSED','CANCELLED') group by f.id order by value desc limit 20")
}
