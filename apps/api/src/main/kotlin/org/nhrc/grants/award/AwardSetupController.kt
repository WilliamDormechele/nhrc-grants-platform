package org.nhrc.grants.award

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/award-management/setup")
class AwardSetupController(private val jdbc:JdbcTemplate){
 @GetMapping fun pending():List<Map<String,Any?>> = jdbc.queryForList("select a.*,f.name funder from awards a left join funders f on f.id=a.funder_id where a.status='SETUP' order by a.created_at desc limit 300")
}
