param(
    [string]$Manifest,
    [Parameter(Mandatory=$true)]$RM,
    [ValidateSet('update.json','android-update.json')][string]$Name = 'update.json',
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
$directory = ([string]$RM.updateDir).TrimEnd('/')
$owner = if ($RM.owner) { [string]$RM.owner } else { 'codexrm' }
if ($sshTarget -notmatch '^[A-Za-z0-9_][A-Za-z0-9_.@-]*$') { throw 'rm.sshTarget must be an SSH alias or user@host.' }
if ($directory -notmatch '^(/[A-Za-z0-9_.-]+)+$' -or @($directory.Split('/') | Where-Object { $_ -in @('.', '..') }).Count) {
    throw 'rm.updateDir must be an absolute Linux directory without parent traversal.'
}
if ($owner -notmatch '^[A-Za-z_][A-Za-z0-9_-]*$') { throw 'rm.owner must be the server service account.' }
foreach ($command in @('ssh', 'scp')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required publication command missing: $command" }
}
if ($ValidateOnly) { return }
$Manifest = [IO.Path]::GetFullPath($Manifest)
$expected = Get-Content -LiteralPath $Manifest -Raw -Encoding UTF8 | ConvertFrom-Json
$remoteStage = $directory + '/.update-' + [Guid]::NewGuid().ToString('N') + '.next'
$sshOptions = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10')
& ssh @sshOptions $sshTarget "install -d -o '$owner' -g '$owner' -m 0700 '$directory'"
if ($LASTEXITCODE) { throw 'Could not prepare the RM update directory.' }
& scp @sshOptions -q $Manifest "${sshTarget}:$remoteStage"
if ($LASTEXITCODE) { throw "Could not upload $Name to RM. The previous manifest is retained." }
$activate = "chown '${owner}:${owner}' '$remoteStage' && chmod 0600 '$remoteStage' && " +
    "{ test ! -f '$directory/$Name' || cp -p '$directory/$Name' '$directory/$Name.previous'; } && " +
    "mv -f '$remoteStage' '$directory/$Name'"
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
