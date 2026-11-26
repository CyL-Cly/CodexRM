# Deployment checks replace network commands; no remote host is contacted.
$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('CodexRM-web-tests-' + [Guid]::NewGuid().ToString('N'))
$webDir = Join-Path $testRoot 'web'
[void][IO.Directory]::CreateDirectory((Join-Path $webDir 'icons'))
$files = @('index.html','app.js','styles.css','dsh-theme.css','dsh-LICENSE.txt','pwa.js','secure-relay.js','sw.js','theme.js','manifest.webmanifest','icons/icon.svg','icons/icon-192.png','icons/icon-512.png','icons/maskable-512.png')
foreach ($file in $files) { Set-Content -LiteralPath (Join-Path $webDir $file) -Value ('fixture:' + $file) -Encoding UTF8 }
Set-Content -LiteralPath (Join-Path $webDir 'app.test.mjs') -Value 'test only'
Set-Content -LiteralPath (Join-Path $webDir 'private.json') -Value 'not a web asset'
$rmConfig = [pscustomobject]@{server='https://rm.example/base';sshTarget='test-rm';installDir='/opt/codexrm'}
$state = @{calls=(New-Object Collections.Generic.List[string]);mode='success'}
function ssh {
    $activation = ([string]$args[-1]).StartsWith('bash ')
    $state.calls.Add($(if ($activation) { 'activate' } else { 'prepare' }))
    $global:LASTEXITCODE = $(if ($activation -and $state.mode -eq 'activation failure') { 1 } else { 0 })
}
function scp {
    $state.calls.Add('upload')
    $entries = @(& tar -tzf $args[-3])
    if ($LASTEXITCODE -or $entries.Count -ne $files.Count -or @($entries | Where-Object { $_ -like '*.test.*' -or $_ -like '*private*' }).Count) {
        throw 'Frontend archive contains missing or unintended files.'
    }
    $global:LASTEXITCODE = $(if ($state.mode -eq 'upload failure') { 1 } else { 0 })
}
function Invoke-WebRequest {
    param([switch]$UseBasicParsing,$Uri,$Headers,$OutFile,$TimeoutSec)
    $url = [Uri]$Uri
    if (-not $url.AbsolutePath.StartsWith('/base/') -or -not $url.Query.StartsWith('?deploy=')) { throw 'Wrong public asset verification URL.' }
    $file = $url.AbsolutePath.Substring('/base/'.Length)
    if (-not $file) { $file = 'index.html' }
    $state.calls.Add('verify:' + $file)
    Copy-Item -LiteralPath (Join-Path $webDir $file) -Destination $OutFile
    if ($state.mode -eq 'hash failure' -and $file -eq 'app.js') { Add-Content -LiteralPath $OutFile -Value 'stale content' }
}
$deploy = Join-Path $PSScriptRoot 'deploy-web.ps1'
try {
    & $deploy -WebDir $webDir -RM $rmConfig
    if (($state.calls | Select-Object -First 3) -join ',' -cne 'prepare,upload,activate' -or
        @($state.calls | Where-Object { $_ -like 'verify:*' }).Count -ne $files.Count) {
        throw 'Frontend deployment did not stage before activation and verify all public assets.'
    }
    Write-Host 'PASS: Only runtime assets are packaged; activation precedes public byte verification of every asset.'
    foreach ($failure in @('upload failure','activation failure','hash failure')) {
        $state.calls.Clear()
        $state.mode = $failure
        $rejected = $false
        try { & $deploy -WebDir $webDir -RM $rmConfig } catch {
            $rejected = $true
            if ($failure -eq 'hash failure' -and $_.Exception.Message -notlike '*hash mismatch*') { throw }
        }
        if (-not $rejected) { throw "Frontend failure was accepted: $failure" }
        if ($failure -eq 'upload failure' -and ($state.calls -join ',') -cne 'prepare,upload') { throw 'A failed upload reached activation.' }
        if ($failure -eq 'activation failure' -and @($state.calls | Where-Object { $_ -like 'verify:*' }).Count) { throw 'A failed activation reached public verification.' }
    }
    Write-Host 'PASS: Upload, activation and stale public asset failures are reported.'

    $state.mode = 'success'
    foreach ($badPath in @('/','/opt/codexrm/../data','/opt/codexrm;false')) {
        $state.calls.Clear()
        $rmConfig.installDir = $badPath
        $rejected = $false
        try { & $deploy -WebDir $webDir -RM $rmConfig } catch { $rejected = $true }
        if (-not $rejected -or $state.calls.Count) { throw 'Unsafe frontend path reached a remote command.' }
    }
    $rmConfig.installDir = '/opt/codexrm'
    Remove-Item -LiteralPath (Join-Path $webDir 'sw.js')
    $state.calls.Clear()
    $rejected = $false
    try { & $deploy -WebDir $webDir -RM $rmConfig -ValidateOnly } catch { $rejected = $true }
    if (-not $rejected -or $state.calls.Count) { throw 'Missing frontend assets were accepted by preflight.' }
    Write-Host 'PASS: Invalid destinations and missing source assets fail before remote access.'
} finally {
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedRoot.StartsWith($tempRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedRoot).StartsWith('CodexRM-web-tests-')) {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
