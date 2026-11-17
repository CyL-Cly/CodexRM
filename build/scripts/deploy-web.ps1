param(
    [string]$WebDir,
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
foreach ($command in @('ssh', 'scp', 'tar')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required frontend deployment command missing: $command" }
}
if (-not $WebDir) { $WebDir = Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'web' }
$WebDir = [IO.Path]::GetFullPath($WebDir)
$files = @('index.html', 'app.js', 'styles.css', 'pwa.js', 'sw.js', 'manifest.webmanifest')
foreach ($file in @($files + @('icons/icon.svg', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-512.png'))) {
    if (-not (Test-Path -LiteralPath (Join-Path $WebDir $file) -PathType Leaf)) { throw "Frontend asset missing: $file" }
}
$files += @(Get-ChildItem -LiteralPath (Join-Path $WebDir 'icons') -Recurse -File | ForEach-Object {
    $_.FullName.Substring($WebDir.TrimEnd('\', '/').Length + 1).Replace('\', '/')
})
if ($ValidateOnly) { return }

$id = [Guid]::NewGuid().ToString('N')
$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$workDir = Join-Path $tempRoot ('CodexRM-web-deploy-' + $id)
[void][IO.Directory]::CreateDirectory($workDir)
$archive = Join-Path $workDir ('.codexrm-web-' + $id + '.tar.gz')
$script = Join-Path $workDir ('.codexrm-web-' + $id + '.sh')
$backup = "$installDir/web.previous-$id"
$sshOptions = @('-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10')
try {
    $hashes = @{}
    foreach ($file in $files) { $hashes[$file] = (Get-FileHash -LiteralPath (Join-Path $WebDir $file) -Algorithm SHA256).Hash }
    & tar -czf $archive -C $WebDir @files
    if ($LASTEXITCODE) { throw 'Could not package frontend assets.' }
    $archiveHash = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    $activation = @'
#!/usr/bin/env bash
set -euo pipefail
root='__ROOT__'
id='__ID__'
archive="$root/.codexrm-web-$id.tar.gz"
stage="$root/.web-$id.next"
target="$root/web"
backup="$root/web.previous-$id"
printf '%s  %s\n' '__HASH__' "$archive" | sha256sum --check --status
test ! -e "$stage"
test ! -e "$backup"
install -d -m 0755 "$stage"
tar --extract --gzip --file "$archive" --directory "$stage" --no-same-owner
test -f "$stage/index.html"
test -f "$stage/app.js"
test -f "$stage/sw.js"
find "$stage" -type d -exec chmod 0755 {} +
find "$stage" -type f -exec chmod 0644 {} +
if test -e "$target"; then mv "$target" "$backup"; fi
if ! mv "$stage" "$target"; then
    if test -e "$backup"; then mv "$backup" "$target"; fi
    exit 1
fi
printf 'Frontend backup: %s\n' "$backup"
rm -f -- "$archive" "$0"
'@
    $activation = $activation.Replace('__ROOT__', $installDir).Replace('__ID__', $id).Replace('__HASH__', $archiveHash).Replace("`r`n", "`n")
    [IO.File]::WriteAllText($script, $activation + "`n", (New-Object Text.UTF8Encoding $false))
    & ssh @sshOptions $sshTarget "test -d '$installDir'"
    if ($LASTEXITCODE) { throw 'The RM installation directory is unavailable.' }
    & scp @sshOptions -q $archive $script "${sshTarget}:$installDir/"
    if ($LASTEXITCODE) { throw 'Could not upload frontend assets. The current frontend was not changed.' }
    & ssh @sshOptions $sshTarget ("bash '$installDir/" + [IO.Path]::GetFileName($script) + "'")
    if ($LASTEXITCODE) { throw "Could not activate the frontend. Previous files are retained at $backup if replacement started." }

    foreach ($file in $files) {
        $path = if ($file -eq 'index.html') { '/' } else { '/' + $file }
        $url = $server + $path + '?deploy=' + $id
        $download = Join-Path $workDir 'verify-download'
        Invoke-WebRequest -UseBasicParsing -Uri $url -Headers @{'Cache-Control'='no-cache'} -OutFile $download -TimeoutSec 30
        if ((Get-FileHash -LiteralPath $download -Algorithm SHA256).Hash -cne $hashes[$file]) {
            throw "Public frontend hash mismatch: $file. Previous files are retained at $backup."
        }
    }
    Write-Host "RM frontend deployed and verified: $server ($($files.Count) assets)"
} finally {
    $resolvedWork = [IO.Path]::GetFullPath($workDir)
    if ($resolvedWork.StartsWith($tempRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($resolvedWork) -ceq ('CodexRM-web-deploy-' + $id)) {
        Remove-Item -LiteralPath $resolvedWork -Recurse -Force
    }
}
