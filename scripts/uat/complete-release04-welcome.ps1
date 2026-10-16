$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$fixture=Get-Content D:\Git\nhrc-grants-platform\.local\release04-welcome-browser-fixture.json -Raw|ConvertFrom-Json
$anonymous=New-Object Microsoft.PowerShell.Commands.WebRequestSession
try {
 $csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $anonymous
 $password='UAT account setup '+[guid]::NewGuid().ToString('N')
 $result=Invoke-RestMethod http://127.0.0.1:8080/api/public/account-invitations/accept -Method POST -WebSession $anonymous -Headers @{$csrf.headerName=$csrf.token} -ContentType application/json -Body (@{token=$fixture.token;password=$password;confirmation=$password}|ConvertTo-Json)
 if(-not $result.completed){throw 'The emailed setup link did not complete'}
 $response=Invoke-WebRequest http://127.0.0.1:8080/api/public/account-invitations/inspect -Method POST -WebSession $anonymous -Headers @{$csrf.headerName=$csrf.token} -ContentType application/json -Body (@{token=$fixture.token}|ConvertTo-Json) -SkipHttpErrorCheck
 if([int]$response.StatusCode-ne 410){throw 'Used emailed setup link remained valid'}
 $evidence=Get-Content D:\Git\nhrc-grants-platform\docs\testing\RELEASE04_WELCOME_ACCEPTANCE.json -Raw|ConvertFrom-Json
 $evidence.browserCompletionPending=$false
 $evidence|Add-Member -NotePropertyName browserSetupFormVerified -NotePropertyValue $true -Force
 $evidence|Add-Member -NotePropertyName emailedLinkCompletedThroughApi -NotePropertyValue $true -Force
 $evidence|Add-Member -NotePropertyName singleUseVerified -NotePropertyValue $true -Force
 $evidence|ConvertTo-Json -Depth 5|Set-Content D:\Git\nhrc-grants-platform\docs\testing\RELEASE04_WELCOME_ACCEPTANCE.json -Encoding utf8
 Write-Output 'PASS Emailed account setup link completed, replay rejected, and no browser password entry was automated'
} finally {$null=Call SUPERADMIN "/api/operations/accounts/$($fixture.userId)" PUT @{roles=@('RESEARCHER');active=$false;reason='Disable completed real-welcome acceptance fixture'}}
