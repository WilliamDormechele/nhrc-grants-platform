package org.nhrc.grants.reference

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/reference")
class ReferenceController(private val jdbc:JdbcTemplate){
 @GetMapping("/funders") fun funders(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q ?: ""}%"; return jdbc.queryForList("select id,name,country_code,website,active from funders where lower(name) like lower(?) order by name limit 300",t) }
 @GetMapping("/researchers") fun researchers(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q ?: ""}%"; return jdbc.queryForList("select r.id,u.display_name,u.email,r.orcid,r.expertise,r.career_stage,r.active from researcher_profiles r join users u on u.id=r.user_id where lower(u.display_name) like lower(?) or lower(u.email) like lower(?) order by u.display_name limit 300",t,t) }
 @GetMapping("/partners") fun partners(@RequestParam(required=false) q:String?):List<Map<String,Any?>> { val t="%${q ?: ""}%"; return jdbc.queryForList("select * from partners where lower(name) like lower(?) order by name limit 300",t) }
 @GetMapping("/themes") fun themes():List<Map<String,Any?>> = jdbc.queryForList("select * from research_themes where active=true order by name")
}
