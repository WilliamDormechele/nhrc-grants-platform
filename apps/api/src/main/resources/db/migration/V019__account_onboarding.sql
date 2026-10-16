CREATE TABLE account_invitations (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id uuid NOT NULL REFERENCES users(id),
 email_at_issue text NOT NULL,token_hash varchar(64) NOT NULL UNIQUE,encrypted_token text NOT NULL,
 revoked_at timestamptz,expires_at timestamptz NOT NULL DEFAULT now()+interval '72 hours',used_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),created_by uuid NOT NULL REFERENCES users(id));
CREATE INDEX account_invitations_user ON account_invitations(user_id,created_at DESC);
INSERT INTO communication_policies(event_code,name,recipient_roles)
 SELECT 'ACCOUNT','Account welcome and onboarding',jsonb_agg(code) FROM roles WHERE code<>'SYSTEM_AUTOMATION';
