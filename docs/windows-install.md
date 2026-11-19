# Windows Agent 安装与设置

需要在 Windows 设置中使用 **移动** 更换安装磁盘时，选择 [MSIX 版 CodexRM Agent](windows-msix.md)。以下是 EXE 安装方式。

1. 安装并登录 Codex 桌面客户端，或配置好 Codex CLI。
2. 下载并双击 `CodexRM-Setup.exe`，选择安装目录。安装包内置 Agent，可离线安装。向导可选开机启动和桌面快捷方式。
3. 安装完成后，Agent 在系统托盘运行。全新安装默认连接公共中继 `https://codexrm.ooth.tech`，无需密钥。右键托盘图标选择 **设置**。
4. 如需改用自托管中继，填写 **远程服务器** 及该服务器要求的密钥，点击 **保存并连接**。
5. 点击 **生成配对码**，用另一台设备扫描二维码，或在远程网页输入配对码。配对码有效期由服务器指定，默认 10 分钟。

安装器使用 Inno Setup，可为当前用户安装。安装后从开始菜单打开 **CodexRM**，也可使用桌面快捷方式或系统托盘。Agent 自动查找 Codex 桌面版内的 CLI，也支持命令行指定路径。

## 本机设置页面

设置页包含：

- **远程服务器**：RM 服务的完整 HTTP(S) 地址，支持路径前缀。
- **连接授权密钥**：公共中继可留空，自托管服务器按配置填写。已保存时留空保留，输入新值替换；点击 **清除已保存密钥** 可显式清空。切换服务器不会复用旧服务器的密钥。
- **生成配对码**：显示一次性配对码、二维码、配对链接和到期时间。
- **已配对设备管理**：显示配对时间、服务器记录的最近连接时间和 IP，可刷新列表及移除授权。在线心跳会更新最近连接时间；服务器启动时及每分钟自动移除超过 7 天未连接的设备，持续在线的设备不会被清理。升级前没有连接记录的设备有一次性的 7 天宽限，重启不会延长宽限。移除后，设备必须重新配对。
- **检查更新**：立即检查新版本；有进行中的对话或排队消息时挂起安装，全部结束后自动更新。更新服务器固定为 `https://codexrm.dagongren.tech/`，不在设置中显示。

首次打开设置时，Agent 随机选择一个 `127.0.0.1` 端口并打开浏览器。同一进程重复打开设置复用该端口；关闭浏览器页面后继续监听，直到 Agent 退出。接口仅允许持有本次启动凭证的本机页面访问。保存配置后自动重连，设置页面和监听端口保持可用。

远程服务器离线、密钥错误或暂时找不到 Codex 时，Agent 继续驻留托盘，可随时打开设置修正。右键托盘图标选择 **退出 CodexRM** 会关闭本机监听和 Codex app-server。

## 命令行参数

```cmd
CodexRM-Setup.exe /DIR="D:\Apps\CodexRM"
CodexRM-Setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /TASKS="autostart,desktopicon"
CodexRM-Setup.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /MERGETASKS="!autostart,!desktopicon"

bin\codexrm-agent.exe --restart
bin\codexrm-agent.exe --settings
bin\codexrm-agent.exe --codex "D:\Apps\Codex\resources\codex.exe"
```

`--settings` 在 Agent 已运行时打开现有实例的设置页，不启动第二个连接器。`--config` 可指定连接配置文件；默认从 exe 所在安装目录读取 `.state\connection.json`。`--data` 可指定设备凭据目录，默认继续复用当前用户每台服务器对应的设备身份。

## 数据、更新与卸载

连接设置保存在安装目录的 `.state\connection.json`，运行日志在 `.state\agent.log`。设备身份继续保存在当前用户 `.codexrm` 下各服务器对应的目录，切换服务器后切回可复用原身份及服务器端的配对授权。

安装记录保存在 `%LOCALAPPDATA%\CodexRM\installation.json`，安装目录另有 `.state\installation.json` 标记。重新安装保留已有连接设置、工作区和设备身份；旧脚本安装的启动入口会迁移为直接运行 exe。

已安装的 Agent 每次启动立即检查更新；持续运行时，成功检查后每 20 小时检查固定更新服务器的 `update.json`，失败则按 1、5、15、60 分钟有上限退避重试，也可在设置页手动检查。设置页显示最近检查、失败原因和下次重试时间。校验新包后等待全部进行中的对话及排队消息结束，再自动替换并重启，保留本机设置及配对关系。参见 [更新与发布](updates.md)。

从 Windows **设置 → 应用 → 已安装的应用 → CodexRM** 卸载。卸载会关闭 Agent，移除程序文件、快捷方式和自启动项，保留 `.state` 中的连接设置、工作区和当前用户的设备身份。重新安装到原目录可继续使用这些配置。

## 重新打包

```powershell
.\build\scripts\build.ps1 -Target Agent
.\build\scripts\package-setup.ps1
.\build\scripts\package-client.ps1
```

构建需要 Go 与 Inno Setup 7。可用 `INNO_ISCC` 指定 `ISCC.exe` 的完整路径。`package-setup.ps1` 生成独立安装器，`package-client.ps1` 同时生成更新 ZIP 和清单。通用安装包使用内置公共中继地址。注册密钥和本机设备身份保存在用户数据中。产物位于 `build/dist`。
