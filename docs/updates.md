# 客户端更新与发布

客户端固定通过 `https://codexrm.dagongren.tech/update.json` 查询更新，设置页不再显示或允许修改更新服务器。旧配置中的更新服务器及清单覆盖地址会被忽略。清单中的 `downloadUrl` 指向 ZIP 更新包。更换远程 RM URL 不改变更新源。Cloudreve 登录凭据及发布配置仅保存在被 Git 忽略的 `build/publish.config.json`，不会进入安装包。

## 原生客户端检查更新

安装后的 Agent 直接检查、下载、替换和重启，不调用 PowerShell。设置入口位于系统托盘；设置页的“检查更新”会立即查询固定更新源，发现新版本后自动下载。存在进行中的对话、正在发送的请求或排队消息时，更新保持挂起，全部结束后自动安装。等待审批或用户输入、浏览器关闭以及中继断线均不会把进行中的对话视为空闲。设置页会显示待安装状态。也可重新运行在线安装器更新。更新只替换受管理的 Agent exe，保留连接设置、工作区、设备身份、配对关系和自启动设置。

```cmd
bin\codexrm-agent.exe --check-update --update-dir "D:\Apps\CodexRM"
```

`--check-update` 只查询清单，输出 JSON，不受自动检查间隔限制。内部的 `--install-update` 供临时副本在 Agent 停止后替换 exe；普通用户可直接重新运行在线安装器。

旧的 `--update-manifest` 参数仅为兼容启动脚本而接受，不再改变 Agent 的更新源。在线安装器的 `--manifest` 仅影响本次安装包下载，不会保存为后续更新源。

## 自动更新

已安装客户端默认启用自动更新。每次启动立即检查，即使上次检查距今不足 20 小时；持续运行期间每 20 小时检查一次。检查时间保存在安装目录的 `.state/update-state.json`，保存连接设置或重连不会重启检查循环。配对命令和模拟模式不自动更新。MSIX 版继续由 Windows 管理更新，不运行 EXE 更新器。

发现更大的 `versionCode` 时，后台下载 ZIP，校验大小、SHA-256 及包内二进制版本，等待所有对话和排队消息结束后关闭 Codex app-server，由独立更新进程等待旧 Agent 退出、备份替换文件并隐藏启动新 Agent。已暂停的排队消息需恢复执行或移除后才会安装更新，防止重启丢失消息。开始安装与新请求接收共用互斥保护，防止空闲检查后插入新任务。更新辅助进程启动失败时保留待安装包，一分钟后重试。新版本必须在 60 秒内启动 Agent 管理循环；替换或启动失败会回滚文件并尝试启动原版本。服务器暂时不可达或尚未配置 Codex 时，托盘和设置页继续可用。日志写入 `.state/update.log`。

## 一键发布到 Cloudreve

全平台发布使用 `build\release-all.cmd`，先构建 Android 正式 APK，再发布 Windows Agent、部署 Linux RM 和 Web、发布 Android 更新，最后上传 Linux amd64 Agent 独立包，并将本地生成的 `build/dist/linux-agent-download.json` 发布到 RM 的 `/linux-update.json`。Windows MSIX 不在此流程内。

```cmd
build\release-all.cmd nopause -ReleaseNotes "修复连接问题"
```

各渠道分步发布；如果中途失败，按脚本提示使用相应平台的 `-PublishOnly` 继续，不要直接重跑全平台入口递增版本。只补发当前版本的 Linux Agent 时，使用 `build\release-all.cmd nopause -LinuxOnly`。

发布流程参考 SmsForwarder 的 `build.bat`：版本号递增 → 构建 → 打包 → 上传网盘 → 更新清单。初次配置：

1. 将 `build/publish.config.example.json` 复制为 `build/publish.config.json`，填写 Cloudreve 站点、邮箱和密码。也可通过 `CLOUDREVE_PASSWORD` 提供密码。`dir` 为空表示网盘根目录，填写其他目录时需预先创建。
2. 填写 `rm.server`（RM URL）、`rm.sshTarget`（现有 SSH 别名或 user@host）、`rm.updateDir`（服务端更新目录）和 `rm.owner`（服务运行用户，默认 `codexrm`）。发布电脑需安装 OpenSSH，SSH 目标需能非交互登录并写入该目录及设置服务用户权限。需要同时部署 Server 时，还可设置 `rm.installDir`（默认 `/opt/codexrm`）和 `rm.service`（默认 `codexrm`，不带 `.service` 后缀）。
3. 运行 `build\release.cmd`。先上传并校验 ZIP，再原子发布 `update.json` 到 RM，最后通过公网 `{RM URL}/update.json` 验证清单内容和禁止缓存响应头。

```cmd
build\release.cmd
build\release.cmd nopause -ReleaseNotes "修复连接问题"

# 一并发布 Agent、Linux Server 和网页前端
build\release.cmd nopause -DeployServer

# 可选：发布后调用本机已安装客户端的更新脚本并验证实际版本
build\release.cmd nopause -TestLocalUpdate

# 自定义安装目录时显式指定
build\release.cmd nopause -TestLocalUpdate -LocalInstallDir "D:\Apps\CodexRM"

# 两个可选步骤也可一起执行
build\release.cmd nopause -DeployServer -TestLocalUpdate
```

脚本递增 `internal/release/release.go` 的 `VersionCode` 和 `VersionName` 补丁号，构建 Windows Agent，生成 `build/dist/CodexRM-windows-amd64-v{版本}-{版本代码}.zip`、`build/dist/update.json` 和 `build/dist/CodexRM-Setup.exe`。在线安装器内置 `{RM URL}/update.json`，用户双击即可下载并安装最新包；公共安装包和在线安装器都不包含注册密钥。`-DeployServer` 和 `-TestLocalUpdate` 默认关闭，不影响原有发布流程。

发布工具通过 Cloudreve v4 的 local/relay 分片上传接口上传 ZIP，生成永久匿名直链，实际匿名下载并校验通过后，才将包含直链的本地清单上传到 RM 更新目录的临时文件，保留上一份 `update.json.previous`，再重命名为 `update.json`。只在网盘上存放 ZIP，清单由 RM 路由提供；以后发布清单无需重启中继。上传工具直接连接网盘，避免继承构建代理。

`-DeployServer` 额外构建 `build/bin/codexrm-server-linux-amd64`，通过 SSH 上传到 `rm.installDir/bin` 的唯一临时文件，保留原二进制为 `codexrm-server.previous`，原子替换后重启 `rm.service`，并通过公网 `{RM URL}/api/v1/health` 验证服务。随后自动打包并部署本地 `web/` 的页面、脚本、样式和图标到 `rm.installDir/web`，保留 `web.previous-{唯一标识}` 备份，逐个下载公网文件并核对 SHA-256。前端测试文件不发布。SSH 账户需有写入安装目录及执行 `systemctl restart/is-active` 的权限；服务替换或重启失败时会报错，并提示旧二进制备份位置。

`-TestLocalUpdate` 从 `%LOCALAPPDATA%\CodexRM\installation.json` 找到本机安装目录，通过临时 Agent 副本执行原生更新，再启动 exe；对尚未迁移的旧安装保留脚本调用兼容。Agent 使用内置固定更新源。自定义安装位置用 `-LocalInstallDir` 指定。发布前检查 Agent 和连接配置，更新后核对实际版本及连接配置未变。连接器正在运行时需先从托盘退出；已是当前版本时仅检查清单。

构建或打包失败会还原版本文件；发布失败保留版本及已生成的安装包，修复网络或配置后重试：

```cmd
build\release.cmd nopause -PublishOnly
```

`-PublishOnly` 不再递增版本，也不重新打包客户端，可在网盘上传或 RM 发布失败后重试；需要重试可选步骤时追加 `-DeployServer` 和/或 `-TestLocalUpdate`，会复用已构建的 Server 和客户端产物，同时同步当前本地 `web/`。客户端使用内置固定更新源。

## 清单和安装包格式

清单字段沿用 SmsForwarder：

```json
{
  "versionCode": 2,
  "versionName": "0.6.1",
  "downloadUrl": "https://your-cloud.example/f/id/CodexRM-windows-amd64-v0.6.1-2.zip",
  "releaseNotes": "修复连接问题",
  "fileSize": 12345678,
  "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
}
```

`versionCode` 为递增整数，仅大于本机时更新；`versionName` 为客户端版本，与中继协议版本独立。下载地址支持 HTTP(S) 完整 URL 或相对清单地址的路径。单个 ZIP 和解压后的受管理文件上限均为 512 MiB。

单独打包可运行 `build/scripts/package-client.ps1`，在 ZIP 同目录生成包含版本、大小、SHA-256 的 `update.json`；`-DownloadUrl` 指定外部下载地址。安装包包含 Agent 和原生安装器，不携带远程连接地址或密钥。为兼容旧版自动更新器，ZIP 保留旧文件名的占位项；原生安装器及更新器只提取 Agent exe，全新安装不落地这些脚本。

## 自托管更新源

RM 通过 `GET /update.json`、`GET /android-update.json` 和 `GET /linux-update.json` 发布各平台清单，默认从 `<服务端数据目录>/updates/` 读取。可通过 `codexrm-server --updates /path/to/releases` 修改读取目录，清单响应使用 `Cache-Control: no-store`。

将各平台的软件包上传到 Cloudreve、OSS、EOS 等外部存储，把客户端可直接访问的完整 HTTP(S) 下载地址填入对应清单的 `downloadUrl`，然后将清单发布到 RM。

## Linux Agent 手动更新

从 `https://codexrm.dagongren.tech/linux-update.json` 查询 Linux amd64 Agent 版本，按 `downloadUrl` 下载 ZIP，核对 `fileSize` 和 `sha256` 后解压。停止 Agent，替换 `codexrm-agent` 二进制，然后重新启动。

## Android 手动检查更新

Android 在“侧栏 → 我的远程工作区 → 检查更新”中手动查询 `https://codexrm.dagongren.tech/android-update.json`。该清单与 Windows 版本号和 ZIP 完全独立，不跟随当前配对服务器变化，只有用户点击“立即更新”后才下载和安装。客户端下载 APK 到应用私有缓存，校验清单大小、SHA-256、APK 包名和版本后通过 `PackageInstaller` 覆盖安装。Android 12+ 在已允许“安装未知应用”、声明 `UPDATE_PACKAGES_WITHOUT_USER_ACTION` 且应用更新自身时，使用 `USER_ACTION_NOT_REQUIRED` 免除系统安装确认；系统不允许免确认的设备仍会显示确认界面。

签名 APK 使用独立脚本打包。手动发布到对象存储时，可用 `-DownloadUrl` 指定外部下载地址。

```powershell
build\scripts\package-android.ps1 -ApkPath android\app\build\outputs\apk\release\app-release.apk -ReleaseNotes "更新说明"
```

脚本读取 `android/app/build.gradle.kts` 的 `versionCode` 和 `versionName`，在 `build/dist/` 生成 APK 副本及 `android-update.json`。使用 `codexrm-publish` 上传到 Cloudreve 时，工具会把清单的 `downloadUrl` 更新为 APK 的外部直链。确认下载地址可用后，运行下方命令发布 Android 清单。

```powershell
$publish = Get-Content build/publish.config.json -Raw | ConvertFrom-Json
build\scripts\publish-manifest.ps1 -Manifest build\dist\android-update.json -Name android-update.json -RM $publish.rm
```

发布工具保留上一份 `android-update.json.previous`，不会覆盖 Windows 的 `update.json`。每次发布必须递增 Android `versionCode`，且新 APK 与已安装版本签名一致；Debug 签名只能用于测试。
