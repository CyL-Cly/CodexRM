# CodexRM Android

原生 Kotlin 客户端，直接连接现有 CodexRM 中继和 Agent，不使用 WebView。

## 界面与功能

- 按 Web 移动布局实现原生界面：品牌侧栏、设备列表、搜索、按文件夹分组的会话、首页欢迎区、顶栏连接状态、圆角输入区和明暗主题。
- Camera2 + ZXing 扫码配对，也支持服务器地址、一次性配对码或完整配对链接手动输入。
- 多设备配对与切换；服务器撤销配对；401 自动提示重新配对。
- 工作区切换、添加/取消固定、远程目录浏览与隐藏目录开关。
- 全部未归档会话分页加载、搜索、恢复、新建、重命名和归档；最近更新优先。
- 流式回复、思考、命令输出、计划、文件 Diff、MCP 工具事件；工具卡片可展开，回复可复制。
- 新任务、停止、运行中排队与插队、队列取消/继续；加载更早的历史消息。
- 命令、文件和权限审批，批量审批，Codex 用户问题回答；只读桌面会话自动同步。
- 模型和思考强度选择；工作区写入、只读、完整访问权限模式；按设备/工作区保存偏好。
- 按设备和会话保存草稿；明暗主题；前台断线重连和当前会话重同步。

## 体积与性能设计

- 无 Compose、AndroidX、AppCompat、Material、CameraX、协程或 JSON 映射库。
- 纯 Android View；`ListView` 回收消息视图，仅重绘变化的可见行，流式文本按 80ms 合帧。
- 仅 OkHttp 和 ZXing Core 两个运行时依赖；无原生 ABI 库。
- Camera2 直接预览，后台线程最多每 200ms 解码一次，使用 Y 平面而不复制完整图像。
- 前台保持 WebSocket，退到后台即断开；指数退避重连，不使用常驻服务。
- release 启用 R8 混淆、资源压缩和按需 locale；工具输出保留末尾 64 KiB。
- 令牌列表整体使用 AES-GCM 加密，密钥由 AndroidKeyStore 持有，禁止系统备份。

## 构建

需要 JDK 17+、Android SDK 35。首次使用在 `local.properties` 设置 `sdk.dir`，或配置 `ANDROID_HOME`。

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

## 使用

1. 电脑启动 CodexRM Agent，打开设置页并生成配对二维码。
2. Android 点击“扫描二维码”，授权相机后扫描；也可以粘贴 `https://服务器/#pair=配对码`。
3. 侧栏选择设备、工作区或历史会话；首页直接输入任务。
4. 运行时“排队发送”在当前轮结束后执行，“插队发送”追加到当前轮。
5. 侧栏底部“我的远程工作区”打开权限、审批、设备详情及解除配对。

最低 Android 6.0（API 23），目标 API 35。HTTP 用于局域网自托管；公网应使用 HTTPS。手机访问电脑的 `127.0.0.1` 并不等于电脑自身：实体手机需使用电脑局域网 IP 或公网中继；USB 调试时可执行 `adb reverse tcp:<手机端口> tcp:<电脑中继端口>` 后再扫对应链接。

历史默认读取最近 30 轮，可点击“加载更早的消息”；后台不接收消息，回到前台自动重连并同步。
