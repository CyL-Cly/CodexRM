param(
    [string]$InstallDir,
    [string]$Workspace,
    [Alias('RMUrl')][string]$Server,
    [string]$EnrollmentKey,
    [string]$CodexPath,
    [string]$UpdateManifest,
    [string]$DeviceId,
    [ValidateSet('Install', 'Configure', 'EnableStartup', 'DisableStartup', 'CheckUpdate', 'Update', 'Pair', 'ManageDevices', 'Uninstall')][string]$Action,
    [switch]$NonInteractive,
    [switch]$NoShortcuts,
    [switch]$NoAutostart,
    [switch]$NoStart
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'client-install.ps1')
$sourceRoot = Split-Path -Parent $PSScriptRoot
$defaultRoot = Join-Path $env:LOCALAPPDATA 'CodexRM'
$recordPath = Join-Path $defaultRoot 'installation.json'
$record = Read-CodexRMJson $recordPath
if (-not $InstallDir) {
    if ($record -and $record.installDir) { $InstallDir = $record.installDir }
    else { $InstallDir = $defaultRoot }
}
$InstallDir = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
if ($record -and $record.installDir -ne $InstallDir) { $record = $null }
$installedConfig = Read-CodexRMJson (Join-Path $InstallDir '.state\connection.json')
$isInstalled = ($null -ne $record -or $null -ne $installedConfig)
$startupPath = Get-CodexRMStartupPath
$installedBinary = Join-Path $InstallDir 'bin\codexrm-agent.exe'
$currentVersion = $null

Write-Host ''
Write-Host 'CodexRM 安装与设置'
Write-Host "安装位置: $InstallDir"
if ($isInstalled) {
    Write-Host '安装状态: 已安装'
    Write-Host "当前 RM URL: $($installedConfig.server)"
    if (Test-Path -LiteralPath $installedBinary -PathType Leaf) {
        try {
            $currentVersion = Get-CodexRMBinaryVersion $installedBinary
            Write-Host "当前版本: $($currentVersion.versionName) (code $($currentVersion.versionCode))"
        } catch { Write-Host '当前版本: 无法读取' -ForegroundColor Yellow }
    }
} else { Write-Host '安装状态: 未安装' }
if (Test-Path -LiteralPath $startupPath -PathType Leaf) { Write-Host '登录自启动: 已启用' }
else { Write-Host '登录自启动: 未启用' }

# Removing startup must remain available when Codex is missing or has moved.
$resolvedCodex = $null
try {
    . (Join-Path $PSScriptRoot 'codex-path.ps1')
    $previousPath = if ($installedConfig) { $installedConfig.codexPath } else { $null }
    $resolvedCodex = Resolve-CodexDesktopCli -CodexPath $CodexPath -PreviousPath $previousPath
    Write-Host "检测到 Codex CLI: $resolvedCodex"
} catch { Write-Host "Codex CLI: 未找到 ($($_.Exception.Message))" -ForegroundColor Yellow }

if (-not $Action) {
    if ($NonInteractive) { $Action = 'Install' }
    elseif ($isInstalled -and $Server) { $Action = 'Configure' }
    elseif ($isInstalled) {
        Write-Host ''
        Write-Host '1. 修改 RM URL'
        Write-Host '2. 启用登录自启动'
        Write-Host '3. 删除登录自启动'
        Write-Host '4. 重新安装 / 更新（启用自启动）'
        Write-Host '5. 检查在线更新'
        Write-Host '6. 生成配对码'
        Write-Host '7. 管理已授权设备'
        Write-Host '8. 卸载 CodexRM'
        Write-Host '0. 退出'
        while (-not $Action) {
            switch ((Read-Host '请选择 [0-8]').Trim()) {
                '1' { $Action = 'Configure' }
                '2' { $Action = 'EnableStartup' }
                '3' { $Action = 'DisableStartup' }
                '4' { $Action = 'Install' }
                '5' { $Action = 'CheckUpdate' }
                '6' { $Action = 'Pair' }
                '7' { $Action = 'ManageDevices' }
                '8' { $Action = 'Uninstall' }
                '0' { return }
                default { Write-Host '请输入 0 到 8。' }
            }
        }
    } else { $Action = 'Install' }
}

if ($Action -eq 'Uninstall') {
    if (-not $isInstalled) { throw '当前目录没有已安装的 CodexRM。' }
    $installRoot = [IO.Path]::GetFullPath($InstallDir).TrimEnd('\')
    $driveRoot = [IO.Path]::GetPathRoot($installRoot).TrimEnd('\')
    $hasMarkers = (Test-Path -LiteralPath (Join-Path $installRoot 'bin\codexrm-agent.exe') -PathType Leaf) -and
        ((Test-Path -LiteralPath (Join-Path $installRoot '.state\connection.json') -PathType Leaf) -or
         ($record -and $record.installDir -eq $installRoot))
    if ($installRoot -eq $driveRoot -or -not $hasMarkers) {
        throw '卸载安全检查失败：目标不是有效的 CodexRM 安装目录。'
    }
    if (-not $NonInteractive) {
        Write-Host '卸载将删除程序、连接配置、设备身份和配对信息，不会删除安装目录之外的工作区。'
        if ((Read-Host "确认卸载 $installRoot？[y/N]").Trim() -notin @('y', 'Y')) { return }
    }
    foreach ($process in @(Get-CodexRMConnectorProcess $installedBinary)) {
        Stop-Process -Id $process.Id -Force -ErrorAction Stop
        if (-not $process.WaitForExit(10000)) { throw '连接器未退出，无法卸载。请从托盘退出后重试。' }
    }
    Remove-CodexRMShortcuts
    if (Test-Path -LiteralPath $recordPath -PathType Leaf) {
        $recordFullPath = [IO.Path]::GetFullPath($recordPath)
        if (-not $recordFullPath.StartsWith($installRoot + '\', [StringComparison]::OrdinalIgnoreCase)) {
            Remove-Item -LiteralPath $recordPath -Force
        }
    }
    Remove-Item -LiteralPath $installRoot -Recurse -Force
    Remove-CodexRMUninstallEntry
    Write-Host 'CodexRM 已卸载。安装目录之外的项目工作区保持不变。'
    return
}

if ($Action -eq 'Pair') {
    $connectPath = Join-Path $InstallDir 'scripts\connect.ps1'
    if (-not $installedConfig -or -not (Test-Path -LiteralPath $installedBinary -PathType Leaf) -or
        -not (Test-Path -LiteralPath $connectPath -PathType Leaf)) { throw '请先完成安装，再生成配对码。' }
    Write-Host '正在生成配对码...'
    & $connectPath -Pair
    return
}

if ($Action -eq 'ManageDevices') {
    if (-not $installedConfig -or -not (Test-Path -LiteralPath $installedBinary -PathType Leaf)) {
        throw '请先完成安装，再管理已授权设备。'
    }
    $env:CODEXRM_ENROLL_KEY = $installedConfig.enrollmentKey
    $agentArgs = @('--server', $installedConfig.server)
    if ($DeviceId) {
        Write-Host "正在删除设备授权: $DeviceId"
        $null = Invoke-CodexRMAgentCommand -Binary $installedBinary -Arguments ($agentArgs + @('--remove-device', $DeviceId))
        Write-Host '设备授权已删除，该设备需要重新配对。'
        return
    }
    $deviceJson = Invoke-CodexRMAgentCommand -Binary $installedBinary -Arguments ($agentArgs + '--list-devices')
    $devices = @($deviceJson | Out-String | ConvertFrom-Json)
    if (-not $devices.Count) {
        Write-Host '当前没有已授权设备。'
        return
    }
    Write-Host '已授权设备（授权永久有效，删除后需重新配对）:'
    for ($i = 0; $i -lt $devices.Count; $i++) {
        Write-Host ("{0}. {1} ({2}) [{3}]" -f ($i + 1), $devices[$i].clientName, $devices[$i].deviceType, $devices[$i].clientId)
    }
    if ($NonInteractive) { return }
    $selection = (Read-Host '请输入要删除的设备编号 [0=取消]').Trim()
    if ($selection -eq '0') { return }
    $index = 0
    if (-not [int]::TryParse($selection, [ref]$index) -or $index -lt 1 -or $index -gt $devices.Count) {
        throw '设备编号无效。'
    }
    $selected = $devices[$index - 1]
    if ((Read-Host "确认删除 $($selected.clientName) 的授权？[y/N]").Trim() -notin @('y', 'Y')) { return }
    $null = Invoke-CodexRMAgentCommand -Binary $installedBinary -Arguments ($agentArgs + @('--remove-device', $selected.clientId))
    Write-Host '设备授权已删除，该设备需要重新配对。'
    return
}

if ($Action -in @('CheckUpdate', 'Update')) {
    if (-not $installedConfig -or -not $currentVersion) { throw '请先完成安装，再检查更新。' }
    $updateBinary = Join-Path $sourceRoot 'bin\codexrm-agent.exe'
    if (-not (Test-Path -LiteralPath $updateBinary -PathType Leaf)) { $updateBinary = $installedBinary }
    $arguments = @('--server', $installedConfig.server, '--update-dir', $InstallDir,
        '--current-version-code', [string]$currentVersion.versionCode)
    if (-not $UpdateManifest -and $installedConfig.updateManifestUrl) { $UpdateManifest = $installedConfig.updateManifestUrl }
    if ($UpdateManifest) { $arguments += @('--update-manifest', $UpdateManifest) }
    Write-Host '正在检查在线更新...'
    $result = Invoke-CodexRMUpdateCommand -Binary $updateBinary -Arguments ($arguments + '--check-update')
    if (-not $result.manifest) { Write-Host '尚未发布更新。'; return }
    if (-not $result.updateAvailable) { Write-Host "已是最新版本: $($currentVersion.versionName)"; return }
    Write-Host "发现新版本: $($result.manifest.versionName) (code $($result.manifest.versionCode))"
    if ($result.manifest.releaseNotes) { Write-Host $result.manifest.releaseNotes }
    if ($Action -eq 'CheckUpdate') {
        if ($NonInteractive) { return }
        if ((Read-Host '下载并安装此更新？[y/N]').Trim() -notin @('y', 'Y')) { return }
    }
    if (@(Get-CodexRMConnectorProcess $installedBinary).Count) {
        throw '请先关闭正在运行的 CodexRM 连接器，再选择检查更新并安装。'
    }
    Write-Host '正在下载、校验并安装更新...'
    $updated = Invoke-CodexRMUpdateCommand -Binary $updateBinary -Arguments ($arguments + '--install-update')
    if ($updated) {
        Save-CodexRMInstallation -Path $recordPath -InstallDir $InstallDir -Previous $record
        Write-Host "更新完成: $($updated.versionName)。本机连接配置、配对信息和自启动设置已保留。"
        if (-not $NoStart) { Start-CodexRMConnector -InstallDir $InstallDir -Workspace $installedConfig.workspace }
    } else { Write-Host '已是最新版本。' }
    return
}

if ($Action -in @('EnableStartup', 'DisableStartup')) {
    if ($Action -eq 'EnableStartup') {
        if (-not $installedConfig -or -not (Test-Path -LiteralPath (Join-Path $InstallDir 'scripts\connect.ps1') -PathType Leaf)) {
            throw '请先完成安装，再启用自启动。'
        }
        Set-CodexRMAutostart -InstallDir $InstallDir -Workspace $installedConfig.workspace
        Write-Host '已启用当前用户的登录自启动。'
    } else {
        if (Test-Path -LiteralPath $startupPath -PathType Leaf) {
            Remove-Item -LiteralPath $startupPath -Force
        }
        Write-Host '已删除登录自启动。当前正在运行的连接器保持运行。'
    }
    if ($isInstalled) { Save-CodexRMInstallation -Path $recordPath -InstallDir $InstallDir -Previous $record }
    return
}

if ($Action -eq 'Configure' -and -not $installedConfig) { throw '请先完成安装。' }
$requiredFiles = @('bin\codexrm-agent.exe', 'scripts\connect.ps1', 'scripts\codex-path.ps1',
    'scripts\client-install.ps1', 'scripts\install-client.ps1', 'install.cmd')
if ($Action -eq 'Install') {
    foreach ($relativePath in $requiredFiles) {
        if (-not (Test-Path -LiteralPath (Join-Path $sourceRoot $relativePath) -PathType Leaf)) {
            throw "安装包缺少文件: $relativePath。请完整解压安装包；源码目录请先运行 build.bat。"
        }
    }
    if ($sourceRoot.TrimEnd('\') -ne $InstallDir -and @(Get-CodexRMConnectorProcess $installedBinary).Count) {
        throw '连接器正在运行。请先从托盘图标退出连接器，再重新安装 / 更新。'
    }
}

$packageConfig = Read-CodexRMJson (Join-Path $sourceRoot '.state\connection.json')
if (-not $Server) {
    $defaultServer = if ($installedConfig -and $installedConfig.server) { $installedConfig.server }
        elseif ($packageConfig) { $packageConfig.server } else { '' }
    if ($NonInteractive) { $Server = $defaultServer }
    else {
        $Server = Read-Host "请输入 RM URL [$defaultServer]（回车使用当前地址）"
        if (-not $Server.Trim()) { $Server = $defaultServer }
    }
}
while ($true) {
    $Server = ([string]$Server).Trim().TrimEnd('/')
    $serverUri = $null
    if ([Uri]::TryCreate($Server, [UriKind]::Absolute, [ref]$serverUri) -and
            $serverUri.Scheme -in @('http', 'https') -and $serverUri.Host -and
            -not $serverUri.UserInfo -and -not $serverUri.Query -and -not $serverUri.Fragment) { break }
    if ($NonInteractive) { throw 'RM URL 必须是完整的 HTTP / HTTPS 地址，且不能带账号、查询参数或片段。' }
    Write-Host '地址无效，例如: https://your-server.example'
    $Server = Read-Host '请输入 RM URL'
}

# Only reuse a key for the same server, including its base path.
if (-not $EnrollmentKey) {
    if ($env:CODEXRM_ENROLL_KEY) { $EnrollmentKey = $env:CODEXRM_ENROLL_KEY }
    elseif ($installedConfig -and $installedConfig.server -and $installedConfig.server.TrimEnd('/') -ceq $Server) {
        $EnrollmentKey = $installedConfig.enrollmentKey
    }
    if (-not $EnrollmentKey -and $packageConfig -and $packageConfig.server -and
            $packageConfig.server.TrimEnd('/') -ceq $Server) { $EnrollmentKey = $packageConfig.enrollmentKey }
}
if (-not $EnrollmentKey -and -not $serverUri.IsLoopback) {
    if ($NonInteractive) { throw '缺少注册密钥。请传入 -EnrollmentKey 或设置 CODEXRM_ENROLL_KEY。' }
    $secret = Read-Host '请输入该 RM 服务的注册密钥（输入不显示）' -AsSecureString
    $secretPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try { $EnrollmentKey = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPointer) }
    if (-not $EnrollmentKey) { throw '注册密钥不能为空。' }
}
if (-not $Workspace) {
    if ($installedConfig -and $installedConfig.workspace) { $Workspace = $installedConfig.workspace }
    else { $Workspace = [Environment]::GetFolderPath('UserProfile') }
}
if (-not (Test-Path -LiteralPath $Workspace -PathType Container)) { throw "工作区不存在: $Workspace" }
$Workspace = (Resolve-Path -LiteralPath $Workspace).ProviderPath
if (-not $resolvedCodex) {
    if ($NonInteractive) { throw '未找到 Codex CLI。请打开 Codex 客户端，或使用 -CodexPath 指定路径。' }
    $CodexPath = Read-Host '请输入 Codex CLI 完整路径（resources\codex.exe）'
    if (-not $CodexPath.Trim()) { throw '未指定 Codex CLI。请打开 Codex 客户端后重试。' }
    $resolvedCodex = Resolve-CodexDesktopCli -CodexPath $CodexPath.Trim().Trim('"')
    Write-Host "Codex CLI: $resolvedCodex"
}
$codexVersion = & $resolvedCodex --version
if ($LASTEXITCODE) { throw "Codex CLI 无法运行: $resolvedCodex" }
Write-Host "CLI 版本: $codexVersion"

if ($Action -eq 'Install') {
    foreach ($relativePath in $requiredFiles) {
        $destination = Join-Path $InstallDir $relativePath
        [void][IO.Directory]::CreateDirectory((Split-Path -Parent $destination))
        if ($sourceRoot.TrimEnd('\') -ne $InstallDir) {
            Copy-Item -LiteralPath (Join-Path $sourceRoot $relativePath) -Destination $destination -Force
        }
    }
}
$connection = [ordered]@{
    server = $Server
    enrollmentKey = $EnrollmentKey
    workspace = $Workspace
    codexPath = $resolvedCodex
    autoUpdate = $true
}
if ($UpdateManifest) { $connection['updateManifestUrl'] = $UpdateManifest }
elseif ($installedConfig -and $installedConfig.updateManifestUrl) { $connection['updateManifestUrl'] = $installedConfig.updateManifestUrl }
Write-CodexRMJson -Path (Join-Path $InstallDir '.state\connection.json') -Value $connection
if ($Action -eq 'Install') {
    if (-not $NoAutostart) { Set-CodexRMAutostart -InstallDir $InstallDir -Workspace $Workspace }
    if (-not $NoShortcuts) { Set-CodexRMDesktopShortcuts -InstallDir $InstallDir -Workspace $Workspace }
}
Save-CodexRMInstallation -Path $recordPath -InstallDir $InstallDir -Previous $record
Write-Host ''
Write-Host "已保存 RM URL: $Server"
Write-Host "安装位置: $InstallDir"
Write-Host "安装记录: $recordPath"
Write-Host '再次运行 install.cmd 或桌面 CodexRM 设置和生成配对码，可修改地址、管理自启动或生成配对码。'
Write-Host "连接器在系统托盘运行。访问 $Server，需要配对时使用桌面 CodexRM 设置和生成配对码。"
if (@(Get-CodexRMConnectorProcess $installedBinary).Count) {
    Write-Host '连接器正在运行；请双击桌面 CodexRM 重启服务，使新配置生效。'
} elseif (-not $NoStart) {
    Start-CodexRMConnector -InstallDir $InstallDir -Workspace $Workspace
}
