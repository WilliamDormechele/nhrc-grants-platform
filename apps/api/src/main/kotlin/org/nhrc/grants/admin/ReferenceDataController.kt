package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/reference-data")
class ReferenceDataController(private val jdbc:JdbcTemplate){
 @GetMapping("/themes") fun themes()=jdbc.queryForList("select * from research_themes order by name")
 @GetMapping("/notification-rules") fun notificationRules()=jdbc.queryForList("select * from notification_rules order by event_type,days_before desc nulls last")
 @GetMapping("/calendar-rules") fun calendarRules()=jdbc.queryForList("select * from calendar_rules order by rule_date desc limit 500")
}
