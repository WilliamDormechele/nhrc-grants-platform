package org.nhrc.grants.discovery

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class DiscoveryReviewRequest(val decision:String,val reviewerId:UUID?=null,val note:String?=null)
data class SourceUpdateRequest(val enabled:Boolean?=null,val scheduleEnabled:Boolean?=null,val queryTerms:String?=null,val fetchLimit:Int?=null)

@Service
class OpportunityDiscoveryService(
    private val jdbc: JdbcTemplate,
    private val adapters: List<ExternalOpportunityAdapter>,
    private val redis: StringRedisTemplate
) {
    fun sources(): List<Map<String,Any?>> = jdbc.queryForList(
        """select id,code,name,adapter_type,endpoint_url,enabled,schedule_enabled,query_terms,fetch_limit,trust_level,
                  last_run_at,last_success_at,last_failure_at,last_failure_message
           from opportunity_sources order by name"""
    )

    fun runs(limit:Int=50): List<Map<String,Any?>> = jdbc.queryForList(
        """select r.*,s.code source_code,s.name source_name
           from opportunity_discovery_runs r left join opportunity_sources s on s.id=r.source_id
           order by r.started_at desc limit ?""", limit.coerceIn(1,500)
    )

    fun evidence(opportunityId:UUID?, limit:Int=100): List<Map<String,Any?>> =
        if(opportunityId==null) jdbc.queryForList(
            """select e.id,e.run_id,e.opportunity_id,e.external_id,e.title,e.canonical_url,e.source_status,e.validation_status,
                      e.open_at,e.close_at,e.payload_hash,e.fetched_at,s.code source_code,s.name source_name
               from opportunity_source_evidence e join opportunity_sources s on s.id=e.source_id
               order by e.fetched_at desc limit ?""",limit.coerceIn(1,500)
        ) else jdbc.queryForList(
            """select e.id,e.run_id,e.opportunity_id,e.external_id,e.title,e.canonical_url,e.source_status,e.validation_status,
                      e.open_at,e.close_at,e.payload_hash,e.fetched_at,s.code source_code,s.name source_name
               from opportunity_source_evidence e join opportunity_sources s on s.id=e.source_id
               where e.opportunity_id=? order by e.fetched_at desc limit ?""",opportunityId,limit.coerceIn(1,500)
        )

    fun evidencePayload(id:UUID): Map<String,Any?> =
        jdbc.queryForMap(
            """select e.id,e.external_id,e.title,e.canonical_url,e.raw_payload,e.fetched_at,s.code source_code,s.name source_name
               from opportunity_source_evidence e join opportunity_sources s on s.id=e.source_id where e.id=?""",id
        )

    fun runAll(trigger:String="MANUAL"): List<DiscoveryRunSummary> =
        loadSources(enabledOnly=true).map { runSource(it,trigger) }

    fun runOne(code:String,trigger:String="MANUAL"): DiscoveryRunSummary {
        val source=loadSources(enabledOnly=false).firstOrNull { it.code.equals(code,true) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Opportunity source not found")
        if(!source.enabled) throw ResponseStatusException(HttpStatus.CONFLICT,"Opportunity source is disabled")
        return runSource(source,trigger)
    }

    fun runScheduled(): List<DiscoveryRunSummary> {
        val lockKey="nhrc:grants:opportunity-discovery:schedule"
        val token=UUID.randomUUID().toString()
        val locked=redis.opsForValue().setIfAbsent(lockKey,token,Duration.ofMinutes(30)) == true
        if(!locked) return emptyList()
        return try {
            loadSources(enabledOnly=true).filter { it.scheduleEnabled }.map { runSource(it,"SCHEDULED") }
        } finally {
            if(redis.opsForValue().get(lockKey)==token) redis.delete(lockKey)
        }
    }

    @Transactional
    fun updateSource(code:String,r:SourceUpdateRequest): Map<String,Any?> {
        if(r.fetchLimit!=null && r.fetchLimit !in 1..500) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Fetch limit must be between 1 and 500")
        val changed=jdbc.update(
            """update opportunity_sources set
               enabled=coalesce(?,enabled),schedule_enabled=coalesce(?,schedule_enabled),
               query_terms=coalesce(?,query_terms),fetch_limit=coalesce(?,fetch_limit),updated_at=now()
               where upper(code)=upper(?)""",
            r.enabled,r.scheduleEnabled,r.queryTerms,r.fetchLimit,code
        )
        if(changed==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Opportunity source not found")
        return sources().first { (it["code"] ?: "").toString().equals(code,true) }
    }

    @Transactional
    fun reviewOpportunity(id:UUID,r:DiscoveryReviewRequest): Map<String,Any?> {
        if(r.decision !in setOf("ACCEPTED","REJECTED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Decision must be ACCEPTED or REJECTED")
        val changed=jdbc.update(
            """update opportunities set discovery_review_status=?,discovery_reviewed_by=?,discovery_reviewed_at=now(),
               discovery_review_note=?,
               status=case when ?='REJECTED' then 'DISMISSED'
                           when ?='ACCEPTED' and opens_at is not null and opens_at>now() then 'UPCOMING'
                           when ?='ACCEPTED' then 'OPEN' else status end,
               updated_at=now()
               where id=? and discovery_source_id is not null""",
            r.decision,r.reviewerId,r.note,r.decision,r.decision,r.decision,id
        )
        if(changed==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Discovered opportunity not found")
        return jdbc.queryForMap("""select id,title,status,discovery_review_status,discovery_reviewed_at from opportunities where id=?""",id)
    }

    private fun runSource(source:OpportunitySourceConfig,trigger:String): DiscoveryRunSummary {
        val adapter=adapters.firstOrNull { it.supports(source) }
            ?: return failedRun(source,trigger,"No adapter configured for " + source.adapterType)
        val runId=UUID.randomUUID()
        jdbc.update("""insert into opportunity_discovery_runs(id,source_id,trigger_type,status) values (?,?,?,'RUNNING')""",runId,source.id,trigger)
        jdbc.update("update opportunity_sources set last_run_at=now(),updated_at=now() where id=?",source.id)
        return try {
            val result=adapter.fetch(source)
            var created=0;var updated=0;var duplicates=0;var rejected=0
            for(item in result.normalized){
                val validation=validate(item)
                if(validation.startsWith("REJECTED")){
                    rejected++
                    saveEvidence(runId,source,item,null,validation)
                    continue
                }
                val persisted=persist(source,item)
                if(persisted.created) created++ else {
                    updated++
                    if(persisted.duplicate) duplicates++
                }
                saveEvidence(runId,source,item,persisted.opportunityId,validation)
                applyMatching(persisted.opportunityId,item)
            }
            jdbc.update(
                """update opportunity_discovery_runs set completed_at=now(),status='SUCCESS',fetched_count=?,normalized_count=?,
                   created_count=?,updated_count=?,duplicate_count=?,rejected_count=? where id=?""",
                result.fetched,result.normalized.size,created,updated,duplicates,rejected,runId
            )
            jdbc.update("update opportunity_sources set last_success_at=now(),last_failure_message=null,updated_at=now() where id=?",source.id)
            jdbc.update("update integration_registry set status='HEALTHY',last_success_at=now(),last_failure_message=null where code=?",source.code)
            if(created>0) notifyGrantsOfficers(runId,source,created)
            DiscoveryRunSummary(runId,source.code,"SUCCESS",result.fetched,result.normalized.size,created,updated,duplicates,rejected)
        } catch(e:Exception){
            val msg=(e.message ?: e.javaClass.simpleName).take(1000)
            jdbc.update("update opportunity_discovery_runs set completed_at=now(),status='FAILED',error_message=? where id=?",msg,runId)
            jdbc.update("update opportunity_sources set last_failure_at=now(),last_failure_message=?,updated_at=now() where id=?",msg,source.id)
            jdbc.update("update integration_registry set status='DEGRADED',last_failure_at=now(),last_failure_message=? where code=?",msg,source.code)
            DiscoveryRunSummary(runId,source.code,"FAILED",0,0,0,0,0,0,msg)
        }
    }

    private fun failedRun(source:OpportunitySourceConfig,trigger:String,message:String):DiscoveryRunSummary{
        val id=UUID.randomUUID()
        jdbc.update("""insert into opportunity_discovery_runs(id,source_id,trigger_type,status,completed_at,error_message) values (?,?,?,'FAILED',now(),?)""",id,source.id,trigger,message)
        return DiscoveryRunSummary(id,source.code,"FAILED",0,0,0,0,0,0,message)
    }

    private fun validate(item:ExternalOpportunity):String{
        if(item.externalId.isBlank()||item.title.isBlank()) return "REJECTED_MISSING_ID_OR_TITLE"
        val now=OffsetDateTime.now(ZoneOffset.UTC)
        if(item.openAt!=null && item.closeAt!=null && item.openAt.isAfter(item.closeAt)) return "REJECTED_INVALID_DATES"
        if(item.closeAt!=null && item.closeAt.isBefore(now)) return "REJECTED_EXPIRED"
        val status=item.sourceStatus?.uppercase().orEmpty()
        if(status.contains("CLOSED")||status.contains("ARCHIVED")) return "REJECTED_CLOSED"
        if(item.closeAt==null) return "VALID_ROLLING_OR_DEADLINE_UNKNOWN"
        if(item.openAt!=null && item.openAt.isAfter(now)) return "VALID_UPCOMING"
        return "VALID_OPEN"
    }

    private data class Persisted(val opportunityId:UUID,val created:Boolean,val duplicate:Boolean)

    private fun persist(source:OpportunitySourceConfig,item:ExternalOpportunity):Persisted{
        val fingerprint=fingerprint(item)
        val direct=jdbc.queryForList(
            "select id from opportunities where discovery_source_id=? and discovery_external_id=? limit 1",
            source.id,item.externalId
        ).firstOrNull()?.get("id") as UUID?
        val byFingerprint=jdbc.queryForList(
            "select opportunity_id from opportunity_discovery_keys where fingerprint=? limit 1",fingerprint
        ).firstOrNull()?.get("opportunity_id") as UUID?
        val existing=direct ?: byFingerprint
        val funderId=findOrCreateFunder(item.funderName)
        val status=when{
            item.openAt!=null && item.openAt.isAfter(OffsetDateTime.now(ZoneOffset.UTC)) -> "UPCOMING"
            item.sourceStatus?.uppercase()?.contains("FORECAST") == true -> "UPCOMING"
            item.sourceStatus?.uppercase()?.contains("UPCOMING") == true -> "UPCOMING"
            else -> "OPEN"
        }
        if(existing!=null){
            jdbc.update(
                """update opportunities set title=?,funder_id=coalesce(?,funder_id),url=coalesce(?,url),summary=coalesce(?,summary),
                   currency=coalesce(?,currency),amount_min=coalesce(?,amount_min),amount_max=coalesce(?,amount_max),
                   opens_at=coalesce(?,opens_at),deadline_at=coalesce(?,deadline_at),status=case when status='DISMISSED' then status else ? end,
                   discovery_source_id=coalesce(discovery_source_id,?),discovery_external_id=coalesce(discovery_external_id,?),
                   discovery_fingerprint=?,last_seen_external_at=now(),updated_at=now() where id=?""",
                item.title,funderId,item.canonicalUrl,item.summary,item.currency,item.amountMin,item.amountMax,item.openAt,item.closeAt,status,
                source.id,item.externalId,fingerprint,existing
            )
            jdbc.update(
                """insert into opportunity_discovery_keys(fingerprint,opportunity_id) values (?,?)
                   on conflict (fingerprint) do update set last_seen_at=now()""",fingerprint,existing
            )
            return Persisted(existing,false,byFingerprint!=null && direct==null)
        }
        val id=UUID.randomUUID()
        jdbc.update(
            """insert into opportunities(id,source_type,source_reference,title,funder_id,url,summary,currency,amount_min,amount_max,opens_at,deadline_at,
               status,eligibility_status,discovery_review_status,discovery_source_id,discovery_external_id,discovery_fingerprint,last_seen_external_at)
               values (?,'EXTERNAL_DISCOVERY',?,?,?,?,?,?,?,?,?,? ,?,'NOT_REVIEWED','PENDING',?,?,?,now())""",
            id,source.code + ":" + item.externalId,item.title,funderId,item.canonicalUrl,item.summary,item.currency,item.amountMin,item.amountMax,
            item.openAt,item.closeAt,status,source.id,item.externalId,fingerprint
        )
        jdbc.update("insert into opportunity_discovery_keys(fingerprint,opportunity_id) values (?,?) on conflict do nothing",fingerprint,id)
        return Persisted(id,true,false)
    }

    private fun findOrCreateFunder(name:String?):UUID?{
        if(name.isNullOrBlank()) return null
        val clean=name.trim().take(255)
        val existing=jdbc.queryForList("select id from funders where lower(name)=lower(?) limit 1",clean).firstOrNull()?.get("id") as UUID?
        if(existing!=null) return existing
        val id=UUID.randomUUID()
        jdbc.update("insert into funders(id,name,active) values (?,?,true) on conflict (name) do nothing",id,clean)
        return jdbc.queryForList("select id from funders where lower(name)=lower(?) limit 1",clean).firstOrNull()?.get("id") as UUID?
    }

    private fun saveEvidence(runId:UUID,source:OpportunitySourceConfig,item:ExternalOpportunity,opportunityId:UUID?,validation:String){
        jdbc.update(
            """insert into opportunity_source_evidence(run_id,source_id,opportunity_id,external_id,title,canonical_url,source_status,
               validation_status,open_at,close_at,payload_hash,raw_payload) values (?,?,?,?,?,?,?,?,?,?,?,?)""",
            runId,source.id,opportunityId,item.externalId,item.title,item.canonicalUrl,item.sourceStatus,validation,item.openAt,item.closeAt,
            sha256(item.rawPayload),item.rawPayload.take(250000)
        )
    }

    private fun applyMatching(opportunityId:UUID,item:ExternalOpportunity){
        val text=((item.title + " " + (item.summary ?: "")).lowercase())
        val themes=jdbc.queryForList("select id,code,name from research_themes where active=true")
        val matchedThemes=themes.filter { row ->
            val words=keywords((row["name"] ?: "").toString()) + keywords((row["code"] ?: "").toString())
            words.any { text.contains(it) }
        }
        for(t in matchedThemes){
            jdbc.update("insert into opportunity_themes(opportunity_id,theme_id) values (?,?) on conflict do nothing",opportunityId,t["id"])
        }

        val researchers=jdbc.queryForList(
            """select rp.id researcher_profile_id,u.display_name,coalesce(array_to_string(rp.expertise,', '),'') expertise
               from researcher_profiles rp join users u on u.id=rp.user_id where rp.active=true"""
        )
        var best=if(matchedThemes.isNotEmpty()) 45.0 + minOf(30.0,matchedThemes.size*10.0) else 25.0
        val rationaleParts=mutableListOf<String>()
        if(matchedThemes.isNotEmpty()) rationaleParts += "Themes: " + matchedThemes.joinToString(", ") { (it["name"] ?: "").toString() }
        for(r in researchers){
            val expertise=(r["expertise"] ?: "").toString()
            val matched=keywords(expertise).filter { text.contains(it) }.distinct()
            if(matched.isEmpty()) continue
            val score=minOf(95.0,45.0 + matched.size*10.0 + matchedThemes.size*5.0)
            best=maxOf(best,score)
            val rationale="Matched expertise: " + matched.joinToString(", ")
            jdbc.update(
                """insert into opportunity_researcher_matches(opportunity_id,researcher_profile_id,fit_score,rationale,human_confirmed)
                   values (?,?,?,?,false)
                   on conflict (opportunity_id,researcher_profile_id) do update
                   set fit_score=excluded.fit_score,rationale=excluded.rationale
                   where opportunity_researcher_matches.human_confirmed=false""",
                opportunityId,r["researcher_profile_id"],BigDecimal.valueOf(score),rationale
            )
        }
        if(rationaleParts.isEmpty()) rationaleParts += "Rules-based match used title and source summary; human confirmation required."
        jdbc.update(
            "update opportunities set institutional_fit_score=?,fit_rationale=?,updated_at=now() where id=?",
            BigDecimal.valueOf(best),rationaleParts.joinToString(" "),opportunityId
        )
    }

    private fun notifyGrantsOfficers(runId:UUID,source:OpportunitySourceConfig,created:Int){
        val message=created.toString()+" new opportunity record(s) were discovered from "+source.name+" and require NHRC review."
        jdbc.update(
            """insert into notifications(user_id,type,title,message,entity_type,entity_id)
               select distinct ur.user_id,'OPPORTUNITY_DISCOVERY','New funding opportunities discovered',
                      ?,'DISCOVERY_RUN',?
               from user_roles ur join roles r on r.id=ur.role_id join users u on u.id=ur.user_id
               where r.code='GRANTS_OFFICER' and u.active=true and (ur.valid_until is null or ur.valid_until>=now())""",
            message,runId
        )
    }

    private fun keywords(value:String):List<String> =
        value.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length>=4 && it !in setOf("health","research","science","sciences") }
            .distinct()

    private fun fingerprint(item:ExternalOpportunity):String{
        val basis=listOf(item.title.lowercase().replace(Regex("\\s+")," ").trim(),
            item.funderName?.lowercase()?.trim().orEmpty(),
            item.closeAt?.toLocalDate()?.toString().orEmpty()).joinToString("|")
        return sha256(basis)
    }

    private fun sha256(value:String):String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun loadSources(enabledOnly:Boolean):List<OpportunitySourceConfig>{
        val sql="""select id,code,name,adapter_type,endpoint_url,detail_endpoint_url,enabled,schedule_enabled,query_terms,fetch_limit,trust_level
                   from opportunity_sources """ + if(enabledOnly) "where enabled=true order by name" else "order by name"
        return jdbc.query(sql){rs,_ -> OpportunitySourceConfig(
            rs.getObject("id",UUID::class.java),rs.getString("code"),rs.getString("name"),rs.getString("adapter_type"),
            rs.getString("endpoint_url"),rs.getString("detail_endpoint_url"),rs.getBoolean("enabled"),rs.getBoolean("schedule_enabled"),
            rs.getString("query_terms"),rs.getInt("fetch_limit"),rs.getString("trust_level")
        )}
    }
}
