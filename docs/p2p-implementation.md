# Agent 与 Android 的 UDP 直连

Android 进入前台后，先用现有 RM WebSocket 建立业务连接。启用了 P2P 的 RM 会通知 Android 后台协商。RM 只在当前配对的两个连接间转发建联信令，并按配对状态续租。Agent 使用同一 UDP socket 完成候选收集、探测和 QUIC；Android 从一个 socket 开始，必要时逐步增加到 256 个，成功后仅保留命中的 socket。握手使用 TLS 1.3 和双向证书指纹校验。

### 候选收集与探测顺序

- STUN 和可选的 RM UDP 地址验证使用同一个 UDP socket 并行进行。每个目标独立匹配事务及响应来源；一个目标不响应不会阻止收集其他目标的回复。新端点可为多条候选分别附上回执。只支持单验证器的端点仍使用第一条候选的回执。
- 优先尝试可用的局域网候选，然后尝试公网候选的原始端口。任一侧映射不可预测时，原始端口阶段为 120 ms；双方都在不同 STUN IPv4 上看到同一映射时为 800 ms；对端映射呈固定端口步长时为 200 ms；其余为 250 ms。观测不用于推断入站过滤类型，不影响候选验证，也不禁止后续探测。旧版本不传提示时按未知处理。
- 不同 STUN IPv4 返回同一公网地址且端口差为固定的 1–32 时，信令带上 `linear` 和步长。原始端口阶段结束后，双方先探测已公布端口的 ±1…8 倍步长，再由 Agent 继续 ±64 邻域和随机端口。步长缺失、超过 32，或各段步长不一致，则按不可预测处理。端口差不外推到 1–1023，也不扩大到未获准的 IP。
- 对端映射不可预测时，Agent 每轮最多探测 2048 个不同的公网 IP:端口，每 5 ms 最多发一个公网探测请求，Android 再按需打开额外 socket。对端是固定步长且本端映射没有变化时，Android 不再为了打洞加开 socket。某个候选没确认时，其他候选仍可继续；双方只在认证探测和 QUIC 握手成功后切换业务。
- 双方映射均不可预测时，新版 Agent、Android 和 RM 协商启用限时公网探测。先尝试同网段和原始候选，再对获准的对端 IPv4 尝试邻近及随机端口。该阶段最长 8 秒，Agent 最多探测 100 个不同的公网候选，Android 最多使用 32 个 UDP socket。旧版组合仍只尝试最多 3 秒的同网段候选。超时后 Android 继续使用 RM，中继期间在换网、重新连接 RM 或收到 Agent 上线通知后再检测。
- 这套策略按映射是否稳定、是否按固定步长变化来选择探测顺序，不是 WebRTC ICE，也不向无关公网地址做防火墙探测。直连失败仍走 RM WebSocket，不需要 TURN。本文的历史真机数据未用固定步长策略重测。

切换完成后，新 RPC 走 QUIC，已经发送的 RPC 继续接收原路径的响应。直连断开时，新请求恢复走 WebSocket，应用重新同步状态；已经发出而未收到响应的操作不会自动重发。撤销配对或替换 WebSocket 会关闭相应的直连；失去 RM 时直连由短租约限制，租约为 12 秒。Android 换网或退到后台时关闭旧连接。Web 客户端继续走原中继。

## Android 文件卡片与文件管理

- 直连后，已完成的 Codex 文件变更只为新增或修改的 Office 文档（含 PDF）、图片、音频、视频和 APK 显示下载卡片。Codex 在回复中明确链接到工作区的同类文件（如命令构建的 APK），也会在 Agent 确认文件存在后显示卡片。卡片下载的是当前工作区文件，不是历史版本；未被记录或链接的产物可在文件管理里找。
- 长按侧栏工作区文件夹，或长按会话底部的当前目录，进入双栏文件管理。左侧浏览电脑工作区，右侧通过 Android 系统选择器授权并浏览手机目录。点击左侧普通文件可下载到右侧当前目录，聊天卡片则会在下载完成后打开系统保存窗口。当前不支持向电脑上传、远程改名或删除，单文件下载上限 512 MB。
- 列目录、查询文件信息、读取分块均只经当前 P2P 会话；RM 拒绝文件 RPC，不提供存储或中继。直连断开不会自动重试传输。已配对客户端可浏览 Agent 默认工作区、已登记的工作区或会话对应目录中的普通文件，请勿把存放敏感资料的宽泛目录设成工作区。

## 启用

Android 的 QUIC 原生库仅构建并打包 `arm64-v8a`，正式包和 QA 包使用同一 ABI。构建要求见 [Android 开发文档](../android/README.md#构建)。

1. 更新 RM、Agent 和 Android 到包含此功能的构建。
2. 局域网直连默认开启，无需配置 STUN。若还需要跨网打洞，为 RM 设置 `CODEXRM_P2P_STUN`，或传 `--p2p-stun`，值为最多四个逗号分隔的 `IPv4:UDP端口`。建议配置两个不同 IPv4 的 STUN 服务，并从 Windows、手机蜂窝网分别验证可达。留空只关闭公网打洞。此版本不需要 TURN。
3. RM 前置 TLS 反代时，后端应只监听 loopback，反代必须覆盖来自客户端的 `X-Real-IP` 和 `X-Forwarded-Proto`；满足这些条件后给 RM 加 `--p2p-trust-loopback-proxy`。Nginx 可用 `proxy_set_header X-Real-IP $remote_addr;` 和 `proxy_set_header X-Forwarded-Proto $scheme;`。

当前直连只支持 IPv4。公网候选默认须与信令来源 IPv4 一致。停用全部直连时，为 RM 传 `--p2p-lan=false`，清空 STUN 和下述验证配置并重启。

局域网仍用 RM 配对、交换候选和续租。RM 只向已配对的双方转发局域网候选，并要求双方的信令来源为同一公网 IPv4，或同在一个私有 IPv4 /24。对于私有来源，局域网候选 IP 还必须与该端信令来源一致。双方只探测各自直连接口网段上的对端地址，Android 使用当前 Wi-Fi 或以太网的网段，不在 VPN 上尝试局域网直连。局域网优先尝试，经双向认证后用 QUIC 传输业务；不可用时继续尝试已配置的公网直连，否则走 RM 中继。首页闪电图标可查看当前直连的往返延迟。锁屏时无需显示或唤醒 App。

2026-11-18 用 OPPO PFFM20 和 Windows 实测同一 Wi-Fi 下的局域网直连。隔离 RM 未配置 STUN 或公网 UDP 验证，信令也经 Wi-Fi，未使用 ADB reverse。两批各 20 次连接中，分别有 19 次和 20 次通过局域网 QUIC 完成真实 Codex app-server 的 `thread/list`；两批合计 40/40 次在协商前成功通过 WebSocket 完成 `codexrm/info`。成功轮次从连接到完成请求的中位数为 320 ms，范围为 256–412 ms。原始记录位于 `.work/p2p-split-egress/lan-qa/wifi-lan-20.txt` 和 `wifi-lan-repeat-20.txt`。

同一台手机在正常解锁、未授予锁屏显示权限的状态下，点击首页的局域网直连闪电图标，弹窗显示「往返延迟：48 ms」。此数值来自已建立的 QUIC 连接，与上面的建连耗时分开统计。测试包不再请求锁屏显示或自动亮屏；运行真机测试前需先正常解锁。

对于 TCP 和 UDP 使用不同公网 IPv4 的网络，可在 RM 上额外启用 UDP 地址验证。保留 STUN 配置，并同时设置下列两个变量：

| 变量 | 用途 |
| --- | --- |
| `CODEXRM_P2P_VERIFY_LISTEN` | RM 本机监听的 IPv4:UDP 端口，例如 `0.0.0.0:3478`。 |
| `CODEXRM_P2P_VERIFY_PUBLIC` | 手机和 Agent 都能直接访问的公网 IPv4:UDP 端口，必须指向上述监听器。 |
| `CODEXRM_P2P_VERIFY_MORE` | 可选。以逗号分隔的额外自管公网 IPv4:UDP 端点，最多三个。 |
| `CODEXRM_P2P_VERIFY_KEY_FILE` | 启用额外端点时必填。文件中保存随机生成的 32 字节密钥，以 64 个十六进制字符表示。只向 RM 和受信的验证器分发。 |

云安全组和主机防火墙都需允许该 UDP 端口；Nginx 的 HTTPS/WebSocket 反代不负责转发这些 UDP 报文。RM 为两端分别签发短期票据；端点用参与打洞的 UDP socket 取得回执，RM 校验回执与会话、IPv4 和端口的绑定后才转发候选。验证失败时仍尝试原有的同 IP 候选，无候选则走中继。UDP 验证确认手机或 Agent 发往各验证器时的映射。运营商发往对端时可能使用另一个公网 IP。

额外端点运行 `codexrm-server --p2p-verify-only --p2p-verify-listen 0.0.0.0:3478 --p2p-verify-key-file /path/to/verifier.key`。RM 和各端点使用同一份密钥，文件权限限制到运行账户。手机与 Agent 使用参与打洞的 socket 并行取回执。RM 逐条核对端点角色的票据和实际候选 IP、端口，然后只把候选转发给对端。单验证器的部署和旧客户端继续使用原流程。

2026-11-18，OPPO PFFM20 在中国移动 `cmnet`、关闭 Wi-Fi 和 USB 转发时通过公网 HTTPS/WSS 测试。单个自管验证器的 5 轮和两个自管验证器的 4 轮均完成中继请求，公网 QUIC 分别为 0/5 和 0/4。单独的隔离对照只放行本机当时测得的两组额外 STUN 出口地址，公网 QUIC 为 2/3。两个自管验证器观测到的公网 IPv4 与这两组 STUN 出口不同。多验证器代码保留逐地址校验，尚未发布到正式 RM。

2026-11-18 的隔离集成测试覆盖不同信令与 UDP 地址下的真实 QUIC 业务和中继回退，并确认改端口、交换两端票据或不提供回执都会被拒绝。生产尚未启用 UDP 地址验证；下文的 realme 对照不计为兼容版验收。

### realme RMX5062 / 中国移动 5G 对照

2026-11-18，使用 Android `arm64-v8a` QA APK、本机已安装的 Agent 0.6.14 与公网生产 RM，手机默认网络为移动 `cmnet`。20 轮均通过 WebSocket 完成 `codexrm/info`，0 轮在 20 秒内通过 QUIC 完成 `thread/list`。原始汇总为 `.work/p2p-split-egress/realme-production-control.txt`。正式 App 继续保留，临时 QA 配对已撤销。

同一个手机 UDP socket 对两个公共 STUN 目标测得不同的公网 IPv4，且都不同于先前观测的生产信令出口。几个自管入口的 `3478/udp` 未收到手机探测包；两台主机的 `443/udp` 则抓到了手机的 STUN 请求，其中一台也收到了验证格式的 58 字节探测包。这只证明该端口能收到请求，尚未验证有效票据的回执或候选是否能通过 RM。由于测试环境拦截启动隔离信令代理，兼容版未进入真机验收，直连率仍未知。隔离服务和临时规则已撤销。

## 2026-11-18 验证

前台运行的隔离 Android QA APK 使用生产 Kotlin、JNI 和 Go 核心。OPPO PFFM20 一直走蜂窝默认网络；Windows 上启动独立 RM、Agent 和实际 Codex app-server。信令通过本机测试反代与 ADB reverse；测试反代用同一网络环境中经 STUN 测得的候选 IPv4 模拟公网反代提供的源地址。UDP 探测、QUIC 和直连 RPC 走实际公网 IPv4，不走 USB 或中继。

- 20/20 次 `codexrm/info` 在协商前经 WebSocket 成功。
- 20/20 次真实 `thread/list` 在 20 秒内经 QUIC 成功；端到端中位数 0.890 秒，范围 0.787–2.115 秒。达成这一台 Symmetric NAT 手机配这一台 Port Restricted Cone NAT 电脑的 >50% 目标。
- 清空 STUN 配置后，独立的 3 次蜂窝测试全部经 WebSocket 成功，未接通直连。Go 集成测试另覆盖从已激活的直连切回中继、配对撤销和候选约束。

隔离测试的原始输出在 `.work/p2p-app-qa/cellular-final-20.txt` 和 `.work/p2p-app-qa/cellular-relay-3.txt`。此前 [194/200 的网络 PoC](p2p-validation-2026-11-18.md) 单独计数。

同日发布后，用同一台手机、同一蜂窝网络和本机安装的 Agent 0.6.13 再跑 20 回合。此次信令走公网生产 RM，未使用 ADB reverse。20/20 次 `codexrm/info` 经 WebSocket 成功，20/20 次 `thread/list` 在 20 秒内经 QUIC 成功，端到端中位数 1.691 秒，范围 1.305–2.714 秒。QA APK 与手机正式应用隔离，测试后撤销临时配对。原始结果在 `.work/p2p-production-release/cellular-rm-20.txt`。该结果只覆盖一台手机、一个运营商与一个网络时段；双 Port Restricted Cone 真机组合和跨网络长时间运行尚未验收。

验证可用 `-Pcodexrm.p2pQa` 构建 `assembleDebug assembleDebugAndroidTest`。安装两只 QA APK 后，使用有一次性配对码的隔离 RM 运行 `adb shell am instrument -w -e rm <RM地址> -e pair <配对码> -e rounds 20 com.codexrm.mobile.p2pqa.test/com.codexrm.mobile.DirectInstrumentation`。
