# Publication checks replace network commands; no remote files or user settings are changed.
$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('CodexRM-publish-tests-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$fixture = [pscustomobject]@{versionCode=3;versionName='0.6.2';downloadUrl='https://cloud.example/client.zip';releaseNotes='test';fileSize=123;sha256=('a'*64)}
$manifest = Join-Path $testRoot 'update.json'
$fixture | ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding UTF8
$rmConfig = [pscustomobject]@{server='https://rm.example/base';sshTarget='test-rm';updateDir='/opt/codexrm/data/updates';owner='codexrm'}
$state = @{calls=(New-Object Collections.Generic.List[string]);mode='success';expectedUri='https://rm.example/base/update.json'}
function ssh {
    $command = [string]$args[-1]
    $state.calls.Add($(if($command.Contains('mv -f')) {'activate'} else {'prepare'}))
    $global:LASTEXITCODE = 0
}
function scp {
    $state.calls.Add('upload')
    $global:LASTEXITCODE = $(if($state.mode -eq 'upload failure') {1} else {0})
}
function Invoke-WebRequest {
    param([switch]$UseBasicParsing,$Uri,$Headers,$TimeoutSec)
    $state.calls.Add('verify')
    if ($Uri -cne $state.expectedUri) { throw 'Publication verified the wrong endpoint.' }
    if ($state.mode -eq 'wrong version') { return [pscustomobject]@{Content='{"versionCode":2}';Headers=@{'Cache-Control'='no-store'}} }
    return [pscustomobject]@{Content=($fixture|ConvertTo-Json);Headers=@{'Cache-Control'='no-store'}}
}
try {
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -RM $rmConfig
    if (($state.calls -join ',') -cne 'prepare,upload,activate,verify') { throw 'Manifest was not staged before activation and public verification.' }
    Write-Host 'PASS: Manifest is staged, atomically activated, and verified through the RM base URL.'
    $state.calls.Clear()
    $state.expectedUri = 'https://rm.example/base/android-update.json'
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -Name 'android-update.json' -RM $rmConfig
    if (($state.calls -join ',') -cne 'prepare,upload,activate,verify') { throw 'Android manifest was not published independently.' }
    $state.expectedUri = 'https://rm.example/base/update.json'
    Write-Host 'PASS: Android manifest uses its own remote filename and public endpoint.'
    $state.calls.Clear()
    $state.mode = 'upload failure'
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -RM $rmConfig } catch { $rejected = $true }
    if (-not $rejected -or ($state.calls -join ',') -cne 'prepare,upload') { throw 'Failed upload activated a manifest.' }
    Write-Host 'PASS: Failed uploads retain the active RM manifest.'
    $state.calls.Clear()
    $state.mode = 'wrong version'
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -RM $rmConfig } catch { $rejected = $true }
    if (-not $rejected) { throw 'Mismatched public version was accepted.' }
    Write-Host 'PASS: A stale or incorrect public manifest is reported as a failed publication.'
    foreach ($badPath in @('/','/opt/codexrm/../data','/opt/codexrm;false')) {
        $state.calls.Clear()
        $rmConfig.updateDir = $badPath
        $rejected = $false
        try { & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -RM $rmConfig } catch { $rejected = $true }
        if (-not $rejected -or $state.calls.Count) { throw 'Unsafe publication directory reached a remote command.' }
    }
    Write-Host 'PASS: Invalid and unsafe destination paths are rejected before remote access.'
} finally {
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedRoot.StartsWith($tempRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedRoot).StartsWith('CodexRM-publish-tests-')) {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
