package org.nhrc.grants.workbench

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

@Service
class WorkbenchOversight(private val store: WorkbenchStore) {
    private fun staff(actor: GrantActor)=actor.hasAny(WorkbenchCatalogue.allBusiness-"RESEARCHER")
    private fun requireAward(id: UUID,actor: GrantActor,lock: Boolean=false): GrantRow {
        actor.requireAny(WorkbenchCatalogue.allBusiness)
        val row=store.one("awards",id,lock)
        if(!staff(actor) && store.uuid(row,"principal_investigator_id")!=actor.id) throw ResponseStatusException(HttpStatus.FORBIDDEN,"You are not assigned to this award")
        return row
    }
    fun summary(actor: GrantActor): GrantRow {
        actor.requireAny(WorkbenchCatalogue.allBusiness)
        val all=staff(actor)
        val pipeline=store.rows("select stage,count(*) count from applications where (? or lead_researcher_id=? or owner_user_id=?) group by stage order by stage",all,actor.id,actor.id)
        return mapOf(
            "opportunities" to (store.jdbc.queryForObject("select count(*) from opportunities",Long::class.java) ?: 0L),
            "applications" to pipeline.sumOf { (it["count"] as Number).toLong() },
            "activeAwards" to (store.jdbc.queryForObject("select count(*) from awards where status='ACTIVE' and (? or principal_investigator_id=?)",Long::class.java,all,actor.id) ?: 0L),
            "myPendingReviews" to (store.jdbc.queryForObject("select count(*) from application_reviews r join applications a on a.id=r.application_id where r.reviewer_id=? and r.status='PENDING' and r.content_revision=a.content_revision",Long::class.java,actor.id) ?: 0L),
            "pipeline" to pipeline,"finance" to finance(actor)["currencies"]
        )
    }
    fun finance(actor: GrantActor): GrantRow {
        actor.requireAny(WorkbenchCatalogue.allBusiness)
        // Never relabel historical foreign-currency transactions as the award currency.
        val position=store.rows("""select a.id,a.reference,a.title,a.currency,a.nhrc_allocation approved_budget,
            coalesce(r.cash,0) verified_receipts,coalesce(e.spent,0) verified_expenditure,coalesce(c.committed,0) open_commitments,
            a.nhrc_allocation-coalesce(e.spent,0)-coalesce(c.committed,0) uncommitted_budget,r.latest_verification,
            (select count(*) from fund_receipts x where x.award_id=a.id and x.currency is distinct from a.currency)
            +(select count(*) from expenditures x where x.award_id=a.id and x.currency is distinct from a.currency)
            +(select count(*) from commitments x where x.award_id=a.id and x.currency is distinct from a.currency) excluded_currency_mismatches
            from awards a
            left join (select award_id,currency,sum(received_amount) cash,max(verified_at) latest_verification from fund_receipts where verified_at is not null group by award_id,currency) r on r.award_id=a.id and r.currency=a.currency
            left join (select award_id,currency,sum(amount) spent from expenditures where reconciled group by award_id,currency) e on e.award_id=a.id and e.currency=a.currency
            left join (select award_id,currency,sum(amount) committed from commitments where status='OPEN' group by award_id,currency) c on c.award_id=a.id and c.currency=a.currency
            where a.status in ('ACTIVE','CLOSING') and (? or a.principal_investigator_id=?) order by a.currency,a.reference""",staff(actor),actor.id)
        val currencies=position.groupBy { it["currency"].toString() }.map { (currency,rows) ->
            mapOf("currency" to currency,"awards" to rows.size,
                "excluded_currency_mismatches" to rows.sumOf { (it["excluded_currency_mismatches"] as? Number)?.toLong() ?: 0L })+
                listOf("approved_budget","verified_receipts","verified_expenditure","open_commitments","uncommitted_budget").associateWith { key -> rows.fold(BigDecimal.ZERO) { total,row -> total+store.decimal(row,key) }.toPlainString() }
        }
        return mapOf("currencies" to currencies,"awards" to position.map(store::wire),"note" to "Currencies are not combined. Transactions with a different or missing currency are excluded and counted for review. Receipts and expenditure include only verified records. Uncommitted budget is not a bank balance. This register does not post entries to NHRC's accounting system")
    }
    fun personal(actor: GrantActor): GrantRow {
        actor.requireAny(WorkbenchCatalogue.allBusiness)
        val work=store.rows("""select a.id,a.reference,a.title,a.stage,a.deadline_at,'ASSIGNMENT' work_type from applications a join application_assignments x on x.application_id=a.id where x.researcher_id=? and x.response='PENDING' and a.stage='ASSIGNED'
            union all select a.id,a.reference,a.title,a.stage,a.deadline_at,r.review_type work_type from applications a join application_reviews r on r.application_id=a.id where r.reviewer_id=? and r.status='PENDING' and r.content_revision=a.content_revision""",actor.id,actor.id)
        val calendar=store.rows("""select r.id,r.title,r.due_date,'deliverables' resource_key,a.reference award_reference from award_deliverables r join awards a on a.id=r.award_id where (r.owner_user_id=? or a.principal_investigator_id=?) and r.status not in ('ACCEPTED','COMPLETED')
            union all select r.id,r.report_type title,r.due_date,'reports' resource_key,a.reference award_reference from reports r join awards a on a.id=r.award_id where (r.owner_user_id=? or a.principal_investigator_id=?) and r.status not in ('ACCEPTED','COMPLETED') order by due_date nulls last limit 200""",actor.id,actor.id,actor.id,actor.id)
        return mapOf("work" to work,"calendar" to calendar,"calendarLimit" to 200,"notifications" to store.rows("select id,title,message,entity_type,entity_id,read_at,created_at from notifications where user_id=? order by created_at desc limit 100",actor.id))
    }
    @Transactional
    fun readNotification(id: UUID,actor: GrantActor): GrantRow {
        actor.requireAny(WorkbenchCatalogue.allBusiness)
        if(store.jdbc.update("update notifications set read_at=coalesce(read_at,now()) where id=? and user_id=?",id,actor.id)==0) throw ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found")
        return mapOf("id" to id,"read" to true)
    }
    fun awards(actor: GrantActor,query: String): GrantRow {
        actor.requireAny(WorkbenchCatalogue.allBusiness);require(query.length<=200) { "Search is too long" }
        val rows=store.rows("select a.*,u.display_name principal_investigator,f.name funder from awards a left join users u on u.id=a.principal_investigator_id left join funders f on f.id=a.funder_id where (? or a.principal_investigator_id=?) and (a.title ilike ? or a.reference ilike ?) order by a.end_date nulls last,a.id limit 201",staff(actor),actor.id,"%$query%","%$query%")
        return mapOf("items" to rows.take(200).map(store::wire),"truncated" to (rows.size>200))
    }
    private fun awardActions(row: GrantRow,actor: GrantActor): List<GrantTransition> {
        val independent=store.uuid(row,"principal_investigator_id")!=actor.id && store.uuid(row,"application_id")?.let { store.uuid(store.one("applications",it),"owner_user_id")!=actor.id }!=false
        return listOf(
            GrantTransition("ACTIVATE","Activate award after setup review",setOf("SETUP"),"ACTIVE",setOf("DIRECTOR"),true,true),
            GrantTransition("START_CLOSEOUT","Start controlled closeout",setOf("ACTIVE"),"CLOSING",WorkbenchCatalogue.grantRoles,evidenceRequired=true),
            GrantTransition("CLOSE_AWARD","Confirm institutional closeout",setOf("CLOSING"),"CLOSED",setOf("DIRECTOR"),true,true)
        ).filter { row["status"] in it.from && actor.hasAny(it.roles) && (!it.independent || independent) }
    }
    fun award(id: UUID,actor: GrantActor): GrantRow {
        val row=requireAward(id,actor)
        return store.wire(row+mapOf(
            "_actions" to awardActions(row,actor),
            "contracts" to store.rows("select * from contracts where award_id=? order by created_at desc",id),
            "partners" to store.rows("select ap.*,p.name partner_name,p.due_diligence_status from award_partners ap join partners p on p.id=ap.partner_id where ap.award_id=?",id),
            "compliance" to store.rows("select * from compliance_records where award_id=? order by expiry_date nulls last",id),
            "deliverables" to store.rows("select * from award_deliverables where award_id=? order by due_date",id),
            "closeout" to store.rows("select * from closeout_items where award_id=? order by item_type,title",id),
            "history" to store.rows("select e.action,e.note,e.occurred_at,u.display_name actor from grant_workspace_events e join users u on u.id=e.actor_user_id where e.entity_type='awards' and e.entity_id=? order by e.occurred_at desc limit 100",id)
        ))
    }
    @Transactional
    fun awardAction(id: UUID,input: GrantActionInput,actor: GrantActor): GrantRow {
        val before=requireAward(id,actor,true)
        val hash=store.hash(mapOf("action" to input.action,"version" to input.version,"note" to input.note,"evidence" to input.evidenceUrl,"values" to input.values))
        val receipt=store.rows("select * from grant_record_action_receipts where resource_key='awards' and record_id=? and request_id=?",id,input.requestId).firstOrNull()
        if(receipt!=null) {
            if(store.uuid(receipt,"actor_user_id")!=actor.id || receipt["request_hash"]!=hash) throw ResponseStatusException(HttpStatus.CONFLICT,"This action reference was already used for another request")
            @Suppress("UNCHECKED_CAST")
            return receipt["result"] as GrantRow
        }
        store.checkVersion(before,input.version)
        val definition=awardActions(before,actor).firstOrNull { it.code==input.action } ?: throw ResponseStatusException(HttpStatus.FORBIDDEN,"This award action is not available to you")
        require(input.note.isNotBlank() && input.note.length<=8000) { "A decision note is required" }
        val evidence=GrantRules.safeUrl(input.evidenceUrl ?: throw IllegalArgumentException("Supporting evidence is required"))
        if(definition.independent) require(input.values["noConflict"]==true) { "Declare that you have no conflict before authorising this decision" }
        when(input.action) {
            "ACTIVATE" -> {
                require(before["start_date"]!=null && before["end_date"]!=null && store.decimal(before,"nhrc_allocation")>BigDecimal.ZERO) { "Complete the award dates and NHRC allocation" }
                require(store.rows("select id from contracts where award_id=? and status='APPROVED' and storage_key is not null",id).isNotEmpty()) { "An approved agreement with a controlled document link is required" }
                require(store.rows("select id from award_deliverables where award_id=?",id).isNotEmpty()) { "Record the award's delivery and reporting obligations" }
                require(store.rows("select id from award_partners where award_id=? and agreement_status<>'ACTIVE'",id).isEmpty()) { "Complete the outstanding partner agreements" }
                require(store.rows("select id from compliance_records where award_id=? and (status<>'APPROVED' or (expiry_date is not null and expiry_date<current_date))",id).isEmpty()) { "Resolve outstanding or expired compliance records" }
                val checklist=input.values["setupChecks"] as? Map<*,*> ?: throw IllegalArgumentException("Complete the award setup checklist")
                require(setOf("agreement","finance","reporting","governance","partners").all { checklist[it] in setOf("CONFIRMED","NOT_APPLICABLE") }) { "Review every setup requirement, including whether approvals are required before work starts" }
            }
            "START_CLOSEOUT" -> {
                val requirements=listOf("TECHNICAL" to "Final technical report accepted","FINANCIAL" to "Final accounts, balances and refunds resolved","PARTNERS" to "Partner obligations and advances settled","ASSETS" to "Equipment and asset disposition agreed","DATA" to "Data, records and access retention agreed","COMPLIANCE" to "Regulatory and contractual closure recorded")
                if(store.rows("select id from closeout_items where award_id=?",id).isEmpty()) for((type,title) in requirements) {
                    val item=UUID.randomUUID()
                    store.jdbc.update("insert into closeout_items(id,award_id,item_type,title,responsible_role) values (?,?,?,?,?)",item,id,type,title,"POST_AWARD_OFFICER")
                    store.registerOwner("closeout",item,actor)
                }
            }
            "CLOSE_AWARD" -> {
                val checklist=store.rows("select id,status from closeout_items where award_id=?",id)
                require(checklist.isNotEmpty() && checklist.all { it["status"]=="COMPLETED" }) { "Complete the controlled closeout checklist" }
                require(store.rows("select id from commitments where award_id=? and status='OPEN' and amount>0",id).isEmpty()) { "Resolve outstanding commitments" }
                require(store.rows("select pa.id from partner_advances pa join award_partners ap on ap.id=pa.award_partner_id where ap.award_id=? and pa.amount_advanced>pa.amount_accounted",id).isEmpty()) { "Retire outstanding partner advances" }
                require(store.rows("select id from reports where award_id=? and status<>'ACCEPTED'",id).isEmpty()) { "Record acceptance of all technical reports" }
                require(store.rows("select id from financial_reports where award_id=? and status<>'ACCEPTED'",id).isEmpty()) { "Record acceptance of all financial reports" }
                require(store.rows("select id from fund_receipts where award_id=? and received_amount is not null and verified_at is null",id).isEmpty()) { "Verify the remaining receipts" }
                require(store.rows("select id from expenditures where award_id=? and not reconciled",id).isEmpty()) { "Reconcile the remaining expenditure" }
                val currency=before["currency"]
                require(store.rows("select id from fund_receipts where award_id=? and currency is distinct from ? union all select id from expenditures where award_id=? and currency is distinct from ? union all select id from commitments where award_id=? and currency is distinct from ?",id,currency,id,currency,id,currency).isEmpty()) { "Resolve currency mismatches before closing the award" }
            }
        }
        store.jdbc.update("update awards set status=?,record_version=record_version+1,updated_at=now() where id=?",definition.to,id)
        store.event("awards",id,input.action,actor,before,store.one("awards",id)+mapOf("evidence_url" to evidence,"checks" to input.values),input.note)
        val result=mapOf("id" to id,"status" to definition.to,"record_version" to store.version(store.one("awards",id)))
        store.jdbc.update("insert into grant_record_action_receipts(resource_key,record_id,request_id,actor_user_id,request_hash,result) values ('awards',?,?,?,?,?::jsonb)",id,input.requestId,actor.id,hash,store.encode(result))
        return result
    }
}
