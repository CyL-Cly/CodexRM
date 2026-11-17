param(
    [string]$Config,
    [string]$ReleaseNotes = '',
    [switch]$PublishOnly,
    [switch]$DeployServer,
    [switch]$TestLocalUpdate,
    [string]$LocalInstallDir
)
$ErrorActionPreference = 'Stop'
if ($LocalInstallDir -and -not $TestLocalUpdate) { throw '-LocalInstallDir requires -TestLocalUpdate.' }
$buildRoot = Split-Path -Parent $PSScriptRoot
$projectRoot = Split-Path -Parent $buildRoot
if (-not $Config) { $Config = Join-Path $buildRoot 'publish.config.json' }
$Config = [IO.Path]::GetFullPath($Config)
if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) {
    throw 'Copy build/publish.config.example.json to build/publish.config.json and configure Cloudreve first.'
}
$publishConfig = Get-Content -LiteralPath $Config -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not $publishConfig.rm) { throw 'Configure rm.server, rm.sshTarget and rm.updateDir in build/publish.config.json.' }
& (Join-Path $PSScriptRoot 'publish-manifest.ps1') -RM $publishConfig.rm -ValidateOnly
if ($DeployServer) {
    & (Join-Path $PSScriptRoot 'deploy-server.ps1') -RM $publishConfig.rm -ValidateOnly
    & (Join-Path $PSScriptRoot 'deploy-web.ps1') -RM $publishConfig.rm -ValidateOnly
}
$localUpdateArguments = @{}
if ($LocalInstallDir) { $localUpdateArguments.InstallDir = $LocalInstallDir }
$versionPath = Join-Path $projectRoot 'internal\release\release.go'
$originalVersion = [IO.File]::ReadAllBytes($versionPath)
$publisher = Join-Path $buildRoot 'bin\codexrm-publish.exe'
$builtPackage = $false
$versionChanged = $false
Push-Location $projectRoot
try {
    $text = [Text.Encoding]::UTF8.GetString($originalVersion)
    $codeMatch = [regex]::Match($text, 'const VersionCode int64 = (\d+)')
    $nameMatch = [regex]::Match($text, 'const VersionName = "(\d+\.\d+\.\d+)"')
    if (-not $codeMatch.Success -or -not $nameMatch.Success) { throw 'Unsupported release version format.' }
    $code = [long]$codeMatch.Groups[1].Value
    $name = $nameMatch.Groups[1].Value
    if (-not $PublishOnly) {
        $code++
        $parts = $name.Split('.')
        $parts[2] = [string]([long]$parts[2] + 1)
        $name = $parts -join '.'
        $text = [regex]::Replace($text, 'const VersionCode int64 = \d+', 'const VersionCode int64 = ' + $code)
        $text = [regex]::Replace($text, 'const VersionName = "\d+\.\d+\.\d+"', 'const VersionName = "' + $name + '"')
    }
    if ($TestLocalUpdate) {
        & (Join-Path $PSScriptRoot 'update-local-client.ps1') -ExpectedVersionCode $code -ExpectedVersionName $name -ValidateOnly @localUpdateArguments
    }
    if (-not $PublishOnly) {
        [IO.File]::WriteAllText($versionPath, $text, (New-Object Text.UTF8Encoding $false))
        $versionChanged = $true
    }
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $publisher))
    go build -trimpath -ldflags '-s -w' -o $publisher ./cmd/codexrm-publish
    if ($LASTEXITCODE) { throw 'Publisher build failed.' }
    $archive = Join-Path $buildRoot ("dist\CodexRM-windows-amd64-v$name-$code.zip")
    $manifest = Join-Path $buildRoot 'dist\update.json'
    Write-Host "Releasing CodexRM $name (code $code)"
    $serverBinary = Join-Path $buildRoot 'bin\codexrm-server-linux-amd64'
    if (-not $PublishOnly) {
        & (Join-Path $PSScriptRoot 'build.ps1') -Target Agent
        if ($DeployServer) { & (Join-Path $PSScriptRoot 'build.ps1') -Linux -Target Server }
        & (Join-Path $PSScriptRoot 'package-client.ps1') -OutputPath $archive -Server $publishConfig.rm.server -ReleaseNotes $ReleaseNotes
    } elseif (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or -not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
        throw 'No built package/update.json for this version. Run build/release.cmd first.'
    }
    $builtPackage = $true
    if ($DeployServer -and -not (Test-Path -LiteralPath $serverBinary -PathType Leaf)) {
        throw 'No built Linux Server binary. Run with -DeployServer without -PublishOnly first.'
    }
    $updateSource = ([string]$publishConfig.rm.server).TrimEnd('/') + '/update.json'
    & (Join-Path $PSScriptRoot 'package-setup.ps1') -Manifest $updateSource
    & $publisher -config $Config -package $archive -manifest $manifest
    if ($LASTEXITCODE) { throw 'Cloudreve publication failed.' }
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -RM $publishConfig.rm
    Write-Host "Released: $archive"
    Write-Host "Online installer: $(Join-Path $buildRoot 'dist\CodexRM-Setup.exe')"
    Write-Host "Update source: $updateSource"
    if ($DeployServer) {
        & (Join-Path $PSScriptRoot 'deploy-server.ps1') -Binary $serverBinary -RM $publishConfig.rm
        & (Join-Path $PSScriptRoot 'deploy-web.ps1') -RM $publishConfig.rm
    }
    if ($TestLocalUpdate) {
        & (Join-Path $PSScriptRoot 'update-local-client.ps1') -ExpectedVersionCode $code -ExpectedVersionName $name @localUpdateArguments
    }
} catch {
    if ($versionChanged -and -not $builtPackage) {
        [IO.File]::WriteAllBytes($versionPath, $originalVersion)
        Write-Host 'Build/package failed; release version restored.' -ForegroundColor Yellow
    } elseif ($builtPackage) {
        $retry = 'build\release.cmd nopause -PublishOnly'
        if ($DeployServer) { $retry += ' -DeployServer' }
        if ($TestLocalUpdate) { $retry += ' -TestLocalUpdate' }
        if ($LocalInstallDir) { $retry += ' -LocalInstallDir "' + $LocalInstallDir + '"' }
        Write-Host "Package retained. Retry without another version bump: $retry" -ForegroundColor Yellow
    }
    throw
} finally { Pop-Location }
