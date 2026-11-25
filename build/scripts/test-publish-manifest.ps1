# Publication checks replace network commands; no remote files or user settings are changed.
$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('CodexRM-publish-tests-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$fixture = [pscustomobject]@{versionCode=3;versionName='0.6.2';downloadUrl='https://cloud.example/client.zip';releaseNotes='test';fileSize=123;sha256=('a'*64)}
$manifest = Join-Path $testRoot 'update.json'
$fixture | ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding UTF8
$rmConfig = [pscustomobject]@{server='https://rm.example/base';sshTarget='test-rm';updateDir='/opt/codexrm/data/updates';owner='codexrm'}
$state = @{calls=(New-Object Collections.Generic.List[string]);mode='success';expectedUri='https://rm.example/base/update.json';expectedName='update.json'}
function ssh {
    $command = [string]$args[-1]
    if ($command.Contains('mv -f')) {
        $directory = if ($state.expectedName -eq 'server-update.json') { '/opt/codexrm/server-updates' } else { $rmConfig.updateDir }
        $destination = "'$directory/$($state.expectedName)'"
        if ($state.expectedName -eq 'server-update.json' -and (-not $command.Contains('chown root:root') -or -not $command.Contains('chmod 0644'))) {
            throw 'Server manifest was not activated with administrator-only write permissions.'
        }
        if (-not $command.EndsWith($destination)) { throw 'Publication activated the wrong filename.' }
        $state.calls.Add('activate')
    } elseif ($command.StartsWith('python3 ')) {
        if (-not $state.seed -and -not $command.Contains("'$($state.expectedName)' '3'")) { throw 'History writer used the wrong manifest or version.' }
        $state.calls.Add('record')
    } else {
        if ($state.expectedName -eq 'server-update.json' -and -not $command.Contains("install -d -o root -g root -m 0755 '/opt/codexrm/server-updates'")) {
            throw 'Server manifest directory is not separated from relay state and client manifests.'
        }
        $state.calls.Add('prepare')
    }
    $global:LASTEXITCODE = 0
}
function scp {
    if ([string]$args[-2] -like '*record-release-history.py') { $state.calls.Add('stage history writer') }
    else { $state.calls.Add('upload') }
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
    if (($state.calls -join ',') -cne 'prepare,upload,activate,verify,stage history writer,record') { throw 'Manifest was not staged before activation and public verification.' }
    Write-Host 'PASS: Manifest is staged, atomically activated, and verified through the RM base URL.'
    $state.calls.Clear()
    $state.expectedName = 'android-update.json'
    $state.expectedUri = 'https://rm.example/base/android-update.json'
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -Name 'android-update.json' -RM $rmConfig
    if (($state.calls -join ',') -cne 'prepare,upload,activate,verify,stage history writer,record') { throw 'Android manifest was not published independently.' }
    Write-Host 'PASS: Android manifest uses its own remote filename and public endpoint.'
    $state.calls.Clear()
    $state.expectedName = 'linux-update.json'
    $state.expectedUri = 'https://rm.example/base/linux-update.json'
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -Name 'linux-update.json' -RM $rmConfig
    if (($state.calls -join ',') -cne 'prepare,upload,activate,verify,stage history writer,record') { throw 'Linux Agent manifest was not published independently.' }
    Write-Host 'PASS: Linux Agent manifest uses its own remote filename and public endpoint.'
    $state.calls.Clear()
    $state.expectedName = 'server-update.json'
    $state.expectedUri = 'https://rm.example/base/server-update.json'
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -Name 'server-update.json' -RM $rmConfig
    if (($state.calls -join ',') -cne 'prepare,upload,activate,verify') { throw 'Server manifest was not published independently.' }
    Write-Host 'PASS: Server manifest uses a root-owned directory and does not modify client history.'
    $state.calls.Clear()
    $fixture.downloadUrl = 'http://cloud.example/server.zip'
    $fixture | ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding UTF8
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -Name 'server-update.json' -RM $rmConfig } catch { $rejected = $true }
    if (-not $rejected -or $state.calls.Count) { throw 'An HTTP Server package URL reached the publisher.' }
    $fixture.downloadUrl = 'https://cloud.example/client.zip'
    $fixture | ConvertTo-Json | Set-Content -LiteralPath $manifest -Encoding UTF8
    Write-Host 'PASS: Server manifest publication rejects insecure download URLs.'
    $state.expectedName = 'update.json'
    $state.expectedUri = 'https://rm.example/base/update.json'
    $state.calls.Clear()
    $state.seed = $true
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -RM $rmConfig -SeedHistory
    if (($state.calls -join ',') -cne 'stage history writer,record') { throw 'History initialization replaced the active manifest.' }
    $state.seed = $false
    Write-Host 'PASS: History initialization uses the currently active manifests.'
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
