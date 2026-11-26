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
. (Join-Path $PSScriptRoot 'component-sources.ps1')
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
$versionPath = Join-Path (Get-CodexRMComponentDir Agent) 'internal/release/release.go'
$publisher = Join-Path $buildRoot 'bin\codexrm-publish.exe'
$builtPackage = $false
function Assert-ReleasePackage([string]$Archive, [string]$Manifest, [long]$Code, [string]$Name) {
    $metadata = Get-Content -LiteralPath $Manifest -Raw -Encoding UTF8 | ConvertFrom-Json
    if ([long]$metadata.versionCode -ne $Code -or [string]$metadata.versionName -cne $Name) {
        throw "dist/update.json version $($metadata.versionName) ($($metadata.versionCode)) does not match release.go $Name ($Code)."
    }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $verificationDir = Join-Path $buildRoot ('.work\release-verify-' + [Guid]::NewGuid().ToString('N'))
    $zip = $null
    try {
        [void][IO.Directory]::CreateDirectory($verificationDir)
        $zip = [IO.Compression.ZipFile]::OpenRead($Archive)
        $entries = @($zip.Entries | Where-Object { $_.FullName.Replace('\', '/') -ceq 'CodexRM/bin/codexrm-agent.exe' })
        if ($entries.Count -ne 1 -or $entries[0].Length -le 0) {
            throw 'The release ZIP must contain exactly one non-empty CodexRM/bin/codexrm-agent.exe.'
        }
        $binary = Join-Path $verificationDir 'codexrm-agent.exe'
        $input = $entries[0].Open()
        try {
            $output = [IO.File]::Create($binary)
            try { $input.CopyTo($output) } finally { $output.Dispose() }
        } finally { $input.Dispose() }
        $zip.Dispose()
        $zip = $null
        . (Join-Path $PSScriptRoot 'client-install.ps1')
        $binaryVersion = Get-CodexRMBinaryVersion $binary
        if ([long]$binaryVersion.versionCode -ne $Code -or [string]$binaryVersion.versionName -cne $Name) {
            throw "ZIP client version $($binaryVersion.versionName) ($($binaryVersion.versionCode)) does not match release.go $Name ($Code)."
        }
    } finally {
        if ($zip) { $zip.Dispose() }
        if (Test-Path -LiteralPath $verificationDir -PathType Container) {
            Remove-Item -LiteralPath $verificationDir -Recurse -Force
        }
    }
}
Push-Location $projectRoot
try {
    $text = Get-Content -LiteralPath $versionPath -Raw -Encoding UTF8
    $codeMatch = [regex]::Match($text, 'const VersionCode int64 = (\d+)')
    $nameMatch = [regex]::Match($text, 'const VersionName = "(\d+\.\d+\.\d+)"')
    if (-not $codeMatch.Success -or -not $nameMatch.Success) { throw 'Unsupported release version format.' }
    $code = [long]$codeMatch.Groups[1].Value
    $name = $nameMatch.Groups[1].Value
    if ($TestLocalUpdate) {
        & (Join-Path $PSScriptRoot 'update-local-client.ps1') -ExpectedVersionCode $code -ExpectedVersionName $name -ValidateOnly @localUpdateArguments
    }
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $publisher))
    Invoke-CodexRMGo build -mod=readonly -trimpath -ldflags '-s -w' -o $publisher ./cmd/codexrm-publish
    if ($LASTEXITCODE) { throw 'Publisher build failed.' }
    $archive = Join-Path $buildRoot ("dist\CodexRM-windows-amd64-v$name-$code.zip")
    $manifest = Join-Path $buildRoot 'dist\update.json'
    Write-Host "Releasing CodexRM $name (code $code)"
    $serverBinary = Join-Path $buildRoot 'bin\codexrm-server-linux-amd64'
    if (-not $PublishOnly) {
        & (Join-Path $PSScriptRoot 'build.ps1') -Target Agent
        if ($DeployServer) { & (Join-Path $PSScriptRoot 'build.ps1') -Linux -Target Server }
        & (Join-Path $PSScriptRoot 'package-client.ps1') -OutputPath $archive -Server $publishConfig.rm.server -ReleaseNotes $ReleaseNotes
    } else {
        if (-not (Test-Path -LiteralPath $archive -PathType Leaf) -or -not (Test-Path -LiteralPath $manifest -PathType Leaf)) {
            throw 'No built package/update.json for this version. Run build/release.cmd first.'
        }
        Assert-ReleasePackage -Archive $archive -Manifest $manifest -Code $code -Name $name
    }
    $builtPackage = $true
    if ($DeployServer -and -not (Test-Path -LiteralPath $serverBinary -PathType Leaf)) {
        throw 'No built Linux Server binary. Run with -DeployServer without -PublishOnly first.'
    }
    $updateSource = ([string]$publishConfig.rm.server).TrimEnd('/') + '/update.json'
    & $publisher -config $Config -package $archive -manifest $manifest
    if ($LASTEXITCODE) { throw 'Cloudreve publication failed.' }
    & (Join-Path $PSScriptRoot 'publish-manifest.ps1') -Manifest $manifest -RM $publishConfig.rm
    Write-Host "Released: $archive"
    if (-not $PublishOnly) { Write-Host "Offline installer: $(Join-Path $buildRoot 'dist\CodexRM-Setup.exe')" }
    Write-Host "Update source: $updateSource"
    if ($DeployServer) {
        & (Join-Path $PSScriptRoot 'deploy-server.ps1') -Binary $serverBinary -RM $publishConfig.rm
        & (Join-Path $PSScriptRoot 'deploy-web.ps1') -RM $publishConfig.rm
    }
    if ($TestLocalUpdate) {
        & (Join-Path $PSScriptRoot 'update-local-client.ps1') -ExpectedVersionCode $code -ExpectedVersionName $name @localUpdateArguments
    }
} catch {
    if ($builtPackage) {
        $retry = 'build\release.cmd nopause -PublishOnly'
        if ($DeployServer) { $retry += ' -DeployServer' }
        if ($TestLocalUpdate) { $retry += ' -TestLocalUpdate' }
        if ($LocalInstallDir) { $retry += ' -LocalInstallDir "' + $LocalInstallDir + '"' }
        Write-Host "Package retained. Retry with the same pinned Agent: $retry" -ForegroundColor Yellow
    }
    throw
} finally { Pop-Location }
