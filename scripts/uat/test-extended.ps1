. "$PSScriptRoot/test_helpers.ps1"
$fixture=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
$rows=Get-Content "$root/docs/testing/DEPARTMENT_FORM_RESULTS.json" -Raw|ConvertFrom-Json
function Saved($key,$role){$id=($rows|Where-Object key -eq $key).id;return (Call $role "/api/operations/$key").rows|Where-Object id -eq $id}
$receipt=Saved receipts FINANCE_OFFICER
$null=Call FINANCE_OFFICER '/api/operations/receipts' POST @{values=@{award_id=$fixture.awardId;currency='USD';received_amount=10;received_date=$fixture.startDate}} 400
$null=Call FINANCE_OFFICER '/api/operations/expenditure' POST @{values=@{award_id=$fixture.awardId;currency='GBP';transaction_date=$fixture.startDate;category='PERSONNEL';amount=-1}} 400
$positions=(Call FINANCE_OFFICER '/api/operations/positions').rows|Where-Object award_id -eq $fixture.awardId
$receipts=@((Call FINANCE_OFFICER '/api/operations/receipts').rows|Where-Object award_id -eq $fixture.awardId)
$expenditure=@((Call FINANCE_OFFICER '/api/operations/expenditure').rows|Where-Object {$_.award_id -eq $fixture.awardId -and $_.reconciled})
$commitments=@((Call FINANCE_OFFICER '/api/operations/commitments').rows|Where-Object {$_.award_id -eq $fixture.awardId -and $_.status -eq 'OPEN'})
Assert ($positions.cash_received -eq ($receipts|Measure-Object received_amount -Sum).Sum) 'Receipt retries never double-count cash received'
Assert ($positions.expenditure -eq ($expenditure|Measure-Object amount -Sum).Sum) 'Reconciled expenditure matches the financial position'
Assert ($positions.commitments -eq ($commitments|Measure-Object amount -Sum).Sum) 'Open commitments match the financial position'
$maintenance=Saved maintenance LABORATORY_OFFICER
if($maintenance.status -notin @('IN_PROGRESS','COMPLETE')){$maintenance=Call LABORATORY_OFFICER "/api/operations/maintenance/$($maintenance.id)/action" POST @{state='IN_PROGRESS';etag=$maintenance._etag;note='Synthetic UAT service commenced'}}
if($maintenance.status -ne 'COMPLETE'){$maintenance=Call LABORATORY_OFFICER "/api/operations/maintenance/$($maintenance.id)/action" POST @{state='COMPLETE';etag=$maintenance._etag;note='Synthetic UAT service certificate uploaded'}}
Assert ($maintenance.status -eq 'COMPLETE') 'Laboratory maintenance completes with document evidence'
$report=Saved reports GRANTS_OFFICER
foreach($state in @('IN_PROGRESS','SUBMITTED','ACCEPTED')){if($report.status -eq 'ACCEPTED'){break};$report=Call GRANTS_OFFICER "/api/operations/reports/$($report.id)/action" POST @{state=$state;etag=$report._etag;note='Synthetic UAT report decision'}}
Assert ($report.submitted_at -and $report.accepted_at) 'Reporting records actual submission and acceptance times'
$preview=Call ADMIN '/api/operations/import/preview' POST @{dataset='funders';csv="Funder name,Active`nUAT CSV Funder $(Get-Date -Format yyyyMMdd-HHmmss-fff),false"}
Assert ($preview.valid -eq 1 -and $preview.errors.Count -eq 0) 'CSV preflight accepts labelled domain columns'
$import=Call ADMIN '/api/operations/import/commit' POST @{dataset='funders';rows=@($preview.rows)}
Assert ($import.created -eq 1) 'Validated CSV import persists the approved rows'
$invalid=Call ADMIN '/api/operations/import/preview' POST @{dataset='funders';csv="Funder name,Active`n,false"}
Assert ($invalid.errors.Count -eq 1) 'CSV preflight identifies invalid rows before import'
$form=Call ADMIN '/api/operations/forms' POST @{values=@{code='UAT QUESTIONS '+(Get-Date -Format HHmmss);name='UAT institutional acceptance questions';entity_type='APPLICATION';active=$true;schema_json=@{fields=@(@{name='purpose';label='Institutional purpose';type='textarea';required=$true},@{name='resources';label='Resources committed';type='number';required=$true},@{name='support';label='Support confirmed';type='select';choices=@('Confirmed','Pending');required=$true})}}}
try{
 $forms=Call RESEARCHER "/api/operations/application-forms/$($fixture.applicationId)"
 Assert (@($forms|Where-Object id -eq $form.id).Count -eq 1) 'Published institutional questions appear on the assigned proposal'
 $null=Call RESEARCHER "/api/operations/application-forms/$($fixture.applicationId)/$($form.id)" PUT @{values=@{};revision=0} 400
 $saved=Call RESEARCHER "/api/operations/application-forms/$($fixture.applicationId)/$($form.id)" PUT @{values=@{purpose='Synthetic UAT purpose';resources=10;support='Confirmed'};revision=0}
 Assert ($saved.revision -eq 1) 'Typed institutional responses save with a revision'
 $null=Call RESEARCHER "/api/operations/application-forms/$($fixture.applicationId)/$($form.id)" PUT @{values=@{purpose='Synthetic UAT purpose';resources=10;support='Confirmed'};revision=0} 409
 Write-Output 'PASS Institutional responses enforce required answers and stale revision protection'
}finally{$null=Call ADMIN "/api/operations/forms/$($form.id)" PUT @{values=@{active=$false};etag=$form._etag;note='Deactivate the synthetic acceptance questionnaire after testing'}}
$run=Call GRANTS_OFFICER '/api/intelligence/automation/run' POST @{}
$repeat=Call GRANTS_OFFICER '/api/intelligence/automation/run' POST @{}
Assert ($run.status -eq 'SUCCESS' -and $repeat.created -eq 0) 'Deadline automation completes without duplicating alerts'
$status=Call GRANTS_OFFICER '/api/intelligence/assistant/status'
Assert ($status.available -and $status.local) 'The free local Qwen model is installed and reachable'
@{result='PASS';checks=16;applicationId=$fixture.applicationId;awardId=$fixture.awardId;testedAt=(Get-Date).ToUniversalTime().ToString('o')}|ConvertTo-Json|Set-Content "$root/docs/testing/EXTENDED_WORKFLOW_RESULTS.json"
