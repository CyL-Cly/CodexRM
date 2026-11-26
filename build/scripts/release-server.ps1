param(
    [string]$Config,
    [string]$ReleaseNotes = '',
    [switch]$Publish,
    [switch]$PublishOnly
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'release-update-flags.ps1')
if ($Publish -and $PublishOnly) { throw 'Choose either -Publish or -PublishOnly.' }
$buildRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $buildRoot
if (-not $Config) { $Config = Join-Path $buildRoot 'publish.config.json' }
$Config = [IO.Path]::GetFullPath($Config)
$dist = Join-Path $buildRoot 'dist'
$bin = Join-Path $buildRoot 'bin'
[void][IO.Directory]::CreateDirectory($dist)
[void][IO.Directory]::CreateDirectory($bin)
$manifest = Join-Path $dist 'server-update.json'
$manifestScript = Join-Path $PSScriptRoot 'publish-manifest.ps1'
$utf8 = New-Object Text.UTF8Encoding $false
$stage = $null
$lock = $null
Push-Location $projectRoot
try {
    try { $lock = [IO.File]::Open((Join-Path $bin '.server-release.lock'), 'OpenOrCreate', 'ReadWrite', 'None') }
    catch { throw 'Another Server release is running, or its lock cannot be opened.' }
    $text = Get-Content -LiteralPath 'internal/release/server.go' -Raw -Encoding UTF8
    $codeMatch = [regex]::Match($text, '(?m)^const ServerVersionCode int64 = ([1-9][0-9]*)\s*$')
    $nameMatch = [regex]::Match($text, '(?m)^const ServerVersionName = "(\d+\.\d+\.\d+)"\s*$')
    if (-not $codeMatch.Success -or -not $nameMatch.Success) { throw 'Unsupported Server version format.' }
    $code = [long]$codeMatch.Groups[1].Value
    $name = $nameMatch.Groups[1].Value
    $base = "CodexRM-Server-linux-amd64-v$name-$code"
    $archive = Join-Path $dist ("$base.zip")
    $installer = Join-Path $dist ("$base.tar.gz")

    if ($Publish -or $PublishOnly) {
        $cfg = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
        & $manifestScript -RM $cfg.rm -Name 'server-update.json' -ValidateOnly
    }
    if (-not $PublishOnly) {
        $stage = Join-Path $dist ('.codexrm-server-' + [Guid]::NewGuid().ToString('N'))
        $bundle = Join-Path $stage 'bundle'
        [void][IO.Directory]::CreateDirectory($bundle)
        $webDir = Join-Path $projectRoot 'web'
        $files = @(& (Join-Path $PSScriptRoot 'get-server-web-files.ps1') -WebDir $webDir)
        $previousOS, $previousArch, $previousCGO = $env:GOOS, $env:GOARCH, $env:CGO_ENABLED
        try {
            $env:GOOS = 'linux'; $env:GOARCH = 'amd64'; $env:CGO_ENABLED = '0'
            foreach ($target in @('codexrm-server', 'codexrm-server-updater')) {
                $ldflags = '-s -w'
                if ($target -eq 'codexrm-server-updater') { $ldflags += (Get-ReleaseUpdateLinkerFlags) }
                go build -trimpath -ldflags $ldflags -o (Join-Path $bundle $target) "./cmd/$target"
                if ($LASTEXITCODE) { throw "$target build failed." }
            }
        } finally {
            $env:GOOS = $previousOS; $env:GOARCH = $previousArch; $env:CGO_ENABLED = $previousCGO
        }
        $entries = @('codexrm-server')
        foreach ($file in $files) {
            $entry = "web/$file"
            $path = Join-Path $bundle $entry
            [void][IO.Directory]::CreateDirectory((Split-Path -Parent $path))
            Copy-Item -LiteralPath (Join-Path $webDir $file) -Destination $path
            $entries += $entry
        }
        Add-Type -AssemblyName System.IO.Compression
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $archiveNext = Join-Path $stage 'server.zip'
        $zip = [IO.Compression.ZipFile]::Open($archiveNext, [IO.Compression.ZipArchiveMode]::Create)
        try {
            foreach ($entry in $entries) {
                [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $bundle $entry), $entry)
            }
        } finally { $zip.Dispose() }
        $deploy = Join-Path $bundle 'deploy'
        [void][IO.Directory]::CreateDirectory($deploy)
        foreach ($unit in @('codexrm.service','codexrm-server-update.service','codexrm-server-update.timer','codexrm-server-update.env.example')) {
            Copy-Item -LiteralPath (Join-Path $projectRoot "deploy/$unit") -Destination $deploy
        }
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'install-server.sh') -Destination $bundle
        $installerNext = Join-Path $stage 'installer.tar.gz'
        & tar -czf $installerNext -C $bundle codexrm-server codexrm-server-updater install-server.sh web deploy
        if ($LASTEXITCODE) { throw 'Server installer archive creation failed.' }
        $meta = [ordered]@{
            versionCode = $code
            versionName = $name
            downloadUrl = "updates/$base.zip"
            releaseNotes = $ReleaseNotes
            fileSize = (Get-Item -LiteralPath $archiveNext).Length
            sha256 = (Get-FileHash -LiteralPath $archiveNext -Algorithm SHA256).Hash.ToLowerInvariant()
        }
        [IO.File]::Copy($archiveNext, $archive, $true)
        [IO.File]::Copy($installerNext, $installer, $true)
        [IO.File]::WriteAllText($manifest, ($meta | ConvertTo-Json), $utf8)
        Write-Host "Server installer: $installer"
        Write-Host "Server update package: $archive"
        Write-Host "Server manifest: $manifest"
    }
    if ($Publish -or $PublishOnly) {
        $local = Get-Content -LiteralPath $manifest -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($local.versionCode -ne $code -or $local.versionName -cne $name -or -not (Test-Path -LiteralPath $archive -PathType Leaf)) {
            throw 'The staged Server package does not match the source Server version. Build it before publishing.'
        }
        $releaseURL = ([string]$cfg.rm.server).TrimEnd('/') + '/server-update.json'
        $published = $null
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $releaseURL -Headers @{'Cache-Control'='no-cache'} -TimeoutSec 30
            $published = $response.Content | ConvertFrom-Json
        } catch {
            if (-not $_.Exception.Response -or [int]$_.Exception.Response.StatusCode -ne 404) { throw }
        }
        if ($published -and ($published.versionCode -gt $code -or
                ($published.versionCode -eq $code -and $published.sha256 -ne $local.sha256))) {
            throw 'The feed already has this or a higher Server version with different content. Increase ServerVersionCode before release.'
        }
        $publisher = Join-Path $bin $(if ($env:OS -eq 'Windows_NT') { 'codexrm-server-publish.exe' } else { 'codexrm-server-publish' })
        go build -trimpath -ldflags '-s -w' -o $publisher ./cmd/codexrm-publish
        if ($LASTEXITCODE) { throw 'Publisher build failed.' }
        & $publisher -config $Config -package $archive -manifest $manifest
        if ($LASTEXITCODE) { throw 'Server ZIP publication failed. Retry with -PublishOnly.' }
        & $manifestScript -Manifest $manifest -RM $cfg.rm -Name 'server-update.json'
    }
} finally {
    if ($stage) {
        $absolute = [IO.Path]::GetFullPath($stage)
        if (([IO.Path]::GetDirectoryName($absolute) -ceq [IO.Path]::GetFullPath($dist)) -and
            [IO.Path]::GetFileName($absolute) -cmatch '^\.codexrm-server-[0-9a-f]{32}$' -and (Test-Path -LiteralPath $absolute)) {
            Remove-Item -LiteralPath $absolute -Recurse -Force
        }
    }
    if ($lock) { $lock.Dispose() }
    Pop-Location
}
