# Shared Windows installation and shortcut operations. Loading this file has no side effects.
function ConvertTo-CodexRMCommandLine([string[]]$Arguments) {
    $quoted = foreach ($argument in $Arguments) {
        if ($null -eq $argument) { $argument = '' }
        if ($argument.Length -gt 0 -and $argument -notmatch '[\s"]') { $argument; continue }
        $text = '"'
        $slashes = 0
        foreach ($character in $argument.ToCharArray()) {
            if ($character -eq '\') { $slashes++; continue }
            if ($character -eq '"') { $text += ('\' * ($slashes * 2 + 1)) + '"' }
            else {
                if ($slashes) { $text += ('\' * $slashes) }
                $text += $character
            }
            $slashes = 0
        }
        if ($slashes) { $text += ('\' * ($slashes * 2)) }
        $text + '"'
    }
    return @($quoted) -join ' '
}

function Invoke-CodexRMAgentCommand([string]$Binary, [string[]]$Arguments) {
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $commandDir = Join-Path $temporaryRoot ('CodexRM-cli-' + [Guid]::NewGuid().ToString('N'))
    $stdoutPath = Join-Path $commandDir 'stdout.txt'
    $stderrPath = Join-Path $commandDir 'stderr.txt'
    try {
        [void][IO.Directory]::CreateDirectory($commandDir)
        $process = Start-Process -FilePath $Binary -ArgumentList (ConvertTo-CodexRMCommandLine $Arguments) `
            -WindowStyle Hidden -Wait -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
        $stdout = if (Test-Path -LiteralPath $stdoutPath -PathType Leaf) { [IO.File]::ReadAllText($stdoutPath) } else { '' }
        $stderr = if (Test-Path -LiteralPath $stderrPath -PathType Leaf) { [IO.File]::ReadAllText($stderrPath) } else { '' }
        if ($process.ExitCode) {
            $details = @($stderr.Trim(), $stdout.Trim()) | Where-Object { $_ }
            throw "Agent command failed (exit $($process.ExitCode)): $($details -join ' | ')"
        }
        if ($stderr) { Write-Verbose $stderr }
        return $stdout
    } finally {
        $resolvedCommand = [IO.Path]::GetFullPath($commandDir)
        if ($resolvedCommand.StartsWith($temporaryRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($resolvedCommand).StartsWith('CodexRM-cli-') -and
            (Test-Path -LiteralPath $resolvedCommand -PathType Container)) {
            Remove-Item -LiteralPath $resolvedCommand -Recurse -Force
        }
    }
}

function Get-CodexRMBinaryVersion([string]$Binary) {
    try { $output = Invoke-CodexRMAgentCommand -Binary $Binary -Arguments @('--version') }
    catch { throw "Could not read the client version. Run build.bat before packaging. $($_.Exception.Message)" }
    $version = $output | ConvertFrom-Json
    if (-not $version.versionCode -or -not $version.versionName) { throw 'Client release version is missing.' }
    return $version
}

function Invoke-CodexRMUpdateCommand([string]$Binary, [string[]]$Arguments) {
    $temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $helperDir = Join-Path $temporaryRoot ('CodexRM-manual-update-' + [Guid]::NewGuid().ToString('N'))
    $helperPath = Join-Path $helperDir 'codexrm-update.exe'
    try {
        [void][IO.Directory]::CreateDirectory($helperDir)
        Copy-Item -LiteralPath $Binary -Destination $helperPath
        $output = Invoke-CodexRMAgentCommand -Binary $helperPath -Arguments $Arguments
        if ($output) { return $output | ConvertFrom-Json }
    } catch {
        throw "Update command failed. The installed connection settings are retained. $($_.Exception.Message)"
    } finally {
        $resolvedHelper = [IO.Path]::GetFullPath($helperDir)
        if ($resolvedHelper.StartsWith($temporaryRoot.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -and
            [IO.Path]::GetFileName($resolvedHelper).StartsWith('CodexRM-manual-update-') -and
            (Test-Path -LiteralPath $resolvedHelper -PathType Container)) {
            Remove-Item -LiteralPath $resolvedHelper -Recurse -Force
        }
    }
}

function Read-CodexRMJson([string]$Path) {
    if (Test-Path -LiteralPath $Path -PathType Leaf) {
        return Get-Content -LiteralPath $Path -Raw -Encoding UTF8 | ConvertFrom-Json
    }
}

function Write-CodexRMJson([string]$Path, $Value) {
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $Path))
    $temporaryPath = $Path + '.' + [Guid]::NewGuid().ToString('N') + '.tmp'
    try {
        $Value | ConvertTo-Json | Set-Content -LiteralPath $temporaryPath -Encoding UTF8
        Move-Item -LiteralPath $temporaryPath -Destination $Path -Force
    } finally {
        if (Test-Path -LiteralPath $temporaryPath -PathType Leaf) { Remove-Item -LiteralPath $temporaryPath -Force }
    }
}

function Get-CodexRMShortcutDirectory([string]$Folder) {
    $directory = [Environment]::GetFolderPath($Folder)
    if (-not $directory) { throw "Windows folder could not be found: $Folder" }
    return $directory
}

function Get-CodexRMStartupPath {
    return Join-Path (Get-CodexRMShortcutDirectory 'Startup') 'CodexRM.lnk'
}

function Set-CodexRMShortcut([string]$Path, [string]$Arguments, [string]$Workspace, [string]$Description) {
    [void][IO.Directory]::CreateDirectory((Split-Path -Parent $Path))
    $shell = New-Object -ComObject WScript.Shell
    $shortcut = $shell.CreateShortcut($Path)
    $shortcut.TargetPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass ' + $Arguments
    $shortcut.WorkingDirectory = $Workspace
    $shortcut.Description = $Description
    $shortcut.Save()
}

function Set-CodexRMAutostart([string]$InstallDir, [string]$Workspace) {
    $connectPath = Join-Path $InstallDir 'scripts\connect.ps1'
    Set-CodexRMShortcut -Path (Get-CodexRMStartupPath) -Workspace $Workspace `
        -Arguments ('-WindowStyle Hidden -File "' + $connectPath + '"') `
        -Description 'Start the CodexRM connector at user sign-in'
}

function Set-CodexRMDesktopShortcuts([string]$InstallDir, [string]$Workspace) {
    $desktop = Get-CodexRMShortcutDirectory 'DesktopDirectory'
    $connectPath = Join-Path $InstallDir 'scripts\connect.ps1'
    $settingsPath = Join-Path $InstallDir 'scripts\install-client.ps1'
    foreach ($entry in @(
            @{ Name = 'CodexRM 重启服务'; Arguments = '-WindowStyle Hidden -File "' + $connectPath + '" -Restart'; Description = 'Restart the CodexRM connector in the system tray' },
            @{ Name = 'CodexRM 设置和生成配对码'; Arguments = '-NoExit -File "' + $settingsPath + '" -InstallDir "' + $InstallDir + '"'; Description = 'Configure CodexRM, manage startup and generate pairing codes' })) {
        Set-CodexRMShortcut -Path (Join-Path $desktop ($entry.Name + '.lnk')) `
            -Arguments $entry.Arguments -Workspace $Workspace -Description $entry.Description
    }
    foreach ($name in @('CodexRM', 'CodexRM Pair', 'CodexRM Settings')) {
        $legacyPath = Join-Path $desktop ($name + '.lnk')
        if (Test-Path -LiteralPath $legacyPath -PathType Leaf) { Remove-Item -LiteralPath $legacyPath -Force }
    }
    Write-Host '桌面快捷方式: CodexRM 重启服务、CodexRM 设置和生成配对码'
}

function Remove-CodexRMShortcuts {
    $desktop = Get-CodexRMShortcutDirectory 'DesktopDirectory'
    foreach ($name in @('CodexRM 重启服务', 'CodexRM 设置和生成配对码', 'CodexRM', 'CodexRM Pair', 'CodexRM Settings')) {
        $path = Join-Path $desktop ($name + '.lnk')
        if (Test-Path -LiteralPath $path -PathType Leaf) { Remove-Item -LiteralPath $path -Force }
    }
    $startup = Get-CodexRMStartupPath
    if (Test-Path -LiteralPath $startup -PathType Leaf) { Remove-Item -LiteralPath $startup -Force }
}

function Get-CodexRMUninstallRegistryPath {
    return 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\CodexRM'
}

function Set-CodexRMUninstallEntry([string]$InstallDir) {
    if ($env:CODEXRM_INSTALL_TEST_ROOT) { return }
    $binary = Join-Path $InstallDir 'bin\codexrm-agent.exe'
    $script = Join-Path $InstallDir 'scripts\install-client.ps1'
    $powershell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $script + '" -Action Uninstall -InstallDir "' + $InstallDir + '"'
    $version = try { (Get-CodexRMBinaryVersion $binary).versionName } catch { 'unknown' }
    $path = Get-CodexRMUninstallRegistryPath
    [void](New-Item -Path $path -Force)
    $properties = @{
        DisplayName = 'CodexRM'
        DisplayVersion = [string]$version
        Publisher = 'CodexRM'
        InstallLocation = $InstallDir
        DisplayIcon = $binary
        UninstallString = '"' + $powershell + '" ' + $arguments
        QuietUninstallString = '"' + $powershell + '" ' + $arguments + ' -NonInteractive'
        NoModify = 1
        NoRepair = 1
    }
    foreach ($name in $properties.Keys) {
        $type = if ($name -in @('NoModify', 'NoRepair')) { 'DWord' } else { 'String' }
        New-ItemProperty -Path $path -Name $name -Value $properties[$name] -PropertyType $type -Force | Out-Null
    }
}

function Remove-CodexRMUninstallEntry {
    if ($env:CODEXRM_INSTALL_TEST_ROOT) { return }
    $path = Get-CodexRMUninstallRegistryPath
    if (Test-Path $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}

function Save-CodexRMInstallation([string]$Path, [string]$InstallDir, $Previous) {
    $now = [DateTime]::UtcNow.ToString('o')
    $installedAt = if ($Previous -and $Previous.installedAt) { $Previous.installedAt } else { $now }
    Write-CodexRMJson -Path $Path -Value ([ordered]@{
        schemaVersion = 1
        installDir = $InstallDir
        installedAt = $installedAt
        updatedAt = $now
        autoStart = Test-Path -LiteralPath (Get-CodexRMStartupPath) -PathType Leaf
    })
    Set-CodexRMUninstallEntry -InstallDir $InstallDir
}

function Get-CodexRMConnectorProcess([string]$Binary) {
    foreach ($process in @(Get-Process -Name codexrm-agent -ErrorAction SilentlyContinue)) {
        try {
            if ($process.Path -eq $Binary) { $process }
        } catch {
            # Processes owned by another user may not expose their executable.
        }
    }
}

function Start-CodexRMConnector([string]$InstallDir, [string]$Workspace) {
    $powershellPath = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $connectPath = Join-Path $InstallDir 'scripts\connect.ps1'
    Start-Process -FilePath $powershellPath -WorkingDirectory $Workspace -WindowStyle Hidden `
        -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -File "' + $connectPath + '"')
}
