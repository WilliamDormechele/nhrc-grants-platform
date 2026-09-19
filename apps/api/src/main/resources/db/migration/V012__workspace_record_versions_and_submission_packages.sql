-- Only known application tables are extended. No existing migration or business record is replaced.
DO $$
DECLARE relation_name TEXT;
BEGIN
  FOREACH relation_name IN ARRAY ARRAY[
    'institution_profiles','researcher_profiles','partners','funders','opportunities','applications',
    'contracts','award_partners','amendments','award_deliverables','award_risks','fund_receipts','expenditures',
    'commitments','partner_advances','financial_reports','financial_forecasts','reconciliations',
    'procurement_plans','procurement_requisitions','suppliers','purchase_orders','procurement_contracts',
    'assets','laboratory_items','laboratory_maintenance','compliance_records','reports','research_outputs',
    'impact_records','closeout_items','support_tickets','organisation_units','research_themes','awards',
    'due_diligence_reviews','declarations'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS record_version BIGINT NOT NULL DEFAULT 0', relation_name);
  END LOOP;
END $$;

ALTER TABLE applications ADD COLUMN parent_application_id UUID REFERENCES applications(id);
ALTER TABLE applications ADD COLUMN budget_scenario VARCHAR(80) NOT NULL DEFAULT 'Working budget';
ALTER TABLE application_reviews ADD COLUMN content_revision BIGINT;
ALTER TABLE application_reviews ADD COLUMN no_conflict_declared BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE grant_record_owners (
    resource_key VARCHAR(80) NOT NULL,
    record_id UUID NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(resource_key,record_id)
);
CREATE TABLE grant_record_action_receipts (
    resource_key VARCHAR(80) NOT NULL,
    record_id UUID NOT NULL,
    request_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    request_hash VARCHAR(64) NOT NULL,
    result JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(resource_key,record_id,request_id)
);
CREATE TABLE grant_application_sections (
    application_id UUID NOT NULL REFERENCES applications(id),
    section_key VARCHAR(100) NOT NULL,
    revision_no BIGINT NOT NULL,
    content TEXT NOT NULL,
    saved_by UUID NOT NULL REFERENCES users(id),
    saved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(application_id,section_key,revision_no)
);
CREATE TABLE grant_document_links (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id),
    document_type VARCHAR(100) NOT NULL,
    title VARCHAR(255) NOT NULL,
    evidence_url TEXT NOT NULL,
    version_no INTEGER NOT NULL,
    content_revision BIGINT NOT NULL,
    added_by UUID NOT NULL REFERENCES users(id),
    added_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(application_id,document_type,version_no)
);
CREATE TABLE grant_submission_packages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id UUID NOT NULL REFERENCES applications(id),
    content_revision BIGINT NOT NULL,
    package_snapshot JSONB NOT NULL,
    authorised_by UUID NOT NULL REFERENCES users(id),
    authorised_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(application_id,content_revision)
);
CREATE INDEX idx_grant_document_links_application ON grant_document_links(application_id,added_at DESC);
CREATE INDEX idx_grant_record_owners_actor ON grant_record_owners(created_by,resource_key);

-- This is an incomplete profile, not a statement that legal or eligibility checks are complete.
INSERT INTO institution_profiles(organisation_unit_id,legal_name,short_name)
SELECT id,name,'NHRC' FROM organisation_units WHERE code='NHRC'
ON CONFLICT (organisation_unit_id) DO NOTHING;
