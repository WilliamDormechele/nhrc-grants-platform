package org.nhrc.grants.workbench

import org.springframework.stereotype.Service

@Service
class WorkbenchOperations(private val store: WorkbenchStore) {
    private val admins=setOf("ADMIN","SUPERADMIN")
    private val governance=setOf("GOVERNANCE_OFFICER","DIRECTOR","ADMIN")
    private val auditReaders=setOf("AUDITOR","DIRECTOR","ADMIN","SUPERADMIN")

    fun list(key:String,actor:GrantActor):List<GrantRow> = when(key) {
        "users" -> {
            actor.requireAny(admins)
            store.rows("""
                select u.id,u.email,u.display_name,u.active,o.name organisation_unit,
                       coalesce(string_agg(distinct r.name,', '),'') roles
                from users u left join organisation_units o on o.id=u.organisation_unit_id
                left join user_roles ur on ur.user_id=u.id and (ur.valid_until is null or ur.valid_until>=now())
                left join roles r on r.id=ur.role_id
                group by u.id,o.name order by u.display_name limit 300
            """.trimIndent())
        }
        "delegations" -> {
            actor.requireAny(setOf("DIRECTOR","ADMIN","SUPERADMIN"))
            store.rows("""select d.*,a.display_name delegator,b.display_name delegate
                from delegations d join users a on a.id=d.delegator_id join users b on b.id=d.delegate_id
                order by d.valid_until desc limit 300""")
        }
        "access-reviews" -> {
            actor.requireAny(admins)
            store.rows("""select ar.*,u.display_name,r.name role_name from access_reviews ar
                join users u on u.id=ar.user_id join roles r on r.id=ar.role_id
                order by ar.due_date limit 300""")
        }
        "audit" -> {
            actor.requireAny(auditReaders)
            store.rows("""select a.id,a.occurred_at,a.action,a.entity_type,a.entity_id,a.reason,u.display_name actor
                from audit_events a left join users u on u.id=a.actor_user_id
                order by a.occurred_at desc limit 500""")
        }
        "templates" -> {
            actor.requireAny(admins)
            store.rows("select id,template_type,name,version_no,active from templates where active=true order by template_type,name")
        }
        "features" -> {
            actor.requireAny(admins)
            store.rows("select id,name,enabled,description from feature_flags order by name")
        }
        "integrations" -> {
            actor.requireAny(admins)
            store.rows("""select id,name,integration_type,status,last_checked_at,last_success_at,last_error
                from integration_registry order by name""")
        }
        "backups" -> {
            actor.requireAny(admins)
            store.rows("select id,backup_type,status,started_at,completed_at,location_reference from backup_runs order by started_at desc limit 200")
        }
        "security-events" -> {
            actor.requireAny(admins)
            store.rows("select id,event_type,severity,status,summary,created_at,resolved_at from system_events order by created_at desc limit 500")
        }
        "privileged" -> {
            actor.requireAny(admins)
            store.rows("""select p.id,p.action,p.entity_type,p.entity_id,p.occurred_at,p.reason,u.display_name actor
                from privileged_events p left join users u on u.id=p.actor_user_id
                order by p.occurred_at desc limit 500""")
        }
        "declarations" -> {
            actor.requireAny(governance)
            store.rows("""select d.id,d.declaration_type,d.status,d.declared_at,d.reviewed_at,d.notes,u.display_name
                from declarations d join users u on u.id=d.user_id order by d.declared_at desc limit 300""")
        }
        "compliance" -> {
            actor.requireAny(governance)
            store.rows("""select id,compliance_type,authority,reference,status,approval_date,expiry_date,renewal_due_date
                from compliance_records order by renewal_due_date nulls last,expiry_date nulls last limit 300""")
        }
        else -> throw IllegalArgumentException("Unsupported operational workspace")
    }
}
