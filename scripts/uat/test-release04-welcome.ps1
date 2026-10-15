$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$sa=SignIn SUPERADMIN;$stamp=Get-Date -Format yyyyMMddHHmmss
$u=Call SUPERADMIN '/api/operations/accounts' POST @{email="welcome-layout-$stamp@example.test";displayName="UAT Welcome Layout $stamp";roles=@('RESEARCHER');reason='Reserved-address welcome email acceptance fixture'}
function Sql([string]$sql){$res=$sql|docker exec -i nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -At -v ON_ERROR_STOP=1;if($LASTEXITCODE-ne 0){throw 'Welcome fixture operation failed'};return $res}
try {
 $fake=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
 $null=Sql "insert into account_invitations(user_id,email_at_issue,token_hash,encrypted_token,created_by) values ('$($u.id)','$($u.email)','$fake','pending-fixture-only','$($sa.user.id)');"
 $r=Call SUPERADMIN "/api/operations/accounts/$($u.id)/welcome" POST @{reason='Verify real welcome generation and safe capture delivery'}
 if(-not $r.queued){throw 'Welcome email was not queued'}
 # This recipient is reserved and can never receive Gmail delivery; only this synthetic job is sent to capture.
 $null=Sql "update email_outbox o set delivery_mode='LOCAL_CAPTURE',status='QUEUED',attempts=0,next_attempt_at=now() from notifications n where n.id=o.notification_id and n.user_id='$($u.id)' and n.type='ACCOUNT';"
 $message=$null
 for($i=0;$i-lt 35;$i++){$list=Invoke-RestMethod http://127.0.0.1:8026/api/v1/messages;$message=@($list.messages|Where-Object {$_.Subject-eq 'NHRC eGMS | Welcome to NHRC eGMS' -and @($_.To|Where-Object Address -eq $u.email).Count-gt 0})[0];if($message){break};Start-Sleep -Seconds 1}
 if(-not $message){throw 'The captured welcome email did not arrive'}
 $mail=Invoke-RestMethod "http://127.0.0.1:8026/api/v1/message/$($message.ID)"
 foreach($text in @('Welcome to NHRC eGMS','Sign-in email','Assigned roles','Setup link expires','Set up my account','Researcher / PI')){if(-not $mail.HTML.Contains($text)){throw "Welcome email missing $text"}}
 $match=[regex]::Match($mail.HTML,'http://localhost:3000/\?activate=([A-Za-z0-9_%\-]+)')
 if(-not $match.Success){throw 'Secure account setup link missing'}
 $token=[uri]::UnescapeDataString($match.Groups[1].Value)
 $anon=New-Object Microsoft.PowerShell.Commands.WebRequestSession;$csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $anon
 $info=Invoke-RestMethod http://127.0.0.1:8080/api/public/account-invitations/inspect -Method POST -WebSession $anon -Headers @{$csrf.headerName=$csrf.token} -ContentType application/json -Body (@{token=$token}|ConvertTo-Json)
 if($info.name-ne "UAT Welcome Layout $stamp"){throw 'Welcome setup link resolves to the wrong account'}
 @{userId=$u.id;token=$token;name=$info.name}|ConvertTo-Json|Set-Content D:\Git\nhrc-grants-platform\.local\release04-welcome-browser-fixture.json -Encoding utf8
 $clean=[regex]::Replace($mail.HTML,'activate=[A-Za-z0-9_%\-]+','activate=redacted-test-token')
 $clean|Set-Content D:\Git\nhrc-grants-platform\docs\testing\RELEASE04_WELCOME_CAPTURE_SANITISED.html -Encoding utf8
 @{status='PASS';checks=@('Admin reissue creates a real encrypted invitation','Welcome message is captured without changing Gmail mode','HTML includes the named account, roles, expiry and next steps','The emailed setup link opens the correct account');externalEmailsSent=0;browserCompletionPending=$true}|ConvertTo-Json -Depth 4|Set-Content D:\Git\nhrc-grants-platform\docs\testing\RELEASE04_WELCOME_ACCEPTANCE.json -Encoding utf8
 Write-Output 'PASS Real welcome email, encrypted setup link and named-account resolution verified through private capture'
} catch {$null=Call SUPERADMIN "/api/operations/accounts/$($u.id)" PUT @{roles=@('RESEARCHER');active=$false;reason='Disable failed welcome acceptance fixture'};throw}
