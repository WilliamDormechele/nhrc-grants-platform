CREATE TABLE roles_seed_guard(id INTEGER PRIMARY KEY);
INSERT INTO organisation_units(code,name,unit_type) VALUES ('NHRC','Navrongo Health Research Centre','INSTITUTION') ON CONFLICT (code) DO NOTHING;
INSERT INTO roles(code,name,description,privileged) VALUES
('DIRECTOR','Director','Institutional executive approval and oversight',false),
('GRANTS_OFFICER','Grants Officer','Pre-award and grant administration',false),
('POST_AWARD_OFFICER','Post-Award Officer','Award administration and reporting',false),
('RESEARCHER','Researcher / PI','Researcher workspace and assigned grants',false),
('FINANCE_OFFICER','Finance Officer','Grant finance preparation and reconciliation',false),
('FINANCE_APPROVER','Finance Approver','Financial approval authority',false),
('PROCUREMENT_OFFICER','Procurement Officer','Grant procurement management',false),
('LABORATORY_OFFICER','Laboratory Officer','Grant laboratory oversight',false),
('GOVERNANCE_OFFICER','Research Governance Officer','Ethics and compliance linkage',false),
('AUDITOR','Auditor','Controlled read-only assurance access',false),
('ADMIN','Business Administrator','Business configuration',false),
('IT_ADMIN','IT Administrator','Technical operations without business approval authority',true),
('SUPERADMIN','Superadmin','Protected platform administration without implicit business approval authority',true)
ON CONFLICT (code) DO NOTHING;
INSERT INTO feature_flags(code,name,state,description) VALUES
('OPPORTUNITY_DISCOVERY','Automated opportunity discovery','PILOT','Controlled ingestion from approved funding sources'),
('RESEARCHER_MATCHING','Researcher matching','PILOT','Explainable fit suggestions requiring human confirmation'),
('PARTNER_PORTAL','Partner portal','OFF','External partner access'),
('FINANCE_INTEGRATION','Finance system integration','OFF','Controlled finance data exchange')
ON CONFLICT (code) DO NOTHING;
