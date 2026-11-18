# Agent ↔ Android P2P 直连方案调研与实施计划

目标：在现有云端 WSS 中继之外，为桌面 agent 与安卓客户端建立 UDP 打洞直连通道，
**重点保证 Port Restricted Cone NAT（下称 PRC）下的高打洞成功率**；打洞失败时无缝回退现有中继。

日期：2026-11-18。状态：调研完成；验收指标与基础设施范围已确认（见 §2），待评审后进入 P0。

---

## 1. 现状（代码调查结论）

当前是纯 C/S 星型中继，无任何 P2P/NAT 穿透基础设施：

- Android → 服务器：`android/.../CodexClient.kt:50-56` 连 `/api/v1/client/ws`，token 走 WS 子协议；
  RPC 信封 `{type:"client_message", streamId, message:{id,method,params}}`（`:123-145`）。
- agent → 服务器：`internal/agent/agent.go:439-486` 出站拨号 `/api/v1/agent/ws`（gorilla/websocket）。
- 服务器路由：`internal/server/server.go:376-430`（认证/登记）、`:518-529`（按 agentID 转发）。
- 配对/认证：enroll → pair（一次性配对码，10 分钟）→ claim 换长期 token（`server.go:167-255`，`internal/store/store.go`）。
- 依赖：Go 侧仅 gorilla/websocket 等；Android 侧仅 OkHttp 4.12 + zxing + Markwon。无 STUN/ICE/QUIC/WebRTC。
- web/ PWA 与 Android 走同一 client WS 协议（P2P 首期不覆盖 web，浏览器只能 WebRTC，另议）。

**有利条件**：信令通道（服务器 WS + 长期 token）已经存在且双端常连，打洞所需的"协调服务器"角色零新增成本；
现有中继天然就是 TURN/DERP 式兜底，不需要另建。

## 2. 验收指标与基础设施范围（已确认）

### 2.1 验收指标

| 编号 | 场景 | 指标 | 目标 | 测量方法 |
|---|---|---|---|---|
| A1 | **PRC × PRC**（双方家用路由，核心场景） | UDP 直连成功率（≤10s 打洞窗口内） | **≥95%** | 实验室 iptables 模拟 PRC ≥100 次 + 真实环境 ≥3 种路由器/ISP 组合 × 各 ≥30 次 |
| A1b | 同上 | 直连建连耗时（信令就绪→QUIC 可用） | P50 ≤2s，P90 ≤5s | `p2p_result` 遥测统计 |
| A2 | **Symmetric 手机（蜂窝）× PRC 本机** | 直连成功率（≤30s 窗口，含生日悖论打洞） | **>50%** | ≥2 家运营商 SIM × 各 ≥30 次，跨时段采样；按运营商分列报告，汇总值达标 |
| A3 | **所有网络（含回退）** | 端到端可连接率（RPC 往返成功） | **≥99.5%** | 前提：RM 服务器可达且 agent 在线；遥测 + 周期探测任务统计 |
| A4 | 直连建立后 | 30 分钟空闲保活存活率 | ≥99% | 实验室（conntrack 超时调短）+ 真机实测 |

说明：
- A1/A2 的"成功"定义：QUIC 握手完成且首个 RPC 往返成功；失败样本必须带原因分类
  （NAT 判定、候选命中情况、超时阶段），落入 `p2p_result` 遥测。
- A2 按**跨运营商汇总**验收：个别运营商若整体丢弃入站 UDP，其样本计为直连失败（由 A3 兜底），
  分列数据用于定位，不单独豁免。
- A3 本质由现有 WSS 中继保证，指标用于防止路径管理改造引入回归（切换丢请求、双通道竞态等）。
- A1 的 95% 是提案值（PRC×PRC 理论可 100%，余量留给真实路由器怪癖）；如需更严可上调。

### 2.2 A2 可行性分析（Symmetric × PRC >50%）

这是"路径上恰好一个 hard NAT"场景，生日悖论打洞（Tailscale 已验证）适用：

- 手机侧（EDM）开 ~256 个 UDP socket 同时向本机公网 `ip:port` 发包 → 在 CGNAT 上开出 256 个未知端口；
- 本机侧（EIM，单 socket 源端口不变）向手机公网 IP 的随机端口探测；
- 命中概率：174 探 ≈50%，1024 探 ≈98%，2048 探 ≈99.9%；100 包/s 下约 2s 过半、20s 内近必中。
- **理论余量远超 50%**，但以下工程风险会侵蚀实际成功率，需逐一覆盖：
  1. **CGNAT 多公网 IP**：STUN 看到的 IP 未必是对本机方向的出口 IP。对策：向多个 STUN 端点
     （双端口 + 可选第二 IP/公共 STUN）探测，收集 IP 集合并对每个 IP 分摊探测预算。
  2. **端口分配非均匀**（集中分段/随机化）：对策：优先用 STUN 观测到的端口邻域加权采样，再退均匀随机。
  3. **运营商丢入站 UDP**：无解，计入失败，A3 兜底；分运营商报告暴露。
  4. **会话表/速率限制**：256 socket + 100–200 包/s 属温和负载，远低于家用/运营商设备会话上限；
     探测限速避免触发 IDS。
  5. **部分蜂窝 CGNAT 实为 EIM**：先做 NAT 自检，EIM 直接走标准打洞（更快），确认 EDM 才升级生日模式。
- 结论：**>50% 目标可达且有余量**；生日悖论打洞因此从原 P2 可选项**提为 P0 核心功能**
  （本机=探测侧、手机=多 socket 侧，两侧都在 Go P2P 栈内实现，经 gomobile 覆盖 Android）。

### 2.3 公网基础设施范围（已确认：沿用 RM 信令，可部署 STUN/TURN）

| 设施 | 决策 | 说明 |
|---|---|---|
| 信令 | **沿用现有 RM 服务器**（codexrm-server） | 复用 WS 通道与配对 token，新增 §4.3 的 5 个信令消息，零新增服务 |
| STUN | **部署在 RM 服务器**，双 UDP 端口（3478/3479） | pion/stun 与 codexrm-server 同进程；双端口用于 EIM/EDM 映射行为判定；云安全组需放行 UDP（nginx 只反代 TCP，不经手） |
| TURN | **可部署但首期不建** | 现有 WSS 中继已承担 DERP/TURN 式兜底角色，另建 coturn 属重复建设；仅当后续 web 端引入标准 ICE/WebRTC 时再评估 |
| 第二公网 IP（可选） | 视 A2 实测决定 | 若 CGNAT 多 IP 问题显著，在 RM 服务器加绑一个 IP 做 STUN 交叉探测，成本极低 |

## 3. 核心调研结论

### 3.1 PRC NAT 的本质：它是"容易打洞"的那一类

按 RFC 4787 的两维分类（映射行为 × 过滤行为）：

| 旧称 | 映射行为 | 过滤行为 | 打洞难度 |
|---|---|---|---|
| Full Cone | 端点无关 EIM | 端点无关 | 极易 |
| Restricted Cone | EIM | 依赖目标 IP | 易 |
| **Port Restricted Cone** | **EIM** | **依赖目标 IP+端口** | **易（标准双向打洞即可）** |
| Symmetric | 端点相关 EDM | 依赖目标 IP+端口 | 难 |

关键认知（Tailscale 官方 NAT traversal 文章的核心论点）：
- PRC 的映射是**端点无关**的：同一本地 socket 无论发给 STUN 服务器还是对端，公网映射 `ip:port` 不变。
  因此 STUN 探到的地址对对端**同样有效**——这是 PRC 打洞能成立的根基。
- PRC 的过滤只要求"我先向对方的 `ip:port` 发过包"。**双向同时发包**后，双方 NAT 各自放行对方，
  与防火墙层数无关（多层 NAT/CGNAT 叠加也适用，只要最外层是 EIM）。
- 结论：**PRC × PRC（家用路由器最常见组合）理论上可 100% 打通**，工程上失败几乎都源于实现细节
  （socket 不一致、单向发包、映射老化、入站 UDP 被运营商丢弃），而不是 NAT 类型本身。
  真正难的是 EDM（对称）NAT，那才需要端口预测/生日悖论等概率手段。
- 参考量级：Tailscale 公开数据约 90–94% 连接可直连，其余走 DERP 中继兜底。

### 3.2 保证 PRC 高成功率的工程要点（必须全部落实）

1. **单 socket 复用**：STUN 探测、打洞探测、QUIC 业务数据必须共用同一个 UDP socket（同一本地端口）。
   每个 socket 在 NAT 上是独立映射，分开用等于拿到一个对对端无效的地址。
   quic-go 支持在既有 `net.PacketConn` 上 Dial/Listen，天然满足。
2. **固定本地端口 + SO_REUSEADDR**：agent 常驻绑定固定端口（可配置），重启后映射可复用；
   端口被占时再退随机端口并重新 STUN。
3. **双向同时打洞**：服务器通过既有 WS 下发同步"开始"指令（两端时钟偏差 <300ms 即可，无需 NTP），
   双方以 100–200ms 间隔持续互发小探测包（<200B，避免分片被过滤），窗口 5–10s。
   首包被对端 NAT 丢弃是**预期行为**——它的作用是在本端 NAT 上开过滤孔，后续包即可到达。
4. **多候选并行**（ICE 思想，不必实现完整 ICE 协议栈）：
   - LAN 候选：双方局域网地址（同 WiFi 场景直接命中，也解决 NAT hairpinning 不支持的问题）；
   - STUN 公网候选（srflx）；
   - IPv6 候选（双栈网络下往往无 NAT，直连最省心；注意防火墙仍可能拦入站，同样走双向打洞）；
   - agent 侧 UPnP IGD / NAT-PMP / PCP 主动端口映射候选（成功则等效公网监听，成功率最高路径）。
   所有候选对并行探测，按 RTT 择优；直连 > 中继。
5. **NAT 行为自检**：向服务器两个不同 UDP 端口各发一次 STUN，比较映射端口是否一致，
   判定 EIM/EDM（即 Tailscale netcheck 的 `MappingVariesByDestIP`）。EDM 端提前告知对端，
   触发 §2.2 生日模式；PRC 判定为 EIM 后走标准打洞，无需端口预测。
6. **保活**：NAT UDP 映射老化普遍 30s–2min，CGNAT/手机热点更短。直连建立后**双向**保活，
   间隔 15s（移动网络）/ 25s（WiFi），与业务复用同一 socket。QUIC `KeepAlivePeriod=15s`、
   `MaxIdleTimeout=60s` 量级。
7. **失败快切、后台升级**：打洞窗口（A1 ≤10s / A2 ≤30s）内未通立即继续走现有 WSS 中继（用户无感知），
   后台周期性重试直连，成功后无缝迁移（Tailscale 同款策略：~5% 连接先中继后升级）。
   不要在失败路径上死磕。
8. **安全**：打洞通道必须加密认证。QUIC 自带 TLS 1.3；证书指纹/公钥经已认证的信令通道交换并 pinning，
   防中间人；复用现有配对 token 做信令鉴权。探测包带会话随机 nonce，防第三方伪造打洞包。

### 3.3 已知不可控因素（必须靠兜底覆盖）

- 运营商/校园网**丢弃入站 UDP**（即使 NAT 类型"看起来很好"）：STUN 正常但双向探测永远碰不上。
  实测案例表明 netcheck 类自检测不出这一点，只能靠快速回退中继。
- 移动网络 CGNAT 多为 EDM（对称）：标准打洞大概率失败，**必须启用生日悖论模式**（§2.2，A2 验收项）。
  双 EDM（手机×手机等）数学上不划算（需 ~17 万包/侧），直接中继，不在本期范围。
- 企业网禁 UDP：可选 TCP simultaneous open（RFC 5382）+ TLS 兜底，但 Android/Windows 栈行为不一，
  优先级放低；现有 WSS 中继本身就是 TCP/443 通路，已覆盖此场景。

## 4. 技术选型

### 4.1 传输层：QUIC over 打洞 UDP socket（推荐）

- Go：`github.com/quic-go/quic-go`，支持在已打洞的 `net.PacketConn` 上建连；
  提供可靠多路复用流（现有 JSON-RPC 信封可直接映射为 stream）、TLS 1.3、
  **连接迁移**（手机 WiFi↔蜂窝切换不断连，对本项目价值极大）。
- 打洞/STUN：`github.com/pion/stun`（客户端+服务端，轻量）；候选调度自研薄层即可，
  不必引入完整 `pion/ice`（其 ICE 状态机面向 WebRTC SDP，对本项目偏重；可借鉴其 candidate pair 逻辑）。
- 不选 WebRTC data channel：libwebrtc 体积数十 MB、引入 DTLS/SCTP/SDP 全套，对文本 RPC 中继严重过重。

### 4.2 Android 侧接入方式（关键决策点）

| 方案 | 说明 | 评估 |
|---|---|---|
| **A. gomobile bind（推荐）** | 把 Go P2P 栈（stun+打洞+生日模式+quic-go+路径管理）编译为 AAR，Kotlin 经 JNI 调用 | 双端共用同一份协议代码，一致性最好；APK 增大约 10–20MB；需 NDK 工具链；gomobile 导出接口限基本类型，用"句柄+回调/轮询"风格封装 |
| B. Kotlin 原生实现 | DatagramSocket 打洞 + 自研可靠层或 kwik(Java QUIC) | 无成熟纯 Kotlin QUIC 库，自研可靠传输风险高；双端协议双实现易漂移 |
| C. libwebrtc | 完整 ICE/DTLS/SCTP | 过重，排除 |

推荐 A：`internal/p2p`（新包）同时被 agent 直接引用、被 `gomobile bind -target=android` 产出 AAR 供
`android/app` 依赖。Tailscale-android 即同构思路（Go 核心 + 平台薄壳），可行性已被大规模验证。
注意本项目约定：无 androidx、minSdk 28/功能下限 Android 12，gomobile `-androidapi=28` 即可。
A2 生日模式要求手机侧同时开 ~256 个 UDP socket——Go 栈内实现无平台障碍，Android 无 socket 数量限制问题。

### 4.3 服务器侧新增

- STUN UDP 服务：监听两个端口（3478/3479，用于 EIM/EDM 判定），用 pion/stun 实现 Binding Request 应答。
  与现有 codexrm-server 同进程部署，nginx 只反代 TCP，UDP 端口需云安全组直接放行。
- 信令扩展（复用现有 WS，新增消息类型，不动现有信封语义）：
  - `p2p_probe`：上报本端候选集（LAN/srflx/IPv6/端口映射）+ NAT 行为自检结果 + QUIC 证书指纹；
  - `p2p_offer` / `p2p_answer`：服务器撮合 agent↔client，互换候选；
  - `p2p_start`：同步打洞开始时间戳（服务器单调时钟对齐，±300ms 容忍）+ 模式（标准/生日）与探测预算；
  - `p2p_result`：上报直连成功/失败、路径 RTT、失败原因分类（A1–A4 验收数据来源，sqlite 落库）。
- 中继兜底：零改动，现有 `/api/v1/client/ws` ↔ `/api/v1/agent/ws` 转发即兜底路径（A3 保障）。

## 5. 连接建立流程（目标态）

```
agent                    server(信令+STUN)                 android
  |-- STUN(同一socket,两端口) -->|                            |
  |<-- srflx + EIM/EDM 判定 ----|<---- STUN(同一socket) ------|
  |-- p2p_probe(候选,指纹) ----->|<---- p2p_probe ------------|
  |<-- p2p_offer(对端候选) ------|---- p2p_answer ----------->|
  |<=========== p2p_start(同步时间戳, 模式) =================>|
  |== 标准: 双向 UDP 探测(150ms间隔, 并行试 LAN/srflx/v6) ====|
  |== 生日(对端EDM): 本机随机探端口 ↔ 手机256 socket 齐发 ====|
  |-- QUIC handshake(TLS1.3, 指纹校验) <====================>|
  |-- RPC 信封迁移到 QUIC stream；WSS 保持为热备 ------------|
  （任一环节失败/超窗：继续走 WSS 中继，后台择机重试升级）
```

路径管理原则：
- 双通道并存期以"先建先用"为准，QUIC 就绪后新 RPC 走直连，在途请求留在原通道完成（streamId 幂等）。
- 直连静默超时（3×保活周期无 pong）→ 立即切回中继并重试打洞；Android 网络切换事件触发重新探测
  （QUIC 迁移优先，失败则重走信令）。
- Android 侧打洞/保活放在现有前台服务生命周期内，注意 Doze 下保活间隔放宽时依赖中继兜底。

## 6. 关键参数表（初始默认，实测后调）

| 参数 | 默认值 | 依据 |
|---|---|---|
| 标准打洞探测间隔 | 150ms | 100–200ms 区间，过快易被 QoS 限流 |
| 标准打洞窗口（A1） | 8s（2 轮×4s） | 超窗即回退，不死磕 |
| 生日模式窗口（A2） | 30s（2 轮×15s） | 100–200 探/s × 2048 探 ≈99.9% 理论命中 |
| 生日模式手机侧 socket 数 | 256 | Tailscale 验证值；会话表负载温和 |
| 探测包大小 | <200B | 避免分片被 NAT 过滤 |
| 保活间隔 | 15s 移动 / 25s WiFi | NAT UDP 老化 30s–2min，CGNAT 更短 |
| STUN 超时 | 3s×3 次 | 超时无响应≈UDP 被禁，直接标记走中继 |
| QUIC KeepAlive / IdleTimeout | 15s / 60s | 与保活对齐 |
| 时钟同步容忍 | ±300ms | NAT 过滤窗口百毫秒量级，无需 NTP |

## 7. 分阶段实施计划

- **P0 打洞核心（Go，桌面↔桌面先验证）**
  1. `internal/p2p`：候选收集（LAN/固定端口）、pion/stun 客户端、EIM/EDM 自检、双向打洞状态机、
     **生日悖论模式（探测侧+多 socket 侧，A2 核心）**、quic-go 建连与指纹校验；
  2. server：STUN 双端口服务 + §4.3 信令消息 + `p2p_result` 统计落库（sqlite，A1–A4 验收数据源）；
  3. agent：路径管理器（直连/中继切换），RPC 信封映射 QUIC stream；
  4. 验证：`go test ./internal/p2p/ ./internal/server/`、`go build ./...`；
     实验室 iptables 模拟 PRC×PRC 与 PRC×EDM，先跑 A1/A2 的实验室部分。
- **P1 Android 接入**
  1. gomobile bind 产出 AAR（`-androidapi=28`），Kotlin 封装句柄式 API + 网络变化广播联动；
  2. CodexClient 增加路径管理（与 agent 对称），UI 显示"直连/中继"状态；
  3. 真机跑 A2（≥2 运营商 SIM × 各 ≥30 次）与 A4；
  4. 验证：assembleDebug + 单元测试 + lint（遵守项目约定，不跑 release 脚本）。
- **P2 增强（按 P0/P1 遥测数据决定优先级）**
  UPnP/NAT-PMP/PCP 端口映射（agent 侧）；IPv6 候选完善；STUN 第二公网 IP（若 CGNAT 多 IP 侵蚀 A2）；
  TCP simultaneous open 兜底（仅当 UDP 被禁场景占比高且 WSS 中继体验不足时）。

### 测试矩阵（P0 起持续执行，对齐验收指标）

用 Linux 网关 iptables/nftables 模拟 NAT 行为（MASQUERADE + 源端口保持=EIM；随机端口=EDM；
conntrack 超时调短模拟老化），覆盖：

| 场景 | 对应指标 | 通过线 |
|---|---|---|
| PRC×PRC（核心） | A1/A1b | ≥95%，P50≤2s / P90≤5s |
| PRC×EDM（模拟蜂窝） | A2 | >50%（30s 窗口） |
| 同 LAN / hairpinning 关闭 | A1 | 直连命中 LAN 候选 |
| CGNAT 双层（EIM 外层） | A1 | 标准打洞仍通 |
| 入站 UDP 全丢 | A3 | ≤10s 回退中继，RPC 不丢 |
| WiFi↔蜂窝切换 | A3/A4 | QUIC 迁移或 ≤2s 内重连，RPC 中断可恢复 |
| agent 重启端口复用 / 弱网 30% 丢包 | A4 | 保活存活 ≥99% |

每次打洞记录 `p2p_result`（NAT 判定、候选命中、耗时、失败原因），用真实数据驱动 P2 优先级与指标复核。

## 8. 参考资料

- Tailscale: How NAT traversal works（tailscale.com/blog/how-nat-traversal-works）——EIM/EDM 分类、
  双向打洞、生日悖论、端口映射、CGNAT/hairpinning、DERP 兜底策略的权威工程叙述
- RFC 4787（NAT UDP 行为）、RFC 5382（TCP）、RFC 8489（STUN）、RFC 8445（ICE）、
  RFC 6886（NAT-PMP）、RFC 6887（PCP）
- github.com/pion/stun、github.com/pion/ice（候选对调度参考）、github.com/quic-go/quic-go
- golang.org/x/mobile（gomobile bind）；Tailscale-android（Go 核心 + Android 薄壳的同构先例）
- github.com/danderson/nat-birthday-paradox（EDM 打洞概率计算）
- 实测案例：netcheck 全绿仍打洞失败（运营商丢入站 UDP）→ 兜底与重试升级策略的必要性