package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/configuration")
class ConfigurationController(private val jdbc:JdbcTemplate){
 @GetMapping("/workflows") fun workflows():List<Map<String,Any?>> = jdbc.queryForList("select w.id,w.code,w.name,w.entity_type,w.active,count(s.id) step_count from workflow_definitions w left join workflow_steps s on s.workflow_id=w.id group by w.id order by w.name")
 @GetMapping("/approval-rules") fun approvalRules():List<Map<String,Any?>> = jdbc.queryForList("select * from approval_rules where active=true order by entity_type,sequence_no,name")
 @GetMapping("/forms") fun forms():List<Map<String,Any?>> = jdbc.queryForList("select id,code,name,entity_type,version_no,active from form_definitions order by name")
 @GetMapping("/templates") fun templates():List<Map<String,Any?>> = jdbc.queryForList("select * from templates where active=true order by template_type,name")
 @GetMapping("/features") fun features():List<Map<String,Any?>> = jdbc.queryForList("select * from feature_flags order by name")
}
