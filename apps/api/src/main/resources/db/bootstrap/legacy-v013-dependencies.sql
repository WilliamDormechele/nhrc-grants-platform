-- Additive recovery of the verified deployed schema prerequisites for immutable V013.
-- Applied only immediately before V013 on fresh installations. No grant rows or decisions are invented.
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS deadline_date date;
ALTER TABLE application_reviews ADD COLUMN IF NOT EXISTS content_revision bigint;
CREATE TABLE IF NOT EXISTS grant_source_runs (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),source_code varchar(80) NOT NULL,search_term varchar(200) NOT NULL,
 status varchar(40) NOT NULL DEFAULT 'RUNNING',imported_count int NOT NULL DEFAULT 0,
 refreshed_count int NOT NULL DEFAULT 0,skipped_count int NOT NULL DEFAULT 0,available_count int,
 truncated boolean NOT NULL DEFAULT false,started_by uuid REFERENCES users(id),started_at timestamptz NOT NULL DEFAULT now(),
 completed_at timestamptz,failure_message text);
CREATE TABLE IF NOT EXISTS grant_document_links (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),application_id uuid NOT NULL REFERENCES applications(id),
 document_type varchar(100) NOT NULL,title varchar(255) NOT NULL,evidence_url text NOT NULL,version_no int NOT NULL,
 content_revision bigint NOT NULL,added_by uuid NOT NULL REFERENCES users(id),added_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(application_id,document_type,version_no));
-- Verified deployed legacy columns absent from the recovered baseline scripts.
ALTER TABLE laboratory_items ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE compliance_records ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE reports ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE research_outputs ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE impact_records ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE closeout_items ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE declarations ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE support_tickets ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE templates ADD COLUMN IF NOT EXISTS content_json jsonb DEFAULT '{}'::jsonb NOT NULL;
ALTER TABLE financial_reports ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE financial_forecasts ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE reconciliations ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE procurement_contracts ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE laboratory_maintenance ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS narrative jsonb DEFAULT '{}'::jsonb NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS content_revision bigint DEFAULT 1 NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS parent_application_id uuid;
ALTER TABLE research_themes ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE organisation_units ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE researcher_profiles ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS source_last_seen_at timestamp with time zone;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE funders ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE award_deliverables ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE awards ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE application_reviews ADD COLUMN IF NOT EXISTS no_conflict_declared boolean DEFAULT false NOT NULL;
ALTER TABLE fund_receipts ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE contracts ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE award_partners ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE amendments ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE award_risks ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE expenditures ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE commitments ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE partner_advances ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE procurement_plans ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE procurement_requisitions ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE purchase_orders ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE assets ADD COLUMN IF NOT EXISTS record_version bigint DEFAULT 0 NOT NULL;
ALTER TABLE application_budget_lines ADD COLUMN IF NOT EXISTS scenario character varying DEFAULT 'Working budget'::character varying NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS application_type character varying DEFAULT 'FULL_PROPOSAL'::character varying NOT NULL;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS submission_reference character varying;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS submission_evidence_url text;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS outcome_evidence_url text;
ALTER TABLE applications ADD COLUMN IF NOT EXISTS budget_scenario character varying DEFAULT 'Working budget'::character varying NOT NULL;
ALTER TABLE researcher_profiles ADD COLUMN IF NOT EXISTS methods text[] DEFAULT '{}'::text[] NOT NULL;
ALTER TABLE researcher_profiles ADD COLUMN IF NOT EXISTS selected_track_record text;
ALTER TABLE researcher_profiles ADD COLUMN IF NOT EXISTS availability_note text;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS call_type character varying DEFAULT 'GRANT_CALL'::character varying NOT NULL;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS eligibility_criteria text;
ALTER TABLE opportunities ADD COLUMN IF NOT EXISTS keywords text[] DEFAULT '{}'::text[] NOT NULL;
ALTER TABLE funders ADD COLUMN IF NOT EXISTS funding_interests text;
ALTER TABLE funders ADD COLUMN IF NOT EXISTS contact_email character varying;
ALTER TABLE templates ADD COLUMN IF NOT EXISTS description text;
ALTER TABLE templates ADD COLUMN IF NOT EXISTS category character varying;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS website text;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS capabilities text;

