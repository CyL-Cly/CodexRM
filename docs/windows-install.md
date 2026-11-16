# 在另一台 Windows 电脑安装 CodexRM

此安装包用于把另一台 Windows 电脑接入 CodexRM。作为远程操作端的手机或电脑只需访问网页并配对。

## 安装

1. 在目标电脑上安装并打开 Codex 客户端，完成登录或配置好已有的 API 服务。
2. 将整个安装压缩包解压到一个普通文件夹，不要直接在压缩包内运行。
3. 双击解压目录中的 `install.cmd`。脚本自动查找 Codex 客户端自带的 `resources\codex.exe`，安装到当前用户的 `%LOCALAPPDATA%\CodexRM`，并创建桌面快捷方式，无需管理员权限、Go 或 npm。
4. 安装完成后连接器自动启动，窗口显示 `Pairing code`。访问部署时配置的服务地址（例如 `https://your-server.example`），点击“连接设备”，输入该配对码。

保持连接器窗口打开即可远程使用，按 Ctrl+C 停止。重启电脑后双击桌面的 **CodexRM** 启动；需要重新配对时，双击 **CodexRM Pair**，配对码有效期为 10 分钟。脚本不设置开机自启。

包名含 `private` 的安装包包含当前服务的设备注册配置，用于自己的电脑；它不包含本机 Codex 登录文件、会话历史或已有设备身份，新电脑会创建自己的设备身份。

## 指定项目目录或安装位置

在解压目录打开 CMD：

```cmd
install.cmd -Workspace "D:\projects\my-project"
```

目录必须已经存在；未指定时默认使用当前用户主目录。安装后也可从网页添加和切换工作区。

```cmd
install.cmd -InstallDir "D:\Apps\CodexRM" -Workspace "D:\projects\my-project"
```

安装脚本会创建两个桌面快捷方式。若只需复制文件而暂不启动，可运行：

```cmd
install.cmd -NoStart
```

## 没找到客户端自带的 CLI

先打开 Codex 客户端，再重新安装。也可明确指定客户端内部的 CLI 路径：

```cmd
install.cmd -CodexPath "D:\Program Files\WindowsApps\OpenAI.Codex_实际版本_x64__实际包标识\app\resources\codex.exe"
```

脚本优先检查正在运行的客户端和当前用户安装的 Store 包，随后检查上次可用路径及常见安装目录。每次启动都会重新查找，避免把客户端版本目录固定写入启动命令。

## 使用不带注册密钥的通用安装包

在 PowerShell 中先设置对应服务的注册密钥，再安装：

```powershell
$env:CODEXRM_ENROLL_KEY = '<服务器注册密钥>'
.\install.cmd
```

连接自己的中继服务可加 `-Server "https://your-server.example"`。自定义服务地址同样保存在安装目录的 `.state\connection.json` 中。

## 重新打包

在源码目录先运行 `scripts/build.ps1`，再运行：

```powershell
.\scripts\package-client.ps1 -Server "https://your-server.example"
# 为自己的其他电脑生成包含当前服务注册配置的安装包；不要公开分发此包
.\scripts\package-client.ps1 -IncludeEnrollmentKey
```

输出位于 `dist`。升级连接器时先关闭已安装的连接器窗口，再用新包重新运行 `install.cmd`；保留原有设备身份及配对关系。
