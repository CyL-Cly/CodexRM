# 工作交接文档：Android MT 风格本地文件管理器

> 2026-11-21 续接结果。Android 1.0.42（43）已通过原更新渠道发布。Android 16 的本地文件、界面与直连文件验收通过。 检查记录和逐项状态见 [本轮验证与交付记录](../.work/handoff-completion-20261121/notes.md)。

> 生成时间：2026-11-21 12:54 +08:00（Asia/Singapore）
> 文档位置：`E:\code\Go\CodexRM\android\HANDOFF.md`（Android 工程根目录，即本会话工作目录）
> Git 仓库根：`E:\code\Go\CodexRM`（本轮未在仓库根创建第二份副本，以避免额外写入）
> 面向读者：没有本会话上下文、需要接手本任务的 Agent

---

## 0. 本轮指令边界（务必先读）

用户最新指令为：

1. 先出现两次「发布版本」；
2. 随即明确要求：**立即中止当前开发任务，停止一切与原任务相关的代码修改、修复、重构和其他写入操作**；
3. **现在仅执行工作交接**，除创建或更新 `HANDOFF.md` 外，不得修改任何项目文件；
4. 不得继续执行原开发任务，不得擅自回滚或清理现有修改，不得为了完善交接文档而进行额外开发；
5. 无法确认的信息必须明确标注，不得猜测或编造。

本轮实际执行的动作：

- 仅做**只读**核对（`git status`、`git diff --numstat`、文件读取、`Get-FileHash`、`Test-Path`、`tar -tf`、测试报告 XML 统计）；
- 仅创建本文件 `HANDOFF.md`；
- **未执行**：任何构建、测试、代码修改、发布、签名、上传、版本递增、回滚、清理、设备操作。

因此：**「发布版本」这一动作本轮未执行**。版本与构建产物在交接间隔内发生的变化来源无法确认，见第 6 节。

---

## 1. 原始目标、需求与约束

### 1.1 原始用户输入

用户提供了 5 张 MT Manager（MT 文件管理器）手机截图（1280x2800，JPEG），原话为：

> 安卓完成文件管理功能，和MT那样完全一致

截图内容（按顺序）覆盖：

1. 竖屏**左右双栏**目录列表，顶栏显示路径、`文件夹: 25 文件: 32 已选: 5 储存: 96.18G/220.15G`，蓝色高亮多选行，底部 5 个图标按钮；
2. 底部弹出的**「最近 / 书签」抽屉**（含历史路径与文件条目）；
3. 非选择态双栏 + 底部导航按钮（后退、前进、新建、交换窗口、上一级）；
4. 长按弹出的**双列操作菜单**：`<- 复制`、`<- 移动`、`删除`、`重命名`、`工具`、`压缩`、`属性`、`分享`、`文件夹对比`、`添加书签`，并有「带 ● 的菜单表示可以长按触发单窗口操作」提示；
5. 右上角**溢出菜单**：刷新、搜索、全选、过滤、排序方式、打开终端、隐藏文件、添加书签、设为首页、交换窗口、设置、退出。

### 1.2 需求解读（本次实际采用的范围）

- 在现有 CodexRM Android 客户端内提供**无需电脑连接**即可使用的本地文件管理器；
- 竖屏仍保持左右双栏；
- 支持路径导航、前进/后退、上级、隐藏文件、排序、过滤、递归搜索；
- 支持多选/全选/反选、跨栏复制移动、新建、删除、重命名、ZIP 压缩解压、属性、分享、书签、最近、文件夹对比、本地终端；
- 界面结构与菜单对齐上述截图；
- 通过单元测试、Debug/Release 构建、ARM64 真机验证。

**重要**：「和 MT 那样完全一致」这一表述**未被完全满足**。本次只实现了截图可见的本地文件管理核心功能，MT 的 APK/DEX 编辑、root 操作、插件生态、RAR/7z 等格式、内置文本/图片编辑器等均**未实现**（见第 8 节）。这一点在下一位 Agent 与用户确认范围时必须先澄清。

### 1.3 环境与约束

| 项 | 值 |
| --- | --- |
| 会话工作目录 | `E:\code\Go\CodexRM\android` |
| Git 仓库根 | `E:\code\Go\CodexRM` |
| 工程 | 原生 Kotlin + Android View，无 Compose / AppCompat / Material |
| `minSdk` / `targetSdk` / `compileSdk` | 28 / 35 / 36 |
| ABI | 仅 `arm64-v8a` |
| 构建工具 | JDK 17+、Go 1.24+、NDK 28.2.13676358、Gradle Wrapper |
| DSH 权限上下文 | `danger-full-access`，审批提示已禁用（无需请求 escalation） |
| 项目约束文件 | `$DSH_HOME/AGENTS.md`（工程分析工作笔记）：JIT harness、真实执行、禁编造工具输出、修改类任务需基线/diff/回滚脚本、每阶段写入 `.work/<case>/notes.md` |
| 系统级约束 | 不得删除 `.codebuddy` 目录；中文短句、命令英文 |

### 1.4 实现期间自行约定的边界

- **保留**原有「电脑 / 手机」SAF 文件窗口（`WorkspaceFilesDialog`）与电脑→手机 P2P 下载链路（`WorkspaceFileUi` / `WorkspaceDownloads`），不改造其行为；
- 新增独立 `FileManagerActivity` 作为本地双栏管理器，从「设置」和原文件窗口菜单进入；
- 工作树原本有**大量并行未提交改动**（见 6.1），只做精确插入，不用整文件覆盖；
- Android 11+ 使用 `MANAGE_EXTERNAL_STORAGE`（系统设置授权），未授权时显示授权页，不绕过系统权限；
- 不引入任何新的第三方依赖，复用 `Ui` / `IconView` / `WorkspaceDownloads.mime()`。

---

## 2. 已完成的工作

### 2.1 新增文件

| 路径 | 内容与规模 |
| --- | --- |
| `app/src/main/java/com/codexrm/mobile/FileManagerActivity.kt` | 本地文件管理器 Activity（PowerShell 统计 1087 行；历史 grep 曾报告最高 1149 行，两者不一致，原因未确认，可能与换行符/编码统计方式有关）。含 MT 深色双栏、顶栏路径与统计、底部五键、双列操作菜单、最近/书签抽屉、授权页、进度/取消对话框、无 root 本地终端、`Pane`/`FileRow`/`LibraryRow` 内部类、`background()` 后台操作框架。 |
| `app/src/main/java/com/codexrm/mobile/LocalFileStore.kt` | 纯 JVM 可测的文件操作层（368 行）：`list` / `search` / `createDirectory` / `createFile` / `rename` / `uniqueTarget` / `copyOrMove` / `delete` / `stats` / `zip` / `unzip` / `compare`，以及 `LocalSort`、`LocalEntry`、`LocalStats`、`LocalOperationCanceled`。 |
| `app/src/test/java/com/codexrm/mobile/LocalFileStoreTest.kt` | 6 个 JVM 单元测试（110 行）：列表/过滤/排序/递归搜索、创建/重命名/复制/移动/删除、禁止复制到自身子目录、取消复制清理半成品、ZIP 往返与 `../` 穿越防护、目录对比差异分类。 |
| `app/src/main/res/xml/local_file_paths.xml` | `LocalFileProvider` 的 FileProvider 路径配置：`<root-path name="shared_storage" path="." />`。 |
| `app/src/debug/AndroidManifest.xml` | Debug 源集清单覆盖：`tools:replace="android:exported"` 把 `FileManagerActivity` 设为 `exported="true"`，**仅用于 `adb shell am start` 真机 QA**。 |

### 2.2 修改文件（均为精确插入，未覆盖整文件）

| 路径 | 本任务插入内容 | 当前状态（只读核对） |
| --- | --- | --- |
| `app/src/main/AndroidManifest.xml` | `READ_EXTERNAL_STORAGE`(maxSdk 32)、`WRITE_EXTERNAL_STORAGE`(maxSdk 29)、`MANAGE_EXTERNAL_STORAGE`、`requestLegacyExternalStorage="true"`、`LocalFileProvider` 声明、`FileManagerActivity` 声明（`exported="false"`） | 均仍在，见文件第 9-11、22、44-52、64-69 行 |
| `app/src/main/java/com/codexrm/mobile/IconView.kt` | 6 个自绘图标：`menu`、`download`、`compare`、`bookmark`、`package`、`archive` | 均仍在（第 30、35 行等） |
| `app/src/main/java/com/codexrm/mobile/MainActivity.kt` | 设置页 `actions` 列表新增 `SettingsPage.Action("文件管理", …) { "MT 双栏 · 本地文件" }` | 仍在，当前约第 3442-3445 行（**行号已因并行编辑漂移**，上一轮记录为 3377） |
| `app/src/main/java/com/codexrm/mobile/WorkspaceFileUi.kt` | 新增 `fun openLocalManager()`，启动 `FileManagerActivity` | 仍在，第 77-78 行 |
| `app/src/main/java/com/codexrm/mobile/WorkspaceFilesDialog.kt` | 溢出菜单新增「本地文件管理（MT 双栏）」 | 仍在，第 328-329 行 |
| `app/src/main/java/com/codexrm/mobile/SettingsPage.kt` | `appActions` 过滤条件追加 `it.title == "文件管理"` | 仍在，第 85 行（**该文件本身是并行工作新增的未跟踪文件**） |
| `README.md`（android 工程内） | 新增一条「设置中的『文件管理』…」功能说明 | 仍在，第 24 行 |

> 注意：`git diff --numstat` 相对 HEAD 显示的改动行数（`MainActivity.kt` 2168/420、`WorkspaceFilesDialog.kt` 677/113 等）**包含大量并行工作**，不能据此区分本任务改动。本任务插入仅上述若干行。

### 2.3 实现的功能（代码存在，验证状态见第 7、8 节）

- **界面**：竖屏并列双栏（不做单栏 tab 折叠）、顶栏路径（可点击编辑）与 `文件夹/文件/已选/储存` 统计、底部五键（后退/前进/新建/交换窗口/上一级）、选择态底部（全选/反选/退出多选/交换窗口/所选文件的操作）、蓝色选中行、双列操作菜单（含「带 ● 的菜单表示可以长按触发单窗口操作」提示）、最近/书签底部抽屉、权限授权页。
- **导航**：路径编辑、前进/后退历史、`..` 上级、窗口交换、隐藏文件开关、按名称/大小/时间/类型排序 + 升降序、当前目录过滤、递归文件名搜索（上限 2000 项）。
- **文件操作**：多选/全选/反选、跨栏复制与移动、单窗口目标目录编辑（长按菜单）、新建文件夹/文件、删除（含确认）、重命名、ZIP 压缩与解压、属性统计、分享、文件夹递归对比、添加书签、设为首页、无 root 本地终端（`/system/bin/sh -c`，60 秒超时）。
- **后台操作框架**：`background()` 显示进度条与当前项，支持取消（`AtomicBoolean`），结束后刷新两栏、清除选择、展示结果或错误。

### 2.4 关键设计决策

1. **独立 Activity 而非改造 `WorkspaceFilesDialog`**：避免破坏既有 P2P 直连文件列表与 SAF 下载链路。两个入口并存。
2. **竖屏保持双栏**：直接对齐截图，未做响应式折叠。
3. **权限模型**：Android 11+ 用 `Environment.isExternalStorageManager()` + `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`；API 29 及以下用 `READ/WRITE_EXTERNAL_STORAGE` 运行时权限；未授权显示授权页而非静默失败。
4. **文件操作抽成纯 JVM 层**：`LocalFileStore` 不依赖 Android API，可用 JUnit 直接测真实文件 IO。
5. **同名不覆盖**：`uniqueTarget()` 生成 `name(1).ext`；移动实现为「复制成功后再删源」，跨文件系统兼容；删源失败时**保留目标并报告**，不静默丢数据。
6. **取消安全**：复制/压缩/解压/删除均检查 `canceled`；复制失败会删除未完成的半成品目标。
7. **ZIP 安全**：解压前用 canonical path 校验，拒绝 `../` 越界路径；不跟随符号链接递归（复制/搜索/统计均跳过符号链接）。
8. **分享/打开**：通过 `LocalFileProvider`（`${applicationId}.local-files`）按需授予 `FLAG_GRANT_READ_URI_PERMISSION`；`root-path` 范围较大，是**已知安全面**，见 8.4。
9. **Debug/Release 差异**：仅 Debug 源集导出 Activity 供 `am start`；Release 合并清单已确认 `android:exported="false"`。
10. **无新增依赖**：图标用既有 `IconView` 自绘，ZIP 用 `java.util.zip`，MIME 复用 `WorkspaceDownloads.mime()`。

---

## 3. 已执行的测试与结果（逐项，均为真实工具事件）

### 3.1 构建与 JVM 测试

| # | 命令 | 退出码 | 结果 |
| --- | --- | --- | --- |
| 1 | `.\gradlew.bat testDebugUnitTest --stacktrace`（基线） | 0 | BUILD SUCCESSFUL，**24 tasks 全部 UP-TO-DATE**，未实际执行测试 |
| 2 | `.\gradlew.bat testDebugUnitTest --tests com.codexrm.mobile.LocalFileStoreTest --stacktrace`（第 1 次） | 1 | 编译失败：`MainActivity.kt:4008:9 Unresolved reference 'olderButton'`（并行编辑导致，非本任务代码） |
| 3 | 同上（第 2 次） | 1 | 编译失败：`FileManagerActivity.kt` 4 处错误（`it` 未解析、`$prefix完成`/`$prefix已取消` 插值、菜单 `anchor` 参数类型）；已修正 |
| 4 | 同上（第 3 次） | 1 | 编译通过，**6 tests / 1 failed**：`listFilterSortAndRecursiveSearch` 断言漏掉「文件夹始终排前」；测试预期已修正（非产品代码缺陷） |
| 5 | `.\gradlew.bat testDebugUnitTest assembleDebug --stacktrace` | 0 | BUILD SUCCESSFUL，43 tasks（7 executed）；测试报告统计 **147 tests / 0 failures / 0 errors / 22 suites** |
| 6 | `.\gradlew.bat assembleDebug`（加入 debug 清单后重打包） | 0 | BUILD SUCCESSFUL，37 tasks（7 executed）；`processDebugResources` 通过 |
| 7 | `.\gradlew.bat assembleRelease --stacktrace` | 0 | BUILD SUCCESSFUL，48 tasks（1 executed：`lintVitalRelease`，47 up-to-date）；R8/资源压缩任务成功 |
| 8 | `.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease --stacktrace`（最终） | 0 | BUILD SUCCESSFUL，90 tasks（1 executed，89 up-to-date）；`testDebugUnitTest` 本次为 UP-TO-DATE |

**当前测试报告复核**（本轮只读）：`app/build/test-results/testDebugUnitTest` → `tests=147 failures=0 errors=0 suites=22`。

**Kotlin 编译警告**（曾出现，非错误，未修复）：`FileManagerActivity.kt:165` 覆盖 deprecated `onBackPressed` 未加 `@Deprecated`；`FileManagerActivity.kt:599` 不必要的 `?.`；另有 `ChatListView.kt:47` 条件恒真、`MainActivity.kt:1444` 不必要 `!!`、`SettingsPage.kt` 若干 deprecated 警告。

### 3.2 真机验证

设备：

- `D1212181032D`：Android 12 / API 31（nubia NX666J）
- `LJCQFEMNVW7TYLRC`：Android 16 / API 36（OPPO PFFM20）

已执行：

- `adb install -r app-debug.apk` 在两台设备均 `Success`；
- Android 12 设备**有密码锁屏**，唤醒后停留在「请输入密码」界面，**未绕过、未进行该机 UI 验证**；
- Android 16 设备通过 `appops set com.codexrm.mobile MANAGE_EXTERNAL_STORAGE allow` 授权后完成全部 UI/操作验证；
- 隔离 QA 目录 `/sdcard/CodexRM-MT-QA-6bc10d90/{left,right}`（两台设备均创建，readlink 核对后删除，输出 `QA_CLEAN`）。

自动化脚本 `.work/mt-file-manager-20261121/qa-drive.ps1` 最终输出：

```
PASS copy=true move=true rename=true zip=true multiSelect=true library=true checksums=true
```

具体证据：

| 项 | 证据 |
| --- | --- |
| 双栏界面 | `01-android16-dual-pane.png`（1080x2412，左栏 `folder` + `note.txt`，右栏「空文件夹」，底部五键） |
| 操作菜单 | `02-action-menu.png`（复制/移动/删除/重命名/工具/压缩/属性/分享/文件夹对比/添加书签，双列 + ● 提示） |
| 多选 | `03-selection.png` + `03-selection.xml`：顶栏「已选: 2」，两行蓝色高亮 |
| 最近/书签抽屉 | `04-library.png`：显示「最近」「书签」页签；**该次截图显示「暂无最近记录」**（原因见 8.3） |
| 授权门 | `05-permission.xml`：`appops … deny` 后出现「授权本地文件管理」「授权全部文件访问」；恢复 `allow` 后启动 `/sdcard` 成功 |
| 复制 | 源/目标 SHA-256 均为 `230b1a20be31e608e5225eb9ba1364ccdf095356c3dbee49979c279cb92e58f7` |
| 移动 | 目标 `note(1).txt` SHA-256 同上，源文件已移除 |
| 重命名 | 设备上实际完成 `folder` → `2` → `folders`（自动化 IME 输入被输入法纠正，见 8.3） |
| ZIP | 真机生成 `folders.zip`（278 B），`tar -tf` 含 `folders/`、`folders/archive.txt`；本地留存 `.work/.../folders.zip` |
| 无崩溃 | `logcat` 检索 `FATAL EXCEPTION` 未发现应用崩溃记录（该检查在首次截图时执行，未覆盖全部操作阶段） |

### 3.3 回滚验证

- `.work/mt-file-manager-20261121/rollback.ps1`：用精确标记反向修改，**不使用基线整文件覆盖**（因为 `MainActivity.kt` 有并行改动）。
- 在独立副本 `.work/.../rollback-check2/` 上执行，输出 `ROLLBACK_VERIFIED`；
- 核对结果：`AndroidManifest.xml`、`WorkspaceFileUi.kt`、`WorkspaceFilesDialog.kt`、`SettingsPage.kt` 与 `baseline/` 逐字一致；5 个新增文件全部删除；`MainActivity.kt` / `README.md` / `IconView.kt` 的 diff 仅移除本次插入块。
- **真实工作区未执行回滚**（本轮亦未执行）。

---

## 4. 尚未完成的工作

### 4.1 用户最新指令相关（最高优先）

- **「发布版本」未执行**：未递增版本（但版本号已被并行改动，见 6.2）、未做正式签名、未生成 `build/dist` 产物、未上传 Cloudreve、未发布 `android-update.json`、未更新 `docs/updates.md` 发布说明。
- 需先与用户确认：最新指令是「仅交接」还是「交接后继续发布」。**在用户确认前不要启动发布流程**。

### 4.2 原始需求「和 MT 完全一致」的范围差距（未满足）

以下 MT 能力**明确未实现**，若用户坚持「完全一致」，需重新排期与确认范围：

- APK / DEX / ARSC 编辑、反编译、签名、ZipAlign；
- root 挂载与系统分区读写；
- 插件生态、脚本/任务自动化；
- RAR / 7z / tar.gz 等压缩格式（当前仅 ZIP）；
- 内置文本编辑器、图片/视频浏览、十六进制查看；
- 网络邻居/云盘、FTP/SMB、蓝牙；
- MT 的窗口分屏手势、主题/配色定制、排序记忆的完整细节。

### 4.3 待修复 / 待确认的问题（代码存在但**未逐项真机验证**）

| 问题 | 现状 | 是否确认 |
| --- | --- | --- |
| 最近记录为空 | `04-library.png` 显示「暂无最近记录」。`recordRecent()` 只在 `Pane.goTo()` / `openFile()` 调用；QA 通过 intent 直接注入路径启动，未触发记录。**是设计缺口还是仅 QA 路径原因，未确认** | 未确认 |
| 重命名目录名偏差 | 自动化 `adb input text` 被输入法纠正为 `2` / `folders`；应用重命名逻辑本身无报错，但「输入任意名称」未真机严格验证 | 部分验证 |
| 溢出菜单锚点 | `showLibrary()` 内 `PopupMenu(this, tabs)` 使用整个页签行作锚点（编译修正时从 `anchor` 改为 `tabs`），弹出位置未真机验证 | 未验证 |
| `showTools` 的「文件夹对比」 | 对非文件夹条目也会调用 `compareFolders()`，内部靠 `toast` 兜底；交互合理性未验证 | 未验证 |
| `LocalFileProvider` 的 `root-path` | 范围为整个设备根路径，仅靠「用户显式打开/分享 + 临时 URI 授权」约束；安全影响未评估 | 未评估 |
| `requestLegacyExternalStorage` | `targetSdk = 35`，该属性在 Android 11+ 可能不生效；未在 API 29/30 真机验证 | 未验证 |

### 4.4 未验证的功能清单

以下功能**代码存在、编译通过**，但**没有真机或 instrumentation 断言**（不得描述为「已验证」）：

- 删除（确认对话框 + 递归删除）真机全流程；
- 解压 ZIP 到当前目录 / 另一栏；
- 属性对话框、分享 Intent、文件夹对比结果展示；
- 新建文件夹/文件、路径编辑、前进/后退、交换窗口、上一级；
- 搜索、过滤、排序方式、隐藏文件开关、设为首页、设置页；
- 「工具」菜单、单窗口复制/移动（长按 ● 的目标目录编辑）；
- 本地终端实际命令执行；
- 书签添加/删除/持久化、最近记录持久化；
- 打开文件（ACTION_VIEW + FileProvider）；
- 大文件、中文/空格/超长文件名、无权限目录、外置 SD 卡、损坏或加密 ZIP、符号链接真机边界；
- Android 9 / 10 / 11 / 12 / 13 / 14 / 15 权限分支与 UI；
- Release（R8 混淆后）APK 安装与运行；
- instrumentation QA：`DirectFilesCheck`、`UiReviewCheck`、`ImageRenderingInstrumentation`、`MathRenderingInstrumentation`、`ConversationFeaturesCheck`、`PairingWarningCheck`、`ConversationUpgradeCheck` 均**未重新运行**；
- 既有设置页新增一行「文件管理」对 `UiReviewCheck` 截图/文本断言的影响未验证。

### 4.5 已知风险

1. **并行编辑风险（最高）**：工作树有几十个 `M` 和 `??` 文件，且在任务期间持续变化。`MainActivity.kt` 曾被并行改动引入 `olderButton` 编译错误；`app/build.gradle.kts` 也曾触发配置缓存失效。**任何后续操作前必须重新跑一次干净构建**，不能依赖历史构建结果。
2. **发布合规风险**：`MANAGE_EXTERNAL_STORAGE` 在 Google Play 等渠道属特殊权限，需声明与审核；本次**未评估**。
3. **Release 未验证**：未签名/签名状态未确认（见 6.2），R8 后 `FileManagerActivity` / `LocalFileProvider` 是否被正确保留未做真机验证。
4. **数据安全面**：`root-path` + 临时 URI 授权、递归删除、移动「先复制后删源」的失败提示，均需安全复核。
5. **工作树不可回滚覆盖**：`MainActivity.kt` 与 `baseline/MainActivity.kt` 已不一致，**禁止用基线整文件覆盖**；只能用 `rollback.ps1` 的精确标记方式。
6. **AGENTS.md 合规**：后续任何修改类任务需留存基线副本、可重建 diff、独立回滚脚本、验证记录（命令/stdout/stderr/退出码）与 `.work/<case>/notes.md`。

---

## 5. 当前代码与产物的实际状态（截至本文件生成时）

### 5.1 编译状态

- 最后一次完整构建（第 8 项）`testDebugUnitTest assembleDebug assembleRelease` **exit 0 / BUILD SUCCESSFUL**，但 90 个任务中 89 个为 UP-TO-DATE；此前第 5、6、7 项均实际执行过编译与测试并通过。
- **本轮未重新构建**（遵守停止指令），因此上述结论是「截至 2026-11-21 11:38 左右」的状态；**交接间隔内工作树已被并行修改，当前是否仍能编译通过未重新验证**。

### 5.2 工作树状态（本轮 `git status --short` 只读结果，节选）

- 本任务相关：`M README.md`、`M app/src/main/AndroidManifest.xml`、`M app/src/main/java/com/codexrm/mobile/{IconView,MainActivity,WorkspaceFileUi,WorkspaceFilesDialog}.kt`、`?? app/src/main/java/com/codexrm/mobile/{FileManagerActivity,LocalFileStore,SettingsPage}.kt`、`?? app/src/debug/`、`?? app/src/main/res/xml/local_file_paths.xml`、`?? app/src/test/java/com/codexrm/mobile/LocalFileStoreTest.kt`。
- **并行工作相关**（非本任务，勿动）：`../README.md`、`app/build.gradle.kts`、`../build/scripts/{build.ps1,release-all.ps1}`、大量 `../internal/...`、`../web/...`、`../docs/...`，以及新增的 `app/src/main/assets/`、`ConversationResults.kt`、`MermaidPreview.kt`、`MessageDisplayGrouping.kt`、`ConversationUpgradeCheck.kt`、`../.web-reverse-*`、`../artifacts/`、`../docs/linux-onebot.md`、`../internal/onebot/` 等。
- `app/src/debug/` 目录内经只读确认**只有** `AndroidManifest.xml`（458 B）。
- `.codebuddy` 目录：系统指令要求**不得删除**；本任务未触碰。

### 5.3 版本与构建产物（重要不一致，原因未确认）

| 项 | 2026-11-21 11:27 左右记录 | 本轮只读复核（12:54） | 结论 |
| --- | --- | --- | --- |
| `versionCode` / `versionName` | 41 / `1.0.40` | **42 / `1.0.41`** | 已被改动，**来源未确认**，本轮未调查 |
| Release 产物 | `app/build/outputs/apk/release/app-release-unsigned.apk`，9,562,517 B，SHA-256 `B2B8B269…A0A` | 该路径 **不存在**；现有 `app-release.apk`，9,566,613 B，SHA-256 `F1280A85…7AB` | 产物被替换/重建，**来源与时间未确认** |
| Debug 产物 | `app-debug.apk`，20,406,759 B，SHA-256 `E9A7780D…CA1` | `app-debug.apk`，20,407,151 B，SHA-256 `9EBCA10D…B22` | 已变化，**来源未确认** |
| 签名状态 | 记录为「未签名」 | `tar -tf app-release.apk \| Select-String '^META-INF/.*\.(RSA\|SF\|MF)$'` 无输出；**无法据此判定是 v2/v3 签名、未签名，还是读取方式限制** | **未确认** |
| 测试报告 | 147 / 0 / 0 / 22 | 147 / 0 / 0 / 22 | 一致 |

> 推测（**明确标注为未确认，不得当作事实**）：`app-release.apk` 的文件名与版本递增可能来自某个并行进程执行了 `build.bat` 或带 `-Pcodexrm.testSign` 的构建。本轮**没有证据**证明这一点，也未执行任何构建去复现。

### 5.4 可能不一致的接口 / 潜在编译运行问题

- `WorkspaceFileUi.openLocalManager()` ↔ `MainActivity` 设置页 Action ↔ `FileManagerActivity`：三者签名一致，`FileManagerActivity` 为 `class`（非 `internal`），从 Manifest 声明，编译已通过。
- `FileManagerActivity.EXTRA_LEFT_PATH` / `EXTRA_RIGHT_PATH`（`"leftPath"` / `"rightPath"`）：**仅 QA 使用**；正式入口不传，走 SharedPreferences 或默认 `/sdcard`。
- **Debug 与 Release 的 `exported` 差异**：Debug 可用 `adb shell am start -n …/.FileManagerActivity` 直接启动；**Release 包这样启动会被系统拒绝**（已实测首次失败：`Permission Denial: … not exported`）。下一位 Agent 若用 Release 包做 `am start` 验证，需从应用内入口进入或改用 Debug 包。
- `LocalFileStore` 顶层扩展 `internal fun File.isSymbolicLink()` 与 `java.nio.file.Files.isSymbolicLink` 概念同名但实现不同（内部调用 NIO），阅读时注意。
- `showProperties()` 中 `background(...).let { }` 空消费写法可简化（不影响行为，未改）。
- 历史 `baseline/` 中 `MainActivity.kt` 仍含 `olderButton` 引用，而当前工作树已不含；**基线副本仅作为回滚锚点，不可整文件回写**。
- CRLF / LF 混用：`git diff` 出现 `CRLF will be replaced by LF` 警告；`rollback.ps1` 已做换行兼容并在隔离副本验证。

### 5.5 证据与工具文件（均在 `.work/mt-file-manager-20261121/`，该目录被 git 忽略）

```
notes.md                      完整实现/验证/回滚记录（78 行，上一任 Agent 写入）
baseline/                     7 个原文件副本（含 MainActivity.kt、SettingsPage.kt、IconView.kt）
qa-drive.ps1                  真机 UI 自动化脚本（UTF-8 BOM；含 -ResumeRename 分支）
rollback.ps1                  精确标记回滚脚本（已在隔离副本验证，真实工作区未执行）
rollback-check/  rollback-check2/   隔离副本及 .rollback-backup-mt-file-manager/
01-dual-pane.png / .xml       Android 12 锁屏截图（仅 SystemUI/锁屏，非应用界面，勿作验收证据）
01-android16-dual-pane.png / .xml   有效双栏截图
02-action-menu.png            操作菜单
03-selection.png / .xml       多选
04-library.png                最近/书签抽屉
05-permission.xml             授权页 UI dump
current.xml / rename-timeout.* / rename2.xml   中间排错记录
folders.zip                   真机生成的 ZIP 产物（278 B）
```

---

## 6. 下一位 Agent 可直接执行的后续步骤（按依赖与优先级）

> 前置条件：先读完本文件与 `.work/mt-file-manager-20261121/notes.md`；**先与用户确认最新指令的意图**（「仅交接」还是「交接后继续发布/继续开发」）。在确认前，只做只读检查。

### P0 — 状态确认（必须最先做，只读）

1. `cd E:\code\Go\CodexRM\android`
2. `git status --short` 与 `git diff --stat` 重新确认工作树（并行改动仍在变化）。
3. `.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease --stacktrace` —— **只有在用户允许继续开发/发布后才执行**；用它确认「当前」代码是否仍能编译通过，不要引用本文档中的历史构建结论作为当前结论。
4. 确认 `app/build/outputs/apk/release/` 下实际文件名、大小、SHA-256，以及是否签名（`apksigner verify --print-certs` 若可用）；确认 `versionCode`/`versionName` 当前值。

### P1 — 明确用户意图与范围

5. 若用户要**发布**：确认是否沿用当前 42/1.0.41、是否需要正式签名证书（README 说明 release 默认不签名，仅本机测试可用 `-Pcodexrm.testSign` 复用 debug 证书）、是否执行 `build.bat`（会递增版本、跑单测、构建 Release、生成独立 Android 清单、上传 Cloudreve、发布 `android-update.json`）。**注意 `build.bat` 会写文件并上传，须先获得明确许可**。
6. 若用户要**「和 MT 完全一致」**：先按 4.2 的范围差距与用户逐项确认，再排期；不要默认继续扩展。

### P2 — 补齐验证（不改产品代码也能做）

7. 运行 instrumentation QA（需连接 ARM64 设备，参照 `README.md` 第 73-92 行的 QA runner 命令），至少覆盖 `UiReviewCheck`（设置页新增一行的影响）与 `DirectFilesCheck`（确认原双栏/下载链路未回归）。
8. 在 Android 16 设备上用 `-Pcodexrm.testSign` 构建 **Release** 并安装，验证 R8 后 `FileManagerActivity`、`LocalFileProvider`、分享/打开 URI 是否正常；Debug 包已验证不代表 Release 通过。
9. 逐项手工/自动化验证 4.4 中「未验证的功能清单」，优先：删除、解压、属性、分享、文件夹对比、新建、路径编辑、搜索/排序/过滤、书签与最近记录。

### P3 — 修复与加固（需用户允许继续开发）

10. 定位并修复「最近记录为空」（`recordRecent()` 是否在 Intent 启动路径下应被调用）；这是 4.3 中唯一有截图证据的异常表现。
11. 复核 `LocalFileProvider` 的 `root-path` 暴露面，评估是否可收窄；复核 `MANAGE_EXTERNAL_STORAGE` 的上架合规说明。
12. 复核 `requestLegacyExternalStorage` 在 targetSdk 35 下的实际效果；补 API 28/29/30 权限分支真机验证。
13. 修掉本任务引入的两条 Kotlin warning（`FileManagerActivity` deprecated override、不必要的 `?.`），按 AGENTS.md 要求留基线/diff/回滚脚本。

### P4 — 发布（仅 P1 确认后）

14. 按 `README.md` 第 42-55 行使用 `build.bat`（或 `build/scripts/package-android.ps1` + `go build ./cmd/codexrm-publish` + `build/scripts/publish-manifest.ps1`），生成 `build/dist/CodexRM-Android-v版本号-版本代码.apk` 与 `android-update.json`。
15. 更新 `docs/updates.md` 发布说明（该文件当前已有并行改动，勿覆盖）。
16. 校验 APK 大小、SHA-256、包名、版本与签名证书一致性后再分发。

### P5 — 如需回滚（须用户明确许可，本轮未执行）

17. `.\.work\mt-file-manager-20261121\rollback.ps1 -Root E:\code\Go\CodexRM\android`
    - 该脚本按精确标记移除本次插入块并删除 5 个新增文件，**不会用基线整文件覆盖 `MainActivity.kt`**；
    - 已在 `rollback-check2/` 隔离副本输出 `ROLLBACK_VERIFIED`；
    - 执行前务必再次确认工作树（并行改动可能使标记失效，脚本会在标记缺失/不唯一时报错停止）。
    - 脚本默认会在目标根创建 `.rollback-backup-mt-file-manager/` 备份目录 —— 这属于写入操作，**未经用户许可不要执行**。

---

## 7. 关键命令速查

```powershell
# 工作目录
cd E:\code\Go\CodexRM\android

# 干净验证（会写 build/ 产物，需许可）
.\gradlew.bat testDebugUnitTest assembleDebug assembleRelease --stacktrace
.\gradlew.bat assembleRelease '-Pcodexrm.testSign'

# 查看当前事实（只读）
git status --short
git rev-parse --show-toplevel
Get-ChildItem .\app\build\outputs\apk\release
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256

# 真机（仅 Debug 包可外部直接启动；Release 内部 Activity 未导出）
adb devices -l
adb -s LJCQFEMNVW7TYLRC install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s LJCQFEMNVW7TYLRC shell appops set com.codexrm.mobile MANAGE_EXTERNAL_STORAGE allow
adb -s LJCQFEMNVW7TYLRC shell am start -W -n com.codexrm.mobile/.FileManagerActivity --es leftPath /sdcard --es rightPath /sdcard
adb -s LJCQFEMNVW7TYLRC shell uiautomator dump /data/local/tmp/ui.xml

# 复用真机自动化（注意：脚本当前含 -ResumeRename 分支与 folders 目录名；
# 重跑完整流程需先重建 QA 目录且不带 -ResumeRename；Windows PowerShell 5.1 需 UTF-8 BOM）
powershell -ExecutionPolicy Bypass -File .\.work\mt-file-manager-20261121\qa-drive.ps1

# 回滚（须用户许可，本轮未执行）
powershell -ExecutionPolicy Bypass -File .\.work\mt-file-manager-20261121\rollback.ps1 -Root E:\code\Go\CodexRM\android
```

---

## 8. 注意事项与不可推断事项清单

1. **本轮未执行任何构建/测试/发布/回滚/清理**；所有「成功」结论均来自 2026-11-21 11:38 之前的工具事件，间隔内工作树已被并行修改，**当前状态需重新验证**。
2. **版本 42 / 1.0.41、Release APK 文件名与哈希变化、Debug APK 哈希变化的来源未确认**；不得写成「本任务发布/签名/递增了版本」。
3. `app-release.apk` 的**签名状态未确认**；`tar -tf` 未列出 v1 签名条目不等于未签名（可能是 v2/v3 或读取方式限制）。
4. `baseline/MainActivity.kt` 与当前工作树不一致，**禁止整文件覆盖回滚**。
5. Android 12 设备的锁屏密码**未被索取、未被绕过**；该机仅有安装成功记录。
6. `.work/mt-file-manager-20261121/01-dual-pane.png` 是**锁屏/SystemUI 截图**，不是应用界面，不能用作验收证据；有效证据是 `01-android16-dual-pane.png` 等。
7. 「已实现」≠「已验证」：4.4 清单中的功能不得在下一次汇报中被描述为「已完成验证」。
8. 不要删除 `.codebuddy` 目录；不要清理 `.work/`（内有基线、回滚脚本与证据）；不要清理设备上的其他目录。
9. 用户偏好：中文短句、命令英文、先 tool 再解释、不反复索取已提供的授权/参数、失败如实报告原始错误。
10. 若平台/工具返回拒绝，按 AGENTS.md 记录可见的 `stop_reason` / `stop_details`，不要据此推断「用户未授权」。

---

## 9. 一句话结论

「MT 风格本地双栏文件管理器」的**代码已落地并有 JVM 单测 + Android 16 真机核心操作验证**，但**「和 MT 完全一致」未满足、「发布版本」未执行、当前工作树存在并行改动且间隔内产物已变化**；下一位 Agent 应先重新确认工作树与用户意图，再按 P0→P5 推进。
