param(
    [switch]$Linux,
    [ValidateSet('Agent', 'Server', 'All')][string]$Target = 'All',
    [switch]$Interactive
)
$ErrorActionPreference = 'Stop'
$buildRoot = Split-Path -Parent $PSScriptRoot
$root = Split-Path -Parent $buildRoot
$ldflags = '-s -w'

if ($Interactive) {
    Write-Host ''
    Write-Host 'CodexRM build'
    Write-Host '1. Windows (amd64)'
    Write-Host '2. Linux (amd64)'
    Write-Host '0. Exit'
    do {
        $platformChoice = (Read-Host 'Select platform [1-2, 0=exit]').Trim()
        if ($platformChoice -eq '0') { return }
    } while ($platformChoice -notin @('1', '2'))
    $Linux = $platformChoice -eq '2'

    Write-Host ''
    Write-Host '1. Agent'
    Write-Host '2. Server'
    Write-Host '3. Agent + Server'
    Write-Host '0. Exit'
    do {
        $targetChoice = (Read-Host 'Select target [1-3, 0=exit]').Trim()
        if ($targetChoice -eq '0') { return }
    } while ($targetChoice -notin @('1', '2', '3'))
    $Target = switch ($targetChoice) { '1' { 'Agent' }; '2' { 'Server' }; '3' { 'All' } }
}

if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw 'Go was not found. Install Go 1.24+ and add it to PATH before building.'
}
$previousOS = $env:GOOS
$previousArch = $env:GOARCH
$previousCGO = $env:CGO_ENABLED
Push-Location $root
try {
    $outputDir = Join-Path $buildRoot 'bin'
    [void][IO.Directory]::CreateDirectory($outputDir)
    $env:GOOS = if ($Linux) { 'linux' } else { 'windows' }
    $env:GOARCH = 'amd64'
    $env:CGO_ENABLED = '0'
    $suffix = if ($Linux) { '-linux-amd64' } else { '.exe' }
    $targets = if ($Target -eq 'All') { @('server', 'agent') } else { @($Target.ToLowerInvariant()) }
    foreach ($component in $targets) {
        $outputPath = Join-Path $outputDir ("codexrm-$component" + $suffix)
        Write-Host ''
        Write-Host "Building $component for $env:GOOS/$env:GOARCH..."
        $componentLdflags = $ldflags
        if (-not $Linux -and $component -eq 'agent') { $componentLdflags += ' -H=windowsgui' }
        go build -trimpath -ldflags $componentLdflags -o $outputPath "./cmd/codexrm-$component"
        if ($LASTEXITCODE) { throw "$component build failed for $env:GOOS/$env:GOARCH (exit code $LASTEXITCODE)." }
        Write-Host "Built: $outputPath"
    }
} finally {
    $env:GOOS = $previousOS
    $env:GOARCH = $previousArch
    $env:CGO_ENABLED = $previousCGO
    Pop-Location
}
