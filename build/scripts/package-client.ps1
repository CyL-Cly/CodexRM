param(
    [string]$OutputPath,
    [string]$Server,
    [string]$DownloadUrl,
    [string]$ReleaseNotes = '',
    [switch]$IncludeEnrollmentKey
)
$ErrorActionPreference = 'Stop'
if ($IncludeEnrollmentKey) { throw 'Private connection packages are retired. Configure the server and key in the agent settings page.' }
$root = Split-Path -Parent $PSScriptRoot
. (Join-Path $PSScriptRoot 'client-install.ps1')
if (-not $OutputPath) {
    $fileName = if ($IncludeEnrollmentKey) { 'CodexRM-windows-amd64-private.zip' } else { 'CodexRM-windows-amd64.zip' }
    $OutputPath = Join-Path $root ('dist\' + $fileName)
}
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
$stage = Join-Path $root ('.work\client-package-' + [Guid]::NewGuid().ToString('N') + '\CodexRM')
& (Join-Path $PSScriptRoot 'package-setup.ps1')
$files = @('bin\codexrm-agent.exe', 'dist\CodexRM-Setup.exe')
foreach ($relativePath in $files) {
    $source = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Package source missing: $source" }
    $destination = if ($relativePath -eq 'dist\CodexRM-Setup.exe') { Join-Path $stage 'CodexRM-Setup.exe' } else { Join-Path $stage $relativePath }
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $destination))
    Copy-Item -LiteralPath $source -Destination $destination
    if ([IO.Path]::GetExtension($destination) -ieq '.ps1') {
        # Windows PowerShell 5.1 requires a BOM to recognize UTF-8 script text.
        $scriptText = [IO.File]::ReadAllText($source, [Text.Encoding]::UTF8)
        [IO.File]::WriteAllText($destination, $scriptText, (New-Object Text.UTF8Encoding $true))
    }
}
$installGuide = Join-Path (Split-Path -Parent $root) 'docs\windows-install.md'
if (-not (Test-Path -LiteralPath $installGuide -PathType Leaf)) { $installGuide = Join-Path $root 'docs\windows-install.md' }
Copy-Item -LiteralPath $installGuide -Destination (Join-Path $stage 'README.md')
# Old automatic updaters require these ZIP entries. Native installers/updaters
# only extract the exe; a fresh installation never installs these placeholders.
$compatScripts = Join-Path $stage 'scripts'
[void][IO.Directory]::CreateDirectory($compatScripts)
foreach ($name in @('connect.ps1', 'codex-path.ps1', 'client-install.ps1', 'install-client.ps1')) {
    [IO.File]::WriteAllText((Join-Path $compatScripts $name), '# Legacy update compatibility. Use the native CodexRM-Setup.exe or agent exe.', (New-Object Text.UTF8Encoding $false))
}
[IO.File]::WriteAllText((Join-Path $stage 'install.cmd'), '@"%~dp0bin\codexrm-agent.exe" --restart' + "`r`n", (New-Object Text.UTF8Encoding $false))
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $OutputPath))
if (-not $IncludeEnrollmentKey) {
    $version = Get-CodexRMBinaryVersion (Join-Path $stage 'bin\codexrm-agent.exe')
}
Compress-Archive -LiteralPath $stage -DestinationPath $OutputPath -Force
Write-Host "Package created: $OutputPath"
if ($IncludeEnrollmentKey) { Write-Host 'This private package includes the server enrollment key for your own computers.' }
else {
    $packageName = [Uri]::EscapeDataString([IO.Path]::GetFileName($OutputPath))
    if (-not $DownloadUrl) {
        $DownloadUrl = 'updates/' + $packageName
        if ($Server) { $DownloadUrl = $Server.TrimEnd('/') + '/' + $DownloadUrl }
    }
    $manifest = [ordered]@{
        versionCode = [long]$version.versionCode
        versionName = [string]$version.versionName
        downloadUrl = $DownloadUrl
        releaseNotes = $ReleaseNotes
        fileSize = (Get-Item -LiteralPath $OutputPath).Length
        sha256 = (Get-FileHash -LiteralPath $OutputPath -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $manifestPath = Join-Path (Split-Path -Parent $OutputPath) 'update.json'
    [IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json), (New-Object Text.UTF8Encoding $false))
    Write-Host "Update manifest created: $manifestPath"
}
