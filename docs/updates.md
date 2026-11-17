# Windows 客户端更新与发布

客户端通过设置页中独立配置的更新服务器查询 `update.json`，默认 `https://codexrm.dagongren.tech/`。清单中的 `downloadUrl` 指向 ZIP 更新包。更换远程 RM URL 不改变更新源。Cloudreve 登录凭据及发布配置仅保存在被 Git 忽略的 `build/publish.config.json`，不会进入安装包。

## 原生客户端检查更新

安装后的 Agent 直接检查、下载、替换和重启，不调用 PowerShell。设置入口位于系统托盘；也可重新运行在线安装器更新。更新只替换受管理的 Agent exe，保留连接设置、工作区、设备身份、配对关系和自启动设置。

```cmd
bin\codexrm-agent.exe --check-update --update-dir "D:\Apps\CodexRM"
```

`--check-update` 只查询清单，输出 JSON，不受每日自动检查间隔限制。内部的 `--install-update` 供临时副本在 Agent 停止后替换 exe；普通用户可直接重新运行在线安装器。

通过 `--update-manifest` 可临时指定清单。在线安装器的 `--manifest` 会保存为 `.state/connection.json` 的 `updateManifestUrl`；更改设置页的更新服务器会替换旧的清单覆盖地址。

## 每日自动更新

已安装客户端默认启用自动更新。首次启动检查，以后每 24 小时检查；时间保存在安装目录的 `.state/update-state.json`，重启不重置间隔，更改更新服务器或清单地址后重新检查。配对命令和模拟模式不自动更新。

发现更大的 `versionCode` 时，后台下载 ZIP，校验大小、SHA-256 及包内二进制版本，关闭 Codex app-server，由独立更新进程等待旧 Agent 退出、备份替换文件并隐藏启动新 Agent。新版本必须在 60 秒内启动 Agent 管理循环；替换或启动失败会回滚文件并尝试启动原版本。服务器暂时不可达或尚未配置 Codex 时，托盘和设置页继续可用。自动更新会中断当时正在执行的任务。日志写入 `.state/update.log`。

## 一键发布到 Cloudreve

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

`-TestLocalUpdate` 从 `%LOCALAPPDATA%\CodexRM\installation.json` 找到本机安装目录，通过临时 Agent 副本执行原生更新，再启动 exe；对尚未迁移的旧安装保留脚本调用兼容。不覆盖已保存的清单地址。自定义安装位置用 `-LocalInstallDir` 指定。发布前检查 Agent 和连接配置，更新后核对实际版本及连接配置未变。连接器正在运行时需先从托盘退出；已是当前版本时仅检查清单。

构建或打包失败会还原版本文件；发布失败保留版本及已生成的安装包，修复网络或配置后重试：

```cmd
build\release.cmd nopause -PublishOnly
```

`-PublishOnly` 不再递增版本，也不重新打包客户端，可在网盘上传或 RM 发布失败后重试；需要重试可选步骤时追加 `-DeployServer` 和/或 `-TestLocalUpdate`，会复用已构建的 Server 和客户端产物，同时同步当前本地 `web/`。客户端使用独立配置的更新源。

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

服务端通过公开路由 `GET /update.json` 提供清单，默认从 `<服务端数据目录>/updates/update.json` 读取，可通过 `codexrm-server --updates /path/to/releases` 修改，响应使用 `Cache-Control: no-store`。清单下载地址默认指向 Cloudreve，也支持其他 HTTP(S) 直链。`GET /updates/{文件名}.zip` 保留用于直接托管 ZIP；使用 Cloudreve 时无需向 RM 上传 ZIP。
