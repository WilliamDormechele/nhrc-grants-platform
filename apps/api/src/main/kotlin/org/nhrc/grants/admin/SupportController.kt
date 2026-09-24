package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/support")
class SupportController(private val jdbc:JdbcTemplate){
 @GetMapping fun tickets(@RequestParam(required=false) q:String?,@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val t="%${q?:""}%";val s=status?:"";return jdbc.queryForList("select st.*,u.display_name requester from support_tickets st left join users u on u.id=st.requester_id where (lower(st.subject) like lower(?) or lower(st.reference) like lower(?)) and (?='' or st.status=?) order by st.created_at desc limit 300",t,t,s,s) }
}
