$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$items=@(Call ADMIN '/api/operations/form-administration')
Assert ($items.Count -eq 53) 'Administrator can inspect all 53 operational form definitions'
foreach($role in @('DIRECTOR','GRANTS_OFFICER','RESEARCHER','FINANCE_OFFICER','FINANCE_APPROVER','PROCUREMENT_OFFICER','LABORATORY_OFFICER','GOVERNANCE_OFFICER','POST_AWARD_OFFICER','IT_ADMIN','SUPERADMIN','AUDITOR')){
 $null=Call $role '/api/operations/form-administration' GET $null 403
}
Assert $true 'All twelve non-Administrator roles are denied form-design administration'
$item=@($items|Where-Object {$_.base.key-eq 'units'})[0]
function NewFormConfig($source){return @{title=$source.definition.title;description=$source.definition.description;enabled=$source.enabled;readers=@($source.readers);writers=@($source.definition.writers);revision=[long]$source.revision;reason='Synthetic UAT form administration verification';fields=@($source.definition.fields|ForEach-Object {@{name=$_.name;label=$_.label;help=$_.help;section=$_.section;required=$_.required;hidden=$_.hidden}})}}
$baseline=NewFormConfig $item
$config=NewFormConfig $item
try {
 $config.fields[0].label='Institutional unit code'
 @($config.fields|Where-Object name -eq active)[0].hidden=$true
 $saved=Call ADMIN '/api/operations/form-administration/units' PUT $config
 $fresh=Call ADMIN '/api/operations/units?size=1'
 Assert ($fresh.definition.fields[0].label-eq 'Institutional unit code' -and @($fresh.definition.fields|Where-Object name -eq active)[0].hidden) 'Saved labels and optional-field visibility reach the live record form'
 $null=Call ADMIN '/api/operations/form-administration/units' PUT $config 409
 Assert $true 'Stale configuration edits are rejected'
 $config.revision=$saved.revision
 $config.fields[0].hidden=$true
 $null=Call ADMIN '/api/operations/form-administration/units' PUT $config 400
 $config.fields[0].hidden=$false
 $config.fields[0].required=$false
 $null=Call ADMIN '/api/operations/form-administration/units' PUT $config 400
 $config.fields[0].required=$true
 Assert $true 'Protected required fields cannot be hidden or made optional'
 $config.writers=@('ADMIN','RESEARCHER')
 $null=Call ADMIN '/api/operations/form-administration/units' PUT $config 400
 $config.writers=@($baseline.writers)
 Assert $true 'Form editing cannot create new departmental privileges'
 $config.enabled=$false
 $saved=Call ADMIN '/api/operations/form-administration/units' PUT $config
 $r=Call ADMIN '/api/operations/units?size=1'
 Assert (-not $r.canCreate -and -not $r.canEdit -and $r.total -gt 0) 'Retiring an operational entry form disables editing while retaining records'
 $null=Call ADMIN '/api/operations/units' POST @{values=@{code='UAT blocked';name='UAT blocked';unit_type='UNIT'}} 403
} finally {
 $now=@(Call ADMIN '/api/operations/form-administration'|Where-Object {$_.base.key-eq 'units'})[0]
 $baseline.revision=$now.revision
 $baseline.reason='Restore original entry-form configuration after the UAT checks'
 $null=Call ADMIN '/api/operations/form-administration/units' PUT $baseline
}
$history=@(Call ADMIN '/api/operations/form-administration/units/history')
Assert ($history.Count-ge 3 -and @($history|Where-Object {-not $_.actor -or -not $_.note}).Count-eq 0) 'Configuration edits retain named actors, timestamps and reasons'
$stamp=Get-Date -Format HHmmss
$fields=@(@{name='studyRationale';label='Study rationale';type='textarea';required=$false;help='Synthetic questionnaire verification only'})
$form=Call ADMIN '/api/operations/forms' POST @{values=@{code="UAT-FORM-HUB-$stamp";name="UAT form administration $stamp";entity_type='APPLICATION';version_no=1;schema_json=@{fields=$fields};active=$true};requestId=[guid]::NewGuid()}
$fixture=Get-Content 'D:\Git\nhrc-grants-platform\.local\uat-lifecycle-fixture.json' -Raw|ConvertFrom-Json
$read=@(Call GRANTS_OFFICER "/api/operations/application-forms/$($fixture.applicationId)")
Assert (@($read|Where-Object id -eq $form.id).Count-eq 1) 'Published institutional questions appear in the proposal workspace'
$null=Call GRANTS_OFFICER "/api/operations/application-forms/$($fixture.applicationId)/$($form.id)" PUT @{values=@{studyRationale='Synthetic test response; not a research finding'};revision=0}
$removal=@{fields=@(@{name='replacement';label='Replacement question';type='text';required=$false})}
$null=Call ADMIN "/api/operations/forms/$($form.id)" PUT @{values=@{schema_json=$removal};etag=$form._etag;note='Synthetic answered-question removal probe'} 400
Assert $true 'Answered questions cannot be removed or silently replaced'
$null=Call GRANTS_OFFICER "/api/operations/forms/$($form.id)" PUT @{values=@{active=$false};etag=$form._etag;note='Unauthorised retirement probe'} 403
$retired=Call ADMIN "/api/operations/forms/$($form.id)" PUT @{values=@{active=$false};etag=$form._etag;note='Retain the synthetic answered form but retire it from proposal entry'}
Assert (-not $retired.active -and $retired.version_no-eq 2) 'Institutional retirement retains the form and increments its version'
$after=@(Call GRANTS_OFFICER "/api/operations/application-forms/$($fixture.applicationId)")
Assert (@($after|Where-Object id -eq $form.id).Count-eq 0) 'Retired questions no longer appear for new proposal entry'
$restored=Call ADMIN "/api/operations/forms/$($form.id)" PUT @{values=@{active=$true};etag=$retired._etag;note='Verify that restoring a questionnaire retains its saved answers'}
$visible=@(Call GRANTS_OFFICER "/api/operations/application-forms/$($fixture.applicationId)"|Where-Object id -eq $form.id)[0]
Assert ($visible.answers.studyRationale-eq 'Synthetic test response; not a research finding') 'Restoring a questionnaire recovers the previously saved response'
$null=Call ADMIN "/api/operations/forms/$($form.id)" PUT @{values=@{active=$false};etag=$restored._etag;note='Finish UAT with the synthetic questionnaire retired'}
@{result='PASS';testedAt=(Get-Date).ToUniversalTime().ToString('o');operationalForms=$items.Count;deniedRoles=12;historyEntries=$history.Count;retainedFormId=$form.id;applicationId=$fixture.applicationId}|ConvertTo-Json|Set-Content 'D:\Git\nhrc-grants-platform\docs\testing\FORMS_ADMINISTRATION_CHECKS.json'


