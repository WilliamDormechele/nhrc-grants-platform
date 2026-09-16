package org.nhrc.grants.workflow

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class StageRequest(val stage: String, val note: String? = null, val actorId: UUID? = null)
data class EligibilityRequest(val decision: String, val rationale: String, val conditions: String? = null, val actorId: UUID? = null)
data class AssignmentRequest(val researcherId: UUID, val assignedBy: UUID? = null)
data class AssignmentResponseRequest(val response: String, val note: String? = null)
data class OutcomeRequest(val outcome: String)

@RestController
@RequestMapping("/api/applications")
class ApplicationWorkflowController(private val jdbc: JdbcTemplate) {
    private val stages = listOf("DISCOVERED","ELIGIBILITY_REVIEW","DIRECTOR_DECISION","ASSIGNED","ACCEPTED","PREPARATION","INTERNAL_REVIEW","INSTITUTIONAL_APPROVAL","SUBMITTED","OUTCOME_RECORDED","AWARDED","CLOSED")

    @GetMapping
    fun search(@RequestParam(required=false) q: String?, @RequestParam(required=false) stage: String?): List<Map<String, Any?>> {
        val term="%${q ?: ""}%"; val s=stage ?: ""
        return jdbc.queryForList("""
          select a.id,a.reference,a.title,a.stage,a.progress_percent,a.deadline_at,a.funder_outcome,
                 u.display_name lead_researcher
          from applications a left join users u on u.id=a.lead_researcher_id
          where (lower(a.title) like lower(?) or lower(a.reference) like lower(?)) and (?='' or a.stage=?)
          order by a.deadline_at nulls last,a.created_at desc limit 200
        """.trimIndent(),term,term,s,s)
    }

    @PostMapping("/{id}/stage") @Transactional
    fun move(@PathVariable id: UUID, @RequestBody req: StageRequest): Map<String,Any> {
        if(req.stage !in stages) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown stage")
        val current=jdbc.queryForObject("select stage from applications where id=?",String::class.java,id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found")
        val from=stages.indexOf(current); val to=stages.indexOf(req.stage)
        if(to < from || to > from+1) throw ResponseStatusException(HttpStatus.CONFLICT,"Stage transition must follow the governed workflow")
        jdbc.update("update applications set stage=?, updated_at=now() where id=?",req.stage,id)
        jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,note,changed_by) values (?,?,?,?,?)",id,current,req.stage,req.note,req.actorId)
        return mapOf("id" to id,"from" to current,"stage" to req.stage)
    }

    @PostMapping("/{id}/eligibility") @Transactional
    fun eligibility(@PathVariable id: UUID,@RequestBody req: EligibilityRequest): Map<String,Any> {
        if(req.decision !in setOf("ELIGIBLE","INELIGIBLE","CONDITIONAL")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid eligibility decision")
        val opp=jdbc.queryForObject("select opportunity_id from applications where id=?",UUID::class.java,id)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT,"Application has no opportunity")
        jdbc.update("insert into eligibility_reviews(opportunity_id,decision,rationale,conditions,reviewed_by) values (?,?,?,?,?)",opp,req.decision,req.rationale,req.conditions,req.actorId)
        jdbc.update("update opportunities set eligibility_status=?, updated_at=now() where id=?",req.decision,opp)
        return mapOf("applicationId" to id,"decision" to req.decision)
    }

    @PostMapping("/{id}/assignments") @Transactional
    fun assign(@PathVariable id: UUID,@RequestBody req: AssignmentRequest): Map<String,Any> {
        val assignment=UUID.randomUUID()
        jdbc.update("insert into application_assignments(id,application_id,researcher_id,assigned_by) values (?,?,?,?)",assignment,id,req.researcherId,req.assignedBy)
        jdbc.update("update applications set lead_researcher_id=?, stage='ASSIGNED', updated_at=now() where id=?",req.researcherId,id)
        return mapOf("assignmentId" to assignment,"status" to "PENDING")
    }

    @PostMapping("/assignments/{assignmentId}/response") @Transactional
    fun respond(@PathVariable assignmentId: UUID,@RequestBody req: AssignmentResponseRequest): Map<String,Any> {
        if(req.response !in setOf("ACCEPTED","DECLINED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Response must be ACCEPTED or DECLINED")
        val app=jdbc.queryForObject("select application_id from application_assignments where id=?",UUID::class.java,assignmentId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Assignment not found")
        jdbc.update("update application_assignments set response=?,response_note=?,responded_at=now() where id=?",req.response,req.note,assignmentId)
        if(req.response=="ACCEPTED") jdbc.update("update applications set stage='ACCEPTED',updated_at=now() where id=?",app)
        return mapOf("assignmentId" to assignmentId,"response" to req.response)
    }

    @PostMapping("/{id}/outcome") @Transactional
    fun outcome(@PathVariable id: UUID,@RequestBody req: OutcomeRequest): Map<String,Any> {
        if(req.outcome !in setOf("AWARDED","UNSUCCESSFUL","WITHDRAWN")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid outcome")
        jdbc.update("update applications set funder_outcome=?,funder_outcome_at=now(),stage='OUTCOME_RECORDED',updated_at=now() where id=?",req.outcome,id)
        return mapOf("id" to id,"outcome" to req.outcome)
    }
}
