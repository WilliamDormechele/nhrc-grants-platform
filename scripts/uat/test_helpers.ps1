$ErrorActionPreference='Stop'
$root='D:\Git\nhrc-grants-platform'
$accounts=Get-Content -LiteralPath (Join-Path $root '.local/uat-accounts.json') -Raw | ConvertFrom-Json
$sessions=@{}
function SignIn([string]$role){
 if($sessions.ContainsKey($role)){return $sessions[$role]}
 $account=@($accounts | Where-Object {$_.role-eq $role})[0]
 $cookie=New-Object Microsoft.PowerShell.Commands.WebRequestSession
 $csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $cookie -TimeoutSec 15
 $null=Invoke-RestMethod http://127.0.0.1:8080/api/auth/login -Method Post -Body @{username=$account.email;password=$account.password} -ContentType application/x-www-form-urlencoded -Headers @{$csrf.headerName=$csrf.token} -WebSession $cookie -TimeoutSec 15
 $me=Invoke-RestMethod http://127.0.0.1:8080/api/auth/me -WebSession $cookie -TimeoutSec 15
 $sessions[$role]=@{cookie=$cookie;user=$me};return $sessions[$role]
}
function Call([string]$role,[string]$path,[string]$method='GET',$body=$null,[int]$expected=200){
 $session=SignIn $role
 $options=@{Uri="http://127.0.0.1:8080$path";Method=$method;WebSession=$session.cookie;TimeoutSec=30;SkipHttpErrorCheck=$true}
 if($method-ne 'GET'){
   $csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $session.cookie -TimeoutSec 15
   $options.Headers=@{$csrf.headerName=$csrf.token}
   $options.ContentType='application/json';$options.Body=ConvertTo-Json -InputObject $body -Depth 15 -Compress
 }
 $response=Invoke-WebRequest @options
 if([int]$response.StatusCode-ne $expected){throw "$role $method $path expected $expected, received $($response.StatusCode): $($response.Content)"}
 if($response.Content){return $response.Content | ConvertFrom-Json}
}
function Assert($condition,[string]$message){if(-not $condition){throw $message};Write-Output "PASS $message"}

