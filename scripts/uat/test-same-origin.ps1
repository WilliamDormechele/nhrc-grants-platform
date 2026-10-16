$ErrorActionPreference='Stop'
$helpers=Get-Content 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1' -Raw
Invoke-Expression ($helpers.Replace('127.0.0.1:8080','127.0.0.1:3000'))
$results=@()
foreach($role in $accounts.role){$session=SignIn $role;$defs=Call $role '/api/operations/catalog';$inbox=Call $role '/api/notifications/inbox';$results+=@{role=$role;signedIn=$true;catalogCount=@($defs).Count;inboxAccessible=$true}}
$super=SignIn SUPERADMIN
$noCsrf=Invoke-WebRequest http://127.0.0.1:3000/api/notifications/00000000-0000-0000-0000-000000000000/read -Method Post -Body '{}' -ContentType application/json -WebSession $super.cookie -SkipHttpErrorCheck
Assert ($noCsrf.StatusCode-eq 403) 'Same-origin proxy retains CSRF enforcement'
$anon=Invoke-WebRequest http://127.0.0.1:3000/api/operations/catalog -SkipHttpErrorCheck
Assert ($anon.StatusCode-eq 401) 'Anonymous users cannot access records through web proxy'
$null=Call RESEARCHER '/api/operations/advances/18efbc3b-b296-47c6-8e5b-7ab13b194de1' GET $null 403
$delivery=Call SUPERADMIN '/api/operations/communications/email-delivery'
Assert ($delivery.mode-eq 'GMAIL') 'Configured Gmail mode restored'
@{status='PASS';roles=$results;csrfRetained=$true;anonymousDenied=$true;restrictedRecordDenied=$true;gmailModeRestored=$true;origin='http://localhost:3000'}|ConvertTo-Json -Depth 6|Set-Content D:\Git\nhrc-grants-platform\docs\testing\EMAIL_SAME_ORIGIN_ACCEPTANCE.json -Encoding utf8
Write-Output "PASS All $($results.Count) role sessions and inboxes through the deployed web address"
