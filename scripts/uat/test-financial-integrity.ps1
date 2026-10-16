. "$PSScriptRoot/test_helpers.ps1"
$f=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
$record=@((Call FINANCE_OFFICER "/api/operations/positions?q=$($f.awardId)").rows)[0]
$meta=Call FINANCE_OFFICER "/api/operations/positions?q=$($f.awardId)"
Assert (-not $meta.canEdit -and -not $meta.canCreate) 'Calculated financial positions have no create or edit permission'
$null=Call FINANCE_OFFICER "/api/operations/positions/$($f.awardId)" PUT @{values=@{cash_received=999999};etag=$record._etag;note='Synthetic forbidden derived-total probe'} 403
$null=Call FINANCE_APPROVER "/api/operations/positions/$($f.awardId)" PUT @{values=@{approved_budget=999999};etag=$record._etag;note='Synthetic forbidden derived-total probe'} 403
$null=Call FINANCE_OFFICER '/api/operations/receipts' POST @{values=@{award_id=$f.awardId;currency='GBP';received_amount=1;received_date=$f.startDate}} 409
$award=@((Call GRANTS_OFFICER "/api/operations/awards?q=$($f.awardId)").rows)[0]
$null=Call GRANTS_OFFICER "/api/operations/awards/$($award.id)" PUT @{values=@{nhrc_allocation=790};etag=$award._etag;note='Synthetic forbidden closed-award budget probe'} 409
$rows=Get-Content "$root/.local/uat-department-fixtures.json" -Raw|ConvertFrom-Json
$advance=@((Call FINANCE_OFFICER "/api/operations/advances?size=100").rows|Where-Object id -eq $rows.advances.id)[0]
$null=Call FINANCE_OFFICER "/api/operations/advances/$($advance.id)" PUT @{values=@{amount_accounted=0};etag=$advance._etag;note='Synthetic forbidden closed-award advance probe'} 409
Write-Output 'PASS Closed awards reject new postings, advance changes and financial-term changes'
$cash=@((Call FINANCE_OFFICER "/api/operations/positions?q=$($f.awardId)").rows)[0]
Assert ($cash.cash_received -eq $record.cash_received -and $cash.approved_budget -eq $record.approved_budget) 'Rejected financial overrides leave the verified position unchanged'
@{result='PASS';awardId=$f.awardId;derivedTotals='Read only';closedFinancialChanges='Denied';verifiedAt=(Get-Date).ToUniversalTime().ToString('o')}|ConvertTo-Json|Set-Content "$root/docs/testing/FULL_FINANCIAL_INTEGRITY_RESULTS.json"
