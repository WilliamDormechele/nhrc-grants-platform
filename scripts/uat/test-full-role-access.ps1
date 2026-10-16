. (Join-Path $PSScriptRoot 'test_helpers.ps1')
$anon=Invoke-WebRequest http://127.0.0.1:8080/api/operations/catalog -SkipHttpErrorCheck -TimeoutSec 15
Assert ($anon.StatusCode-eq 401) 'Anonymous operational access is denied'
$results=@()
foreach($role in @('DIRECTOR','GRANTS_OFFICER','RESEARCHER','FINANCE_OFFICER','FINANCE_APPROVER','PROCUREMENT_OFFICER','LABORATORY_OFFICER','GOVERNANCE_OFFICER','POST_AWARD_OFFICER','ADMIN','IT_ADMIN','SUPERADMIN','AUDITOR')){
 $session=SignIn $role
 Assert ($session.user.roles-contains $role) "Named $role account signs in with its institutional role"
 $catalog=Call $role '/api/operations/catalog'
 foreach($d in $catalog){try{$null=Call $role "/api/operations/$($d.key)";$results+=@{role=$role;workspace=$d.key;status='PASS'}}catch{$results+=@{role=$role;workspace=$d.key;status='FAIL';message=$_.Exception.Message}}}
}
$null=Call RESEARCHER '/api/operations/accounts' 'GET' $null 403
$null=Call RESEARCHER '/api/operations/assurance' 'GET' $null 403
$null=Call FINANCE_OFFICER '/api/admin/audit' 'GET' $null 403
$null=Call SUPERADMIN '/api/operations/receipts' 'POST' @{values=@{}} 403
Write-Output 'PASS Direct administrative and technical/business boundary denials'
$finance=SignIn FINANCE_OFFICER
$csrf=Invoke-WebRequest http://127.0.0.1:8080/api/operations/receipts -Method Post -Body '{}' -ContentType application/json -WebSession $finance.cookie -SkipHttpErrorCheck -TimeoutSec 15
Assert ($csrf.StatusCode-eq 403) 'Mutation without a CSRF token is denied'
$results | ConvertTo-Json -Depth 6 | Set-Content (Join-Path $root 'docs/testing/ROLE_WORKSPACE_RESULTS.json')
$failed=@($results | Where-Object {$_.status-eq 'FAIL'})
Write-Output "Role-workspace reads: $($results.Count-$failed.Count)/$($results.Count) passed"
$failed | ForEach-Object {Write-Output $_.message}
Assert ($failed.Count-eq 0) 'Every authorised operational register loads successfully'


