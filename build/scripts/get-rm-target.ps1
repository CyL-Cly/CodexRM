param([Parameter(Mandatory=$true)]$RM)

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
[pscustomobject]@{
    Server = $server
    SshTarget = $sshTarget
    InstallDir = $installDir
}
