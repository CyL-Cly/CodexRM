# Deployment checks replace network commands; no remote host is contacted.
$ErrorActionPreference = 'Stop'
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $tempRoot ('CodexRM-deploy-tests-' + [Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$binary = Join-Path $testRoot 'codexrm-server-linux-amd64'
Set-Content -LiteralPath $binary -Value 'test server binary' -Encoding UTF8
$rmConfig = [pscustomobject]@{
    server = 'https://rm.example/base'
    sshTarget = 'test-rm'
    installDir = '/opt/codexrm'
    service = 'codexrm'
}
$state = @{calls=(New-Object Collections.Generic.List[string]);mode='success'}
function ssh {
    $command = [string]$args[-1]
    $state.calls.Add($(if ($command.Contains('mv -f')) { 'activate' } else { 'prepare' }))
    $global:LASTEXITCODE = $(if ($state.mode -eq 'activation failure' -and $command.Contains('mv -f')) { 1 } else { 0 })
}
function scp {
    $state.calls.Add('upload')
    $global:LASTEXITCODE = $(if ($state.mode -eq 'upload failure') { 1 } else { 0 })
}
function Invoke-WebRequest {
    param([switch]$UseBasicParsing,$Uri,$Headers,$TimeoutSec)
    $state.calls.Add('verify')
    if ($Uri -cne 'https://rm.example/base/api/v1/health') { throw 'Deployment verified the wrong health endpoint.' }
    if ($state.mode -eq 'bad health') { return [pscustomobject]@{Content='{"ok":false,"service":"other"}'} }
    return [pscustomobject]@{Content='{"ok":true,"service":"codexrm","version":"test"}'}
}
function Start-Sleep { param($Seconds) }
try {
    & (Join-Path $PSScriptRoot 'deploy-server.ps1') -Binary $binary -RM $rmConfig
    if (($state.calls -join ',') -cne 'prepare,upload,activate,verify') { throw "Unexpected deployment order: $($state.calls -join ',')" }
    Write-Host 'PASS: Server is staged, atomically activated, restarted, and verified through the RM base URL.'

    $state.calls.Clear()
    $state.mode = 'upload failure'
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'deploy-server.ps1') -Binary $binary -RM $rmConfig } catch { $rejected = $true }
    if (-not $rejected -or ($state.calls -join ',') -cne 'prepare,upload') { throw 'A failed Server upload reached activation.' }
    Write-Host 'PASS: Failed Server uploads do not replace or restart the running service.'

    $state.calls.Clear()
    $state.mode = 'activation failure'
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'deploy-server.ps1') -Binary $binary -RM $rmConfig } catch { $rejected = $true }
    if (-not $rejected -or ($state.calls -join ',') -cne 'prepare,upload,activate') { throw 'A failed restart was accepted or reached public verification.' }
    Write-Host 'PASS: Activation and restart failures are reported before health verification.'

    $state.calls.Clear()
    $state.mode = 'bad health'
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'deploy-server.ps1') -Binary $binary -RM $rmConfig } catch { $rejected = $true }
    if (-not $rejected -or @($state.calls | Where-Object { $_ -eq 'verify' }).Count -ne 10) { throw 'An unhealthy Server was accepted without the expected retries.' }
    Write-Host 'PASS: Public health mismatches fail deployment after bounded retries.'

    foreach ($badPath in @('/','/opt/codexrm/../data','/opt/codexrm;false')) {
        $state.calls.Clear()
        $state.mode = 'success'
        $rmConfig.installDir = $badPath
        $rejected = $false
        try { & (Join-Path $PSScriptRoot 'deploy-server.ps1') -Binary $binary -RM $rmConfig } catch { $rejected = $true }
        if (-not $rejected -or $state.calls.Count) { throw 'An unsafe Server installation path reached a remote command.' }
    }
    $rmConfig.installDir = '/opt/codexrm'
    $rmConfig.service = 'codexrm;false'
    $state.calls.Clear()
    $rejected = $false
    try { & (Join-Path $PSScriptRoot 'deploy-server.ps1') -Binary $binary -RM $rmConfig } catch { $rejected = $true }
    if (-not $rejected -or $state.calls.Count) { throw 'An unsafe service name reached a remote command.' }
    Write-Host 'PASS: Invalid installation paths and service names are rejected before remote access.'
} finally {
    $resolvedRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedRoot.StartsWith($tempRoot.TrimEnd('\')+'\',[StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedRoot).StartsWith('CodexRM-deploy-tests-')) {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}
