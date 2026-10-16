CREATE TABLE IF NOT EXISTS questionnaire_permissions (
 form_id UUID PRIMARY KEY REFERENCES form_definitions(id),
 readers JSONB NOT NULL, writers JSONB NOT NULL,
 revision BIGINT NOT NULL DEFAULT 1,
 updated_by UUID NOT NULL REFERENCES users(id), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
