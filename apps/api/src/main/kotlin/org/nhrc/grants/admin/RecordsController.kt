package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/records")
class RecordsController(private val jdbc:JdbcTemplate){
 @GetMapping("/retention") fun retention(@RequestParam(required=false) status:String?):List<Map<String,Any?>> { val s=status?:""; return jdbc.queryForList("select * from records_retention where (?='' or status=?) order by coalesce(disposal_review_date,archive_date) nulls last limit 300",s,s) }
 @GetMapping("/imports") fun imports():List<Map<String,Any?>> = jdbc.queryForList("select * from data_import_runs order by started_at desc limit 200")
 @GetMapping("/exports") fun exports():List<Map<String,Any?>> = jdbc.queryForList("select * from data_export_runs order by requested_at desc limit 200")
}
