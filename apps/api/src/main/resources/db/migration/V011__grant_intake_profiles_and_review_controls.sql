-- Additive migration. Existing opportunities, applications, awards and finance data are retained.
ALTER TABLE opportunities ADD COLUMN call_type VARCHAR(40) NOT NULL DEFAULT 'GRANT_CALL';
ALTER TABLE opportunities ADD COLUMN eligibility_criteria TEXT;
ALTER TABLE opportunities ADD COLUMN keywords TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE opportunities ADD COLUMN deadline_date DATE;
ALTER TABLE opportunities ADD COLUMN source_last_seen_at TIMESTAMPTZ;
ALTER TABLE opportunities ADD COLUMN record_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE researcher_profiles ADD COLUMN methods TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE researcher_profiles ADD COLUMN selected_track_record TEXT;
ALTER TABLE researcher_profiles ADD COLUMN availability_note TEXT;
ALTER TABLE researcher_profiles ADD COLUMN record_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE partners ADD COLUMN website TEXT;
ALTER TABLE partners ADD COLUMN capabilities TEXT;
ALTER TABLE partners ADD COLUMN record_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE funders ADD COLUMN funding_interests TEXT;
ALTER TABLE funders ADD COLUMN contact_email VARCHAR(320);
ALTER TABLE funders ADD COLUMN record_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE applications ADD COLUMN application_type VARCHAR(40) NOT NULL DEFAULT 'FULL_PROPOSAL';
ALTER TABLE applications ADD COLUMN narrative JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE applications ADD COLUMN record_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE applications ADD COLUMN content_revision BIGINT NOT NULL DEFAULT 1;
ALTER TABLE applications ADD COLUMN submission_reference VARCHAR(255);
ALTER TABLE applications ADD COLUMN submission_evidence_url TEXT;
ALTER TABLE applications ADD COLUMN outcome_evidence_url TEXT;
ALTER TABLE application_budget_lines ADD COLUMN scenario VARCHAR(80) NOT NULL DEFAULT 'Working budget';

CREATE TABLE institution_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organisation_unit_id UUID UNIQUE REFERENCES organisation_units(id),
    legal_name VARCHAR(255) NOT NULL,
    short_name VARCHAR(100),
    country_code VARCHAR(3),
    organisation_type VARCHAR(100),
    registration_reference VARCHAR(255),
    website TEXT,
    contact_name VARCHAR(255),
    contact_email VARCHAR(320),
    research_themes TEXT[] NOT NULL DEFAULT '{}',
    methods TEXT[] NOT NULL DEFAULT '{}',
    facilities TEXT,
    research_platforms TEXT,
    strategic_priorities TEXT,
    eligibility_notes TEXT,
    record_version BIGINT NOT NULL DEFAULT 0,
    updated_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE grant_source_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_code VARCHAR(80) NOT NULL,
    search_term VARCHAR(200) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'RUNNING',
    imported_count INTEGER NOT NULL DEFAULT 0,
    refreshed_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    available_count INTEGER,
    truncated BOOLEAN NOT NULL DEFAULT FALSE,
    started_by UUID REFERENCES users(id),
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    failure_message TEXT
);
CREATE INDEX idx_grant_source_runs_latest ON grant_source_runs(source_code, started_at DESC);

CREATE TABLE grant_source_records (
    source_code VARCHAR(80) NOT NULL,
    external_id VARCHAR(255) NOT NULL,
    opportunity_id UUID NOT NULL UNIQUE REFERENCES opportunities(id),
    source_snapshot JSONB NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(source_code,external_id)
);

CREATE TABLE application_gate_decisions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id),
    content_revision BIGINT NOT NULL,
    gate VARCHAR(80) NOT NULL,
    decision VARCHAR(40) NOT NULL,
    note TEXT NOT NULL,
    evidence_url TEXT,
    decided_by UUID NOT NULL REFERENCES users(id),
    decided_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_application_gate_current ON application_gate_decisions(application_id,content_revision,gate,decided_at DESC);

CREATE TABLE application_action_receipts (
    application_id UUID NOT NULL REFERENCES applications(id),
    request_id UUID NOT NULL,
    action VARCHAR(80) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    request_hash VARCHAR(64) NOT NULL,
    result JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(application_id,request_id)
);

CREATE TABLE grant_workspace_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID NOT NULL,
    action VARCHAR(80) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    previous_value JSONB,
    new_value JSONB,
    note TEXT,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_grant_workspace_events_entity ON grant_workspace_events(entity_type,entity_id,occurred_at DESC);
