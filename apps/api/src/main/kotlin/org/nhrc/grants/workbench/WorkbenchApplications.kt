package org.nhrc.grants.workbench

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Service
class WorkbenchApplications(private val store: WorkbenchStore,private val auth: WorkbenchAuth,private val records: WorkbenchRecords) {
    val budgetFields = listOf(
        WorkbenchCatalogue.text("scenario","Budget scenario",true,max=80),WorkbenchCatalogue.text("category","Cost category",true,max=120),
        WorkbenchCatalogue.area("description","Description and justification",true),GrantField("year_no","Project year","integer",true,defaultValue="1"),
        GrantField("quantity","Quantity","decimal",true,defaultValue="1"),WorkbenchCatalogue.money("unit_cost","Unit cost"),WorkbenchCatalogue.currency,
        WorkbenchCatalogue.money("funder_amount","Funder contribution"),WorkbenchCatalogue.money("nhrc_contribution","NHRC cost share"),WorkbenchCatalogue.money("partner_amount","Partner cost share")
    )
    private fun base(id: UUID,actor: GrantActor,lock: Boolean=false): GrantRow {
        val row=store.one("applications",id,lock);auth.requireRead(records.resource("applications"),row,actor);return row
    }
    @Suppress("UNCHECKED_CAST")
    private fun narrative(row: GrantRow): Map<String,Any?> = row["narrative"] as? Map<String,Any?> ?: emptyMap()
    private fun revision(row: GrantRow)=(row["content_revision"] as Number).toLong()
    private fun isLead(row: GrantRow,actor: GrantActor)=store.uuid(row,"lead_researcher_id")==actor.id
    private fun editable(row: GrantRow,actor: GrantActor)=row["stage"] in GrantRules.narrativeEditableStages && (isLead(row,actor) || actor.hasAny(setOf("GRANTS_OFFICER")))
    private fun requiredReviews(row: GrantRow)=listOf("SCIENTIFIC","FINANCE","GRANTS") + if(narrative(row)["governanceReviewRequired"]==true) listOf("GOVERNANCE") else emptyList()
    private fun reviews(id: UUID)=store.rows("select r.*,u.display_name reviewer from application_reviews r left join users u on u.id=r.reviewer_id where application_id=? order by r.created_at desc,r.id",id)
    private fun lines(id: UUID)=store.rows("select * from application_budget_lines where application_id=? order by scenario,currency,year_no,category,id",id)
    fun budgetTotals(id: UUID): List<GrantRow> = store.rows("""select scenario,currency,count(*) line_count,
        sum(round(quantity*unit_cost,2)) line_total,sum(funder_amount) funder_amount,sum(nhrc_contribution) nhrc_contribution,sum(partner_amount) partner_amount,
        count(*) filter(where round(quantity*unit_cost,2)<>(funder_amount+nhrc_contribution+partner_amount) or quantity<=0 or unit_cost<0 or funder_amount<0 or nhrc_contribution<0 or partner_amount<0) invalid_lines
        from application_budget_lines where application_id=? group by scenario,currency order by scenario,currency""",id).map(store::wire)
    private fun actionAllowed(row: GrantRow,action: GrantTransition,actor: GrantActor,currentReviews: List<GrantRow>): Boolean {
        if(row["stage"] !in action.from || !actor.hasAny(action.roles)) return false
        if(action.independent && actor.id in listOf(store.uuid(row,"owner_user_id"),store.uuid(row,"lead_researcher_id"))) return false
        if(action.code in setOf("ACCEPT","DECLINE") && !isLead(row,actor)) return false
        if(action.code in setOf("START_PREPARATION","REQUEST_REVIEW") && !isLead(row,actor) && !actor.hasAny(setOf("GRANTS_OFFICER"))) return false
        if(action.code=="COMPLETE_REVIEW" && currentReviews.none { store.uuid(it,"reviewer_id")==actor.id && it["status"]=="PENDING" && (it["content_revision"] as? Number)?.toLong()==revision(row) }) return false
        if(action.code=="CONVERT_AWARD" && (row["funder_outcome"]!="AWARDED" || row["application_type"] !in setOf("FULL_PROPOSAL","FELLOWSHIP"))) return false
        if(action.code=="NEXT_STAGE" && row["funder_outcome"]!="INVITED_TO_NEXT_STAGE") return false
        return true
    }
    fun get(id: UUID,actor: GrantActor): GrantRow {
        val row=base(id,actor);val review=reviews(id)
        return store.wire(row+mapOf(
            "_actions" to GrantRules.appActions.values.filter { actionAllowed(row,it,actor,review) },
            "_canEditNarrative" to editable(row,actor),"_readiness" to GrantRules.readiness(row["application_type"].toString(),narrative(row)),
            "_requiredSections" to WorkbenchCatalogue.requiredNarrative(row["application_type"].toString()),
            "narrativeFields" to WorkbenchCatalogue.narrativeFields,"budgetFields" to budgetFields,
            "budgetLines" to lines(id).map(store::wire),"budgetTotals" to budgetTotals(id),"reviews" to review,
            "documents" to store.rows("select d.*,u.display_name added_by_name from grant_document_links d join users u on u.id=d.added_by where application_id=? order by document_type,version_no desc",id),
            "assignments" to store.rows("select a.*,u.display_name researcher from application_assignments a join users u on u.id=a.researcher_id where application_id=? order by assigned_at desc",id),
            "decisions" to store.rows("select d.*,u.display_name decided_by_name from application_gate_decisions d join users u on u.id=d.decided_by where application_id=? order by decided_at desc",id),
            "history" to store.rows("select h.*,u.display_name changed_by_name from application_stage_history h left join users u on u.id=h.changed_by where application_id=? order by changed_at desc limit 100",id)
        ))
    }
    private fun requireEditable(row: GrantRow,actor: GrantActor,version: Long?) {
        store.checkVersion(row,version)
        if(!editable(row,actor)) throw ResponseStatusException(HttpStatus.FORBIDDEN,"Only the lead researcher or grants officer may edit this application during preparation")
    }
    private fun bump(id: UUID) = store.jdbc.update("update applications set record_version=record_version+1,content_revision=content_revision+1,updated_at=now() where id=?",id)
    @Transactional
    fun saveNarrative(id: UUID,input: GrantRecordInput,actor: GrantActor): GrantRow {
        val before=base(id,actor,true);requireEditable(before,actor,input.version)
        val values=GrantRules.validate(WorkbenchCatalogue.narrativeFields.map { it.copy(required=false) },input.values)
        val nextRevision=revision(before)+1
        for((key,value) in values) if(value != null) store.jdbc.update("insert into grant_application_sections(application_id,section_key,revision_no,content,saved_by) values (?,?,?,?,?)",id,key,nextRevision,value.toString(),actor.id)
        store.jdbc.update("update applications set narrative=?::jsonb,progress_percent=?,content_revision=?,record_version=record_version+1,updated_at=now() where id=?",store.encode(values),GrantRules.readiness(before["application_type"].toString(),values),nextRevision,id)
        store.event("applications",id,"SAVE_NARRATIVE",actor,before,store.one("applications",id))
        return get(id,actor)
    }
    @Transactional
    fun saveBudget(id: UUID,lineId: UUID?,input: GrantRecordInput,actor: GrantActor): GrantRow {
        val before=base(id,actor,true);requireEditable(before,actor,input.version)
        val values=GrantRules.validate(budgetFields,input.values)
        require((values["year_no"] as Int)>0) { "Project year must be at least one" }
        GrantRules.budgetTotal(values["quantity"] as BigDecimal,values["unit_cost"] as BigDecimal,values["funder_amount"] as BigDecimal,values["nhrc_contribution"] as BigDecimal,values["partner_amount"] as BigDecimal)
        val old=lineId?.let { store.one("application_budget_lines",it,true) }
        require(old==null || store.uuid(old,"application_id")==id) { "This budget line belongs to a different application" }
        val opp=store.uuid(before,"opportunity_id")?.let { store.one("opportunities",it) }
        require(opp?.get("currency")==null || opp["currency"]==values["currency"]) { "The budget currency must match the funding call" }
        val recordId=lineId ?: UUID.randomUUID()
        val args=budgetFields.map { store.parameter(it,values[it.name]) }
        if(lineId==null) store.jdbc.update("insert into application_budget_lines(${budgetFields.joinToString { it.name }},id,application_id) values (${budgetFields.joinToString { store.bind(it) }},?,?)",*(args+listOf(recordId,id)).toTypedArray())
        else store.jdbc.update("update application_budget_lines set ${budgetFields.joinToString { "${it.name}=${store.bind(it)}" }} where id=?",*(args+recordId).toTypedArray())
        bump(id);store.event("applications",id,"SAVE_BUDGET_LINE",actor,old,store.one("application_budget_lines",recordId))
        return get(id,actor)
    }
    @Transactional
    fun removeBudget(id: UUID,lineId: UUID,version: Long,actor: GrantActor): GrantRow {
        val before=base(id,actor,true);requireEditable(before,actor,version)
        val line=store.one("application_budget_lines",lineId,true)
        require(store.uuid(line,"application_id")==id) { "This budget line belongs to a different application" }
        store.jdbc.update("delete from application_budget_lines where id=?",lineId)
        bump(id);store.event("applications",id,"REMOVE_DRAFT_BUDGET_LINE",actor,line,null,"Removed during preparation; the earlier line is retained in the audit history")
        return get(id,actor)
    }
    @Transactional
    fun selectBudget(id: UUID,input: GrantRecordInput,actor: GrantActor): GrantRow {
        val before=base(id,actor,true);requireEditable(before,actor,input.version)
        val scenario=input.values["scenario"]?.toString()?.trim().orEmpty()
        require(scenario.isNotBlank() && scenario.length<=80) { "Select a budget scenario" }
        require(store.rows("select id from application_budget_lines where application_id=? and scenario=? limit 1",id,scenario).isNotEmpty()) { "The selected scenario has no budget lines" }
        store.jdbc.update("update applications set budget_scenario=? where id=?",scenario,id);bump(id)
        store.event("applications",id,"SELECT_BUDGET_SCENARIO",actor,before,store.one("applications",id))
        return get(id,actor)
    }
    @Transactional
    fun addDocument(id: UUID,input: GrantRecordInput,actor: GrantActor): GrantRow {
        val before=base(id,actor,true);requireEditable(before,actor,input.version)
        val fields=listOf(WorkbenchCatalogue.text("document_type","Document type",true,max=100),WorkbenchCatalogue.text("title","Document title",true),WorkbenchCatalogue.url("evidence_url","Controlled document link").copy(required=true))
        val values=GrantRules.validate(fields,input.values)
        val count=store.jdbc.queryForObject("select count(*) from grant_document_links where application_id=?",Long::class.java,id) ?: 0L
        require(count<500) { "The application has reached the controlled document version limit" }
        val next=store.jdbc.queryForObject("select coalesce(max(version_no),0)+1 from grant_document_links where application_id=? and document_type=?",Int::class.java,id,values["document_type"]) ?: 1
        store.jdbc.update("insert into grant_document_links(application_id,document_type,title,evidence_url,version_no,content_revision,added_by) values (?,?,?,?,?,?,?)",id,values["document_type"],values["title"],values["evidence_url"],next,revision(before)+1,actor.id)
        bump(id);store.event("applications",id,"ADD_DOCUMENT_VERSION",actor,null,values+mapOf("version_no" to next))
        return get(id,actor)
    }
    private fun readyForReview(row: GrantRow) {
        require(GrantRules.readiness(row["application_type"].toString(),narrative(row))==100) { "Complete every required narrative section before requesting review" }
        val totals=budgetTotals(store.uuid(row,"id")!!).filter { it["scenario"]==row["budget_scenario"] }
        if(row["application_type"] in setOf("FULL_PROPOSAL","FELLOWSHIP")) require(totals.isNotEmpty()) { "A detailed budget is required for a full proposal or fellowship" }
        require(totals.size<=1) { "The selected scenario contains multiple currencies; use one authorised budget currency" }
        totals.forEach { total ->
            require((total["invalid_lines"] as Number).toInt()==0) { "The selected budget has unbalanced or invalid lines" }
            val opportunity=store.uuid(row,"opportunity_id")?.let { store.one("opportunities",it) }
            if(opportunity?.get("amount_max")!=null && opportunity["currency"]==total["currency"]) require(store.decimal(total,"funder_amount")<=store.decimal(opportunity,"amount_max")) { "The requested funding exceeds the published award ceiling" }
        }
    }
    private fun reviewerEligible(userId: UUID,roles: Set<String>): Boolean {
        val marks=roles.joinToString { "?" }
        return store.rows("""select u.id from users u join user_roles ur on ur.user_id=u.id join roles r on r.id=ur.role_id join organisation_units ou on ou.id=ur.scope_id
            where u.id=? and u.active and ur.scope_type='INSTITUTION' and ou.code='NHRC' and r.code in ($marks)
            and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>now()) limit 1""",*(listOf(userId)+roles).toTypedArray()).isNotEmpty()
    }
    private fun notification(user: UUID,title: String,message: String,id: UUID) = store.jdbc.update("insert into notifications(user_id,type,title,message,entity_type,entity_id) values (?,'APPLICATION',?,?,'APPLICATION',?)",user,title,message,id)
    private fun value(input: GrantActionInput,name: String,limit: Int=8000): String {
        val text=input.values[name] as? String ?: throw IllegalArgumentException("$name is required")
        require(text.isNotBlank() && text.length<=limit) { "$name is required and must not exceed $limit characters" }
        return text.trim()
    }
    @Transactional
    fun action(id: UUID,input: GrantActionInput,actor: GrantActor): GrantRow {
        val before=base(id,actor,true)
        val hash=store.hash(mapOf("action" to input.action,"version" to input.version,"note" to input.note,"evidence" to input.evidenceUrl,"values" to input.values))
        val receipt=store.rows("select * from application_action_receipts where application_id=? and request_id=?",id,input.requestId).firstOrNull()
        if(receipt!=null) {
            if(store.uuid(receipt,"actor_user_id")!=actor.id || receipt["request_hash"]!=hash) throw ResponseStatusException(HttpStatus.CONFLICT,"This action reference has already been used for another request")
            @Suppress("UNCHECKED_CAST")
            return receipt["result"] as GrantRow
        }
        store.checkVersion(before,input.version)
        require(input.note.isNotBlank() && input.note.length<=8000) { "Record a clear decision note (maximum 8,000 characters)" }
        val definition=GrantRules.requireAction(input.action,before["stage"].toString(),actor.roles)
        if(!actionAllowed(before,definition,actor,reviews(id))) throw ResponseStatusException(HttpStatus.FORBIDDEN,"You are not the authorised person for this action")
        val evidence=input.evidenceUrl?.takeIf { it.isNotBlank() }?.let(GrantRules::safeUrl)
        require(!definition.evidenceRequired || evidence!=null) { "A supporting evidence link is required" }
        var target=definition.to
        var decision="RECORDED"
        var extra: GrantRow=emptyMap()
        when(input.action) {
            "RECORD_ELIGIBILITY" -> {
                decision=value(input,"decision",40)
                require(decision in setOf("ELIGIBLE","CONDITIONAL","INELIGIBLE")) { "Choose an eligibility decision" }
                val checks=input.values["checks"] as? Map<*,*> ?: throw IllegalArgumentException("Complete the eligibility checklist")
                val names=setOf("applicant","geography","theme","deadline","budget","partners")
                require(checks.keys.map { it.toString() }.toSet()==names && checks.values.all { it in setOf("PASS","FAIL","UNKNOWN","NOT_APPLICABLE") }) { "Every eligibility criterion must be assessed" }
                if(decision=="ELIGIBLE") require(checks.values.none { it in setOf("FAIL","UNKNOWN") }) { "Unresolved or failed criteria cannot be marked eligible" }
                val conditions=(input.values["conditions"] as? String)?.trim()
                require(conditions==null || conditions.length<=8000) { "Conditions are too long" }
                require(decision!="CONDITIONAL" || !conditions.isNullOrBlank()) { "Explain the outstanding conditions" }
                val opp=store.uuid(before,"opportunity_id") ?: throw IllegalArgumentException("The application has no linked call")
                store.jdbc.update("insert into eligibility_reviews(opportunity_id,decision,rationale,conditions,reviewed_by) values (?,?,?,?,?)",opp,decision,input.note,conditions,actor.id)
                store.jdbc.update("update opportunities set eligibility_status=?,record_version=record_version+1,updated_at=now() where id=?",decision,opp)
                if(decision=="INELIGIBLE") target="CLOSED"
                if(decision=="CONDITIONAL") target="ELIGIBILITY_REVIEW"
                extra=mapOf("checks" to checks,"conditions" to conditions)
            }
            "DIRECTOR_DECISION" -> {
                require(store.one("opportunities",store.uuid(before,"opportunity_id")!!)["eligibility_status"]=="ELIGIBLE") { "Resolve eligibility conditions before committing proposal effort" }
                decision=value(input,"decision",40);require(decision in setOf("PURSUE","DO_NOT_PURSUE")) { "Choose pursue or do not pursue" }
                if(decision=="DO_NOT_PURSUE") target="CLOSED"
            }
            "ASSIGN" -> {
                val researcher=UUID.fromString(value(input,"researcherId",36))
                require(reviewerEligible(researcher,WorkbenchCatalogue.researchRoles)) { "The selected person is not an active researcher or research fellow" }
                require(store.rows("select id from researcher_profiles where user_id=? and active",researcher).isNotEmpty()) { "Create the researcher's profile before assigning an application" }
                store.jdbc.update("insert into application_assignments(application_id,researcher_id,assigned_by) values (?,?,?)",id,researcher,actor.id)
                store.jdbc.update("update applications set lead_researcher_id=? where id=?",researcher,id)
                notification(researcher,"Application assignment",before["title"].toString()+": please accept or decline the assignment.",id)
            }
            "ACCEPT","DECLINE" -> {
                val assignment=store.rows("select id from application_assignments where application_id=? and researcher_id=? and response='PENDING' order by assigned_at desc limit 1 for update",id,actor.id).firstOrNull()
                    ?: throw ResponseStatusException(HttpStatus.CONFLICT,"No pending assignment was found")
                store.jdbc.update("update application_assignments set response=?,responded_at=now(),response_note=? where id=?",if(input.action=="ACCEPT") "ACCEPTED" else "DECLINED",input.note,store.uuid(assignment,"id"))
                if(input.action=="DECLINE") store.jdbc.update("update applications set lead_researcher_id=null where id=?",id)
            }
            "REQUEST_REVIEW" -> {
                readyForReview(before)
                require(input.values["documentsConfirmed"]==true) { "Confirm that the package meets the funder's document requirements" }
                val reviewers=input.values["reviewers"] as? Map<*,*> ?: throw IllegalArgumentException("Assign the required reviewers")
                for(type in requiredReviews(before)) {
                    val reviewer=UUID.fromString(reviewers[type]?.toString() ?: throw IllegalArgumentException("Assign the $type reviewer"))
                    require(reviewerEligible(reviewer,GrantRules.reviewRoles.getValue(type))) { "The selected $type reviewer does not hold the required role" }
                    require(reviewer!=store.uuid(before,"lead_researcher_id")) { "The lead researcher cannot review their own proposal" }
                    if(type in setOf("SCIENTIFIC","FINANCE")) require(reviewer!=store.uuid(before,"owner_user_id")) { "Scientific and finance review require an independent reviewer" }
                    store.jdbc.update("insert into application_reviews(application_id,review_type,reviewer_id,content_revision) values (?,?,?,?)",id,type,reviewer,revision(before))
                    notification(reviewer,"Application review requested",before["title"].toString()+": $type review is assigned to you.",id)
                }
            }
            "COMPLETE_REVIEW" -> {
                val type=value(input,"reviewType",40);require(type in requiredReviews(before)) { "This review type is not required for the application" }
                actor.requireAny(GrantRules.reviewRoles.getValue(type))
                val review=store.rows("select * from application_reviews where application_id=? and content_revision=? and review_type=? and reviewer_id=? and status='PENDING' for update",id,revision(before),type,actor.id).singleOrNull()
                    ?: throw ResponseStatusException(HttpStatus.FORBIDDEN,"This review is not currently assigned to you")
                require(input.values["noConflict"]==true) { "Declare that you have no conflict before completing the review" }
                decision=value(input,"decision",40);require(decision in setOf("CLEARED","RETURNED")) { "Choose cleared or returned" }
                store.jdbc.update("update application_reviews set status=?,comments=?,no_conflict_declared=true,completed_at=now() where id=?",decision,input.note,store.uuid(review,"id"))
                if(decision=="RETURNED") {
                    target="PREPARATION"
                    store.jdbc.update("update applications set content_revision=content_revision+1 where id=?",id)
                    store.jdbc.update("update application_reviews set status='SUPERSEDED' where application_id=? and content_revision=? and status='PENDING'",id,revision(before))
                } else {
                    val cleared=store.rows("select distinct review_type from application_reviews where application_id=? and content_revision=? and status='CLEARED'",id,revision(before)).map { it["review_type"].toString() }.toSet()
                    if(cleared.containsAll(requiredReviews(before))) target="INSTITUTIONAL_APPROVAL"
                }
                extra=mapOf("review_type" to type)
            }
            "APPROVE" -> {
                readyForReview(before)
                require(input.values["noConflict"]==true) { "Declare that you have no conflict before authorising the package" }
                val cleared=store.rows("select distinct review_type from application_reviews where application_id=? and content_revision=? and status='CLEARED' and no_conflict_declared",id,revision(before)).map { it["review_type"].toString() }.toSet()
                require(cleared.containsAll(requiredReviews(before))) { "All required reviews must clear the current revision" }
                val packageSnapshot=mapOf("application" to before,"budget" to lines(id).filter { it["scenario"]==before["budget_scenario"] },"documents" to store.rows("select distinct on (document_type) * from grant_document_links where application_id=? order by document_type,version_no desc",id),"reviews" to reviews(id).filter { (it["content_revision"] as? Number)?.toLong()==revision(before) })
                store.jdbc.update("insert into grant_submission_packages(application_id,content_revision,package_snapshot,authorised_by) values (?,?,?::jsonb,?)",id,revision(before),store.encode(packageSnapshot),actor.id)
                decision="AUTHORISED"
            }
            "RETURN" -> store.jdbc.update("update applications set content_revision=content_revision+1 where id=?",id)
            "RECORD_SUBMISSION" -> {
                require(store.rows("select id from grant_submission_packages where application_id=? and content_revision=?",id,revision(before)).isNotEmpty()) { "No authorised package exists for the current revision" }
                val submitted=OffsetDateTime.parse(value(input,"submittedAt",80))
                require(!submitted.isAfter(OffsetDateTime.now())) { "A submission cannot be recorded in the future" }
                store.jdbc.update("update applications set submitted_at=?,submission_reference=?,submission_evidence_url=? where id=?",submitted,value(input,"reference",255),evidence,id)
            }
            "RECORD_OUTCOME" -> {
                decision=value(input,"outcome",80)
                require(decision in setOf("AWARDED","UNSUCCESSFUL","WITHDRAWN","INVITED_TO_NEXT_STAGE","WAITLISTED")) { "Choose a recognised funder outcome" }
                require(decision!="AWARDED" || before["application_type"] in setOf("FULL_PROPOSAL","FELLOWSHIP")) { "For an SOI, EOI, LOI or concept note, record an invitation to the next stage, not a financial award" }
                store.jdbc.update("update applications set funder_outcome=?,funder_outcome_at=now(),outcome_evidence_url=? where id=?",decision,evidence,id)
            }
            "NEXT_STAGE" -> {
                val type=value(input,"applicationType",40)
                require(type in WorkbenchCatalogue.applicationTypes && type!=before["application_type"]) { "Choose the invited next-stage submission type" }
                require(type in setOf("EOI","CONCEPT_NOTE","FULL_PROPOSAL","FELLOWSHIP")) { "The next-stage type is not supported" }
                require(store.rows("select id from applications where parent_application_id=? and application_type=?",id,type).isEmpty()) { "A linked application of this type already exists" }
                val child=UUID.randomUUID()
                store.jdbc.update("insert into applications(id,reference,opportunity_id,title,owner_user_id,application_type,parent_application_id,narrative,progress_percent,deadline_at) values (?,?,?,?,?,?,?,?::jsonb,?,?)",child,"APP-${LocalDate.now().year}-"+child.toString().take(8).uppercase(),store.uuid(before,"opportunity_id"),before["title"],actor.id,type,id,store.encode(narrative(before)),GrantRules.readiness(type,narrative(before)),null)
                store.registerOwner("applications",child,actor)
                store.event("applications",child,"CREATE_INVITED_STAGE",actor,null,store.one("applications",child),"Created from ${before["reference"]}; the next-stage deadline must be checked against the invitation")
                extra=mapOf("createdApplicationId" to child)
            }
            "CONVERT_AWARD" -> {
                require(before["funder_outcome"]=="AWARDED") { "A recorded financial award outcome is required" }
                val start=LocalDate.parse(value(input,"startDate",10));val end=LocalDate.parse(value(input,"endDate",10));require(end>=start) { "Award end date must not precede start date" }
                val currency=value(input,"currency",3);require(currency in WorkbenchCatalogue.currencies) { "Choose a supported currency" }
                val total=GrantRules.amount(value(input,"totalAward",30));val nhrc=GrantRules.amount(value(input,"nhrcAllocation",30));val partner=GrantRules.amount(value(input,"partnerAllocation",30))
                require(total>BigDecimal.ZERO && (nhrc+partner).compareTo(total)==0) { "NHRC and partner allocations must equal the total award" }
                val award=UUID.randomUUID();val opp=store.one("opportunities",store.uuid(before,"opportunity_id")!!)
                store.jdbc.update("insert into awards(id,reference,application_id,title,funder_id,principal_investigator_id,start_date,end_date,currency,total_award,nhrc_allocation,partner_allocation) values (?,?,?,?,?,?,?,?,?,?,?,?)",award,value(input,"reference",80),id,before["title"],store.uuid(opp,"funder_id"),store.uuid(before,"lead_researcher_id"),start,end,currency,total,nhrc,partner)
                store.jdbc.update("insert into award_financial_positions(award_id,approved_budget) values (?,?)",award,nhrc)
                store.event("awards",award,"CREATE_FROM_APPLICATION",actor,null,store.one("awards",award),input.note)
                extra=mapOf("createdAwardId" to award)
            }
        }
        store.jdbc.update("update applications set stage=?,record_version=record_version+1,updated_at=now() where id=?",target,id)
        store.jdbc.update("insert into application_stage_history(application_id,from_stage,to_stage,note,changed_by) values (?,?,?,?,?)",id,before["stage"],target,input.note,actor.id)
        store.jdbc.update("insert into application_gate_decisions(application_id,content_revision,gate,decision,note,evidence_url,decided_by) values (?,?,?,?,?,?,?)",id,revision(before),input.action,decision,input.note,evidence,actor.id)
        store.event("applications",id,input.action,actor,before,store.one("applications",id)+extra+mapOf("evidence_url" to evidence),input.note)
        val result=mapOf("id" to id,"stage" to target,"record_version" to store.version(store.one("applications",id)))+extra
        store.jdbc.update("insert into application_action_receipts(application_id,request_id,action,actor_user_id,request_hash,result) values (?,?,?,?,?,?::jsonb)",id,input.requestId,input.action,actor.id,hash,store.encode(result))
        return result
    }
}
