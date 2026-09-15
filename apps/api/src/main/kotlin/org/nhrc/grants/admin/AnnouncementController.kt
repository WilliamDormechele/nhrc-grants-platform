package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/announcements")
class AnnouncementController(private val jdbc:JdbcTemplate){
 @GetMapping fun list():List<Map<String,Any?>> = jdbc.queryForList("select * from system_announcements where active=true and starts_at<=now() and (expires_at is null or expires_at>=now()) order by severity desc,created_at desc")
}
