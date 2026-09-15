package org.nhrc.grants.superadmin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/superadmin")
class SuperadminController(private val jdbc:JdbcTemplate){
 @GetMapping("/features") fun features():List<Map<String,Any?>> = jdbc.queryForList("select * from feature_flags order by name")
 @GetMapping("/privileged-activity") fun privileged():List<Map<String,Any?>> = jdbc.queryForList("select p.*,u.display_name actor from privileged_events p left join users u on u.id=p.actor_user_id order by p.occurred_at desc limit 500")
 @GetMapping("/announcements") fun announcements():List<Map<String,Any?>> = jdbc.queryForList("select * from system_announcements order by created_at desc limit 200")
}
