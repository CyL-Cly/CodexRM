param(
    [string]$Manifest,
    [Parameter(Mandatory=$true)]$RM,
    [ValidateSet('update.json','android-update.json','linux-update.json','server-update.json')][string]$Name = 'update.json',
    [switch]$ValidateOnly,
    [switch]$SeedHistory
)
$ErrorActionPreference = 'Stop'
$rmTarget = & (Join-Path $PSScriptRoot 'get-rm-target.ps1') -RM $RM
$server = $rmTarget.Server
$sshTarget = $rmTarget.SshTarget
$installDir = $rmTarget.InstallDir
$isServer = $Name -eq 'server-update.json'
$directory = if ($isServer) { $installDir + '/server-updates' } else { ([string]$RM.updateDir).TrimEnd('/') }
if ($isServer -and (-not $server.StartsWith('https://', [StringComparison]::OrdinalIgnoreCase) -or $SeedHistory)) {
    throw 'Server manifest publication requires an HTTPS RM URL and does not use client release history.'
}
$owner = if ($RM.owner) { [string]$RM.owner } else { 'codexrm' }
if ($directory -notmatch '^(/[A-Za-z0-9_.-]+)+$' -or @($directory.Split('/') | Where-Object { $_ -in @('.', '..') }).Count) {
    throw 'rm.updateDir must be an absolute Linux directory without parent traversal.'
}
if (-not $isServer -and $owner -notmatch '^[A-Za-z_][A-Za-z0-9_-]*$') { throw 'rm.owner must be the server service account.' }
foreach ($command in @('ssh', 'scp')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required publication command missing: $command" }
}
if ($ValidateOnly) { return }
$sshOptions = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10')

function Record-ReleaseHistory($expected) {
    $historyScript = Join-Path $PSScriptRoot 'record-release-history.py'
    $remoteScript = $directory + '/.record-release-' + [Guid]::NewGuid().ToString('N') + '.py'
    $arguments = "'$directory' '$installDir/web' '$owner'"
    if ($null -ne $expected) {
        $code = [long]$expected.versionCode
        $sha = ([string]$expected.sha256).ToLowerInvariant()
        if ($code -le 0 -or $sha -notmatch '^[0-9a-f]{64}$') { throw 'Invalid version code or hash in published manifest.' }
        $arguments += " '$Name' '$code' '$sha'"
    }
    & scp @sshOptions -q $historyScript "${sshTarget}:$remoteScript"
    if ($LASTEXITCODE) { throw 'Could not stage the RM release history writer.' }
    & ssh @sshOptions $sshTarget "python3 '$remoteScript' $arguments; result=`$?; rm -f -- '$remoteScript'; exit `$result"
    if ($LASTEXITCODE) { throw 'Could not record the RM release history.' }
}

if ($SeedHistory) {
    Record-ReleaseHistory $null
    Write-Host 'RM release history initialized from the active manifests.'
    return
}
$Manifest = [IO.Path]::GetFullPath($Manifest)
$expected = Get-Content -LiteralPath $Manifest -Raw -Encoding UTF8 | ConvertFrom-Json
if ($isServer) {
    $packageUri = $null
    if (-not [Uri]::TryCreate([string]$expected.downloadUrl, [UriKind]::Absolute, [ref]$packageUri) -or
        $packageUri.Scheme -ne 'https' -or $packageUri.UserInfo -or $packageUri.Fragment) {
        throw 'The published Server package URL must use HTTPS and must not include credentials or a fragment.'
    }
}
$remoteStage = $directory + '/.update-' + [Guid]::NewGuid().ToString('N') + '.next'
$prepare = if ($isServer) {
    "test ! -L '$directory' && install -d -o root -g root -m 0755 '$directory'"
} else { "install -d -o '$owner' -g '$owner' -m 0700 '$directory'" }
& ssh @sshOptions $sshTarget $prepare
if ($LASTEXITCODE) { throw 'Could not prepare the RM update directory.' }
& scp @sshOptions -q $Manifest "${sshTarget}:$remoteStage"
if ($LASTEXITCODE) { throw "Could not upload $Name to RM. The previous manifest is retained." }
$activate = "chown '${owner}:${owner}' '$remoteStage' && chmod 0600 '$remoteStage' && " +
    "{ test ! -f '$directory/$Name' || cp -p '$directory/$Name' '$directory/$Name.previous'; } && " +
    "mv -f '$remoteStage' '$directory/$Name'"
if ($isServer) {
    $activate = "chown root:root '$remoteStage' && chmod 0644 '$remoteStage' && mv -fT '$remoteStage' '$directory/$Name'"
}
& ssh @sshOptions $sshTarget $activate
if ($LASTEXITCODE) { throw "Could not activate $Name on RM." }
$url = $server + '/' + $Name
$response = Invoke-WebRequest -UseBasicParsing -Uri $url -Headers @{'Cache-Control'='no-cache'} -TimeoutSec 30
$actual = $response.Content | ConvertFrom-Json
foreach ($field in @('versionCode','versionName','downloadUrl','releaseNotes','fileSize','sha256')) {
    if ($actual.$field -cne $expected.$field) { throw "RM manifest verification failed: $field" }
}
if ($response.Headers['Cache-Control'] -notmatch 'no-store') { throw "RM $Name must be served with Cache-Control: no-store." }
Write-Host "RM manifest published and verified: $url"
if ($isServer) { return }
Record-ReleaseHistory $expected
Write-Host 'RM release history updated.'
