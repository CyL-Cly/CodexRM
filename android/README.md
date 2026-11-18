# CodexRM Android

原生 Kotlin 客户端，直接连接现有 CodexRM 中继和 Agent，不使用 WebView。

## 界面与功能

- 按 Web 移动布局实现原生界面：品牌侧栏、可收起的设备选择器、搜索、按文件夹分组的会话、首页欢迎区、顶栏连接状态、圆角输入区和明暗主题。主界面向右滑动（→）可打开侧栏，也可点击顶部侧栏按钮。
- Camera2 + ZXing 扫码配对，也支持服务器地址、一次性配对码或完整配对链接手动输入。
- 多设备配对与切换；侧栏默认只显示当前设备，点击后展开列表，顶栏设备按钮打开同款下拉菜单；菜单底部均可添加新设备。支持服务器撤销配对；401 自动提示重新配对。
- 长按侧栏当前电脑或设备列表中的电脑可确认删除；立即移除手机上的配对记录，并尝试撤销服务器令牌，离线或撤销失败不影响删除。删除当前电脑后自动切换到下一台，全部删除后返回配对页。
- 工作区切换、添加/取消固定、远程目录浏览与隐藏目录开关。
- 全部未归档会话分页加载、搜索、恢复、新建、重命名和归档；最近更新优先。
- 长按侧栏对话可确认删除，从列表移除并保留电脑上的本地归档；运行中的对话须先停止，归档失败时保留原对话。
- 流式回复、思考、命令输出、计划、文件 Diff、MCP 工具事件；工具卡片可展开，回复可复制。
- 回复支持 Markdown 标题、粗体、斜体、删除线、嵌套列表、引用、链接、表格、任务列表和代码块；LaTeX 数学公式支持 `$…$`、`\(...\)` 行内公式及 `$$…$$`、`\[…\]` 独立公式，离线原生绘制并适配明暗主题。支持 `aligned` 多行对齐、矩阵和分段函数；超宽独立公式保持字号，可在公式区域左右拖动，底部显示滚动位置，竖向滑动仍滚动消息。未闭合公式保留原文，代码内不解析公式，复制回复保留 Markdown/LaTeX 源码。
- 输出仅在消息列表已滑到底部时自动跟随；上滑阅读时保留当前位置，重新滑到底部后恢复跟随。
- 新任务、停止、运行中排队与插队、队列取消/继续；加载更早的历史消息。
- 系统文件选择器多选文件和图片、发送前图片缩略图、分块上传进度、失败重试与附件草稿；支持仅发送附件、排队和插队。单个最大 20 MB，每条最多 10 个、合计 50 MB；需要更新电脑端 Agent。
- 已发送图片直接显示在对应消息中，发送前保存手机私有缓存，清理草稿或重启后仍可显示；历史缓存缺失时按附件 ID 从 Agent 分块恢复。Codex 回复支持 Markdown 图片、图片查看/生成事件和 MCP/动态工具返回图片，支持 HTTP(S)、内嵌图片及电脑上的本地图片路径；本地路径需要包含 `images` 能力的新 Agent。非附件的本地路径每次显示都向 Agent 重读并校验版本，避免同路径内容更新后长期使用旧缓存；上传附件和远程图片仍使用内存/磁盘缓存。点击查看大图，加载失败可点击重试。
- 命令、文件和权限审批，批量审批，Codex 用户问题回答；只读桌面会话自动同步。当前会话收到 `codexrm/thread/detached` 后即使原状态可写也每 3 秒读取快照，活跃快照转为只读；收到对应 `loaded` 后停止 detached 轮询并恢复可写状态。
- 模型和思考强度选择；工作区写入、只读、完整访问权限模式；按设备/工作区保存偏好。
- 按设备、会话及新会话工作区绑定草稿和附件；设备/线程切换立即重绑，连接期间的输入不会被延迟恢复覆盖。新会话在进程重建后的 bootstrap/cwd 绑定完成时恢复草稿；明暗主题；前台断线重连和当前会话重同步。
- 工作区设置内手动检查更新，显示版本说明和下载进度；校验 APK 大小、SHA-256、包名与版本后自动覆盖安装，支持取消下载及未知来源授权。

## 体积与性能设计

- 无 Compose、AppCompat、Material、CameraX、协程或 JSON 映射库；AndroidX 仅使用解析库的注解依赖。
- 纯 Android View；`ListView` 回收消息视图，仅重绘变化的可见行，流式文本按 80ms 合帧。
- 网络和扫码使用 OkHttp、ZXing Core；P2P 使用 Go/QUIC 原生库。富文本使用 Markwon/CommonMark 与 JLatexMath Android，公式异步绘制，消息缓存解析结果。
- 图片在后台读写和采样解码，最多同时加载 2 张；内存缓存上限 24 MiB，独立图片磁盘缓存上限 256 MiB，按最近使用时间淘汰。图片缓存按中继和 Agent 隔离，远程图片请求不携带配对令牌。
- Camera2 直接预览，后台线程最多每 200ms 解码一次，使用 Y 平面而不复制完整图像。
- 前台保持 WebSocket；配对的 Agent 和 RM 启用 [P2P](../docs/p2p-implementation.md) 时，业务在直连验证通过后切换至 QUIC。首页设备选择器左侧显示连接路径：点击闪电图标可测当前 QUIC 往返延迟，点击中继图标可查看协商状态或最近的失败原因。退到后台关闭连接，不使用常驻服务。
- release 启用 R8 混淆、资源压缩和按需 locale；工具输出保留末尾 64 KiB。
- 令牌列表整体使用 AES-GCM 加密，密钥由 AndroidKeyStore 持有，禁止系统备份。

## 构建

Android 仅支持 `arm64-v8a`（64 位 ARM）。Debug、Release 和 QA APK 都只构建并打包这一 ABI 的原生库。开发和测试请使用 ARM64 真机或 ARM64 模拟器镜像。

需要 JDK 17+、Android 16 SDK（API 36）、NDK 28.2.13676358 以及 PATH 上的 Go 1.24+。首次使用在 `local.properties` 设置 `sdk.dir`，或配置 `ANDROID_HOME`。

Windows 可双击本目录的 `build.bat` 一键构建发布，流程参考 HaveAPlan：Android `versionCode` 加 1、`versionName` 末段加 1，运行单元测试、构建 Release APK、生成独立 Android 清单，上传 APK 到 Cloudreve，最后发布 RM 的 `android-update.json`。复用仓库的 `build/publish.config.json`；默认使用当前已分发版本所用的本机 debug 证书签名（`-Pcodexrm.testSign`）。

```cmd
build.bat
build.bat nopause -ReleaseNotes "修复消息重复显示"
build.bat nopause -BuildOnly
build.bat nopause -BuildOnly -KeepVersion
build.bat nopause -PublishOnly
build.bat -Help
```

产物位于仓库的 `build/dist/CodexRM-Android-v版本号-版本代码.apk` 和 `build/dist/android-update.json`，Gradle 日志位于 `android/build.log`。构建或打包失败会恢复原版本文件；上传或清单发布失败保留新版本及产物，使用 `-PublishOnly` 重试，不再递增版本。`-BuildOnly` 不要求发布配置、Go 或 SSH；加 `-KeepVersion` 可保留当前版本号。

脚本优先使用 `JAVA_EXE`、`JAVA_HOME` 或已安装的 Android Studio JBR，也可用 `-JavaExe` 指定。需要构建代理时设置 `BUILD_PROXY=127.0.0.1:8848`，或传入 `-BuildProxy 127.0.0.1:8848`；`direct` 表示直连。`-Config`、`-OutputDirectory` 可指定配置和产物目录，重试发布时沿用相同目录。运行 `-Help` 查看参数。

```powershell
cd android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

`release` 默认不签名，用于后续正式签名。仅做本机测试时，可复用当前机器的 debug 签名：

```powershell
.\gradlew.bat assembleRelease '-Pcodexrm.testSign'
adb install -r .\app\build\outputs\apk\release\app-release.apk
```

正式发布应使用自己的 keystore，并通过 `apksigner` 或 Android Studio 签名；不要把密码或 keystore 提交到仓库。Debug 签名 APK 只用于测试，不是正式发布签名。

### 原生图片与公式 QA runner

两个属性互斥，均同时配置隔离的应用 ID 和对应 instrumentation runner，避免覆盖正式应用。连接设备后执行：

```powershell
# 数学公式：应用 ID com.codexrm.mobile.mathqa
.\gradlew.bat assembleDebug assembleDebugAndroidTest '-Pcodexrm.mathQa'
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -r com.codexrm.mobile.mathqa.test/com.codexrm.mobile.MathRenderingInstrumentation

# 图片：应用 ID com.codexrm.mobile.imageqa
.\gradlew.bat assembleDebug assembleDebugAndroidTest '-Pcodexrm.imageQa'
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
adb install -r .\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -r com.codexrm.mobile.imageqa.test/com.codexrm.mobile.ImageRenderingInstrumentation
```

公式 runner 也支持隔离的 minified Release 验收（本机测试签名）：`.\gradlew.bat assembleRelease assembleReleaseAndroidTest '-Pcodexrm.mathQa' '-Pcodexrm.testSign'`，随后安装 Release 目标包和 `androidTest\release` 包，并将上面的 instrumentation 包名改为 `com.codexrm.mobile.mathqa.test`。

## 使用

侧栏打开时，Android 14+ 的系统边缘返回手势会让侧栏和遮罩随进度收起；取消手势会恢复侧栏。设备列表展开时，第一次返回先收起列表。返回键和 Android 13 及以下仍在确认返回后收起侧栏。

1. 电脑启动 CodexRM Agent，打开设置页并生成配对二维码。
2. Android 点击“扫描二维码”，授权相机后扫描；也可以粘贴 `https://服务器/#pair=配对码`。
3. 点击侧栏当前设备或顶栏设备按钮展开设备菜单，选择已配对设备，或使用菜单底部的“添加新设备”；工作区与历史会话仍在侧栏选择，首页可直接输入任务。
4. 输入后按回车或键盘发送键发送，Shift+回车换行；运行时“排队发送”在当前轮结束后执行，“插队发送”追加到当前轮。
5. 侧栏底部设置入口可打开权限、审批、设备详情及解除配对。
6. 工作区设置底部显示当前应用版本；点击侧栏底部的检查更新图标可查询独立 Android 发布清单。发现新版本后确认下载，首次安装需按系统提示允许 CodexRM 安装未知应用。Android 12+ 授权后更新应用自身不再显示系统安装确认；系统不允许免确认的设备保留安装确认兜底。

更新源固定为 `https://codexrm.dagongren.tech/android-update.json`，与 Windows 的 `update.json` 和当前配对服务器互不影响。应用只在本机 `versionCode` 小于清单时提示更新，不会在后台自动下载或安装。下载地址支持 HTTP(S) 直链和相对清单地址；APK 必须与已安装应用使用相同签名证书，否则 Android 会拒绝覆盖安装。

发布已签名 APK 时，在仓库根目录生成独立清单，再复用现有 Cloudreve 上传和 RM 清单发布工具：

```powershell
.\build\scripts\package-android.ps1 -ApkPath .\android\app\build\outputs\apk\release\app-release.apk -ReleaseNotes "更新说明"
go build -o .\build\bin\codexrm-publish.exe .\cmd\codexrm-publish
.\build\bin\codexrm-publish.exe -package .\build\dist\CodexRM-Android-v版本号-版本代码.apk -manifest .\build\dist\android-update.json
$publish = Get-Content .\build\publish.config.json -Raw | ConvertFrom-Json
.\build\scripts\publish-manifest.ps1 -Manifest .\build\dist\android-update.json -Name android-update.json -RM $publish.rm
```

最低可安装 Android 9（API 28），目标 Android 15（API 35），编译使用 Android 16 SDK（API 36）。系统返回回调、类型化 PackageInfo/Parcelable 读取按 API 33 分支；`setDecorFitsSystemWindows`、`InsetsController` 和 `WindowInsets.Type` 仅在 API 30+ 使用，API 28/29 使用 `systemUiVisibility` 与传统 system-window/cutout insets，状态栏和导航栏图标按主题设置。更新安装器的 `setRequireUserAction` 仅在 API 31+ 调用，低版本保留系统安装确认；系统返回回调隔离在 SystemBackCompat，避免低版本类加载崩溃。Android 13+ 复用系统剪贴板确认提示；更新安装回调由系统发送，天然豁免 Android 14+ 的后台启动限制。HTTP 用于局域网自托管；公网应使用 HTTPS。手机访问电脑的 `127.0.0.1` 并不等于电脑自身：实体手机需使用电脑局域网 IP 或公网中继；USB 调试时可执行 `adb reverse tcp:<手机端口> tcp:<电脑中继端口>` 后再扫对应链接。

历史默认读取最近 30 轮，可点击“加载更早的消息”；后台不接收消息，回到前台自动重连并同步。
