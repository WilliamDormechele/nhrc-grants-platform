param([switch]$OnlyFailed)
. "$PSScriptRoot/test_helpers.ps1"
$fixture=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
$catalog=Call ADMIN '/api/operations/catalog'
$catalog+=Call IT_ADMIN '/api/operations/catalog'
$catalog=@($catalog|Sort-Object key -Unique)
$stamp=Get-Date -Format 'HHmmss'
$created=@{};$results=[Collections.Generic.List[object]]::new()
$users=@{};foreach($role in @('ADMIN','GRANTS_OFFICER','POST_AWARD_OFFICER','DIRECTOR','RESEARCHER','FINANCE_OFFICER','FINANCE_APPROVER','PROCUREMENT_OFFICER','LABORATORY_OFFICER','GOVERNANCE_OFFICER','IT_ADMIN')){$users[$role]=(SignIn $role).user.id}
function Action($role,$key,$record,$state,$expected=200){return Call $role "/api/operations/$key/$($record.id)/action" POST @{state=$state;etag=$record._etag;note='Synthetic UAT workflow evidence'} $expected}
$order=@('partners','suppliers','funders','workflows','plans','requisitions','orders','subawards','laboratory','expenditure')
$definitions=$catalog|Where-Object {$_.create}|Sort-Object @{Expression={if($_.key -in $order){[array]::IndexOf($order,$_.key)}else{100}}}
if($OnlyFailed){$prior=Get-Content "$root/docs/testing/DEPARTMENT_FORM_RESULTS.json" -Raw|ConvertFrom-Json;$definitions=$definitions|Where-Object {$_.key -in @($prior|Where-Object result -eq FAIL|ForEach-Object key)};$results.AddRange([object[]]@($prior|Where-Object result -eq PASS))}
foreach($d in $definitions){
 try {
 $role=$d.writers[0];$v=@{}
 foreach($f in $d.fields){
  $v[$f.name]=$f.defaultValue
  if($f.required){
   if($f.choices.Count){$v[$f.name]=$f.choices[0]}
   elseif($f.reference){$v[$f.name]=switch($f.reference){'awards'{$fixture.awardId};'applications'{$fixture.applicationId};'users'{$users.RESEARCHER};'roles'{(Call ADMIN '/api/operations/options/roles')[0].id};'entity'{$created.partners.id};'financial_entity'{$created.expenditure.id};'grant_entity'{$fixture.awardId};default{$created[$f.reference].id}}}
   else{$v[$f.name]=switch($f.dbType){'date'{$fixture.startDate};'timestamptz'{(Get-Date).ToUniversalTime().ToString('o')};'numeric'{10};'int4'{1};'jsonb'{@{fields=@(@{name='purpose';label='Institutional purpose';type='textarea';required=$true})}};default{"UAT $($d.key) $stamp"}}}
  }
  if($f.name -eq 'active' -or $f.name -eq 'enabled'){$v[$f.name]=$false}
  if($f.name -eq 'storage_key' -or $f.type -eq 'document'){$v[$f.name]=$fixture.document}
 }
 if($d.fields.name -contains 'currency'){$v.currency='GBP'}
 switch($d.key){
  'forms'{$v.schema_json=@{fields=@(@{name='purpose';label='Institutional purpose';type='textarea';required=$true})}}
  'agreements'{$v.award_id=$fixture.awardId;$v.effective_date=$fixture.startDate;$v.expiry_date=$fixture.endDate}
  'diligence'{$v.findings='Synthetic UAT organisational review';$v.expires_at=$fixture.endDate}
  'orders'{$v.supplier_id=$created.suppliers.id;$v.amount=10}
  'compliance'{$v.award_id=$fixture.awardId}
  'delegations'{$v.delegator_id=$users.GRANTS_OFFICER;$v.delegate_id=$users.POST_AWARD_OFFICER;$v.valid_until=(Get-Date).AddDays(1).ToUniversalTime().ToString('o')}
  'financial-approvals'{$v.assigned_user_id=$users.FINANCE_APPROVER}
  'researchers'{
    $profiles=Call ADMIN '/api/operations/researchers?size=100'
    $unused=@($users.Values|Where-Object {$_ -notin $profiles.rows.user_id})
    if($unused.Count){$v.user_id=$unused[0]}else{
      $existing=@($profiles.rows|Where-Object user_id -eq $users.ADMIN)[0]
      $edited=Call ADMIN "/api/operations/researchers/$($existing.id)" PUT @{values=@{summary='Synthetic UAT profile regression check'};etag=$existing._etag;note='Retest existing UAT account profile without creating duplicate users'}
      $null=Call ADMIN "/api/operations/researchers/$($existing.id)" PUT @{values=@{summary=$existing.summary};etag=$edited._etag;note='Restore existing UAT profile summary after verification'}
      $null=Call ADMIN "/api/operations/researchers/$($existing.id)" PUT @{values=@{summary='Stale probe'};etag=$existing._etag} 409
      $results.Add(@{key=$d.key;result='PASS';id=$existing.id;mode='Existing named UAT profile edit, restoration and stale conflict; unique account constraint retained'})
      continue
    }
  }
  'calendar-rules'{$v.rule_date=(Get-Date).AddDays((Get-Random -Minimum 366 -Maximum 36500)).ToString('yyyy-MM-dd')}
  'advances'{$v.amount_accounted=0}
  'receipts'{$v.received_amount=100;$v.received_date=$fixture.startDate}
  'expenditure'{$v.reconciled=$true;$v.finance_reference="UAT LEDGER $stamp"}
  'reconciliations'{$v.ledger_amount=10;$v.platform_amount=10}
 }
 # Procurement order requires an independently approved requisition and supplier review.
 if($d.key -eq 'orders'){
  $supplier=Call PROCUREMENT_OFFICER '/api/operations/suppliers' POST @{values=@{name="UAT supplier $stamp";active=$false}}
  $created.suppliers=$supplier;$v.supplier_id=$supplier.id
  $dd=Call PROCUREMENT_OFFICER '/api/operations/diligence' POST @{values=@{entity_type='SUPPLIER';entity_id=$supplier.id;review_type='PROCUREMENT';findings='Synthetic UAT review';expires_at=$fixture.endDate}}
  $dd=Action PROCUREMENT_OFFICER diligence $dd IN_REVIEW;$dd=Action PROCUREMENT_OFFICER diligence $dd APPROVED
 }
 $request=@{values=$v;requestId=[guid]::NewGuid().ToString();note='Synthetic UAT form test'}
 $r=Call $role "/api/operations/$($d.key)" POST $request
 $created[$d.key]=$r
 $replay=Call $role "/api/operations/$($d.key)" POST $request
 Assert ($r.id -eq $replay.id) "$($d.title): retry returns the same saved record"
 $edited=Call $role "/api/operations/$($d.key)/$($r.id)" PUT @{values=$v;etag=$r._etag;note='UAT saved form revision'}
 $created[$d.key]=$edited
 $null=Call $role "/api/operations/$($d.key)/$($r.id)" PUT @{values=$v;etag='stale';note='UAT collision probe'} 409
 $history=Call $role "/api/operations/$($d.key)/$($r.id)/history"
 Assert ($history.Count -ge 2) "$($d.title): create, edit, conflict protection and named audit history"
 if($d.key -eq 'requisitions'){$r=Action PROCUREMENT_OFFICER requisitions $edited SUBMITTED;$null=Action PROCUREMENT_OFFICER requisitions $r APPROVED 403;$created.requisitions=Action DIRECTOR requisitions $r APPROVED}
 if($d.key -eq 'financial-approvals'){$null=Action FINANCE_OFFICER financial-approvals $edited APPROVED 403;$created[$d.key]=Action FINANCE_APPROVER financial-approvals $edited APPROVED}
 if($d.key -eq 'reconciliations'){$r=Action FINANCE_OFFICER reconciliations $edited IN_REVIEW;$created[$d.key]=Action FINANCE_OFFICER reconciliations $r RECONCILED}
 $results.Add(@{key=$d.key;result='PASS';id=$r.id})
 }catch{$results.Add(@{key=$d.key;result='FAIL';error=$_.Exception.Message});Write-Output "FAIL $($d.key): $($_.Exception.Message)"}
}
$results|ConvertTo-Json -Depth 8|Set-Content "$root/docs/testing/DEPARTMENT_FORM_RESULTS.json"
$created|ConvertTo-Json -Depth 12|Set-Content "$root/.local/uat-department-fixtures.json"
Write-Output "Form results: $(@($results|Where-Object result -eq PASS).Count)/$($results.Count)"
$backup=Call IT_ADMIN '/api/operations/backups/run' POST @{}
$restored=Call IT_ADMIN "/api/operations/backups/$($backup.id)/verify-restore" POST @{}
Assert ($restored.status -eq 'SUCCESS') 'Managed backup restores into an isolated database'
if(@($results|Where-Object result -eq FAIL).Count){throw 'Department form failures require repair'}

