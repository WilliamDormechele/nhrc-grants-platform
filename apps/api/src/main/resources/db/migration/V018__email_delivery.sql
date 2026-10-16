CREATE TABLE email_delivery_settings (
 id boolean PRIMARY KEY DEFAULT true CHECK(id),
 mode text NOT NULL DEFAULT 'LOCAL_CAPTURE' CHECK(mode IN ('LOCAL_CAPTURE','GMAIL')),
 encrypted_password text, tested_at timestamptz, updated_at timestamptz NOT NULL DEFAULT now());
INSERT INTO email_delivery_settings(id) VALUES(true);
ALTER TABLE email_outbox ADD COLUMN delivery_mode text NOT NULL DEFAULT 'LOCAL_CAPTURE' CHECK(delivery_mode IN ('LOCAL_CAPTURE','GMAIL'));
-- Stamp only new messages. Captured UAT history must never be replayed to real inboxes.
CREATE FUNCTION stamp_email_delivery_mode() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 SELECT mode INTO NEW.delivery_mode FROM email_delivery_settings WHERE id=true;
 RETURN NEW;
END $$;
CREATE TRIGGER stamp_delivery BEFORE INSERT ON email_outbox FOR EACH ROW EXECUTE FUNCTION stamp_email_delivery_mode();
