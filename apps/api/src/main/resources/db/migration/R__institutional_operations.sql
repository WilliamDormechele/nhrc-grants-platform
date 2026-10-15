CREATE TABLE IF NOT EXISTS uat_credentials (
 user_id UUID PRIMARY KEY REFERENCES users(id), password_hash VARCHAR(100) NOT NULL,
 failed_attempts INTEGER NOT NULL DEFAULT 0, locked_until TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS operational_versions (
 dataset VARCHAR(100) NOT NULL, record_id UUID NOT NULL, version BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(dataset,record_id)
);
CREATE TABLE IF NOT EXISTS operational_history (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), dataset VARCHAR(100) NOT NULL, record_id UUID NOT NULL,
 actor_id UUID REFERENCES users(id), action VARCHAR(100) NOT NULL, before_data JSONB, after_data JSONB,
 note TEXT, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_operational_history_record ON operational_history(dataset,record_id,occurred_at);
CREATE TABLE IF NOT EXISTS privacy_assessments (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), award_id UUID REFERENCES awards(id), title VARCHAR(255) NOT NULL,
 data_categories TEXT NOT NULL, lawful_basis TEXT, retention_period TEXT, storage_location TEXT,
 access_controls TEXT, transfer_arrangements TEXT, risks TEXT, mitigation TEXT, review_date DATE,
 owner_user_id UUID REFERENCES users(id), status VARCHAR(40) NOT NULL DEFAULT 'DRAFT', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS business_continuity_plans (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), service_name VARCHAR(180) NOT NULL,
 owner_user_id UUID REFERENCES users(id), critical_dependencies TEXT, recovery_time_minutes INTEGER,
 recovery_point_minutes INTEGER, recovery_procedure TEXT NOT NULL, alternate_contact TEXT,
 last_test_date DATE, next_test_date DATE, test_findings TEXT, status VARCHAR(40) NOT NULL DEFAULT 'DRAFT', created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS managed_documents (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), file_name TEXT NOT NULL, media_type VARCHAR(120) NOT NULL,
 size_bytes BIGINT NOT NULL, sha256 VARCHAR(64) NOT NULL, uploaded_by UUID NOT NULL REFERENCES users(id),
 content BYTEA NOT NULL, uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS proposal_narratives (
 application_id UUID NOT NULL REFERENCES applications(id), section VARCHAR(80) NOT NULL,
 content TEXT NOT NULL, revision BIGINT NOT NULL DEFAULT 1, saved_by UUID NOT NULL REFERENCES users(id),
 saved_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(application_id,section)
);
CREATE TABLE IF NOT EXISTS operational_request_receipts (
 request_id UUID NOT NULL, dataset VARCHAR(100) NOT NULL, actor_id UUID NOT NULL REFERENCES users(id),
 request_hash VARCHAR(64) NOT NULL, response JSONB NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY(request_id,dataset,actor_id)
);
CREATE TABLE IF NOT EXISTS institutional_form_responses (
 application_id UUID NOT NULL REFERENCES applications(id), form_id UUID NOT NULL REFERENCES form_definitions(id),
 values_json JSONB NOT NULL, revision BIGINT NOT NULL DEFAULT 1, saved_by UUID NOT NULL REFERENCES users(id),
 saved_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY(application_id,form_id)
);
