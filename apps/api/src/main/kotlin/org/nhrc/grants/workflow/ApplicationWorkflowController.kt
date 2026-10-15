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
class ApplicationWorkflowController(private val jdbc: JdbcTemplate,private val identity:org.nhrc.grants.config.PlatformIdentity) {
    private val stages = listOf("DISCOVERED","ELIGIBILITY_REVIEW","DIRECTOR_DECISION","ASSIGNED","ACCEPTED","PREPARATION","INTERNAL_REVIEW","INSTITUTIONAL_APPROVAL","SUBMITTED","OUTCOME_RECORDED","AWARDED","CLOSED")

    @GetMapping
    fun search(@RequestParam(required=false) q: String?, @RequestParam(required=false) stage: String?): List<Map<String, Any?>> {
        val term="%${q ?: ""}%"; val s=stage ?: ""
        val actor=identity.current();val owned=if(actor.roles==setOf("RESEARCHER")) actor.id else null
        return jdbc.queryForList("""
          select a.id,a.reference,a.title,a.stage,case a.stage when 'DISCOVERED' then 0 when 'ELIGIBILITY_REVIEW' then 9 when 'DIRECTOR_DECISION' then 18 when 'ASSIGNED' then 27 when 'ACCEPTED' then 36 when 'PREPARATION' then 45 when 'INTERNAL_REVIEW' then 55 when 'INSTITUTIONAL_APPROVAL' then 64 when 'SUBMITTED' then 73 when 'OUTCOME_RECORDED' then 82 when 'AWARDED' then 91 when 'CLOSED' then 100 else 0 end progress_percent,a.deadline_at,a.funder_outcome,
                 u.display_name lead_researcher
          from applications a left join users u on u.id=a.lead_researcher_id
          where (lower(a.title) like lower(?) or lower(a.reference) like lower(?)) and (?='' or a.stage=?)
          and (?::uuid is null or a.owner_user_id=? or a.lead_researcher_id=? or exists(select 1 from application_team_members t where t.application_id=a.id and t.user_id=?) or exists(select 1 from application_reviews v where v.application_id=a.id and v.reviewer_id=?))
          order by a.deadline_at nulls last,a.created_at desc limit 200
        """.trimIndent(),term,term,s,s,owned,owned,owned,owned,owned)
    }

    @PostMapping("/{id}/stage") @Transactional
    fun move(@PathVariable id: UUID, @RequestBody req: StageRequest): Map<String,Any> {
        val actor=identity.current()
        if(actor.roles.none {it=="GRANTS_OFFICER"} && !("RESEARCHER" in actor.roles && req.stage in setOf("PREPARATION","INTERNAL_REVIEW")))
            throw ResponseStatusException(HttpStatus.FORBIDDEN,"This stage must be recorded by the responsible grants officer")
        if(req.stage !in stages) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Unknown stage")
        val current=jdbc.queryForObject("select stage from applications where id=? for update",String::class.java,id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Application not found")
        val from=stages.indexOf(current); val to=stages.indexOf(req.stage)
        if(to <= from || to > from+1) throw ResponseStatusException(HttpStatus.CONFLICT,"Stage transition must follow the governed workflow")
        if(req.stage=="ASSIGNED") throw ResponseStatusException(HttpStatus.CONFLICT,"Use the researcher assignment action for this stage")
        if(req.stage=="ACCEPTED") throw ResponseStatusException(HttpStatus.CONFLICT,"Use the researcher assignment response for this stage")
        if(req.stage=="SUBMITTED") throw ResponseStatusException(HttpStatus.CONFLICT,"Use the controlled submission action for this stage")
        if(req.stage=="OUTCOME_RECORDED") throw ResponseStatusException(HttpStatus.CONFLICT,"Use the funder outcome action for this stage")
        if(req.stage=="CLOSED" && jdbc.queryForObject("select count(*) from awards where application_id=? and status='CLOSED'",Long::class.java,id)==0L)
            throw ResponseStatusException(HttpStatus.CONFLICT,"Close the linked award before closing this application")
        if(req.stage=="AWARDED") throw ResponseStatusException(HttpStatus.CONFLICT,"Use successful-application conversion to create the award")
        if(req.stage=="DIRECTOR_DECISION" && jdbc.queryForObject("select count(*) from applications a join opportunities o on o.id=a.opportunity_id where a.id=? and o.eligibility_status in ('ELIGIBLE','CONDITIONAL')",Long::class.java,id)==0L)
            throw ResponseStatusException(HttpStatus.CONFLICT,"Record the eligibility assessment before requesting the Director decision")
        if(req.stage=="INSTITUTIONAL_APPROVAL"){
            val reviews=jdbc.queryForObject("select count(*) from application_reviews where application_id=?",Long::class.java,id)?:0
            val incomplete=jdbc.queryForObject("select count(*) from application_reviews where application_id=? and status<>'APPROVED'",Long::class.java,id)?:0
            if(reviews==0L || incomplete>0L) throw ResponseStatusException(HttpStatus.CONFLICT,"All required internal reviews must be approved before institutional approval")
        }
        jdbc.update("update applications set stage=?, updated_at=now() where id=?",req.stage,id)
        jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,note,changed_by) values (?,?,?,?,?)",id,current,req.stage,req.note,actor.id)
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
        val stage=jdbc.queryForObject("select stage from applications where id=? for update",String::class.java,id)
        if(stage!="DIRECTOR_DECISION") throw ResponseStatusException(HttpStatus.CONFLICT,"Assignment follows the Director decision stage")
        if(!ApplicationDecisions.approved(jdbc,id,"DIRECTOR_DECISION")) throw ResponseStatusException(HttpStatus.CONFLICT,"Record the approved Director decision before assignment")
        if(jdbc.queryForObject("select count(*) from users u join researcher_profiles p on p.user_id=u.id where u.id=? and u.active=true and p.active=true and exists(select 1 from user_roles ur join roles r on r.id=ur.role_id where ur.user_id=u.id and r.code='RESEARCHER' and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>=now()))",Long::class.java,req.researcherId)!=1L)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Choose an active researcher with current proposal access")
        val assignment=UUID.randomUUID()
        jdbc.update("insert into application_assignments(id,application_id,researcher_id,assigned_by) values (?,?,?,?)",assignment,id,req.researcherId,req.assignedBy)
        jdbc.update("update applications set lead_researcher_id=?, stage='ASSIGNED', updated_at=now() where id=?",req.researcherId,id)
        jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,changed_by,note) values (?,'DIRECTOR_DECISION','ASSIGNED',?,?)",id,identity.current().id,"Principal investigator assigned after the Director decision")
        return mapOf("assignmentId" to assignment,"status" to "PENDING")
    }

    @PostMapping("/assignments/{assignmentId}/response") @Transactional
    fun respond(@PathVariable assignmentId: UUID,@RequestBody req: AssignmentResponseRequest): Map<String,Any> {
        if(req.response !in setOf("ACCEPTED","DECLINED")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Response must be ACCEPTED or DECLINED")
        if(jdbc.queryForObject("select count(*) from application_assignments where id=? and response='PENDING' and researcher_id=?",Long::class.java,assignmentId,identity.current().id)!=1L) throw ResponseStatusException(HttpStatus.CONFLICT,"Only the assigned researcher can respond to a pending assignment")
        val locked=jdbc.queryForMap("select application_id,response,researcher_id from application_assignments where id=? for update",assignmentId)
        if(locked["response"]!="PENDING" || locked["researcher_id"].toString()!=identity.current().id.toString()) throw ResponseStatusException(HttpStatus.CONFLICT,"This assignment has already received a response")
        val app=locked["application_id"] as UUID
        val previous=jdbc.queryForObject("select stage from applications where id=? for update",String::class.java,app)
        if(previous!="ASSIGNED") throw ResponseStatusException(HttpStatus.CONFLICT,"Respond while the application is at the assigned stage")
        jdbc.update("update application_assignments set response=?,response_note=?,responded_at=now() where id=?",req.response,req.note,assignmentId)
        if(req.response=="ACCEPTED") {
          jdbc.update("update applications set stage='ACCEPTED',updated_at=now() where id=?",app)
          jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,changed_by,note) values (?,'ASSIGNED','ACCEPTED',?,?)",app,identity.current().id,req.note ?: "Assigned principal investigator accepted")
        }
        if(req.response=="DECLINED") {
          jdbc.update("update applications set stage='DIRECTOR_DECISION',lead_researcher_id=null,updated_at=now() where id=?",app)
          jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,changed_by,note) values (?,'ASSIGNED','DIRECTOR_DECISION',?,?)",app,identity.current().id,req.note ?: "Assigned researcher declined; grants officer must reassign")
        }
        return mapOf("assignmentId" to assignmentId,"response" to req.response)
    }

    @PostMapping("/{id}/outcome") @Transactional
    fun outcome(@PathVariable id: UUID,@RequestBody req: OutcomeRequest): Map<String,Any> {
        if(req.outcome !in setOf("AWARDED","UNSUCCESSFUL","WITHDRAWN")) throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid outcome")
        val stage=jdbc.queryForObject("select stage from applications where id=? for update",String::class.java,id)
        if(stage!="SUBMITTED") throw ResponseStatusException(HttpStatus.CONFLICT,"Record the outcome only after submission")
        jdbc.update("update applications set funder_outcome=?,funder_outcome_at=now(),stage='OUTCOME_RECORDED',updated_at=now() where id=?",req.outcome,id)
        jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,changed_by,note) values (?,'SUBMITTED','OUTCOME_RECORDED',?,?)",id,identity.current().id,"Funder outcome recorded: "+req.outcome.lowercase().replace('_',' '))
        return mapOf("id" to id,"outcome" to req.outcome)
    }
}
