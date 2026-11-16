param(
    [string]$InstallDir = (Join-Path $env:LOCALAPPDATA 'CodexRM'),
    [string]$Workspace,
    [string]$Server,
    [string]$EnrollmentKey,
    [string]$CodexPath,
    [switch]$NoShortcuts,
    [switch]$NoStart
)
$ErrorActionPreference = 'Stop'
$sourceRoot = Split-Path -Parent $PSScriptRoot
$InstallDir = [IO.Path]::GetFullPath($InstallDir)
if ($InstallDir.TrimEnd('\') -eq $sourceRoot.TrimEnd('\')) {
    throw 'Choose an installation directory different from the extracted package directory.'
}

$requiredFiles = @('bin\codexrm-agent.exe', 'scripts\connect.ps1',
    'scripts\codex-path.ps1', 'scripts\install-client.ps1')
foreach ($relativePath in $requiredFiles) {
    if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $relativePath) -PathType Leaf)) {
        throw "Package file missing: $relativePath. Extract the complete installation ZIP first."
    }
}

function Read-ConnectionConfig([string]$Root) {
    $path = Join-Path $Root '.state\connection.json'
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        return Get-Content -LiteralPath $path -Raw -Encoding UTF8 | ConvertFrom-Json
    }
}

$packageConfig = Read-ConnectionConfig $sourceRoot
$installedConfig = Read-ConnectionConfig $InstallDir
if (-not $Server) {
    if ($packageConfig -and $packageConfig.server) { $Server = $packageConfig.server }
    elseif ($installedConfig -and $installedConfig.server) { $Server = $installedConfig.server }
    else { throw 'Server missing. Use -Server or provide a package with .state/connection.json.' }
}
$Server = $Server.TrimEnd('/')
$serverUri = $null
if (-not [Uri]::TryCreate($Server, [UriKind]::Absolute, [ref]$serverUri) -or
        $serverUri.Scheme -notin @('http', 'https')) {
    throw 'Server must be an absolute HTTP or HTTPS URL.'
}
if (-not $EnrollmentKey) {
    if ($env:CODEXRM_ENROLL_KEY) { $EnrollmentKey = $env:CODEXRM_ENROLL_KEY }
    elseif ($packageConfig -and $packageConfig.server.TrimEnd('/') -eq $Server) {
        $EnrollmentKey = $packageConfig.enrollmentKey
    }
    if (-not $EnrollmentKey -and $installedConfig -and $installedConfig.server.TrimEnd('/') -eq $Server) {
        $EnrollmentKey = $installedConfig.enrollmentKey
    }
}
if (-not $EnrollmentKey -and -not $serverUri.IsLoopback) {
    throw 'Enrollment key missing. Use the configured installation package, or set CODEXRM_ENROLL_KEY before installing.'
}
if (-not $Workspace) {
    if ($installedConfig -and $installedConfig.workspace) { $Workspace = $installedConfig.workspace }
    else { $Workspace = [Environment]::GetFolderPath('UserProfile') }
}
if (-not (Test-Path -LiteralPath $Workspace -PathType Container)) {
    throw "Workspace directory does not exist: $Workspace"
}
$Workspace = (Resolve-Path -LiteralPath $Workspace).ProviderPath

. (Join-Path $PSScriptRoot 'codex-path.ps1')
$previousPath = if ($installedConfig) { $installedConfig.codexPath } else { $null }
$resolvedCodex = Resolve-CodexDesktopCli -CodexPath $CodexPath -PreviousPath $previousPath
$codexVersion = & $resolvedCodex --version
if ($LASTEXITCODE) { throw "Codex desktop CLI failed: $resolvedCodex" }
Write-Host "Using $codexVersion"
Write-Host "Codex desktop CLI: $resolvedCodex"

$installedBinary = Join-Path $InstallDir 'bin\codexrm-agent.exe'
foreach ($process in @(Get-Process -Name codexrm-agent -ErrorAction SilentlyContinue)) {
    if ($process.Path -eq $installedBinary) {
        throw 'The installed CodexRM connector is running. Close its terminal window before updating.'
    }
}
foreach ($directory in @('bin', 'scripts', '.state')) {
    [void][IO.Directory]::CreateDirectory((Join-Path $InstallDir $directory))
}
foreach ($relativePath in $requiredFiles) {
    Copy-Item -LiteralPath (Join-Path $sourceRoot $relativePath) `
        -Destination (Join-Path $InstallDir $relativePath) -Force
}
$connection = [ordered]@{
    server = $Server
    enrollmentKey = $EnrollmentKey
    workspace = $Workspace
    codexPath = $resolvedCodex
}
$connection | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $InstallDir '.state\connection.json') -Encoding UTF8

$connectPath = Join-Path $InstallDir 'scripts\connect.ps1'
$powershellPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
if (-not $NoShortcuts) {
    $desktop = [Environment]::GetFolderPath('DesktopDirectory')
    if (-not $desktop) { throw 'Desktop folder could not be found. Retry with -NoShortcuts.' }
    [void][IO.Directory]::CreateDirectory($desktop)
    $shell = New-Object -ComObject WScript.Shell
    foreach ($entry in @(
            @{ Name = 'CodexRM'; Extra = ''; Description = 'Start the CodexRM connector' },
            @{ Name = 'CodexRM Pair'; Extra = ' -Pair'; Description = 'Generate a new CodexRM pairing code' })) {
        $shortcut = $shell.CreateShortcut((Join-Path $desktop ($entry.Name + '.lnk')))
        $shortcut.TargetPath = $powershellPath
        $shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -NoExit -File "' + $connectPath + '"' + $entry.Extra
        $shortcut.WorkingDirectory = $Workspace
        $shortcut.Description = $entry.Description
        $shortcut.Save()
    }
    Write-Host 'Desktop shortcuts created: CodexRM, CodexRM Pair'
}

Write-Host "Installed to: $InstallDir"
Write-Host "Workspace: $Workspace"
Write-Host "Open $Server and choose Connect device."
Write-Host 'Keep the connector terminal open while using remote access. Press Ctrl+C to stop.'
if (-not $NoStart) {
    & $connectPath -Server $Server -Workspace $Workspace
}
