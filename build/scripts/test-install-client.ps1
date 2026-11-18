# Windows integration checks. All configuration, shortcuts and binaries stay in a temporary directory.
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'test-codex-path.ps1')
$repoRoot = Split-Path -Parent $PSScriptRoot
$testParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot = Join-Path $testParent ('CodexRM-install-tests-' + [Guid]::NewGuid().ToString('N'))
$previousLocalAppData = $env:LOCALAPPDATA
$previousEnrollmentKey = $env:CODEXRM_ENROLL_KEY
$previousTestRoot = $env:CODEXRM_INSTALL_TEST_ROOT
$checks = 0

function Assert-InstallCheck([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "FAIL: $Message" }
    $script:checks++
    Write-Host "PASS: $Message"
}

function Read-Host {
    param([string]$Prompt, [switch]$AsSecureString)
    if (-not $testAnswers.Count) { throw "Unexpected interactive prompt: $Prompt" }
    $answer = $testAnswers.Dequeue()
    if ($AsSecureString) { return ConvertTo-SecureString $answer -AsPlainText -Force }
    return $answer
}

function Invoke-TestInstaller {
    param([hashtable]$Arguments = @{}, [string[]]$Responses = @(), [string]$ScriptPath = $installer)
    $testAnswers = New-Object System.Collections.Queue
    foreach ($response in $Responses) { $testAnswers.Enqueue($response) }
    $Arguments['NoStart'] = $true
    $output = & $ScriptPath @Arguments 6>&1 | Out-String
    if ($testAnswers.Count) { throw 'Installer did not consume the expected responses.' }
    return $output
}

function Assert-InstallerRejects([scriptblock]$Operation, [string]$Message) {
    $rejected = $false
    try { & $Operation | Out-Null } catch { $rejected = $true }
    Assert-InstallCheck $rejected $Message
}

try {
    [void][IO.Directory]::CreateDirectory($testRoot)
    $env:LOCALAPPDATA = Join-Path $testRoot 'local app data'
    $env:CODEXRM_ENROLL_KEY = $null
    $env:CODEXRM_INSTALL_TEST_ROOT = $testRoot
    $packageRoot = Join-Path $testRoot 'package'
    foreach ($directory in @('bin', 'scripts', 'docs')) {
        [void][IO.Directory]::CreateDirectory((Join-Path $packageRoot $directory))
    }
    foreach ($file in @('install.cmd', 'scripts\install-client.ps1', 'scripts\client-install.ps1',
            'scripts\connect.ps1', 'scripts\package-client.ps1', 'docs\windows-install.md')) {
        $sourceFile = Join-Path $repoRoot $file
        if ($file -eq 'docs\windows-install.md') { $sourceFile = Join-Path (Split-Path -Parent $repoRoot) $file }
        Copy-Item -LiteralPath $sourceFile -Destination (Join-Path $packageRoot $file)
    }
    $fixtureBinary = Join-Path $packageRoot 'bin\codexrm-agent.exe'
    Set-Content -LiteralPath $fixtureBinary -Value 'Test fixture; never executed.'
    $fixtureCli = Join-Path $testRoot 'codex fixture.cmd'
    Set-Content -LiteralPath $fixtureCli -Value "@echo off`r`necho codex-cli test`r`nexit /b 0" -Encoding ASCII
    & {
        . (Join-Path $repoRoot 'scripts\codex-path.ps1')
        function Get-Process { param([string[]]$Name); return @() }
        function Get-AppxPackage { param([string]$Name); throw 'Store enumeration is unavailable in this WinRM session.' }
        $resolved = Resolve-CodexDesktopCli -PreviousPath $fixtureCli
        Assert-InstallCheck ($resolved -ceq $fixtureCli) 'CLI discovery falls back to the saved path when Store enumeration fails in WinRM'
    }
    # Replace only OS directory discovery, so real .lnk files can be inspected safely.
    Add-Content -LiteralPath (Join-Path $packageRoot 'scripts\client-install.ps1') -Encoding UTF8 -Value @'

function Get-CodexRMShortcutDirectory([string]$Folder) {
    return Join-Path $env:CODEXRM_INSTALL_TEST_ROOT $Folder
}
function Get-CodexRMBinaryVersion([string]$Binary) {
    return [pscustomobject]@{ versionCode = 1; versionName = 'test' }
}
function Invoke-CodexRMUpdateCommand([string]$Binary, [string[]]$Arguments) {
    if ($env:CODEXRM_TEST_UPDATE_FAIL -eq '1') { throw 'Fixture update network error' }
    if ($Arguments -contains '--check-update') {
        return [pscustomobject]@{updateAvailable=$true; manifest=[pscustomobject]@{versionCode=2;versionName='test-new';releaseNotes='test notes'}}
    }
    Set-Content -LiteralPath (Join-Path $env:CODEXRM_INSTALL_TEST_ROOT 'update-invoked.txt') -Value ($Arguments -join ' ')
    return [pscustomobject]@{versionCode=2;versionName='test-new'}
}
'@
    Set-Content -LiteralPath (Join-Path $packageRoot 'scripts\codex-path.ps1') -Encoding UTF8 -Value @'
function Resolve-CodexDesktopCli {
    param([string]$CodexPath, [string]$PreviousPath)
    $path = if ($CodexPath) { $CodexPath } else { Join-Path $env:CODEXRM_INSTALL_TEST_ROOT 'codex fixture.cmd' }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Fixture CLI is missing.' }
    return $path
}
'@
    $installer = Join-Path $packageRoot 'scripts\install-client.ps1'
    $installRoot = Join-Path $testRoot 'installed app [test]'
    $recordPath = Join-Path $env:LOCALAPPDATA 'CodexRM\installation.json'
    $configPath = Join-Path $installRoot '.state\connection.json'
    $startupPath = Join-Path $testRoot 'Startup\CodexRM.lnk'
    $desktop = Join-Path $testRoot 'DesktopDirectory'
    [void][IO.Directory]::CreateDirectory($desktop)
    foreach ($name in @('CodexRM', 'CodexRM Pair', 'CodexRM Settings', 'Other app')) {
        Set-Content -LiteralPath (Join-Path $desktop ($name + '.lnk')) -Value 'Existing desktop shortcut'
    }

    $firstOutput = Invoke-TestInstaller -Arguments @{ InstallDir = $installRoot; Workspace = $testRoot } `
        -Responses @('ftp://invalid.example', 'https://rm-one.example/rm/', 'test-key-one')
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $record = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $installedAt = $record.installedAt
    Assert-InstallCheck ($config.server -ceq 'https://rm-one.example/rm' -and $config.enrollmentKey -ceq 'test-key-one') 'First install validates interactive URL and saves the entered key'
    Assert-InstallCheck ([bool]$config.autoUpdate) 'Managed installations enable automatic update checks'
    Assert-InstallCheck ($record.installDir -eq $installRoot -and $record.autoStart) 'Installation record saves the custom location and startup state'
    Assert-InstallCheck ($firstOutput.Contains($fixtureCli) -and -not $firstOutput.Contains('test-key-one')) 'Detected CLI path is displayed without disclosing the key'
    Assert-InstallCheck (Test-Path -LiteralPath (Join-Path $installRoot 'install.cmd') -PathType Leaf) 'Installed copy includes the management entry point'
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($startupPath)
    Assert-InstallCheck ($shortcut.Arguments.Contains('"' + (Join-Path $installRoot 'scripts\connect.ps1') + '"') -and
        $shortcut.Arguments.Contains('-WindowStyle Hidden') -and -not $shortcut.Arguments.Contains('test-key')) 'Startup shortcut hides the launcher, quotes the installed script and contains no enrollment key'
    $shortcutDeadline = [DateTime]::UtcNow.AddSeconds(5)
    do {
        $desktopShortcuts = @(Get-ChildItem -LiteralPath $desktop -Filter 'CodexRM*.lnk')
        $shortcutsReady = $desktopShortcuts.Count -eq 2 -and
            (Test-Path -LiteralPath (Join-Path $desktop 'CodexRM 重启服务.lnk')) -and
            (Test-Path -LiteralPath (Join-Path $desktop 'CodexRM 设置和生成配对码.lnk'))
        if (-not $shortcutsReady) { Start-Sleep -Milliseconds 50 }
    } while (-not $shortcutsReady -and [DateTime]::UtcNow -lt $shortcutDeadline)
    Assert-InstallCheck ($shortcutsReady -and
        (Test-Path -LiteralPath (Join-Path $desktop 'Other app.lnk'))) 'Installation leaves exactly two CodexRM desktop shortcuts and preserves unrelated shortcuts'
    $restartShortcut = $shell.CreateShortcut((Join-Path $desktop 'CodexRM 重启服务.lnk'))
    Assert-InstallCheck ($restartShortcut.Arguments.Contains('"' + (Join-Path $installRoot 'scripts\connect.ps1') + '" -Restart') -and
        $restartShortcut.WorkingDirectory -eq $testRoot) 'Desktop restart shortcut restarts the connector from the custom installation'
    $settingsShortcut = $shell.CreateShortcut((Join-Path $desktop 'CodexRM 设置和生成配对码.lnk'))
    Assert-InstallCheck ($settingsShortcut.Arguments.Contains('-InstallDir "' + $installRoot + '"')) 'Desktop settings shortcut targets the custom installation'

    Invoke-TestInstaller -Responses @('1', 'https://rm-two.example', 'test-key-two') | Out-Null
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    $record = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-InstallCheck ($config.server -ceq 'https://rm-two.example' -and $config.enrollmentKey -ceq 'test-key-two') 'Rerunning the package finds the installation and changes its RM URL and key'
    Assert-InstallCheck ($record.installedAt -eq $installedAt -and $record.autoStart) 'Changing URL preserves the installation time and startup state'

    Remove-Item -LiteralPath $fixtureCli,$fixtureBinary -Force
    Invoke-TestInstaller -Responses @('3') | Out-Null
    $record = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-InstallCheck (-not (Test-Path -LiteralPath $startupPath) -and -not $record.autoStart) 'Menu removes startup even without Codex CLI or package binary'
    Invoke-TestInstaller -Arguments @{ Action = 'DisableStartup'; NonInteractive = $true } | Out-Null
    Assert-InstallCheck (Test-Path -LiteralPath $configPath) 'Repeated startup removal keeps the connection configuration'
    Invoke-TestInstaller -Arguments @{ Action = 'EnableStartup'; NonInteractive = $true } | Out-Null
    Assert-InstallCheck (Test-Path -LiteralPath $startupPath) 'Startup can be restored without requiring Codex CLI or a new key'

    Set-Content -LiteralPath $fixtureCli -Value "@echo off`r`necho codex-cli test`r`nexit /b 0" -Encoding ASCII
    Set-Content -LiteralPath $fixtureBinary -Value 'Test fixture; never executed.'
    Invoke-TestInstaller -Responses @('3') | Out-Null
    Invoke-TestInstaller -Arguments @{ Action = 'Install'; NonInteractive = $true; NoAutostart = $true; NoShortcuts = $true } | Out-Null
    $record = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-InstallCheck (-not (Test-Path -LiteralPath $startupPath) -and -not $record.autoStart) 'NoAutostart installs without creating a startup shortcut'
    Invoke-TestInstaller -Arguments @{ Action = 'EnableStartup'; NonInteractive = $true } | Out-Null
    [void][IO.Directory]::CreateDirectory((Join-Path $packageRoot '.state'))
    @{ server = 'https://rm-one.example/rm'; enrollmentKey = 'stale-package-key' } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $packageRoot '.state\connection.json') -Encoding UTF8
    Invoke-TestInstaller -Responses @('1', '') | Out-Null
    $config = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-InstallCheck ($config.server -ceq 'https://rm-two.example' -and $config.enrollmentKey -ceq 'test-key-two') 'Saved connection takes precedence over stale package defaults'

    $beforeConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
    Assert-InstallerRejects { Invoke-TestInstaller -Arguments @{ Action = 'Configure'; Server = 'file:///C:/bad'; NonInteractive = $true } } 'Noninteractive mode rejects an invalid URL'
    Assert-InstallerRejects { Invoke-TestInstaller -Arguments @{ Action = 'Configure'; Server = 'https://rm-three.example'; NonInteractive = $true } } 'A different remote server cannot reuse the previous server key'
    Assert-InstallCheck ((Get-Content -LiteralPath $configPath -Raw -Encoding UTF8) -ceq $beforeConfig) 'Rejected configuration changes leave the saved connection intact'

    $installedInstaller = Join-Path $installRoot 'scripts\install-client.ps1'
    Set-Content -LiteralPath (Join-Path $desktop 'CodexRM Pair.lnk') -Value 'Legacy pairing shortcut'
    Invoke-TestInstaller -ScriptPath $installedInstaller -Arguments @{ Action = 'Install'; NonInteractive = $true } | Out-Null
    Assert-InstallCheck (Test-Path -LiteralPath $startupPath) 'Reinstalling from the installed entry point supports copying onto itself'
    Assert-InstallCheck (@(Get-ChildItem -LiteralPath $desktop -Filter 'CodexRM*.lnk').Count -eq 2) 'Reinstalling removes legacy desktop shortcuts without adding duplicates'
    $beforeRecord = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8
    Invoke-TestInstaller -ScriptPath $installedInstaller -Responses @('0') | Out-Null
    Assert-InstallCheck ((Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8) -ceq $beforeRecord) 'Exiting the installed management menu changes no installation state'

    $beforeUpdateConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
    $beforeUpdateShortcut = (Get-FileHash -LiteralPath $startupPath).Hash
    $checkOutput = Invoke-TestInstaller -Arguments @{Action='CheckUpdate'; NonInteractive=$true}
    Assert-InstallCheck ($checkOutput.Contains('test-new') -and -not (Test-Path -LiteralPath (Join-Path $testRoot 'update-invoked.txt'))) 'Noninteractive checks report new versions without installing'
    Invoke-TestInstaller -Responses @('5', 'n') | Out-Null
    Assert-InstallCheck (-not (Test-Path -LiteralPath (Join-Path $testRoot 'update-invoked.txt'))) 'Online update menu permits declining the installation'
    Invoke-TestInstaller -Arguments @{Action='Update'; NonInteractive=$true; UpdateManifest='https://cloud.example/feed.json'} | Out-Null
    $updateArgs = Get-Content -LiteralPath (Join-Path $testRoot 'update-invoked.txt') -Raw
    Assert-InstallCheck ($updateArgs.Contains('--install-update') -and $updateArgs.Contains('https://cloud.example/feed.json')) 'Manual updates forward the feed override and bypass the daily schedule'
    Assert-InstallCheck ((Get-Content -LiteralPath $configPath -Raw -Encoding UTF8) -ceq $beforeUpdateConfig -and
        (Get-FileHash -LiteralPath $startupPath).Hash -eq $beforeUpdateShortcut) 'Manual updates preserve the exact connection configuration and startup shortcut'
    $env:CODEXRM_TEST_UPDATE_FAIL = '1'
    try { Assert-InstallerRejects { Invoke-TestInstaller -Arguments @{Action='CheckUpdate';NonInteractive=$true} } 'Network failures are reported by manual update checks' }
    finally { $env:CODEXRM_TEST_UPDATE_FAIL = $null }

    # Exercise the native Setup/CMD boundary in a fresh process. The fixture only records arguments.
    $setupDirectory = Join-Path $packageRoot 'dist'
    [void][IO.Directory]::CreateDirectory($setupDirectory)
    $setupFixture = Join-Path $setupDirectory 'CodexRM-Setup.exe'
    Add-Type -OutputAssembly $setupFixture -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.IO;
public static class SetupFixture {
    public static int Main(string[] args) {
        File.WriteAllLines(Path.Combine(Environment.GetEnvironmentVariable("CODEXRM_INSTALL_TEST_ROOT"), "native-setup-args.txt"), args);
        return Array.IndexOf(args, "--fixture-failure") >= 0 ? 23 : 0;
    }
}
'@
    $nativeArguments = @('--install-dir', $installRoot, '--workspace', $testRoot, '--non-interactive', '--no-shortcuts', '--no-autostart', '--no-start')
    $nativeArgumentText = ($nativeArguments | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    $beforeCmdConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
    $beforeCmdStartup = (Get-FileHash -LiteralPath $startupPath).Hash
    $cmdOutput = Join-Path $testRoot 'cmd-output.txt'
    $cmdError = Join-Path $testRoot 'cmd-error.txt'
    $cmdProcess = Start-Process -FilePath $env:ComSpec -WindowStyle Hidden -PassThru `
        -ArgumentList ('/d /s /c ""' + (Join-Path $packageRoot 'install.cmd') + '" ' + $nativeArgumentText + '"') `
        -RedirectStandardOutput $cmdOutput -RedirectStandardError $cmdError
    $null = $cmdProcess.Handle
    if (-not $cmdProcess.WaitForExit(10000)) {
        $cmdProcess.Kill()
        throw 'Noninteractive install.cmd did not exit without pausing.'
    }
    if ($cmdProcess.ExitCode -ne 0) {
        Get-Content -LiteralPath $cmdOutput,$cmdError | Write-Host
        throw "CMD installer failed with exit code: $($cmdProcess.ExitCode)"
    }
    $forwardedArguments = @(Get-Content -LiteralPath (Join-Path $testRoot 'native-setup-args.txt'))
    Assert-InstallCheck ([Linq.Enumerable]::SequenceEqual([string[]]$nativeArguments, [string[]]$forwardedArguments) -and
        (Get-Content -LiteralPath $configPath -Raw -Encoding UTF8) -ceq $beforeCmdConfig -and
        (Get-FileHash -LiteralPath $startupPath).Hash -eq $beforeCmdStartup) 'CMD forwards native Setup arguments with spaces and leaves configuration/startup untouched'

    $failureProcess = Start-Process -FilePath $env:ComSpec -WindowStyle Hidden -PassThru -Wait `
        -ArgumentList ('/d /s /c ""' + (Join-Path $packageRoot 'install.cmd') + '" --non-interactive --fixture-failure"') `
        -RedirectStandardOutput $cmdOutput -RedirectStandardError $cmdError
    Assert-InstallCheck ($failureProcess.ExitCode -eq 23) 'CMD preserves a native Setup failure exit code'

    # Real isolated processes verify pairing and restart without contacting an RM or running Codex.
    $processFixture = Join-Path $testRoot 'process-fixture.exe'
    Add-Type -OutputAssembly $processFixture -OutputType ConsoleApplication -TypeDefinition @'
using System;
using System.IO;
using System.Threading;
public static class ConnectorFixture {
    public static void Main(string[] args) {
        if (Array.IndexOf(args, "--wait") >= 0) { Thread.Sleep(60000); return; }
        File.WriteAllLines(Path.Combine(Environment.GetEnvironmentVariable("CODEXRM_INSTALL_TEST_ROOT"), "connector-args.txt"), args);
        if (Array.IndexOf(args, "--list-devices") >= 0) {
            Console.WriteLine("[{\"clientId\":\"cli-test\",\"clientName\":\"Test phone\",\"deviceType\":\"browser\",\"createdAt\":\"2026-01-01T00:00:00Z\"}]");
        } else {
            bool pair = Array.IndexOf(args, "--pair") >= 0;
            bool remove = Array.IndexOf(args, "--remove-device") >= 0;
            Console.WriteLine(pair ? "Pairing code: TEST-CODE" : remove ? "Device authorization removed." : "Fixture connector started.");
            if (!pair && !remove) { Thread.Sleep(60000); }
        }
    }
}
'@
    $installedAgent = Join-Path $installRoot 'bin\codexrm-agent.exe'
    Copy-Item -LiteralPath $processFixture -Destination $installedAgent -Force
    $otherBinary = Join-Path $testRoot 'other installation\codexrm-agent.exe'
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $otherBinary))
    Copy-Item -LiteralPath $processFixture -Destination $otherBinary
    $runningAgent = $null
    $otherAgent = $null
    try {
        $runningAgent = Start-Process -FilePath $installedAgent -ArgumentList '--wait' -WindowStyle Hidden -PassThru
        $otherAgent = Start-Process -FilePath $otherBinary -ArgumentList '--wait' -WindowStyle Hidden -PassThru
        # Opening the handles also retains the old process identity after a restart.
        $null = $runningAgent.Handle
        $null = $otherAgent.Handle
        $beforePairConfig = Get-Content -LiteralPath $configPath -Raw -Encoding UTF8
        $beforePairRecord = Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8
        Remove-Item -LiteralPath $fixtureCli -Force
        $pairOutput = Invoke-TestInstaller -ScriptPath $installedInstaller -Responses @('6')
        Assert-InstallCheck ($pairOutput.Contains('Pairing code: TEST-CODE') -and -not $runningAgent.HasExited -and
            (Get-Content -LiteralPath $configPath -Raw -Encoding UTF8) -ceq $beforePairConfig -and
            (Get-Content -LiteralPath $recordPath -Raw -Encoding UTF8) -ceq $beforePairRecord) 'Settings menu generates a pairing code without Codex CLI, restarting the connector or rewriting configuration'
        $pairArgs = @(Get-Content -LiteralPath (Join-Path $testRoot 'connector-args.txt'))
        Assert-InstallCheck ($pairArgs -contains '--pair' -and $pairArgs -contains 'https://rm-two.example' -and
            $pairArgs -notcontains '--update-dir') 'Settings pairing uses the saved RM URL and skips automatic updates'
        $directPairOutput = Invoke-TestInstaller -ScriptPath $installedInstaller -Arguments @{Action='Pair'; NonInteractive=$true}
        Assert-InstallCheck ($directPairOutput.Contains('Pairing code: TEST-CODE')) 'Noninteractive pairing is available from the installed management entry point'
        $deviceOutput = Invoke-TestInstaller -ScriptPath $installedInstaller -Responses @('7', '1', 'y')
        $deviceArgs = @(Get-Content -LiteralPath (Join-Path $testRoot 'connector-args.txt'))
        Assert-InstallCheck ($deviceOutput.Contains('Test phone') -and $deviceOutput.Contains('设备授权已删除') -and
            $deviceArgs -contains '--remove-device' -and $deviceArgs -contains 'cli-test' -and -not $runningAgent.HasExited) 'Settings menu lists and deletes authorized devices without restarting the connector'
        $directDeleteOutput = Invoke-TestInstaller -ScriptPath $installedInstaller -Arguments @{Action='ManageDevices'; DeviceId='cli-test'; NonInteractive=$true}
        Assert-InstallCheck ($directDeleteOutput.Contains('设备授权已删除')) 'Noninteractive device deletion accepts a client ID'
        . (Join-Path $installRoot 'scripts\client-install.ps1')
        $installedConnect = Join-Path $installRoot 'scripts\connect.ps1'
        Assert-InstallerRejects { & $installedConnect -Restart } 'Restart validates Codex CLI before stopping the running connector'
        Assert-InstallCheck (-not $runningAgent.HasExited) 'Failed restart validation keeps the existing connector running'
        Set-Content -LiteralPath $fixtureCli -Value "@echo off`r`necho codex-cli test`r`nexit /b 0" -Encoding ASCII
        $restartOutput = & $installedConnect -Restart 6>&1 | Out-String
        Assert-InstallCheck ($runningAgent.HasExited -and -not $otherAgent.HasExited -and
            @(Get-CodexRMConnectorProcess $installedAgent).Count -eq 1 -and
            $restartOutput.Contains('system tray')) 'Restart stops only the selected installation and launches its hidden tray replacement'
        $restartArgs = @()
        for ($attempt = 0; $attempt -lt 50; $attempt++) {
            $restartArgs = @(Get-Content -LiteralPath (Join-Path $testRoot 'connector-args.txt'))
            if ($restartArgs -contains '--codex' -and $restartArgs -contains '--update-dir') { break }
            Start-Sleep -Milliseconds 100
        }
        Assert-InstallCheck ($restartArgs -contains '--codex' -and $restartArgs -contains $fixtureCli -and
            $restartArgs -contains '--update-dir' -and $restartArgs -contains $installRoot -and
            $restartArgs -notcontains '--pair') 'Restart retains the configured CLI, installation and automatic updates'
        foreach ($replacement in @(Get-CodexRMConnectorProcess $installedAgent)) {
            Stop-Process -InputObject $replacement -Force
            [void]$replacement.WaitForExit(10000)
        }
        $stoppedOutput = & $installedConnect -Restart 6>&1 | Out-String
        Assert-InstallCheck ($stoppedOutput.Contains('system tray') -and -not $otherAgent.HasExited -and
            @(Get-CodexRMConnectorProcess $installedAgent).Count -eq 1) 'Restart also starts a stopped connector in the tray'
        Assert-InstallerRejects { & $installedConnect -Restart -Pair } 'Pairing and restart cannot be requested together'
    } finally {
        $remainingAgents = @($runningAgent, $otherAgent) + @(Get-CodexRMConnectorProcess $installedAgent)
        foreach ($process in $remainingAgents) {
            if ($process -and -not $process.HasExited) {
                Stop-Process -InputObject $process -Force
                [void]$process.WaitForExit(10000)
            }
        }
    }

    Add-Content -LiteralPath (Join-Path $installRoot 'scripts\client-install.ps1') -Encoding UTF8 -Value @'

function Get-CodexRMConnectorProcess([string]$Binary) {
    return [pscustomobject]@{ Path = $Binary }
}
'@
    $duplicateOutput = & (Join-Path $installRoot 'scripts\connect.ps1') 6>&1 | Out-String
    Assert-InstallCheck ($duplicateOutput.Contains('already running')) 'Manual startup does not launch a duplicate installed connector'

    . (Join-Path $repoRoot 'scripts\client-install.ps1')
    function Start-Process {
        param([string]$FilePath, [string]$WorkingDirectory, [string]$ArgumentList, [string]$WindowStyle)
        $script:capturedLaunch = @{ FilePath = $FilePath; WorkingDirectory = $WorkingDirectory; ArgumentList = $ArgumentList; WindowStyle = $WindowStyle }
    }
    Start-CodexRMConnector -InstallDir $installRoot -Workspace $testRoot
    Assert-InstallCheck ($capturedLaunch.WorkingDirectory -eq $testRoot -and
        $capturedLaunch.WindowStyle -eq 'Hidden' -and
        $capturedLaunch.ArgumentList.Contains('"' + (Join-Path $installRoot 'scripts\connect.ps1') + '"') -and
        -not $capturedLaunch.ArgumentList.Contains('-NoExit')) 'Initial launch hides the console and quotes the installed script path'
    Remove-Item -LiteralPath Function:\Start-Process

    Remove-Item -LiteralPath (Join-Path $packageRoot '.state\connection.json') -Force
    @'
param($Manifest,$OutputPath)
if (-not (Test-Path -LiteralPath (Join-Path (Split-Path -Parent $PSScriptRoot) 'dist\CodexRM-Setup.exe') -PathType Leaf)) {
    throw 'The isolated native Setup fixture is missing.'
}
'@ | Set-Content -LiteralPath (Join-Path $packageRoot 'scripts\package-setup.ps1') -Encoding UTF8
    # The package must remain compatible with Windows PowerShell even if a source editor omits the BOM.
    $packageInstallerPath = Join-Path $packageRoot 'scripts\install-client.ps1'
    [IO.File]::WriteAllText($packageInstallerPath, [IO.File]::ReadAllText($packageInstallerPath), (New-Object Text.UTF8Encoding $false))
    $zipPath = Join-Path $testRoot 'public client.zip'
    & (Join-Path $packageRoot 'scripts\package-client.ps1') -OutputPath $zipPath 6>$null
    $expanded = Join-Path $testRoot 'expanded'
    Expand-Archive -LiteralPath $zipPath -DestinationPath $expanded
    Assert-InstallCheck ((Test-Path -LiteralPath (Join-Path $expanded 'CodexRM\CodexRM-Setup.exe') -PathType Leaf) -and
        -not (Test-Path -LiteralPath (Join-Path $expanded 'CodexRM\.state\connection.json'))) 'A generic package includes native Setup and can omit a preset RM URL'
    Assert-InstallCheck ([Linq.Enumerable]::SequenceEqual([byte[]][IO.File]::ReadAllBytes($setupFixture),
        [byte[]][IO.File]::ReadAllBytes((Join-Path $expanded 'CodexRM\CodexRM-Setup.exe')))) 'Packaging preserves the native Setup executable bytes'
    foreach ($packagedScript in @(Get-ChildItem -LiteralPath (Join-Path $expanded 'CodexRM\scripts') -Filter '*.ps1')) {
        $parseErrors = $null
        $tokens = $null
        $null = [System.Management.Automation.Language.Parser]::ParseFile($packagedScript.FullName, [ref]$tokens, [ref]$parseErrors)
        Assert-InstallCheck ((Get-Content -LiteralPath $packagedScript.FullName -Raw).Contains('Legacy update compatibility') -and
            -not $parseErrors.Count) ('Packaged compatibility script parses and points to native Setup: ' + $packagedScript.Name)
    }
    $manifestPath = Join-Path $testRoot 'update.json'
    $manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
    Assert-InstallCheck ($manifest.versionCode -eq 1 -and $manifest.versionName -ceq 'test' -and
        $manifest.fileSize -eq (Get-Item -LiteralPath $zipPath).Length -and
        $manifest.sha256 -eq (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash -and
        $manifest.downloadUrl -ceq 'updates/public%20client.zip') 'Packaging generates the expected update.json format with package size and SHA-256'
    $beforeManifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8
    @{ server = 'https://private.example'; enrollmentKey = 'private-test-key' } | ConvertTo-Json |
        Set-Content -LiteralPath (Join-Path $packageRoot '.state\connection.json') -Encoding UTF8
    $privateZip = Join-Path $testRoot 'private.zip'
    Assert-InstallerRejects { & (Join-Path $packageRoot 'scripts\package-client.ps1') -OutputPath $privateZip -IncludeEnrollmentKey 6>$null } 'Retired IncludeEnrollmentKey packages are rejected instead of exposing a key'
    Assert-InstallCheck ((Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8) -ceq $beforeManifest -and
        -not (Test-Path -LiteralPath $privateZip)) 'Rejected private packages preserve the public update manifest and create no ZIP'

    [IO.File]::WriteAllText($packageInstallerPath, [IO.File]::ReadAllText($packageInstallerPath), (New-Object Text.UTF8Encoding $true))
    $uninstallInstaller = $installer
    Invoke-TestInstaller -ScriptPath $uninstallInstaller -Responses @('8', 'n') | Out-Null
    Assert-InstallCheck (Test-Path -LiteralPath $installRoot -PathType Container) 'Declining uninstall preserves the installation'
    Invoke-TestInstaller -ScriptPath $uninstallInstaller -Responses @('8', 'y') | Out-Null
    Assert-InstallCheck (-not (Test-Path -LiteralPath $installRoot) -and -not (Test-Path -LiteralPath $recordPath) -and
        -not (Test-Path -LiteralPath $startupPath) -and -not (Test-Path -LiteralPath (Join-Path $desktop 'CodexRM 重启服务.lnk')) -and
        (Test-Path -LiteralPath (Join-Path $desktop 'Other app.lnk'))) 'Uninstall removes only CodexRM files, configuration, startup and shortcuts'

    Write-Host "All $checks Windows installation checks passed."
} finally {
    $env:LOCALAPPDATA = $previousLocalAppData
    $env:CODEXRM_ENROLL_KEY = $previousEnrollmentKey
    $env:CODEXRM_INSTALL_TEST_ROOT = $previousTestRoot
    $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
    if ($resolvedTestRoot.StartsWith($testParent.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($resolvedTestRoot).StartsWith('CodexRM-install-tests-') -and
            (Test-Path -LiteralPath $resolvedTestRoot -PathType Container)) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
