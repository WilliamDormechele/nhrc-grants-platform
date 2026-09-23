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
