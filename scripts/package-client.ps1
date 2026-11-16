param(
    [string]$OutputPath,
    [string]$Server,
    [switch]$IncludeEnrollmentKey
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $OutputPath) {
    $fileName = if ($IncludeEnrollmentKey) { 'CodexRM-windows-amd64-private.zip' } else { 'CodexRM-windows-amd64.zip' }
    $OutputPath = Join-Path $root ('dist\' + $fileName)
}
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
$stage = Join-Path $root ('.work\client-package-' + [Guid]::NewGuid().ToString('N') + '\CodexRM')
$files = @('bin\codexrm-agent.exe', 'scripts\connect.ps1', 'scripts\codex-path.ps1',
    'scripts\install-client.ps1', 'install.cmd')
foreach ($relativePath in $files) {
    $source = Join-Path $root $relativePath
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "Package source missing: $source" }
    $destination = Join-Path $stage $relativePath
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $destination))
    Copy-Item -LiteralPath $source -Destination $destination
}
Copy-Item -LiteralPath (Join-Path $root 'docs\windows-install.md') -Destination (Join-Path $stage 'README.md')
$configPath = Join-Path $root '.state\connection.json'
$config = $null
if (Test-Path -LiteralPath $configPath -PathType Leaf) {
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
}
if (-not $Server -and $config) { $Server = $config.server }
if (-not $Server) { throw 'Server missing. Use -Server or set server in .state/connection.json.' }
$connection = [ordered]@{ server = $Server.TrimEnd('/') }
if ($IncludeEnrollmentKey) {
    if (-not $config -or -not $config.server -or -not $config.enrollmentKey) {
        throw 'Local connection config is missing the server or enrollment key.'
    }
    if ($config.server.TrimEnd('/') -ne $connection.server) {
        throw 'The local enrollment key belongs to a different server.'
    }
    $connection['enrollmentKey'] = $config.enrollmentKey
}
[void][IO.Directory]::CreateDirectory((Join-Path $stage '.state'))
$connection | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stage '.state\connection.json') -Encoding UTF8
[void][IO.Directory]::CreateDirectory((Split-Path -Parent $OutputPath))
Compress-Archive -LiteralPath $stage -DestinationPath $OutputPath -Force
Write-Host "Package created: $OutputPath"
if ($IncludeEnrollmentKey) { Write-Host 'This private package includes the server enrollment key for your own computers.' }
