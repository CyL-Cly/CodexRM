param(
    [Parameter(Mandatory=$true)][long]$ExpectedVersionCode,
    [Parameter(Mandatory=$true)][string]$ExpectedVersionName,
    [string]$InstallDir,
    [string]$Manifest,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
if ($ExpectedVersionCode -lt 1 -or $ExpectedVersionName -notmatch '^\d+\.\d+\.\d+$') {
    throw 'The expected client release version is invalid.'
}
$defaultDiscovery = $false
if (-not $InstallDir) {
    if (-not $env:LOCALAPPDATA) { throw 'LOCALAPPDATA is unavailable; specify -InstallDir.' }
    $defaultRoot = Join-Path $env:LOCALAPPDATA 'CodexRM'
    $recordPath = Join-Path $defaultRoot 'installation.json'
    $record = if (Test-Path -LiteralPath $recordPath -PathType Leaf) {
        Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } else { $null }
    if ($record -and $record.installDir) { $InstallDir = [string]$record.installDir }
    else {
        $InstallDir = $defaultRoot
        $defaultDiscovery = $true
    }
}
$InstallDir = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
$installer = Join-Path $InstallDir 'scripts\install-client.ps1'
$helpers = Join-Path $InstallDir 'scripts\client-install.ps1'
$binary = Join-Path $InstallDir 'bin\codexrm-agent.exe'
$connectionPath = Join-Path $InstallDir '.state\connection.json'
$native = Test-Path -LiteralPath (Join-Path $InstallDir '.state\installation.json') -PathType Leaf
if ($native) { $helpers = Join-Path $PSScriptRoot 'client-install.ps1' }
$requiredPaths = if ($native) { @($binary, $connectionPath) } else { @($installer, $helpers, $binary, $connectionPath) }
$missing = @($requiredPaths | Where-Object {
    -not (Test-Path -LiteralPath $_ -PathType Leaf)
})
if ($missing.Count) {
    if ($defaultDiscovery) {
        throw "No managed CodexRM installation was found at $InstallDir. Install CodexRM with CodexRM-Setup.exe or the package install.cmd first; for a custom location, specify -InstallDir."
    }
    throw "The local CodexRM installation is incomplete at $InstallDir. Missing: $($missing[0])"
}
. $helpers
$before = Get-CodexRMBinaryVersion $binary
if ([long]$before.versionCode -gt $ExpectedVersionCode) {
    throw "The local client $($before.versionName) (code $($before.versionCode)) is newer than this release; no downgrade was attempted."
}
if ([long]$before.versionCode -eq $ExpectedVersionCode -and [string]$before.versionName -cne $ExpectedVersionName) {
    throw "The local client has version code $ExpectedVersionCode but name $($before.versionName), expected $ExpectedVersionName."
}
if ($ValidateOnly) {
    Write-Host "Local update preflight passed: $InstallDir (current $($before.versionName), code $($before.versionCode))."
    return
}
$connectionBefore = (Get-FileHash -LiteralPath $connectionPath -Algorithm SHA256).Hash
$action = if ([long]$before.versionCode -eq $ExpectedVersionCode) { 'CheckUpdate' } else { 'Update' }
Write-Host "Local client before verification: $($before.versionName) (code $($before.versionCode)); action: $action"
$updateArguments = @{ InstallDir = $InstallDir; Action = $action; NonInteractive = $true }
if ($Manifest) { $updateArguments.UpdateManifest = $Manifest }
if ($native) {
    $agentArguments = @('--config', $connectionPath, '--update-dir', $InstallDir)
    if ($Manifest) { $agentArguments += @('--update-manifest', $Manifest) }
    if ($action -eq 'CheckUpdate') {
        $null = Invoke-CodexRMAgentCommand -Binary $binary -Arguments ($agentArguments + '--check-update')
    } else {
        if (@(Get-CodexRMConnectorProcess $binary).Count) { throw 'Exit the running connector from the tray before updating the local installation.' }
        $null = Invoke-CodexRMUpdateCommand -Binary $binary -Arguments ($agentArguments + '--install-update')
        Start-Process -FilePath $binary -ArgumentList '--restart' -WindowStyle Hidden -WorkingDirectory $InstallDir | Out-Null
    }
} else { & $installer @updateArguments }
$after = $null
$versionReadError = $null
foreach ($attempt in 1..20) {
    try { $after = Get-CodexRMBinaryVersion $binary; break } catch { $versionReadError = $_.Exception.Message }
    if ($attempt -lt 20) { Start-Sleep -Milliseconds 250 }
}
if (-not $after) { throw "Could not read the updated client version after 5 seconds: $versionReadError" }
if ([long]$after.versionCode -ne $ExpectedVersionCode -or [string]$after.versionName -cne $ExpectedVersionName) {
    throw "Local update verification failed: got $($after.versionName) (code $($after.versionCode)), expected $ExpectedVersionName (code $ExpectedVersionCode)."
}
$connectionAfter = (Get-FileHash -LiteralPath $connectionPath -Algorithm SHA256).Hash
if ($connectionAfter -cne $connectionBefore) { throw 'Local update verification detected unexpected changes to the saved connection settings.' }
Write-Host "Local client update verified: $($after.versionName) (code $($after.versionCode)); connection settings preserved."
