# Linux Agent · OneBot 11

Linux Agent 可以直接作为 OneBot 11 应用端，把 QQ 私聊和群聊接入本机 Codex。适配器在 Agent 进程内运行，不需要把 OneBot 事件转发到 CodexRM 中继，也不需要额外运行一个机器人客户端。NapCat、LLOneBot、Lagrange.OneBot 等实现只需按 OneBot 11 标准连接 Agent。

当前协议目标是 **OneBot 11**，不是 OneBot 12。传输层的 `Call` 支持任意 OneBot 动作名及扩展动作；聊天流程使用 `send_msg`、`get_msg` 和 `get_image`。

## 配置入口

在 Agent 设置页打开「QQ 机器人 → OneBot 11」。无桌面主机继续使用 `--loopback-settings` 和 SSH 端口转发。保存配置会热重载适配器，不重建 CodexRM 中继连接。

配置保存在 `connection.json` 同目录的 `onebot.json`，权限为 `0600`；会话绑定保存在 `onebot-sessions.json`。Access Token 不会通过设置 API 或网页回显。清除 Token 需要显式勾选「清除已保存的 Access Token」。

### 反向 WebSocket（推荐）

Agent 默认监听 `127.0.0.1:6701`，兼容以下路径：

- `/`、`/onebot`、`/onebot/`
- `/ws`、`/ws/`

在 NapCat 等协议端新建 WebSocket 客户端，URL 填 `ws://<Agent 地址>:6701/ws`，Access Token 与 Agent 设置保持一致。OneBot 与 Agent 同机时使用 `127.0.0.1`；Docker 中的协议端应使用宿主机可达地址，不要把宿主回环地址当成容器回环地址。

反向监听也接受 HTTP POST 事件，便于只支持事件上报的实现。

### 正向 WebSocket

通信方式选择「正向 WebSocket」，服务地址填协议端提供的完整地址，例如 `ws://127.0.0.1:3001` 或 `wss://onebot.example/ws`。Agent 负责重连、API echo 关联和请求并发。

### HTTP API + HTTP POST

通信方式选择「HTTP API + HTTP POST」：

- OneBot 服务地址填 API 根地址，例如 `http://127.0.0.1:3000`；动作请求使用 `POST <根地址>/<action>`。
- Agent 事件监听地址填 `127.0.0.1:6701`。
- OneBot 的 HTTP POST 上报地址填 `http://<Agent 地址>:6701/onebot/`。

HTTP 和 WebSocket 都使用 `Authorization: Bearer <Access Token>`。启用反向 WebSocket 或 HTTP 模式时，只要监听地址不是回环地址，保存和启动都会强制要求非空 Access Token；仅用防火墙代替应用鉴权不会被接受。反向/HTTP 监听从非回环地址开放时，请使用强随机 Token，并用防火墙限制来源；不要把监听直接暴露到公网。启用工作区工具或管理员权限时，即使监听回环地址也必须设置 Token，防止本机网页或其他本地进程伪造授权 QQ 事件。仅文本的回环监听可留空，但服务端会拒绝不可信浏览器 Origin；HTTP 事件必须使用 `application/json` Content-Type。

## 私聊与群聊权限

未配置的 QQ 和群一律忽略，不返回错误，避免向陌生会话暴露机器人能力。

| 模式 | 私聊 | 群聊 | 权限边界 |
| --- | --- | --- | --- |
| 仅 AI 聊天 | 支持 | 支持 | 独立 text-only Codex，强制只读、禁止工具和网络搜索 |
| 工作区工具 | 支持 | 支持 | `workspace-write`、从不审批、禁用网络搜索；工作区必须是 Agent 已登记目录 |
| 管理员 | 支持 | 不支持 | `danger-full-access`；授权直接保存在本机 Agent 配置中，只给可信 QQ |

工作区、模型和补充提示词可按私聊或群配置。留空工作区使用 Agent 默认目录。管理员模式不等同于群会话管理员：后者只能停止或重置共享群会话。

群聊支持：

- `仅 @机器人`：默认模式；从数组消息或 CQ 码识别 `at`，移除对应 @ 后交给 Codex。
- `全部消息`：群里的所有支持消息都可能触发任务。
- `全群共享`：全群共用一条 Codex 会话，输入带群号和发送者 QQ；停止/重置仅限配置的会话管理员。
- `成员独立`：每个群成员拥有独立会话。
- `允许成员`：留空允许全群，填写后只响应名单内成员。

机器人自己发出的 `message_sent` 和 `self_id == user_id` 的消息不会再次进入 Codex，避免自回复循环。

## 消息与图片

- 接收 OneBot 11 数组消息和 CQ 字符串，保留未知消息段；文字、@、回复和图片进入聊天流程。
- 回复消息会尝试调用 `get_msg`，把被回复文字作为上下文；接口不可用时继续处理当前消息。返回消息必须匹配当前群或私聊对端，跨会话引用会被忽略。合并转发默认只保留占位；显式开启后才调用 `get_forward_msg`，最多提取 40 条、6000 字，因为 OneBot 11 无法验证转发 ID 属于当前会话。
- 语音、视频、文件、卡片和其他消息段以明确的类型占位进入上下文，不会声称已经转写、提取画面或上传附件。
- 好友申请和群邀请默认忽略。开启自动接受后，也只接受已授权私聊 QQ 的好友申请或已授权群的入群邀请。
- 每条消息最多 4 张图片，每张最大 5 MiB，支持 PNG、JPEG、GIF、WebP。支持 data URL、`base64://`、本机绝对路径、`get_image` 和 HTTP(S) URL。
- 公网图片下载默认开启，限制响应大小、重定向和实际图片格式。回环、私有、链路本地地址默认阻止；仅可信环境可单独允许回环/私有地址，链路本地地址始终阻止。
- 本机文件读取默认关闭，需要显式开启。消息段里的 `/absolute/path` 和 `file://` 不会直接作为读取授权；只有 `get_image` 返回的本机绝对路径可被读取，并会解析符号链接、校验普通文件和大小。OneBot 在容器中运行时，需要让 Agent 能访问 `get_image` 返回的相同路径，或改用可访问的图片 URL。
- Codex 文本回复按配置拆成多条消息，默认 1800 字，优先在换行处拆分。群里的首条回复携带 OneBot `reply` 段；协议端拒绝回复段时自动退回纯文本。
- OneBot 11 没有统一的 Markdown 编辑/流式更新接口，因此回复在任务完成后发送，不会为每个 delta 新建消息。

## 聊天命令

命令同时支持常用中文别名：

| 命令 | 中文别名 | 用途 |
| --- | --- | --- |
| `/help` | `#帮助` | 查看帮助 |
| `/new`、`/reset` | `#新会话`、`#重置` | 清除当前 OneBot 与 Codex 的会话绑定，不删除 Codex 历史 |
| `/stop`、`/interrupt` | `#停止` | 取消正在启动的任务或中断当前 turn |
| `/status` | `#状态` | 查看线程、活动任务、权限和工作区 |
| `/id`、`/whoami` | — | 查看 QQ、OneBot 会话键和权限模式 |

一个会话同时只运行一个 turn；任务运行时收到的新聊天内容会收到忙碌提示，不会静默排队或重复执行。

## 会话恢复与重载

首次消息创建 Codex thread，后续消息复用。绑定包含会话所有者、权限模式、模型/提示词配置和群策略版本。重启 Agent 后从本机文件恢复绑定，首次使用时恢复 thread；权限或群策略变化后不会沿用旧的高权限会话，而是创建新绑定。

「保存并应用」只重启 OneBot 适配器。进行中的任务会被取消并中断；曾活动的线程不会跨热重载复用，避免旧通知串入新任务，下一次消息会创建新会话。空闲持久会话绑定保留。中继重连或 Codex 重启也会关闭旧的 OneBot 传输和活动任务。

## 验证

开发验证使用本机模拟 OneBot WebSocket/HTTP 端和 Codex mock，不需要 QQ 账号：

```sh
gofmt -w internal/onebot internal/agent/onebot*.go internal/agenthost/onebot.go
go test ./internal/onebot ./internal/agent ./internal/agenthost
GOOS=linux GOARCH=amd64 CGO_ENABLED=0 go build ./cmd/codexrm-agent
node --check internal/agenthost/web/onebot.js
```

真实 NapCat/LLOneBot 的账号登录、群成员权限、图片下载和消息长度限制仍需用测试 QQ 与测试群做联机验收。代码级测试不能替代这些真实平台检查。
