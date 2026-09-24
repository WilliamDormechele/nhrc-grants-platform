package org.nhrc.grants.organisation

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/organisation")
class OrganisationController(private val jdbc:JdbcTemplate){
 @GetMapping("/units") fun units(@RequestParam(required=false) active:Boolean?):List<Map<String,Any?>> = if(active==null) jdbc.queryForList("select * from organisation_units order by name") else jdbc.queryForList("select * from organisation_units where active=? order by name",active)
 @GetMapping("/roles") fun roles():List<Map<String,Any?>> = jdbc.queryForList("select * from roles order by privileged,name")
}
