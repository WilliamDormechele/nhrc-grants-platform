-- Additive changes only. Historical approvals are not fabricated or silently recertified.
ALTER TABLE due_diligence_reviews ADD COLUMN IF NOT EXISTS record_version BIGINT NOT NULL DEFAULT 1;
ALTER TABLE due_diligence_reviews ADD COLUMN IF NOT EXISTS prepared_by UUID REFERENCES users(id);
ALTER TABLE due_diligence_reviews ADD COLUMN IF NOT EXISTS checklist JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE due_diligence_reviews ADD COLUMN IF NOT EXISTS evidence_url TEXT;
ALTER TABLE due_diligence_reviews ADD COLUMN IF NOT EXISTS decision_note TEXT;
ALTER TABLE due_diligence_reviews ADD COLUMN IF NOT EXISTS submitted_at TIMESTAMPTZ;
ALTER TABLE due_diligence_reviews ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE partners ADD COLUMN IF NOT EXISTS due_diligence_valid_until DATE;
ALTER TABLE partners ADD COLUMN IF NOT EXISTS due_diligence_review_id UUID REFERENCES due_diligence_reviews(id);
ALTER TABLE partners ADD COLUMN IF NOT EXISTS due_diligence_evidence_url TEXT;
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS due_diligence_valid_until DATE;
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS due_diligence_review_id UUID REFERENCES due_diligence_reviews(id);
ALTER TABLE suppliers ADD COLUMN IF NOT EXISTS due_diligence_evidence_url TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS idx_one_controlled_diligence_draft
ON due_diligence_reviews(entity_type,entity_id)
WHERE prepared_by IS NOT NULL AND status IN ('DRAFT','PENDING','CONDITIONAL');
CREATE INDEX IF NOT EXISTS idx_diligence_review_expiry ON due_diligence_reviews(expires_at,status);
