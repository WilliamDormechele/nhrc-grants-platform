. "$PSScriptRoot/test_helpers.ps1"
$defs=Get-Content "$root/apps/api/src/main/resources/operational-catalog.json" -Raw|ConvertFrom-Json
$results=@()
foreach($role in @('DIRECTOR','GRANTS_OFFICER','RESEARCHER','FINANCE_OFFICER','FINANCE_APPROVER','PROCUREMENT_OFFICER','LABORATORY_OFFICER','GOVERNANCE_OFFICER','POST_AWARD_OFFICER','ADMIN','IT_ADMIN','SUPERADMIN','AUDITOR')){
 foreach($d in $defs|Where-Object create){
  if($role -in $d.writers -or ($role -eq 'SUPERADMIN' -and $d.group -in @('Administration','People & Organisation','IT & Security'))){continue}
  $null=Call $role "/api/operations/$($d.key)" POST @{values=@{}} 403
  $results+=@{role=$role;form=$d.key;result='PASS';expectedStatus=403}
 }
}
$results|ConvertTo-Json -Depth 5|Set-Content "$root/docs/testing/FULL_ROLE_WRITE_DENIALS.json"
Write-Output "PASS $($results.Count) non-writer role/form mutations denied across all 13 named roles"
