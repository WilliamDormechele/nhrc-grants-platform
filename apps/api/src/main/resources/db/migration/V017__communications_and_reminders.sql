CREATE TABLE communication_settings (
 id boolean PRIMARY KEY DEFAULT true CHECK(id), automatic_reminders boolean NOT NULL DEFAULT true,
 lookahead_days int NOT NULL DEFAULT 7 CHECK(lookahead_days BETWEEN 0 AND 90),
 repeat_days int NOT NULL DEFAULT 3 CHECK(repeat_days BETWEEN 1 AND 30),
 updated_at timestamptz NOT NULL DEFAULT now(), updated_by uuid REFERENCES users(id));
INSERT INTO communication_settings(id) VALUES(true);
CREATE TABLE communication_policies (
 event_code text PRIMARY KEY, name text NOT NULL, in_app boolean NOT NULL DEFAULT true,
 email boolean NOT NULL DEFAULT true, recipient_roles jsonb NOT NULL DEFAULT '[]');
INSERT INTO communication_policies(event_code,name) VALUES
 ('APPLICATION','Application stages and submission'),('ASSIGNMENT','Researcher assignments'),
 ('APPROVAL','Institutional decisions'),('AWARD','Award activation and closure'),
 ('DELIVERABLE','Delivery milestones'),('REPORT','Funder and financial reports'),
 ('FINANCE','Financial accountability'),('PROCUREMENT','Procurement and delivery'),
 ('GOVERNANCE','Contracts and regulatory obligations'),('LABORATORY','Maintenance and laboratory work'),
 ('CLOSEOUT','Grant closeout'),('REMINDER','Incomplete work and deadline reminders'),('ANNOUNCEMENT','Announcements');
UPDATE communication_policies SET recipient_roles=(SELECT jsonb_agg(code) FROM roles);
CREATE TABLE communication_sender_permissions (
 role_code text PRIMARY KEY REFERENCES roles(code), announcements boolean NOT NULL DEFAULT false,
 notifications boolean NOT NULL DEFAULT false, reminders boolean NOT NULL DEFAULT false);
INSERT INTO communication_sender_permissions(role_code,announcements,notifications,reminders)
 SELECT code,code='SUPERADMIN',code='SUPERADMIN',code IN ('SUPERADMIN','GRANTS_OFFICER','POST_AWARD_OFFICER') FROM roles;
CREATE TABLE communication_broadcasts (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), title varchar(255) NOT NULL, message varchar(8000) NOT NULL,
 category text NOT NULL CHECK(category IN ('ANNOUNCEMENT','NOTICE')), audience_roles jsonb NOT NULL,
 created_by uuid NOT NULL REFERENCES users(id), created_at timestamptz NOT NULL DEFAULT now(),
 expires_at timestamptz, withdrawn_at timestamptz, recipients int NOT NULL DEFAULT 0);
CREATE TABLE email_outbox (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), notification_id uuid NOT NULL UNIQUE REFERENCES notifications(id),
 recipient text NOT NULL, subject text NOT NULL, body text NOT NULL, status text NOT NULL DEFAULT 'QUEUED',
 attempts int NOT NULL DEFAULT 0, next_attempt_at timestamptz NOT NULL DEFAULT now(),
 sent_at timestamptz, last_error text, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX email_outbox_pending ON email_outbox(status,next_attempt_at);
CREATE TABLE reminder_dispatches (
 task_type text NOT NULL, task_id uuid NOT NULL, user_id uuid NOT NULL REFERENCES users(id),
 last_sent_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(task_type,task_id,user_id));

CREATE VIEW lifecycle_tasks AS
 SELECT 'APPLICATION'::text kind,id,title,stage status,deadline_at::date due_date,
 coalesce(lead_researcher_id,owner_user_id) owner_id,'GRANTS_OFFICER'::text responsible_role,'Pre-Award'::text group_name,'Applications'::text workspace
 FROM applications WHERE stage NOT IN ('CLOSED','AWARDED','REJECTED','WITHDRAWN','SUBMITTED','OUTCOME_RECORDED')
 UNION ALL SELECT 'ASSIGNMENT',x.id,a.reference||' - Accept research assignment',x.response,a.deadline_at::date,x.researcher_id,'RESEARCHER','Personal','My Work'
 FROM application_assignments x JOIN applications a ON a.id=x.application_id WHERE x.response='PENDING' AND a.stage NOT IN ('CLOSED','AWARDED','REJECTED','WITHDRAWN')
 UNION ALL SELECT 'APPROVAL',p.id,'Decision: '||p.approval_type,p.status,NULL::date,p.assigned_user_id,p.required_role_code,'Pre-Award','Approvals' FROM approvals p WHERE status='PENDING'
 UNION ALL SELECT 'DELIVERABLE',id,title,status,due_date,owner_user_id,'POST_AWARD_OFFICER','Award Management','Deliverables' FROM award_deliverables WHERE status NOT IN ('ACCEPTED','CLOSED','CANCELLED')
 UNION ALL SELECT 'REPORT',id,report_type,status,due_date,owner_user_id,'POST_AWARD_OFFICER','Performance','Reports' FROM reports WHERE status NOT IN ('ACCEPTED','CLOSED','CANCELLED')
 UNION ALL SELECT 'FINANCIAL_REPORT',id,'Financial report',status,due_date,CASE WHEN status IN ('IN_REVIEW','SUBMITTED') THEN reviewed_by ELSE prepared_by END,
 CASE WHEN status IN ('IN_REVIEW','SUBMITTED') THEN 'FINANCE_APPROVER' ELSE 'FINANCE_OFFICER' END,'Finance & Accounts','Financial Reporting' FROM financial_reports WHERE status NOT IN ('ACCEPTED','CLOSED','CANCELLED')
 UNION ALL SELECT 'CLOSEOUT',id,title,status,due_date,owner_user_id,coalesce(responsible_role,'POST_AWARD_OFFICER'),'Performance','Closeout' FROM closeout_items WHERE status NOT IN ('COMPLETE','CLOSED','CANCELLED')
 UNION ALL SELECT 'PROCUREMENT',id,reference||' - Delivery',status,expected_delivery_date,NULL::uuid,'PROCUREMENT_OFFICER','Procurement','Purchase Orders' FROM purchase_orders WHERE status IN ('ISSUED','PART_DELIVERED')
 UNION ALL SELECT 'LABORATORY',id,activity_type,status,scheduled_date,NULL::uuid,'LABORATORY_OFFICER','Laboratory','Maintenance & Calibration' FROM laboratory_maintenance WHERE status NOT IN ('COMPLETE','CANCELLED')
 UNION ALL SELECT 'GOVERNANCE',id,compliance_type,status,coalesce(renewal_due_date,expiry_date),NULL::uuid,'GOVERNANCE_OFFICER','Research Governance','Ethics & Regulatory Links' FROM compliance_records WHERE status NOT IN ('CLOSED','CANCELLED','REJECTED')
 UNION ALL SELECT 'CONTRACT',id,title,status,expiry_date,owner_user_id,'GOVERNANCE_OFFICER','Research Governance','Legal & Contracts Review' FROM contracts WHERE status NOT IN ('CLOSED','CANCELLED','EXPIRED')
 UNION ALL SELECT 'REVIEW',r.id,a.reference||' - '||r.review_type,r.status,a.deadline_at::date,r.reviewer_id,'GRANTS_OFFICER','Pre-Award','Internal Review' FROM application_reviews r JOIN applications a ON a.id=r.application_id WHERE r.status IN ('PENDING','IN_PROGRESS','RETURNED') AND a.stage NOT IN ('CLOSED','AWARDED','REJECTED','WITHDRAWN')
 UNION ALL SELECT 'HANDOVER',h.id,a.reference||' - Award handover',h.status,NULL::date,CASE WHEN h.status='READY' THEN NULL::uuid ELSE h.prepared_by END,CASE WHEN h.status='READY' THEN 'POST_AWARD_OFFICER' ELSE 'GRANTS_OFFICER' END,'Award Management','Award Register' FROM award_handovers h JOIN applications a ON a.id=h.application_id WHERE h.status NOT IN ('ACCEPTED','CANCELLED');

-- Only committed domain changes create notifications and email jobs. No historic backfill.
CREATE FUNCTION lifecycle_communication_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE rec jsonb; old_rec jsonb; event_kind text; state text; label text; uid uuid; recipient record; policy record;
BEGIN
 rec=to_jsonb(NEW); old_rec=CASE WHEN TG_OP='UPDATE' THEN to_jsonb(OLD) ELSE '{}'::jsonb END;
 state=coalesce(rec->>'stage',rec->>'response',rec->>'status','RECORDED');
 IF TG_OP='UPDATE' AND coalesce(old_rec->>'stage',old_rec->>'response',old_rec->>'status','RECORDED')=state THEN RETURN NEW; END IF;
 event_kind=TG_ARGV[0]; SELECT * INTO policy FROM communication_policies WHERE event_code=event_kind;
 label=left(coalesce(rec->>'reference',rec->>'title',rec->>'report_type',rec->>'approval_type',TG_TABLE_NAME),180);
 FOR recipient IN
 SELECT DISTINCT u.id FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id
 WHERE u.active AND (ur.valid_from IS NULL OR ur.valid_from<=now()) AND (ur.valid_until IS NULL OR ur.valid_until>=now())
 AND policy.recipient_roles ? r.code
 AND (u.id::text IN (rec->>'owner_user_id',rec->>'lead_researcher_id',rec->>'researcher_id',rec->>'assigned_user_id',rec->>'principal_investigator_id',rec->>'prepared_by',rec->>'reviewed_by',rec->>'requested_by',rec->>'created_by',rec->>'authorised_by',rec->>'accepted_by')
 OR u.id IN (SELECT a.principal_investigator_id FROM awards a WHERE a.id::text=rec->>'award_id')
 OR u.id IN (SELECT a.owner_user_id FROM applications a WHERE a.id::text=rec->>'application_id')
 OR r.code=CASE event_kind WHEN 'APPLICATION' THEN 'GRANTS_OFFICER' WHEN 'AWARD' THEN 'POST_AWARD_OFFICER' WHEN 'DELIVERABLE' THEN 'POST_AWARD_OFFICER' WHEN 'REPORT' THEN 'POST_AWARD_OFFICER' WHEN 'FINANCE' THEN 'FINANCE_OFFICER' WHEN 'PROCUREMENT' THEN 'PROCUREMENT_OFFICER' WHEN 'GOVERNANCE' THEN 'GOVERNANCE_OFFICER' WHEN 'LABORATORY' THEN 'LABORATORY_OFFICER' WHEN 'CLOSEOUT' THEN 'POST_AWARD_OFFICER' ELSE '' END
 OR (rec->>'assigned_user_id' IS NULL AND r.code=rec->>'required_role_code'))
 LOOP
 IF policy.in_app OR policy.email THEN
 INSERT INTO notifications(user_id,type,title,message,entity_type,entity_id)
 VALUES(recipient.id,event_kind,left(replace(initcap(event_kind),'_',' ')||' - '||label,255),'Recorded stage: '||replace(initcap(state),'_',' ')||'. Open the relevant workspace to review the record.',TG_TABLE_NAME,(rec->>'id')::uuid) RETURNING id INTO uid;
 END IF;
 END LOOP;
 RETURN NEW;
END $$;
DO $$ DECLARE pair text[]; BEGIN
 FOREACH pair SLICE 1 IN ARRAY ARRAY[['applications','APPLICATION'],['application_assignments','ASSIGNMENT'],['approvals','APPROVAL'],['awards','AWARD'],['award_deliverables','DELIVERABLE'],['reports','REPORT'],['financial_reports','REPORT'],['partner_advances','FINANCE'],['procurement_requisitions','PROCUREMENT'],['purchase_orders','PROCUREMENT'],['contracts','GOVERNANCE'],['laboratory_maintenance','LABORATORY'],['closeout_items','CLOSEOUT'],['compliance_records','GOVERNANCE'],['award_handovers','AWARD'],['application_reviews','APPROVAL']] LOOP
 IF to_regclass(pair[1]) IS NOT NULL THEN EXECUTE format('CREATE TRIGGER lifecycle_communications AFTER INSERT OR UPDATE ON %I FOR EACH ROW EXECUTE FUNCTION lifecycle_communication_event(%L)',pair[1],pair[2]); END IF;
 END LOOP;
END $$;
ALTER TABLE notifications ADD COLUMN in_app_visible boolean NOT NULL DEFAULT true;
CREATE FUNCTION queue_notification_email() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE policy record; BEGIN
 SELECT * INTO policy FROM communication_policies WHERE event_code=CASE WHEN NEW.type='MANUAL_REMINDER' THEN 'REMINDER' WHEN NEW.type='NOTICE' THEN 'ANNOUNCEMENT' ELSE NEW.type END;
 IF FOUND THEN
 UPDATE notifications SET in_app_visible=policy.in_app WHERE id=NEW.id;
 IF policy.email THEN INSERT INTO email_outbox(notification_id,recipient,subject,body)
 SELECT NEW.id,email,NEW.title,NEW.message FROM users WHERE id=NEW.user_id AND active; END IF;
 END IF; RETURN NEW;
END $$;
CREATE TRIGGER queue_email AFTER INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION queue_notification_email();
