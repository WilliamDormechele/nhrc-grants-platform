package org.nhrc.grants.workbench

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Service
class WorkbenchRecords(private val store: WorkbenchStore, private val auth: WorkbenchAuth) {
    fun resource(key: String): GrantResource = WorkbenchCatalogue.resources[key]
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND,"Unknown workspace")
    fun catalogue(actor: GrantActor): List<GrantRow> = WorkbenchCatalogue.resources.values.filter { actor.hasAny(it.readers) }.map {
        mapOf("key" to it.key,"title" to it.title,"fields" to it.fields,"canCreate" to actor.hasAny(it.writers),"statusColumn" to it.statusColumn,"transitions" to it.transitions)
    }
    private fun scope(r: GrantResource, actor: GrantActor): Pair<String,List<Any?>> {
        if(r.key=="support" && !actor.hasAny(setOf("ADMIN"))) return "t.requester_id=?" to listOf(actor.id)
        if(r.key=="applications" && !actor.hasAny(r.readers-"RESEARCHER")) return "(t.lead_researcher_id=? or t.owner_user_id=? or exists(select 1 from application_reviews ar where ar.application_id=t.id and ar.reviewer_id=?))" to listOf(actor.id,actor.id,actor.id)
        if(r.awardColumn != null && !actor.hasAny(r.readers-"RESEARCHER")) return "exists(select 1 from awards aw where aw.id=t.${r.awardColumn} and aw.principal_investigator_id=?)" to listOf(actor.id)
        return "true" to emptyList()
    }
    fun list(key: String, actor: GrantActor, query: String, page: Int, size: Int): GrantRow {
        val r=resource(key); actor.requireAny(r.readers)
        require(query.length<=200 && page in 0..100000 && size in 1..100) { "Invalid search or page size" }
        val (scope,params)=scope(r,actor)
        val searchable=r.fields.filter { it.kind in setOf("text","textarea","email") }.map { it.name }.take(6)
        val search=if(searchable.isEmpty()) "true" else searchable.joinToString(" or ","(",")") { "coalesce(t.$it,'') ilike ?" }
        val args=params + searchable.map { "%$query%" }
        val where="$scope and $search"
        val count=store.jdbc.queryForObject("select count(*) from ${r.table} t where $where",Long::class.java,*args.toTypedArray()) ?: 0L
        val sort=r.fields.firstOrNull { it.name in setOf("deadline_date","due_date","scheduled_date") }?.name
            ?: r.fields.firstOrNull { it.name in setOf("name","legal_name","title","subject","reference") }?.name ?: "id"
        val data=store.rows("select t.* from ${r.table} t where $where order by t.$sort nulls last,t.id limit ? offset ?",*(args+listOf(size,page.toLong()*size)).toTypedArray())
        return mapOf("items" to data.map { decorate(r,it,actor) },"total" to count,"page" to page,"size" to size,"hasMore" to ((page.toLong()+1)*size<count))
    }
    fun get(key: String,id: UUID,actor: GrantActor): GrantRow {
        val r=resource(key); val row=store.one(r.table,id); auth.requireRead(r,row,actor)
        return decorate(r,row,actor)
    }
    fun history(key: String,id: UUID,actor: GrantActor): List<GrantRow> {
        get(key,id,actor)
        return store.rows("select e.id,e.action,e.note,e.occurred_at,u.display_name actor,e.previous_value,e.new_value from grant_workspace_events e join users u on u.id=e.actor_user_id where e.entity_type=? and e.entity_id=? order by e.occurred_at desc limit 100",key,id).map(store::wire)
    }
    fun lookups(key: String,actor: GrantActor,query: String): GrantRow {
        require(query.length<=200) { "Search is too long" }
        actor.requireAny(WorkbenchCatalogue.allBusiness)
        val result = when(key) {
            "users" -> store.rows("""select u.id,u.display_name label,u.job_title,
                coalesce((select jsonb_agg(distinct r.code) from user_roles ur join roles r on r.id=ur.role_id join organisation_units ou on ou.id=ur.scope_id where ur.user_id=u.id and ur.scope_type='INSTITUTION' and ou.code='NHRC' and (ur.valid_from is null or ur.valid_from<=now()) and (ur.valid_until is null or ur.valid_until>now())),'[]'::jsonb) roles
                from users u where u.active and u.display_name ilike ? order by u.display_name,u.id limit 201""","%$query%")
            "awards" -> {
                val staff=actor.hasAny(WorkbenchCatalogue.allBusiness-"RESEARCHER")
                store.rows("select id,reference || ' | ' || title label,currency,status from awards where title ilike ? and (? or principal_investigator_id=?) order by title,id limit 201","%$query%",staff,actor.id)
            }
            else -> {
                val r=resource(key); actor.requireAny(r.readers)
                val (condition,parameters)=scope(r,actor)
                val label = r.fields.firstOrNull { it.name in setOf("name","legal_name","title","subject","reference","item_description") }?.name ?: "id"
                store.rows("select t.id,t.$label::text label from ${r.table} t where $condition and t.$label::text ilike ? order by t.$label,t.id limit 201",*(parameters+"%$query%").toTypedArray())
            }
        }
        return mapOf("items" to result.take(200),"truncated" to (result.size>200))
    }
    fun canEdit(r: GrantResource,row: GrantRow,actor: GrantActor): Boolean {
        if(!actor.hasAny(r.writers) || !auth.canRead(r,row,actor)) return false
        if(r.key=="researchers" && !actor.hasAny(WorkbenchCatalogue.profileWriters) && store.uuid(row,"user_id")!=actor.id) return false
        if(r.key=="applications") return row["stage"] in setOf("DISCOVERED","ELIGIBILITY_REVIEW","PREPARATION")
        if(r.key=="receipts" && row["verified_at"]!=null) return false
        if(r.key=="expenditures" && row["reconciled"]==true) return false
        if(r.statusColumn==null) return true
        val status=row[r.statusColumn]?.toString() ?: return true
        if(status=="PENDING" && r.transitions.any { it.code=="REQUEST_REVIEW" }) return false
        return status in setOf("DRAFT","RETURNED","OPEN","PLANNED","ACTIVE","SCHEDULED","NOT_STARTED","IN_PROGRESS","OUTSTANDING","CLAIMED","RECORDED","PENDING","NEW")
    }
    private fun flow(r: GrantResource,row: GrantRow): List<GrantTransition> = when(r.key) {
        "receipts" -> if(row["verified_at"]==null) listOf(GrantTransition("VERIFY_RECEIPT","Verify receipt against accounting evidence",setOf("PENDING"),"VERIFIED",setOf("FINANCE_APPROVER"),true,true)) else emptyList()
        "expenditures" -> if(row["reconciled"]!=true) listOf(GrantTransition("VERIFY_EXPENDITURE","Verify expenditure against accounting evidence",setOf("PENDING"),"VERIFIED",setOf("FINANCE_APPROVER"),true,true)) else emptyList()
        else -> r.transitions
    }
    fun decorate(r: GrantResource,row: GrantRow,actor: GrantActor): GrantRow {
        val id=store.uuid(row,"id")!!
        val status=r.statusColumn?.let { row[it]?.toString() } ?: "PENDING"
        val creator=store.owner(r.key,id) ?: r.creatorColumn?.let { store.uuid(row,it) }
        val actions=flow(r,row).filter { status in it.from && actor.hasAny(it.roles) && (!it.independent || (creator!=null && creator!=actor.id && store.uuid(row,"owner_user_id")!=actor.id)) }
        val required=r.fields.filter { it.required }
        val filled=required.count { !row[it.name]?.toString().isNullOrBlank() }
        return store.wire(row + mapOf("_editable" to canEdit(r,row,actor),"_actions" to actions,"_requiredComplete" to filled,"_requiredTotal" to required.size))
    }
    private fun validateLinks(r: GrantResource,values: Map<String,Any?>,actor: GrantActor,id: UUID?) {
        r.fields.filter { it.kind=="lookup" }.forEach { f ->
            val value=values[f.name]?.toString() ?: return@forEach
            val target=UUID.fromString(value)
            when(f.lookup) {
                "users" -> require(store.rows("select id from users where id=? and active",target).isNotEmpty()) { "${f.label} is not an active user" }
                "awards" -> {
                    val award=store.one("awards",target)
                    if(!actor.hasAny(WorkbenchCatalogue.allBusiness-"RESEARCHER") && store.uuid(award,"principal_investigator_id")!=actor.id) throw ResponseStatusException(HttpStatus.FORBIDDEN,"You are not assigned to this award")
                    require(award["status"] !in setOf("CLOSED","CANCELLED")) { "This award is closed" }
                    values["currency"]?.let { require(it==award["currency"]) { "The currency must match the award; no automatic currency conversion is applied" } }
                }
                else -> { val linked=resource(f.lookup!!); auth.requireRead(linked,store.one(linked.table,target),actor) }
            }
            if(r.key=="units" && f.name=="parent_id") {
                require(target!=id) { "A unit cannot be its own parent" }
                if(id!=null) require(store.rows("with recursive ancestors as (select id,parent_id,array[id] path from organisation_units where id=? union all select u.id,u.parent_id,a.path||u.id from organisation_units u join ancestors a on u.id=a.parent_id where not u.id=any(a.path)) select id from ancestors where id=?",target,id).isEmpty()) { "The parent selection would create a cycle" }
            }
        }
    }
    private fun consistency(r: GrantResource,values: Map<String,Any?>,before: GrantRow?) {
        for(pair in listOf("start_date" to "end_date","effective_date" to "expiry_date","period_start" to "period_end","forecast_date" to "period_end","approved_date" to "expiry_date")) GrantRules.validateDates(values,pair.first,pair.second)
        values["country_code"]?.let { require(Regex("^[A-Z]{2,3}$").matches(it.toString())) { "Use an uppercase two- or three-letter country code" } }
        if(r.key=="opportunities") {
            val lo=values["amount_min"] as? BigDecimal;val hi=values["amount_max"] as? BigDecimal
            require(lo==null || hi==null || lo<=hi) { "The maximum award must not be below the minimum award" }
            require((lo==null && hi==null) || values["currency"]!=null) { "Specify the currency when an award amount is entered" }
        }
        if(r.key=="researchers" && before!=null) require(values["user_id"]?.toString()==before["user_id"]?.toString()) { "A researcher profile cannot be transferred to another user" }
        if(r.key=="units" && before?.get("code")=="NHRC") require(values["code"]=="NHRC" && values["parent_id"]==null) { "The root institutional code and parent cannot be changed" }
        if(r.key=="advances") require((values["amount_accounted"] as BigDecimal)<=(values["amount_advanced"] as BigDecimal)) { "Accounted expenditure cannot exceed the advance" }
        if(r.key=="receipts") require((values["received_amount"]==null)==(values["received_date"]==null)) { "Received amount and receipt date must be recorded together" }
        if(r.key=="purchase-orders") {
            val req=store.one("procurement_requisitions",UUID.fromString(values["requisition_id"].toString()))
            require(req["status"]=="APPROVED") { "The requisition must be approved before an order is prepared" }
            require(req["currency"]==values["currency"]) { "The order currency must match the approved requisition" }
            require((values["amount"] as BigDecimal)<=store.decimal(req,"amount")) { "The order exceeds the approved requisition" }
            val supplier=store.one("suppliers",UUID.fromString(values["supplier_id"].toString()))
            require(supplier["due_diligence_status"]=="APPROVED") { "Supplier due diligence has not been approved" }
        }
        if(r.key=="requisitions" && values["plan_id"]!=null) {
            val plan=store.one("procurement_plans",UUID.fromString(values["plan_id"].toString()))
            require(plan["award_id"]?.toString()==values["award_id"]?.toString()) { "The plan and requisition must relate to the same award" }
        }
        if(r.key=="assets" && values["purchase_order_id"]!=null) {
            val po=store.one("purchase_orders",UUID.fromString(values["purchase_order_id"].toString()))
            val req=store.one("procurement_requisitions",store.uuid(po,"requisition_id")!!)
            require(req["award_id"]?.toString()==values["award_id"]?.toString()) { "The asset and order must relate to the same award" }
        }
        if(r.key=="maintenance") require(store.one("laboratory_items",UUID.fromString(values["laboratory_item_id"].toString()))["item_type"]=="EQUIPMENT") { "Maintenance must refer to an equipment item" }
    }
    @Transactional
    fun save(key: String,id: UUID?,input: GrantRecordInput,actor: GrantActor): GrantRow {
        val r=resource(key); val before=id?.let { store.one(r.table,it,true) }
        val values=GrantRules.validate(r.fields,input.values)
        if(r.key=="researchers" && before==null && !actor.hasAny(WorkbenchCatalogue.profileWriters)) {
            actor.requireAny(setOf("RESEARCHER"));require(values["user_id"]==actor.id.toString()) { "You may create only your own researcher profile" }
        } else auth.requireWrite(r,before,actor)
        if(before!=null) {
            store.checkVersion(before,input.version)
            if(!canEdit(r,before,actor)) throw ResponseStatusException(HttpStatus.CONFLICT,"This record is locked at its current stage")
        }
        validateLinks(r,values,actor,id);consistency(r,values,before)
        val recordId=id ?: UUID.randomUUID()
        val columns=r.fields.map { it.name }.toMutableList()
        val bindings=r.fields.map(store::bind).toMutableList()
        val args=r.fields.map { store.parameter(it,values[it.name]) }.toMutableList()
        if(before==null) {
            columns.add("id");bindings.add("?");args.add(recordId)
            if(r.statusColumn!=null && r.initialStatus!=null) { columns.add(r.statusColumn);bindings.add("?");args.add(r.initialStatus) }
            r.creatorColumn?.let { columns.add(it);bindings.add("?");args.add(actor.id) }
            if(r.key in setOf("applications","support")) { columns.add("reference");bindings.add("?");args.add((if(r.key=="applications") "APP" else "SUP")+"-${LocalDate.now().year}-"+recordId.toString().take(8).uppercase()) }
            if(r.key=="opportunities") { columns.add("source_type");bindings.add("?");args.add("MANUAL") }
            if(r.key=="risks") { columns.add("rating");bindings.add("?");args.add(GrantRules.riskRating(values["likelihood"].toString(),values["impact"].toString())) }
            if(r.key=="reconciliations") { columns.add("variance");bindings.add("?");args.add((values["ledger_amount"] as BigDecimal)-(values["platform_amount"] as BigDecimal)) }
            store.jdbc.update("insert into ${r.table}(${columns.joinToString()}) values (${bindings.joinToString()})",*args.toTypedArray())
        } else {
            var extra=""
            if(r.key=="applications") {
                require(before["opportunity_id"]?.toString()==values["opportunity_id"]?.toString() || before["stage"]=="DISCOVERED") { "The funding call cannot be changed after eligibility review starts" }
                require(before["application_type"]==values["application_type"] || before["stage"]=="DISCOVERED") { "Create a linked next-stage application rather than replacing the submission type" }
                extra=",content_revision=content_revision+1"
            }
            if(r.key=="risks") { extra+=",rating=?";args.add(GrantRules.riskRating(values["likelihood"].toString(),values["impact"].toString())) }
            if(r.key=="reconciliations") { extra+=",variance=?";args.add((values["ledger_amount"] as BigDecimal)-(values["platform_amount"] as BigDecimal)) }
            if(r.key in setOf("institutions","researchers","opportunities","applications","contracts","units")) extra+=",updated_at=now()"
            args.add(recordId)
            store.jdbc.update("update ${r.table} set ${columns.zip(bindings).joinToString { (c,b) -> "$c=$b" }},record_version=record_version+1$extra where id=?",*args.toTypedArray())
        }
        store.registerOwner(key,recordId,actor)
        val after=store.one(r.table,recordId)
        store.event(key,recordId,if(before==null) "CREATE" else "UPDATE",actor,before,after)
        return decorate(r,after,actor)
    }
    @Transactional
    fun action(key: String,id: UUID,input: GrantActionInput,actor: GrantActor): GrantRow {
        require(key!="applications") { "Use the application workflow action" }
        val r=resource(key); val before=store.one(r.table,id,true);auth.requireRead(r,before,actor)
        val hash=store.hash(mapOf("action" to input.action,"version" to input.version,"note" to input.note,"evidence" to input.evidenceUrl,"values" to input.values))
        val receipt=store.rows("select * from grant_record_action_receipts where resource_key=? and record_id=? and request_id=?",key,id,input.requestId).firstOrNull()
        if(receipt!=null) {
            if(store.uuid(receipt,"actor_user_id")!=actor.id || receipt["request_hash"]!=hash) throw ResponseStatusException(HttpStatus.CONFLICT,"This action reference was already used for a different request")
            @Suppress("UNCHECKED_CAST")
            return receipt["result"] as GrantRow
        }
        store.checkVersion(before,input.version)
        require(input.note.isNotBlank() && input.note.length<=8000) { "A decision note is required (maximum 8,000 characters)" }
        val definition=flow(r,before).firstOrNull { it.code==input.action } ?: throw ResponseStatusException(HttpStatus.CONFLICT,"This action is unavailable")
        actor.requireAny(definition.roles)
        val status=r.statusColumn?.let { before[it]?.toString() } ?: "PENDING"
        require(status in definition.from) { "The record is no longer at the required stage" }
        val maker=store.owner(key,id) ?: r.creatorColumn?.let { store.uuid(before,it) }
        if(definition.independent && (maker==null || maker==actor.id || store.uuid(before,"owner_user_id")==actor.id)) throw ResponseStatusException(HttpStatus.FORBIDDEN,"An independent authorised officer must make this decision; legacy records also require recorded ownership")
        val evidence=input.evidenceUrl?.takeIf { it.isNotBlank() }?.let(GrantRules::safeUrl)
        require(!definition.evidenceRequired || evidence!=null) { "Supporting evidence is required" }
        if(key=="maintenance" && input.action=="COMPLETE") require(before["completed_date"]!=null && before["certificate_storage_key"]!=null) { "Record the completion date and certificate before completing this service" }
        if(key=="receipts") require(before["received_amount"]!=null && before["received_date"]!=null) { "An expected instalment cannot be verified as received" }
        if(key=="reconciliations") require(store.decimal(before,"variance").compareTo(BigDecimal.ZERO)==0) { "Resolve the variance before approving this reconciliation" }
        if(key=="award-partners") require(store.one("partners",store.uuid(before,"partner_id")!!)["due_diligence_status"]=="APPROVED") { "Complete partner due diligence first" }
        when(key) {
            "receipts" -> store.jdbc.update("update fund_receipts set verified_by=?,verified_at=now(),record_version=record_version+1 where id=?",actor.id,id)
            "expenditures" -> store.jdbc.update("update expenditures set reconciled=true,record_version=record_version+1 where id=?",id)
            else -> {
                require(r.statusColumn!=null) { "No status transition is configured for this record" }
                store.jdbc.update("update ${r.table} set ${r.statusColumn}=?,record_version=record_version+1 where id=?",definition.to,id)
                if(key=="reconciliations") store.jdbc.update("update reconciliations set reconciled_by=?,reconciled_at=now() where id=?",actor.id,id)
                if(key=="closeout" && definition.to=="COMPLETED") store.jdbc.update("update closeout_items set completed_at=now() where id=?",id)
                if(key in setOf("reports","financial-reports","deliverables") && definition.to=="SUBMITTED") store.jdbc.update("update ${r.table} set submitted_at=now() where id=?",id)
                if(key in setOf("reports","financial-reports","deliverables") && definition.to=="ACCEPTED") store.jdbc.update("update ${r.table} set accepted_at=now() where id=?",id)
            }
        }
        val after=store.one(r.table,id)
        store.event(key,id,input.action,actor,before,after+mapOf("decision_evidence_url" to evidence),input.note)
        val result=decorate(r,after,actor)
        store.jdbc.update("insert into grant_record_action_receipts(resource_key,record_id,request_id,actor_user_id,request_hash,result) values (?,?,?,?,?,?::jsonb)",key,id,input.requestId,actor.id,hash,store.encode(result))
        return result
    }
}
