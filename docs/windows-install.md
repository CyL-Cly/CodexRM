# Windows Agent 安装与设置

需要在 Windows 设置中使用 **移动** 更换安装磁盘时，选择 [MSIX 版 CodexRM Agent](windows-msix.md)。以下内容介绍现有 EXE 安装方式。

1. 安装并登录 Codex 桌面客户端，或配置好 Codex CLI。
2. 下载并双击 `CodexRM-Setup.exe`，选择安装目录。在线安装器读取公开更新清单，校验下载包的大小、SHA-256 和实际客户端版本后安装。
3. 安装完成后，Agent 在系统托盘运行。没有配置 RM URL 时，每次启动都会自动打开设置页面；也可右键托盘图标选择 **设置**。
4. 在设置页填写 **远程服务器** 和 **连接授权密钥**，点击 **保存并连接**。安装过程不要求这两项配置。
5. 点击 **生成配对码**，用另一台设备扫描二维码，或在远程网页输入配对码。配对码有效期由服务器指定，默认 10 分钟。

安装、运行、重启和卸载均由 Go 程序直接完成，不需要 PowerShell、Go、npm 或管理员权限。Agent 自动查找 Codex 桌面版内的 CLI，也支持命令行指定路径。

安装时只创建一个桌面快捷方式 **CodexRM 重启服务**：目标为安装目录的 `bin\codexrm-agent.exe`，参数为 `--restart`。该参数先等待当前 Agent 和其 Codex app-server 退出，再启动新的 Agent；尚未运行时直接启动。登录自启动通过当前用户的注册表 Run 项直接运行 Agent exe。

## 本机设置页面

设置页包含：

- **远程服务器**：RM 服务的完整 HTTP(S) 地址，支持路径前缀。
- **连接授权密钥**：该服务器提供的注册密钥。已保存时留空保留，输入新值替换；切换服务器不会复用旧服务器的密钥。
- **生成配对码**：显示一次性配对码、二维码、配对链接和到期时间。
- **已配对设备管理**：刷新已授权浏览器列表并移除授权。移除后，设备必须重新配对。
- **更新服务器**：默认折叠，默认为 `https://codexrm.dagongren.tech/`。更新源独立于远程服务器。

首次打开设置时，Agent 随机选择一个 `127.0.0.1` 端口并打开浏览器。同一进程重复打开设置复用该端口；关闭浏览器页面后继续监听，直到 Agent 退出。接口仅允许持有本次启动凭证的本机页面访问。保存配置后自动重连，设置页面和监听端口保持可用。

没有配置 RM URL 的新安装会自动打开设置页；已配置但远程服务器离线、密钥错误或暂时找不到 Codex 时，Agent 继续驻留托盘，可随时打开设置修正。右键托盘图标选择 **退出 CodexRM** 会关闭本机监听和 Codex app-server。

## 离线安装

完整解压 `CodexRM-windows-amd64.zip`，双击其中的 `CodexRM-Setup.exe`。安装器发现同目录下 `bin\codexrm-agent.exe` 时直接安装本地文件，无需下载安装包。其安装及设置流程与在线安装相同。

## 命令行参数

```cmd
CodexRM-Setup.exe --install-dir "D:\Apps\CodexRM" --workspace "D:\projects"
CodexRM-Setup.exe --manifest "https://updates.example/update.json"
CodexRM-Setup.exe --non-interactive --no-shortcuts --no-autostart --no-start

bin\codexrm-agent.exe --restart
bin\codexrm-agent.exe --settings
bin\codexrm-agent.exe --codex "D:\Apps\Codex\resources\codex.exe"
bin\codexrm-agent.exe --uninstall
```

`--settings` 在 Agent 已运行时打开现有实例的设置页，不启动第二个连接器。`--config` 可指定连接配置文件；默认从 exe 所在安装目录读取 `.state\connection.json`。`--data` 可指定设备凭据目录，默认继续复用当前用户每台服务器对应的设备身份。

## 数据、更新与卸载

连接设置保存在安装目录的 `.state\connection.json`，运行日志在 `.state\agent.log`。设备身份继续保存在当前用户 `.codexrm` 下各服务器对应的目录，切换服务器后切回可复用原身份及服务器端的配对授权。

安装记录保存在 `%LOCALAPPDATA%\CodexRM\installation.json`，安装目录另有 `.state\installation.json` 标记。重新安装保留已有连接设置、工作区和设备身份；旧脚本安装的启动入口会迁移为直接运行 exe。

已安装的 Agent 每 24 小时从更新服务器检查 `update.json`，校验新包后自动替换并重启，保留本机设置及配对关系。自定义在线安装器的 `--manifest` 会保留为独立更新源。参见 [更新与发布](updates.md)。

从 Windows **设置 → 应用 → 已安装的应用 → CodexRM** 卸载，或执行 `bin\codexrm-agent.exe --uninstall`。卸载会关闭 Agent，移除程序目录、快捷方式和自启动项，保留安装目录外的工作区以及当前用户的服务器设备身份，便于重新安装。

## 重新打包

```powershell
.\build\scripts\build.ps1 -Target Agent
.\build\scripts\package-client.ps1
.\build\scripts\package-setup.ps1 -Manifest "https://updates.example/update.json"
```

开发用构建脚本仍可使用 PowerShell；安装后的客户端不依赖这些脚本。通用安装包不包含远程服务器、注册密钥或本机设备身份。产物位于 `build/dist`。
