-- Enable the requested researcher audience without altering channel choices or replaying past calls.
UPDATE communication_policies SET name='New funding calls and researcher expertise matches',
 recipient_roles=(SELECT jsonb_agg(DISTINCT role_code) FROM (SELECT jsonb_array_elements_text(recipient_roles) AS role_code UNION SELECT 'RESEARCHER' UNION SELECT 'RESEARCH_FELLOW') audience)
WHERE event_code='OPPORTUNITY_DISCOVERY';

UPDATE feature_flags SET state='ON',description='Expertise matching and governed researcher notifications; eligibility and application decisions require human review' WHERE code='RESEARCHER_MATCHING';
