CREATE TABLE financial_reports (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
 period_start DATE, period_end DATE, due_date DATE NOT NULL, status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED',
 prepared_by UUID REFERENCES users(id), reviewed_by UUID REFERENCES users(id), authorised_by UUID REFERENCES users(id),
 storage_key TEXT, submitted_at TIMESTAMPTZ, accepted_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE financial_forecasts (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
 forecast_date DATE NOT NULL, period_end DATE NOT NULL, forecast_expenditure NUMERIC(19,2) NOT NULL,
 forecast_commitments NUMERIC(19,2) NOT NULL DEFAULT 0, currency VARCHAR(3) NOT NULL, assumptions TEXT,
 prepared_by UUID REFERENCES users(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE reconciliations (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
 period_end DATE NOT NULL, status VARCHAR(40) NOT NULL DEFAULT 'OPEN', ledger_amount NUMERIC(19,2), platform_amount NUMERIC(19,2),
 variance NUMERIC(19,2), note TEXT, reconciled_by UUID REFERENCES users(id), reconciled_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE financial_approvals (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), award_id UUID REFERENCES awards(id), entity_type VARCHAR(80) NOT NULL,
 entity_id UUID NOT NULL, approval_type VARCHAR(100) NOT NULL, amount NUMERIC(19,2), currency VARCHAR(3),
 status VARCHAR(40) NOT NULL DEFAULT 'PENDING', assigned_user_id UUID REFERENCES users(id), decided_by UUID REFERENCES users(id),
 decided_at TIMESTAMPTZ, decision_note TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE laboratory_maintenance (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), laboratory_item_id UUID NOT NULL REFERENCES laboratory_items(id) ON DELETE CASCADE,
 activity_type VARCHAR(80) NOT NULL, scheduled_date DATE NOT NULL, completed_date DATE, provider VARCHAR(255), certificate_storage_key TEXT,
 status VARCHAR(40) NOT NULL DEFAULT 'SCHEDULED', notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now());
CREATE TABLE procurement_contracts (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), purchase_order_id UUID REFERENCES purchase_orders(id), supplier_id UUID REFERENCES suppliers(id),
 reference VARCHAR(100), title VARCHAR(255) NOT NULL, status VARCHAR(40) NOT NULL DEFAULT 'DRAFT', start_date DATE,end_date DATE,
 amount NUMERIC(19,2),currency VARCHAR(3),storage_key TEXT,created_at TIMESTAMPTZ NOT NULL DEFAULT now());
