package org.nhrc.grants.workbench

import org.springframework.core.env.Environment
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class FundingSearchInput(val source: String,val query: String)

@Service
class FundingDiscoveryService(private val store: WorkbenchStore,private val records: WorkbenchRecords,private val client: FundingSourceClient,transactionManager: PlatformTransactionManager,private val environment: Environment) {
    private val transaction=TransactionTemplate(transactionManager)
    fun sources(actor: GrantActor): GrantRow {
        actor.requireAny(WorkbenchCatalogue.allBusiness)
        return mapOf("sources" to listOf(
            mapOf("code" to "GRANTS_GOV","name" to "Grants.gov","configured" to true,"requiresKey" to false,"capability" to "Public call search and opportunity details"),
            mapOf("code" to "SIMPLER_GRANTS_GOV","name" to "Simpler.Grants.gov","configured" to client.simplerConfigured(),"requiresKey" to true,"capability" to "Public call search")
        ),"canImport" to actor.hasAny(setOf("GRANTS_OFFICER")),"limitPerRun" to 100,
            "scheduledDiscoveryEnabled" to environment.getProperty("nhrc.discovery.enabled",Boolean::class.java,false),
            "runs" to store.rows("select r.id,r.source_code,r.search_term,r.status,r.imported_count,r.refreshed_count,r.skipped_count,r.available_count,r.truncated,r.started_at,r.completed_at,r.failure_message,u.display_name started_by_name from grant_source_runs r left join users u on u.id=r.started_by order by r.started_at desc limit 30"))
    }
    fun import(input: FundingSearchInput,actor: GrantActor): GrantRow {
        actor.requireAny(setOf("GRANTS_OFFICER"))
        require(input.source in setOf("GRANTS_GOV","SIMPLER_GRANTS_GOV")) { "Choose a configured source" }
        require(input.query.isNotBlank() && input.query.length<=100) { "Enter a search of 1 to 100 characters" }
        require(input.source!="SIMPLER_GRANTS_GOV" || client.simplerConfigured()) { "Simpler.Grants.gov requires an API key in the server environment" }
        val run=UUID.randomUUID()
        store.jdbc.update("update grant_source_runs set status='INTERRUPTED',completed_at=now(),failure_message='The earlier run exceeded its permitted duration; no completed import was recorded' where source_code=? and status='RUNNING' and started_at<now()-interval '10 minutes'",input.source)
        try { store.jdbc.update("insert into grant_source_runs(id,source_code,search_term,started_by) values (?,?,?,?)",run,input.source,input.query.trim(),actor.id) }
        catch(_: DataIntegrityViolationException) { throw ResponseStatusException(HttpStatus.CONFLICT,"A search for this source is already running") }
        try {
            val batch=client.search(input.source,input.query.trim())
            val result=transaction.execute {
                var inserted=0;var refreshed=0
                for(call in batch.calls) {
                    val existing=store.rows("select opportunity_id from grant_source_records where source_code=? and external_id=? for update",input.source,call.externalId).firstOrNull()
                    val snapshot=store.encode(call.snapshot)
                    if(existing!=null) {
                        val id=store.uuid(existing,"opportunity_id")!!
                        store.jdbc.update("update grant_source_records set source_snapshot=?::jsonb,last_seen_at=now() where source_code=? and external_id=?",snapshot,input.source,call.externalId)
                        store.jdbc.update("update opportunities set source_last_seen_at=now() where id=?",id)
                        refreshed++
                    } else {
                        val id=UUID.randomUUID()
                        val funder=call.agency?.let { name ->
                            store.jdbc.queryForObject("insert into funders(name) values (?) on conflict(name) do update set name=excluded.name returning id",UUID::class.java,name)
                        }
                        store.jdbc.update("insert into opportunities(id,source_type,source_reference,title,funder_id,url,summary,deadline_date,source_last_seen_at,created_by) values (?,?,?,?,?,?,?,?::date,now(),?)",id,"API_"+input.source,call.reference,call.title,funder,call.url,call.summary,call.closeDate?.toString(),actor.id)
                        store.jdbc.update("insert into grant_source_records(source_code,external_id,opportunity_id,source_snapshot) values (?,?,?,?::jsonb)",input.source,call.externalId,id,snapshot)
                        store.registerOwner("opportunities",id,actor)
                        store.event("opportunities",id,"IMPORT_FOR_REVIEW",actor,null,call.snapshot,"Imported from an official funding source. Eligibility and precise closing time require human review")
                        inserted++
                    }
                }
                store.jdbc.update("update grant_source_runs set status='COMPLETED',imported_count=?,refreshed_count=?,skipped_count=?,available_count=?,truncated=?,completed_at=now() where id=?",inserted,refreshed,batch.skipped,batch.available,batch.truncated,run)
                mapOf("runId" to run,"imported" to inserted,"refreshed" to refreshed,"skipped" to batch.skipped,"available" to batch.available,"truncated" to batch.truncated,"message" to if(batch.truncated) "The result limit was reached or the source returned an incomplete page set. Narrow the search for the remaining calls" else "Calls imported for human review. Existing curated records were not overwritten")
            }
            return result ?: throw IllegalStateException("The import did not complete")
        } catch(ex: Exception) {
            val message=when(ex) {
                is IllegalArgumentException,is IllegalStateException -> ex.message?.take(300) ?: "The funding import failed"
                else -> "The source could not be imported. Existing grant records were retained"
            }
            store.jdbc.update("update grant_source_runs set status='FAILED',failure_message=?,completed_at=now() where id=?",message,run)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY,message)
        }
    }
    fun sourceRecord(id: UUID,actor: GrantActor): GrantRow {
        records.get("opportunities",id,actor)
        val source=store.rows("select source_code,external_id,source_snapshot,last_seen_at from grant_source_records where opportunity_id=?",id).firstOrNull()
        return mapOf("source" to source,"notice" to "Source snapshots do not overwrite staff-reviewed call information. Check the official call before making an eligibility decision")
    }
    fun fetchDetails(id: UUID,actor: GrantActor): GrantRow {
        actor.requireAny(setOf("GRANTS_OFFICER"));records.get("opportunities",id,actor)
        val source=store.rows("select source_code,external_id from grant_source_records where opportunity_id=?",id).firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.CONFLICT,"This call has no connected source record")
        require(source["source_code"]=="GRANTS_GOV") { "Detailed retrieval is currently supported for Grants.gov. Use the official source link for this call" }
        val details=client.detail(source["external_id"].toString())
        store.jdbc.update("update grant_source_records set source_snapshot=source_snapshot||jsonb_build_object('details',?::jsonb),last_seen_at=now() where opportunity_id=?",store.encode(details),id)
        store.event("opportunities",id,"REFRESH_SOURCE_DETAILS",actor,null,details,"Refreshed source evidence without changing the curated call")
        return store.wire(mapOf("details" to details,"message" to "Official details retrieved. Review them before updating the call form"))
    }
    @Suppress("UNCHECKED_CAST")
    fun matches(id: UUID,actor: GrantActor): GrantRow {
        val call=records.get("opportunities",id,actor)
        val profiles=store.rows("select p.id,p.user_id,p.expertise,p.methods,p.career_stage,u.display_name from researcher_profiles p join users u on u.id=p.user_id where p.active and u.active order by u.display_name limit 501")
        val results=profiles.take(500).mapNotNull { profile ->
            val terms=((profile["expertise"] as? List<String>).orEmpty()+(profile["methods"] as? List<String>).orEmpty()).distinctBy { it.lowercase() }
            val matched=GrantRules.matchedTerms(terms,call["title"].toString(),call["summary"]?.toString().orEmpty(),(call["keywords"] as? List<String>).orEmpty())
            if(matched.isEmpty()) null else mapOf("researcherProfileId" to profile["id"],"userId" to profile["user_id"],"name" to profile["display_name"],"matchedTerms" to matched,"matchedCount" to matched.size,"profileTermCount" to terms.size,"careerStage" to profile["career_stage"])
        }.sortedByDescending { it["matchedCount"] as Int }.take(20)
        return mapOf("items" to results,"profilesConsidered" to profiles.take(500).size,"truncated" to (profiles.size>500),"method" to "Exact keyword and phrase overlap with researcher-approved expertise and methods. This is not an eligibility decision or an estimate of funding success; no assignment or email is made")
    }
}
