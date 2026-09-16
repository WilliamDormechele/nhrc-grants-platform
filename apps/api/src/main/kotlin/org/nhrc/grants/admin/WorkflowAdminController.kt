package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/workflows")
class WorkflowAdminController(private val jdbc:JdbcTemplate){
 @GetMapping("/steps") fun steps():List<Map<String,Any?>> = jdbc.queryForList("select w.code workflow_code,w.name workflow_name,s.* from workflow_steps s join workflow_definitions w on w.id=s.workflow_id order by w.name,s.step_order")
}
