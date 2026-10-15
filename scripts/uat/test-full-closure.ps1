param([switch]$LeaveReady)
. "$PSScriptRoot/test_helpers.ps1"
$f=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
$forms=Get-Content "$root/docs/testing/DEPARTMENT_FORM_RESULTS.json" -Raw|ConvertFrom-Json
function Record($key,$role){$rid=($forms|Where-Object key -eq $key).id;return (Call $role "/api/operations/$($key)?size=100").rows|Where-Object id -eq $rid}
function InvokeGrantState($key,$record,$state,$role,$expected=200){return Call $role "/api/operations/$key/$($record.id)/action" POST @{state=$state;etag=$record._etag;note='Synthetic full grant lifecycle UAT: '+$state} $expected}
$award=@((Call POST_AWARD_OFFICER "/api/operations/awards?q=$($f.awardId)").rows)[0]
Assert ($award.id -eq $f.awardId) 'Post-award officer sees the converted award'
$null=InvokeGrantState awards $award ACTIVE POST_AWARD_OFFICER 400
Write-Output 'PASS Award activation requires the executed funding agreement'
$agreement=Record agreements GOVERNANCE_OFFICER
$agreement=InvokeGrantState agreements $agreement IN_REVIEW GOVERNANCE_OFFICER
$agreement=InvokeGrantState agreements $agreement EXECUTED GOVERNANCE_OFFICER
$award=InvokeGrantState awards $award ACTIVE POST_AWARD_OFFICER
Assert ($award.status -eq 'ACTIVE') 'Governance executes the funding agreement and post-award activates the award'
$body=@{status='READY';proposalComplete=$true;budgetComplete=$false;awardDocumentsComplete=$true;approvalsComplete=$true;correspondenceComplete=$true;note='Synthetic UAT package check'}
$null=Call GRANTS_OFFICER "/api/operations/awards/$($f.awardId)/handover" PUT $body 400
$body.budgetComplete=$true
$null=Call RESEARCHER "/api/operations/awards/$($f.awardId)/handover" PUT $body 403
$null=Call GRANTS_OFFICER "/api/operations/awards/$($f.awardId)/handover" PUT $body
$body.status='ACCEPTED'
$null=Call GRANTS_OFFICER "/api/operations/awards/$($f.awardId)/handover" PUT $body 403
$body.status='RETURNED';$null=Call POST_AWARD_OFFICER "/api/operations/awards/$($f.awardId)/handover" PUT $body
$body.status='READY';$null=Call GRANTS_OFFICER "/api/operations/awards/$($f.awardId)/handover" PUT $body
$body.status='ACCEPTED';$null=Call POST_AWARD_OFFICER "/api/operations/awards/$($f.awardId)/handover" PUT $body
$handover=Call POST_AWARD_OFFICER "/api/operations/awards/$($f.awardId)/handover"
Assert ($handover.handover.prepared_by -ne $handover.handover.accepted_by -and $handover.handover.accepted_at) 'Handover supports independent return, correction and acceptance with named officers and timestamps'
$award=InvokeGrantState awards $award CLOSEOUT POST_AWARD_OFFICER
$readiness=Call POST_AWARD_OFFICER "/api/operations/awards/$($f.awardId)/closure-readiness"
Assert (-not $readiness.ready -and @($readiness.checks|Where-Object {-not $_.complete}).Count -ge 5) 'Recorded incomplete reports, deliverables, checklist and financial obligations appear as closure blockers'
$null=InvokeGrantState awards $award CLOSED POST_AWARD_OFFICER 400
$report=Record reports GRANTS_OFFICER
foreach($state in @('IN_PROGRESS','SUBMITTED','ACCEPTED')){if($report.status -ne 'ACCEPTED'){$report=InvokeGrantState reports $report $state GRANTS_OFFICER}}
$deliverable=Record deliverables RESEARCHER
foreach($state in @('IN_PROGRESS','SUBMITTED')){$deliverable=InvokeGrantState deliverables $deliverable $state RESEARCHER}
$null=InvokeGrantState deliverables $deliverable ACCEPTED RESEARCHER 403
$deliverable=InvokeGrantState deliverables $deliverable ACCEPTED POST_AWARD_OFFICER
Assert ($deliverable.accepted_at -and $deliverable.submitted_at) 'PI submits deliverable evidence and post-award independently accepts it'
$fr=Record financial-reports FINANCE_OFFICER
foreach($state in @('IN_PROGRESS','IN_REVIEW')){$fr=InvokeGrantState financial-reports $fr $state FINANCE_OFFICER}
$null=InvokeGrantState financial-reports $fr APPROVED FINANCE_OFFICER 403
$fr=InvokeGrantState financial-reports $fr APPROVED FINANCE_APPROVER
$fr=InvokeGrantState financial-reports $fr SUBMITTED FINANCE_OFFICER
$fr=InvokeGrantState financial-reports $fr ACCEPTED FINANCE_APPROVER
Assert ($fr.authorised_by -eq (SignIn FINANCE_APPROVER).user.id -and $fr.submitted_at -and $fr.accepted_at) 'Finance preparation, independent approval, submission and acceptance retain actual officers and times'
$c=Record commitments FINANCE_OFFICER;$c=InvokeGrantState commitments $c SETTLED FINANCE_OFFICER
$a=Record advances FINANCE_OFFICER;$a=InvokeGrantState advances $a PARTIALLY_RETIRED FINANCE_OFFICER
$null=InvokeGrantState advances $a RETIRED FINANCE_OFFICER 400
$a=Call FINANCE_OFFICER "/api/operations/advances/$($a.id)" PUT @{values=@{amount_accounted=$a.amount_advanced};etag=$a._etag;note='Synthetic UAT full retirement evidence'}
$a=InvokeGrantState advances $a RETIRED FINANCE_OFFICER
Write-Output 'PASS Commitments settle and advances cannot retire while unaccounted'
$cl=Record closeout POST_AWARD_OFFICER
$cl=InvokeGrantState closeout $cl IN_PROGRESS POST_AWARD_OFFICER
$null=InvokeGrantState awards $award CLOSED POST_AWARD_OFFICER 400
$cl=InvokeGrantState closeout $cl COMPLETE POST_AWARD_OFFICER
$readiness=Call POST_AWARD_OFFICER "/api/operations/awards/$($f.awardId)/closure-readiness"
Assert ($readiness.ready) 'All recorded closure requirements are complete'
if(-not $LeaveReady){
 $award=InvokeGrantState awards $award CLOSED POST_AWARD_OFFICER
 $null=InvokeGrantState awards $award CLOSED POST_AWARD_OFFICER 403
 $workspace=Call GRANTS_OFFICER "/api/pregrant/applications/$($f.applicationId)/workspace"
 Assert ($award.status -eq 'CLOSED' -and $workspace.application.stage -eq 'CLOSED') 'Award closure also closes the linked application'
 Assert (@($workspace.stageHistory|Where-Object {$_.to_stage -eq 'CLOSED' -and $_.changed_by_name -eq (SignIn POST_AWARD_OFFICER).user.name}).Count -eq 1) 'Exactly one named closure transition is retained'
}
foreach($role in @('RESEARCHER','POST_AWARD_OFFICER','FINANCE_OFFICER','ADMIN','IT_ADMIN','SUPERADMIN','AUDITOR')){
 $null=Call $role "/api/applications/$($f.applicationId)/outcome" POST @{outcome='AWARDED'} 403
 $null=Call $role "/api/pregrant/applications/$($f.applicationId)/submission" POST @{} 403
}
Write-Output 'PASS Institutional submission and funder outcome cannot be impersonated by operational or technical roles'
@{result='PASS';applicationId=$f.applicationId;awardId=$f.awardId;testedAt=(Get-Date).ToUniversalTime().ToString('o');closureChecks=$readiness.checks}|ConvertTo-Json -Depth 6|Set-Content "$root/docs/testing/FULL_CLOSURE_RESULTS.json"

