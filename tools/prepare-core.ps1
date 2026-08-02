param([Parameter(ValueFromRemainingArguments = $true)][object[]]$Arguments)
& (Join-Path $PSScriptRoot "trivox-wizard.ps1") @Arguments
exit $LASTEXITCODE
