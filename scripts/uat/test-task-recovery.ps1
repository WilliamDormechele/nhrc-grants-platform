. "$PSScriptRoot/test_helpers.ps1"
$f=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
$go=SignIn GRANTS_OFFICER;$pi=SignIn RESEARCHER;$director=SignIn DIRECTOR
$stamp=Get-Date -Format 'yyyyMMddHHmmss'
$o=Call GRANTS_OFFICER '/api/operations/opportunities' POST @{values=@{title="UAT task recovery $stamp";source_type='MANUAL';call_type='GRANT_CALL';keywords=@()}}
$a=Call GRANTS_OFFICER '/api/applications' POST @{opportunityId=$o.id;title="UAT task recovery $stamp";ownerUserId=$go.user.id}
$null=Call RESEARCHER "/api/pregrant/applications/$($a.id)/workspace" GET $null 403
$null=Call GRANTS_OFFICER "/api/applications/$($a.id)/stage" POST @{stage='ELIGIBILITY_REVIEW';note='Synthetic recovery test'}
$null=Call GRANTS_OFFICER "/api/applications/$($a.id)/stage" POST @{stage='DIRECTOR_DECISION';note='Attempt to bypass eligibility'} 409
$null=Call GRANTS_OFFICER "/api/applications/$($a.id)/eligibility" POST @{decision='ELIGIBLE';rationale='Synthetic UAT eligibility only'}
$null=Call GRANTS_OFFICER "/api/applications/$($a.id)/stage" POST @{stage='DIRECTOR_DECISION';note='Synthetic Director routing'}
$null=Call GRANTS_OFFICER '/api/approvals' POST @{entityType='APPLICATION';entityId=$a.id;approvalType='DIRECTOR_DECISION';assignedUserId=$go.user.id} 400
$approval=Call GRANTS_OFFICER '/api/approvals' POST @{entityType='APPLICATION';entityId=$a.id;approvalType='DIRECTOR_DECISION';assignedUserId=$director.user.id}
$null=Call DIRECTOR "/api/approvals/$($approval.id)/decision" POST @{decision='APPROVED';note='Synthetic approval for task recovery'}
$null=Call GRANTS_OFFICER "/api/applications/$($a.id)/assignments" POST @{researcherId=$go.user.id} 400
$as=Call GRANTS_OFFICER "/api/applications/$($a.id)/assignments" POST @{researcherId=$pi.user.id}
$null=Call RESEARCHER "/api/applications/assignments/$($as.assignmentId)/response" POST @{response='DECLINED';note='Synthetic decline to test reassignment'}
$w=Call GRANTS_OFFICER "/api/pregrant/applications/$($a.id)/workspace"
Assert ($w.application.stage -eq 'DIRECTOR_DECISION' -and -not $w.application.lead_researcher_id) 'Declined assignment returns to grants officer for reassignment'
$as=Call GRANTS_OFFICER "/api/applications/$($a.id)/assignments" POST @{researcherId=$pi.user.id}
$null=Call RESEARCHER "/api/applications/assignments/$($as.assignmentId)/response" POST @{response='ACCEPTED';note='Synthetic reassignment accepted'}
$null=Call RESEARCHER "/api/applications/$($a.id)/stage" POST @{stage='PREPARATION';note='Synthetic preparation'}
$rv=Call GRANTS_OFFICER "/api/proposals/$($a.id)/reviews" POST @{reviewType='MANAGEMENT';reviewerId=$director.user.id}
$null=Call DIRECTOR "/api/pregrant/reviews/$($rv.id)" PATCH @{status='RETURNED';comments='Synthetic return for revision'}
$null=Call DIRECTOR "/api/pregrant/reviews/$($rv.id)" PATCH @{status='APPROVED';comments='Synthetic revised package reviewed'}
$null=Call RESEARCHER "/api/proposals/$($a.id)/budget" POST @{category='UAT overallocated probe';quantity=1;unitCost=50;currency='GBP';funderAmount=60} 400
$line=Call RESEARCHER "/api/proposals/$($a.id)/budget" POST @{category='UAT decimal budget';quantity=2;unitCost=12.75;currency='GBP';funderAmount=25.50}
Assert ($line.calculatedCost -eq 25.50) 'Decimal budget arithmetic is exact and overallocated lines are rejected'
$forms=Call RESEARCHER "/api/operations/application-forms/$($a.id)"
$answersTested=0
foreach($form in $forms){
 if(-not $form.canEdit){continue};$answers=@{}
 foreach($q in $form.schema.fields){Assert (-not [string]::IsNullOrWhiteSpace($q.name) -and -not [string]::IsNullOrWhiteSpace($q.label)) "Question is labelled: $($form.name) / $($q.label)";$answers[$q.name]=switch($q.type){'number'{1};'date'{(Get-Date).ToString('yyyy-MM-dd')};'select'{$q.choices[0]};'checkbox'{$false};default{'Synthetic local UAT response; no live research or donor record'}}}
 $saved=Call RESEARCHER "/api/operations/application-forms/$($a.id)/$($form.id)" PUT @{values=$answers;revision=$form.revision}
 Assert ($saved.revision -eq $form.revision+1) "$($form.name) saves its real question identities and revision"
 $null=Call RESEARCHER "/api/operations/application-forms/$($a.id)/$($form.id)" PUT @{values=$answers;revision=$form.revision} 409
 $answersTested+=$answers.Count
}
$null=Call RESEARCHER "/api/operations/awards/$($f.awardId)/history"
$null=Call RESEARCHER "/api/operations/awards/$($f.awardId)/closure-readiness"
Write-Output 'PASS Assigned PI can read own award history and closure readiness'
@{result='PASS';applicationId=$a.id;questionnaires=$forms.Count;answersTested=$answersTested;testedAt=(Get-Date).ToUniversalTime().ToString('o')}|ConvertTo-Json|Set-Content "$root/docs/testing/FULL_TASK_RECOVERY_RESULTS.json"
