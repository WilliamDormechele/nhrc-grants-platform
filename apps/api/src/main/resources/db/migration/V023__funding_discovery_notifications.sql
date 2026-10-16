-- Govern discovery notifications and claim each opportunity once. Do not replay historical discovery.
INSERT INTO communication_policies(event_code,name,in_app,email,recipient_roles)
VALUES ('OPPORTUNITY_DISCOVERY','New funding calls and NHRC match evidence',true,true,'["GRANTS_OFFICER","DIRECTOR","SUPERADMIN"]'::jsonb)
ON CONFLICT DO NOTHING;
CREATE TABLE opportunity_discovery_announcements (
 opportunity_id uuid PRIMARY KEY REFERENCES opportunities(id),
 run_id uuid REFERENCES opportunity_discovery_runs(id),
 notified_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO opportunity_discovery_announcements(opportunity_id)
SELECT id FROM opportunities WHERE discovery_source_id IS NOT NULL ON CONFLICT DO NOTHING;
CREATE INDEX discovery_evidence_payload_lookup ON opportunity_source_evidence(source_id,external_id,payload_hash,validation_status);
