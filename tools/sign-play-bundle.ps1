param(
    [Parameter(Mandatory=$true)][string]$BundlePath,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$SigningConfigPath,
    [Parameter(Mandatory=$true)][string]$JavaHome
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$playBundle = (Resolve-Path -LiteralPath $BundlePath).Path
$playSigning = Get-Content -LiteralPath $SigningConfigPath -Raw | ConvertFrom-Json
$playSigner = Join-Path $JavaHome 'bin/jarsigner.exe'
if (-not (Test-Path -LiteralPath $playSigner)) { throw 'The verified JDK jarsigner is required.' }
if (-not (Test-Path -LiteralPath $playSigning.keystorePath)) { throw 'The configured private upload keystore is missing.' }
if (Test-Path -LiteralPath $OutputPath) { throw 'Choose a new output path; existing releases are never overwritten.' }
$playOutput = [IO.Path]::GetFullPath($OutputPath)
if ($playOutput -eq $playBundle) { throw 'The signed output must differ from the verified unsigned candidate.' }
try {
    $env:PASSO_UPLOAD_STORE_PASSWORD = $playSigning.storePassword
    $env:PASSO_UPLOAD_KEY_PASSWORD = $playSigning.keyPassword
    $playSignArgs = @('-J-Duser.language=en', '-keystore', $playSigning.keystorePath, '-storepass:env', 'PASSO_UPLOAD_STORE_PASSWORD', '-keypass:env', 'PASSO_UPLOAD_KEY_PASSWORD', '-sigalg', 'SHA256withRSA', '-digestalg', 'SHA-256', '-signedjar', $playOutput, $playBundle, $playSigning.keyAlias)
    & $playSigner @playSignArgs
    if ($LASTEXITCODE -ne 0) { throw 'App bundle signing failed.' }
    $playVerification = & $playSigner '-J-Duser.language=en' '-verify' $playOutput 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $playVerification -notmatch 'jar verified\.') { throw 'The signed bundle did not pass signature verification.' }
    Get-FileHash -Algorithm SHA256 -LiteralPath $playOutput | Select-Object Path, Hash
    Write-Output 'Signed upload bundle verified. This does not upload or publish it to Google Play.'
} finally {
    Remove-Item Env:PASSO_UPLOAD_STORE_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item Env:PASSO_UPLOAD_KEY_PASSWORD -ErrorAction SilentlyContinue
}
