package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin/settings")
class SettingsController(private val jdbc:JdbcTemplate){
 @GetMapping fun settings():List<Map<String,Any?>> = jdbc.queryForList("select setting_key,case when sensitive then '[PROTECTED]' else setting_value end setting_value,value_type,description,sensitive,updated_at from system_settings order by setting_key")
}
