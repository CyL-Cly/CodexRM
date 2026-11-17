# Local-update checks use a temporary installation and never touch the real client.
$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('CodexRM-local-update-tests-' + [Guid]::NewGuid().ToString('N'))
$installDir = Join-Path $testRoot 'CodexRM'
[void][IO.Directory]::CreateDirectory((Join-Path $installDir 'scripts'))
[void][IO.Directory]::CreateDirectory((Join-Path $installDir 'bin'))
[void][IO.Directory]::CreateDirectory((Join-Path $installDir '.state'))
$binary = Join-Path $installDir 'bin\codexrm-agent.exe'
Set-Content -LiteralPath $binary -Value 'fixture' -Encoding UTF8
$versionPath = Join-Path $installDir 'version.json'
$releasePath = Join-Path $installDir 'release.json'
$connectionPath = Join-Path $installDir '.state\connection.json'
@{versionCode=1;versionName='0.1.0'} | ConvertTo-Json | Set-Content -LiteralPath $versionPath -Encoding UTF8
@{versionCode=2;versionName='0.2.0'} | ConvertTo-Json | Set-Content -LiteralPath $releasePath -Encoding UTF8
Set-Content -LiteralPath $connectionPath -Value '{"server":"https://rm.example","workspace":"D:\\workspace"}' -Encoding UTF8 -NoNewline
@'
function Get-CodexRMBinaryVersion([string]$Binary) {
    $root = Split-Path -Parent (Split-Path -Parent $Binary)
    $failuresPath = Join-Path $root 'version-read-failures.txt'
    if (Test-Path -LiteralPath $failuresPath -PathType Leaf) {
        $remaining = [int](Get-Content -LiteralPath $failuresPath -Raw -Encoding UTF8)
        if ($remaining -gt 0) {
            Set-Content -LiteralPath $failuresPath -Value ($remaining - 1) -Encoding UTF8
            throw 'Client release version is missing.'
        }
    }
    return Get-Content -LiteralPath (Join-Path $root 'version.json') -Raw -Encoding UTF8 | ConvertFrom-Json
}
'@ | Set-Content -LiteralPath (Join-Path $installDir 'scripts\client-install.ps1') -Encoding UTF8
@'
param(
    [string]$InstallDir,
    [ValidateSet('CheckUpdate','Update')][string]$Action,
    [string]$UpdateManifest,
    [switch]$NonInteractive
)
Add-Content -LiteralPath (Join-Path $InstallDir 'actions.log') -Value ($Action + ';NonInteractive=' + $NonInteractive + ';Manifest=' + $UpdateManifest) -Encoding UTF8
if ($Action -eq 'Update') {
    if (-not $NonInteractive) { throw 'The release helper did not request a noninteractive update.' }
    if (Test-Path -LiteralPath (Join-Path $InstallDir 'change-config.flag')) {
        Set-Content -LiteralPath (Join-Path $InstallDir '.state\connection.json') -Value '{"server":"https://changed.example"}' -Encoding UTF8
    }
    if (-not (Test-Path -LiteralPath (Join-Path $InstallDir 'skip-update.flag'))) {
        Copy-Item -LiteralPath (Join-Path $InstallDir 'release.json') -Destination (Join-Path $InstallDir 'version.json') -Force
        if (Test-Path -LiteralPath (Join-Path $InstallDir 'transient-read-failure.flag')) {
            Set-Content -LiteralPath (Join-Path $InstallDir 'version-read-failures.txt') -Value 2 -Encoding UTF8
        }
    }
}
'@ | Set-Content -LiteralPath (Join-Path $installDir 'scripts\install-client.ps1') -Encoding UTF8
$helper = Join-Path $PSScriptRoot 'update-local-client.ps1'
try {
    & $helper -ExpectedVersionCode 2 -ExpectedVersionName '0.2.0' -InstallDir $installDir -ValidateOnly
    $version = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($version.versionCode -ne 1 -or (Test-Path -LiteralPath (Join-Path $installDir 'actions.log'))) {
        throw 'Preflight changed the installation or invoked its update script.'
    }
    Write-Host 'PASS: Preflight validates an installation without running its updater or changing files.'

    & $helper -ExpectedVersionCode 2 -ExpectedVersionName '0.2.0' -InstallDir $installDir
    $version = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $actions = @(Get-Content -LiteralPath (Join-Path $installDir 'actions.log') -Encoding UTF8)
    if ($version.versionCode -ne 2 -or $version.versionName -cne '0.2.0' -or $actions.Count -ne 1 -or $actions[0] -notmatch '^Update;') {
        throw 'The installed update script was not called or did not produce the expected version.'
    }
    Write-Host 'PASS: An older local installation is updated through its installed script and verified.'

    & $helper -ExpectedVersionCode 2 -ExpectedVersionName '0.2.0' -InstallDir $installDir
    $actions = @(Get-Content -LiteralPath (Join-Path $installDir 'actions.log') -Encoding UTF8)
    if ($actions.Count -ne 2 -or $actions[1] -notmatch '^CheckUpdate;') { throw 'An up-to-date installation was replaced instead of checked.' }
    Write-Host 'PASS: An already current installation only checks the normal update source.'

    @{versionCode=3;versionName='0.3.0'} | ConvertTo-Json | Set-Content -LiteralPath $versionPath -Encoding UTF8
    $beforeActions = @(Get-Content -LiteralPath (Join-Path $installDir 'actions.log') -Encoding UTF8).Count
    $rejected = $false
    try { & $helper -ExpectedVersionCode 2 -ExpectedVersionName '0.2.0' -InstallDir $installDir } catch { $rejected = $true }
    $afterActions = @(Get-Content -LiteralPath (Join-Path $installDir 'actions.log') -Encoding UTF8).Count
    if (-not $rejected -or $beforeActions -ne $afterActions) { throw 'A newer local installation was downgraded.' }
    Write-Host 'PASS: A newer local installation rejects an accidental downgrade before running the installer.'

    @{versionCode=1;versionName='0.1.0'} | ConvertTo-Json | Set-Content -LiteralPath $versionPath -Encoding UTF8
    New-Item -ItemType File -Path (Join-Path $installDir 'skip-update.flag') -Force | Out-Null
    $rejected = $false
    try { & $helper -ExpectedVersionCode 2 -ExpectedVersionName '0.2.0' -InstallDir $installDir } catch { $rejected = $true }
    if (-not $rejected) { throw 'An installer result that did not match the released version was accepted.' }
    Remove-Item -LiteralPath (Join-Path $installDir 'skip-update.flag') -Force
    Write-Host 'PASS: A missing or wrong post-update binary version fails verification.'

    New-Item -ItemType File -Path (Join-Path $installDir 'change-config.flag') -Force | Out-Null
    $rejected = $false
    try { & $helper -ExpectedVersionCode 2 -ExpectedVersionName '0.2.0' -InstallDir $installDir } catch { $rejected = $true }
    if (-not $rejected) { throw 'Unexpected connection setting changes were accepted.' }
    Write-Host 'PASS: Post-update verification detects unexpected connection setting changes.'

    Remove-Item -LiteralPath (Join-Path $installDir 'change-config.flag') -Force
    @{versionCode=1;versionName='0.1.0'} | ConvertTo-Json | Set-Content -LiteralPath $versionPath -Encoding UTF8
    New-Item -ItemType File -Path (Join-Path $installDir 'transient-read-failure.flag') -Force | Out-Null
    & $helper -ExpectedVersionCode 2 -ExpectedVersionName '0.2.0' -InstallDir $installDir
    $version = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $remainingFailures = [int](Get-Content -LiteralPath (Join-Path $installDir 'version-read-failures.txt') -Raw -Encoding UTF8)
    if ($version.versionCode -ne 2 -or $remainingFailures -ne 0) { throw 'Transient post-update version reads were not retried.' }
    Write-Host 'PASS: Transient post-update version read failures are retried without reinstalling.'

    $previousLocalAppData = $env:LOCALAPPDATA
    try {
        $env:LOCALAPPDATA = Join-Path $testRoot 'empty local app data'
        [void][IO.Directory]::CreateDirectory($env:LOCALAPPDATA)
        $rejected = $false
        $failure = ''
        try { & $helper -ExpectedVersionCode 2 -ExpectedVersionName '0.2.0' -ValidateOnly } catch { $rejected = $true; $failure = $_.Exception.Message }
        if (-not $rejected -or $failure -notmatch 'No managed CodexRM installation') {
            throw "Missing installation was not explained clearly: $failure"
        }
    } finally { $env:LOCALAPPDATA = $previousLocalAppData }
    Write-Host 'PASS: A missing default installation produces an actionable preflight error.'
} finally {
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedRoot.StartsWith($tempRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedRoot).StartsWith('CodexRM-local-update-tests-')) {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
