-- Automatic funding discovery profiles plus versioned institutional forms/templates.
CREATE TABLE IF NOT EXISTS funding_search_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(180) NOT NULL,
    source_code VARCHAR(80) NOT NULL,
    search_term VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    interval_hours INTEGER NOT NULL DEFAULT 24 CHECK (interval_hours BETWEEN 1 AND 720),
    last_run_at TIMESTAMPTZ,
    next_run_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_status VARCHAR(40),
    last_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(source_code,search_term)
);

CREATE INDEX IF NOT EXISTS idx_funding_search_profiles_due
ON funding_search_profiles(enabled,next_run_at);

INSERT INTO roles(code,name,description,privileged) VALUES
('SYSTEM_AUTOMATION','System Automation','Internal scheduled ingestion identity with no interactive business approval authority',true)
ON CONFLICT (code) DO NOTHING;

INSERT INTO users(email,display_name,organisation_unit_id,job_title,active)
SELECT 'grant.discovery@nhrc.system','Grant Discovery Automation',id,'Automated funding-source ingestion',true
FROM organisation_units WHERE code='NHRC'
ON CONFLICT (email) DO UPDATE SET active=true,display_name=EXCLUDED.display_name,job_title=EXCLUDED.job_title;

INSERT INTO user_roles(user_id,role_id,scope_type,scope_id)
SELECT u.id,r.id,'INSTITUTION',o.id
FROM users u CROSS JOIN roles r CROSS JOIN organisation_units o
WHERE u.email='grant.discovery@nhrc.system' AND r.code='SYSTEM_AUTOMATION' AND o.code='NHRC'
ON CONFLICT DO NOTHING;

INSERT INTO funding_search_profiles(name,source_code,search_term,enabled,interval_hours,next_run_at) VALUES
('Global health systems and services','GRANTS_GOV','health systems health services population health',true,24,now()),
('Digital health and data science','GRANTS_GOV','digital health data science artificial intelligence health',true,24,now()),
('Maternal child and reproductive health','GRANTS_GOV','maternal child reproductive health',true,24,now()),
('Infectious disease and surveillance','GRANTS_GOV','infectious disease surveillance epidemiology',true,24,now()),
('Implementation science and capacity strengthening','GRANTS_GOV','implementation science capacity strengthening health',true,24,now())
ON CONFLICT (source_code,search_term) DO NOTHING;

ALTER TABLE templates ADD COLUMN IF NOT EXISTS description TEXT;
ALTER TABLE templates ADD COLUMN IF NOT EXISTS category VARCHAR(100);
ALTER TABLE templates ADD COLUMN IF NOT EXISTS content_json JSONB NOT NULL DEFAULT '{}'::jsonb;

INSERT INTO form_definitions(code,name,entity_type,version_no,schema_json,active) VALUES
('OPPORTUNITY_REVIEW','Funding Opportunity Review','OPPORTUNITY',1,'{"sections":[{"title":"Source and scope","fields":["source","funder","scheme","deadline","value","url"]},{"title":"Eligibility","fields":["institution","applicant","country","partnership","financial","delivery"]},{"title":"NHRC assessment","fields":["theme_fit","capacity","deadline_feasibility","notes"]}]}',true),
('ELIGIBILITY_ASSESSMENT','Eligibility Assessment','APPLICATION',1,'{"sections":[{"title":"Institution","fields":["legal_status","country","institution_type"]},{"title":"Applicant","fields":["career_stage","employment","role_eligibility"]},{"title":"Finance","fields":["currency","cost_share","indirect_costs"]},{"title":"Decision","fields":["decision","conditions","rationale","evidence"]}]}',true),
('SOI_EOI_LOI','SOI / EOI / LOI Preparation','APPLICATION',1,'{"sections":[{"title":"Overview","fields":["title","funder","scheme","deadline"]},{"title":"Scientific summary","fields":["problem","aim","objectives","approach"]},{"title":"Delivery","fields":["team","partners","indicative_budget","timeline"]},{"title":"Declarations","fields":["conflict","accuracy","authorisation"]}]}',true),
('FULL_PROPOSAL','Full Proposal','APPLICATION',1,'{"sections":[{"title":"Scientific case","fields":["executive_summary","background","problem","objectives","methods","analysis"]},{"title":"Implementation","fields":["setting","participants","workplan","stakeholders","impact","sustainability"]},{"title":"Governance","fields":["ethics","data_management","risk","dissemination"]},{"title":"Delivery","fields":["team","partners","timeline","budget"]}]}',true),
('BUDGET','Proposal Budget','APPLICATION',1,'{"columns":["year","work_package","category","description","quantity","unit","unit_cost","currency","exchange_rate","funder_amount","nhrc_amount","partner_amount","justification"]}',true),
('INTERNAL_REVIEW','Internal Review','APPLICATION',1,'{"fields":["review_type","recommendation","required_changes","comments","evidence","no_conflict"]}',true),
('INSTITUTIONAL_APPROVAL','Institutional Approval','APPLICATION',1,'{"fields":["application_version","budget_version","reviews_complete","conditions","conflicts","decision","authority","comments"]}',true),
('EXTERNAL_SUBMISSION','External Submission Record','APPLICATION',1,'{"fields":["method","portal","submitted_by","submitted_at","funder_reference","approved_version","evidence","acknowledgement"]}',true),
('AWARD_SETUP','Award Setup Checklist','AWARD',1,'{"checks":["award_letter","contract_executed","budget_confirmed","cost_centre","team","partner_agreements","ethics","data_governance","procurement_plan","lab_requirements","reporting_schedule","deliverables","risk_register","kickoff"]}',true),
('CONTRACT_REVIEW','Contract Review','CONTRACT',1,'{"sections":[{"title":"Terms","fields":["counterparty","dates","value","governing_law"]},{"title":"Obligations","fields":["financial","reporting","data","ip","publication","termination"]},{"title":"Review","fields":["scientific","finance","governance","management"]}]}',true),
('FUND_RECEIPT','Funds Received','FINANCE',1,'{"fields":["award","payer","receipt_date","value_date","amount","currency","exchange_rate","bank_reference","payment_reference","tranche","evidence"]}',true),
('EXPENDITURE','Expenditure Record','FINANCE',1,'{"fields":["award","transaction_date","posting_date","category","work_package","description","supplier","invoice_reference","amount","currency","exchange_rate","cost_centre","procurement_reference","evidence"]}',true),
('BUDGET_TRANSFER','Budget Transfer','FINANCE',1,'{"fields":["award","from_category","to_category","amount","reason","effective_date","funder_approval_required","funder_approval","evidence"]}',true),
('PROCUREMENT_REQUISITION','Procurement Requisition','PROCUREMENT',1,'{"fields":["award","requester","description","category","quantity","estimated_amount","currency","budget_category","justification","required_date","specification"]}',true),
('PROCUREMENT_EVALUATION','Procurement Evaluation','PROCUREMENT',1,'{"fields":["requisition","supplier","criteria","scores","comments","conflict_declaration","recommendation"]}',true),
('GOODS_RECEIPT','Goods Receipt and Inspection','PROCUREMENT',1,'{"fields":["purchase_order","supplier","delivery_date","delivery_note","ordered","delivered","accepted","rejected","inspection","received_by","inspected_by","evidence"]}',true),
('LAB_EQUIPMENT','Laboratory Equipment','LABORATORY',1,'{"fields":["asset_number","type","manufacturer","model","serial_number","laboratory","award","installation_date","warranty_expiry","maintenance_interval","calibration_interval","status"]}',true),
('LAB_SERVICE','Maintenance / Calibration','LABORATORY',1,'{"fields":["equipment","event_type","scheduled_date","completed_date","provider","certificate","result","next_due","cost","award","notes"]}',true),
('DELIVERABLE','Deliverable / Milestone','AWARD',1,'{"fields":["number","title","description","category","owner","internal_due_date","funder_due_date","status","evidence"]}',true),
('REPORTING','Award Report','AWARD',1,'{"fields":["report_type","reporting_period","internal_deadline","funder_deadline","owner","status","document","submitted_at","acknowledgement","funder_feedback"]}',true),
('AMENDMENT','Award Amendment','AWARD',1,'{"fields":["amendment_type","current_terms","proposed_change","reason","budget_impact","timeline_impact","deliverable_impact","ethics_impact","funder_approval_required","evidence"]}',true),
('DUE_DILIGENCE','Due Diligence Assessment','GOVERNANCE',1,'{"areas":["legal_existence","registration","ownership","governance","financial_standing","banking","tax","sanctions","conflict","research_integrity","data_protection","safeguarding","anti_fraud","technical_capability","prior_performance","reputation"]}',true),
('CONFLICT_DECLARATION','Conflict of Interest Declaration','GOVERNANCE',1,'{"fields":["person","related_entity","context","category","description","declared_at","management_plan","decision","reviewer","effective_from","expires_on"]}',true),
('CLOSEOUT','Award Closeout','AWARD',1,'{"sections":[{"title":"Scientific","checks":["final_report","deliverables","outputs","data_archive"]},{"title":"Financial","checks":["final_expenditure","commitments","partner_advances","financial_report","unspent_balance","refunds","reconciliation"]},{"title":"Operational","checks":["procurement","assets","laboratory","contracts","partners"]},{"title":"Governance","checks":["ethics_closure","data_retention","ip","audit","funder_conditions"]}]}',true)
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,entity_type=EXCLUDED.entity_type,version_no=EXCLUDED.version_no,schema_json=EXCLUDED.schema_json,active=true;

INSERT INTO templates(code,name,template_type,version_no,description,category,content_json,active) VALUES
('TPL_SCIENTIFIC_CONCEPT','Scientific Concept Note','DOCUMENT',1,'Structured concept note for early-stage funding opportunities.','PRE_AWARD','{"sections":["Title","Problem","Rationale","Aim","Objectives","Methods","Expected results","Impact","Team","Indicative budget","Timeline"]}',true),
('TPL_SOI_EOI','SOI / EOI / LOI Template','DOCUMENT',1,'Short-form expression of interest template.','PRE_AWARD','{"sections":["Opportunity details","Institutional fit","Scientific summary","Team","Delivery capability","Indicative budget","Declarations"]}',true),
('TPL_FULL_PROPOSAL','Full Proposal Template','DOCUMENT',1,'Full grant proposal structure.','PRE_AWARD','{"sections":["Executive summary","Background","Problem statement","Objectives","Methods","Setting and participants","Analysis","Implementation","Impact","Capacity strengthening","Stakeholder engagement","Governance","Data management","Ethics","Risk","Dissemination","Sustainability","Timeline"]}',true),
('TPL_BUDGET_JUSTIFICATION','Budget Justification Template','DOCUMENT',1,'Narrative justification aligned to proposal budget lines.','FINANCE','{"sections":["Personnel","Travel","Equipment","Consumables","Laboratory","Data collection","Training","Consultancy","Subcontracts","Dissemination","Indirect costs","Other"]}',true),
('TPL_INTERNAL_REVIEW','Internal Review Template','REVIEW',1,'Scientific, Grants, Finance and Governance review record.','PRE_AWARD','{"fields":["Review type","Strengths","Concerns","Required changes","Recommendation","Conflict declaration"]}',true),
('TPL_APPROVAL_MEMO','Institutional Approval Memo','DECISION',1,'Controlled institutional submission authorisation record.','PRE_AWARD','{"sections":["Application summary","Versions reviewed","Outstanding conditions","Conflicts","Decision","Authority","Conditions"]}',true),
('TPL_AWARD_SETUP','Award Activation Checklist','CHECKLIST',1,'Mandatory post-award setup controls before activation.','AWARD','{"checks":["Award letter","Contract","Budget","Cost centre","Team","Partners","Ethics","Data governance","Procurement","Laboratory","Reporting","Deliverables","Risk register","Kickoff"]}',true),
('TPL_RISK_REGISTER','Award Risk Register','REGISTER',1,'Award-level risk and mitigation register.','AWARD','{"columns":["Category","Risk","Cause","Consequence","Likelihood","Impact","Owner","Mitigation","Target date","Review date","Status"]}',true),
('TPL_PROGRESS_REPORT','Progress Report Template','REPORT',1,'Scientific and operational progress report.','AWARD','{"sections":["Executive summary","Progress against objectives","Milestones","Deliverables","Results","Challenges","Risk changes","Finance summary","Next period"]}',true),
('TPL_FINANCIAL_REPORT','Financial Report Template','REPORT',1,'Award financial reporting structure.','FINANCE','{"sections":["Budget","Receipts","Expenditure","Commitments","Variance","Forecast","Partner advances","Reconciliation","Certification"]}',true),
('TPL_PROCUREMENT_EVALUATION','Procurement Evaluation Template','PROCUREMENT',1,'Controlled supplier evaluation and recommendation record.','PROCUREMENT','{"sections":["Requirement","Suppliers","Criteria","Independent scores","Conflicts","Recommendation","Approval"]}',true),
('TPL_GOODS_RECEIPT','Goods Receipt Template','PROCUREMENT',1,'Delivery inspection and acceptance record.','PROCUREMENT','{"sections":["Purchase order","Delivery","Quantities","Inspection","Acceptance","Rejection","Evidence"]}',true),
('TPL_LAB_MAINTENANCE','Laboratory Maintenance / Calibration Template','LABORATORY',1,'Equipment service, calibration and certification record.','LABORATORY','{"sections":["Equipment","Event","Provider","Result","Certificate","Cost","Next due"]}',true),
('TPL_DUE_DILIGENCE','Due Diligence Review Template','GOVERNANCE',1,'Institutional counterparty due diligence template.','GOVERNANCE','{"sections":["Legal","Ownership","Governance","Financial","Banking","Tax","Sanctions","Conflict","Integrity","Data protection","Safeguarding","Anti-fraud","Capability","Performance","Reputation","Decision"]}',true),
('TPL_CLOSEOUT','Award Closeout Checklist','CHECKLIST',1,'Cross-functional award closeout checklist.','AWARD','{"sections":["Scientific","Financial","Procurement","Assets","Laboratory","Contracts","Partners","Governance","Management confirmation"]}',true)
ON CONFLICT (code) DO UPDATE SET name=EXCLUDED.name,template_type=EXCLUDED.template_type,version_no=EXCLUDED.version_no,description=EXCLUDED.description,category=EXCLUDED.category,content_json=EXCLUDED.content_json,active=true;
