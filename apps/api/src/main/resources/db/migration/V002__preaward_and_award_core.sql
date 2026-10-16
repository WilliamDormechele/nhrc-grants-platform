CREATE TABLE funders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL UNIQUE,
    country_code VARCHAR(3),
    website TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE researcher_profiles (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    ORCID VARCHAR(32),
    expertise TEXT[],
    career_stage VARCHAR(100),
    summary TEXT,
    cv_storage_key TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE opportunities (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_type VARCHAR(80) NOT NULL,
    source_reference VARCHAR(255),
    title TEXT NOT NULL,
    funder_id UUID REFERENCES funders(id),
    url TEXT,
    summary TEXT,
    currency VARCHAR(3),
    amount_min NUMERIC(19,2),
    amount_max NUMERIC(19,2),
    opens_at TIMESTAMPTZ,
    deadline_at TIMESTAMPTZ,
    status VARCHAR(80) NOT NULL DEFAULT 'DISCOVERED',
    eligibility_status VARCHAR(80) NOT NULL DEFAULT 'NOT_REVIEWED',
    institutional_fit_score NUMERIC(5,2),
    fit_rationale TEXT,
    discovered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE opportunity_researcher_matches (
    opportunity_id UUID NOT NULL REFERENCES opportunities(id) ON DELETE CASCADE,
    researcher_profile_id UUID NOT NULL REFERENCES researcher_profiles(id) ON DELETE CASCADE,
    fit_score NUMERIC(5,2),
    rationale TEXT,
    human_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    confirmed_by UUID REFERENCES users(id),
    confirmed_at TIMESTAMPTZ,
    PRIMARY KEY (opportunity_id, researcher_profile_id)
);

CREATE TABLE applications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference VARCHAR(80) NOT NULL UNIQUE,
    opportunity_id UUID REFERENCES opportunities(id),
    title TEXT NOT NULL,
    lead_researcher_id UUID REFERENCES users(id),
    owner_user_id UUID REFERENCES users(id),
    stage VARCHAR(100) NOT NULL DEFAULT 'DISCOVERED',
    progress_percent INTEGER NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    current_challenge TEXT,
    deadline_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ,
    funder_outcome VARCHAR(80),
    funder_outcome_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE application_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    researcher_id UUID NOT NULL REFERENCES users(id),
    assigned_by UUID REFERENCES users(id),
    assigned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    response VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    responded_at TIMESTAMPTZ,
    response_note TEXT
);

CREATE TABLE approvals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID NOT NULL,
    approval_type VARCHAR(120) NOT NULL,
    sequence_no INTEGER NOT NULL DEFAULT 1,
    required_role_code VARCHAR(100),
    assigned_user_id UUID REFERENCES users(id),
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    decided_by UUID REFERENCES users(id),
    decided_at TIMESTAMPTZ,
    decision_note TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_approvals_entity ON approvals(entity_type, entity_id, status);

CREATE TABLE awards (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reference VARCHAR(80) NOT NULL UNIQUE,
    application_id UUID UNIQUE REFERENCES applications(id),
    title TEXT NOT NULL,
    funder_id UUID REFERENCES funders(id),
    principal_investigator_id UUID REFERENCES users(id),
    start_date DATE,
    end_date DATE,
    currency VARCHAR(3) NOT NULL,
    total_award NUMERIC(19,2) NOT NULL DEFAULT 0,
    nhrc_allocation NUMERIC(19,2) NOT NULL DEFAULT 0,
    partner_allocation NUMERIC(19,2) NOT NULL DEFAULT 0,
    status VARCHAR(80) NOT NULL DEFAULT 'SETUP',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE award_financial_positions (
    award_id UUID PRIMARY KEY REFERENCES awards(id) ON DELETE CASCADE,
    approved_budget NUMERIC(19,2) NOT NULL DEFAULT 0,
    cash_received NUMERIC(19,2) NOT NULL DEFAULT 0,
    expenditure NUMERIC(19,2) NOT NULL DEFAULT 0,
    commitments NUMERIC(19,2) NOT NULL DEFAULT 0,
    reconciled_at TIMESTAMPTZ,
    reconciled_by UUID REFERENCES users(id),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
