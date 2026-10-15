. (Join-Path $PSScriptRoot 'test_helpers.ps1')
$stamp=Get-Date -Format 'HHmmss'
$grants=SignIn GRANTS_OFFICER;$director=SignIn DIRECTOR;$researcher=SignIn RESEARCHER
$day=(Get-Date).ToString('yyyy-MM-dd');$end=(Get-Date).AddYears(1).ToString('yyyy-MM-dd')
$op=Call GRANTS_OFFICER '/api/operations/opportunities' POST @{values=@{title="UAT Acceptance Call $stamp";source_type='MANUAL';source_reference="UAT-CALL-$stamp";url='https://example.invalid/uat-call';summary='Synthetic acceptance fixture, not a live funding call';currency='GBP';amount_max='1000';deadline_at=(Get-Date).AddDays(30).ToUniversalTime().ToString('o')}}
$app=Call GRANTS_OFFICER '/api/applications' POST @{opportunityId=$op.id;title="UAT Acceptance Project $stamp";ownerUserId=$grants.user.id;deadlineAt=(Get-Date).AddDays(30).ToUniversalTime().ToString('o')}
$id=$app.id
$form=Call ADMIN '/api/operations/forms' POST @{values=@{code="UAT GATE $stamp";name="UAT submission gate questionnaire $stamp";entity_type='APPLICATION';active=$true;schema_json=@{fields=@(@{name='purpose';label='Institutional purpose';type='textarea';required=$true})}}}
$null=Call GRANTS_OFFICER "/api/applications/$id/outcome" POST @{outcome='AWARDED'} 409
Write-Output 'PASS Funder outcome cannot bypass submission'
$null=Call GRANTS_OFFICER "/api/applications/$id/stage" POST @{stage='ELIGIBILITY_REVIEW';actorId=$grants.user.id;note='UAT gate check'}
$null=Call GRANTS_OFFICER "/api/applications/$id/eligibility" POST @{decision='ELIGIBLE';rationale='Synthetic UAT call only; no live eligibility assessment';actorId=$grants.user.id}
$null=Call GRANTS_OFFICER "/api/applications/$id/stage" POST @{stage='DIRECTOR_DECISION';actorId=$grants.user.id;note='UAT Director routing'}
$null=Call GRANTS_OFFICER "/api/applications/$id/assignments" POST @{researcherId=$researcher.user.id;assignedBy=$grants.user.id} 409
$decision=Call GRANTS_OFFICER '/api/approvals' POST @{entityType='APPLICATION';entityId=$id;approvalType='DIRECTOR_DECISION';assignedUserId=$director.user.id;requestedBy=$grants.user.id}
$null=Call DIRECTOR "/api/approvals/$($decision.id)/decision" POST @{decision='APPROVED';decidedBy=$director.user.id;note='Synthetic UAT go decision'}
$assignment=Call GRANTS_OFFICER "/api/applications/$id/assignments" POST @{researcherId=$researcher.user.id;assignedBy=$grants.user.id}
$null=Call GRANTS_OFFICER "/api/applications/assignments/$($assignment.assignmentId)/response" POST @{response='ACCEPTED';note='Impersonation probe'} 403
$null=Call RESEARCHER "/api/applications/assignments/$($assignment.assignmentId)/response" POST @{response='ACCEPTED';note='Synthetic researcher acceptance'}
$null=Call RESEARCHER "/api/applications/$id/stage" POST @{stage='PREPARATION';actorId=$researcher.user.id;note='UAT proposal preparation'}
Write-Output 'PASS Director decision and assigned-researcher gates'
$section=Call RESEARCHER "/api/operations/proposals/$id/narrative" PUT @{section='summary';content='Synthetic UAT text for saving and revision checks. No research findings are asserted.';revision=0}
Assert ($section.revision-eq 1) 'Proposal narrative saves a revision'
$null=Call RESEARCHER "/api/operations/proposals/$id/narrative" PUT @{section='summary';content='Stale edit probe';revision=0} 409
Write-Output 'PASS Stale proposal narrative edit is denied'
$proofPath=Join-Path $root '.local/uat-acceptance-evidence.txt'
[IO.File]::WriteAllText($proofPath,'Synthetic NHRC Grants UAT evidence only. No donor submission or real financial transaction occurred.')
$cookie=$grants.cookie;$csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $cookie
$doc=Invoke-RestMethod http://127.0.0.1:8080/api/operations/documents -Method Post -WebSession $cookie -Headers @{$csrf.headerName=$csrf.token} -Form @{file=Get-Item -LiteralPath $proofPath}
$null=Call GRANTS_OFFICER "/api/proposals/$id/documents" POST @{documentType='TECHNICAL';title='UAT acceptance evidence';storageKey=$doc.storageKey;versionNo=1;status='FINAL';uploadedBy=$grants.user.id}
$null=Call RESEARCHER "/api/proposals/$id/budget" POST @{category='UAT test cost';description='Synthetic budget-line validation';yearNo=1;quantity=2;unitCost=50;currency='GBP';funderAmount=100;nhrcContribution=0;partnerAmount=0}
$workspace=Call GRANTS_OFFICER "/api/pregrant/applications/$id/workspace"
foreach($r in $workspace.requirements){$null=Call GRANTS_OFFICER "/api/pregrant/requirements/$($r.id)" PATCH @{status='COMPLETE';note='Synthetic UAT checklist evidence';evidenceStorageKey=$doc.storageKey}}
$review=Call GRANTS_OFFICER "/api/proposals/$id/reviews" POST @{reviewType='MANAGEMENT';reviewerId=$director.user.id;comments='Synthetic independent UAT review'}
$null=Call DIRECTOR "/api/pregrant/reviews/$($review.id)" PATCH @{status='APPROVED';reviewerId=$director.user.id;comments='Synthetic UAT review; validates routing, not scientific acceptance'}
$null=Call RESEARCHER "/api/applications/$id/stage" POST @{stage='INTERNAL_REVIEW';actorId=$researcher.user.id;note='UAT internal review'}
$null=Call GRANTS_OFFICER "/api/applications/$id/stage" POST @{stage='INSTITUTIONAL_APPROVAL';actorId=$grants.user.id;note='UAT institutional approval routing'}
$null=Call GRANTS_OFFICER "/api/pregrant/applications/$id/submission" POST @{acknowledgement='UAT probe';proofStorageKey=$doc.storageKey} 409
Write-Output 'PASS Submission requires an actual institutional approval record'
$approval=Call GRANTS_OFFICER '/api/approvals' POST @{entityType='APPLICATION';entityId=$id;approvalType='INSTITUTIONAL';assignedUserId=$director.user.id;requestedBy=$grants.user.id}
$null=Call DIRECTOR "/api/approvals/$($approval.id)/decision" POST @{decision='APPROVED';decidedBy=$director.user.id;note='Synthetic institutional UAT approval'}
foreach($q in $workspace.qualityChecks){$null=Call GRANTS_OFFICER "/api/pregrant/quality-checks/$($q.id)" PATCH @{status='PASSED';checkedBy=$grants.user.id;note='Synthetic UAT quality-check evidence'} }
$null=Call GRANTS_OFFICER "/api/pregrant/applications/$id/submission" POST @{acknowledgement="UAT receipt $stamp";proofStorageKey=$doc.storageKey} 409
Write-Output 'PASS Required institutional questions block submission until answered'
$null=Call RESEARCHER "/api/operations/application-forms/$id/$($form.id)" PUT @{values=@{purpose='Synthetic UAT institutional purpose'};revision=0}
$null=Call GRANTS_OFFICER "/api/pregrant/applications/$id/submission" POST @{submittedAt=(Get-Date).ToUniversalTime().ToString('o');acknowledgement="UAT receipt $stamp";proofStorageKey=$doc.storageKey}
$null=Call ADMIN "/api/operations/forms/$($form.id)" PUT @{values=@{active=$false};etag=$form._etag;note='Deactivate synthetic submission gate questionnaire after the successful check'}
$null=Call GRANTS_OFFICER "/api/applications/$id/outcome" POST @{outcome='AWARDED'}
$award=Call GRANTS_OFFICER "/api/applications/$id/convert-to-award" POST @{reference="UAT-ACCEPT-$stamp";startDate=$day;endDate=$end;currency='GBP';totalAward=1000;nhrcAllocation=800;partnerAllocation=200}
Write-Output 'PASS Complete application-to-award lifecycle with versioned document evidence'
@{applicationId=$id;awardId=$award.awardId;document=$doc.storageKey;stamp=$stamp;startDate=$day;endDate=$end} | ConvertTo-Json | Set-Content (Join-Path $root '.local/uat-lifecycle-fixture.json')

