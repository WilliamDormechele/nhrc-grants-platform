$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$sa=SignIn SUPERADMIN
$checks=@()
$types=@{docx='application/vnd.openxmlformats-officedocument.wordprocessingml.document';xlsx='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';csv='text/csv';png='image/png'}
foreach($file in Get-ChildItem 'D:\Git\nhrc-grants-platform\.local\release03-formats' -File){
 $ext=$file.Extension.TrimStart('.')
 $csrf=Invoke-RestMethod http://127.0.0.1:8080/api/auth/csrf -WebSession $sa.cookie
 $upload=Invoke-RestMethod http://127.0.0.1:8080/api/operations/documents -Method POST -WebSession $sa.cookie -Headers @{$csrf.headerName=$csrf.token} -Form @{file=$file}
 $dest='D:\Git\nhrc-grants-platform\.local\release03-native-download.'+$ext
 $response=Invoke-WebRequest "http://127.0.0.1:8080/api/operations/documents/$($upload.id)" -WebSession $sa.cookie -OutFile $dest -PassThru
 if((Get-FileHash $dest).Hash-ne (Get-FileHash $file.FullName).Hash){throw "$ext bytes changed"}
 if($response.Headers['Content-Type']-notmatch [regex]::Escape($types[$ext])){throw "$ext media type incorrect"}
 if($response.Headers['Content-Disposition']-notmatch "\.$ext"){throw "$ext filename incorrect"}
 $checks+=@{extension=$ext;nativeBytesPreserved=$true;mediaTypeCorrect=$true;filenameCorrect=$true}
 Write-Output "PASS $ext native bytes, media type and filename"
}
@{status='PASS';formats=$checks}|ConvertTo-Json -Depth 5|Set-Content D:\Git\nhrc-grants-platform\docs\testing\RELEASE03_NATIVE_FORMATS.json -Encoding utf8
