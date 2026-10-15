. "$PSScriptRoot/test_helpers.ps1"
$f=Get-Content "$root/.local/uat-lifecycle-fixture.json" -Raw|ConvertFrom-Json
$taskAward=[guid]$f.awardId
$taskLock=735190215
$taskSql="begin; select id from awards where id='$taskAward' for update; select pg_advisory_xact_lock($taskLock); select pg_sleep(12); commit;"
$taskJob=Start-ThreadJob -ScriptBlock {param($sql) docker exec nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -v ON_ERROR_STOP=1 -c $sql} -ArgumentList $taskSql
try{
 $taskHeld=$false
 for($taskAttempt=0;$taskAttempt -lt 12;$taskAttempt++){
  $taskHeldValue=docker exec nhrc-grants-postgres psql -U nhrc_grants -d nhrc_grants -t -A -c "select exists(select 1 from pg_locks where locktype='advisory' and objid=$taskLock and granted)"
  if(($taskHeldValue -join '').Trim() -eq 't'){$taskHeld=$true;break};Start-Sleep -Milliseconds 250
 }
 Assert $taskHeld 'The synthetic closure transaction holds its award lock before the financial request starts'
 $null=SignIn FINANCE_OFFICER
 $taskWatch=[Diagnostics.Stopwatch]::StartNew()
 $null=Call FINANCE_OFFICER '/api/operations/receipts' POST @{values=@{award_id=$f.awardId;currency='GBP';received_amount=1;received_date=$f.startDate}} 409
 $taskWatch.Stop()
 Assert ($taskWatch.Elapsed.TotalSeconds -ge 5) 'Financial posting waits for the award transaction, then rejects the closed status without inserting a receipt'
 $taskJob|Wait-Job|Receive-Job|Out-Null
 @{result='PASS';awardId=$f.awardId;observedLockWaitSeconds=[math]::Round($taskWatch.Elapsed.TotalSeconds,2);requestResult=409}|ConvertTo-Json|Set-Content "$root/docs/testing/FULL_FINANCIAL_CLOSURE_LOCK_RESULTS.json"
}finally{$taskJob|Wait-Job|Receive-Job|Out-Null;Remove-Job -Job $taskJob}
