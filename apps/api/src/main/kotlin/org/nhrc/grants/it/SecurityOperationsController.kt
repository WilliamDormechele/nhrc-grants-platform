package org.nhrc.grants.it

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/it/security")
class SecurityOperationsController(private val jdbc:JdbcTemplate){
 @GetMapping("/events") fun events(@RequestParam(required=false) severity:String?):List<Map<String,Any?>> { val s=severity?:"";return jdbc.queryForList("select * from system_events where (?='' or severity=?) order by created_at desc limit 500",s,s) }
 @GetMapping("/privileged") fun privileged():List<Map<String,Any?>> = jdbc.queryForList("select p.*,u.display_name actor from privileged_events p left join users u on u.id=p.actor_user_id order by p.occurred_at desc limit 500")
}
