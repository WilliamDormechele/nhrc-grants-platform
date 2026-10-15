. "$PSScriptRoot/test_helpers.ps1"
$f=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
$created=Get-Content "$root/.local/uat-department-fixtures.json" -Raw|ConvertFrom-Json
$w=Call GRANTS_OFFICER "/api/pregrant/applications/$($f.applicationId)/workspace"
$defs=Get-Content "$root/apps/api/src/main/resources/operational-catalog.json" -Raw|ConvertFrom-Json
$results=@()
foreach($d in $defs){
 $record=$created.($d.key)
 if(-not $record -or $record.award_id -ne $f.awardId -or -not ($d.fields|Where-Object {$_.name -eq 'award_id' -and $_.reference -eq 'awards'})){continue}
 $role=$d.writers[0]
 $rows=Call $role ("/api/operations/$($d.key)?q="+[uri]::EscapeDataString($w.application.title)+'&size=100')
 Assert ($record.id -in $rows.rows.id) "$($d.title) can be found by its displayed award title"
 $results+=@{dataset=$d.key;result='PASS';query='Displayed award title'}
}
$orders=$created.orders;$supplier=@((Call PROCUREMENT_OFFICER '/api/operations/options/suppliers')|Where-Object id -eq $orders.supplier_id)[0]
$found=Call PROCUREMENT_OFFICER ('/api/operations/orders?q='+[uri]::EscapeDataString($supplier.label))
Assert ($orders.id -in $found.rows.id) 'Purchase orders can be found by displayed supplier name'
$results+=@{dataset='orders';query='Displayed supplier name';result='PASS'}
$results|ConvertTo-Json -Depth 5|Set-Content "$root/docs/testing/FULL_REFERENCE_SEARCH_RESULTS.json"
