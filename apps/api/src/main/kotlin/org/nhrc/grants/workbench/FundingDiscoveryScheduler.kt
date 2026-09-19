package org.nhrc.grants.workbench

import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class FundingDiscoveryScheduler(
    private val store: WorkbenchStore,
    private val discovery: FundingDiscoveryService,
    private val environment: Environment
) {
    @Scheduled(fixedDelayString = "\${GRANT_DISCOVERY_POLL_MS:3600000}")
    fun runDueProfiles() {
        if(environment.getProperty("APP_ENV","development")=="test") return
        if(!environment.getProperty("GRANT_DISCOVERY_AUTO_ENABLED","true").toBoolean()) return

        val system=store.rows("select id,display_name from users where email='grant.discovery@nhrc.system' and active").firstOrNull() ?: return
        val actor=GrantActor(UUID.fromString(system["id"].toString()),system["display_name"].toString(),setOf("SYSTEM_AUTOMATION"),false)
        val profiles=store.rows("""select id,name,source_code,search_term,interval_hours
            from funding_search_profiles
            where enabled and next_run_at<=now()
            order by next_run_at
            limit 10""")
        for(profile in profiles) {
            val id=UUID.fromString(profile["id"].toString())
            val source=profile["source_code"].toString()
            val term=profile["search_term"].toString()
            val hours=(profile["interval_hours"] as Number).toInt()
            try {
                val result=discovery.importAutomated(FundingSearchInput(source,term),actor)
                store.jdbc.update("""update funding_search_profiles set last_run_at=now(),next_run_at=now()+(?||' hours')::interval,
                    last_status='COMPLETED',last_message=?,updated_at=now() where id=?""",
                    hours,result["message"]?.toString()?.take(1000),id)
            } catch(ex: Exception) {
                store.jdbc.update("""update funding_search_profiles set last_run_at=now(),next_run_at=now()+(?||' hours')::interval,
                    last_status='FAILED',last_message=?,updated_at=now() where id=?""",
                    hours,(ex.message?:"Automatic funding search failed").take(1000),id)
            }
        }
    }
}
