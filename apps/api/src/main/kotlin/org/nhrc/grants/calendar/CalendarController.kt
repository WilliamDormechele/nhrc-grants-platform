package org.nhrc.grants.calendar

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/calendar")
class CalendarController(private val jdbc:JdbcTemplate){
 @GetMapping fun calendar(@RequestParam(required=false) userId:UUID?):List<Map<String,Any?>>{
  val sql="""select 'APPLICATION' item_type,id,reference title,deadline_at::date event_date,stage status from applications where deadline_at is not null
  union all select 'DELIVERABLE',d.id,d.title,d.due_date,d.status from award_deliverables d
  union all select 'REPORT',r.id,r.report_type,r.due_date,r.status from reports r
  union all select 'COMPLIANCE',c.id,c.compliance_type,coalesce(c.renewal_due_date,c.expiry_date),c.status from compliance_records c where coalesce(c.renewal_due_date,c.expiry_date) is not null
  order by event_date limit 500"""
  return jdbc.queryForList(sql)
 }
}
