$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
function NewPolicy($item){return @{title=$item.definition.title;description=$item.definition.description;enabled=$item.enabled;readers=@($item.readers);writers=@($item.definition.writers);revision=[long]$item.revision;reason='Synthetic role form permission acceptance test';fields=@($item.definition.fields|ForEach-Object {@{name=$_.name;label=$_.label;help=$_.help;section=$_.section;required=$_.required;hidden=$_.hidden}})}}
function FreshPolicy($key){return @((Call ADMIN '/api/operations/form-administration')|Where-Object {$_.base.key-eq $key})[0]}
function SavePolicy($key,$p){return Call ADMIN "/api/operations/form-administration/$key" PUT $p}
$items=@(Call ADMIN '/api/operations/form-administration')
$routeSource=Get-Content 'D:\Git\nhrc-grants-platform\apps\api\src\main\kotlin\org\nhrc\grants\operations\FormRoutePermissions.kt' -Raw
$aliases=[regex]::Matches($routeSource,'"(/api/[^"]+)" to "([^"]+)"')
$results=@();$aliasChecks=0
foreach($item in $items){
 $key=$item.base.key
 if('AUDITOR' -notin $item.readers){throw "Expected Auditor baseline read access for $key"}
 $baseline=NewPolicy $item
 try {
  $p=NewPolicy $item
  $p.readers=@($p.readers|Where-Object {$_-ne 'AUDITOR'});$p.writers=@($p.writers|Where-Object {$_-ne 'AUDITOR'})
  $null=SavePolicy $key $p
  $null=Call AUDITOR "/api/operations/$key" GET $null 403
  $null=Call AUDITOR "/api/operations/options/$key" GET $null 403
  $null=Call AUDITOR "/api/operations/$key/views" GET $null 403
  $null=Call AUDITOR "/api/operations/$key/views" POST @{name='Denied view';settings=@{}} 403
  $null=Call AUDITOR "/api/operations/$key/00000000-0000-0000-0000-000000000000/discussion" GET $null 403
  $null=Call AUDITOR "/api/operations/$key/00000000-0000-0000-0000-000000000000/discussion" POST @{body='Denied note'} 403
  foreach($alias in $aliases){if($alias.Groups[2].Value-eq $key){$null=Call AUDITOR $alias.Groups[1].Value GET $null 403;$aliasChecks++}}
  $visibility=Call AUDITOR '/api/operations/form-visibility'
  foreach($page in $item.base.pages){
   $other=@($items|Where-Object {$_.base.key-ne $key -and $page-in $_.base.pages})
   if($other.Count-eq 0 -and $page-notin $visibility.hiddenPages){throw "Denied form page remained visible: $page"}
  }
  $results+=@{form=$key;result='PASS';check='Read denied for operational, lookup and mapped legacy routes'}
 } finally {
  $baseline.revision=(FreshPolicy $key).revision;$baseline.reason='Restore original permissions after the bounded denial test';$null=SavePolicy $key $baseline
 }
}
Assert ($results.Count-eq 53) 'All 53 operational form denials enforce role access and are restored'
Assert ($aliasChecks-gt 50) "$aliasChecks mapped record endpoints enforce the same form denial"
$catalog=@(Call AUDITOR '/api/operations/catalog');Assert ($catalog.Count-gt 45) 'Restoring permissions restores the existing Auditor session catalogue'

$item=FreshPolicy 'receipts';$baseline=NewPolicy $item
try {
 $p=NewPolicy $item;$p.writers=@($p.writers|Where-Object {$_-ne 'FINANCE_OFFICER'});$null=SavePolicy receipts $p
 $read=Call FINANCE_OFFICER '/api/operations/receipts'
 Assert (-not $read.canEdit -and -not $read.canCreate) 'View-only permission removes creation and editing controls'
 $null=Call FINANCE_OFFICER '/api/operations/receipts' POST @{values=@{}} 403
 $null=Call FINANCE_OFFICER '/api/finance/receipts' POST @{} 403
 $null=Call FINANCE_OFFICER '/api/operations/import/commit' POST @{dataset='receipts';rows=@(@{})} 403
 Assert $true 'Read-only roles cannot bypass editing restrictions through legacy creation or imports'
 $p.revision=(FreshPolicy receipts).revision;$p.readers=@($p.readers|Where-Object {$_-ne 'FINANCE_OFFICER'});$null=SavePolicy receipts $p
 $null=Call FINANCE_OFFICER '/api/finance/receipts' GET $null 403
 $null=Call FINANCE_OFFICER '/api/operations/receipts' GET $null 403
 $other=Call FINANCE_APPROVER '/api/operations/receipts';Assert ($other.canEdit) 'A separately authorised Finance Approver keeps their existing access'
} finally {$baseline.revision=(FreshPolicy receipts).revision;$null=SavePolicy receipts $baseline}
Assert ((Call FINANCE_OFFICER '/api/operations/receipts').canEdit) 'Restored editing works in the same signed-in session'

$item=FreshPolicy units;$baseline=NewPolicy $item
try {
 $p=NewPolicy $item;$p.readers=@($p.readers|Where-Object {$_-ne 'ADMIN'});$p.writers=@();$null=SavePolicy units $p
 $null=Call ADMIN '/api/operations/units' GET $null 403
 Assert (@(Call ADMIN '/api/operations/form-administration').Count-eq 53) 'The permission console remains available after an Administrator denies their own record access'
 $null=Call GRANTS_OFFICER '/api/operations/form-administration/units' PUT $p 403
 $bad=NewPolicy (FreshPolicy units);$bad.writers=@('RESEARCHER');$null=Call ADMIN '/api/operations/form-administration/units' PUT $bad 400
 Assert $true 'Neither non-admin actors nor out-of-department role escalation can change form authority'
} finally {$baseline.revision=(FreshPolicy units).revision;$null=SavePolicy units $baseline}

$stamp=Get-Date -Format HHmmss
$form=Call ADMIN '/api/operations/forms' POST @{values=@{code="UAT-ACCESS-$stamp";name="UAT role permission questionnaire $stamp";entity_type='APPLICATION';version_no=1;schema_json=@{fields=@(@{name='delivery';label='Delivery context';type='text';required=$false})};active=$true};requestId=[guid]::NewGuid()}
$fixture=Get-Content 'D:\Git\nhrc-grants-platform\.local\uat-lifecycle-fixture.json' -Raw|ConvertFrom-Json
try {
 $policy=Call ADMIN "/api/operations/questionnaire-permissions/$($form.id)"
 $p=@{readers=@($policy.readers);writers=@($policy.writers|Where-Object {$_-ne 'GRANTS_OFFICER'});revision=$policy.revision;reason='Synthetic read-only questionnaire permission'}
 $saved=Call ADMIN "/api/operations/questionnaire-permissions/$($form.id)" PUT $p
 $read=@(Call GRANTS_OFFICER "/api/operations/application-forms/$($fixture.applicationId)"|Where-Object id -eq $form.id)[0]
 Assert ($read -and -not $read.canEdit) 'Questionnaire read-only permission is visible without exposing editing'
 $null=Call GRANTS_OFFICER "/api/operations/application-forms/$($fixture.applicationId)/$($form.id)" PUT @{values=@{delivery='Denied write probe'};revision=0} 403
 $p.readers=@($p.readers|Where-Object {$_-ne 'GRANTS_OFFICER'});$p.revision=$saved.revision
 $null=Call ADMIN "/api/operations/questionnaire-permissions/$($form.id)" PUT $p
 $read=@(Call GRANTS_OFFICER "/api/operations/application-forms/$($fixture.applicationId)"|Where-Object id -eq $form.id)
 Assert ($read.Count-eq 0) 'An unticked questionnaire is removed from proposal responses'
 $null=Call ADMIN "/api/operations/questionnaire-permissions/$($form.id)" PUT $p 409
 $null=Call GRANTS_OFFICER "/api/operations/questionnaire-permissions/$($form.id)" GET $null 403
 $p.revision=(Call ADMIN "/api/operations/questionnaire-permissions/$($form.id)").revision;$p.readers=@($policy.readers);$p.writers=@($policy.writers)
 $null=Call ADMIN "/api/operations/questionnaire-permissions/$($form.id)" PUT $p
 $null=Call GRANTS_OFFICER "/api/operations/application-forms/$($fixture.applicationId)/$($form.id)" PUT @{values=@{delivery='Synthetic restored-access response'};revision=0}
 $null=Call FINANCE_OFFICER "/api/operations/application-forms/$($fixture.applicationId)" GET $null 403
 $audit=Call ADMIN "/api/operations/questionnaire-permissions/$($form.id)"
 Assert ($audit.history.Count-eq 3 -and @($audit.history|Where-Object {-not $_.actor -or -not $_.note}).Count-eq 0) 'Questionnaire permission history retains named actors and reasons; original private-application scope stays enforced'
} finally {
 $current=@((Call ADMIN '/api/operations/forms?size=100').rows|Where-Object id -eq $form.id)[0]
 $null=Call ADMIN "/api/operations/forms/$($form.id)" PUT @{values=@{active=$false};etag=$current._etag;note='Retire the synthetic permission test questionnaire'}
}
@{result='PASS';testedAt=(Get-Date).ToUniversalTime().ToString('o');forms=$results.Count;legacyEndpointChecks=$aliasChecks;results=$results;syntheticQuestionnaireId=$form.id}|ConvertTo-Json -Depth 6|Set-Content 'D:\Git\nhrc-grants-platform\docs\testing\ROLE_FORM_PERMISSION_RESULTS.json'

