$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$fixture=Get-Content D:\Git\nhrc-grants-platform\.local\scheduled-email-acceptance.json -Raw|ConvertFrom-Json
try{
 $inbox=Call POST_AWARD_OFFICER '/api/notifications/inbox'
 $notice=@($inbox.notifications|Where-Object {$_.entity_id-eq $fixture.id-and $_.type-eq 'REMINDER'})[0]
 Assert ($null-ne $notice) 'Real scheduler addressed the reminder to the named task owner'
 Assert ($notice.route.record-eq $fixture.id) 'Scheduled reminder opens the exact deliverable'
 $messages=Invoke-RestMethod 'http://127.0.0.1:8026/api/v1/messages?limit=200'
 $found=$null
 foreach($m in @($messages.messages|Where-Object Subject -like '*UAT scheduled email acceptance*')){$mail=Invoke-RestMethod "http://127.0.0.1:8026/api/v1/message/$($m.ID)";if($mail.Text.Contains([string]$notice.id)){$found=$mail;break}}
 Assert ($null-ne $found) 'Scheduled reminder delivered through SMTP capture'
 Assert (@($found.To|ForEach-Object {$_.PSObject.Properties['Address'].Value})-contains (SignIn POST_AWARD_OFFICER).user.email) 'Scheduled email recipient matches the named task owner'
 Assert ($found.Text.Contains('record='+$fixture.id)) 'Scheduled email contains the exact-record URL'
 $record=Call POST_AWARD_OFFICER "/api/operations/deliverables/$($fixture.id)"
 if($record._actions-contains 'CANCELLED'){$null=Call POST_AWARD_OFFICER "/api/operations/deliverables/$($fixture.id)/action" POST @{state='CANCELLED';etag=$record._etag;note='Synthetic automatic reminder acceptance completed; cancel test-only task'} }
 $report=Get-Content D:\Git\nhrc-grants-platform\docs\testing\EMAIL_WORKFLOW_ACCEPTANCE.json -Raw|ConvertFrom-Json
 $report|Add-Member scheduledReminderVerified $true -Force
 $report|Add-Member scheduledReminderNotification $notice.id -Force
 $report|Add-Member scheduledReminderRecord $fixture.id -Force
 $report|ConvertTo-Json -Depth 10|Set-Content D:\Git\nhrc-grants-platform\docs\testing\EMAIL_WORKFLOW_ACCEPTANCE.json -Encoding utf8
}finally{$null=Call SUPERADMIN '/api/operations/communications/email-delivery/mode' PUT @{enabled=$true;reason='Restore configured Gmail delivery after successful scheduled and manual UAT capture checks'}}
