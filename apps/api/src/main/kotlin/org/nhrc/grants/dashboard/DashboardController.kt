package org.nhrc.grants.dashboard

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/dashboard")
class DashboardController(private val jdbc: JdbcTemplate) {
    @GetMapping("/summary")
    fun summary(): Map<String, Any> = mapOf(
        "opportunities" to count("opportunities"),
        "applications" to count("applications"),
        "activeAwards" to jdbc.queryForObject("select count(*) from awards where status not in ('CLOSED','CANCELLED')", Long::class.java)!!,
        "pendingApprovals" to jdbc.queryForObject("select count(*) from approvals where status='PENDING'", Long::class.java)!!,
        "reportsDue" to jdbc.queryForObject("select count(*) from reports where status not in ('ACCEPTED','CLOSED') and due_date <= current_date + 30", Long::class.java)!!,
        "openRisks" to jdbc.queryForObject("select count(*) from award_risks where status='OPEN'", Long::class.java)!!
    )

    private fun count(table: String): Long = jdbc.queryForObject("select count(*) from $table", Long::class.java)!!
}

@RestController
@RequestMapping("/api/opportunities")
class OpportunityController(private val jdbc: JdbcTemplate) {
    @GetMapping
    fun search(@RequestParam(required=false) q: String?, @RequestParam(required=false) status: String?): List<Map<String, Any?>> {
        val term = "%${q ?: ""}%"
        val state = status ?: ""
        return jdbc.queryForList("""
            select o.id, o.title, coalesce(f.name,'') funder, o.source_type, o.deadline_at,
                   o.status, o.eligibility_status, o.institutional_fit_score
            from opportunities o left join funders f on f.id=o.funder_id
            where (lower(o.title) like lower(?) or lower(coalesce(f.name,'')) like lower(?))
              and (?='' or o.status=?)
            order by o.deadline_at nulls last, o.created_at desc
            limit 200
        """.trimIndent(), term, term, state, state)
    }
}

@RestController
@RequestMapping("/api/awards")
class AwardController(private val jdbc: JdbcTemplate) {
    @GetMapping
    fun search(@RequestParam(required=false) q: String?, @RequestParam(required=false) status: String?): List<Map<String, Any?>> {
        val term = "%${q ?: ""}%"
        val state = status ?: ""
        return jdbc.queryForList("""
            select a.id, a.reference, a.title, coalesce(f.name,'') funder, a.status, a.currency,
                   a.total_award, a.nhrc_allocation, a.start_date, a.end_date,
                   coalesce(p.cash_received,0) cash_received, coalesce(p.expenditure,0) expenditure,
                   coalesce(p.commitments,0) commitments
            from awards a left join funders f on f.id=a.funder_id
            left join award_financial_positions p on p.award_id=a.id
            where (lower(a.title) like lower(?) or lower(a.reference) like lower(?) or lower(coalesce(f.name,'')) like lower(?))
              and (?='' or a.status=?)
            order by a.end_date nulls last, a.created_at desc
            limit 200
        """.trimIndent(), term, term, term, state, state)
    }
}
