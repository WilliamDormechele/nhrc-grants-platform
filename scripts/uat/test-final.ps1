. "$PSScriptRoot/test_helpers.ps1"
$fixture=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
foreach($pair in @(@('FINANCE_OFFICER','Finance & Accounts','Financial reports due'),@('PROCUREMENT_OFFICER','Procurement','Orders outstanding'),@('LABORATORY_OFFICER','Laboratory','Maintenance due'))){
 $dashboard=Call $pair[0] ('/api/operations/dashboard?group='+[uri]::EscapeDataString($pair[1]))
 Assert ($dashboard.tiles.title -contains $pair[2]) "$($pair[1]) dashboard reports the department's actual workload"
}
$settings=Call ADMIN '/api/operations/institutional-settings'
$saved=Call ADMIN '/api/operations/institutional-settings' PUT @{enabled=$settings.enabled;lookaheadDays=$settings.lookahead_days;urgentDays=$settings.urgent_days;note='UAT verifies that governed settings persist without changing the existing schedule'}
Assert ($saved.lookahead_days -eq $settings.lookahead_days) 'Governed automation settings persist with a change reason'
$null=Call ADMIN '/api/operations/institutional-settings' PUT @{enabled=$true;lookaheadDays=0;urgentDays=7;note='UAT invalid-window probe'} 400
$null=Call AUDITOR '/api/operations/institutional-settings' PUT @{enabled=$true;lookaheadDays=30;urgentDays=7;note='UAT write-denial probe'} 403
foreach($role in @('FINANCE_OFFICER','FINANCE_APPROVER','AUDITOR')){
 $p=Call $role "/api/operations/proposals/$($fixture.applicationId)/permissions"
 Assert (-not $p.canRead -and -not $p.canEdit -and -not $p.canAttach) "$role retains the existing protected proposal authoring boundary"
 $null=Call $role "/api/operations/proposals/$($fixture.applicationId)/narrative" GET $null 403
 $null=Call $role "/api/operations/application-forms/$($fixture.applicationId)" GET $null 403
}
$p=Call RESEARCHER "/api/operations/proposals/$($fixture.applicationId)/permissions"
Assert ($p.canRead -and $p.canEdit -and $p.canAttach) 'The assigned principal investigator can author and attach proposal evidence'
$null=Call FINANCE_APPROVER '/api/operations/catalog'
$backup=Call IT_ADMIN '/api/operations/backups/run' POST @{}
$restore=Call IT_ADMIN "/api/operations/backups/$($backup.id)/verify-restore" POST @{}
Assert ($restore.status -eq 'SUCCESS') 'The final deployment database restores into an isolated database'
@{result='PASS';testedAt=(Get-Date).ToUniversalTime().ToString('o');backupId=$backup.id;restoreTestId=$restore.id;applicationId=$fixture.applicationId;awardId=$fixture.awardId}|ConvertTo-Json|Set-Content "$root/docs/testing/FINAL_DEPLOYMENT_CHECKS.json"
