package org.nhrc.grants.workbench

import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID

@Service
class WorkbenchConfiguration(private val store: WorkbenchStore) {
    fun forms(actor: GrantActor): GrantRow {
        actor.requireAny(setOf("ADMIN","SUPERADMIN"))
        return mapOf("items" to store.rows("""select id,code,name,entity_type,version_no,schema_json,active,created_at
            from form_definitions where active order by entity_type,name"""))
    }

    fun templates(actor: GrantActor): GrantRow {
        actor.requireAny(WorkbenchCatalogue.platformReaders)
        return mapOf("items" to store.rows("""select id,code,name,template_type,version_no,description,category,content_json,active,created_at
            from templates where active order by category,template_type,name"""))
    }

    fun updateForm(id: UUID,input: GrantRecordInput,actor: GrantActor): GrantRow {
        actor.requireAny(setOf("ADMIN"))
        val before=store.one("form_definitions",id,true)
        val schema=input.values["schema_json"] ?: throw IllegalArgumentException("Form schema is required")
        val name=input.values["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Form name is required")
        val version=(before["version_no"] as Number).toInt()+1
        store.jdbc.update("update form_definitions set name=?,schema_json=?::jsonb,version_no=?,active=true where id=?",name,store.encode(schema),version,id)
        return store.wire(store.one("form_definitions",id))
    }

    fun updateTemplate(id: UUID,input: GrantRecordInput,actor: GrantActor): GrantRow {
        actor.requireAny(setOf("ADMIN"))
        val before=store.one("templates",id,true)
        val content=input.values["content_json"] ?: throw IllegalArgumentException("Template content is required")
        val name=input.values["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Template name is required")
        val description=input.values["description"]?.toString()?.take(4000)
        val version=(before["version_no"] as Number).toInt()+1
        store.jdbc.update("update templates set name=?,description=?,content_json=?::jsonb,version_no=?,active=true where id=?",name,description,store.encode(content),version,id)
        return store.wire(store.one("templates",id))
    }

    fun searchProfiles(actor: GrantActor): GrantRow {
        actor.requireAny(setOf("GRANTS_OFFICER","ADMIN","SUPERADMIN"))
        return mapOf("items" to store.rows("""select id,name,source_code,search_term,enabled,interval_hours,last_run_at,next_run_at,last_status,last_message
            from funding_search_profiles order by name"""))
    }

    fun updateSearchProfile(id: UUID,input: GrantRecordInput,actor: GrantActor): GrantRow {
        actor.requireAny(setOf("GRANTS_OFFICER","ADMIN"))
        val before=store.one("funding_search_profiles",id,true)
        val name=input.values["name"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Profile name is required")
        val source=input.values["source_code"]?.toString()
        if(source !in setOf("GRANTS_GOV","SIMPLER_GRANTS_GOV")) throw IllegalArgumentException("Choose an approved funding source")
        val term=input.values["search_term"]?.toString()?.trim()?.takeIf { it.isNotBlank() && it.length<=100 } ?: throw IllegalArgumentException("Search terms are required")
        val hours=(input.values["interval_hours"] as? Number)?.toInt() ?: input.values["interval_hours"]?.toString()?.toIntOrNull() ?: 24
        if(hours !in 1..720) throw IllegalArgumentException("Search interval must be between 1 and 720 hours")
        val enabled=input.values["enabled"] as? Boolean ?: false
        store.jdbc.update("""update funding_search_profiles set name=?,source_code=?,search_term=?,enabled=?,interval_hours=?,
            next_run_at=case when ? then least(next_run_at,now()) else next_run_at end,updated_at=now() where id=?""",
            name,source,term,enabled,hours,enabled,id)
        return store.wire(store.one("funding_search_profiles",id))
    }
}
