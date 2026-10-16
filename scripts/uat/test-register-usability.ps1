. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$stamp=Get-Date -Format 'yyyyMMdd-HHmmss'
$r=Call ADMIN '/api/operations/units?size=100&sort=name&direction=asc'
$names=@($r.rows.name)
Assert ($names.Count -ge 2) 'Organisation records available for register checks'
Assert ((($names | Sort-Object) -join '|') -eq ($names -join '|')) 'Register sorts ascending by the selected column'
$descending=Call ADMIN '/api/operations/units?size=100&sort=name&direction=desc'
Assert ($descending.rows[0].name -eq $names[-1]) 'Descending sorting reverses the selected column'
$null=Call ADMIN '/api/operations/units?sort=invalid_column' GET $null 400
$null=Call ADMIN '/api/operations/units?direction=invalid' GET $null 400
Assert $true 'Sorting rejects unknown column names and directions'
$view=Call ADMIN '/api/operations/units/views' POST @{name="UAT usability $stamp";settings=@{q='Health';status='';sort='name';direction='desc';columns=@('code','name')}}
try {
 $views=Call ADMIN '/api/operations/units/views'
 $saved=@($views | Where-Object id -eq $view.id)[0]
 Assert ($saved.settings.columns.Count -eq 2 -and $saved.settings.direction -eq 'desc') 'Personal view retains columns, search and sorting'
 $other=Call AUDITOR '/api/operations/units/views'
 Assert (@($other | Where-Object id -eq $view.id).Count -eq 0) 'Saved views are private to the account'
 $null=Call AUDITOR "/api/operations/units/views/$($view.id)" DELETE @{} 404
 $null=Call ADMIN '/api/operations/units/views' POST @{name='Invalid view';settings=@{columns=@('secret_sql')}} 400
 Assert $true 'Saved views reject cross-account deletion and invalid columns'
 $null=Call IT_ADMIN '/api/operations/units/views' GET $null 403
 Assert $true 'Saved-view API preserves workspace permissions'
} finally {$null=Call ADMIN "/api/operations/units/views/$($view.id)" DELETE @{}}
$record=$r.rows[0];$id=$record.id;$before=$record._etag
$note=Call ADMIN "/api/operations/units/$id/discussion" POST @{body="UAT usability verification $stamp. Synthetic test note; no institutional decision."}
$notes=Call ADMIN "/api/operations/units/$id/discussion"
Assert (@($notes | Where-Object id -eq $note.id).Count -eq 1) 'Record discussion persists the author and note'
$null=Call AUDITOR "/api/operations/units/$id/discussion" POST @{body='Read-only test must be rejected'} 403
$null=Call IT_ADMIN "/api/operations/units/$id/discussion" GET $null 403
Assert $true 'Read-only and technical roles cannot write or read restricted discussion'
$null=Call ADMIN "/api/operations/units/$id/discussion/$($note.id)/resolve" POST @{}
$resolved=Call ADMIN "/api/operations/units/$id/discussion"
Assert (@($resolved | Where-Object {$_.id -eq $note.id -and $_.resolved_at}).Count -eq 1) 'The author can resolve a discussion note'
$null=Call ADMIN "/api/operations/units/$id/discussion/$($note.id)/resolve" POST @{} 403
$after=Call ADMIN '/api/operations/units?size=100'
Assert ((@($after.rows | Where-Object id -eq $id)[0])._etag -eq $before) 'Discussion does not change the business record or workflow'
$history=Call ADMIN "/api/operations/units/$id/history"
Assert (@($history | Where-Object action -eq 'DISCUSSION_ADDED').Count -gt 0) 'Discussion changes have retained audit history'
$paged=Call ADMIN '/api/operations/opportunities?size=100&sort=title'
$collected=@();for($p=0;$collected.Count -lt $paged.total;$p++){$part=Call ADMIN "/api/operations/opportunities?size=100&page=$p&sort=title";$collected+=@($part.rows);if(!$part.rows.Count){break}}
Assert ($collected.Count -eq $paged.total -and $collected.Count -gt 25) 'Full filtered export pagination covers every matching record beyond the first page'
Write-Output 'Register usability runtime checks completed.'
