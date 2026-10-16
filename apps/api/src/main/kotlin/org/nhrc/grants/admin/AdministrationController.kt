package org.nhrc.grants.admin

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin")
class AdministrationController(private val jdbc: JdbcTemplate) {
    @GetMapping("/users")
    fun users(@RequestParam(required=false) q:String?):List<Map<String,Any?>>{
        val term="%${q ?: ""}%"
        return jdbc.queryForList("""
          select u.id,u.email,u.display_name,u.active,o.name organisation_unit,
                 coalesce(string_agg(distinct r.name,', '),'') roles
          from users u left join organisation_units o on o.id=u.organisation_unit_id
          left join user_roles ur on ur.user_id=u.id and (ur.valid_until is null or ur.valid_until>=now())
          left join roles r on r.id=ur.role_id
          where lower(u.display_name) like lower(?) or lower(u.email) like lower(?)
          group by u.id,o.name order by u.display_name limit 300
        """.trimIndent(),term,term)
    }

    @GetMapping("/audit")
    fun audit(@RequestParam(required=false) q:String?):List<Map<String,Any?>>{
        val term="%${q ?: ""}%"
        return jdbc.queryForList("""
          select a.id,a.occurred_at,a.action,a.entity_type,a.entity_id,a.reason,a.actor,a.before_data,a.after_data from (
          select e.id,e.occurred_at,e.action,e.entity_type,e.entity_id,e.reason,coalesce(u.display_name,e.actor_subject) actor,e.previous_value before_data,e.new_value after_data from audit_events e left join users u on u.id=e.actor_user_id
          union all select h.id,h.occurred_at,h.action,h.dataset,h.record_id::text,h.note,u.display_name,h.before_data,h.after_data from operational_history h left join users u on u.id=h.actor_id
          ) a
          where lower(a.action) like lower(?) or lower(a.entity_type) like lower(?) or lower(coalesce(a.reason,'')) like lower(?)
          order by a.occurred_at desc limit 500
        """.trimIndent(),term,term,term)
    }
}

@RestController
@RequestMapping("/api/operations")
class OperationsController(private val jdbc: JdbcTemplate) {
    @GetMapping("/system-events")
    fun events(@RequestParam(required=false) severity:String?):List<Map<String,Any?>>{
        val s=severity ?: ""
        return jdbc.queryForList("select * from system_events where (?='' or severity=?) order by created_at desc limit 300",s,s)
    }
    @GetMapping("/backups")
    fun backups():List<Map<String,Any?>> = jdbc.queryForList("select * from backup_runs order by started_at desc limit 100")
}
