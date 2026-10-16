\set ON_ERROR_STOP on
BEGIN;
CREATE TEMP TABLE notification_before AS SELECT id FROM notifications;
DO $$
DECLARE target uuid; investigator uuid; reviewer uuid; missing int;
BEGIN
 SELECT p.id,a.lead_researcher_id INTO target,investigator FROM approvals p JOIN applications a ON a.id=p.entity_id WHERE a.reference='APP-2026-04725515' AND p.approval_type='INSTITUTIONAL' ORDER BY p.created_at DESC LIMIT 1;
 UPDATE approvals SET status='RETURNED',decision_note='Synthetic notification acceptance - return' WHERE id=target;
 IF NOT EXISTS(SELECT 1 FROM notifications n WHERE n.entity_id=target AND n.user_id=investigator AND n.message LIKE '%Synthetic notification acceptance - return%' AND NOT EXISTS(SELECT 1 FROM notification_before b WHERE b.id=n.id)) THEN RAISE EXCEPTION 'Returned approval did not notify the assigned investigator with decision reason';END IF;
 UPDATE approvals SET status='REJECTED',decision_note='Synthetic notification acceptance - rejection' WHERE id=target;
 IF NOT EXISTS(SELECT 1 FROM notifications n WHERE n.entity_id=target AND n.user_id=investigator AND n.message LIKE '%Synthetic notification acceptance - rejection%' AND NOT EXISTS(SELECT 1 FROM notification_before b WHERE b.id=n.id)) THEN RAISE EXCEPTION 'Rejected approval did not notify investigator';END IF;
 SELECT r.id,r.reviewer_id,a.lead_researcher_id INTO target,reviewer,investigator FROM application_reviews r JOIN applications a ON a.id=r.application_id WHERE r.id='d0867065-5044-435c-b2e7-12844076a571';
 UPDATE application_reviews SET status='RETURNED',comments='Synthetic notification acceptance - revise costing' WHERE id=target;
 IF (SELECT count(DISTINCT user_id) FROM notifications n WHERE entity_id=target AND user_id IN(reviewer,investigator) AND NOT EXISTS(SELECT 1 FROM notification_before b WHERE b.id=n.id))<>2 THEN RAISE EXCEPTION 'Returned review misses named reviewer or investigator';END IF;
 IF NOT EXISTS(SELECT 1 FROM lifecycle_tasks WHERE id=target AND kind='REVIEW' AND owner_id=investigator) THEN RAISE EXCEPTION 'Returned review reminder still belongs to reviewer';END IF;
 UPDATE partner_advances SET status='PARTIALLY_RETIRED' WHERE id='18efbc3b-b296-47c6-8e5b-7ab13b194de1';
 SELECT count(DISTINCT r.code) INTO missing FROM notifications n JOIN user_roles ur ON ur.user_id=n.user_id JOIN roles r ON r.id=ur.role_id WHERE n.entity_id='18efbc3b-b296-47c6-8e5b-7ab13b194de1' AND r.code IN ('FINANCE_OFFICER','FINANCE_APPROVER','POST_AWARD_OFFICER') AND NOT EXISTS(SELECT 1 FROM notification_before b WHERE b.id=n.id);
 IF missing<>3 THEN RAISE EXCEPTION 'Advance updates miss Finance or Post-award oversight: %',missing;END IF;
 IF NOT EXISTS(SELECT 1 FROM lifecycle_tasks WHERE kind='ADVANCE' AND id='18efbc3b-b296-47c6-8e5b-7ab13b194de1') THEN RAISE EXCEPTION 'Outstanding advance has no reminder task';END IF;
 IF EXISTS(SELECT 1 FROM lifecycle_tasks t JOIN partner_advances p ON t.id=p.id WHERE t.kind='ADVANCE' AND p.status='RETIRED') THEN RAISE EXCEPTION 'Retired advance remains reminder eligible';END IF;
 IF EXISTS(SELECT 1 FROM lifecycle_tasks WHERE kind='APPLICATION' AND id IN ('04725515-7ad4-4f93-bc97-101d198c42fc','87503b44-504a-4063-bc49-a2f2d673e06b','0bdd8700-06dd-498b-95ea-3ca2e4d1399d')) THEN RAISE EXCEPTION 'Closed or currently rejected applications still generate preparation reminders';END IF;
 IF EXISTS(SELECT 1 FROM notifications n JOIN users u ON u.id=n.user_id WHERE NOT EXISTS(SELECT 1 FROM notification_before b WHERE b.id=n.id) AND NOT u.active) THEN RAISE EXCEPTION 'Inactive recipient received event';END IF;
 IF EXISTS(SELECT 1 FROM notifications n JOIN user_roles ur ON ur.user_id=n.user_id JOIN roles r ON r.id=ur.role_id WHERE NOT EXISTS(SELECT 1 FROM notification_before b WHERE b.id=n.id) AND r.code IN ('IT_ADMIN','AUDITOR','PROCUREMENT_OFFICER','LABORATORY_OFFICER')) THEN RAISE EXCEPTION 'Unrelated roles received tested events';END IF;
 RAISE NOTICE 'PASS: returns, rejections, reviewers, investigator, finance oversight, advance retirement reminders, completed/rejected suppression, inactive and unrelated roles';
END $$;
ROLLBACK;
