# Windows 原生客户端的系统“移动”功能

使用 **MSIX 版 CodexRM Agent**，即可从 **Windows 设置 → 应用 → 已安装的应用 → CodexRM Agent → 移动** 选择其他磁盘。Windows 负责搬迁程序、更新开始菜单入口和自启动任务；移动完成后从开始菜单重新打开 Agent。

支持 Windows 10 2004 及之后的桌面系统。目标磁盘须支持 Windows 应用安装，由系统的移动对话框列出。系统选择的是安装磁盘，具体目录由 Windows 管理。

## 安装和使用

双击已签名且签名受本机信任的 `CodexRM-Agent-版本-x64.msix`，由 Windows 应用安装程序完成安装。安装后开始菜单的 **CodexRM Agent** 启动原生连接器；托盘里的设置、配对和退出功能与 EXE 版相同。首次启动启用登录自启动，可在 Windows 的“启动应用”中关闭。

连接配置和日志位于 `%LOCALAPPDATA%\Packages\<包系列名称>\LocalState\`；路径独立于程序所在磁盘及版本。设备身份继续使用当前用户的 `.codexrm` 目录。移动程序无需重新设置连接或配对。

Windows 管理 MSIX 的更新与卸载。安装新版本时保持相同的包名称和签名发布者，使用更高版本号的 MSIX；通过 `.appinstaller` 安装的版本也可由 Windows 自动更新。MSIX 版不运行旧的 ZIP/exe 原地替换更新器。

现有 `CodexRM-Setup.exe` 安装仍可使用，Windows 的“移动”按钮适用于 MSIX 安装。由 EXE 版切换时先退出原 Agent 并关闭其登录自启动，再安装 MSIX；如需沿用连接设置，将原安装目录的 `.state\connection.json` 复制到新包的 `LocalState\connection.json` 后启动。已有服务器设备身份可继续复用。

## 构建与签名

开发电脑需 Go 和 Windows 10/11 SDK。运行：

```powershell
.\build\scripts\package-msix.ps1
```

生成 `build/dist/CodexRM-Agent-版本-x64-unsigned.msix`。此产物已由 MakeAppx 校验，安装前仍须签名。双击 `build/package-msix.cmd` 也可构建。

使用当前用户“个人”证书存储中的代码签名证书构建：

```powershell
.\build\scripts\package-msix.ps1 `
  -Publisher 'CN=你的签名发布者' `
  -CertificateThumbprint '证书的40位指纹'
```

`Publisher` 必须与证书 Subject 完全一致。证书位于本地计算机“个人”存储时加 `-MachineCertificateStore`。可用 `-TimestampUrl` 指定 RFC 3161 时间戳服务；`-WindowsSdkBin` 可指定 SDK 的 x64 工具目录。脚本同时导出公开的 `.cer` 文件，不导出私钥。

供个人或内部使用的自签名证书，需要先由目标电脑管理员将公开的 `.cer` 导入 **本地计算机 → 受信任人**，再安装 MSIX。面向其他用户分发时使用目标电脑信任的代码签名证书。

本次生成的 `CodexRM-Agent-0.6.6.0-x64.msix` 使用本机开发签名，同目录的同名 `.cer` 为公开证书。首次安装可双击 `.cer`，选择“安装证书 → 本地计算机 → 将所有证书放入下列存储 → 受信任人”，完成管理员确认后，再双击 MSIX。签名私钥保存在构建电脑当前用户的“个人”证书存储中，友好名称为 `CodexRM local MSIX development`；后续构建可按该证书的指纹继续签名。

需要通过 HTTPS 托管安装和自动更新时：

```powershell
.\build\scripts\package-msix.ps1 `
  -Publisher 'CN=你的签名发布者' `
  -CertificateThumbprint '证书的40位指纹' `
  -UpdateBaseUrl 'https://updates.example/native/'
```

将生成的 MSIX 与 `CodexRM-Agent.appinstaller` 放到该目录。用户通过 `.appinstaller` 安装，Windows 在启动时按 24 小时间隔检查更新，并执行系统后台更新检查。此更新源独立于 EXE 版的 `update.json`；现有 `release.cmd` 继续发布 EXE/ZIP，不会自动发布 MSIX。

## 验证真实跨盘移动

在具有第二个在线 Windows 应用安装卷的电脑上，以管理员身份运行：

```powershell
.\build\scripts\test-msix-move.ps1 -TargetDrive 'E:'
```

测试创建独立包标识和临时测试签名，安装到系统卷，启动原生 Agent，然后调用 Windows 的包移动功能迁往第二块磁盘，再验证配置、日志、启动和正常退出。结束时移除测试应用及其临时证书。结果保存在脚本输出的 `Evidence` 目录，不更改正式安装或默认安装卷。
