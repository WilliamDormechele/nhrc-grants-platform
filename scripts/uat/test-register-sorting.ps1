. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$catalog=Call AUDITOR '/api/operations/catalog'
$results=@()
foreach($d in $catalog){
 foreach($column in $d.columns){
  $r=Call AUDITOR "/api/operations/$($d.key)?size=1&sort=$column&direction=asc"
  Assert ($null -ne $r.total) "$($d.key): $column sorts without an invalid column or status error"
  $results+=@{dataset=$d.key;column=$column;result='PASS'}
 }
}
$results|ConvertTo-Json -Depth 5|Set-Content 'D:\Git\nhrc-grants-platform\docs\testing\REGISTER_SORTING_RESULTS.json'
Write-Output "All $($results.Count) displayed-column sorting checks passed."
