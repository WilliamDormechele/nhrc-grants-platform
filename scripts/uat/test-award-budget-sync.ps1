. "$PSScriptRoot/test_helpers.ps1"
$f=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
$a=@((Call GRANTS_OFFICER "/api/operations/awards?q=$($f.awardId)").rows)[0]
$changed=Call GRANTS_OFFICER "/api/operations/awards/$($a.id)" PUT @{values=@{nhrc_allocation=790};etag=$a._etag;note='Synthetic award budget source synchronization test'}
$p=@((Call FINANCE_OFFICER "/api/operations/positions?q=$($f.awardId)").rows)[0]
Assert ($p.approved_budget -eq 790) 'Authorised award allocation changes update the financial budget from its source'
$restored=Call GRANTS_OFFICER "/api/operations/awards/$($a.id)" PUT @{values=@{nhrc_allocation=$a.nhrc_allocation};etag=$changed._etag;note='Restore the original synthetic UAT allocation after source synchronization check'}
$p=@((Call FINANCE_OFFICER "/api/operations/positions?q=$($f.awardId)").rows)[0]
Assert ($p.approved_budget -eq $a.nhrc_allocation) 'The original synthetic allocation and financial budget are restored together'
@{result='PASS';awardId=$a.id;originalAllocation=$a.nhrc_allocation;restoredBudget=$p.approved_budget}|ConvertTo-Json|Set-Content "$root/docs/testing/FULL_AWARD_BUDGET_SYNC_RESULTS.json"
