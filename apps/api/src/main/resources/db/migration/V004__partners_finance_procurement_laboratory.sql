CREATE TABLE partners (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    country_code VARCHAR(3),
    organisation_type VARCHAR(100),
    primary_contact_name VARCHAR(180),
    primary_contact_email VARCHAR(255),
    due_diligence_status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED',
    risk_rating VARCHAR(30),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(name, country_code)
);

CREATE TABLE due_diligence_reviews (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type VARCHAR(40) NOT NULL,
    entity_id UUID NOT NULL,
    review_type VARCHAR(100) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    risk_rating VARCHAR(30),
    findings TEXT,
    conditions TEXT,
    reviewed_by UUID REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    expires_at DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_due_diligence_entity ON due_diligence_reviews(entity_type, entity_id, status);

CREATE TABLE award_partners (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    partner_id UUID NOT NULL REFERENCES partners(id),
    role VARCHAR(100),
    approved_allocation NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    agreement_status VARCHAR(40) NOT NULL DEFAULT 'PENDING',
    UNIQUE(award_id, partner_id)
);

CREATE TABLE contracts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID REFERENCES awards(id) ON DELETE CASCADE,
    partner_id UUID REFERENCES partners(id),
    contract_type VARCHAR(100) NOT NULL,
    reference VARCHAR(100),
    title VARCHAR(255) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    effective_date DATE,
    expiry_date DATE,
    storage_key TEXT,
    owner_user_id UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE amendments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    amendment_type VARCHAR(100) NOT NULL,
    reference VARCHAR(100),
    justification TEXT NOT NULL,
    internal_status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    funder_approval_required BOOLEAN NOT NULL DEFAULT FALSE,
    funder_status VARCHAR(40) NOT NULL DEFAULT 'NOT_REQUIRED',
    effective_date DATE,
    requested_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ
);

CREATE TABLE fund_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    instalment_no INTEGER,
    expected_amount NUMERIC(19,2) NOT NULL DEFAULT 0,
    expected_date DATE,
    received_amount NUMERIC(19,2),
    received_date DATE,
    currency VARCHAR(3) NOT NULL,
    reference VARCHAR(120),
    verified_by UUID REFERENCES users(id),
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE expenditures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    transaction_date DATE NOT NULL,
    category VARCHAR(120) NOT NULL,
    description TEXT,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    finance_reference VARCHAR(120),
    reconciled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_expenditures_award_date ON expenditures(award_id, transaction_date DESC);

CREATE TABLE commitments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    category VARCHAR(120) NOT NULL,
    description TEXT,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    expected_date DATE,
    status VARCHAR(40) NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE partner_advances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_partner_id UUID NOT NULL REFERENCES award_partners(id) ON DELETE CASCADE,
    amount_advanced NUMERIC(19,2) NOT NULL,
    amount_accounted NUMERIC(19,2) NOT NULL DEFAULT 0,
    currency VARCHAR(3) NOT NULL,
    advanced_date DATE NOT NULL,
    retirement_due_date DATE,
    status VARCHAR(40) NOT NULL DEFAULT 'OUTSTANDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE procurement_plans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    item_description TEXT NOT NULL,
    category VARCHAR(100) NOT NULL,
    estimated_amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    planned_date DATE,
    procurement_method VARCHAR(100),
    status VARCHAR(40) NOT NULL DEFAULT 'PLANNED',
    owner_user_id UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE procurement_requisitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    plan_id UUID REFERENCES procurement_plans(id),
    award_id UUID NOT NULL REFERENCES awards(id) ON DELETE CASCADE,
    reference VARCHAR(100) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'DRAFT',
    requested_by UUID REFERENCES users(id),
    requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE suppliers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL UNIQUE,
    country_code VARCHAR(3),
    contact_email VARCHAR(255),
    due_diligence_status VARCHAR(40) NOT NULL DEFAULT 'NOT_STARTED',
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE purchase_orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requisition_id UUID NOT NULL REFERENCES procurement_requisitions(id),
    supplier_id UUID REFERENCES suppliers(id),
    reference VARCHAR(100) NOT NULL UNIQUE,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(40) NOT NULL DEFAULT 'ISSUED',
    issued_date DATE,
    expected_delivery_date DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE assets (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID REFERENCES awards(id),
    purchase_order_id UUID REFERENCES purchase_orders(id),
    asset_tag VARCHAR(100) UNIQUE,
    description TEXT NOT NULL,
    serial_number VARCHAR(180),
    location VARCHAR(255),
    custodian_user_id UUID REFERENCES users(id),
    status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
    acquired_date DATE,
    acquisition_cost NUMERIC(19,2),
    currency VARCHAR(3),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE laboratory_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    award_id UUID REFERENCES awards(id),
    item_type VARCHAR(60) NOT NULL,
    name VARCHAR(255) NOT NULL,
    catalogue_or_asset_ref VARCHAR(180),
    quantity NUMERIC(19,4),
    unit VARCHAR(40),
    expiry_date DATE,
    calibration_due_date DATE,
    maintenance_due_date DATE,
    status VARCHAR(40) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
