package org.nhrc.grants.search

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/search")
class GlobalSearchController(private val jdbc:JdbcTemplate){
 @GetMapping fun search(@RequestParam q:String):Map<String,Any>{ val t="%$q%"; return mapOf(
  "opportunities" to jdbc.queryForList("select id,title,status,deadline_at from opportunities where lower(title) like lower(?) order by deadline_at nulls last limit 20",t),
  "applications" to jdbc.queryForList("select id,reference,title,stage,deadline_at from applications where lower(title) like lower(?) or lower(reference) like lower(?) order by deadline_at nulls last limit 20",t,t),
  "awards" to jdbc.queryForList("select id,reference,title,status,end_date from awards where lower(title) like lower(?) or lower(reference) like lower(?) order by end_date nulls last limit 20",t,t),
  "partners" to jdbc.queryForList("select id,name,due_diligence_status from partners where lower(name) like lower(?) order by name limit 20",t),
  "funders" to jdbc.queryForList("select id,name from funders where lower(name) like lower(?) order by name limit 20",t)
 )}
}
