$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
function Sql([string]$sql){$r=$sql|docker exec -i nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -At -v ON_ERROR_STOP=1;if($LASTEXITCODE-ne 0){throw 'Mail scope fixture operation failed'};return $r}
$fixture=Get-Content D:\Git\nhrc-grants-platform\.local\release04-browser-fixture.json -Raw|ConvertFrom-Json
$officer=SignIn GRANTS_OFFICER;$application=(Sql 'select json_build_object(''id'',id,''reference'',reference,''title'',title)::text from applications order by created_at desc limit 1')|ConvertFrom-Json
$stamp=Get-Date -Format yyyyMMddHHmmss;$allowId=[guid]::NewGuid().ToString();$denyId=[guid]::NewGuid().ToString();$allowTitle="UAT authorised email detail $stamp";$denyTitle="UAT restricted email detail $stamp"
$null=Call SUPERADMIN "/api/operations/accounts/$($fixture.userId)" PUT @{roles=@('RESEARCHER');active=$true;reason='Temporary activation of reserved-address mail-scope fixture'}
try {
 $null=Sql "begin; insert into notifications(id,user_id,type,title,message,entity_type,entity_id) values ('$allowId','$($officer.user.id)','UAT_SCOPE','$allowTitle','Synthetic scope acceptance message. No business action is required.','applications','$($application.id)'),('$denyId','$($fixture.userId)','UAT_SCOPE','$denyTitle','Synthetic scope acceptance message. No business action is required.','applications','$($application.id)'); insert into email_outbox(notification_id,recipient,subject,body) select id,'capture-scope@example.test',title,message from notifications where id in ('$allowId','$denyId'); update email_outbox set delivery_mode='LOCAL_CAPTURE' where notification_id in ('$allowId','$denyId'); commit;"
 $positive=$null;$negative=$null
 for($i=0;$i-lt 35;$i++){$list=Invoke-RestMethod http://127.0.0.1:8026/api/v1/messages;$positive=@($list.messages|Where-Object Subject -eq "NHRC eGMS | $allowTitle")[0];$negative=@($list.messages|Where-Object Subject -eq "NHRC eGMS | $denyTitle")[0];if($positive-and $negative){break};Start-Sleep -Seconds 1}
 if(-not $positive-or -not $negative){throw 'Scoped mail capture did not complete'}
 $p=Invoke-RestMethod "http://127.0.0.1:8026/api/v1/message/$($positive.ID)";$n=Invoke-RestMethod "http://127.0.0.1:8026/api/v1/message/$($negative.ID)"
 if(-not $p.Text.Contains($application.reference)-or -not $p.Text.Contains($application.title)){throw 'Authorised officer email lacks entitled record details'}
 if($n.Text.Contains($application.reference)-or $n.Text.Contains($application.title)){throw 'Unassigned researcher email disclosed record details'}
 if(-not $n.Text.Contains($denyId)){throw 'Restricted enrichment lost the recipient-owned notification link'}
 @{status='PASS';authorisedOfficerDetailsIncluded=$true;unassignedResearcherDetailsExcluded=$true;ownedNotificationLinkRetained=$true;externalEmailsSent=0}|ConvertTo-Json|Set-Content D:\Git\nhrc-grants-platform\docs\testing\RELEASE04_MAIL_SCOPE.json -Encoding utf8
 Write-Output 'PASS Role and assigned-record email enrichment, with owned notification links preserved'
} finally {
 $null=Sql "update notifications set in_app_visible=false,read_at=coalesce(read_at,now()) where id in ('$allowId','$denyId') and type='UAT_SCOPE';"
 $null=Call SUPERADMIN "/api/operations/accounts/$($fixture.userId)" PUT @{roles=@('RESEARCHER');active=$false;reason='Disable completed reserved-address mail-scope fixture'}
}
