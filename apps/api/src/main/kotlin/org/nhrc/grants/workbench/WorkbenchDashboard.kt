package org.nhrc.grants.workbench

import org.springframework.stereotype.Service

@Service
class WorkbenchDashboard(private val store: WorkbenchStore) {
    fun dashboard(actor: GrantActor): GrantRow {
        actor.requireAny(WorkbenchCatalogue.platformReaders)
        val type=when {
            "SUPERADMIN" in actor.roles -> "SUPERADMIN"
            "ADMIN" in actor.roles -> "ADMIN"
            "DIRECTOR" in actor.roles || "AUDITOR" in actor.roles -> "EXECUTIVE"
            actor.hasAny(WorkbenchCatalogue.financeRoles) -> "FINANCE"
            actor.hasAny(WorkbenchCatalogue.procurementRoles) -> "PROCUREMENT"
            actor.hasAny(WorkbenchCatalogue.labRoles) -> "LABORATORY"
            actor.hasAny(WorkbenchCatalogue.governanceRoles) -> "GOVERNANCE"
            actor.hasAny(WorkbenchCatalogue.grantRoles) -> "GRANTS"
            actor.hasAny(WorkbenchCatalogue.researchRoles) -> "RESEARCH"
            else -> "PERSONAL"
        }
        return mapOf(
            "type" to type,
            "roles" to actor.roles.sorted(),
            "metrics" to metrics(type,actor),
            "queues" to queues(type,actor),
            "quickLinks" to quickLinks(type),
            "note" to when(type) {
                "SUPERADMIN" -> "Platform-wide visibility is provided for technical oversight. Business approvals remain controlled by separately assigned business roles."
                "FINANCE" -> "Financial figures remain separated by currency and include only controlled records."
                else -> "Counts come from recorded NHRC Grants data and are not forecasts of funding success."
            }
        )
    }

    private fun count(sql:String,vararg args:Any?)=(store.jdbc.queryForObject(sql,Long::class.java,*args)?:0L)

    private fun metrics(type:String,actor:GrantActor):List<GrantRow> = when(type) {
        "GRANTS" -> listOf(
            metric("Funding calls",count("select count(*) from opportunities")),
            metric("Applications due in 30 days",count("select count(*) from applications where deadline_at between now() and now()+interval '30 days' and stage not in ('CLOSED','OUTCOME_RECORDED','AWARDED')")),
            metric("Eligibility / pursuit decisions",count("select count(*) from applications where stage in ('DISCOVERED','ELIGIBILITY_REVIEW','DIRECTOR_DECISION','READY_FOR_ASSIGNMENT')")),
            metric("Applications in review",count("select count(*) from applications where stage in ('INTERNAL_REVIEW','INSTITUTIONAL_APPROVAL')"))
        )
        "RESEARCH" -> listOf(
            metric("My applications",count("select count(*) from applications where lead_researcher_id=? or id in (select application_id from application_assignments where researcher_id=?)",actor.id,actor.id)),
            metric("My active awards",count("select count(*) from awards where principal_investigator_id=? and status in ('ACTIVE','CLOSING')",actor.id)),
            metric("My deliverables due",count("select count(*) from award_deliverables where owner_user_id=? and status not in ('COMPLETED','ACCEPTED')",actor.id)),
            metric("My reports due",count("select count(*) from reports where owner_user_id=? and status not in ('COMPLETED','ACCEPTED')",actor.id))
        )
        "FINANCE" -> listOf(
            metric("Receipts awaiting verification",count("select count(*) from fund_receipts where received_amount is not null and verified_at is null")),
            metric("Unreconciled expenditure",count("select count(*) from expenditures where not reconciled")),
            metric("Open commitments",count("select count(*) from commitments where status='OPEN'")),
            metric("Financial reports due",count("select count(*) from financial_reports where status not in ('ACCEPTED','COMPLETED') and due_date<=current_date+30"))
        )
        "PROCUREMENT" -> listOf(
            metric("Open requisitions",count("select count(*) from procurement_requisitions where status not in ('CLOSED','CANCELLED')")),
            metric("Purchase orders open",count("select count(*) from purchase_orders where status not in ('CLOSED','CANCELLED')")),
            metric("Procurement plans due",count("select count(*) from procurement_plans where planned_date<=current_date+30 and status not in ('CLOSED','COMPLETED')")),
            metric("Suppliers requiring DD",count("select count(*) from suppliers where due_diligence_status is distinct from 'APPROVED' or due_diligence_valid_until<current_date"))
        )
        "LABORATORY" -> listOf(
            metric("Equipment registered",count("select count(*) from laboratory_items where item_type='EQUIPMENT'")),
            metric("Maintenance due in 30 days",count("select count(*) from laboratory_items where maintenance_due_date<=current_date+30 and maintenance_due_date>=current_date")),
            metric("Calibration due in 30 days",count("select count(*) from laboratory_items where calibration_due_date<=current_date+30 and calibration_due_date>=current_date")),
            metric("Consumables expiring in 60 days",count("select count(*) from laboratory_items where item_type in ('REAGENT','CONSUMABLE') and expiry_date between current_date and current_date+60"))
        )
        "GOVERNANCE" -> listOf(
            metric("Current compliance obligations",count("select count(*) from compliance_records where status='APPROVED'")),
            metric("Renewals due in 60 days",count("select count(*) from compliance_records where coalesce(renewal_due_date,expiry_date) between current_date and current_date+60")),
            metric("Open declarations",count("select count(*) from declarations where status not in ('CLOSED','RESOLVED')")),
            metric("Due diligence under review",count("select count(*) from due_diligence_reviews where status in ('DRAFT','PENDING','CONDITIONAL')"))
        )
        "ADMIN" -> listOf(
            metric("Active users",count("select count(*) from users where active")),
            metric("Active roles",count("select count(*) from roles")),
            metric("Forms",count("select count(*) from form_definitions where active")),
            metric("Templates",count("select count(*) from templates where active"))
        )
        "SUPERADMIN" -> listOf(
            metric("Active users",count("select count(*) from users where active")),
            metric("Funding calls",count("select count(*) from opportunities")),
            metric("Active awards",count("select count(*) from awards where status in ('ACTIVE','CLOSING')")),
            metric("Open security events",count("select count(*) from system_events where acknowledged_at is null"))
        )
        "EXECUTIVE" -> listOf(
            metric("Funding calls",count("select count(*) from opportunities")),
            metric("Applications in progress",count("select count(*) from applications where stage not in ('CLOSED','OUTCOME_RECORDED','AWARDED')")),
            metric("Active awards",count("select count(*) from awards where status in ('ACTIVE','CLOSING')")),
            metric("Reports / deliverables due",count("select (select count(*) from reports where status not in ('ACCEPTED','COMPLETED') and due_date<=current_date+30)+(select count(*) from award_deliverables where status not in ('ACCEPTED','COMPLETED') and due_date<=current_date+30)"))
        )
        else -> listOf(
            metric("Active awards",count("select count(*) from awards where status='ACTIVE'")),
            metric("Applications",count("select count(*) from applications")),
            metric("Reports due in 30 days",count("select count(*) from reports where due_date<=current_date+30 and status not in ('ACCEPTED','COMPLETED')")),
            metric("Open risks",count("select count(*) from award_risks where status='OPEN'"))
        )
    }

    private fun queues(type:String,actor:GrantActor):List<GrantRow> = when(type) {
        "GRANTS" -> store.rows("""select id,title,reference,stage,deadline_at due_date,'Applications' workspace
            from applications where stage not in ('CLOSED','OUTCOME_RECORDED','AWARDED')
            order by deadline_at nulls last limit 8""")
        "RESEARCH" -> store.rows("""select a.id,a.title,a.reference,a.stage,a.deadline_at due_date,'Applications' workspace
            from applications a where a.lead_researcher_id=? or a.id in (select application_id from application_assignments where researcher_id=?)
            order by a.deadline_at nulls last limit 8""",actor.id,actor.id)
        "FINANCE" -> store.rows("""select r.id,a.title,r.reference,'RECEIPT_VERIFICATION' stage,r.received_date due_date,'Funds Received' workspace
            from fund_receipts r join awards a on a.id=r.award_id where r.received_amount is not null and r.verified_at is null
            order by r.received_date nulls last limit 8""")
        "PROCUREMENT" -> store.rows("""select r.id,r.description title,r.reference,r.status stage,r.requested_at::date due_date,'Requisitions' workspace
            from procurement_requisitions r where r.status not in ('CLOSED','CANCELLED')
            order by r.requested_at desc limit 8""")
        "LABORATORY" -> store.rows("""select id,name title,catalogue_or_asset_ref reference,status,
            least(coalesce(maintenance_due_date,'9999-12-31'::date),coalesce(calibration_due_date,'9999-12-31'::date)) due_date,
            'Maintenance & Calibration' workspace from laboratory_items where item_type='EQUIPMENT'
            order by due_date limit 8""")
        "GOVERNANCE" -> store.rows("""select id,compliance_type title,reference,status,coalesce(renewal_due_date,expiry_date) due_date,
            'Ethics & Regulatory Links' workspace from compliance_records order by due_date nulls last limit 8""")
        "ADMIN" -> store.rows("""select id,display_name title,email reference,case when active then 'ACTIVE' else 'INACTIVE' end stage,
            null::date due_date,'Users & Roles' workspace from users order by display_name limit 8""")
        "EXECUTIVE" -> store.rows("""select a.id,a.title,a.reference,a.stage,a.deadline_at::date due_date,'Applications' workspace
            from applications a where a.stage in ('DIRECTOR_DECISION','INSTITUTIONAL_APPROVAL','APPROVED_FOR_SUBMISSION')
            order by a.deadline_at nulls last limit 8""")
        "SUPERADMIN" -> store.rows("""select id,message title,event_type reference,severity stage,created_at::date due_date,
            'Security Events' workspace from system_events order by created_at desc limit 8""")
        else -> emptyList()
    }

    private fun quickLinks(type:String):List<String> = when(type) {
        "GRANTS" -> listOf("Opportunity Intelligence","Applications","Internal Review","Submissions","Calendar")
        "RESEARCH" -> listOf("My Work","Proposal Workspace","Budget Builder","Deliverables","Calendar")
        "FINANCE" -> listOf("Finance Dashboard","Funds Received","Expenditure","Reconciliations","Calendar")
        "PROCUREMENT" -> listOf("Procurement Dashboard","Requisitions","Purchase Orders","Suppliers & Due Diligence","Calendar")
        "LABORATORY" -> listOf("Laboratory Oversight","Laboratory Equipment","Maintenance & Calibration","Reagents & Consumables","Calendar")
        "GOVERNANCE" -> listOf("Due Diligence","Ethics & Regulatory Links","Conflicts / Declarations","Compliance Calendar","Calendar")
        "ADMIN" -> listOf("Users & Roles","Forms & Fields","Templates","Workflow Configuration","Audit Log")
        "EXECUTIVE" -> listOf("Executive Overview","Approvals","Portfolio Analytics","Finance Dashboard","Calendar")
        "SUPERADMIN" -> listOf("System Health","Integration Health","Security Events","Audit Log","Superadmin Console")
        else -> listOf("My Work","Calendar")
    }

    private fun metric(label:String,value:Long):GrantRow=mapOf("label" to label,"value" to value)
}
