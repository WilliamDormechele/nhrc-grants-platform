CREATE UNIQUE INDEX idx_grant_source_one_active_run
ON grant_source_runs(source_code) WHERE status='RUNNING';
CREATE INDEX idx_opportunities_deadline_date ON opportunities(deadline_date);
CREATE INDEX idx_grants_application_owner_stage ON applications(owner_user_id,stage);
CREATE INDEX idx_grants_application_lead_stage ON applications(lead_researcher_id,stage);
CREATE INDEX idx_grants_current_review ON application_reviews(application_id,content_revision,reviewer_id,status);
CREATE INDEX idx_grants_document_latest ON grant_document_links(application_id,document_type,version_no DESC);
