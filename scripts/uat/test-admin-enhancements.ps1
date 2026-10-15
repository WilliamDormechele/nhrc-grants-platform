$ErrorActionPreference='Stop'
. "$PSScriptRoot/test_helpers.ps1"
$checks=[System.Collections.Generic.List[string]]::new()
function Check($condition,[string]$name){if(-not $condition){throw $name};$checks.Add($name);Write-Output "PASS $name"}
function Roles($value){if($value -is [array]){return @($value)};if($value.value){return @($value.value|ConvertFrom-Json)};return @($value|ConvertFrom-Json)}
$sa=SignIn SUPERADMIN
Check ($sa.user.groups.Count-eq 12) 'Superadmin has all 12 workspace groups'
$catalog=Call SUPERADMIN /api/operations/catalog
foreach($d in $catalog){$null=Call SUPERADMIN "/api/operations/$($d.key)?size=1"; $checks.Add("Superadmin reads $($d.key)")}
Check (@((Call SUPERADMIN /api/operations/form-visibility).hiddenPages).Count-eq 0) 'Superadmin is not hidden by form reader policies'
$accountsData=Call SUPERADMIN /api/operations/accounts
Check (@($accountsData.roles|Where-Object code -eq SUPERADMIN).Count-eq 1) 'Superadmin can assign technical roles'
Check (@((Call ADMIN /api/operations/accounts).roles|Where-Object code -eq SUPERADMIN).Count-eq 0) 'Business Administrator cannot assign Superadmin'
$null=Call SUPERADMIN /api/finance/receipts POST @{} 403
$null=Call SUPERADMIN /api/approvals/00000000-0000-0000-0000-000000000001/decision POST @{} 403
$null=Call SUPERADMIN /api/pregrant/applications/00000000-0000-0000-0000-000000000001/submission POST @{} 403
Check $true 'Superadmin alone has no finance, approval or submission authority'
$null=Call SUPERADMIN "/api/operations/accounts/$($sa.user.id)" PUT @{roles=@('SUPERADMIN');active=$true;reason='Synthetic self-access rejection'} 409
$null=Call ADMIN "/api/operations/accounts/$($sa.user.id)" PUT @{roles=@('RESEARCHER');active=$false;reason='Synthetic privileged-account protection'} 403
Check $true 'Self-access changes and Business Administrator changes to privileged accounts are blocked'
$stamp=Get-Date -Format yyyyMMddHHmmss
$user=Call SUPERADMIN /api/operations/accounts POST @{email="uat-multirole-$stamp@nhrc.local";displayName="UAT multi-role $stamp";roles=@('GRANTS_OFFICER','RESEARCHER','IT_ADMIN');reason='Synthetic multi-role acceptance test'}
try {
 $record=@((Call SUPERADMIN /api/operations/accounts).users|Where-Object id -eq $user.id)[0]
 Check (($record.role_codes-split ',').Count-eq 3) 'Multiple business and technical roles save together'
 $null=Call SUPERADMIN "/api/operations/accounts/$($user.id)" PUT @{roles=@('SUPERADMIN','FINANCE_APPROVER');active=$true;reason='Synthetic revised role set'}
 $record=@((Call SUPERADMIN /api/operations/accounts).users|Where-Object id -eq $user.id)[0]
 Check (($record.role_codes-split ',').Count-eq 2 -and $record.role_codes-match 'SUPERADMIN') 'Superadmin can replace the assigned role set'
}finally{$null=Call SUPERADMIN "/api/operations/accounts/$($user.id)" PUT @{roles=@();active=$false;reason='Disable completed synthetic test account'}}
$config=Call SUPERADMIN /api/operations/communications
Check ($config.superadmin -and $config.policies.Count-eq 13) 'Superadmin controls all lifecycle communications policies'
$settings=@{automaticReminders=$config.settings.automatic_reminders;lookaheadDays=$config.settings.lookahead_days;repeatDays=$config.settings.repeat_days;events=@($config.policies|ForEach-Object {@{eventCode=$_.event_code;inApp=$_.in_app;email=$_.email;recipientRoles=@(Roles $_.recipient_roles)}});senders=@($config.senders|ForEach-Object {@{roleCode=$_.role_code;announcements=$_.announcements;notifications=$_.notifications;reminders=$_.reminders}});reason='Synthetic policy roundtrip'}
$null=Call ADMIN /api/operations/communications/settings PUT $settings 403
$null=Call GRANTS_OFFICER /api/operations/communications/broadcasts POST @{title='UAT denied';message='Synthetic blocked broadcast';category='ANNOUNCEMENT';audienceRoles=@('RESEARCHER');reason='Permission probe'} 403
Check $true 'Non-Superadmin policy edits and unpermitted announcements are denied'
$null=Call SUPERADMIN /api/operations/communications/settings PUT $settings
Check $true 'Lifecycle checkboxes and reminder timing save successfully'
$broadcast=Call SUPERADMIN /api/operations/communications/broadcasts POST @{title="UAT communication $stamp";message='Synthetic acceptance check. No real action is required.';category='ANNOUNCEMENT';audienceRoles=@('RESEARCHER');reason='Synthetic notification and SMTP verification'}
Check ($broadcast.recipients-ge 1) 'Role-targeted announcement has eligible recipients'
$research=Call RESEARCHER /api/operations/communications
$finance=Call FINANCE_OFFICER /api/operations/communications
Check (@($research.broadcasts|Where-Object id -eq $broadcast.id).Count-eq 1 -and @($finance.broadcasts|Where-Object id -eq $broadcast.id).Count-eq 0) 'Announcement visibility follows audience roles'
$personal=Call RESEARCHER /api/operations/personal
Check (@($personal.notifications|Where-Object title -eq "UAT communication $stamp").Count-eq 1) 'Announcement is delivered to the in-app inbox'
$task=@($config.tasks|Where-Object {$_.kind-eq 'APPLICATION' -and $_.owner_id}|Select-Object -First 1)[0]
if($task){
 $first=Call SUPERADMIN /api/operations/communications/remind POST @{kind=$task.kind;id=$task.id;reason='Synthetic manual incomplete-task reminder'}
 $second=Call SUPERADMIN /api/operations/communications/remind POST @{kind=$task.kind;id=$task.id;reason='Synthetic duplicate suppression'}
 Check ($second.recipients-eq 0) 'Manual reminders suppress duplicates within five minutes'
}
# Temporarily grant a sender capability, verify actual use, then restore the complete policy.
try {
 $delegated=($settings|ConvertTo-Json -Depth 20|ConvertFrom-Json -AsHashtable)
 @($delegated.senders|Where-Object roleCode -eq GRANTS_OFFICER)[0].announcements=$true
 $delegated.reason='Synthetic sender checkbox test'
 $null=Call SUPERADMIN /api/operations/communications/settings PUT $delegated
 $sent=Call GRANTS_OFFICER /api/operations/communications/broadcasts POST @{title="UAT delegated announcement $stamp";message='Synthetic delegated sender acceptance test.';category='ANNOUNCEMENT';audienceRoles=@('GRANTS_OFFICER');reason='Synthetic enabled role sends announcement'}
 Check ($sent.recipients-ge 1) 'Ticked sender role can send an announcement'
 $null=Call SUPERADMIN "/api/operations/communications/broadcasts/$($sent.id)/withdraw" POST @{reason='Synthetic test completed'}
} finally {$settings.reason='Restore original communication sender permissions';$null=Call SUPERADMIN /api/operations/communications/settings PUT $settings}
$null=Call SUPERADMIN "/api/operations/communications/broadcasts/$($broadcast.id)/withdraw" POST @{reason='Synthetic test completed'}
Check (@((Call RESEARCHER /api/operations/communications).broadcasts|Where-Object id -eq $broadcast.id).Count-eq 0) 'Withdrawn announcement is no longer visible'
$analytics=Call SUPERADMIN /api/operations/analytics
Check ($null-ne $analytics.pipeline -and $null-ne $analytics.portfolio -and $null-ne $analytics.finance) 'Analytics exposes separate application, award and currency datasets'
$none=Call SUPERADMIN '/api/operations/analytics?q=NO-SUCH-GRANT-UAT-999999'
Check (@($none.pipeline).Count-eq 0 -and @($none.portfolio).Count-eq 0) 'Live grant search filters applications and awards'
$filtered=Call SUPERADMIN '/api/operations/analytics?currency=USD&year=2026'
Check (@($filtered.finance|Where-Object currency -ne USD).Count-eq 0) 'Analytics keeps currencies separate and supports year filtering'
$pi=Call RESEARCHER /api/operations/analytics
Check ($pi.scope-eq 'Your assigned grants') 'Researcher analytics retains assigned-record scope'
$audit=Call SUPERADMIN '/api/admin/audit?q=COMMUNICATION_POLICY_CHANGED'
Check (@($audit|Where-Object action -eq COMMUNICATION_POLICY_CHANGED).Count-ge 1) 'Audit console includes named communication policy changes'
@{status='PASS';verifiedAt=(Get-Date).ToUniversalTime().ToString('o');checks=$checks;catalogReads=$catalog.Count;testUser=$user.id}|ConvertTo-Json -Depth 8|Set-Content "$root/docs/testing/ADMIN_ENHANCEMENTS_RESULTS.json"
Write-Output "PASS $($checks.Count) administrator, communications and analytics checks"
