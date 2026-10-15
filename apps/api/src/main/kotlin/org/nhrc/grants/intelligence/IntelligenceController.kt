package org.nhrc.grants.intelligence

import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class AutomationSettings(val enabled: Boolean, val lookaheadDays: Int, val urgentDays: Int)
data class AcknowledgeAlert(val actorId: UUID, val note: String)

@Service
class IntelligenceService(private val jdbc: JdbcTemplate, private val transactions: TransactionTemplate) {
    private val logger = LoggerFactory.getLogger(javaClass)
    fun settings() = jdbc.queryForMap("select enabled,lookahead_days,urgent_days,updated_at from intelligence_automation_settings where id=true")
    fun candidates(): List<Map<String,Any?>> {
        val days = (settings()["lookahead_days"] as Number).toInt()
        return jdbc.queryForList("""
            select * from (
              select 'APPLICATION_DEADLINE' rule_code,id entity_id,title,deadline_at::date due_date,'Pre-Award' group_name,'Applications' workspace
                from applications where stage not in ('SUBMITTED','OUTCOME_RECORDED','AWARDED','CLOSED') and deadline_at is not null
              union all select 'REPORT_DUE',r.id,a.reference || ' · ' || r.report_type,r.due_date,'Performance','Reports'
                from reports r join awards a on a.id=r.award_id where r.status not in ('SUBMITTED','ACCEPTED','CLOSED')
              union all select 'DELIVERABLE_DUE',id,title,due_date,'Award Management','Deliverables'
                from award_deliverables where status not in ('SUBMITTED','ACCEPTED','COMPLETE','COMPLETED','CANCELLED')
              union all select 'COMPLIANCE_RENEWAL',id,compliance_type,coalesce(renewal_due_date,expiry_date),'Research Governance','Ethics & Regulatory Links'
                from compliance_records where status not in ('CLOSED','NOT_APPLICABLE')
              union all select 'AWARD_END',id,reference || ' · ' || title,end_date,'Performance','Closeout'
                from awards where status not in ('CLOSED','CANCELLED')
            ) obligations where due_date <= current_date + ? order by due_date,rule_code,entity_id
        """, days)
    }
    @Scheduled(fixedDelayString="\${nhrc.intelligence.delay-ms:900000}",initialDelayString="\${nhrc.intelligence.initial-delay-ms:60000}")
    fun scheduled() { try { if (settings()["enabled"] == true) run("SCHEDULED") } catch(ex:Exception) { logger.error("Intelligence automation failed",ex) } }
    fun run(trigger: String): Map<String,Any> {
        val runId=UUID.randomUUID()
        jdbc.update("insert into intelligence_automation_runs(id,trigger_type,status) values (?,?,'RUNNING')",runId,trigger)
        try {
            return transactions.execute {
                val locked=jdbc.queryForObject("select pg_try_advisory_xact_lock(78143420)",Boolean::class.java) == true
                if (!locked) {
                    jdbc.update("update intelligence_automation_runs set status='SKIPPED',completed_at=now() where id=?",runId)
                    return@execute mapOf("id" to runId,"status" to "SKIPPED","created" to 0,"detected" to 0,"resolved" to 0)
                }
                val config=settings()
                if(config["enabled"] != true) {
                    jdbc.update("update intelligence_automation_runs set status='DISABLED',completed_at=now() where id=?",runId)
                    return@execute mapOf("id" to runId,"status" to "DISABLED","created" to 0,"detected" to 0,"resolved" to 0)
                }
                val started=jdbc.queryForObject("select current_timestamp",OffsetDateTime::class.java)!!
                val today=jdbc.queryForObject("select current_date",LocalDate::class.java)!!
                val rows=candidates(); var created=0
                rows.forEach { r ->
                    val date=LocalDate.parse(r["due_date"].toString())
                    val severity=IntelligenceRules.severity(date,today,(config["urgent_days"] as Number).toInt())
                    created+=jdbc.update("""insert into intelligence_alerts(rule_code,entity_id,title,due_date,severity,workspace,group_name)
                      values (?,?,?,?,?,?,?) on conflict(rule_code,entity_id,due_date) do nothing""",
                      r["rule_code"],r["entity_id"],r["title"],date,severity,r["workspace"],r["group_name"])
                    jdbc.update("""update intelligence_alerts set title=?,severity=?,last_seen_at=?,
                      status=case when status='RESOLVED' then 'OPEN' else status end
                      where rule_code=? and entity_id=? and due_date=?""",r["title"],severity,started,r["rule_code"],r["entity_id"],date)
                }
                val resolved=jdbc.update("update intelligence_alerts set status='RESOLVED' where status<>'RESOLVED' and last_seen_at < ?",started)
                jdbc.update("update intelligence_automation_runs set status='SUCCESS',completed_at=now(),detected=?,created=?,resolved=? where id=?",rows.size,created,resolved,runId)
                mapOf("id" to runId,"status" to "SUCCESS","detected" to rows.size,"created" to created,"resolved" to resolved)
            }!!
        } catch(ex:Exception) {
            jdbc.update("update intelligence_automation_runs set status='FAILED',completed_at=now(),error=? where id=?",ex.message?.take(1000),runId)
            throw ex
        }
    }
    fun forecasts(): List<Map<String,Any?>> {
        val today=jdbc.queryForObject("select current_date",LocalDate::class.java)!!
        return jdbc.queryForList("""select a.id,a.reference,a.title,a.currency,a.start_date,a.end_date,
          p.approved_budget,p.cash_received,p.expenditure,p.commitments,p.reconciled_at
          from awards a join award_financial_positions p on p.award_id=a.id where a.status not in ('CLOSED','CANCELLED') order by a.reference""")
          .map { r -> r + IntelligenceRules.forecast(r["approved_budget"] as BigDecimal,r["expenditure"] as BigDecimal,r["commitments"] as BigDecimal,
            r["start_date"]?.let { LocalDate.parse(it.toString()) },r["end_date"]?.let { LocalDate.parse(it.toString()) },today) }
    }
}

@RestController
@RequestMapping("/api/intelligence")
class IntelligenceController(private val service:IntelligenceService,private val jdbc:JdbcTemplate,private val environment:Environment) {
    @GetMapping("/overview") fun overview():Map<String,Any> = mapOf(
      "engine" to "NHRC_RULES_V1", "generatedAt" to OffsetDateTime.now().toString(),
      "settings" to service.settings(), "alerts" to jdbc.queryForList("select * from intelligence_alerts order by case status when 'OPEN' then 0 when 'ACKNOWLEDGED' then 1 else 2 end,due_date limit 500"),
      "runs" to jdbc.queryForList("select * from intelligence_automation_runs order by started_at desc limit 20"),
      "forecasts" to service.forecasts(),
      "funding" to jdbc.queryForList("select id,title,status,discovery_review_status,url,deadline_at,institutional_fit_score,fit_rationale,eligibility_status,fit_model_version from opportunities where status not in ('CLOSED','DISMISSED') and (deadline_at is null or deadline_at>now()) order by institutional_fit_score desc nulls last limit 10"),
      "gates" to listOf(
        mapOf("name" to "Authenticated institutional roles", "status" to if(environment.getProperty("nhrc.auth.mode","development")=="development") "BLOCKED" else "AUTHENTICATED", "detail" to "Named local UAT accounts, role-based navigation, protected API writes and CSRF checks are active. Institutional acceptance and production identity arrangements remain separate."),
        mapOf("name" to "Local generative AI", "status" to "HUMAN_REVIEW_REQUIRED", "detail" to "Qwen 3.5 4B drafts answers from a limited register snapshot. Check live model status and factual claims. Document review and institutional AI acceptance remain pending."),
        mapOf("name" to "Donor and accounting integrations", "status" to "NOT_CONNECTED", "detail" to "No live submission, bank payment, ERP posting or email delivery is performed."),
        mapOf("name" to "Institutional acceptance", "status" to "PENDING", "detail" to "Grants, Researcher, Director, Finance, Procurement, Laboratory, Governance and IT journeys require user acceptance.")
      ))
    @PostMapping("/automation/run") fun run() = service.run("MANUAL")
    @PutMapping("/automation/settings") fun settings(@RequestBody r:AutomationSettings):Map<String,Any?> {
        require(r.lookaheadDays in 1..180 && r.urgentDays in 0..30 && r.urgentDays <= r.lookaheadDays) { "Lookahead must be 1–180 days; urgent window 0–30 days and no greater than lookahead." }
        jdbc.update("update intelligence_automation_settings set enabled=?,lookahead_days=?,urgent_days=?,updated_at=now() where id=true",r.enabled,r.lookaheadDays,r.urgentDays)
        return service.settings()
    }
    @PostMapping("/alerts/{id}/acknowledge") fun acknowledge(@PathVariable id:UUID,@RequestBody r:AcknowledgeAlert):Map<String,Any> {
        require(r.note.isNotBlank() && r.note.length<=2000) { "A review note of 1–2000 characters is required." }
        if(jdbc.queryForObject("select count(*) from users where id=? and active=true",Long::class.java,r.actorId)!=1L)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Select an active demonstration reviewer")
        val updated=jdbc.update("update intelligence_alerts set status='ACKNOWLEDGED',acknowledged_by=?,acknowledged_at=now(),note=? where id=? and status='OPEN'",r.actorId,r.note,id)
        if(updated!=1) throw ResponseStatusException(HttpStatus.CONFLICT,"Alert is no longer open. Refresh the queue.")
        return mapOf("id" to id,"status" to "ACKNOWLEDGED")
    }
}
