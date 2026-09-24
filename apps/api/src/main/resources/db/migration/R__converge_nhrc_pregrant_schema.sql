-- Corrective convergence migration after legacy V011 checksum drift.
-- V011 is treated as immutable from this point forward. This migration is intentionally idempotent.

CREATE TABLE IF NOT EXISTS pregrant_expressions_of_interest (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    opportunity_id UUID NOT NULL REFERENCES opportunities(id) ON DELETE CASCADE,
    researcher_id UUID NOT NULL REFERENCES users(id),
    proposed_role VARCHAR(80) NOT NULL DEFAULT 'PI',
    team_summary TEXT,
    note TEXT,
    status VARCHAR(40) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_by UUID REFERENCES users(id),
    decided_at TIMESTAMPTZ,
    decision_note TEXT,
    UNIQUE(opportunity_id, researcher_id)
);

CREATE TABLE IF NOT EXISTS application_team_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    user_id UUID REFERENCES users(id),
    external_name VARCHAR(180),
    external_email VARCHAR(255),
    organisation VARCHAR(255),
    role_code VARCHAR(80) NOT NULL,
    responsibility TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS application_requirements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    requirement_type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED',
    owner_user_id UUID REFERENCES users(id),
    due_at TIMESTAMPTZ,
    evidence_storage_key TEXT,
    note TEXT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS funder_portal_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    portal_name VARCHAR(180) NOT NULL,
    portal_url TEXT,
    portal_application_reference VARCHAR(180),
    owner_user_id UUID REFERENCES users(id),
    registration_status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED',
    portal_deadline_at TIMESTAMPTZ,
    requirements_note TEXT,
    last_checked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(application_id,portal_name)
);

CREATE TABLE IF NOT EXISTS application_quality_checks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    check_type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    checked_by UUID REFERENCES users(id),
    checked_at TIMESTAMPTZ,
    note TEXT,
    UNIQUE(application_id,check_type)
);

CREATE TABLE IF NOT EXISTS funder_communications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    communication_type VARCHAR(80) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    communication_at TIMESTAMPTZ NOT NULL,
    contact_name VARCHAR(180),
    contact_email VARCHAR(255),
    summary TEXT,
    evidence_storage_key TEXT,
    recorded_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS award_handovers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL UNIQUE REFERENCES applications(id) ON DELETE CASCADE,
    award_id UUID REFERENCES awards(id) ON DELETE SET NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PREPARING',
    proposal_complete BOOLEAN NOT NULL DEFAULT FALSE,
    budget_complete BOOLEAN NOT NULL DEFAULT FALSE,
    award_documents_complete BOOLEAN NOT NULL DEFAULT FALSE,
    approvals_complete BOOLEAN NOT NULL DEFAULT FALSE,
    correspondence_complete BOOLEAN NOT NULL DEFAULT FALSE,
    prepared_by UUID REFERENCES users(id),
    accepted_by UUID REFERENCES users(id),
    prepared_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    note TEXT
);

ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS thematic_area VARCHAR(180);
ALTER TABLE approvals ADD COLUMN IF NOT EXISTS requested_by UUID REFERENCES users(id);
ALTER TABLE applications ADD COLUMN IF NOT EXISTS portal_status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED';
ALTER TABLE applications ADD COLUMN IF NOT EXISTS submission_proof_storage_key TEXT;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS submission_acknowledgement VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_approvals_requested_by ON approvals(requested_by,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_application_requirements_app_status ON application_requirements(application_id,status,due_at);
CREATE INDEX IF NOT EXISTS idx_funder_communications_app_date ON funder_communications(application_id,communication_at DESC);

INSERT INTO application_requirements(application_id,requirement_type,title,required,status)
SELECT a.id,x.requirement_type,x.title,true,'NOT_STARTED'
FROM applications a
CROSS JOIN (VALUES
 ('TECHNICAL','Technical proposal / concept note'),('WORKPLAN','Work plan'),
 ('BUDGET','Budget and justification'),('CV','Required CVs / biosketches'),
 ('LETTER','Letters of support / commitment'),('INSTITUTIONAL','Required institutional documents'),
 ('APPROVAL','Required internal approvals'),('CERTIFICATION','Required certifications'),
 ('PORTAL','Funder portal requirements'),('SUBMISSION','Submission readiness and proof')
) AS x(requirement_type,title)
WHERE NOT EXISTS (SELECT 1 FROM application_requirements r WHERE r.application_id=a.id AND r.requirement_type=x.requirement_type);

INSERT INTO application_quality_checks(application_id,check_type,title)
SELECT a.id,x.check_type,x.title
FROM applications a
CROSS JOIN (VALUES
 ('COMPLETENESS','All required sections and attachments complete'),
 ('CONSISTENCY','Narrative, work plan and budget are consistent'),
 ('FUNDER_COMPLIANCE','Funder instructions and eligibility requirements satisfied'),
 ('ATTACHMENTS','Required attachments are present and current'),
 ('APPROVALS','Required institutional approvals completed'),
 ('SUBMISSION_READY','Authorised final version ready for submission')
) AS x(check_type,title)
WHERE NOT EXISTS (SELECT 1 FROM application_quality_checks q WHERE q.application_id=a.id AND q.check_type=x.check_type);


-- External opportunity discovery engine.
CREATE TABLE IF NOT EXISTS opportunity_sources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(180) NOT NULL,
    adapter_type VARCHAR(40) NOT NULL,
    endpoint_url TEXT NOT NULL,
    detail_endpoint_url TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    schedule_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    query_terms TEXT,
    fetch_limit INTEGER NOT NULL DEFAULT 50 CHECK (fetch_limit BETWEEN 1 AND 500),
    trust_level VARCHAR(30) NOT NULL DEFAULT 'OFFICIAL',
    last_run_at TIMESTAMPTZ,
    last_success_at TIMESTAMPTZ,
    last_failure_at TIMESTAMPTZ,
    last_failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS opportunity_discovery_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id UUID REFERENCES opportunity_sources(id) ON DELETE SET NULL,
    trigger_type VARCHAR(30) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    status VARCHAR(30) NOT NULL DEFAULT 'RUNNING',
    fetched_count INTEGER NOT NULL DEFAULT 0,
    normalized_count INTEGER NOT NULL DEFAULT 0,
    created_count INTEGER NOT NULL DEFAULT 0,
    updated_count INTEGER NOT NULL DEFAULT 0,
    duplicate_count INTEGER NOT NULL DEFAULT 0,
    rejected_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT
);
CREATE INDEX IF NOT EXISTS idx_opportunity_discovery_runs_started ON opportunity_discovery_runs(started_at DESC);

CREATE TABLE IF NOT EXISTS opportunity_source_evidence (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID REFERENCES opportunity_discovery_runs(id) ON DELETE SET NULL,
    source_id UUID NOT NULL REFERENCES opportunity_sources(id) ON DELETE CASCADE,
    opportunity_id UUID REFERENCES opportunities(id) ON DELETE SET NULL,
    external_id VARCHAR(255),
    title TEXT,
    canonical_url TEXT,
    source_status VARCHAR(80),
    validation_status VARCHAR(80) NOT NULL,
    open_at TIMESTAMPTZ,
    close_at TIMESTAMPTZ,
    payload_hash VARCHAR(64),
    raw_payload TEXT,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_opportunity_source_evidence_external ON opportunity_source_evidence(source_id,external_id,fetched_at DESC);
CREATE INDEX IF NOT EXISTS idx_opportunity_source_evidence_opportunity ON opportunity_source_evidence(opportunity_id,fetched_at DESC);

CREATE TABLE IF NOT EXISTS opportunity_discovery_keys (
    fingerprint VARCHAR(64) PRIMARY KEY,
    opportunity_id UUID NOT NULL REFERENCES opportunities(id) ON DELETE CASCADE,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS discovery_review_status VARCHAR(40) NOT NULL DEFAULT 'NOT_APPLICABLE';
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS discovery_source_id UUID REFERENCES opportunity_sources(id);
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS discovery_external_id VARCHAR(255);
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS discovery_fingerprint VARCHAR(64);
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS last_seen_external_at TIMESTAMPTZ;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS discovery_reviewed_by UUID REFERENCES users(id);
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS discovery_reviewed_at TIMESTAMPTZ;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS discovery_review_note TEXT;
CREATE INDEX IF NOT EXISTS idx_opportunities_discovery_review ON opportunities(discovery_review_status,deadline_at);
CREATE UNIQUE INDEX IF NOT EXISTS ux_opportunity_source_external ON opportunities(discovery_source_id,discovery_external_id) WHERE discovery_source_id IS NOT NULL AND discovery_external_id IS NOT NULL;

INSERT INTO opportunity_sources(code,name,adapter_type,endpoint_url,detail_endpoint_url,enabled,schedule_enabled,query_terms,fetch_limit,trust_level)
VALUES
('GRANTS_GOV','Grants.gov','GRANTS_GOV','https://api.grants.gov/v1/api/search2','https://api.grants.gov/v1/api/fetchOpportunity',true,true,
 'health|public health|global health|population health|health systems|epidemiology|data science|digital health|maternal health|child health|infectious disease|climate health',50,'OFFICIAL'),
('UKRI_FUNDING_FINDER','UKRI Funding Finder','RSS','https://www.ukri.org/opportunity/feed/',NULL,true,true,
 'health|medical|public health|population|data|digital|implementation|epidemiology|global health|climate',100,'OFFICIAL')
ON CONFLICT (code) DO UPDATE SET
 name=excluded.name,
 adapter_type=excluded.adapter_type,
 endpoint_url=excluded.endpoint_url,
 detail_endpoint_url=excluded.detail_endpoint_url,
 trust_level=excluded.trust_level,
 updated_at=now();

INSERT INTO integration_registry(code,name,integration_type,status,data_direction,created_at)
VALUES
('GRANTS_GOV','Grants.gov opportunity discovery','PUBLIC_API','CONFIGURED','INBOUND',now()),
('UKRI_FUNDING_FINDER','UKRI Funding Finder RSS','RSS','CONFIGURED','INBOUND',now())
ON CONFLICT (code) DO UPDATE SET name=excluded.name,integration_type=excluded.integration_type,data_direction=excluded.data_direction;

UPDATE feature_flags SET state='ON',description='Scheduled ingestion from approved official funding sources with normalization, deduplication, provenance and human review' WHERE code='OPPORTUNITY_DISCOVERY';
