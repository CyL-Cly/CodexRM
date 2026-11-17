param(
    [string]$Binary,
    [Parameter(Mandatory=$true)]$RM,
    [switch]$ValidateOnly
)
$ErrorActionPreference = 'Stop'
$server = ([string]$RM.server).TrimEnd('/')
$serverUri = $null
if (-not [Uri]::TryCreate($server, [UriKind]::Absolute, [ref]$serverUri) -or
    $serverUri.Scheme -notin @('https', 'http') -or $serverUri.UserInfo -or $serverUri.Query -or $serverUri.Fragment) {
    throw 'rm.server must be the RM HTTP(S) base URL.'
}
$sshTarget = [string]$RM.sshTarget
if ($sshTarget -notmatch '^[A-Za-z0-9_][A-Za-z0-9_.@-]*$') { throw 'rm.sshTarget must be an SSH alias or user@host.' }
$installDir = if ($RM.installDir) { ([string]$RM.installDir).TrimEnd('/') } else { '/opt/codexrm' }
if ($installDir -notmatch '^(/[A-Za-z0-9_.-]+)+$' -or @($installDir.Split('/') | Where-Object { $_ -in @('.', '..') }).Count) {
    throw 'rm.installDir must be an absolute Linux directory without parent traversal.'
}
$service = if ($RM.service) { [string]$RM.service } else { 'codexrm' }
if ($service -notmatch '^[A-Za-z_][A-Za-z0-9_.-]*$') { throw 'rm.service must be a valid systemd service name.' }
foreach ($command in @('ssh', 'scp')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required deployment command missing: $command" }
}
if ($ValidateOnly) { return }
if (-not $Binary) { throw 'The Linux Server binary path is required.' }
$Binary = [IO.Path]::GetFullPath($Binary)
if (-not (Test-Path -LiteralPath $Binary -PathType Leaf)) { throw "Linux Server binary not found: $Binary" }

$target = $installDir + '/bin/codexrm-server'
$previous = $target + '.previous'
$remoteStage = $installDir + '/bin/.codexrm-server-' + [Guid]::NewGuid().ToString('N') + '.next'
$sshOptions = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10')
& ssh @sshOptions $sshTarget "install -d -m 0755 '$installDir/bin'"
if ($LASTEXITCODE) { throw 'Could not prepare the RM Server binary directory.' }
& scp @sshOptions -q $Binary "${sshTarget}:$remoteStage"
if ($LASTEXITCODE) { throw 'Could not upload the RM Server binary. The running Server was not changed.' }
$activate = "chmod 0755 '$remoteStage' && " +
    "{ test ! -f '$target' || cp -p '$target' '$previous'; } && " +
    "mv -f '$remoteStage' '$target' && systemctl restart '$service' && systemctl is-active --quiet '$service'"
& ssh @sshOptions $sshTarget $activate
if ($LASTEXITCODE) {
    throw "Could not activate or restart the RM Server. If activation failed after replacement, the previous binary is $previous."
}

$url = $server + '/api/v1/health'
$lastError = $null
foreach ($attempt in 1..10) {
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers @{'Cache-Control'='no-cache'} -TimeoutSec 10
        $actual = $response.Content | ConvertFrom-Json
        if ($actual.ok -eq $true -and $actual.service -ceq 'codexrm') {
            Write-Host "RM Server deployed and verified: $url"
            return
        }
        $lastError = 'the health endpoint returned an unexpected response'
    } catch { $lastError = $_.Exception.Message }
    if ($attempt -lt 10) { Start-Sleep -Seconds 1 }
}
throw "RM Server was uploaded and restarted, but public health verification failed: $lastError"
