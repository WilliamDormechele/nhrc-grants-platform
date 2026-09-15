package org.nhrc.grants.it

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/it/backups")
class BackupController(private val jdbc:JdbcTemplate){
 @GetMapping fun backups():List<Map<String,Any?>> = jdbc.queryForList("select * from backup_runs order by started_at desc limit 200")
 @GetMapping("/recovery-tests") fun recoveryTests():List<Map<String,Any?>> = jdbc.queryForList("select r.*,b.backup_type,b.completed_at backup_completed_at from recovery_tests r left join backup_runs b on b.id=r.backup_run_id order by r.tested_at desc limit 200")
}
