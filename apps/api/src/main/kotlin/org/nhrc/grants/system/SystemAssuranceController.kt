package org.nhrc.grants.system

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/system")
class SystemAssuranceController(private val jdbc:JdbcTemplate){
 @GetMapping("/assurance") fun assurance():Map<String,Any> = mapOf(
  "unacknowledgedCriticalEvents" to (jdbc.queryForObject("select count(*) from system_events where severity='CRITICAL' and acknowledged_at is null",Long::class.java)?:0),
  "lastSuccessfulBackup" to jdbc.queryForList("select completed_at,verification_status from backup_runs where status='SUCCESS' order by completed_at desc nulls last limit 1"),
  "openSupportTickets" to (jdbc.queryForObject("select count(*) from support_tickets where status not in ('RESOLVED','CLOSED')",Long::class.java)?:0),
  "accessReviewsDue" to (jdbc.queryForObject("select count(*) from access_reviews where decision is null and due_date<=current_date",Long::class.java)?:0)
 )
}
