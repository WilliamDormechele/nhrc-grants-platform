package org.nhrc.grants.workbench

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

object GrantDiligenceRules {
    val checks = linkedMapOf("identity" to "Legal identity and eligibility", "financialControls" to "Financial controls and accountability", "conflicts" to "Conflicts and integrity", "safeguarding" to "Safeguarding and institutional policies", "deliveryCapacity" to "Capacity to deliver the proposed work")
    fun validateChecklist(values: Map<String,Any?>): Map<String,String> = checks.keys.associateWith { name ->
        val value=values["check_$name"]?.toString()
        require(value in setOf("PASS","FAIL","UNKNOWN","NOT_APPLICABLE")) { "Complete every due diligence check" }
        value!!
    }
    fun requireCurrentApproval(row: GrantRow) {
        require(row["due_diligence_status"]=="APPROVED" && row["due_diligence_review_id"]!=null && row["due_diligence_evidence_url"]!=null) { "An independently approved due diligence review with evidence is required; historical status alone is insufficient" }
        val expiry=row["due_diligence_valid_until"]?.toString()?.let(LocalDate::parse)
        require(expiry!=null && !expiry.isBefore(LocalDate.now())) { "The due diligence approval has expired or has no recorded validity date" }
    }
}

@Service
class WorkbenchDueDiligence(private val store: WorkbenchStore,private val records: WorkbenchRecords) {
    private val readers=WorkbenchCatalogue.grantRoles+WorkbenchCatalogue.financeRoles+WorkbenchCatalogue.procurementRoles+WorkbenchCatalogue.governanceRoles+WorkbenchCatalogue.oversight
    private val approvers=setOf("DIRECTOR","GOVERNANCE_OFFICER")
    private fun subjectKey(type: String)=when(type) { "PARTNER"->"partners";"SUPPLIER"->"suppliers";else->throw IllegalArgumentException("Select a partner or supplier") }
    private fun preparers(type: String)=if(type=="PARTNER") WorkbenchCatalogue.grantRoles+WorkbenchCatalogue.governanceRoles else WorkbenchCatalogue.procurementRoles
    private fun editable(row: GrantRow,actor: GrantActor)=row["prepared_by"]!=null && row["status"] in setOf("DRAFT","CONDITIONAL") && actor.hasAny(preparers(row["entity_type"].toString()))
    private fun allowed(row: GrantRow,actor: GrantActor): List<GrantTransition> {
        val type=row["entity_type"].toString()
        val independent=store.uuid(row,"prepared_by")!=null && actor.id!=store.uuid(row,"prepared_by") && actor.id!=store.owner(subjectKey(type),store.uuid(row,"entity_id")!!)
        return listOf(
            GrantTransition("REQUEST_REVIEW","Request due diligence approval",setOf("DRAFT","CONDITIONAL"),"PENDING",preparers(type)),
            GrantTransition("APPROVE","Approve due diligence",setOf("PENDING"),"APPROVED",approvers,true,true),
            GrantTransition("RETURN","Return with conditions",setOf("PENDING"),"CONDITIONAL",approvers,true,true),
            GrantTransition("REJECT","Reject due diligence",setOf("PENDING"),"REJECTED",approvers,true,true)
        ).filter { row["prepared_by"]!=null && row["status"] in it.from && actor.hasAny(it.roles) && (!it.independent||independent) }
    }
    private val commonFields=listOf(
        WorkbenchCatalogue.text("review_type","Review scope",true,max=100),
        WorkbenchCatalogue.select("risk_rating","Assessed risk",listOf("LOW","MEDIUM","HIGH"),"MEDIUM"),
        WorkbenchCatalogue.area("findings","Findings and supporting assessment",true),
        WorkbenchCatalogue.area("conditions","Conditions or follow-up requirements"),
        WorkbenchCatalogue.date("expires_at","Proposed approval valid until",true),
        WorkbenchCatalogue.url("evidence_url","Controlled assessment evidence").copy(required=true)
    )+GrantDiligenceRules.checks.map { (name,label)->GrantField("check_$name",label,"select",true,listOf("PASS","FAIL","UNKNOWN","NOT_APPLICABLE"),defaultValue="UNKNOWN") }
    private fun form(type: String)=listOf(WorkbenchCatalogue.link("entity_id",if(type=="PARTNER") "Partner institution" else "Supplier",subjectKey(type),true))+commonFields
    private fun decorate(row: GrantRow,actor: GrantActor): GrantRow {
        val type=row["entity_type"].toString()
        val subject=store.one(subjectKey(type),store.uuid(row,"entity_id")!!)
        @Suppress("UNCHECKED_CAST")
        val checks=row["checklist"] as? Map<String,Any?> ?: emptyMap()
        return store.wire(row+checks.mapKeys { "check_${it.key}" }+mapOf("subject_name" to subject["name"],"fields" to form(type),"_editable" to editable(row,actor),"_actions" to allowed(row,actor)))
    }
    fun list(actor: GrantActor,query: String): GrantRow {
        actor.requireAny(readers);require(query.length<=200) { "Search is too long" }
        val rows=store.rows("""select r.*,case when r.entity_type='PARTNER' then p.name else s.name end subject_name
            from due_diligence_reviews r left join partners p on r.entity_type='PARTNER' and p.id=r.entity_id
            left join suppliers s on r.entity_type='SUPPLIER' and s.id=r.entity_id
            where r.entity_type in ('PARTNER','SUPPLIER') and (r.review_type ilike ? or coalesce(p.name,s.name,'') ilike ?)
            order by r.created_at desc,r.id limit 101""","%$query%","%$query%")
        return mapOf("items" to rows.take(100).map(store::wire),"truncated" to (rows.size>100),"createTypes" to listOf("PARTNER","SUPPLIER").filter { actor.hasAny(preparers(it)) },"forms" to mapOf("PARTNER" to form("PARTNER"),"SUPPLIER" to form("SUPPLIER")))
    }
    fun get(id: UUID,actor: GrantActor): GrantRow {
        actor.requireAny(readers)
        val row=store.one("due_diligence_reviews",id)
        subjectKey(row["entity_type"].toString())
        return decorate(row,actor)+mapOf("history" to store.rows("select e.action,e.note,e.occurred_at,u.display_name actor from grant_workspace_events e join users u on u.id=e.actor_user_id where e.entity_type='due-diligence' and e.entity_id=? order by e.occurred_at desc limit 100",id))
    }
    @Transactional
    fun save(type: String,id: UUID?,input: GrantRecordInput,actor: GrantActor): GrantRow {
        val key=subjectKey(type);actor.requireAny(preparers(type))
        val before=id?.let { store.one("due_diligence_reviews",it,true) }
        if(before!=null) {
            store.checkVersion(before,input.version)
            require(before["entity_type"]==type && editable(before,actor)) { "This due diligence review is locked at its current stage" }
        }
        val values=GrantRules.validate(form(type),input.values)
        val entity=UUID.fromString(values["entity_id"].toString())
        records.get(key,entity,actor)
        val subject=store.one(key,entity,true)
        require(subject["active"]==true) { "The selected institution or supplier is inactive" }
        require(before==null || store.uuid(before,"entity_id")==entity) { "A review cannot be transferred to another institution or supplier" }
        val checks=GrantDiligenceRules.validateChecklist(values)
        val record=id?:UUID.randomUUID()
        if(before==null) store.jdbc.update("""insert into due_diligence_reviews(id,entity_type,entity_id,review_type,status,risk_rating,findings,conditions,expires_at,prepared_by,checklist,evidence_url)
            values (?,?,?,?,'DRAFT',?,?,?,?::date,?,?::jsonb,?)""",record,type,entity,values["review_type"],values["risk_rating"],values["findings"],values["conditions"],values["expires_at"],actor.id,store.encode(checks),values["evidence_url"])
        else store.jdbc.update("""update due_diligence_reviews set review_type=?,risk_rating=?,findings=?,conditions=?,expires_at=?::date,checklist=?::jsonb,evidence_url=?,record_version=record_version+1,updated_at=now() where id=?""",values["review_type"],values["risk_rating"],values["findings"],values["conditions"],values["expires_at"],store.encode(checks),values["evidence_url"],record)
        store.registerOwner("due-diligence",record,actor)
        store.event("due-diligence",record,if(id==null) "CREATE" else "UPDATE",actor,before,store.one("due_diligence_reviews",record))
        return get(record,actor)
    }
    @Transactional
    fun action(id: UUID,input: GrantActionInput,actor: GrantActor): GrantRow {
        actor.requireAny(readers)
        val before=store.one("due_diligence_reviews",id,true)
        val hash=store.hash(mapOf("action" to input.action,"version" to input.version,"note" to input.note,"evidence" to input.evidenceUrl,"values" to input.values))
        val receipt=store.rows("select * from grant_record_action_receipts where resource_key='due-diligence' and record_id=? and request_id=?",id,input.requestId).firstOrNull()
        if(receipt!=null) {
            if(store.uuid(receipt,"actor_user_id")!=actor.id || receipt["request_hash"]!=hash) throw ResponseStatusException(HttpStatus.CONFLICT,"This action reference has already been used for a different request")
            @Suppress("UNCHECKED_CAST") return receipt["result"] as GrantRow
        }
        store.checkVersion(before,input.version)
        val definition=allowed(before,actor).firstOrNull { it.code==input.action } ?: throw ResponseStatusException(HttpStatus.FORBIDDEN,"You are not authorised for this due diligence decision")
        require(input.note.isNotBlank()&&input.note.length<=8000) { "A clear decision note is required" }
        require(input.values.keys.all { it=="noConflict" }) { "Unsupported decision fields" }
        val evidence=input.evidenceUrl?.takeIf { it.isNotBlank() }?.let(GrantRules::safeUrl)
        if(definition.independent) {
            require(input.values["noConflict"]==true) { "Declare that you have no conflict of interest" }
            require(evidence!=null) { "Supporting decision evidence is required" }
        }
        if(input.action in setOf("REQUEST_REVIEW","APPROVE")) {
            require(before["evidence_url"]!=null && !before["findings"]?.toString().isNullOrBlank()) { "Complete the assessment and controlled evidence" }
            @Suppress("UNCHECKED_CAST") val checks=before["checklist"] as? Map<String,Any?> ?: emptyMap()
            require(checks.keys==GrantDiligenceRules.checks.keys) { "Complete all due diligence checks" }
            if(input.action=="APPROVE") {
                require(checks.values.all { it in setOf("PASS","NOT_APPLICABLE") }) { "Unresolved or failed checks cannot be approved" }
                require(!LocalDate.parse(before["expires_at"].toString()).isBefore(LocalDate.now())) { "The proposed approval validity date has passed" }
                require(before["conditions"]?.toString().isNullOrBlank()) { "Resolve recorded conditions before approving the review" }
            }
        }
        val key=subjectKey(before["entity_type"].toString());val entity=store.uuid(before,"entity_id")!!
        store.one(key,entity,true)
        store.jdbc.update("update due_diligence_reviews set status=?,decision_note=?,reviewed_by=?,reviewed_at=case when ? then now() else reviewed_at end,submitted_at=case when ? then now() else submitted_at end,record_version=record_version+1,updated_at=now() where id=?",definition.to,input.note,if(definition.independent) actor.id else null,definition.independent,input.action=="REQUEST_REVIEW",id)
        if(definition.independent) {
            store.jdbc.update("update $key set due_diligence_status=?,due_diligence_review_id=?,due_diligence_valid_until=?::date,due_diligence_evidence_url=?,record_version=record_version+1 where id=?",definition.to,id,if(definition.to=="APPROVED") before["expires_at"] else null,evidence,entity)
        }
        store.event("due-diligence",id,input.action,actor,before,store.one("due_diligence_reviews",id)+mapOf("decision_evidence_url" to evidence),input.note)
        val result=get(id,actor)
        store.jdbc.update("insert into grant_record_action_receipts(resource_key,record_id,request_id,actor_user_id,request_hash,result) values ('due-diligence',?,?,?,?,?::jsonb)",id,input.requestId,actor.id,hash,store.encode(result))
        return result
    }
}

@RestController
@RequestMapping("/api/workbench/due-diligence")
class WorkbenchDueDiligenceController(private val auth: WorkbenchAuth,private val service: WorkbenchDueDiligence) {
    @GetMapping fun list(request: HttpServletRequest,@RequestParam(defaultValue="") q: String)=service.list(auth.actor(request),q)
    @GetMapping("/{id}") fun get(request: HttpServletRequest,@PathVariable id: UUID)=service.get(id,auth.actor(request))
    @PostMapping("/subjects/{type}") fun create(request: HttpServletRequest,@PathVariable type: String,@RequestBody input: GrantRecordInput)=service.save(type,null,input,auth.actor(request))
    @PutMapping("/subjects/{type}/{id}") fun update(request: HttpServletRequest,@PathVariable type: String,@PathVariable id: UUID,@RequestBody input: GrantRecordInput)=service.save(type,id,input,auth.actor(request))
    @PostMapping("/{id}/actions") fun action(request: HttpServletRequest,@PathVariable id: UUID,@RequestBody input: GrantActionInput)=service.action(id,input,auth.actor(request))
}
