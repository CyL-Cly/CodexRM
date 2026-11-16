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

## PWA 与加载缓存

页面提供 Web App Manifest、桌面/移动端安装图标和 Service Worker，可通过浏览器“安装应用”使用独立窗口。首次在线访问后会预缓存应用壳，离线仍可打开界面和恢复本地草稿；远程设备连接、发送任务和同步会话仍需网络。

静态加载使用三层 Cache Storage：版本化应用壳采用缓存优先，页面采用网络优先并保留离线副本，Manifest 与图标采用运行时缓存和 stale-while-revalidate。版本化 JS/CSS 同时使用浏览器一年 immutable HTTP 缓存；HTML 和 Service Worker 每次检查更新。旧版本缓存会在升级后自动清理。`/api/`、带 Authorization 的请求、非 GET、跨域请求及 WebSocket 不经过 Service Worker 缓存，会话和配对响应不会写入 Cache Storage。

## 快速开始

需要 Go 1.22+。页面使用原生 HTML / CSS / JavaScript，无需 npm 构建；运行前端测试需要 Node.js 22+。

```powershell
git clone https://github.com/codexrm/codexrm.git
cd codexrm
.\scripts\build.ps1
```

Windows 输出为 `bin/codexrm-server.exe`、`bin/codexrm-agent.exe`。Linux amd64 构建使用 `.\scripts\build.ps1 -Linux`，输出带 `-linux-amd64` 后缀。页面已内嵌至服务端二进制，不依赖额外的静态文件目录。

启动本地连接器：

```powershell
$env:CODEXRM_ENROLL_KEY = '<服务器注册密钥>'
.\scripts\connect.ps1 -Server "https://your-server.example" -Workspace "D:\projects\my-project"
```

首次注册后，连接配置和凭据保存在被 Git 忽略的 `.state/` 中。连接器会显示一次性配对码，在网页中点击“连接设备”输入即可。再次生成配对码：

```powershell
.\scripts\connect.ps1 -Server "https://your-server.example" -Pair
```

同一服务器与同一 Windows 用户默认复用 Agent 身份。不要同时启动多个使用同一数据目录的连接器；多实例可用 `--data` 指定不同目录。

## 其他 Windows 电脑安装

使用安装包中的 `install.cmd`，自动安装到当前用户的 `%LOCALAPPDATA%\CodexRM`，创建启动和生成配对码的桌面快捷方式，随后显示配对码。目标电脑只需已经安装并登录或配置好 Codex 客户端；连接脚本自动查找客户端自带的 CLI，不需要另外安装 CLI、Go 或 npm。

```powershell
# 生成通用安装包；安装时通过 CODEXRM_ENROLL_KEY 提供注册密钥
.\scripts\package-client.ps1 -Server "https://your-server.example"
# 给自己的其他电脑使用，包含当前服务的注册配置；不要公开分发此包
.\scripts\package-client.ps1 -IncludeEnrollmentKey
```

安装包输出到 `dist`，完整解压后双击 `install.cmd`。也可用 `install.cmd -Workspace "D:\projects\my-project"` 指定初始工作区。详见 [Windows 安装说明](docs/windows-install.md)。

## 多工作区

一个 Agent 可以同时管理多个文件夹，`--cwd` / `-Workspace` 只指定初始默认目录，无需为每个项目启动连接器。

1. 点击侧栏“工作区”旁的 **+**，或输入框下方的文件夹按钮。
2. 在已连接的电脑上浏览磁盘、主目录及子文件夹，也可以输入绝对路径后点击“前往”。点击“添加此文件夹”将其保存为工作区。
3. 侧栏自动加载全部未归档会话，按原工作目录分组；文件夹及组内会话均按最近更新时间排序，无需逐个手动添加。页面可见时每 15 秒同步其他客户端的新会话。点击文件夹名称展开或收起。
4. 点击文件夹旁的 **+** 或输入框下方的目录下拉框，在所选目录新建会话；已有会话保持自己的工作目录。已固定工作区旁的 **×** 取消固定，含历史会话的文件夹仍会自动显示。

固定的工作区存放在 Agent 数据目录的 `workspaces.json`，同一设备的配对浏览器共享该列表；当前选择及文件夹折叠状态按设备保存在浏览器中，刷新及切换设备后恢复。目录浏览读取的是 Agent 所在电脑，网页服务器不需要访问项目文件。

权限默认采用“完整访问 + 从不请求审批”，设置会在新建会话及下一次发送时传入 Codex。若曾手动保存其他权限设置，仍使用该设置。多个审批可以分别处理或批量允许/拒绝；其他待处理问题的输入会保留，响应经连接器确认后才移除。

桌面客户端占用的会话以只读快照展示，每 3 秒同步已保存消息。独立 app-server 可能将未加载的进行中轮次标为 `interrupted`；此时会结合原始生命周期记录判断，只有明确的停止事件才显示“任务已停止”。无法确认的状态显示为等待原客户端更新。

## 供应商与模型

Agent 默认只读本机 `~/.cc-switch/cc-switch.db` 中的 Codex 供应商，在输入框下方按供应商分组显示模型。每组分别请求自己的 `/models` 接口，并合并 CCSwitch 保存的模型与思考强度；在线查询失败时继续显示已保存的模型。官方组使用 CCSwitch 的 `codex-official-models.json` 缓存，实际对话仍需本机可用的官方登录。

点击模型旁的刷新按钮重新读取供应商和模型。不同工作区会分别记住模型及思考强度，选择保存在此浏览器中，并按设备、目录区分。同一供应商可以在已有会话中换模型；切换供应商会回到新会话，原会话保留在历史列表中。

每个会话向 Codex app-server 注入独立的供应商标识和连接设置，恢复历史时根据会话保存的标识读取原供应商。Agent 数据目录的 `thread-models.json` 仅保存会话的供应商标识、模型与思考强度；旧会话会从本地历史恢复最后使用的模型。密钥仅在本机 Agent 与 app-server 之间传递，网页只接收供应商和模型元数据，不修改 CCSwitch 当前选择、数据库或全局 Codex 配置。CCSwitch 中移除供应商后，对应会话会明确提示配置缺失。

如数据库不在默认位置，可设置 `CODEXRM_CCSWITCH_DB`，或传入 `--ccswitch-db <绝对路径>`。未安装 CCSwitch、模拟模式及连接外部 app-server 时，仍可使用当前 Codex 配置的模型。

## 构建与测试

需要 Go 1.22+。页面使用原生 HTML / CSS / JavaScript，无需 npm 构建。真实模式使用已安装、已完成登录或 API 配置的 Codex 客户端自带 CLI；直接运行 Agent 时也支持用 `--codex` 指定 CLI 路径。

```powershell
.\scripts\build.ps1
.\scripts\build.ps1 -Linux
go test ./...
go test -race ./...
node --test web/app.test.mjs web/sw.test.mjs
node --check web/app.js
node --check web/pwa.js
node --check web/sw.js
```

构建产物输出到被 Git 忽略的 `bin/`。`codex/` 上游参考源码、`ChatGPT*` 设计参考和私人部署记录不参与构建，也不包含在公开仓库中。

## 本地演示

```powershell
# 终端 1：服务端，默认使用内嵌页面
.\bin\codexrm-server.exe --addr 127.0.0.1:8080 --data .state/demo-server

# 终端 2：模拟设备，终端会打印配对码
.\bin\codexrm-agent.exe --server http://127.0.0.1:8080 --mock --data .state/demo-agent
```

访问 http://127.0.0.1:8080 并输入配对码。模拟模式支持流式回复、历史、重命名、归档、停止；发送含“审批”的任务可测试审批回传。模拟会话只保存在内存中，不调用模型或执行命令。也可运行 `scripts/dev-mock.ps1` 一键启动。

## 连接真实 Codex

默认由 Agent 启动 `codex app-server --listen stdio://`，继承当前用户的 Codex 登录与配置：

```powershell
.\bin\codexrm-agent.exe --server https://your-server.example --cwd D:\projects\my-project
```

支持 `--codex <可执行文件路径>`。Agent 运行独立 app-server，可列出、恢复同一 Codex 用户目录中可见的历史会话。另一个实例仍持有写入锁的会话以只读历史展示，结束原会话后可重新打开继续。

也可连接已有 app-server：

```powershell
codex app-server --listen ws://127.0.0.1:4500
.\bin\codexrm-agent.exe --server https://your-server.example --appserver ws://127.0.0.1:4500
```

若该 WebSocket 要求 Bearer token，设置 `CODEXRM_APPSERVER_TOKEN` 或传入 `--appserver-token`。浏览器刷新会保留配对；重新进入会话时从 Codex 同步历史。连接中断时不重放用户任务，避免重复执行。

## 自行部署

```bash
# 在 Linux 上构建，或上传 Windows 交叉编译的 Linux 二进制
go build -trimpath -ldflags '-s -w' -o codexrm-server ./cmd/codexrm-server
sudo bash scripts/install-server.sh ./codexrm-server ./deploy/codexrm.service
sudo systemctl status codexrm
sudo journalctl -u codexrm -n 50 --no-pager
```

安装脚本生成 `/opt/codexrm/server.env`，权限为 `0600`，里面的 `CODEXRM_ENROLL_KEY` 用于注册设备。将相同值设置到本地 Agent 的环境变量：

```powershell
$env:CODEXRM_ENROLL_KEY = '<服务器注册密钥>'
.\bin\codexrm-agent.exe --server https://your-server.example --cwd D:\projects\my-project
```

将 `deploy/nginx-location.conf` 中的代理规则合入已有 TLS 站点。服务进程使用专用 `codexrm` 用户，状态目录为 `/opt/codexrm/data`。如需直接监听公网端口，自行设置 `--addr :18080`；已部署实例通过 HTTPS 域名访问。

注册密钥仅用于 Agent 注册；浏览器使用 10 分钟有效的一次性配对码换取专属 token。设备信息 API、WebSocket、解除配对均校验凭据；支持 Origin 检查、设备间路由隔离和凭据到期。默认无注册密钥时仅接受直接来自 loopback 的注册，反向代理场景需配置注册密钥。配对浏览器可以操作对应电脑上的 Codex，权限由新会话的 sandbox 与审批设置控制。

## 结构与接口

```text
cmd/codexrm-server/   HTTP / WebSocket 服务入口
cmd/codexrm-agent/    本地连接器入口
internal/agent/      出站重连、RPC 并发与审批回传
internal/appserver/  Codex stdio / WS 客户端与模拟服务
internal/server/     配对、鉴权、中继与内嵌 UI
internal/store/      JSON 状态原子持久化
internal/protocol/   中继消息结构
web/                 响应式前端
deploy/              systemd 与 Nginx 示例
scripts/             构建、连接与安装脚本
```

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/health` | 健康检查 |
| `POST /api/v1/agent/enroll` | 注册/续期，远程注册需 `X-Enrollment-Key` |
| `POST /api/v1/agent/pair` | Agent 生成配对码 |
| `GET /api/v1/agent/pair/status` | 查询配对状态，需 Agent token |
| `GET /api/v1/agent/ws` | Agent WebSocket |
| `POST /api/v1/client/claim` | 浏览器认领配对码 |
| `POST /api/v1/client/revoke` | 撤销当前浏览器 token 并断开连接 |
| `GET /api/v1/client/ws` | 浏览器 WebSocket |
| `GET /api/v1/agents` | 返回浏览器已授权的设备 |

普通请求通过 `Authorization: Bearer <token>` 认证。浏览器 WebSocket 使用 `codexrm` 和 `codexrm.token.<token>` 子协议传递凭据，避免 token 出现在 URL 及普通访问日志中。中继的 `message` 包含 Codex JSON-RPC；`codexrm/info` 是 Agent 提供的运行状态接口。请求 ID 在 Agent 内映射，支持多个浏览器各自从相同 ID 开始发送请求。

Agent 还提供 `codexrm/workspace/list`、`codexrm/workspace/add`、`codexrm/workspace/remove` 和 `codexrm/fs/list` RPC，经过已有的设备配对鉴权。添加/移除及浏览使用 `{ path }` 参数；浏览可加 `showHidden`。会话筛选使用 Codex 原生 `thread/list` 的 `cwd` 参数，在分页前按目录筛选。

## 贡献与安全

欢迎通过 Issue 和 Pull Request 参与，请先阅读[贡献指南](CONTRIBUTING.md)和[行为准则](CODE_OF_CONDUCT.md)。安全漏洞请按照[安全政策](SECURITY.md)私密报告，不要公开披露凭据或未修复的漏洞细节。

## 许可证

本项目以 [MIT License](LICENSE) 开源。Codex、OpenAI 及其他第三方名称和标识归其各自所有者所有；本项目不提供或许可第三方 Codex 客户端及其源码。
