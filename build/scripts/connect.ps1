param(
    [string]$Server,
    [string]$Workspace,
    [string]$CodexPath,
    [string]$Data,
    [switch]$Pair,
    [switch]$Restart,
    [switch]$Mock
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$binary = Join-Path $root 'bin/codexrm-agent.exe'
if (-not (Test-Path -LiteralPath $binary)) { throw 'Run scripts/build.ps1 first.' }
if ($Restart -and $Pair) { throw 'Restart and Pair cannot be used together.' }
. (Join-Path $PSScriptRoot 'client-install.ps1')
if (-not $Pair) {
    if (-not $Restart -and @(Get-CodexRMConnectorProcess $binary).Count) {
        Write-Host 'The CodexRM connector is already running. Open CodexRM 设置和生成配对码 to generate a new pairing code.'
        return
    }
}
# The deployment can write an ignored local config without embedding secrets in
# source code, command-line arguments, or the Web UI.
$configPath = Join-Path $root '.state/connection.json'
$config = $null
if (Test-Path -LiteralPath $configPath) {
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
}
if (-not $Server) {
    if ($config -and $config.server) { $Server = $config.server }
    else { throw 'Server missing. Use -Server or set server in .state/connection.json.' }
}
$Server = $Server.TrimEnd('/')
if ($config -and $config.server -and $config.server.TrimEnd('/') -eq $Server -and $config.enrollmentKey) {
    $env:CODEXRM_ENROLL_KEY = $config.enrollmentKey
}
if (-not $Workspace) {
    if ($config -and $config.workspace) { $Workspace = $config.workspace }
    else { $Workspace = (Get-Location).Path }
}
$agentArgs = @('--server', $Server, '--cwd', $Workspace)
if (-not $Pair -and -not $Mock -and $config -and $config.autoUpdate) {
    $agentArgs += @('--update-dir', $root)
    if ($config.updateManifestUrl) { $agentArgs += @('--update-manifest', $config.updateManifestUrl) }
}
if ($Data) { $agentArgs += @('--data', $Data) }
if ($Pair) { $agentArgs += '--pair' }
if ($Mock) { $agentArgs += '--mock' }
if (-not $Pair -and -not $Mock) {
    . (Join-Path $PSScriptRoot 'codex-path.ps1')
    $previousPath = if ($config) { $config.codexPath } else { $null }
    $resolvedCodex = Resolve-CodexDesktopCli -CodexPath $CodexPath -PreviousPath $previousPath
    Write-Host "Codex desktop CLI: $resolvedCodex"
    $agentArgs += @('--codex', $resolvedCodex)
}
if ($Restart) {
    foreach ($process in @(Get-CodexRMConnectorProcess $binary)) {
        if (-not $process.HasExited) { Stop-Process -InputObject $process -Force }
        if (-not $process.WaitForExit(10000)) { throw 'The running CodexRM connector did not stop in time.' }
    }
    Write-Host 'Starting the CodexRM connector...'
}
if ($Pair) {
    $pairOutput = Invoke-CodexRMAgentCommand -Binary $binary -Arguments $agentArgs
    if ($pairOutput) { Write-Host $pairOutput.TrimEnd() }
    return
}
$process = Start-Process -FilePath $binary -ArgumentList (ConvertTo-CodexRMCommandLine $agentArgs) `
    -WorkingDirectory $Workspace -WindowStyle Hidden -PassThru
Write-Host "CodexRM connector started in the system tray (PID $($process.Id)). Use the tray icon to exit."
