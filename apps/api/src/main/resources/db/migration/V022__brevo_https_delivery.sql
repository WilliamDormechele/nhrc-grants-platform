ALTER TABLE email_delivery_settings DROP CONSTRAINT email_delivery_settings_mode_check;
ALTER TABLE email_delivery_settings ADD CONSTRAINT email_delivery_settings_mode_check CHECK(mode IN ('LOCAL_CAPTURE','GMAIL','BREVO'));
ALTER TABLE email_delivery_settings ADD COLUMN brevo_encrypted_key text, ADD COLUMN brevo_tested_at timestamptz;
ALTER TABLE email_outbox DROP CONSTRAINT email_outbox_delivery_mode_check;
ALTER TABLE email_outbox ADD CONSTRAINT email_outbox_delivery_mode_check CHECK(delivery_mode IN ('LOCAL_CAPTURE','GMAIL','BREVO'));
