-- Include assigned investigators, reviewers and partner finance oversight. No historic email replay.
CREATE OR REPLACE FUNCTION lifecycle_communication_event() RETURNS trigger LANGUAGE plpgsql AS $$
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
 AND (u.id::text IN (rec->>'owner_user_id',rec->>'lead_researcher_id',rec->>'researcher_id',rec->>'reviewer_id',rec->>'assigned_user_id',rec->>'principal_investigator_id',rec->>'prepared_by',rec->>'reviewed_by',rec->>'requested_by',rec->>'created_by',rec->>'authorised_by',rec->>'accepted_by')
 OR u.id IN (SELECT a.principal_investigator_id FROM awards a WHERE a.id::text=rec->>'award_id')
 OR u.id IN (SELECT a.owner_user_id FROM applications a WHERE a.id::text=CASE WHEN TG_TABLE_NAME='approvals' AND rec->>'entity_type'='APPLICATION' THEN rec->>'entity_id' ELSE rec->>'application_id' END)
 OR u.id IN (SELECT a.lead_researcher_id FROM applications a WHERE a.id::text=CASE WHEN TG_TABLE_NAME='approvals' AND rec->>'entity_type'='APPLICATION' THEN rec->>'entity_id' ELSE rec->>'application_id' END)
 OR (TG_TABLE_NAME='partner_advances' AND (u.id IN (SELECT a.principal_investigator_id FROM award_partners ap JOIN awards a ON a.id=ap.award_id WHERE ap.id::text=rec->>'award_partner_id') OR r.code IN ('FINANCE_APPROVER','POST_AWARD_OFFICER')))
 OR (event_kind='APPROVAL' AND state IN ('RETURNED','REJECTED') AND r.code='GRANTS_OFFICER')
 OR r.code=CASE event_kind WHEN 'APPLICATION' THEN 'GRANTS_OFFICER' WHEN 'AWARD' THEN 'POST_AWARD_OFFICER' WHEN 'DELIVERABLE' THEN 'POST_AWARD_OFFICER' WHEN 'REPORT' THEN 'POST_AWARD_OFFICER' WHEN 'FINANCE' THEN 'FINANCE_OFFICER' WHEN 'PROCUREMENT' THEN 'PROCUREMENT_OFFICER' WHEN 'GOVERNANCE' THEN 'GOVERNANCE_OFFICER' WHEN 'LABORATORY' THEN 'LABORATORY_OFFICER' WHEN 'CLOSEOUT' THEN 'POST_AWARD_OFFICER' ELSE '' END
 OR (rec->>'assigned_user_id' IS NULL AND r.code=rec->>'required_role_code'))
 LOOP
 IF policy.in_app OR policy.email THEN
 INSERT INTO notifications(user_id,type,title,message,entity_type,entity_id)
 VALUES(recipient.id,event_kind,left(replace(initcap(event_kind),'_',' ')||' - '||label,255),'Recorded stage: '||replace(initcap(state),'_',' ')||'. '||coalesce(nullif(rec->>'decision_note',''),nullif(rec->>'comments',''),'Open the relevant workspace to review the record.'),TG_TABLE_NAME,(rec->>'id')::uuid) RETURNING id INTO uid;
 END IF;
 END LOOP;
 RETURN NEW;
END $$;

CREATE OR REPLACE VIEW lifecycle_tasks AS
 SELECT 'APPLICATION'::text kind,id,title,stage status,deadline_at::date due_date,
 coalesce(lead_researcher_id,owner_user_id) owner_id,'GRANTS_OFFICER'::text responsible_role,'Pre-Award'::text group_name,'Applications'::text workspace
 FROM applications WHERE NOT EXISTS (SELECT 1 FROM approvals p WHERE p.entity_type='APPLICATION' AND p.entity_id=applications.id AND p.status='REJECTED' AND p.id=(SELECT p2.id FROM approvals p2 WHERE p2.entity_type=p.entity_type AND p2.entity_id=p.entity_id AND p2.approval_type=p.approval_type ORDER BY p2.created_at DESC,p2.id DESC LIMIT 1)) AND stage NOT IN ('CLOSED','AWARDED','REJECTED','WITHDRAWN','SUBMITTED','OUTCOME_RECORDED')
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
 UNION ALL SELECT 'REVIEW',r.id,a.reference||' - '||r.review_type,r.status,a.deadline_at::date,CASE WHEN r.status='RETURNED' THEN coalesce(a.lead_researcher_id,a.owner_user_id) ELSE r.reviewer_id END,'GRANTS_OFFICER','Pre-Award','Internal Review' FROM application_reviews r JOIN applications a ON a.id=r.application_id WHERE r.status IN ('PENDING','IN_PROGRESS','RETURNED') AND a.stage NOT IN ('CLOSED','AWARDED','REJECTED','WITHDRAWN')
 UNION ALL SELECT 'HANDOVER',h.id,a.reference||' - Award handover',h.status,NULL::date,CASE WHEN h.status='READY' THEN NULL::uuid ELSE h.prepared_by END,CASE WHEN h.status='READY' THEN 'POST_AWARD_OFFICER' ELSE 'GRANTS_OFFICER' END,'Award Management','Award Register' FROM award_handovers h JOIN applications a ON a.id=h.application_id WHERE h.status NOT IN ('ACCEPTED','CANCELLED')
 UNION ALL SELECT 'ADVANCE',p.id,a.reference||' - Partner advance retirement',p.status,p.retirement_due_date,NULL::uuid,'FINANCE_OFFICER','Finance & Accounts','Partner Advances'
 FROM partner_advances p JOIN award_partners ap ON ap.id=p.award_partner_id JOIN awards a ON a.id=ap.award_id WHERE p.status IN ('OUTSTANDING','PARTIALLY_RETIRED') AND a.status NOT IN ('CLOSED','CANCELLED');
