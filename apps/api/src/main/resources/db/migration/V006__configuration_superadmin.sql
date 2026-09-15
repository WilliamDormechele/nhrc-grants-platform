CREATE TABLE workflow_definitions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(180) NOT NULL,
 entity_type VARCHAR(80) NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE workflow_steps (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), workflow_id UUID NOT NULL REFERENCES workflow_definitions(id) ON DELETE CASCADE,
 step_order INTEGER NOT NULL, code VARCHAR(100) NOT NULL, name VARCHAR(180) NOT NULL, required_role_code VARCHAR(100),
 approval_required BOOLEAN NOT NULL DEFAULT FALSE, UNIQUE(workflow_id,step_order), UNIQUE(workflow_id,code));
CREATE TABLE approval_rules (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(180) NOT NULL,
 entity_type VARCHAR(80) NOT NULL, condition_json JSONB NOT NULL DEFAULT '{}'::jsonb, required_role_code VARCHAR(100) NOT NULL,
 sequence_no INTEGER NOT NULL DEFAULT 1, active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE form_definitions (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(180) NOT NULL,
 entity_type VARCHAR(80) NOT NULL, version_no INTEGER NOT NULL DEFAULT 1, schema_json JSONB NOT NULL DEFAULT '{}'::jsonb,
 active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE templates (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), code VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(180) NOT NULL,
 template_type VARCHAR(80) NOT NULL, storage_key TEXT, version_no INTEGER NOT NULL DEFAULT 1, active BOOLEAN NOT NULL DEFAULT TRUE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE calendar_rules (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), rule_date DATE NOT NULL UNIQUE, name VARCHAR(180) NOT NULL,
 working_day BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE data_import_runs (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), import_type VARCHAR(100) NOT NULL, file_name VARCHAR(255) NOT NULL,
 status VARCHAR(40) NOT NULL DEFAULT 'UPLOADED', total_rows INTEGER, valid_rows INTEGER, error_rows INTEGER,
 initiated_by UUID REFERENCES users(id), started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ, error_summary TEXT);
CREATE TABLE data_export_runs (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), export_type VARCHAR(100) NOT NULL, filters JSONB,
 status VARCHAR(40) NOT NULL DEFAULT 'REQUESTED', requested_by UUID REFERENCES users(id), requested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 completed_at TIMESTAMPTZ, storage_key TEXT);
CREATE TABLE system_announcements (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), title VARCHAR(255) NOT NULL, message TEXT NOT NULL, severity VARCHAR(30) NOT NULL DEFAULT 'INFO',
 audience_role_code VARCHAR(100), starts_at TIMESTAMPTZ NOT NULL DEFAULT now(), expires_at TIMESTAMPTZ, active BOOLEAN NOT NULL DEFAULT TRUE,
 created_by UUID REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE feature_flags (
 code VARCHAR(100) PRIMARY KEY, name VARCHAR(180) NOT NULL, state VARCHAR(30) NOT NULL DEFAULT 'OFF', description TEXT,
 updated_by UUID REFERENCES users(id), updated_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE privileged_events (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), actor_user_id UUID REFERENCES users(id), action VARCHAR(160) NOT NULL,
 target_type VARCHAR(100), target_id VARCHAR(180), reason TEXT, source_ip INET, occurred_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE INDEX idx_privileged_events_time ON privileged_events(occurred_at DESC);

INSERT INTO workflow_definitions(code,name,entity_type) VALUES ('PRE_AWARD','Pre-Award Lifecycle','APPLICATION') ON CONFLICT DO NOTHING;
INSERT INTO workflow_steps(workflow_id,step_order,code,name,required_role_code,approval_required)
SELECT w.id,x.n,x.code,x.name,x.role,x.approval FROM workflow_definitions w CROSS JOIN (VALUES
(1,'DISCOVERED','Discover','GRANTS_OFFICER',false),(2,'ELIGIBILITY_REVIEW','Review eligibility','GRANTS_OFFICER',false),
(3,'DIRECTOR_DECISION','Director/team decision','DIRECTOR',true),(4,'ASSIGNED','Assign researcher','GRANTS_OFFICER',false),
(5,'ACCEPTED','Researcher accepts','RESEARCHER',false),(6,'PREPARATION','Track preparation','RESEARCHER',false),
(7,'INTERNAL_REVIEW','Internal review','GRANTS_OFFICER',true),(8,'INSTITUTIONAL_APPROVAL','Institutional approval','DIRECTOR',true),
(9,'SUBMITTED','Submit','GRANTS_OFFICER',false),(10,'OUTCOME_RECORDED','Record funder outcome','GRANTS_OFFICER',false),
(11,'AWARDED','Convert to award','GRANTS_OFFICER',false)) AS x(n,code,name,role,approval)
WHERE w.code='PRE_AWARD' ON CONFLICT DO NOTHING;
