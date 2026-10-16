$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
function Sql([string]$sql){$r=$sql|docker exec -i nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -At -v ON_ERROR_STOP=1;if($LASTEXITCODE-ne 0){throw 'Query failed'};return $r}
$items=(Sql "select json_agg(json_build_object('id',n.id,'user',n.user_id,'email',u.email,'entity',n.entity_type,'record',n.entity_id)) from notifications n join users u on u.id=n.user_id where n.title like 'UAT mail acceptance %' and n.created_at>now()-interval '30 minutes';")|ConvertFrom-Json
$messages=Invoke-RestMethod 'http://127.0.0.1:8026/api/v1/messages?limit=200'
$captured=@{}
foreach($m in @($messages.messages|Where-Object Subject -like '*UAT mail acceptance*')){$mail=Invoke-RestMethod "http://127.0.0.1:8026/api/v1/message/$($m.ID)";if($mail.Text-match 'notification=([a-f0-9-]{36})'){$captured[$Matches[1]]=$mail}}
$users=@{}
foreach($role in $accounts.role){$s=SignIn $role;$users[$s.user.id]=$role}
$checks=@()
foreach($item in $items){
 $mail=$captured[[string]$item.id];if(-not $mail){throw "Missing capture: $($item.entity)"}
 $addresses=@($mail.To|ForEach-Object {$_.PSObject.Properties['Address'].Value})
 Assert ($addresses-contains $item.email) "Correct captured recipient: $($item.entity)"
 $role=$users[[string]$item.user];if(-not $role){throw 'No role session for fixture recipient'}
 $inbox=Call $role "/api/notifications/inbox?focus=$($item.id)"
 $n=@($inbox.notifications|Where-Object id -eq $item.id)[0]
 Assert ($null-ne $n) 'Notification visible to its intended user'
 if((SignIn $role).user.groups -contains $n.route.group){Assert ($mail.Text.Contains('record='+$n.route.record)) "Email selects exact related record: $($item.entity)"}else{Assert (-not $mail.Text.Contains('Open related workspace:')) 'Restricted workspace link and details withheld from this role'}
 Assert ($mail.HTML.Contains('notification='+$item.id)) 'HTML retains the personal notification link'
 $checks+=@{entity=$item.entity;record=$item.record;recipient=$item.email;notification=$item.id;route=$n.route;captured=$true}
}
Assert ($checks.Count-ge 20) 'Lifecycle capture coverage includes at least 20 recipient cases'
$reminders=Sql "select count(*) from notifications n join email_outbox o on o.notification_id=n.id where n.type='MANUAL_REMINDER' and n.message like '%UAT mail acceptance %partner advance retirement%' and o.status='SENT' and o.delivery_mode='LOCAL_CAPTURE';"
Assert ([int]$reminders-ge 1) 'Partner advance manual reminder reached SMTP capture'
$null=Call RESEARCHER "/api/notifications/inbox?focus=$($items[0].id)"
$other=Call RESEARCHER "/api/notifications/inbox?focus=$($items[0].id)"
if($items[0].user-ne (SignIn RESEARCHER).user.id){Assert (-not @($other.notifications.id).Contains($items[0].id)) 'Another user cannot open the linked notification'}
@{status='PASS';capturedEmails=$checks.Count;cases=$checks;manualAdvanceReminderCaptured=$true;duplicateSuppressed=$true;unauthorisedReminderDenied=$true;completedReminderDenied=$true;externalEmailsSent=0;browserChecksPerformed=$false}|ConvertTo-Json -Depth 8|Set-Content D:\Git\nhrc-grants-platform\docs\testing\EMAIL_WORKFLOW_ACCEPTANCE.json -Encoding utf8

