# 工作交接文档（HANDOFF）— 服务器端全量 Code Review 与「全修复」任务

> 2026-11-21 续接结果。服务端审查修复已落到当前工作树。Go 全量普通测试、静态检查和竞态测试通过。 检查记录和逐项状态见 [本轮验证与交付记录](.work/handoff-completion-20261121/notes.md)。

> 生成/更新时间：2026-11-21（Asia/Singapore）
> 生成原因：用户下达「立即中止当前开发任务，停止一切与原任务相关的代码修改/修复/重构/写入；仅执行工作交接；除创建或更新 HANDOFF.md 外不得修改任何项目文件；不得回滚或清理现有修改；不得为完善交接文档做额外开发；无法确认的信息必须标注」。
> 本文件是本次中止后**唯一被写入**的项目文件。写入后不再执行任何工具调用，未回滚、未清理、未继续开发、未在中止后执行构建或测试。
> **重要**：写入前 `HANDOFF.md` 已存在且为未跟踪文件（`?? HANDOFF.md`），其内容描述的是**另一个任务**（Codex 桌面版前端逆向 / 思考动画 / 操作折叠），来源**无法确认**，且与本会话环境事实存在冲突（例如原文称「本机没有 pwsh.exe」，而本会话 `pwsh` 工具实际可用）。为不丢失信息，原文完整保留在**附录 A**，并明确标注来源未确认、不得直接采信。
> 本文所有结论均来自本会话中已真实执行的工具输出与文件读取；未执行/未确认的内容显式标注，不推测、不编造。

---

## 0. 中止指令与硬边界

用户最后一条指令优先于此前所有任务目标：

1. 立即中止当前开发任务；停止一切与原任务相关的代码修改、修复、重构和其他写入操作。
2. 不要继续推进当前任务，即使只剩少量工作也必须停止。
3. 现在仅执行工作交接：汇总原始目标/需求/约束、已完成工作、未完成工作、当前代码实际状态、测试记录、后续步骤、关键路径/命令/注意事项。
4. 除创建或更新 `HANDOFF.md` 外，**不得修改任何项目文件**。
5. 不得擅自回滚或清理现有修改。
6. 不得为了完善交接文档而进行额外开发。
7. 无法确认的信息必须明确标注，不得猜测或编造。

执行结果（已核验）：

- `list_agents` 显示本会话 14 个子代理全部为 `inactive`，**没有仍在运行的写入任务**；因此无需也不能再发起任何开发动作。
- `job_list` 显示本会话 4 个后台作业全部 `completed`；其它会话/任务的后台进程是否存活**无法确认**。
- 主工作树与隔离副本保持原样，未回滚、未清理、未同步。

---

## 1. 原始目标、需求与约束

### 1.1 用户请求链

| 轮次 | 用户请求 | 结果 |
| --- | --- | --- |
| 1 | `全量code review 服务器端代码，检查正确性和可靠性` | **已完成并交付**：36 项 findings + 验证记录 |
| 2 | `全修复` | **已中止**：仅完成基线/隔离副本/脚本等准备，36 项 findings **一项都未落盘修复** |
| 3 | `立即中止…仅交接…写入 HANDOFF.md` | 正在执行（本文件） |

### 1.2 轮次 1 目标与约束

- 对当前工作树的 Go 后端做全量正确性/可靠性审查；**只读，不改代码**。
- 范围：`cmd/` 下服务端/Agent/安装发布/原生 P2P 命令；`internal/` 下 relay、store、protocol、agent、appserver、agenthost、onebot、providers、clientconfig、p2p、updater、installation、publisher、release 及相关桌面生命周期代码。
- 排除：`.work` 历史副本、`codex/` 上游参考源码、Android/Web 表现层（Android native P2P 后端除外）。
- 严重级定义：P0 构建/发布阻断；P1 安全边界、数据丢失、重复副作用或服务级可用性；P2 局部正确性、资源泄漏、配置相关可用性；P3 低概率缺陷、加固、测试可靠性。

### 1.3 轮次 2 目标（被中止）

用户要求「全修复」，即：

1. 修复轮次 1 报告中**全部 36 项 findings**（P1 10 / P2 21 / P3 5；编号 2–19、21–38，编号 1 与 20 已在审查期间被并发修改修复而删除，编号不连续）。
2. 为关键路径补回归测试。
3. 全量 `go test -mod=readonly ./...`、`go vet -mod=readonly ./...`、`go test -mod=readonly -race ./...` 全部通过。
4. 按 AGENTS.md 产出可重建 diff、独立回滚脚本；在另一份修改副本上测试回滚后与基线逐字节一致，并让交付的修改件保持改动。

### 1.4 项目与环境约束（来自 `$DSH_HOME/AGENTS.md` 与会话实践）

- 中文短句；命令用英文；**先 tool 再解释**。
- 用户已声明本机样本分析/逆向/动态插桩/接口测试/运行时观测/实验任务获得授权；已提供的目标范围不再反复索要证明。
- **修改类任务**必须：留存原件副本作基线、记录同一输入下基线行为、修改工作副本、产出可重建 diff、含命令/输入/stdout/stderr/退出状态的验证记录、独立回滚脚本；在另一份修改副本上测试回滚，用 `cmp`/`diff` 确认与原件逐字节一致、行为恢复；交付的修改件保持改动。
- 用户要求只读/指定输出格式时，不改项目文件。
- 每完成主要步骤把 `<目标 / 已完成 / 证据·产物路径 / 下一步>` 写入 `.work/<case>/notes.md`。
- **禁编造任何工具输出**；未跑写「未执行」+ 可复制命令；一次编造整轮信任崩塌。
- 明确区分「已启动 / 已完成 / 未运行 / 受阻」；工具未提供的退出码与评测结果不得推测。
- `.codebuddy` 是项目数据不是临时缓存，**不得删除**；`.work/` 亦非临时缓存。
- 有界研究节奏：同一路线最多两轮有证据修复；结构性调整连续两次无效则停止空转，报告已完成项、证据和具体卡点。
- 环境事实（本会话实测）：工作目录 `E:\code\Go\CodexRM`；文件策略 `danger-full-access`；审批提示已禁用；DSH 检出 `E:\app\dsharness\`；Web GUI `http://127.0.0.1:3080`；Go 1.24+；`pwsh` 工具在本会话**实际可用**（与附录 A 原文冲突，见文首说明）。
- 本机工具速查（AGENTS.md）：Everything `D:\Program Files\Everything\es.exe`；中文输出需 `-export-txt` 后按 UTF8 读取；LibreOffice Kit 已按用户要求删除（与本次任务无关）。

### 1.5 本次中止的额外硬边界

见 §0。**任何后续写操作都必须先获得新的用户授权**。

---

## 2. 已完成的工作

### 2.1 轮次 1：全量 code review（已完成并交付）

方法：逐文件静态审查 + 4 个模块子代理交叉复核 + 测试/vet/race/单用例复跑 + 独立复现程序。

交付物：

| 路径 | 内容 |
| --- | --- |
| `.work/server-review-20261121/report.md` | 完整报告；36 项 findings，含准确行号、触发条件、影响、最小修复建议、验证记录、未执行项 |
| `.work/server-review-20261121/store_lost_update.go` | Store 跨实例丢更新 + 空状态文件接受的独立复现程序 |
| `.work/server-review-20261121/snapshot/` | 11:35:54 固定的审查快照（`cmd`/`internal`/`web`/`go.mod`/`go.sum`），用于最终验证 |
| `.work/server-review-20261121/notes.md` | 审查阶段笔记 |

审查结论统计：**P0 无（最终快照可构建）；P1 10、P2 21、P3 5**，合计 36 项。

已动态验证（非静态推断）：

- `go run -mod=readonly .work/server-review-20261121/store_lost_update.go` 输出 `agents=1 first_err=not found second_err=<nil>` 与 `empty_agents=0 empty_clients=0`；证实跨 Store 实例丢更新、空 `state.json` 被当成空库。
- 最终固定快照 `go test -mod=readonly ./...` 全部通过；`go vet -mod=readonly ./...` 通过；`go test -mod=readonly -race ./...` 未报告 data race，但 `TestLargeThreadHistoryKeepsRelayConnected` 在固定 10 秒读期限超时（整体 exit 1，其余包通过）。
- 单用例对照：`go test -race ./internal/server -run '^TestLargeThreadHistoryKeepsRelayConnected$' -count=1 -v` 再次超时；普通模式同用例 4.52 秒通过 → 判定为测试/性能可靠性问题，非已证实死锁。

### 2.2 轮次 2：修复准备（已完成，但**没有产生任何生产代码修改**）

| 路径 | 内容 |
| --- | --- |
| `.work/full-fix-20261121/baseline/` | 修复前完整工作树副本（排除 `.git`/`.work`/`codex`/`.state`/`.qoder-input-images`），约 28,157 个文件 |
| `.work/full-fix-20261121/fixed/` | 隔离修复副本；**最终状态与 baseline 逐字节一致** |
| `.work/full-fix-20261121/baseline-created.txt` | 基线创建时间 |
| `.work/full-fix-20261121/notes.md` | 基线、分组方案、验收标准 |
| `.work/full-fix-20261121/rollback.ps1` | 独立回滚脚本（`-Target` + `-Apply`，dry-run 默认） |
| `.work/full-fix-20261121/verify-rollback.ps1` | 回滚后逐文件 SHA256 比对脚本 |
| `.work/full-fix-20261121/compare-fixed.ps1` | 生成 `changes.txt`（ADD/MOD/DEL 清单） |
| `.work/full-fix-20261121/sync-fixed.ps1` | 带冲突检测的同步脚本（目标文件哈希须等于基线哈希，否则报 `SYNC_CONFLICTS`） |
| `.work/full-fix-20261121/changes.txt` | 曾记录 14 条差异（多为副本创建时的锁文件缺失与主树 web 并发改动造成的伪差异），**已随副本重置而失效，不可作为最终 diff** |

分组方案（文件互不重叠）：

- A 中继/持久化：`cmd/codexrm-server/*`、`internal/server/*`、`internal/store/*`、`internal/protocol/*`
- B Agent/OneBot/appserver：`cmd/codexrm-agent/main.go`、`internal/agent` 非 `wx_*`、`internal/appserver/*`、`internal/onebot/*`、`internal/clientconfig/*`、`internal/providers/*`
- C AgentHost/微信：`internal/agenthost/*`、`internal/agent/wx_*`（`wx_robot.go` 归 B）
- D 基础设施：`internal/p2p/*`、`internal/updater/*`、`internal/installation/*`、`internal/publisher/*`、`internal/release/*`、`cmd/codexrm-publish|setup|android-p2p/main.go`

### 2.3 关键设计决策

1. **隔离副本修复**：本会话观测到主工作树存在并发写入（12 秒内 `internal/agent/onebot_runtime.go` 的 SHA256 发生变化），因此所有修复写入 `.work/full-fix-20261121/fixed/`，最后才用 `sync-fixed.ps1 -Apply` 同步回主树，避免覆盖他人改动。
2. **冲突检测同步**：`sync-fixed.ps1` 对每个待同步文件校验「目标当前哈希 == 基线哈希」，不满足则整体拒绝，防止把并发改动覆盖掉。
3. **先小批、后集成**：大分组失败后改为 6 个小批次（见 §2.4），并明确要求各批只写自己白名单文件。
4. **不回滚、不清理**：中止指令后，所有已产生的 `.work` 产物与主树改动保持原样。

### 2.4 明确失败 / 未落盘的工作（不得描述为已完成）

- **4 个大子代理**（`修复 Agent 与 OneBot/appserver`、`修复 P2P 更新安装测试`、`修复中继与持久化`、`修复 AgentHost 与微信管理面`）全部 `failed before it finished`；其中部分留下「仍在分析 / 已确认关键源码」的中间消息，**未交付任何完整修复**。
- 其运行期间 `fixed/` 曾出现 14 条差异（`changes.txt`），随后用 `baseline` 整体重置；最终核验 `CHANGED=0`。
- **6 个小批子代理**（小批基础设施、Store 持久化、中继核心 server.go、appserver 写阻塞与通知、Agent 确定问题、AgentHost 状态问题）也全部 `failed before it finished`；最后一批消息均停留在「已理解 / 继续核对」阶段，**未写入任何文件**。
- 曾向 6 个小批代理发送「优先落盘、分项交付」的消息，但它们随即全部失败。
- 期间曾出现两条**仅文字、未落盘**的跨包接口预报告：AgentHost 组指出 `settingsSecret()` / `randomSecret()` 改签名会涉及包内 3 个调用文件；Server 组指出将按预期调用 `Store.Close` / `PairingStatus(agentID,code)` / `AgentCount` / `RecordAgentConnection` / `PruneInactiveAgents`。这些接口变更均未在 `fixed/` 中落盘。

---

## 3. 当前代码的实际状态

### 3.1 中止后只读核验（真实输出）

```text
BASE_FILES=28157 FIXED_FILES=28157 CHANGED=0
LATEST_FIXED_WRITE=11/21/2026 11:59:29
HANDOFF_EXISTS=True
```

- `BASE_FILES=28157 FIXED_FILES=28157 CHANGED=0`：`.work/full-fix-20261121/fixed/` 与 `baseline/` **逐文件 SHA256 完全一致**（全量比较，不只是 Go 文件）。
- `LATEST_FIXED_WRITE=11:59:29`：副本内最新写入时间早于修复任务开始，说明重置后没有新写入。
- `HANDOFF_EXISTS=True` 且 `git status --short -- HANDOFF.md` → `?? HANDOFF.md`（未跟踪，写入前已存在，见文首与附录 A）。
- `git rev-parse --short HEAD` → `ccaa46c`。
- `list_agents`：14 个子代理全部 `inactive`。
- `job_list`：本会话 4 个后台作业全部 `completed`。

### 3.2 主工作树

- **本次「全修复」任务没有向主工作树写入任何生产代码**。所有写入都发生在 `.work/full-fix-20261121/` 下；且 `fixed/` 已重置为基线。
- `git status --short -- cmd internal` 显示大量 `M` 与 `??`（例如 `cmd/codexrm-agent/main.go`、`internal/agent/agent.go`、`internal/server/server.go`、`internal/p2p/punch.go`，以及 `internal/agent/onebot_*.go`、`internal/agenthost/wx_*.go` 等未跟踪文件）。这些是**任务开始前既有改动 + 会话期间其他写入者的并发改动**；来源与具体内容**未逐项确认**。
- 已确认存在并发写入：12 秒观测中 `internal/agent/onebot_runtime.go` 哈希变化；审查阶段亦多次出现「文件在编译与读取之间被修改」。
- **结论**：主工作树不处于「已修复」状态，也不处于「已回滚」状态；它是任务开始前的用户工作树，且可能仍在被并行编辑。

### 3.3 隔离副本

- `fixed/` == `baseline/`，**没有任何部分完成的修改**，因此**不存在由本次任务引入的接口不一致或编译错误**。
- 但 `baseline/` 自身有两个已知问题（见 §3.4），它们也是「全修复」任务要处理的基线失败。

### 3.4 可能不一致的接口与潜在编译/运行问题

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 本次任务引入的接口不一致 | **无** | `fixed/` 与基线逐字节一致，未落盘任何改动 |
| 计划中但未落盘的接口变更（下一位若继续须注意） | 未落盘 | `Store.PairingStatus(agentID, code)`、`Store.Close()`、`AgentCount()`、`RecordAgentConnection()`、`PruneInactiveAgents()`；`appserver.Client` 写函数改为 `func(context.Context, []byte) error` + `WriteRawContext`；`settingsSecret()` / `randomSecret()` 返回 `(string, error)`。若只改一半会造成编译失败 |
| `internal/onebot` `TestConfigValidate` | **基线即失败** | 新增 `validateListenerAuth` 把测试里的 `Listen:":8081"`（无 token）判为非 loopback；`go test ./internal/onebot` 失败。修复方向：测试改为本机地址或带 token，同时保留「非本机通配监听必须有 token」的安全要求 |
| `TestLargeThreadHistoryKeepsRelayConnected` | **race 基线即超时** | `internal/server/server_test.go` 的 `read()` 固定 10 秒 deadline；race 下大数据用例超时，普通模式约 4.5–5 秒通过。修复方向：延长/分阶段 deadline，不改生产代码 |
| 主工作树并发写入 | 已确认存在、来源未确认 | 任何后续写入前必须先 `git diff` / 哈希只读复核 |
| `HANDOFF.md` 附录 A 的环境事实 | 来源未确认、与本会话冲突 | 原文称「本机没有 pwsh.exe，只有 powershell.exe」「Tee-Object 日志 UTF-16LE」等；本会话 `pwsh` 工具实际可用。**不得直接采信附录 A 的环境结论** |
| 后台进程 | 本会话无运行中作业 | `job_list` 全部 completed；附录 A 提到的 `python -m http.server 8765`（job `pwsh-4fe66f2d225f4d32`）属于另一个任务，本会话 `job_list` 中不存在，是否存活**无法确认** |

### 3.5 未产出的交付物

以下均**不存在**（不得描述为已完成）：

- 任何修复后的生产源码 diff / `changes.patch`（有效的最终 diff 应为 `CHANGED=0`）
- `current/` 快照、`manifest.json`、`rollback-verification.log`
- 回滚实际执行结果（脚本已写，但**未运行**）
- 修复同步回主工作树（`sync-fixed.ps1 -Apply` **未执行**）
- 新增回归测试（36 项对应的测试**一个都未写**）
- 全量修复后的 `go test` / `go vet` / `go test -race` 结果

---

## 4. 测试与验证记录

> 以下均为本会话中**真实执行**的命令与输出摘要。未执行的项在 §4.4 列出，不得描述为已完成。

### 4.1 轮次 1（审查阶段）

| 命令 | 结果 |
| --- | --- |
| `go test ./...`（OneBot 新增前） | exit 0，全通过 |
| `go vet ./...` | exit 0 |
| `go test -race ./...` | exit 1；无 data race 报告，`TestLargeThreadHistoryKeepsRelayConnected` 超时 |
| `go test -race ./internal/server -run '^TestLargeThreadHistoryKeepsRelayConnected$' -count=1 -v` | 再次超时（19.22s / 29.36s） |
| `go test ./internal/server -run '^TestLargeThreadHistoryKeepsRelayConnected$' -count=1 -v` | 通过，4.52s |
| `go test -mod=readonly ./internal/store -count=1` | 通过 |
| `go test -mod=readonly ./cmd/codexrm-server ./internal/protocol -count=1` | cmd 通过；protocol 无测试 |
| 显式文件列表 `go test ... -run '^TestRevocationBetweenClientAuthAndUpgrade$' -count=1` | 通过（仅覆盖 upgrade 前撤销） |
| `go test -count=1 ./internal/p2p ./internal/updater ./internal/installation ./internal/publisher ./cmd/codexrm-setup` | 全通过（含最新 P2P peer-reflexive 修改） |
| `go test ./internal/desktop -run '^TestDuplicateOpenAndRestartWaitForOriginalExit$' -count=1` | 通过 |
| `go test -count=1 ./internal/onebot ./internal/appserver ./internal/clientconfig ./internal/providers` | 可构建目标通过（当时 OneBot 无测试） |
| `go test ./internal/agenthost ./internal/agent`（子代理补审后） | agenthost 4.257s、agent 7.707s 通过 |
| 固定审查快照 `go test -mod=readonly ./...` | **全部通过**（Server 19.820s） |
| 固定审查快照 `go vet -mod=readonly ./...` | 通过 |
| 固定审查快照 `go test -mod=readonly -race ./...` | exit 1；无 data race，大历史用例超时（Server 53.219s），其余包通过 |
| `go run -mod=readonly .work/server-review-20261121/store_lost_update.go` | `agents=1 first_err=not found second_err=<nil>` + `empty_agents=0 empty_clients=0` |

### 4.2 轮次 2（修复阶段，基线）

| 命令 | 结果 |
| --- | --- |
| `go test -mod=readonly ./...`（主工作树基线） | **exit 1**：`internal/onebot` `TestConfigValidate` 失败（`onebot: accessToken is required for a non-loopback listener`）；其余包通过 |
| `go test -mod=readonly -race ./...`（主工作树基线） | **exit 1**：同上 onebot 失败 + `TestLargeThreadHistoryKeepsRelayConnected` 20.13s 超时；未报告 data race |
| 12 秒哈希观测 | `internal/agent/onebot_runtime.go` 哈希变化 → 确认并发写入 |
| `go test -mod=readonly ./... -run '^$'`（当时含少量部分编辑的 `fixed/`） | exit 0，全部包可编译（该结果对应重置前的副本，**不对应最终状态**） |
| 副本重置后 `compare-fixed.ps1` | `CHANGES=0`（12:38、12:39、12:41 三次） |
| 中止后全量哈希比较 | `BASE_FILES=28157 FIXED_FILES=28157 CHANGED=0` |

### 4.3 中止后已执行（只读，无写入）

- `list_agents`（14 个全部 inactive）
- `job_list`（本会话 4 个全部 completed）
- `git status --short -- HANDOFF.md` → `?? HANDOFF.md`；`git rev-parse --short HEAD` → `ccaa46c`
- `git status --short -- cmd internal`（前 60 行）
- 全量 SHA256 比较 `baseline/` vs `fixed/`（内联 PowerShell，**未写 `changes.txt`**）
- `read`：`.work/server-review-20261121/report.md`（验证记录段）、`.work/full-fix-20261121/notes.md`、`HANDOFF.md`（写入前原文）

### 4.4 未执行 / 未验证（NOT_RUN，不得描述为已完成）

1. 36 项 findings 的全部修复与回归测试。
2. 修复后 `go test -mod=readonly ./...`、`go vet -mod=readonly ./...`、`go test -mod=readonly -race ./...`。
3. `rollback.ps1 -Apply` / `verify-rollback.ps1` 的实际执行与逐字节一致性验证。
4. `sync-fixed.ps1 -Apply`（同步回主工作树）。
5. 有效 `changes.patch` / `current/` 快照 / `manifest.json` / `rollback-verification.log`。
6. 中止后重新编译、测试或浏览器/运行时验证（受「仅交接」约束，未执行）。
7. 真实 NAT/公网 STUN、Android/cgo 运行时、Linux reexec、真实 Docker/微信操作、Cloudreve 发布、掉电、慢消费者、stdin 写满、OneBot 无响应故障注入。
8. 附录 A 所述另一任务的收尾状态（`task-close`、浏览器补验、跨浏览器验证等）：**来源未确认，本会话未核验**。

---

## 5. 尚未完成的工作与已知风险

### 5.1 全部 36 项 findings（**一项都未修复**）

> 完整描述、行号、触发条件、影响、最小修复建议见 `.work/server-review-20261121/report.md`。编号为审查跟踪 ID，1 与 20 已被并发修改修复后删除，故不连续。工作树在审查后仍有变化，报告行号可能漂移，需重新核对。

**P1（10 项）**

| ID | 标题 |
| --- | --- |
| 2 | 撤销/删除成功返回后，已读 RPC 仍可被派发执行 |
| 3 | 单 peer 出站队列理论峰值约 4 GiB |
| 4 | JSON 状态没有崩溃持久性；已有空文件被静默当成空库 |
| 5 | OneBot 回复可无限阻塞 app-server 唯一读循环 |
| 6 | stdio 写阻塞不受 RPC context/deadline 控制 |
| 7 | 未过期但被撤销的 token 不会触发重新 enroll |
| 8 | 排队 turn 已被接受但响应超时后，会重复执行同一消息 |
| 9 | 微信后台任务脱离 Host 生命周期和更新空闲门控 |
| 10 | 微信任务终态保存失败会在下一次轮询重复执行维护操作 |
| 11 | taken-over 账号使用默认 relay 配对后，Host 重启不会恢复 relay |

**P2（21 项）**

| ID | 标题 |
| --- | --- |
| 12 | 多进程共用 DataDir 会丢状态更新 |
| 13 | relay 添加元数据可使出站帧超过 16 MiB |
| 14 | PublicEnrollment 允许永久状态无限增长并产生 O(N²) 写放大 |
| 15 | 无 enrollment key 时，特定 loopback 代理可把远端注册误判为本地 |
| 16 | 在线 Agent 重新 enroll 会被误标 offline |
| 17 | Agent 中继缺少应用层读超时，写错误又被忽略 |
| 18 | 上传中断后的 `.part` 文件没有恢复或启动清理 |
| 19 | `updating` 使用两把锁，存在数据竞争 |
| 21 | OneBot status/stopTurn 锁外读取可变 TurnID |
| 22 | 无 HEAD 仓库的 workspace diff 漏掉暂存后的工作区修改 |
| 23 | threadSnapshot 的 status 与 turns 来自不同时间点 |
| 24 | Android native 信令背压可绕过 P2PAttempt 并卡住清理 |
| 25 | 默认安装目录卸载成功后仍返回错误 |
| 26 | 更新最终状态写盘失败会遗留暂存包并保留成功状态 |
| 27 | Linux 默认 headless 的 SSH 提示没有可用认证凭据 |
| 28 | 微信命令输出限额与超时不能约束实际进程行为 |
| 29 | 设置服务 40 秒写超时与合法长操作错配 |
| 30 | `wx-instance.json` 的读改写锁不完整且共用固定 tmp |
| 31 | 安装阶段的 Docker network 调用完全绕过任务 ctx |
| 32 | 独立微信 relay 未复用主连接的 CodexPath 失效回退 |
| 33 | 默认本机设置的 bearer fragment 会进入错误日志 |

**P3（5 项）**

| ID | 标题 |
| --- | --- |
| 34 | CSPRNG 错误被忽略或回退为可预测秘密 |
| 35 | 静态文件/更新下载没有响应写超时 |
| 36 | pair/status 未按所属 Agent 隔离 |
| 37 | 40 位手工配对码未检查活跃码唯一性 |
| 38 | 大历史测试在 race 下存在固定 10 秒读超时脆弱性 |

### 5.2 待完成工程产物

- 有效修复 diff、新增回归测试、`current/` 快照、`manifest.json`、`rollback-verification.log`、`notes.md` 的最终更新。
- 回滚实际执行与逐字节一致性验证。
- 同步回主工作树（`sync-fixed.ps1 -Apply`，须先通过冲突检测）。
- 全量三件套验证通过。

### 5.3 已知风险

- **主工作树长期存在并发写入**（已实测），任何写入都可能与他人改动叠加；必须先做只读 diff/哈希复核。
- **基线本身不完整**：`internal/onebot` 配置测试失败、race 大历史用例超时；即使不改代码，`go test ./...` / `go test -race ./...` 也不是全绿。
- **跨包接口变更风险**：`Store.PairingStatus(agentID,code)`、`Store.Close`、appserver 写签名、`settingsSecret/randomSecret` 返回值等计划中变更涉及多个包与多个调用点；分批修改时极易出现一半落盘、一半未落盘的编译失败。
- **子代理整体失败**：10 个修复子代理（4 大 + 6 小）全部 `failed before it finished`；继续沿用「一次大任务交给子代理」的方式风险高，建议改为「单文件 / 单 finding、主代理直接编辑」。
- **`changes.txt` 已失效**：其内容对应重置前的副本，不可作为最终 diff。
- **附录 A 内容来源未确认**：其中环境事实、任务背景、后台进程信息与本会话不一致，勿直接采信。
- `.work/`、`artifacts/`、`.codebuddy` 不得删除或清理。

---

## 6. 下一位 Agent 可直接执行的后续步骤（按依赖与优先级）

> **前提**：当前用户指令是「中止」。以下步骤是交接建议，**执行任何写操作前必须先获得新的用户授权**。P0 全部只读，不违反本次中止约束。

### P0 — 只读复核（无写入，可立即执行）

1. 确认交接边界与工作树：
   ```text
   git status --short
   git rev-parse --short HEAD
   ```
2. 复核并发改动：对 `cmd/`、`internal/` 做 `git diff --stat` 与关键文件 `git diff`，确认哪些改动属于用户/其他写入者。
3. 确认隔离副本仍等于基线（内联 SHA256 比较，不落盘；`compare-fixed.ps1` 会写 `changes.txt`，只读约束下不要用）。
4. 重读 `.work/server-review-20261121/report.md`，按 §5.1 的 36 项重新对当前工作树核对行号。
5. 确认附录 A 的来源（另一个任务）不需要被合并进本任务。

### P1 — 单批小改动（需新授权）

按「一个 finding / 一个包」推进，**不要**一次委派大任务给子代理：

1. 先修基线自带失败：`internal/onebot/protocol_test.go`（`Listen:":8081"` → 本机地址或带 token，保留非 loopback 无 token 必须报错的安全断言）；`internal/server/server_test.go` 读 deadline 延长/按 race 分阶段。
2. 再按 P1 → P2 → P3 顺序，优先处理安全边界与重复副作用：2、3、4、5、6、7、8、9、10、11。
3. 每改一个包立即跑该包 `go test -count=1` + `go vet`；跨包接口变更（Store 新 API、appserver 写签名）必须**同一次提交内**改完所有调用点。
4. 每次落盘后更新 `.work/full-fix-20261121/notes.md`。

### P2 — 集成与全量验证（需新授权）

```text
go test -mod=readonly ./...
go vet -mod=readonly ./...
go test -mod=readonly -race ./...
```

若大历史用例仍超时，按 finding 38 处理（测试时限），**不要**放宽生产逻辑来换通过。

### P3 — 产物、回滚与同步（需新授权）

1. 生成有效 `changes.txt` / `changes.patch` / `current/` / `manifest.json`。
2. 在**独立副本**上执行 `rollback.ps1 -Apply` 与 `verify-rollback.ps1`，确认与基线逐字节一致、行为恢复；交付副本保持改动。
3. 用 `sync-fixed.ps1`（先 dry-run）做冲突检测；若报 `SYNC_CONFLICTS`，逐个文件人工核对并发改动后再决定，**不要**强制覆盖。
4. 同步后在主工作树再跑一次全量三件套。

### P4 — 收尾

- 更新 `.work/full-fix-20261121/notes.md`，记录每项 findings 的修复状态与验证证据。
- 按 AGENTS.md 输出 findings + 命令/stdout/stderr/退出状态；未跑项写 NOT_RUN。

---

## 7. 关键路径、命令与注意事项

### 7.1 路径

| 用途 | 路径 |
| --- | --- |
| 项目根 / 当前工作目录 | `E:\code\Go\CodexRM` |
| 本交接文档 | `E:\code\Go\CodexRM\HANDOFF.md`（`??` 未跟踪） |
| 审查报告（36 项） | `.work/server-review-20261121/report.md` |
| 审查快照 | `.work/server-review-20261121/snapshot/` |
| 审查笔记 | `.work/server-review-20261121/notes.md` |
| Store 复现程序 | `.work/server-review-20261121/store_lost_update.go` |
| 修复基线 | `.work/full-fix-20261121/baseline/` |
| 隔离副本（== 基线） | `.work/full-fix-20261121/fixed/` |
| 修复笔记 | `.work/full-fix-20261121/notes.md` |
| 回滚脚本 | `.work/full-fix-20261121/rollback.ps1` |
| 回滚校验脚本 | `.work/full-fix-20261121/verify-rollback.ps1` |
| 差异清单脚本 | `.work/full-fix-20261121/compare-fixed.ps1` |
| 同步脚本 | `.work/full-fix-20261121/sync-fixed.ps1` |
| 失效的中间产物 | `.work/full-fix-20261121/changes.txt` |

### 7.2 常用命令

```powershell
# 全量验证（在工作副本根执行）
go test -mod=readonly ./...
go vet -mod=readonly ./...
go test -mod=readonly -race ./...

# 独立复现（审查阶段）
go run -mod=readonly .work/server-review-20261121/store_lost_update.go

# 差异清单（会写 changes.txt）
powershell -NoProfile -ExecutionPolicy Bypass -File .work/full-fix-20261121/compare-fixed.ps1

# 同步 dry-run / 应用
powershell -NoProfile -ExecutionPolicy Bypass -File .work/full-fix-20261121/sync-fixed.ps1 -Target E:\code\Go\CodexRM
powershell -NoProfile -ExecutionPolicy Bypass -File .work/full-fix-20261121/sync-fixed.ps1 -Target E:\code\Go\CodexRM -Apply

# 回滚 dry-run / 应用 / 校验
powershell -NoProfile -ExecutionPolicy Bypass -File .work/full-fix-20261121/rollback.ps1 -Target <副本路径>
powershell -NoProfile -ExecutionPolicy Bypass -File .work/full-fix-20261121/rollback.ps1 -Target <副本路径> -Apply
powershell -NoProfile -ExecutionPolicy Bypass -File .work/full-fix-20261121/verify-rollback.ps1 -Root <副本路径>
```

### 7.3 注意事项

- **不要**删除 `.codebuddy`、`.work/`、`artifacts/`；不要 `git reset` / `git checkout` / `git clean`。
- **不要**把 `.work/full-fix-20261121/changes.txt` 当作最终 diff（已失效）。
- **不要**把 `.work/server-review-20261121/snapshot/` 当作可修改的工作副本；它是审查验证快照。
- 任何写入前先只读复核主工作树（存在并发编辑）。
- `sync-fixed.ps1` 的冲突检测是保护机制，报冲突时**不要**绕过。
- 附录 A 原文的环境结论与本会话冲突，勿直接采信。
- 本会话 `pwsh` 工具可用；AGENTS.md 提到的 `es.exe` 速查仍适用。

---

## 附录 A：写入前已存在的 `HANDOFF.md` 原文（来源未确认，与本次任务无关，完整保留）

> 以下为本次写入前 `HANDOFF.md` 的完整内容。它描述的是另一个任务（Codex 桌面版前端逆向 / 思考动画 / 操作折叠），其「用户请求链」「环境事实」（如「本机没有 pwsh.exe」）与本会话不一致，来源**无法确认**。保留仅为不丢失信息，**不得视为本次任务的已确认事实**。
---

# HANDOFF — CodexRM Android 全量审查与“全修复”任务交接

> 本文件由当前 Agent 在用户要求“立即中止当前开发任务”后创建。  
> 创建/更新 `HANDOFF.md` 是停止请求之后唯一的项目文件写入；**未继续执行修复、重构、回滚或清理**。  
> 当前 Host Goal 已暂停（`goal-e36ac408-ac3b-4b08-975e-68678aa5b952`，phase `paused`）。  
> 时间：2026-11-21 12:55 +08:00 左右；Git 根：`E:/code/Go/CodexRM`；当前会话工作目录此前为 `E:\code\Go\CodexRM\android`。

---

## 1. 原始目标、需求与约束

### 1.1 第一阶段：全量 code review

用户原始请求：

- “全量code review代码，检查正确性和可靠性”
- 范围：`E:\code\Go\CodexRM\android`，覆盖源码、配置、构建与测试，以及 Android 直接依赖的 Go/P2P 入口。
- 要求：按严重程度排序，带文件路径、行号、触发条件、影响、证据、最小修复方向；运行可用验证；未运行项明确标注；**不修改业务代码**。

阶段产出：

- `android/.work/full-code-review-20261121/code-review-report.md`
- `android/.work/full-code-review-20261121/notes.md`
- `android/.work/full-code-review-20261121/final-source-hashes.txt`
- `android/.work/full-code-review-20261121/source-hashes.txt`
- Lint 报告：`android/app/build/reports/lint-results-debug.html` / `.sarif`

### 1.2 第二阶段：全修复

用户随后请求：

- “全修复”
- 目标：修复上述报告中的 **52 项 findings**（5 高 / 36 中 / 11 低与测试可靠性），补齐回归测试，建立修改前基线、独立回滚脚本，并最终重新运行 Android 完整验证、Go/P2P 测试与发布 fixture，全部通过。
- 隐含约束：保留工作树现有未提交改动；不得擅自清理或回滚已有修改。
- 当前用户新指令：**立即中止该任务**，停止一切与原任务相关的代码修改、修复、重构和其他写入操作；仅允许创建/更新 `HANDOFF.md`。

### 1.3 当前最高优先级约束

- **不得继续修复**。
- **不得回滚或清理现有修改**。
- **不得把未验证内容称为已完成**。
- 下一位 Agent 若继续开发，必须获得新的用户指令；本文件只是交接，不代表授权继续修改。

---

## 2. 关键路径与已有产物

### 2.1 审查报告与基线

| 用途 | 路径 |
|---|---|
| 52 项 findings 全文 | `android/.work/full-code-review-20261121/code-review-report.md` |
| 审查过程笔记 | `android/.work/full-code-review-20261121/notes.md` |
| 最终源码哈希（审查阶段） | `android/.work/full-code-review-20261121/final-source-hashes.txt` |
| 修复前基线副本（159 文件） | `android/.work/fix-all-20261121/baseline/` |
| 修复前基线哈希清单 | `android/.work/fix-all-20261121/baseline-manifest.tsv` |
| 独立回滚脚本 | `android/.work/fix-all-20261121/rollback.ps1` |
| 分组 ownership / 跨组契约 / 全局规则 | `android/.work/fix-all-20261121/coordination.md` |
| 本报告 | `HANDOFF.md`（仓库根） |

`coordination.md` 是在 12:09 左右生成的；其中“5 个并行组修复”的计划**未成功完成**，只能作为历史分工参考，不能视为已执行结果。

### 2.2 `changes-*.txt` 状态

`android/.work/fix-all-20261121/` 下当前只有：

- `baseline/`
- `baseline-manifest.tsv`
- `coordination.md`
- `rollback.ps1`

只读检查确认：**没有任何 `changes-*.txt` 文件**。因此 `rollback.ps1` 的 ADD/DELETE 记录为空；它也未在本次任务中运行过。

### 2.3 回滚脚本注意事项

`rollback.ps1` 会将 baseline 中的 159 个文件逐字节复制回仓库，并删除 `changes-*.txt` 中记录的 ADD 文件。它提供 `-VerifyOnly`，但**从未执行**。

风险：

- baseline 时间是 11:58；之后存在 12:00–12:25 的外部并发改动。若运行回滚，这些改动会被覆盖回 11:58 状态。
- 没有 `changes-*.txt`，因此新增但未记录的文件不会被脚本删除。
- `HANDOFF.md` 在 baseline 之后创建，不在 baseline 中，也不会被回滚脚本自动删除。
- 用户明确要求“不擅自回滚或清理现有修改”；下一位 Agent 不应运行该脚本，除非收到新的明确授权。

---

## 3. 第一阶段已完成的审查与验证

### 3.1 审查结论

`code-review-report.md` 共记录 52 项：

- 高严重度 5 项（H1–H5）
- 中严重度 36 项（M1–M36）
- 低严重度 / 测试可靠性 11 项（报告“低严重度与测试可靠性”章节编号 1–11）

完整触发路径、行号、影响和修复方向见报告；本交接文档不重复其全部技术细节。

### 3.2 第一阶段实际执行过的验证

以下结果属于**审查阶段 / 修复任务开始前**，不能代表修复任务开始后的当前代码状态。

#### Android Gradle

早期快照：

```powershell
cd E:\code\Go\CodexRM\android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug --stacktrace
```

结果：`BUILD SUCCESSFUL`，但多数任务为 `UP-TO-DATE`。

强制重跑：

```powershell
.\gradlew.bat testDebugUnitTest --rerun-tasks --stacktrace
```

结果：133 个 JVM 测试，0 failures / 0 errors / 0 skipped。

随后：

```powershell
.\gradlew.bat assembleDebugAndroidTest assembleRelease --stacktrace
```

结果：`BUILD SUCCESSFUL`；Release/R8、资源压缩、`lintVitalRelease` 执行通过。

中间快照曾因并发编辑出现 Kotlin 编译错误（`FileManagerActivity.kt` 的 `olderButton` / `prefix完成` / `prefix已取消` / lambda 类型不匹配等），后来外部并发编辑修复了这些错误。**这些中间失败不是最终修复任务验证结果**。

最终审查快照：

```powershell
.\gradlew.bat compileDebugKotlin --rerun-tasks --console=plain
.\gradlew.bat testDebugUnitTest lintDebug assembleDebugAndroidTest assembleRelease --stacktrace
```

结果：

- `compileDebugKotlin --rerun-tasks`：`BUILD SUCCESSFUL`
- 完整命令：`BUILD SUCCESSFUL`，exit 0
- `testDebugUnitTest`：22 suites，**147 tests，0 failures / 0 errors / 0 skipped**
- `lintDebug`、`assembleDebugAndroidTest`、`minifyReleaseWithR8`、`lintVitalRelease`、`assembleRelease` 均通过。

#### Go / P2P

最终稳定快照曾执行：

```powershell
cd E:\code\Go\CodexRM
go test -race ./internal/p2p -count=1
go test ./internal/p2p -count=20
go vet ./internal/p2p
```

结果：均通过（race 约 1.741s，count=20 约 14.020s，vet exit 0）。

还验证了 Android arm64 `c-shared` 交叉构建、ELF64/AArch64、5 个 JNI 导出符号、16 KB LOAD 对齐；这些结果属于审查阶段，不是修复阶段后的验证。

#### 发布脚本 fixture

审查阶段子代理报告：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\build\scripts\test-release-android.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\build\scripts\test-package-android.ps1
```

结果：两者 exit 0，全部 fixture PASS；使用临时目录和模拟发布，未远程发布、未递增正式版本。

#### 明确未运行

- 真实 Android 设备 / 模拟器 instrumentation
- Camera2、升级安装、SAF、WebView、P2P 实网测试
- API 28/29/30/31/33/34/35/36 设备矩阵
- 进程回收、慢网乱序、多窗口、OEM 兼容动态复现
- 除单列动态复现外的故障注入

---

## 4. 第二阶段“全修复”实际完成的工作

> 重要：本节只描述**已落盘**和**已发生**的事实；所有修复均未经过编译或测试验证，不能称为已验证完成。

### 4.1 基线、回滚与协调

已创建：

- `android/.work/fix-all-20261121/baseline/`（159 文件，11:58 快照）
- `android/.work/fix-all-20261121/baseline-manifest.tsv`
- `android/.work/fix-all-20261121/rollback.ps1`
- `android/.work/fix-all-20261121/coordination.md`

已创建 Host Goal 并随后按用户要求暂停。未运行回滚；未生成可重建 diff 文件。

### 4.2 并行修复子代理

曾启动 5 个子代理：

1. 文件/附件组
2. 网络/更新/扫码组
3. 渲染/公式组
4. 设置/发布/QA runner 组
5. Go/P2P/Gradle 组

**全部子代理在完成任务前 failed**，没有提交最终报告，也没有写入 `changes-*.txt`。它们只报告了若干 12:00 后的外部并发冲突预警和少量调研信息。因此：

- 不能认为这些组完成了任何 finding 修复。
- 不能依据 `coordination.md` 的分工声称某组已交付。
- 后续必须由下一位 Agent 重新核对文件实际内容。

### 4.3 外部并发改动

在 12:00–12:25 期间，工作树出现外部并发编辑。12:34 左右相对 11:58 baseline 的差异文件共 11 个；随后主 Agent 又直接修改了若干文件。截至交接前，只读比对显示相对 baseline 共有 **24 个文件**不同：

```text
android/app/build.gradle.kts
android/app/src/androidTest/java/com/codexrm/mobile/MathRenderingInstrumentation.kt
android/app/src/main/java/com/codexrm/mobile/ApprovalState.kt
android/app/src/main/java/com/codexrm/mobile/ChatListView.kt
android/app/src/main/java/com/codexrm/mobile/ComposerState.kt
android/app/src/main/java/com/codexrm/mobile/DeviceStore.kt
android/app/src/main/java/com/codexrm/mobile/FileManagerActivity.kt
android/app/src/main/java/com/codexrm/mobile/MainActivity.kt
android/app/src/main/java/com/codexrm/mobile/MessageAdapter.kt
android/app/src/main/java/com/codexrm/mobile/MessageImagesPlugin.kt
android/app/src/main/java/com/codexrm/mobile/PendingSend.kt
android/app/src/main/java/com/codexrm/mobile/ProtocolFormatter.kt
android/app/src/main/java/com/codexrm/mobile/ScannerActivity.kt
android/app/src/main/java/com/codexrm/mobile/SettingsPage.kt
android/app/src/main/java/com/codexrm/mobile/ThreadListState.kt
android/app/src/main/java/com/codexrm/mobile/TranscriptState.kt
android/app/src/main/java/com/codexrm/mobile/Ui.kt
android/app/src/test/java/com/codexrm/mobile/ApprovalStateTest.kt
android/app/src/test/java/com/codexrm/mobile/ComposerStateTest.kt
android/app/src/test/java/com/codexrm/mobile/ImageFileCacheTest.kt
android/app/src/test/java/com/codexrm/mobile/OutboxTest.kt
android/app/src/test/java/com/codexrm/mobile/ThreadListStateTest.kt
android/app/src/test/java/com/codexrm/mobile/TranscriptStateTest.kt
```

其中**外部并发改动**（非主 Agent 修复）包括但不限于：

| 文件 | 外部改动摘要 |
|---|---|
| `app/build.gradle.kts` | `versionCode 41→42`、`versionName "1.0.40"→"1.0.41"` |
| `MathRenderingInstrumentation.kt` | 新增 `checkHistoryPrepend()` 等较大段 instrumentation 代码 |
| `ChatListView.kt` | 新增 `scrollToMessage(position)`；`onLayout` 中 groupHeader 定位回退 |
| `FileManagerActivity.kt` | `@Deprecated` 注解；`action.run?.invoke()` 改为 `action.run.invoke()` |
| `MainActivity.kt` | `chooseModel(row.option!!)` 改为 `chooseModel(row.option)`；新增 `prependHistory()`；`scrollToMessage()` 调用 |
| `MessageAdapter.kt` | `prepend()` 中迁移展开状态到 `expandedGroupMessageIds` |
| `MessageImagesPlugin.kt` | `getOpacity()` 增加 `@Deprecated` |
| `ScannerActivity.kt` | `openSession()` 增加 `@Suppress("DEPRECATION")` |
| `SettingsPage.kt` | 多处 `@Deprecated` / `@Suppress` / `save` 局部变量类型调整 |
| `Ui.kt` | `configureSystemBars30()` 增加 `@Suppress("DEPRECATION")` |
| `ImageFileCacheTest.kt` | `file.parentFile.canonicalFile` 改为 `file.parentFile?.canonicalFile` |

这些改动是否来自同一项目流程、是否完整、是否与 52 项 findings 有重叠，**未验证**。下一位 Agent 必须保留它们，除非收到明确回滚授权。

### 4.4 主 Agent 已落盘的纯逻辑修改（均未经编译/测试）

| 文件 | 落盘修改 | 关联 finding | 状态 |
|---|---|---|---|
| `ComposerState.kt` | `afterFailedSend` 增加 `submitted in current -> current`，避免编辑插入在提交文本前时重复 prepend | M9 | 已落盘，未验证 |
| `PendingSend.kt` | `OutboxState.restore(values)` 新增，用于持久化失败时回滚内存状态 | M21 辅助 | 已落盘，未验证；`MainActivity` 尚未调用 |
| `ApprovalState.kt` | `ApprovalFilter.visible()` 改为要求 `scope.threadId` 非空且精确等于当前 thread；缺失归属被隔离 | H3 | 已落盘，未验证；尚未实现“独立未定位请求恢复/重同步流程” |
| `ProtocolFormatter.kt` | `fromItem(item, stableId: String = "")`；无 id 时优先使用 stableId，否则用 `item.toString().hashCode()` | M10 | 已落盘，未验证 |
| `TranscriptState.kt` | `TurnRenderer.messages()` 为无 id item 生成 `"$turnId:item:$index"` 并写回 item；调用 `fromItem(item, stableId)` | M10 | 已落盘，未验证；注意它会 mutate 传入 JSONObject |
| `DeviceStore.kt` | `savePendingSends(...)` 返回值改为 `Boolean`（`editor.commit()` 结果） | M21 | 部分；`MainActivity.persistOutbox()` 仍忽略返回值 |
| `ThreadListState.kt` | 重写：`mergePage()` 返回候选列表且不再直接改 `entries`；新增 `commit(values)` | M11 | 已落盘，未验证；`UiReviewCheck.kt` 仍按旧副作用调用 |
| `ApprovalStateTest.kt` | 重写：新增缺失 threadId 不得跨会话可见的断言 | H3 | 已落盘，未运行 |
| `ComposerStateTest.kt` | 增加 `new\noriginal` 已包含提交文本时不重复 prepend 的断言 | M9 | 已落盘，未运行 |
| `OutboxTest.kt` | 增加 `OutboxRecovery` 同类断言和 `OutboxState.restore()` 测试 | M9 / M21 | 已落盘，未运行 |
| `ThreadListStateTest.kt` | 重写为 `commit(state.mergePage(...))`；新增“未完成分页不替换已提交列表” | M11 | 已落盘，未运行 |
| `TranscriptStateTest.kt` | 新增无 id item 跨重叠页稳定身份测试 | M10 | 已落盘，未运行 |

### 4.5 主 Agent 已落盘的 `MainActivity.kt` 部分修改（未经编译/测试，且明显不完整）

`MainActivity.kt` 当前总行数约 5235（停止前读到的版本）。已落盘的关键修改：

1. 新增字段：
   - `pairOperation`
   - `outboxOwnerAgentId`
   - `pendingOpenThread: Pair<String, String>?`
   - `pendingShares = ArrayDeque<Intent>()`
   - `bootstrapListVersion`
   - `conversationResults = LinkedHashMap<Int, AlertDialog>()`
   - `nextConversationPanelId`

2. `onCreate()`：
   - 在 `AttachmentStore(this)` 后调用 `attachmentStore.cleanupStaleFiles()`。
   - 设置 `outboxOwnerAgentId = currentAgent()`。

3. `connectPair()`：
   - 引入 `pairOperation` 序号。
   - 用 `val call = Network.request(...)` 保存 Call 身份。
   - 回调检查 `isDestroyed || operation != pairOperation || pairCall !== call`。
   - 成功后先 `disconnect()` 再切换 `device`，再 `showMain(reset = true)`。
   - `pairCall = call` 在 `Network.request(...)` 返回后赋值；是否存在回调早于赋值的竞态**未验证**。

4. `showPair()` / `onDestroy()`：
   - 增加 `pairOperation++`、`pairCall?.cancel()`、`pairCall = null`。

5. `showMain()`：
   - 在非空设备后设置 `outboxOwnerAgentId = currentAgent()`。

6. `selectDevice()`：
   - 在修改 `device` 前调用 `disconnect()`，意图修复 H2 的持久化顺序。
   - 但 `showMain(reset = true)` 仍会再次调用 `disconnect()`；双重断开未验证影响。

7. `onAuthorizationExpired()`：
   - 切换设备前调用 `disconnect()`；无可用设备时也会 `disconnect()` 后进入配对页。

8. `disconnect()`：
   - 增加 `bootstrapListVersion = 0`
   - `pendingOpenThread = null`
   - `conversationResults.values.forEach { it.dismiss() }`
   - `conversationResults.clear()`
   但 `conversationResults` 尚未在任何面板创建处填充。

9. `bootstrap()`：
   - `loadThreads(..., reopenCurrent = true).also { bootstrapListVersion = it }`
   - 依赖 `loadThreads` 返回 `Int`。

10. `loadThreads()`：
    - 重写为返回 `Int`。
    - 增加 `bootstrapListVersion` 与 `authoritative` 判断，意图避免普通事件刷新取消 bootstrap 完成路径（M1）。
    - 使用 `threadList.mergePage(accumulated, page)` 返回的 `staged`，仅在 `nextCursor == null` 完整成功后 `threadList.commit(staged)`（M11）。
    - 重复 cursor 或 20 页上限会显示可重试错误，不再当作成功。
    - 末尾调用 `drainPendingOpen()`。
    - **该函数当前调用未定义方法 `drainPendingOpen()`，预计编译失败。**

11. `openThread()`：
    - 忙态时调用 `deferOpenThread(id)`；该方法未定义。
    - 成功分支前新增 `previousBinding`、`previousToken`、`targetBinding`、`targetToken`。
    - 调用 `pendingOpenState = PendingOpenState(...)`；`pendingOpenState` 字段和 `PendingOpenState` 类型均未定义。
    - `failOpenThread()` 仍是旧实现（`MainActivity.kt:1784-1797` 附近）：仍无条件 `saveDraft()`、把 `pendingText` 塞回旧绑定。因此 H1 尚未修复。

12. `persistOutbox()`：
    - 仍调用 `store.savePendingSends(currentAgent(), outbox.values())` 并忽略返回的 Boolean。
    - `outboxOwnerAgentId` 字段已加入但未使用；H2/M21 仍不完整。

13. `toggleTheme()`：
    - 仍直接 `buildMain()` 新建 Adapter，没有调用 `adapter.dispose()`；M16 未修复。

14. `acceptExternalIntent()` / `reviewSharedDraft()`：
    - 仍使用单槽 `pendingShare`；`pendingShares` 队列未使用；M4/M5 未修复。

15. `currentBackTarget()`：
    - 仍使用 `!mainVisible && devices.isNotEmpty()`；low1 未修复。

16. `showUserInput()` / `showRawResponse()` / `respondResult()`：
    - 仍是自动 dismiss 的 positive button；`respondResult()` 仍返回 `Unit`；M8 未修复。

17. `openCachedThread()`：
    - 未重置 `running` / `activeTurnId` / `startedAt`；M7 未修复。

18. `upsertItem()` / `reconcileUserMessage()`：
    - 未见保留 `turnId` 的修改；M6 未修复。

---

## 5. 当前代码实际状态

### 5.1 总体结论

- 第一阶段 code review 已完成并交付报告。
- 第二阶段“全修复”**未完成**。
- 5 个并行修复子代理全部失败，没有可采信的最终交付。
- 主 Agent 只落盘了一部分纯逻辑修改和一批**明显不完整的 `MainActivity.kt` 修改**。
- 停止请求之后没有再做任何代码修改；仅创建本 `HANDOFF.md`。
- 没有执行回滚，没有清理现有修改。

### 5.2 预计存在的编译 / 接口问题

以下为**只读 grep/read 推断，未运行编译器验证**；不能声称是完整编译错误列表：

1. `MainActivity.kt:314` 调用 `attachmentStore.cleanupStaleFiles()`，但全项目 grep 未发现 `AttachmentStore.cleanupStaleFiles()` 定义。  
   - 原因：files 组失败，跨组契约未实现。
   - 预计：`Unresolved reference: cleanupStaleFiles`。

2. `MainActivity.kt:1378` 调用 `drainPendingOpen()`，未发现定义。  
   - 预计：`Unresolved reference: drainPendingOpen`。

3. `MainActivity.kt:1651` 调用 `deferOpenThread(id)`，未发现定义。  
   - 预计：`Unresolved reference: deferOpenThread`。

4. `MainActivity.kt:1666` 使用 `pendingOpenState` 字段和 `PendingOpenState(...)` 类型，两者均未定义。  
   - 预计：`Unresolved reference: pendingOpenState` / `Unresolved reference: PendingOpenState`。

5. `MessageAdapter.dispose()` 未实现，`toggleTheme()` 也未调用；M16 修复未开始。  
   - 这不是当前已引用的编译错误，但属于跨组契约缺失。

6. `ThreadListState.mergePage()` 已从“直接改 `entries`”改为“返回候选列表，需 `commit()`”。  
   - `MainActivity.loadThreads()` 已按新签名使用返回值并 `commit()`。
   - `androidTest/UiReviewCheck.kt:245` 仍调用 `threads.mergePage(...)` 并忽略返回值；不会一定有编译错误，但该 UI fixture 可能不会填充 `ThreadListState.entries`，行为已改变，需复核。
   - `MainActivity` 中 `loadThreads(generation, threadCursor, threads)` 仍把已提交的 `threads` 作为 accumulated，可能与“分页 staged 列表”语义不一致，需复核。

7. `DeviceStore.savePendingSends()` 返回 `Boolean`，但 `MainActivity.persistOutbox()` 忽略返回值；M21 只完成了一半。

8. `ProtocolFormatter.fromItem()` 新增可选参数 `stableId`，默认参数可保持多数调用点编译；但 `TranscriptState.TurnRenderer.messages()` 会 mutate 传入的 `JSONObject`（写入稳定 id），这是设计决策，需复核是否影响其他共享对象。

9. `connectPair()` 中 `pairCall = call` 在 `Network.request(...)` 返回后赋值；回调在主线程 post，通常赋值先发生，但**未动态验证**是否存在回调早于赋值导致 `pairCall !== call` 的竞态。

10. `MainActivity.disconnect()` 现在会 dismiss/clear `conversationResults`；该 map 没有被任何面板注册，属于未完成字段。

11. `outboxOwnerAgentId`、`pendingShares`、`pendingOpenThread`、`conversationResults`、`nextConversationPanelId` 中多个字段已声明但未完整接入；这是明显的部分完成状态。

### 5.3 `git status` 与工作树

`git status --short`（仓库根）显示大量原有未提交/未跟踪文件，包括本任务之前就存在的：

- 根目录 `README.md`, `build/scripts/*.ps1`, `cmd/...`, `internal/...`, `web/...` 等大量修改
- 多个 OneBot / wxbot 相关新增目录与文件
- `.qoder-input-images/`, `.web-reverse-*`, `artifacts/` 等
- Android 模块下大量 `M` / `??` 文件

这些不属于本交接任务创建的基线清理范围；**不要清理**。

本任务直接修改/创建的相关文件见 §4.4、§4.5。只读状态显示 `ThreadListState.kt` 与 `ThreadListStateTest.kt` 相对 Git 仍是 `??`（原本就是未跟踪文件），但 baseline 中已保存 11:58 版本。

---

## 6. 52 项 findings 当前状态清单

> 状态定义：
> - **已落盘（未验证）**：代码/测试已写入，但没有编译或运行验证，不能称为完成。
> - **部分**：只完成部分路径，仍有明显缺口或未定义引用。
> - **未处理**：未发现本任务针对该 finding 的落盘修复；外部并发改动可能相关但未确认。
> - 所有结论均为静态核对；没有运行过修复后的构建/测试。

### 高严重度

| ID | 摘要 | 状态 |
|---|---|---|
| H1 | 打开会话失败清空目标草稿 / 串写旧会话 | **部分**；`openThread` 开始保存 previous/target binding，但 `PendingOpenState` 缺失，`failOpenThread` 仍旧逻辑 |
| H2 | 发送中切换设备把旧 outbox 写到新设备 key | **部分**；设备切换前加了 `disconnect()`，但 `persistOutbox()` 仍用 `currentAgent()`，`outboxOwnerAgentId` 未使用 |
| H3 | 缺失 `threadId` 的审批跨会话可见/可提交 | **已落盘（未验证）**；`ApprovalFilter.visible()` 要求精确 thread；测试已改；独立恢复流程未实现 |
| H4 | `uiReview` 可清空正式应用配对 | **未处理** |
| H5 | 本地复制/移动竞争覆盖或递归误删 | **未处理** |

### 中严重度

| ID | 摘要 | 状态 |
|---|---|---|
| M1 | RPC 事件取消 bootstrap 唯一完成路径 | **部分**；`loadThreads`/`bootstrapListVersion` 重写，但依赖未定义 `drainPendingOpen()`，未验证 |
| M2 | 配对陈旧回调改变新页面/设备 | **已落盘（未验证）**；`pairOperation` + Call 身份 + destroyed 检查；回调竞态未验证 |
| M3 | cwd 未初始化时分享附件消失 | **未处理** |
| M4 | 分享 Intent 忙态滞留 / 被覆盖 | **未处理**；`pendingShares` 字段未使用 |
| M5 | 忙态点击任务通知不打开线程 | **未处理**；`deferOpenThread` 未实现 |
| M6 | reconcile 后丢失 `turnId` | **未处理** |
| M7 | 离线缓存会话继承 running/activeTurnId | **未处理** |
| M8 | 审批输入校验/发送失败仍自动关闭 | **未处理** |
| M9 | 失败发送 / outbox 恢复复制提交文本 | **已落盘（未验证）**；`ComposerText.afterFailedSend` + 测试 |
| M10 | 无 item.id 使用对象 hash 导致重复 | **已落盘（未验证）**；stableId + `TurnRenderer` + 测试 |
| M11 | 会话列表分页失败/游标异常截断 | **已落盘（未验证）**；`ThreadListState.commit()` + `loadThreads` staged 提交 + 测试；`UiReviewCheck` 需复核 |
| M12 | 新建供应商结果未知后重试重复创建 | **未处理** |
| M13 | 搜索/归档/关联分支面板重连后旧 generation | **未处理**；`conversationResults` map 未接入 |
| M14 | 转义末尾分隔符使块级公式误判 | **未处理** |
| M15 | 展开工具组共享 dirty，离屏头行旧状态 | **未处理**；外部并发改动涉及 `prepend()` 展开状态迁移，但不等于 M15 修复 |
| M16 | 流式中切换主题旧 Adapter 覆盖 rendered | **未处理**；`MessageAdapter.dispose()` 未实现 |
| M17 | Mermaid 源码变化后仍显示旧图 | **未处理** |
| M18 | Camera2 异步回调漏捕 `CameraAccessException` | **未处理**；`ScannerActivity` 仅外部加了 `@Suppress` |
| M19 | 预览打开后仍用全局 `setTorchMode` | **未处理** |
| M20 | 同版本 APK 取消后重试共享文件 | **未处理** |
| M21 | outbox `commit()` 失败被忽略 | **部分**；`DeviceStore` 返回 Boolean，但 `persistOutbox()` 未处理失败/回滚 |
| M22 | ZIP 输出位于源目录内包含自身 | **未处理** |
| M23 | 压缩时源项目消失被静默跳过 | **未处理** |
| M24 | 解压无条目/大小上限 | **未处理** |
| M25 | 解压失败留下部分结果且覆盖原文件 | **未处理** |
| M26 | 普通目录列举未应用 2000 项上限 | **未处理** |
| M27 | 目录对比截断/读取失败仍误报一致 | **未处理** |
| M28 | 递归搜索取消不停止旧遍历 | **未处理** |
| M29 | 终端 60 秒超时对持续输出不可达 | **未处理** |
| M30 | 图片缓存返回文件实际使用期不受保护 | **未处理** |
| M31 | 附件/图片缓存崩溃残留回收 | **部分**；`MainActivity.onCreate()` 调用了跨组契约方法，但 `AttachmentStore.cleanupStaleFiles()` 不存在，预计编译失败 |
| M32 | NAT 混合重复端口误判 linear | **未处理** |
| M33 | 原生写侧故障/队列溢出无声取消 | **未处理** |
| M34 | Gradle 未跟踪 Go 工具链/环境 | **未处理**；`app/build.gradle.kts` 只有外部 version bump |
| M35 | 发布校验接受有效但错误签名证书 | **未处理** |
| M36 | 版本文件初始写入失败误判并发编辑 | **未处理** |

### 低严重度 / 测试可靠性

| 编号 | 摘要 | 状态 |
|---|---|---|
| 1 | 全部设备失效后 Back 循环 | **未处理**；`currentBackTarget()` 仍旧逻辑 |
| 2 | 服务器 base path 二次 percent-encoding | **未处理** |
| 3 | 相机断开/配置失败清理不完整 | **未处理** |
| 4 | 带 authority 的 `file:` URI 丢主机名 | **未处理** |
| 5 | 下载空间预检只算一份 | **未处理** |
| 6 | 覆盖 SAF 映射遗漏释放旧树授权 | **未处理** |
| 7 | BuildOnly 文档错称不需要 Go | **未处理** |
| 8 | 单行成组时丢失展开状态 | **部分 / 外部并发改动相关，未验证**；`MessageAdapter.prepend()` 有迁移逻辑，但通用分组边界未复核 |
| 9 | 配对测试可被旧 Call 假通过 | **未处理** |
| 10 | Mermaid full-screen 测试可读取内联 WebView 假通过 | **未处理** |
| 11 | FileProvider `root-path` 过宽 | **未处理** |

---

## 7. 已执行测试、结果、未执行验证（修复阶段）

### 7.1 修复阶段实际执行过的内容

- 建立了 baseline 与哈希清单；未运行验证脚本。
- 启动了 5 个子代理；全部失败，没有完成修复。
- 各组仅做了只读冲突检查 / 调研；未提交有效最终测试。
- 主 Agent 落盘了 §4.4、§4.5 的代码修改。
- 停止请求后只做了只读 `grep` / `read` / `git status` / `Get-FileHash` 比对，并写入本 `HANDOFF.md`。

### 7.2 修复阶段明确 NOT_RUN

以下在修复任务开始后**全部没有运行**：

- `.\gradlew.bat compileDebugKotlin`
- `.\gradlew.bat testDebugUnitTest`
- `.\gradlew.bat lintDebug`
- `.\gradlew.bat assembleDebugAndroidTest`
- `.\gradlew.bat assembleRelease`
- `go test -race ./internal/p2p -count=1`
- `go test ./internal/p2p -count=20`
- `go vet ./internal/p2p`
- `buildDirectLibraries` 两次 up-to-date 验证
- `test-release-android.ps1`
- `test-package-android.ps1`
- `rollback.ps1 -VerifyOnly`
- 生成修复 diff / 哈希清单
- 真机 instrumentation
- 任何针对本阶段修改的单元/集成/设备测试

因此：

- **不能声称当前代码可编译**。
- **不能声称任何修复已通过测试**。
- **不能声称 52 项已全部修复**。
- 当前代码存在多个只读检查可推断的未解析引用，预计 `compileDebugKotlin` 会失败；但具体错误列表未经编译器确认。

---

## 8. 已知风险与注意事项

1. **部分完成的 `MainActivity.kt` 是最大风险**：它引用了多个未定义类型/方法，且 H1 的 `failOpenThread()` 仍保持旧的错误回滚逻辑。
2. **跨组契约未实现**：`AttachmentStore.cleanupStaleFiles()`、`MessageAdapter.dispose()`、`CodexClient.nativeFailure()` reason 映射、Go/P2P/Gradle 修改均未落地。
3. **`ThreadListState` 接口语义变更**：`mergePage()` 不再有副作用，必须所有调用点显式 `commit()`；`UiReviewCheck.kt` 未同步。
4. **`DeviceStore.savePendingSends()` 返回 Boolean 但调用方忽略**：持久化失败仍可能继续清草稿/附件。
5. **外部并发改动可能被回滚脚本覆盖**：baseline 是 11:58，之后有多处外部编辑；不要运行回滚，除非用户重新授权并确认接受丢失这些改动。
6. **工作树有大量与本任务无关的原有无提交/未跟踪文件**：不要清理、不要 `git clean`、不要 `git reset --hard`。
7. **`coordination.md` 中的 5 组分工已失效**：子代理全部失败；下一位 Agent 应重新按实际文件状态分派或直接修复。
8. **`changes-*.txt` 不存在**：回滚脚本的 ADD/DELETE 增删记录为空；不要据此推断“没有新增文件”。
9. **`HANDOFF.md` 是本次停止请求后唯一新写入的项目文件**，在 baseline 之后创建。

---

## 9. 下一位 Agent 可直接执行的后续步骤（按依赖与优先级）

> 仅在收到新的用户指令后执行。以下步骤是交接建议，不是当前授权。

### 步骤 0：先只读核对，不要立刻写文件

1. 阅读本 `HANDOFF.md`。
2. 阅读 `android/.work/full-code-review-20261121/code-review-report.md` 全部 52 项。
3. 阅读 `android/.work/fix-all-20261121/coordination.md`，但把它当作历史计划。
4. 运行只读状态检查：

```powershell
cd E:\code\Go\CodexRM
git status --short
Get-ChildItem -LiteralPath 'android\.work\fix-all-20261121' -Force
Get-Content -LiteralPath 'android\.work\fix-all-20261121\baseline-manifest.tsv' -TotalCount 5
```

5. 计算当前相对 baseline 的差异：

```powershell
$root='E:\code\Go\CodexRM'
$base=Join-Path $root 'android\.work\fix-all-20261121\baseline'
Get-ChildItem -LiteralPath $base -Recurse -File | ForEach-Object {
  $rel=$_.FullName.Substring($base.Length+1)
  $cur=Join-Path $root $rel
  if(Test-Path -LiteralPath $cur -PathType Leaf){
    if((Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $cur -Algorithm SHA256).Hash){ $rel }
  }
} | Sort-Object
```

6. **不要运行 `rollback.ps1`**，除非用户明确要求回滚并接受丢失 12:00 后外部改动。

### 步骤 1：先恢复可编译状态（最高优先级）

必须先解决预计的编译 blocker，否则无法验证任何修复：

1. 实现或移除 `AttachmentStore.cleanupStaleFiles()` 调用。  
   - 若保留 M31 修复，需在 files 组 ownership 内实现该方法并补测试。
   - 若用户授权简化，也可暂时移除 `MainActivity.onCreate()` 的调用；但这会放弃 M31 的接入点。
2. 定义 `PendingOpenState` 数据类和 `pendingOpenState` 字段，或移除 `openThread()` 中相关未完成代码。
3. 实现 `deferOpenThread()` 与 `drainPendingOpen()`，或移除 `loadThreads()` / `openThread()` 中对它们的调用。
4. 完成 H1：`failOpenThread()` 必须使用 `pendingOpenState` 的 previous/target binding 与 token；不得无条件 `saveDraft()` 目标空草稿，不得把 target `pendingText` 写回旧 binding。
5. 实现 `MessageAdapter.dispose()` 并在 `toggleTheme()` 的 `buildMain()` 前调用（M16 跨组契约）。
6. 检查 `ThreadListState.mergePage()` 全部调用点：
   - `UiReviewCheck.kt:245` 应改为 `threads.commit(threads.mergePage(...))` 或等价。
   - `MainActivity.loadThreads(generation, threadCursor, threads)` 应确认 accumulated 是否应为 staged 列表，而非已提交 `threads`。
7. 处理 `DeviceStore.savePendingSends(): Boolean`：
   - `persistOutbox()` 应检查返回值。
   - 失败时回滚内存 outbox（`OutboxState.restore()` 已提供）并阻止推进发送/清除草稿/附件。
8. 完成或移除 `outboxOwnerAgentId`：若保留，所有 outbox 持久化应显式使用它；否则删除字段避免误导。
9. 完成或移除 `pendingShares` / `pendingOpenThread` / `conversationResults` / `nextConversationPanelId`：避免声明未使用字段和半成品状态机。

然后先跑最小编译：

```powershell
cd E:\code\Go\CodexRM\android
.\gradlew.bat compileDebugKotlin --rerun-tasks --console=plain
```

### 步骤 2：按优先级完成剩余 findings

建议顺序：

1. H1、H2、H3、H4、H5（数据损坏 / 越权 / 测试破坏正式数据）
2. M1–M13（状态机、草稿、Intent、审批、分页、供应商、重连面板）
3. M14–M17（公式、分组 dirty、主题 Adapter、Mermaid）
4. M18–M21（相机、更新下载、持久化）
5. M22–M31（文件系统、图片缓存、搜索/终端、空间/SAF）
6. M32–M36（Go/P2P、Gradle、发布脚本）
7. 低严重度 1–11

每完成一组，补对应回归测试。不要只增加正常流 fixture；报告明确要求竞态、失败路径、缺失归属、转义输入、ZIP 自包含、取消、进程恢复等断言。

### 步骤 3：重新运行完整验证

编译通过后执行：

```powershell
cd E:\code\Go\CodexRM\android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebugAndroidTest assembleRelease --stacktrace
```

Go/P2P：

```powershell
cd E:\code\Go\CodexRM
go test -race ./internal/p2p -count=1
go test ./internal/p2p -count=20
go vet ./internal/p2p
```

Gradle `BuildDirectLibraries` 增量验证（M34）：

```powershell
cd E:\code\Go\CodexRM\android
.\gradlew.bat buildDirectLibraries --console=plain
.\gradlew.bat buildDirectLibraries --console=plain
```

发布脚本 fixture：

```powershell
cd E:\code\Go\CodexRM
powershell -NoProfile -ExecutionPolicy Bypass -File .\build\scripts\test-release-android.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File .\build\scripts\test-package-android.ps1
```

### 步骤 4：重新审查与交付

1. 重新生成修复后源码哈希清单。
2. 生成可重建 diff（相对 baseline 与相对 Git HEAD 两份更稳妥）。
3. 在另一份工作副本上验证 `rollback.ps1 -VerifyOnly` 与实际回滚；用 `cmp` / `Get-FileHash` 确认逐字节一致。
4. 重新阅读 52 项报告，逐项标注“已修复 / 部分 / 不修及理由 / 需设备验证”。
5. 明确列出 NOT_RUN 项：真机 instrumentation、API/OEM 矩阵、真实 RM/P2P 网络、升级安装等。
6. 若用户要求继续，再交付最终报告；不要把静态修复等同于运行时验证。

---

## 10. 不确定 / 未确认信息清单

- 12:00–12:25 的外部并发改动来源、完整意图、是否与其他 Agent 或用户操作相关：**未确认**。
- 外部并发改动是否已部分修复 low8 / 历史 prepend 相关问题：**未确认**。
- 当前 `MainActivity.kt` 的 `pairCall = call` 赋值与异步回调之间是否存在竞态：**未动态验证**。
- 当前代码具体编译错误列表：只根据只读 grep/read 推断，**未运行编译器**。
- 修复阶段所有新增/修改代码是否通过任何测试：**未运行，结果未知**。
- 子代理失败前是否在临时目录写入过其他非项目文件：**未确认**；只读检查未发现 `changes-*.txt`。
- `rollback.ps1` 是否能在当前含大量外部改动的工作树上安全运行：**未验证**。
- `HANDOFF.md` 创建前是否存在同名文件：只读 `glob` 返回 `No files found`，创建时为新文件。

---

## 11. 当前会话停止状态

- 已按用户要求停止后续开发。
- 未继续修复、重构、回滚或清理。
- 未修改除 `HANDOFF.md` 外的项目文件（停止请求之后）。
- 当前 Host Goal 已暂停。
- 下一位 Agent 必须以新的用户指令为继续开发的前提。

