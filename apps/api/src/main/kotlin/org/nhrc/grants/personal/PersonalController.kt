package org.nhrc.grants.personal

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/personal")
class PersonalController(private val jdbc:JdbcTemplate){
 @GetMapping("/{userId}/work") fun work(@PathVariable userId:UUID):Map<String,Any> = mapOf(
  "assignments" to jdbc.queryForList("select aa.*,a.reference,a.title,a.deadline_at from application_assignments aa join applications a on a.id=aa.application_id where aa.researcher_id=? order by a.deadline_at nulls last",userId),
  "deliverables" to jdbc.queryForList("select d.*,a.reference award_reference from award_deliverables d join awards a on a.id=d.award_id where d.owner_user_id=? and d.status not in ('ACCEPTED','CLOSED') order by d.due_date",userId),
  "notifications" to jdbc.queryForList("select * from notifications where user_id=? and read_at is null order by created_at desc limit 100",userId)
 )
}
