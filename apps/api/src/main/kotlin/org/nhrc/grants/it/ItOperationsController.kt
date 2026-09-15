package org.nhrc.grants.it

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/it")
class ItOperationsController(private val jdbc:JdbcTemplate){
 @GetMapping("/integrations") fun integrations():List<Map<String,Any?>> = jdbc.queryForList("select * from integration_registry order by name")
 @GetMapping("/support") fun support(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select t.*,u.display_name requester from support_tickets t left join users u on u.id=t.requester_id where (?='' or t.status=?) order by t.created_at desc limit 300",s,s) }
 @GetMapping("/access-reviews") fun accessReviews():List<Map<String,Any?>> = jdbc.queryForList("select ar.*,u.display_name,r.name role_name from access_reviews ar join users u on u.id=ar.user_id join roles r on r.id=ar.role_id order by ar.due_date limit 300")
}
