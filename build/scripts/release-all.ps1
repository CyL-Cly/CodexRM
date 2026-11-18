param(
    [Parameter(Position=0)][ValidateSet('nopause')][string]$PauseMode,
    [string]$Config,
    [string]$ReleaseNotes = '',
    [switch]$LinuxOnly,
    [switch]$Help
)
$ErrorActionPreference = 'Stop'
if ($Help) {
    Write-Host 'build\release-all.cmd [nopause] [-Config PATH] [-ReleaseNotes TEXT] [-LinuxOnly]'
    Write-Host 'Default: build and publish Windows Agent, RM, Web, Android, and the standalone Linux Agent.'
    Write-Host '-LinuxOnly: publish the standalone Linux Agent at the current version without bumping versions.'
    return
}
$buildRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $buildRoot
if (-not $Config) { $Config = Join-Path $buildRoot 'publish.config.json' }
$Config = [IO.Path]::GetFullPath($Config)
$cfg = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $cfg.cloudreve.base -or -not $cfg.cloudreve.email -or (-not $cfg.cloudreve.password -and -not $env:CLOUDREVE_PASSWORD)) {
    throw 'Configure Cloudreve base/email/password (or CLOUDREVE_PASSWORD) first.'
}

function Publish-LinuxAgent {
    $versionPath = Join-Path $projectRoot 'internal\release\release.go'
    $text = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8
    $code = [regex]::Match($text, 'const VersionCode int64 = (\d+)')
    $name = [regex]::Match($text, 'const VersionName = "(\d+\.\d+\.\d+)"')
    if (-not $code.Success -or -not $name.Success) { throw 'Unsupported Agent version format.' }
    $versionCode = [long]$code.Groups[1].Value
    $versionName = $name.Groups[1].Value
    $packageName = "CodexRM-Agent-linux-amd64-v$versionName-$versionCode.zip"
    $dist = Join-Path $buildRoot 'dist'
    $archive = Join-Path $dist $packageName
    $manifest = Join-Path $dist 'linux-agent-download.json'
    [void][IO.Directory]::CreateDirectory($dist)

    & (Join-Path $PSScriptRoot 'build.ps1') -Linux -Target Agent
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $staged = $archive + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        $zip = [IO.Compression.ZipFile]::Open($staged, [IO.Compression.ZipArchiveMode]::Create)
        try {
            [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $buildRoot 'bin\codexrm-agent-linux-amd64'), 'codexrm-agent')
            $writer = New-Object IO.StreamWriter($zip.CreateEntry('README.md').Open())
            try { $writer.WriteLine('Unpack, run chmod +x codexrm-agent, then run ./codexrm-agent --help.') } finally { $writer.Dispose() }
        } finally { $zip.Dispose() }
        [IO.File]::Copy($staged, $archive, $true)
    } finally {
        if (Test-Path -LiteralPath $staged -PathType Leaf) { Remove-Item -LiteralPath $staged }
    }
    $meta = [ordered]@{
        versionCode = $versionCode
        versionName = $versionName
        downloadUrl = 'updates/' + $packageName
        releaseNotes = $ReleaseNotes
        fileSize = (Get-Item -LiteralPath $archive).Length
        sha256 = (Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    [IO.File]::WriteAllText($manifest, ($meta | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
    $publisher = Join-Path $buildRoot 'bin\codexrm-publish.exe'
    go build -trimpath -ldflags '-s -w' -o $publisher ./cmd/codexrm-publish
    if ($LASTEXITCODE) { throw 'Publisher build failed.' }
    & $publisher -config $Config -package $archive -manifest $manifest
    if ($LASTEXITCODE) { throw 'Standalone Linux Agent publication failed; retry with -LinuxOnly.' }
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -RM $cfg.rm -Name 'linux-update.json'
}

$lock = $null
Push-Location $projectRoot
try {
    $bin = Join-Path $buildRoot 'bin'
    [void][IO.Directory]::CreateDirectory($bin)
    try {
        $lock = [IO.File]::Open((Join-Path $bin '.all-release.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
    } catch { throw 'Another all-platform release is running, or the release lock cannot be opened.' }
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -RM $cfg.rm -Name 'linux-update.json' -ValidateOnly
    if (-not $LinuxOnly) {
        & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -RM $cfg.rm -ValidateOnly
        & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -RM $cfg.rm -Name 'android-update.json' -ValidateOnly
        & (Join-Path $PSScriptRoot 'deploy-server.ps1') -RM $cfg.rm -ValidateOnly
        & (Join-Path $PSScriptRoot 'deploy-web.ps1') -RM $cfg.rm -ValidateOnly
        & (Join-Path $PSScriptRoot 'release-android.ps1') -BuildOnly -Config $Config -ReleaseNotes $ReleaseNotes
        try {
            & (Join-Path $PSScriptRoot 'release.ps1') -DeployServer -Config $Config -ReleaseNotes $ReleaseNotes
        } catch {
            Write-Warning 'Android package is staged, not published. Resolve the Windows/RM step, then use android\build.bat nopause -PublishOnly and build\release-all.cmd nopause -LinuxOnly.'
            throw
        }
        & (Join-Path $PSScriptRoot 'release-android.ps1') -PublishOnly -Config $Config
    }
    Publish-LinuxAgent
    Write-Host 'Release completed.' -ForegroundColor Green
} finally {
    if ($lock) { $lock.Dispose() }
    Pop-Location
}
