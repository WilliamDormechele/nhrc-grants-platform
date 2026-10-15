$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$checks=[Collections.Generic.List[string]]::new()
function Check($ok,[string]$name){if(-not $ok){throw $name};$checks.Add($name);Write-Output "PASS $name"}
function Sql([string]$sql){$result=$sql | docker exec -i nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -At -v ON_ERROR_STOP=1;if($LASTEXITCODE-ne 0){throw 'Fixture database operation failed'};return $result}
$sa=SignIn SUPERADMIN
$mode=Call SUPERADMIN '/api/operations/communications/email-delivery'
Check ($mode.mode-eq 'GMAIL' -and $mode.configured) 'Enabled Gmail connection is preserved'
foreach($kind in @('notification','onboarding')){
 $p=Call SUPERADMIN "/api/operations/communications/email-delivery/preview?kind=$kind"
 Check ($p.html.Contains('data:image/png;base64,') -and $p.html.Contains('What to do next') -and $p.plain.Contains('http://localhost:3000')) "$kind preview has branding, next steps and a plain-text alternative"
}
foreach($a in $accounts|Where-Object role -ne SUPERADMIN){$null=Call $a.role '/api/operations/communications/email-delivery/preview?kind=onboarding' GET $null 403;Check $true ($a.role+' cannot access Superadmin email previews')}
foreach($a in $accounts){
 $own=SignIn $a.role;$nid=[guid]::NewGuid().ToString()
 $null=Sql "insert into notifications(id,user_id,type,title,message) values ('$nid','$($own.user.id)','UAT_ACK','UAT acknowledgment check','Synthetic personal inbox check');"
 try {$null=Call $a.role "/api/notifications/$nid/read" POST @{};$box=Call $a.role "/api/notifications/inbox?focus=$nid";Check (@($box.notifications|Where-Object {$_.id-eq $nid -and $_.read_at}).Count-eq 1) ($a.role+' can acknowledge its own notification')}
 finally {$null=Sql "delete from notifications where id='$nid' and type='UAT_ACK';"}
}
$config=Call SUPERADMIN '/api/operations/communications'
Check (@($config.policies|Where-Object event_code -eq ACCOUNT).Count-eq 1) 'Onboarding email has a dedicated configurable lifecycle policy'
$stamp=Get-Date -Format yyyyMMddHHmmss
$tag="UAT_INBOX_$stamp";$focus=[guid]::NewGuid().ToString();$unread=(Call SUPERADMIN '/api/notifications/unread-count').unread
$null=Sql "insert into notifications(id,user_id,type,title,message,created_at) values ('$focus','$($sa.user.id)','$tag','UAT notification navigation check','Synthetic acceptance message. No business action is required.',now()-interval '10 years'); insert into notifications(user_id,type,title,message) select '$($sa.user.id)','$tag','UAT inbox boundary fixture','Synthetic acceptance fixture' from generate_series(1,100);"
try {
 $count=Call SUPERADMIN '/api/notifications/unread-count'
 Check ($count.unread-eq ($unread+101)) 'Unread bell counts all messages beyond the latest-page limit'
 $inbox=Call SUPERADMIN "/api/notifications/inbox?focus=$focus"
 Check (@($inbox.notifications|Where-Object id -eq $focus).Count-eq 1 -and $inbox.notifications.Count-eq 101) 'An owned notification outside the latest 100 is retrieved by its direct link'
 $other=Call RESEARCHER "/api/notifications/inbox?focus=$focus"
 Check (@($other.notifications|Where-Object id -eq $focus).Count-eq 0) 'Direct notification links do not disclose another user message'
 $null=Call RESEARCHER "/api/notifications/$focus/read" POST @{} 404
 $null=Call RESEARCHER "/api/notifications/$($sa.user.id)" GET $null 403
 Check $true 'Cross-account notification reads and mutations are denied'
 $null=Call SUPERADMIN "/api/notifications/$focus/read" POST @{}
 Check ((Call SUPERADMIN '/api/notifications/unread-count').unread-eq ($unread+100)) 'Mark as read updates the exact unread count'
 $latest=@($inbox.notifications|Where-Object id -ne $focus)[0].id;$null=Call SUPERADMIN "/api/notifications/$latest/read" POST @{}
 $onlyUnread=Call SUPERADMIN '/api/notifications/inbox?unreadOnly=true'
 Check (@($onlyUnread.notifications|Where-Object {$_.read_at -or $_.id-eq $latest}).Count-eq 0) 'Unread filtering queries the full inbox and excludes read messages'

} finally {$null=Sql "delete from notifications where type='$tag' and user_id='$($sa.user.id)';"}
$user=Call SUPERADMIN '/api/operations/accounts' POST @{email="onboarding-uat-$stamp@example.test";displayName="UAT Onboarding $stamp";roles=@('RESEARCHER');reason='Reserved-address onboarding acceptance fixture'}
$uid=$user.id;$tokens=@{}
foreach($kind in @('valid','expired','revoked','wrong_email')){
 $random=[byte[]]::new(32);[Security.Cryptography.RandomNumberGenerator]::Fill($random);$token=[Convert]::ToBase64String($random).TrimEnd('=').Replace('+','-').Replace('/','_');$tokens[$kind]=$token
 $digest=[Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($token))).ToLowerInvariant()
 $expiry=if($kind-eq 'expired'){"now()-interval '1 hour'"}else{"now()+interval '72 hours'"};$revoked=if($kind-eq 'revoked'){'now()'}else{'null'};$email=if($kind-eq 'wrong_email'){'different@example.test'}else{$user.email}
 $null=Sql "insert into account_invitations(user_id,email_at_issue,token_hash,encrypted_token,created_by,expires_at,revoked_at) values ('$uid','$email','$digest','acceptance-fixture-not-for-email','$($sa.user.id)',$expiry,$revoked);"
}
$anon=New-Object Microsoft.PowerShell.Commands.WebRequestSession
function Public([string]$path,$body,[int]$expected=200){$c=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $anon;$res=Invoke-WebRequest "http://127.0.0.1:8080/api/public/account-invitations/$path" -Method POST -WebSession $anon -Headers @{$c.headerName=$c.token} -ContentType application/json -Body ($body|ConvertTo-Json -Compress) -SkipHttpErrorCheck;if([int]$res.StatusCode-ne $expected){throw "Public invitation $path expected $expected, received $($res.StatusCode): $($res.Content)"};if($res.Content){return $res.Content|ConvertFrom-Json}}
try {
 $info=Public inspect @{token=$tokens.valid};Check ($info.name-eq "UAT Onboarding $stamp") 'Valid invitation opens the intended named account'
 foreach($kind in @('expired','revoked','wrong_email')){$null=Public inspect @{token=$tokens[$kind]} 410;Check $true ("$kind invitation cannot be used")}
 $null=Public accept @{token=$tokens.valid;password='Short';confirmation='Short'} 400
 $null=Public accept @{token=$tokens.valid;password='A long acceptance password';confirmation='Different confirmation'} 400
 Check $true 'Account setup rejects weak passwords and mismatched confirmation'
 $password='UAT-only acceptance '+[guid]::NewGuid().ToString('N')
 $completed=Public accept @{token=$tokens.valid;password=$password;confirmation=$password}
 Check $completed.completed 'A valid setup link completes password creation'
 $null=Public accept @{token=$tokens.valid;password=$password;confirmation=$password} 410
 Check $true 'A used setup link cannot be replayed'
 $session=New-Object Microsoft.PowerShell.Commands.WebRequestSession;$csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $session
 $null=Invoke-RestMethod http://127.0.0.1:8080/api/auth/login -Method POST -WebSession $session -Headers @{$csrf.headerName=$csrf.token} -Body @{username=$user.email;password=$password} -ContentType application/x-www-form-urlencoded
 $me=Invoke-RestMethod http://127.0.0.1:8080/api/auth/me -WebSession $session
 Check ($me.id-eq $uid -and $me.roles.Count-eq 1 -and $me.roles[0]-eq 'RESEARCHER') 'The chosen password signs in with only the assigned role'
} finally {$null=Call SUPERADMIN "/api/operations/accounts/$uid" PUT @{roles=@('RESEARCHER');active=$false;reason='Disable completed reserved-address onboarding fixture'}}
# SMTP format verification is explicitly forced to the private capture service, without changing Gmail settings.
$hostName=(docker exec nhrc-grants-api printenv SPRING_MAIL_HOST).Trim()
if($hostName-ne 'mailpit'){throw 'Capture format test refused: local capture transport is not Mailpit'}
$mailId=[guid]::NewGuid().ToString();$title="UAT email layout acceptance $stamp"
$null=Sql "begin; insert into notifications(id,user_id,type,title,message) values ('$mailId','$uid','UAT_FORMAT','$title','Synthetic format verification. No business action is required.'); insert into email_outbox(notification_id,recipient,subject,body) values ('$mailId','capture-recipient@example.test','$title','Synthetic format verification'); update email_outbox set delivery_mode='LOCAL_CAPTURE' where notification_id='$mailId'; commit;"
$mail=$null
for($i=0;$i-lt 40;$i++){$messages=Invoke-RestMethod http://127.0.0.1:8026/api/v1/messages;$mail=@($messages.messages|Where-Object Subject -eq "NHRC eGMS | $title")[0];if($mail){break};Start-Sleep -Seconds 1}
if(-not $mail){throw 'Branded capture email was not delivered'}
$detail=Invoke-RestMethod "http://127.0.0.1:8026/api/v1/message/$($mail.ID)"
Check ($detail.HTML.Contains('NHRC eGMS') -and $detail.HTML.Contains('<table')) 'Actual captured SMTP message contains the branded HTML layout'
Check ($detail.Text.Contains('View this notification') -and $detail.Text.Contains($mailId)) 'Actual captured SMTP message includes a readable plain-text alternative and exact direct link'
Check ($detail.HTML.Contains('cid:nhrc-logo')) 'Actual captured SMTP message references the inline institutional logo'
$after=Call SUPERADMIN '/api/operations/communications/email-delivery'
Check ($after.mode-eq $mode.mode -and $after.configured) 'Gmail remains enabled after every acceptance check'
$browserId=[guid]::NewGuid().ToString()
$null=Sql "insert into notifications(id,user_id,type,title,message) values ('$browserId','$($sa.user.id)','UAT_BROWSER','UAT notification navigation check','Synthetic usability check. Mark this message as read after checking the notification bell.');"
@{notificationId=$browserId;userId=$uid;mode=$after.mode}|ConvertTo-Json|Set-Content D:\Git\nhrc-grants-platform\.local\release04-browser-fixture.json -Encoding utf8
@{status='PASS';checks=$checks;externalTestEmailsSent=0;gmailMode=$after.mode;completedAt=(Get-Date).ToUniversalTime().ToString('o')}|ConvertTo-Json -Depth 6|Set-Content D:\Git\nhrc-grants-platform\docs\testing\RELEASE04_ACCEPTANCE.json -Encoding utf8
Write-Output "PASS $($checks.Count) release acceptance checks"
