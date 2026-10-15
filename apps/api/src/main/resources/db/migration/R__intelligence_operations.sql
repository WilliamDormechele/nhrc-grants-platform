CREATE TABLE IF NOT EXISTS intelligence_automation_settings (
 id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id), enabled BOOLEAN NOT NULL DEFAULT TRUE,
 lookahead_days INTEGER NOT NULL DEFAULT 30 CHECK (lookahead_days BETWEEN 1 AND 180),
 urgent_days INTEGER NOT NULL DEFAULT 7 CHECK (urgent_days BETWEEN 0 AND 30),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), CHECK (urgent_days <= lookahead_days)
);
INSERT INTO intelligence_automation_settings(id) VALUES(TRUE) ON CONFLICT DO NOTHING;
CREATE TABLE IF NOT EXISTS intelligence_automation_runs (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), trigger_type VARCHAR(30) NOT NULL,
 started_at TIMESTAMPTZ NOT NULL DEFAULT now(), completed_at TIMESTAMPTZ,
 status VARCHAR(30) NOT NULL, detected INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL DEFAULT 0,
 resolved INTEGER NOT NULL DEFAULT 0, error TEXT
);
CREATE TABLE IF NOT EXISTS intelligence_alerts (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(), rule_code VARCHAR(60) NOT NULL,
 entity_id UUID NOT NULL, title TEXT NOT NULL, due_date DATE NOT NULL,
 severity VARCHAR(30) NOT NULL, workspace VARCHAR(100) NOT NULL, group_name VARCHAR(100) NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','ACKNOWLEDGED','RESOLVED')),
 acknowledged_by UUID REFERENCES users(id), acknowledged_at TIMESTAMPTZ, note TEXT,
 detected_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(rule_code,entity_id,due_date)
);
CREATE INDEX IF NOT EXISTS idx_intelligence_alerts_queue ON intelligence_alerts(status,due_date);
