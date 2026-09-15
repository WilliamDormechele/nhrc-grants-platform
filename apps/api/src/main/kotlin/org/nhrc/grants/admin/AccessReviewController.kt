package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/access-reviews")
class AccessReviewController(private val jdbc:JdbcTemplate){
 @GetMapping fun list(@RequestParam(required=false) decision:String?):List<Map<String,Any?>> { val d=decision?:"";return jdbc.queryForList("select ar.*,u.display_name,r.name role_name from access_reviews ar join users u on u.id=ar.user_id join roles r on r.id=ar.role_id where (?='' or coalesce(ar.decision,'PENDING')=?) order by ar.due_date limit 300",d,d) }
}
