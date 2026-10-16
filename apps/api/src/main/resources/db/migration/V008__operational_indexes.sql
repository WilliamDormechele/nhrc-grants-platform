CREATE INDEX IF NOT EXISTS idx_opportunities_status_deadline ON opportunities(status,deadline_at);
CREATE INDEX IF NOT EXISTS idx_applications_stage_deadline ON applications(stage,deadline_at);
CREATE INDEX IF NOT EXISTS idx_awards_status_end ON awards(status,end_date);
CREATE INDEX IF NOT EXISTS idx_reports_status_due ON reports(status,due_date);
CREATE INDEX IF NOT EXISTS idx_procurement_requisitions_status ON procurement_requisitions(status,requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_contracts_status_expiry ON contracts(status,expiry_date);
CREATE INDEX IF NOT EXISTS idx_amendments_status ON amendments(internal_status,created_at DESC);
CREATE INDEX IF NOT EXISTS idx_partner_advances_status_due ON partner_advances(status,retirement_due_date);
CREATE INDEX IF NOT EXISTS idx_laboratory_items_status ON laboratory_items(status,expiry_date,calibration_due_date);
