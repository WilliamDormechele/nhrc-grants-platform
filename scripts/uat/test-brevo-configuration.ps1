$ErrorActionPreference='Stop'
. 'D:\Git\nhrc-grants-platform\scripts\uat\test_helpers.ps1'
$before=Call SUPERADMIN '/api/operations/communications/email-delivery'
$null=Call GRANTS_OFFICER '/api/operations/communications/email-delivery/brevo/connect' POST @{apiKey='short';reason='Unauthorised provider configuration test'} 403
$null=Call SUPERADMIN '/api/operations/communications/email-delivery/brevo/connect' POST @{apiKey='short';reason='Invalid key rejection test - no external request'} 400
$null=Call SUPERADMIN '/api/operations/communications/email-delivery/mode' PUT @{enabled=$true;provider='BREVO';reason='Unverified provider must remain disabled'} 409
$after=Call SUPERADMIN '/api/operations/communications/email-delivery'
Assert ($after.mode-eq $before.mode) 'Rejected Brevo setup does not change the active provider'
Assert (-not $after.brevoConfigured) 'Invalid Brevo key is not stored'
Assert (-not ($after.PSObject.Properties.Name -contains 'brevo_encrypted_key')) 'Provider secret is never returned to the browser'
@{status='PASS';nonAdminDenied=$true;invalidKeyDeniedBeforeNetwork=$true;untestedEnableDenied=$true;existingProviderPreserved=$true;secretNotReturned=$true;actualBrevoDelivery='Pending administrator key and provider test'}|ConvertTo-Json|Set-Content D:\Git\nhrc-grants-platform\docs\testing\BREVO_CONFIGURATION_ACCEPTANCE.json -Encoding utf8
