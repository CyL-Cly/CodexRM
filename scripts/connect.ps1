param(
    [string]$Server,
    [string]$Workspace,
    [string]$CodexPath,
    [string]$Data,
    [switch]$Pair,
    [switch]$Mock
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$binary = Join-Path $root 'bin/codexrm-agent.exe'
if (-not (Test-Path -LiteralPath $binary)) { throw 'Run scripts/build.ps1 first.' }
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
& $binary @agentArgs
if ($LASTEXITCODE) { throw "Agent exited: $LASTEXITCODE" }
