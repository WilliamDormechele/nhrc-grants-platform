CREATE TABLE research_themes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(60) NOT NULL UNIQUE,
    name VARCHAR(180) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE opportunity_themes (
    opportunity_id UUID NOT NULL REFERENCES opportunities(id) ON DELETE CASCADE,
    theme_id UUID NOT NULL REFERENCES research_themes(id),
    PRIMARY KEY (opportunity_id, theme_id)
);

CREATE TABLE eligibility_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    opportunity_id UUID NOT NULL REFERENCES opportunities(id) ON DELETE CASCADE,
    decision VARCHAR(40) NOT NULL CHECK (decision IN ('ELIGIBLE','INELIGIBLE','CONDITIONAL')),
    rationale TEXT NOT NULL,
    conditions TEXT,
    reviewed_by UUID REFERENCES users(id),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE application_stage_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    from_stage VARCHAR(100),
    to_stage VARCHAR(100) NOT NULL,
    note TEXT,
    changed_by UUID REFERENCES users(id),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_application_stage_history_app ON application_stage_history(application_id, changed_at DESC);

CREATE TABLE application_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    document_type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    storage_key TEXT NOT NULL,
    version_no INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    uploaded_by UUID REFERENCES users(id),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(application_id, document_type, version_no)
);

CREATE TABLE application_budget_lines (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    category VARCHAR(120) NOT NULL,
    description TEXT,
    year_no INTEGER NOT NULL DEFAULT 1 CHECK (year_no > 0),
    quantity NUMERIC(19,4) NOT NULL DEFAULT 1,
    unit_cost NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    funder_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    nhrc_contribution NUMERIC(19,2) NOT NULL DEFAULT 0,
    partner_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE application_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    review_type VARCHAR(100) NOT NULL,
    reviewer_id UUID REFERENCES users(id),
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    comments TEXT,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE award_deliverables (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    deliverable_type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    due_date DATE NOT NULL,
    owner_user_id UUID REFERENCES users(id),
    status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED',
    submitted_at TIMESTAMPTZ,
    accepted_at TIMESTAMPTZ,
    evidence_storage_key TEXT
);
CREATE INDEX idx_award_deliverables_due ON award_deliverables(status, due_date);

CREATE TABLE award_risks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    likelihood VARCHAR(30) NOT NULL,
    impact VARCHAR(30) NOT NULL,
    rating VARCHAR(30) NOT NULL,
    owner_user_id UUID REFERENCES users(id),
    mitigation TEXT,
    status VARCHAR(40) NOT NULL DEFAULT 'OPEN',
    review_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type VARCHAR(80) NOT NULL,
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,
    entity_type VARCHAR(80),
    entity_id UUID,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user ON notifications(user_id, read_at, created_at DESC);

INSERT INTO research_themes(code, name) VALUES
('HDSS','Health and Demographic Surveillance'),
('CLINICAL','Clinical Sciences'),
('PUBLIC_HEALTH','Public Health'),
('DATA_SCIENCE','Data Science and Digital Health'),
('LAB','Laboratory and Biomedical Sciences'),
('IMPLEMENTATION','Implementation Science')
ON CONFLICT (code) DO NOTHING;
