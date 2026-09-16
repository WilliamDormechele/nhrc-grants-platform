package org.nhrc.grants.people

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/people")
class PeopleController(private val jdbc:JdbcTemplate){
 @GetMapping("/researchers") fun researchers(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q?:""}%";return jdbc.queryForList("select rp.id,u.display_name,u.email,u.job_title,ou.name unit,rp.orcid,rp.expertise,rp.career_stage,rp.active from researcher_profiles rp join users u on u.id=rp.user_id left join organisation_units ou on ou.id=u.organisation_unit_id where lower(u.display_name) like lower(?) or lower(u.email) like lower(?) order by u.display_name limit 300",t,t) }
 @GetMapping("/partners") fun partners(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q?:""}%";return jdbc.queryForList("select * from partners where lower(name) like lower(?) order by name limit 300",t) }
 @GetMapping("/funders") fun funders(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q?:""}%";return jdbc.queryForList("select * from funders where lower(name) like lower(?) order by name limit 300",t) }
}
