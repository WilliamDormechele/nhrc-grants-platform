$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$results=[System.Collections.Generic.List[object]]::new()
function Check($condition,$label){if(-not $condition){throw $label};$results.Add(@{check=$label;passed=$true});Write-Output "PASS $label"}
$status=Invoke-RestMethod http://127.0.0.1:8080/api/public/status
Check ($status.version-eq '0.3.0' -and $status.service-eq 'NHRC eGMS API') 'API release branding and version'
$delivery=Call SUPERADMIN '/api/operations/communications/email-delivery'
Check ($delivery.mode-eq 'LOCAL_CAPTURE' -and -not $delivery.configured -and $delivery.sender-eq 'williamdormechele@gmail.com') 'Gmail remains paused until authorised connection'
Check ($delivery.addressesToUpdate-gt 0 -and $delivery.dailyLimit-eq 450) 'Test addresses identified and Gmail sending budget exposed'
Check (-not ($delivery.PSObject.Properties.Name -contains 'encrypted_password') -and -not ($delivery.PSObject.Properties.Name -contains 'appPassword')) 'Email setup never returns secrets'
foreach($a in $accounts|Where-Object role -ne 'SUPERADMIN'){
 $null=Call $a.role '/api/operations/communications/email-delivery' GET $null 403
 $null=Call $a.role '/api/operations/communications/email-delivery/mode' PUT @{enabled=$true;reason='UAT denial test'} 403
 Check $true ($a.role+' cannot inspect or enable Gmail settings')
}
$null=Call SUPERADMIN '/api/operations/communications/email-delivery/mode' PUT @{enabled=$true;reason='UAT missing connection test'} 409
Check $true 'Live email cannot be enabled before Gmail verification'
$null=Call SUPERADMIN '/api/operations/communications/email-delivery/connect' POST @{appPassword='invalid';reason='UAT validation test'} 400
Check $true 'Invalid app password rejected before network use'
$null=Call SUPERADMIN '/api/operations/communications/email-delivery/mode' PUT @{enabled=$false;reason='UAT confirms live email remains paused'}
Check $true 'Superadmin can keep local UAT capture enabled'
$sa=SignIn SUPERADMIN
$csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $sa.cookie
$bytes=[Text.Encoding]::UTF8.GetBytes("NHRC eGMS acceptance evidence`nCafé - £100 and €200`nOriginal evidence retained")
$fixture='D:\Git\nhrc-grants-platform\.local\release03-evidence.txt'
[IO.File]::WriteAllBytes($fixture,$bytes)
$upload=Invoke-RestMethod http://127.0.0.1:8080/api/operations/documents -Method POST -WebSession $sa.cookie -Headers @{$csrf.headerName=$csrf.token} -Form @{file=Get-Item -LiteralPath $fixture}
$pdfPath='D:\Git\nhrc-grants-platform\docs\testing\RELEASE03_DOWNLOADED_DOCUMENT.pdf'
$response=Invoke-WebRequest "http://127.0.0.1:8080/api/operations/documents/$($upload.id)" -WebSession $sa.cookie -OutFile $pdfPath -PassThru
Check ($response.Headers['Content-Type']-match 'application/pdf') 'Text document downloads use PDF media type'
$pdf=[IO.File]::ReadAllBytes($pdfPath)
Check ([Text.Encoding]::ASCII.GetString($pdf[0..4])-eq '%PDF-') 'Downloaded document has a real PDF signature'
Check ($response.Headers['Content-Disposition']-match 'release03-evidence.pdf') 'PDF download uses the correct filename'
$original='D:\Git\nhrc-grants-platform\.local\release03-original.txt'
$response=Invoke-WebRequest "http://127.0.0.1:8080/api/operations/documents/$($upload.id)?format=original" -WebSession $sa.cookie -OutFile $original -PassThru
Check ((Get-FileHash $fixture).Hash-eq (Get-FileHash $original).Hash) 'Original upload remains byte-for-byte unchanged'
$null=Call RESEARCHER "/api/operations/documents/$($upload.id)" GET $null 403
Check $true 'Unrelated researcher cannot download another user document'
foreach($file in @(@{path=$pdfPath;mime='application/pdf';suffix='pdf'},@{path=$fixture;mime='text/plain';suffix='txt'})){
 $csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $sa.cookie
 $u=Invoke-RestMethod http://127.0.0.1:8080/api/operations/documents -Method POST -WebSession $sa.cookie -Headers @{$csrf.headerName=$csrf.token} -Form @{file=Get-Item -LiteralPath $file.path}
 $dest="D:\Git\nhrc-grants-platform\.local\release03-roundtrip.$($file.suffix)"
 $res=Invoke-WebRequest "http://127.0.0.1:8080/api/operations/documents/$($u.id)?format=original" -WebSession $sa.cookie -OutFile $dest -PassThru
 Check ((Get-FileHash $dest).Hash-eq (Get-FileHash $file.path).Hash -and $res.Headers['Content-Type']-match $file.mime) ($file.suffix+' upload/download preserves format and bytes')
}
$name='Release 03 Contact Acceptance'
$users=Call SUPERADMIN '/api/operations/accounts'
$user=@($users.users|Where-Object display_name -eq $name)[0]
if(-not $user){$u=Call SUPERADMIN '/api/operations/accounts' POST @{email='release03-contact@nhrc.local';displayName=$name;roles=@('RESEARCHER');reason='Local contact-edit acceptance test'};$user=@{id=$u.id}}
$null=Call SUPERADMIN "/api/operations/accounts/$($user.id)" PUT @{email='release03-contact@example.org';displayName=$name;jobTitle='UAT fixture';roles=@('RESEARCHER');active=$false;reason='Verify audited email editing; disable test account'}
$users=Call SUPERADMIN '/api/operations/accounts'
$updated=@($users.users|Where-Object id -eq $user.id)[0]
Check ($updated.email-eq 'release03-contact@example.org' -and -not $updated.active -and $updated.job_title-eq 'UAT fixture') 'Account email and profile edits persist; test account disabled'
$null=Call SUPERADMIN "/api/operations/accounts/$($user.id)" PUT @{email='invalid';roles=@('RESEARCHER');active=$false;reason='Validation test'} 400
Check $true 'Invalid account email rejected'
$null=Call SUPERADMIN "/api/operations/accounts/$($sa.user.id)" PUT @{roles=@('SUPERADMIN');active=$true;reason='Self access guard test'} 409
Check $true 'Independent administrator self-access guard remains enforced'
@{status='PASS';version='0.3.0';checks=$results;liveGmailVerified=$false;note='Google account authentication still required; no live emails sent';completedAt=(Get-Date).ToUniversalTime().ToString('o')}|ConvertTo-Json -Depth 8|Set-Content D:\Git\nhrc-grants-platform\docs\testing\RELEASE03_ACCEPTANCE.json -Encoding utf8
Write-Output "Release acceptance: $($results.Count) checks passed"
