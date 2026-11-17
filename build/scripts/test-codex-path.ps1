# Regression checks use fixture installations without depending on this machine's Codex app.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'codex-path.ps1')
$testParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $testParent ('CodexRM-cli-tests-' + [Guid]::NewGuid().ToString('N'))
$previousEnvironment = @{}
$checks = 0
$testProcesses = @()
$testPackages = @()
$testDrives = @()
$testStoreFailure = $true

function Get-Process {
    param([string[]]$Name)
    return @($testProcesses | Where-Object { $_.ProcessName -in $Name })
}
function Get-AppxPackage {
    param([string]$Name)
    if ($testStoreFailure) { throw 'Store registration is unavailable.' }
    return $testPackages
}
function Get-PSDrive {
    param([string]$PSProvider)
    return $testDrives
}
function New-CliFixture([string]$RelativePath) {
    $path = Join-Path $testRoot $RelativePath
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $path))
    Set-Content -LiteralPath $path -Value 'CLI fixture; never executed.' -Encoding ASCII
    return $path
}
function Assert-CliPath([string]$Actual, [string]$Expected, [string]$Message) {
    if ($Actual -cne $Expected) { throw "FAIL: $Message; resolved '$Actual', expected '$Expected'." }
    $script:checks++
    Write-Host "PASS: $Message"
}
function Assert-CliRejected([scriptblock]$Operation, [string]$Message) {
    $rejected = $false
    try { & $Operation | Out-Null } catch { $rejected = $true }
    if (-not $rejected) { throw "FAIL: $Message" }
    $script:checks++
    Write-Host "PASS: $Message"
}

try {
    foreach ($name in @('LOCALAPPDATA', 'ProgramW6432', 'ProgramFiles', 'ProgramFiles(x86)')) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name)
        [Environment]::SetEnvironmentVariable($name, (Join-Path $testRoot ('empty-' + $name)))
    }
    $savedCli = New-CliFixture 'saved\resources\codex.exe'
    Assert-CliPath (Resolve-CodexDesktopCli -PreviousPath $savedCli) $savedCli `
        'Saved CLI remains available when Store registration fails'

    $desktopCli = New-CliFixture 'desktop app [test]\app\resources\codex.exe'
    $desktopAppDir = Split-Path -Parent (Split-Path -Parent $desktopCli)
    $testProcesses = @([pscustomobject]@{ProcessName='ChatGPT'; Path=(Join-Path $desktopAppDir 'ChatGPT.exe')})
    Assert-CliPath (Resolve-CodexDesktopCli -PreviousPath $savedCli) $desktopCli `
        'ChatGPT.exe locates the desktop CLI before any CLI child has started'

    $testProcesses = @(
        [pscustomobject]@{ProcessName='Codex'; Path=$savedCli},
        [pscustomobject]@{ProcessName='Codex'; Path=(Join-Path $desktopAppDir 'Codex.exe')}
    )
    Assert-CliPath (Resolve-CodexDesktopCli) $desktopCli `
        'Active desktop installation takes precedence over a stale running CLI'
    Assert-CliPath (Resolve-CodexDesktopCli -CodexPath $savedCli) $savedCli `
        'Explicit CLI path overrides desktop discovery'

    $testProcesses = @([pscustomobject]@{ProcessName='Codex'; Path=$desktopCli})
    Assert-CliPath (Resolve-CodexDesktopCli) $desktopCli 'Running bundled CLI remains discoverable'

    $testProcesses = @()
    $testStoreFailure = $false
    $testPackages = @(
        [pscustomobject]@{InstallLocation=(Join-Path $testRoot 'saved'); Version=[version]'1.0'},
        [pscustomobject]@{InstallLocation=(Split-Path -Parent $desktopAppDir); Version=[version]'2.0'}
    )
    Assert-CliPath (Resolve-CodexDesktopCli -PreviousPath $savedCli) $desktopCli `
        'Newest registered Store installation works with the desktop closed'

    $testStoreFailure = $true
    $volumeRoot = Join-Path $testRoot 'other volume'
    $volumeCli = New-CliFixture 'other volume\WindowsApps\OpenAI.Codex_2.0_x64__test\resources\codex.exe'
    $testDrives = @([pscustomobject]@{Root=$volumeRoot})
    Assert-CliPath (Resolve-CodexDesktopCli -PreviousPath ($savedCli + '.missing')) $volumeCli `
        'Other Store volumes and resources-only layouts work without registration'

    $testDrives = @()
    $commonCli = New-CliFixture 'local\Programs\Codex\app\resources\codex.exe'
    $env:LOCALAPPDATA = Join-Path $testRoot 'local'
    Assert-CliPath (Resolve-CodexDesktopCli) $commonCli 'Per-user desktop installations are detected'
    Assert-CliRejected { Resolve-CodexDesktopCli -CodexPath ($savedCli + '.missing') } `
        'Invalid explicit CLI is rejected instead of silently using another installation'

    $env:LOCALAPPDATA = Join-Path $testRoot 'empty-local'
    Assert-CliRejected { Resolve-CodexDesktopCli } 'Missing desktop CLI reports an error'
    Write-Host "All $checks Codex desktop CLI discovery checks passed."
} finally {
    foreach ($name in $previousEnvironment.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name])
    }
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedTestRoot.StartsWith($testParent.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($resolvedTestRoot).StartsWith('CodexRM-cli-tests-') -and
            (Test-Path -LiteralPath $resolvedTestRoot -PathType Container)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
