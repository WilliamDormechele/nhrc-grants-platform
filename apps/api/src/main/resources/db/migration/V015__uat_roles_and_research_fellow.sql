-- UAT role differentiation and senior administration accounts.
-- Non-destructive: production deployments may retain the role definitions without UAT users
-- because development accounts are selected only when AUTH_MODE=development.

INSERT INTO roles(code,name,description,privileged) VALUES
('RESEARCH_FELLOW','Research Fellow','Researcher-level proposal and award participation without institutional approval authority',false)
ON CONFLICT (code) DO UPDATE SET
  name=EXCLUDED.name,
  description=EXCLUDED.description,
  privileged=EXCLUDED.privileged;

DO $$
DECLARE
  nhrc UUID;
  grants UUID;
  research_fellow UUID;
  admin_user UUID;
  superadmin_user UUID;
BEGIN
  SELECT id INTO nhrc FROM organisation_units WHERE code='NHRC';
  SELECT id INTO grants FROM organisation_units WHERE code='GRANTS';

  INSERT INTO users(email,display_name,organisation_unit_id,job_title)
  VALUES
    ('research.fellow.uat@nhrc.local','UAT Research Fellow',COALESCE(grants,nhrc),'Research Fellow'),
    ('admin.uat@nhrc.local','UAT Business Administrator',nhrc,'Business Administrator'),
    ('superadmin.uat@nhrc.local','UAT Superadmin',nhrc,'Platform Superadmin')
  ON CONFLICT (email) DO UPDATE SET
    display_name=EXCLUDED.display_name,
    organisation_unit_id=EXCLUDED.organisation_unit_id,
    job_title=EXCLUDED.job_title,
    active=true;

  SELECT id INTO research_fellow FROM users WHERE email='research.fellow.uat@nhrc.local';
  SELECT id INTO admin_user FROM users WHERE email='admin.uat@nhrc.local';
  SELECT id INTO superadmin_user FROM users WHERE email='superadmin.uat@nhrc.local';

  INSERT INTO user_roles(user_id,role_id,scope_type,scope_id)
  SELECT research_fellow,id,'INSTITUTION',nhrc FROM roles WHERE code='RESEARCH_FELLOW'
  ON CONFLICT DO NOTHING;

  INSERT INTO user_roles(user_id,role_id,scope_type,scope_id)
  SELECT admin_user,id,'INSTITUTION',nhrc FROM roles WHERE code='ADMIN'
  ON CONFLICT DO NOTHING;

  INSERT INTO user_roles(user_id,role_id,scope_type,scope_id)
  SELECT superadmin_user,id,'INSTITUTION',nhrc FROM roles WHERE code='SUPERADMIN'
  ON CONFLICT DO NOTHING;

  INSERT INTO researcher_profiles(user_id,orcid,expertise,methods,career_stage,summary,selected_track_record,availability_note,active)
  VALUES(
    research_fellow,
    NULL,
    ARRAY['population health','health systems','implementation research'],
    ARRAY['mixed methods','routine health data','evidence synthesis'],
    'EARLY_CAREER',
    'UAT Research Fellow profile for proposal preparation, collaboration and assigned award work.',
    'Controlled UAT profile only.',
    'Available for UAT proposal assignments.',
    true
  )
  ON CONFLICT (user_id) DO UPDATE SET
    expertise=EXCLUDED.expertise,
    methods=EXCLUDED.methods,
    career_stage=EXCLUDED.career_stage,
    summary=EXCLUDED.summary,
    selected_track_record=EXCLUDED.selected_track_record,
    availability_note=EXCLUDED.availability_note,
    active=true;
END $$;
