# CodexRM

自托管的 Codex 远程工作区。电脑上的 Go Agent 启动本地 Codex，通过出站 WebSocket 连接 Go 服务器；浏览器完成配对后即可远程操作。响应式 Web 界面支持深浅主题、手机布局和可安装的 PWA。

> CodexRM 是独立的社区项目，不是 OpenAI 官方产品。使用真实模式前，需要自行安装并配置 Codex 客户端。

```text
浏览器 ── HTTPS / WebSocket ── 中继服务器 :18080
                                  ↑ 出站 WebSocket
                              本地 Go Agent
                                  ↓ stdio / WebSocket
                            Codex app-server
```

功能包括设备配对与切换、多工作区与远程目录浏览、按文件夹筛选历史会话和分页、新建/重命名/归档会话、流式 Markdown 回复、模型和思考强度选择、权限设置、执行输出、文件 Diff、审批与用户输入、停止任务、断线重连。未发送的输入按设备、工作区和会话保存在当前浏览器中，切换或刷新后可恢复。真实会话由本机 Codex 保存；中继仅保存设备、浏览器授权和短期配对信息，不保存对话。

网页采用 DeepSeek Harness 的主题、侧栏、居中新对话页、圆角输入区、消息气泡和模型菜单布局，适配 CodexRM 的设备、工作区和 Codex 通信。模型菜单提供按供应商分组、搜索及思考强度选择。新对话沿用当前浏览器内这台设备最近选择或成功发送使用的供应商、模型和思考强度，跨工作区、刷新后保留；仅查看旧对话不会覆盖最近选择。无记录或模型已不可用时，选择可用的默认模型。复用主题和布局样式的 MIT 许可见 `web/dsh-LICENSE.txt`。

输入框旁的附件按钮可以发送文件和图片，也可以在网页输入框或页面空白处粘贴图片、拖入文件；搜索、其他输入框和弹窗保留自己的粘贴行为。支持多选、上传进度、重试、移除、仅发送附件及运行中的排队/插队发送。每个文件最大 20 MB，每条消息最多 10 个附件、合计 50 MB。PNG、JPEG、GIF、WebP 作为图像输入交给 Codex；其他文件提供电脑中的实际路径供 Codex 读取。已上传的附件草稿按设备、工作区和会话保留，网页中的历史附件支持图片预览和原文件下载。

附件分块经已配对的连接传到 Agent 数据目录的 `attachments/`，中继不保存文件；已发送的文件保留在电脑上供后续会话使用，手动移除后对应历史附件将无法下载。启用此功能需要同步更新网页、Agent 和 Server；旧 Agent 的附件入口会提示更新。

## PWA 与加载缓存

页面提供 Web App Manifest、桌面/移动端安装图标和 Service Worker，可通过浏览器“安装应用”使用独立窗口。首次在线访问后会预缓存应用壳，离线仍可打开界面和恢复本地草稿；远程设备连接、发送任务和同步会话仍需网络。

静态加载使用三层 Cache Storage：版本化应用壳采用缓存优先，页面采用网络优先并保留离线副本，Manifest 与图标采用运行时缓存和 stale-while-revalidate。版本化 JS/CSS 同时使用浏览器一年 immutable HTTP 缓存；HTML 和 Service Worker 每次检查更新。旧版本缓存会在升级后自动清理。`/api/`、带 Authorization 的请求、非 GET、跨域请求及 WebSocket 不经过 Service Worker 缓存，会话和配对响应不会写入 Cache Storage。

## 快速开始

需要 Go 1.24+。页面使用原生 HTML / CSS / JavaScript，无需 npm 构建；运行前端测试需要 Node.js 22+。

```powershell
git clone https://github.com/codexrm/codexrm.git
cd codexrm
.\build.bat
```

Windows 上可双击根目录的 `build.bat`，依次选择 Windows / Linux 和 Agent / Server，也可选择同时构建两者。程序输出到 `build/bin`：Windows 文件为 `codexrm-server.exe`、`codexrm-agent.exe`，Linux amd64 文件带 `-linux-amd64` 后缀。

也可直接运行 `.\build\scripts\build.ps1`，默认构建 Windows Agent 和 Server；加 `-Linux` 构建 Linux 版本，加 `-Target Agent` 或 `-Target Server` 单独构建。页面已内嵌至服务端二进制，不依赖额外的静态文件目录。

启动本地连接器：

```powershell
.\build\bin\codexrm-agent.exe
```

Windows Agent 直接在系统托盘运行，不依赖 PowerShell。尚未配置 RM URL 时，每次启动都会自动打开本机设置页面；也可右键托盘图标选择 **设置**。填写远程服务器和连接授权密钥后保存，Agent 自动连接。设置页可生成配对码、显示二维码、查看和移除已配对设备；更新服务器固定为 `https://codexrm.dagongren.tech/`，不在设置中显示。

```powershell
.\build\bin\codexrm-agent.exe --settings
.\build\bin\codexrm-agent.exe --restart
```

设置页首次打开时随机监听一个 `127.0.0.1` 端口，之后持续监听至 Agent 退出；再次打开复用同一端口。连接设置和日志在 `.state/` 中，设备身份继续按当前用户和服务器复用。相同配置路径的重复启动会打开已有实例的设置页；`--restart` 等待旧实例退出后重启。

已配对设备使用该浏览器检测到的完整 WebGL GPU 型号作为名称；检测不可用时回退为系统和浏览器名称。旧配对在浏览器重新连接后自动更新名称，无需重新配对。

## 其他 Windows 电脑安装

需要在 Windows“已安装的应用”中使用 **移动** 更换安装磁盘时，使用原生 **MSIX 版 CodexRM Agent**，参见 [MSIX 安装与跨盘移动](docs/windows-msix.md)。可运行 `.\build\scripts\package-msix.ps1` 构建；MSIX 由 Windows 管理安装、移动、更新和卸载。

首次安装只需下载 `CodexRM-Setup.exe` 并双击。选择安装目录后，Go 安装器下载并校验客户端；安装过程无需设置 RM URL 或密钥。首次启动自动打开本机设置页。离线 ZIP 完整解压后双击同名安装器，会直接使用包内文件安装。

默认安装到 `%LOCALAPPDATA%\CodexRM`，仅创建一个桌面快捷方式 **CodexRM 重启服务**，直接指向 `bin\codexrm-agent.exe --restart`。登录自启动直接运行 exe；从 Windows“已安装的应用”中可卸载。目标电脑需要已安装并登录或配置好 Codex 客户端，不需要 PowerShell、Go 或 npm。

```powershell
# 生成通用安装包，不包含远程连接地址或密钥
.\build\scripts\package-client.ps1
# 生成独立的在线安装器；省略 -Manifest 时使用发布配置中的 RM 更新源
.\build\scripts\package-setup.ps1 -Manifest "https://your-server.example/update.json"
```

安装包输出到 `build/dist`。安装器支持 `--install-dir`、`--workspace`、`--non-interactive` 等参数。详见 [Windows 安装说明](docs/windows-install.md)。

已安装的 Windows EXE 客户端每次启动立即检查更新，之后每 20 小时检查固定更新服务器的 `update.json`，也可在 Agent 设置页手动检查。发现新版本后自动下载；有进行中的对话或排队消息时挂起安装，全部结束后自动替换并重启。失败自动回滚，保留本机配置和配对信息。更新源与远程连接服务器独立。MSIX 版由 Windows 管理更新。开发者可运行 `build\release.cmd` 构建并发布。详见 [自动更新与发布](docs/updates.md)。

## 多工作区

一个 Agent 可以同时管理多个文件夹，Agent 的 `--cwd` / 安装器的 `--workspace` 只指定初始默认目录，无需为每个项目启动连接器。

1. 点击侧栏“工作区”旁的 **+**，或输入框下方的文件夹按钮。
2. 在已连接的电脑上浏览磁盘、主目录及子文件夹，也可以输入绝对路径后点击“前往”。点击“添加此文件夹”将其保存为工作区。
3. 侧栏自动加载全部未归档会话，按原工作目录分组；文件夹及组内会话均按最近更新时间排序，无需逐个手动添加。页面可见时每 15 秒同步其他客户端的新会话。点击文件夹名称展开或收起。
4. 点击文件夹旁的 **+** 或输入框下方的目录下拉框，在所选目录新建会话；已有会话保持自己的工作目录。已固定工作区旁的 **×** 取消固定，含历史会话的文件夹仍会自动显示。

固定的工作区存放在 Agent 数据目录的 `workspaces.json`，同一设备的配对浏览器共享该列表；当前选择及文件夹折叠状态按设备保存在浏览器中，刷新及切换设备后恢复。目录浏览读取的是 Agent 所在电脑，网页服务器不需要访问项目文件。

桌面端可拖动侧栏右边缘调整宽度，刷新后保留；双击边缘恢复默认宽度，聚焦边缘后也可用左右方向键调整。右键对话或点击其旁的“…”打开删除菜单，确认后从列表移除，历史仍保留在电脑的 Codex 归档中。删除正在运行的对话前需先停止任务。

权限默认采用“完整访问 + 从不请求审批”，设置会在新建会话及下一次发送时传入 Codex。若曾手动保存其他权限设置，仍使用该设置。多个审批可以分别处理或批量允许/拒绝；其他待处理问题的输入会保留，响应经连接器确认后才移除。

CodexRM 创建或恢复会话时会启用 Codex CLI 普通模式的提问功能，无需切换到计划模式。收到问题后可查看选项说明、选择答案或填写自定义回答，再点击“提交回答”；也可以点击“跳过”。普通模式的非阻塞问题会保留至回答或被 Codex 取消，已完成一轮不会直接清除问题；刷新或重连后会重新同步未处理的问题。此配置只应用于远程会话，不修改本机全局 Codex 配置。

桌面客户端占用的会话以只读快照展示，每 3 秒同步已保存消息。独立 app-server 可能将未加载的进行中轮次标为 `interrupted`；此时会结合原始生命周期记录判断，只有明确的停止事件才显示“任务已停止”。无法确认的状态显示为等待原客户端更新。

任务运行中可以继续输入消息，点击“排队发送”或按 Enter，当前任务成功完成后按顺序逐条发送；点击“插队发送”或按 Ctrl / ⌘ Enter，把补充指令追加到当前任务。Shift Enter 换行，停止按钮单独保留。排队列表支持取消消息；任务停止、失败或消息发送失败时会暂停排队，点击“继续排队”后恢复。队列由本地 Agent 在内存中管理，切换会话、刷新或关闭网页不会中断排队；Agent 或 Codex app-server 重启后队列会清空。只读会话无法排队或插队，排队需要更新本地 Agent。

## 供应商与模型

Agent 默认只读本机 `~/.cc-switch/cc-switch.db` 中的 Codex 供应商，在输入框下方按供应商分组显示模型。每组分别请求自己的 `/models` 接口，并合并 CCSwitch 保存的模型与思考强度；在线查询失败时继续显示已保存的模型。官方组使用 CCSwitch 的 `codex-official-models.json` 缓存，实际对话仍需本机可用的官方登录。

点击模型旁的刷新按钮重新读取供应商和模型。不同工作区会分别记住模型及思考强度，选择保存在此浏览器中，并按设备、目录区分。同一供应商可以在已有会话中换模型；切换供应商会回到新会话，原会话保留在历史列表中。

每个会话向 Codex app-server 注入独立的供应商标识和连接设置，恢复历史时根据会话保存的标识读取原供应商。Agent 数据目录的 `thread-models.json` 仅保存会话的供应商标识、模型与思考强度；旧会话会从本地历史恢复最后使用的模型。密钥仅在本机 Agent 与 app-server 之间传递，网页只接收供应商和模型元数据，不修改 CCSwitch 当前选择、数据库或全局 Codex 配置。CCSwitch 中移除供应商后，对应会话会明确提示配置缺失。

如数据库不在默认位置，可设置 `CODEXRM_CCSWITCH_DB`，或传入 `--ccswitch-db <绝对路径>`。未安装 CCSwitch、模拟模式及连接外部 app-server 时，仍可使用当前 Codex 配置的模型。

## 构建与测试

需要 Go 1.24+。页面使用原生 HTML / CSS / JavaScript，无需 npm 构建。Server 直接从磁盘 `web/` 目录提供页面，不使用 Go embed；更新页面只需替换该目录，无需重新编译或重启 Server。真实模式默认从 Codex 桌面版安装位置查找自带的 CLI，无需另外安装 CLI 或加入 PATH。Windows 上会检查正在运行的桌面版、Store 安装信息及各本地磁盘的常见安装目录，桌面版关闭时也可查找。直接运行 Agent 时，找不到桌面版 CLI 才使用 PATH；也支持用 `--codex` 指定 CLI 路径。

```powershell
.\build\scripts\build.ps1
.\build\scripts\build.ps1 -Linux
go test ./...
go test -race ./...
node --test web/app.test.mjs web/sw.test.mjs
node --check web/app.js
node --check web/pwa.js
node --check web/sw.js
node --check internal/agenthost/web/app.js
# 原生安装和单实例测试包含在 go test ./... 中，使用隔离目录和启动项
```

构建产物输出到被 Git 忽略的 `build/bin/`。`codex/` 上游参考源码、`ChatGPT*` 设计参考和私人部署记录不参与构建，也不包含在公开仓库中。

## 本地演示

```powershell
# 终端 1：服务端，从当前仓库的 web/ 目录读取页面
.\build\bin\codexrm-server.exe --addr 127.0.0.1:8080 --data .state/demo-server --web web

# 终端 2：模拟设备，在系统托盘运行
.\build\bin\codexrm-agent.exe --server http://127.0.0.1:8080 --mock --data .state/demo-agent

# 生成配对码
.\build\bin\codexrm-agent.exe --server http://127.0.0.1:8080 --mock --data .state/demo-agent --pair
```

访问 http://127.0.0.1:8080 并输入配对码。模拟模式支持流式回复、历史、重命名、归档、停止；发送含“审批”的任务可测试审批回传。模拟会话只保存在内存中，不调用模型或执行命令。也可运行 `build/scripts/dev-mock.ps1` 一键启动。

## 连接真实 Codex

默认由 Agent 启动 `codex app-server --listen stdio://`，继承当前用户的 Codex 登录与配置：

```powershell
.\build\bin\codexrm-agent.exe --server https://your-server.example --cwd D:\projects\my-project
```

支持 `--codex <可执行文件路径>`。Agent 运行独立 app-server，可列出、恢复同一 Codex 用户目录中可见的历史会话。另一个实例仍持有写入锁的会话以只读历史展示，结束原会话后可重新打开继续。

也可连接已有 app-server：

```powershell
codex app-server --listen ws://127.0.0.1:4500
.\build\bin\codexrm-agent.exe --server https://your-server.example --appserver ws://127.0.0.1:4500
```

若该 WebSocket 要求 Bearer token，设置 `CODEXRM_APPSERVER_TOKEN` 或传入 `--appserver-token`。浏览器刷新会保留配对；重新进入会话时从 Codex 同步历史。连接中断时不重放用户任务，避免重复执行。

## 自行部署

```bash
# 在 Linux 上构建，或上传 Windows 交叉编译的 Linux 二进制
go build -trimpath -ldflags '-s -w' -o codexrm-server ./cmd/codexrm-server
sudo bash build/scripts/install-server.sh ./codexrm-server ./deploy/codexrm.service ./web
sudo systemctl status codexrm
sudo journalctl -u codexrm -n 50 --no-pager
```

安装脚本生成 `/opt/codexrm/server.env`，权限为 `0600`，里面的 `CODEXRM_ENROLL_KEY` 用于注册设备。将相同值设置到本地 Agent 的环境变量：

```powershell
$env:CODEXRM_ENROLL_KEY = '<服务器注册密钥>'
.\build\bin\codexrm-agent.exe --server https://your-server.example --cwd D:\projects\my-project
```

将 `deploy/nginx-location.conf` 中的代理规则合入已有 TLS 站点。服务进程使用专用 `codexrm` 用户，状态目录为 `/opt/codexrm/data`，页面目录为 `/opt/codexrm/web`。后续更新页面只需将本地 `web/` 同步到该目录并刷新浏览器，不需要重新上传二进制或重启服务。前端引用统一携带 `?v=YYYYMMDD` 日期版本，发布页面时应同步更新 `index.html`、`manifest.webmanifest`、`sw.js` 中的版本号。如需直接监听公网端口，自行设置 `--addr :18080`；已部署实例通过 HTTPS 域名访问。

注册密钥仅用于 Agent 注册；浏览器使用 10 分钟有效的一次性配对码换取专属 token。服务器记录配对设备的最近连接时间及 IP，Agent 设置页可查看；在线心跳持续更新时间。服务器启动时及每分钟清理超过 7 天未连接的设备授权，持续在线的设备保留；升级前缺少连接记录的设备给予一次性的 7 天宽限，重启不会延长。设备信息 API、WebSocket、解除配对均校验凭据；支持 Origin 检查和设备间路由隔离。可在 Agent 设置的“已配对设备管理”中删除授权，删除后立即失效并需要重新配对。默认无注册密钥时仅接受直接来自 loopback 的注册，反向代理场景需配置注册密钥。配对浏览器可以操作对应电脑上的 Codex，权限由新会话的 sandbox 与审批设置控制。

## 结构与接口

```text
cmd/codexrm-server/   HTTP / WebSocket 服务入口
cmd/codexrm-agent/    本地连接器入口
internal/agent/      出站重连、RPC 并发与审批回传
internal/appserver/  Codex stdio / WS 客户端与模拟服务
internal/server/     配对、鉴权、中继与磁盘静态 UI
internal/store/      JSON 状态原子持久化
internal/protocol/   中继消息结构
web/                 响应式前端
deploy/              systemd 与 Nginx 示例
build/scripts/       开发构建、发布与服务器安装脚本
```

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/health` | 健康检查 |
| `GET /update.json` | Windows 客户端自动更新清单 |
| `GET /android-update.json` | Android 客户端手动更新清单 |
| `POST /api/v1/agent/enroll` | 注册/续期，远程注册需 `X-Enrollment-Key` |
| `POST /api/v1/agent/pair` | Agent 生成配对码 |
| `GET /api/v1/agent/pair/status` | 查询配对状态，需 Agent token |
| `GET /api/v1/agent/clients` | 列出已授权浏览器，需 Agent token |
| `DELETE /api/v1/agent/clients/{clientId}` | 删除浏览器授权，需 Agent token |
| `GET /api/v1/agent/ws` | Agent WebSocket |
| `POST /api/v1/client/claim` | 浏览器认领配对码 |
| `POST /api/v1/client/profile` | 浏览器凭自身 token 更新设备名称和类型 |
| `POST /api/v1/client/revoke` | 撤销当前浏览器 token 并断开连接 |
| `GET /api/v1/client/ws` | 浏览器 WebSocket |
| `GET /api/v1/agents` | 返回浏览器已授权的设备 |

更新服务器只发布更新清单。客户端按清单中的 `downloadUrl` 从外部存储下载 Windows ZIP 或 Android APK。项目的发布脚本使用 Cloudreve，自托管时也可使用 OSS、EOS 等对象存储。详见 [客户端更新与发布](docs/updates.md)。

普通请求通过 `Authorization: Bearer <token>` 认证。浏览器 WebSocket 使用 `codexrm` 和 `codexrm.token.<token>` 子协议传递凭据，避免 token 出现在 URL 及普通访问日志中。中继的 `message` 包含 Codex JSON-RPC；`codexrm/info` 是 Agent 提供的运行状态接口。请求 ID 在 Agent 内映射，支持多个浏览器各自从相同 ID 开始发送请求。

Agent 还提供 `codexrm/workspace/list`、`codexrm/workspace/add`、`codexrm/workspace/remove` 和 `codexrm/fs/list` RPC，经过已有的设备配对鉴权。添加/移除及浏览使用 `{ path }` 参数；浏览可加 `showHidden`。会话筛选使用 Codex 原生 `thread/list` 的 `cwd` 参数，在分页前按目录筛选。

## 贡献与安全

欢迎通过 Issue 和 Pull Request 参与，请先阅读[贡献指南](CONTRIBUTING.md)和[行为准则](CODE_OF_CONDUCT.md)。安全漏洞请按照[安全政策](SECURITY.md)私密报告，不要公开披露凭据或未修复的漏洞细节。

## 许可证

本项目以 [MIT License](LICENSE) 开源。Codex、OpenAI 及其他第三方名称和标识归其各自所有者所有；本项目不提供或许可第三方 Codex 客户端及其源码。
