package com.codexrm.mobile

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.min

private const val PICK_COMPOSER_ATTACHMENT = 43
private const val CAPTURE_ATTACHMENT_PERMISSION = 46
private const val ALBUM_WRITE_PERMISSION = 47
private const val ATTACHMENT_CAMERA_URI_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal class MainActivity : Activity() {
    private lateinit var store: DeviceStore
    private lateinit var attachmentStore: AttachmentStore
    private lateinit var imageLoader: MessageImageLoader
    private lateinit var workspaceFiles: WorkspaceFileUi
    private var attachmentFiles = mutableListOf<JSONObject>()
    private var preparingAttachments = false
    private var attachmentPickerKey = ""
    private var attachmentCameraFile = ""
    private var composerBinding = ComposerBinding("", "", "")
    private var composerRestoreToken = ComposerRestoreToken(composerBinding, 0)
    private var composerEditVersion = 0L
    private var applyingComposerText = false
    private var devices = mutableListOf<Device>()
    private var device: Device? = null
    private var client: CodexClient? = null
    private var pairCall: Call? = null
    private var updateCall: Call? = null
    private var updateDialog: AlertDialog? = null
    private var updateFailureReceiver: BroadcastReceiver? = null
    private var connectionDialog: AlertDialog? = null
    private var dialogShowsDirect = false
    private var updateOperation = 0
    private var pendingUpdate: UpdateChecker.VersionInfo? = null
    private var startupUpdate: UpdateChecker.VersionInfo? = null
    private var resumed = false
    private var started = false
    private var mainVisible = false
    private var mainBuilt = false
    private var pairMessage = ""
    private var pairPage: ScrollView? = null

    private lateinit var mainRoot: SidebarSwipeLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var sidebar: LinearLayout
    private lateinit var sidebarShade: View
    private lateinit var deviceRows: LinearLayout
    private lateinit var deviceListScroll: ScrollView
    private lateinit var deviceDropdown: LinearLayout
    private lateinit var sidebarDeviceButton: LinearLayout
    private lateinit var sidebarDeviceName: TextView
    private lateinit var sidebarDeviceStatus: TextView
    private lateinit var sidebarDeviceChevron: IconView
    private lateinit var workspaceRows: LinearLayout
    private lateinit var historyLabel: TextView
    private lateinit var searchInput: EditText
    private lateinit var deviceButton: LinearLayout
    private lateinit var connectionButton: FrameLayout
    private lateinit var connectionIcon: IconView
    private lateinit var deviceLabel: TextView
    private lateinit var connectionDot: View
    private lateinit var topbarTitle: TextView
    private lateinit var demoBadge: TextView
    private lateinit var messageList: ListView
    private lateinit var welcome: LinearLayout
    private lateinit var welcomeDescription: TextView
    private lateinit var olderButton: TextView
    private lateinit var adapter: MessageAdapter
    private lateinit var composer: EditText
    private lateinit var attachButton: FrameLayout
    private lateinit var attachmentChoices: LinearLayout
    private lateinit var sendButton: FrameLayout
    private lateinit var continueButton: FrameLayout
    private lateinit var attachmentScroll: HorizontalScrollView
    private lateinit var attachmentRows: LinearLayout
    private lateinit var attachmentStatus: TextView
    private lateinit var stopButton: FrameLayout
    private lateinit var workspaceSpinner: Spinner
    private lateinit var modelButton: TextView
    private lateinit var effortSpinner: Spinner
    private lateinit var cwdLabel: TextView
    private lateinit var runningActions: LinearLayout
    private lateinit var bootstrapArea: LinearLayout
    private lateinit var outboxArea: LinearLayout
    private lateinit var queueArea: LinearLayout
    private lateinit var activityRow: LinearLayout
    private lateinit var activityText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var approvalScroll: ScrollView
    private lateinit var approvalArea: LinearLayout

    private var serverInput: EditText? = null
    private var codeInput: EditText? = null
    private var pairError: TextView? = null
    private var pairSubmit: TextView? = null

    private var online = false
    private var loaded = false
    private var bootstrapping = false
    private var bootstrapError = ""
    private var approvalRecoveryMessage = ""
    private var loading = false
    private var sending = false
    private var activePendingSendId = ""
    private var outbox = OutboxState()
    private var running = false
    private var readOnly = false
    private var activeTurnId = ""
    private var latestTurn: TurnProgress? = null
    private var startedAt = 0L
    private var currentThread: JSONObject? = null
    private var currentThreadId = ""
    private var restoreThreadId = ""
    private var openingThreadId = ""
    private var currentCwd = ""
    private var info = JSONObject()
    private var threads = listOf<ThreadSummary>()
    private var threadCursor: String? = null
    private var threadListLoading = false
    private var threadListVersion = 0
    private val deletingThreads = HashSet<String>()
    private var workspaces = listOf<Workspace>()
    private var modelCatalog = ModelCatalog()
    private var models = listOf<ModelOption>()
    private var selectedModelOption: ModelOption? = null
    private var updatingSpinners = false
    private var generation = 0
    private var openGeneration = 0
    private var sidebarOpen = false
    private enum class BackTarget { DEVICES, SIDEBAR, ATTACHMENTS, PAIR }

    private class BackPreview(val draw: (Float) -> Unit, val restore: (committing: Boolean) -> Unit)

    private class BackGesture(val target: BackTarget, var preview: BackPreview?) {
        var progress = 0f
        var invalidated = false
        var completion: ValueAnimator? = null
    }

    private var backGesture: BackGesture? = null
    private var backCallbackRegistered = false
    private val backCallback: Any? = SystemBackCompat.createCallback(
        onStarted = ::startBackGesture,
        onProgressed = ::progressBackGesture,
        onCancelled = { finishBackGesture(commit = false) },
        onBack = ::invokeSystemBack,
    )
    private var collapsedPaths = mutableSetOf<String>()
    private val expandedWorkspaceThreads = mutableSetOf<Pair<String, String>>()
    private var workspacesCollapsed = false
    private var devicesExpanded = false
    private var devicePopup: PopupWindow? = null
    private val items = LinkedHashMap<String, JSONObject>()
    private val userMessages = UserMessageReconciler()
    private data class DeltaBuffer(val id: String, val type: String, val field: String, val text: StringBuilder)
    private val deltaBuffers = HashMap<String, DeltaBuffer>()
    private val dirtyDeltaIds = LinkedHashSet<String>()
    private val requests = LinkedHashMap<String, JSONObject>()
    private val requestScopes = HashMap<String, ApprovalScope>()
    private val responding = HashSet<String>()
    private val messageQueues = HashMap<String, JSONObject>()
    private val pendingQueueRemovals = HashSet<String>()
    private var editingQueueDraft: ComposerBinding? = null
    private var historyCursor: String? = null
    private var historyOlder = false
    private val historyMessageIds = HashSet<String>()
    private var renderScheduled = false
    private var snapshotRunning = false
    private var snapshotRequestVersion = 0
    private val detachedThreads = DetachedThreadState()
    private val handler = Handler(Looper.getMainLooper())
    private val elapsedTicker = object : Runnable {
        override fun run() {
            if (running && startedAt > 0) {
                val seconds = (System.currentTimeMillis() - startedAt) / 1000
                elapsedText.text = String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
                handler.postDelayed(this, 1000)
            }
        }
    }
    private val snapshotTicker = object : Runnable {
        override fun run() { refreshSnapshot() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        attachmentStore = AttachmentStore(this)
        imageLoader = MessageImageLoader(this) {
            ImageEnvironment("${device?.serverUrl.orEmpty().trimEnd('/')}\n${currentAgent()}", currentCwd, client, info.optBoolean("images"))
        }
        workspaceFiles = WorkspaceFileUi(this) { client }
        attachmentPickerKey = savedInstanceState?.getString("attachmentPickerKey").orEmpty()
        attachmentCameraFile = savedInstanceState?.getString("attachmentCameraFile").orEmpty()
        Ui.configure(this, store.darkTheme)
        devices = store.load()
        restoreThreadId = savedInstanceState?.getString("threadId").orEmpty()
        device = devices.firstOrNull { it.agentId == store.selectedId } ?: devices.firstOrNull()
        if (device == null) showPair() else showMain(reset = true)
        if (Build.VERSION.SDK_INT < 33 && !store.legacySystemWarningShown) {
            store.legacySystemWarningShown = true
            styledDialog(
                AlertDialog.Builder(this)
                    .setTitle("系统版本提示")
                    .setMessage("当前系统版本低于 Android 13，本应用可能无法完全兼容 Android 13 以下的系统，部分功能可能出现异常。建议将系统更新到 Android 13 或更高版本。")
                    .setPositiveButton("我知道了", null),
            )
        }
        checkForUpdatesOnStartup()
    }

    override fun onStart() { super.onStart(); started = true; if (mainVisible) connect() }
    override fun onResume() {
        super.onResume()
        resumed = true
        val pending = pendingUpdate
        if (pending != null) {
            pendingUpdate = null
            if (UpdateChecker.canInstall(this)) startUpdateDownload(pending) else showUpdateAvailable(pending)
        }
        showStartupUpdateIfReady()
    }
    override fun onPause() {
        resumed = false
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showStartupUpdateIfReady()
    }
    override fun onStop() {
        started = false
        finishBackGesture(commit = false)
        workspaceFiles.activityStopped()
        saveDraft()
        devicePopup?.dismiss()
        handler.removeCallbacks(snapshotTicker)
        disconnect()
        super.onStop()
    }
    override fun onDestroy() {
        if (backCallbackRegistered) {
            SystemBackCompat.unregister(this, backCallback)
        }
        cancelUpdateOperation()
        pairCall?.cancel()
        pendingUpdate = null
        handler.removeCallbacksAndMessages(null)
        imageLoader.close()
        workspaceFiles.close()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        saveDraft()
        outState.putString("threadId", currentThreadId)
        outState.putString("attachmentPickerKey", attachmentPickerKey)
        outState.putString("attachmentCameraFile", attachmentCameraFile)
        super.onSaveInstanceState(outState)
    }

    private fun showPair() {
        invalidateBackPreview()
        saveDraft()
        workspaceFiles.closeManager()
        disconnect()
        mainVisible = false
        if (mainBuilt) {
            sidebarOpen = false
            mainRoot.stopSidebarSwipe()
            sidebar.animate().cancel()
            sidebarShade.animate().cancel()
            drawSidebar(-sidebar.layoutParams.width.toFloat())
        }
        Ui.configure(this, store.darkTheme)
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(Ui.BACKGROUND) }
        val outer = Ui.column(this).apply {
            setPadding(Ui.dp(context, 20), Ui.dp(context, 40), Ui.dp(context, 20), Ui.dp(context, 24))
            gravity = Gravity.CENTER
        }
        scroll.addView(outer)
        val card = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 26), Ui.dp(context, 24), Ui.dp(context, 24))
            background = Ui.rounded(Ui.BACKGROUND, Ui.dp(context, 20).toFloat(), Ui.BORDER)
        }
        outer.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val brand = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL }
        val mark = FrameLayout(this).apply {
            background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 12).toFloat(), Ui.BORDER)
            addView(IconView(context, "terminal", Ui.TEXT), FrameLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24), Gravity.CENTER))
        }
        brand.addView(mark, LinearLayout.LayoutParams(Ui.dp(this, 43), Ui.dp(this, 43)))
        val names = Ui.column(this).apply { setPadding(Ui.dp(context, 12), 0, 0, 0) }
        names.addView(Ui.text(this, "CodexRM", 20f, bold = true))
        names.addView(Ui.text(this, "REMOTE WORKSPACE", 9f, Ui.DIM))
        brand.addView(names)
        card.addView(brand)
        card.addView(Ui.text(this, "连接你的电脑", 24f).apply { setPadding(0, Ui.dp(context, 28), 0, Ui.dp(context, 8)) })
        card.addView(Ui.text(this, "扫描连接器显示的二维码自动配对，或输入一次性配对码。", 13f, Ui.MUTED).apply {
            setLineSpacing(0f, 1.4f); setPadding(0, 0, 0, Ui.dp(context, 22))
        })
        card.addView(Ui.button(this, "扫描二维码", primary = true) {
            startActivityForResult(Intent(this, ScannerActivity::class.java), 42)
        }, matchWidth())
        card.addView(Ui.text(this, "设备配对码 / 完整配对链接", 11f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 22), 0, Ui.dp(context, 7)) })
        val code = Ui.boxInput(this, "ABCD-EFGH").apply {
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        codeInput = code
        card.addView(code, matchWidth())
        val server = Ui.boxInput(this, "服务器地址（手动配对码需要）").apply { setText(store.lastServer); textSize = 12f }
        serverInput = server
        card.addView(server, matchWidth(top = 9))
        val error = Ui.text(this, pairMessage, 12f, Ui.DANGER).apply { visibility = if (pairMessage.isEmpty()) View.GONE else View.VISIBLE }
        pairError = error
        card.addView(error, matchWidth(top = 8))
        val submit = Ui.button(this, "连接设备  →", primary = true) { submitManualPair() }
        pairSubmit = submit
        card.addView(submit, matchWidth(top = 10))
        card.addView(Ui.text(this, "配对码有效期 10 分钟，仅可使用一次。\n只有完成配对的设备才能访问这台电脑。", 11f, Ui.DIM).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f); setPadding(0, Ui.dp(context, 22), 0, 0)
        })
        if (devices.isNotEmpty()) card.addView(Ui.button(this, "返回已配对设备") { showMain() }, matchWidth(top = 12))
        Ui.applyWindowInsets(scroll)
        pairPage = scroll
        setContentView(FrameLayout(this).apply { addView(scroll, backPageParams()) })
        updateBackHandling()
    }

    private fun submitManualPair() {
        try { pair(PairingLink.parse(codeInput?.text?.toString().orEmpty(), serverInput?.text?.toString().orEmpty())) }
        catch (error: Exception) { showPairError(error.message ?: "配对信息无效") }
    }

    private fun pair(data: PairingData) {
        pairCall?.cancel()
        pairSubmit?.isEnabled = false
        pairSubmit?.text = "正在连接…"
        pairError?.visibility = View.GONE
        val body = JSONObject()
            .put("code", data.code)
            .put("clientName", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80))
            .put("deviceType", "android")
        pairCall = Network.request(data.serverUrl, "/api/v1/client/claim", body = body) { result, error ->
            pairSubmit?.isEnabled = true; pairSubmit?.text = "连接设备  →"
            if (error != null || result == null) {
                showPairError(when (error?.status) {
                    404 -> "找不到这个配对码，请确认输入正确"
                    409 -> "配对码已使用，请重新生成"
                    410 -> "配对码已过期，请在电脑上重新生成"
                    else -> error?.message ?: "配对失败"
                })
                return@request
            }
            try {
                val paired = Device.fromClaim(data.serverUrl, result)
                val old = devices.firstOrNull { it.agentId == paired.agentId }
                devices.removeAll { it.agentId == paired.agentId }; devices.add(0, paired)
                store.save(devices); store.selectedId = paired.agentId; store.lastServer = paired.serverUrl
                device = paired; pairMessage = ""; restoreThreadId = ""
                if (old != null && old.token != paired.token) Network.request(old.serverUrl, "/api/v1/client/revoke", old.token, JSONObject()) { _, _ -> }
                toast("已配对 ${paired.serverName}")
                showMain(reset = true)
            } catch (exception: Exception) { showPairError(exception.message ?: "配对响应无效") }
        }
    }

    private fun showPairError(message: String) {
        pairMessage = message; pairError?.text = message; pairError?.visibility = View.VISIBLE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (workspaceFiles.onActivityResult(requestCode, resultCode, data)) return
        if (requestCode == PICK_COMPOSER_ATTACHMENT) {
            receiveAttachments(resultCode, data)
            return
        }
        if (requestCode != 42 || resultCode != RESULT_OK) return
        try { pair(PairingLink.parse(data?.getStringExtra(ScannerActivity.EXTRA_TEXT).orEmpty(), store.lastServer)) }
        catch (error: Exception) { showPairError(error.message ?: "二维码内容无效") }
    }

    private fun showMain(reset: Boolean = false) {
        invalidateBackPreview()
        if (reset) disconnect()
        if (!mainBuilt) buildMain()
        mainVisible = true
        pairPage = null
        setContentView(mainRoot)
        device = devices.firstOrNull { it.agentId == store.selectedId } ?: devices.firstOrNull().also { store.selectedId = it?.agentId.orEmpty() }
        if (device == null) { showPair(); return }
        if (reset) resetConversation()
        renderDevices(); renderWorkspaces(); updateControls()
        updateBackHandling()
        if (started) connect()
    }

    private fun buildMain() {
        mainBuilt = true
        sidebarOpen = false
        devicesExpanded = false
        devicePopup?.dismiss()
        adapter = MessageAdapter(this, imageLoader, { file, done ->
            workspaceFiles.size(RemoteFile(currentCwd, currentThreadId, file.path), done)
        }, ::downloadMessageFile)
        adapter.showFileCards = directFilesAvailable()
        mainRoot = SidebarSwipeLayout(
            this,
            canOpenSidebar = { mainVisible && !sidebarOpen },
            startSidebarDrag = {
                sidebar.animate().cancel()
                sidebarShade.animate().cancel()
                sidebar.translationX
            },
            moveSidebar = ::drawSidebar,
            finishSidebarDrag = { open -> if (open) openSidebar() else closeSidebar() },
        ).apply {
            setBackgroundColor(Ui.BACKGROUND)
        }
        Ui.applyWindowInsets(mainRoot)
        contentRoot = Ui.column(this)
        mainRoot.addView(contentRoot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildTopbar()
        val body = Ui.column(this)
        contentRoot.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        welcome = Ui.column(this).apply {
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 24), Ui.dp(context, 20), Ui.dp(context, 24), Ui.dp(context, 20))
            val mark = FrameLayout(context).apply {
                background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 14).toFloat(), Ui.BORDER)
                addView(IconView(context, "terminal", Ui.TEXT), FrameLayout.LayoutParams(Ui.dp(context, 25), Ui.dp(context, 25), Gravity.CENTER))
            }
            addView(mark, LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = Ui.dp(context, 22) })
            addView(Ui.text(context, "你 的 工 作 区 ， 随 处 可 达", 10f, Ui.MUTED).apply { gravity = Gravity.CENTER })
            addView(Ui.text(context, "随时可以开始。", 29f).apply { gravity = Gravity.CENTER; setPadding(0, Ui.dp(context, 15), 0, Ui.dp(context, 10)) })
            welcomeDescription = Ui.text(context, "连接你的电脑，让 Codex 继续手边的工作。", 12f, Ui.MUTED).apply { gravity = Gravity.CENTER }
            addView(welcomeDescription)
        }
        val transcript = Ui.column(this)
        transcript.addView(welcome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        olderButton = Ui.text(this, "加载更早的消息", 12f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; background = Ui.ripple(Color.TRANSPARENT, 0f); visibility = View.GONE
            setOnClickListener { loadOlderTurns() }
        }
        transcript.addView(olderButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))
        messageList = ListView(this).apply {
            divider = null; dividerHeight = 0; cacheColorHint = Color.TRANSPARENT; selector = Ui.rounded(Color.TRANSPARENT, 0f)
            clipToPadding = false; setStackFromBottom(true)
            // Check the last row's bottom before relayout, including growth within a long reply.
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            adapter = this@MainActivity.adapter
            setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), 0)
            setOnItemLongClickListener { _, _, position, _ -> copyMessage(this@MainActivity.adapter.getItem(position).body); true }
        }
        transcript.addView(messageList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildComposer(body)
        buildSidebar()
    }

    private fun buildTopbar() {
        val topbar = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0)
        }
        topbar.addView(Ui.iconButton(this, "panel", "打开侧栏", 44) { toggleSidebar() })
        topbarTitle = Ui.text(this, "Codex Remote", 15f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0)
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 8).toFloat())
            setOnClickListener { renameCurrentThread() }
        }
        topbar.addView(topbarTitle, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        demoBadge = Ui.text(this, "演示模式", 9f, 0xffd2b889.toInt()).apply {
            background = Ui.rounded(0x1aac8232, Ui.dp(context, 4).toFloat(), 0x30ac8232)
            setPadding(Ui.dp(context, 7), Ui.dp(context, 4), Ui.dp(context, 7), Ui.dp(context, 4)); visibility = View.GONE
        }
        topbar.addView(demoBadge)
        connectionButton = Ui.iconButton(this, "relay", "RM 中继", 36) { showConnectionDetails() }.apply {
            visibility = View.INVISIBLE
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 44))
        }
        connectionIcon = connectionButton.getChildAt(0) as IconView
        topbar.addView(connectionButton)
        deviceButton = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 20).toFloat(), Ui.BORDER)
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 8), 0)
            setOnClickListener { showDevices() }
        }
        connectionDot = Ui.dot(this, Ui.DIM)
        deviceButton.addView(connectionDot, LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 6)))
        deviceLabel = Ui.text(this, "连接设备", 10f, Ui.MUTED).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(context, 7), 0, Ui.dp(context, 5), 0)
        }
        deviceButton.addView(deviceLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        deviceButton.addView(IconView(this, "chevron", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12)))
        topbar.addView(deviceButton, LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 36)).apply { leftMargin = Ui.dp(this@MainActivity, 6) })
        topbar.addView(Ui.iconButton(this, "chat-plus", "新会话") { newChat() }.apply {
            (getChildAt(0) as IconView).apply {
                setColor(Ui.TEXT)
                val iconSize = Ui.dp(context, 24)
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
        })
        contentRoot.addView(topbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 60)))
    }

    private fun buildComposer(body: LinearLayout) {
        val zone = Ui.column(this).apply { setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8)) }
        bootstrapArea = Ui.column(this).apply { visibility = View.GONE }
        zone.addView(bootstrapArea, matchWidth())
        outboxArea = Ui.column(this).apply { visibility = View.GONE }
        zone.addView(outboxArea, matchWidth())
        approvalScroll = ScrollView(this).apply { visibility = View.GONE; isFillViewport = false }
        approvalArea = Ui.column(this)
        approvalScroll.addView(approvalArea)
        zone.addView(approvalScroll, matchWidth())
        queueArea = Ui.column(this).apply { visibility = View.GONE }
        zone.addView(queueArea, matchWidth())
        activityRow = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), Ui.dp(context, 10)); visibility = View.GONE
            addView(Ui.dot(context, Ui.GREEN, 5), LinearLayout.LayoutParams(Ui.dp(context, 5), Ui.dp(context, 5)))
            activityText = Ui.text(context, "Codex 正在处理", 11f, Ui.MUTED).apply { setPadding(Ui.dp(context, 8), 0, 0, 0) }
            addView(activityText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            elapsedText = Ui.text(context, "", 10f, Ui.DIM); addView(elapsedText)
        }
        zone.addView(activityRow, matchWidth())
        val composerCard = Ui.column(this).apply {
            setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 9))
            background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 18).toFloat(), if (Ui.dark) 0xff383838.toInt() else Ui.BORDER)
        }
        attachmentStatus = Ui.text(this, "", 10f, Ui.MUTED).apply { visibility = View.GONE; maxLines = 1 }
        composerCard.addView(attachmentStatus, matchWidth())
        attachmentRows = Ui.column(this, false)
        attachmentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; visibility = View.GONE
            addView(attachmentRows)
        }
        composerCard.addView(attachmentScroll, matchWidth(bottom = 4))
        composer = Ui.input(this, "安排一个任务，或问问 Codex…", multiLine = true).apply {
            setHintTextColor(Ui.MUTED); minHeight = Ui.dp(context, 44); maxHeight = Ui.dp(context, 200)
            filters = arrayOf(android.text.InputFilter.LengthFilter(200000))
            contentDescription = "消息输入，回车发送，Shift 加回车换行"
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        composer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                sendMessage(false); true
            } else false
        }
        composer.setOnKeyListener { _, keyCode, event ->
            val enter = keyCode == android.view.KeyEvent.KEYCODE_ENTER || keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            val plainEnter = enter && event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0 &&
                !event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed
            if (plainEnter) { sendMessage(false); true } else false
        }
        composer.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!applyingComposerText) composerEditVersion++
                updateSendState()
            }
        })
        composerCard.addView(composer, matchWidth())
        runningActions = Ui.column(this, false).apply {
            gravity = Gravity.END; visibility = View.GONE
            addView(Ui.button(context, "插队发送") { sendMessage(true) })
        }
        composerCard.addView(runningActions, matchWidth())
        val toolbar = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL }
        attachButton = Ui.iconButton(this, "attach", "添加文件或图片", 48) { toggleAttachmentChoices() }
        toolbar.addView(attachButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        modelButton = Ui.text(this, "默认模型", 11f, Ui.MUTED).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = Ui.dp(context, 48)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4))
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 8).toFloat())
            contentDescription = "选择模型，当前为默认模型"
            isFocusable = true
            setOnClickListener { showModelPicker() }
        }
        toolbar.addView(modelButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        effortSpinner = Spinner(this).apply { contentDescription = "选择思考强度"; minimumHeight = Ui.dp(context, 48) }
        toolbar.addView(effortSpinner, LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 48)))
        stopButton = circleButton("stop", "停止任务") { stopTurn() }.apply { visibility = View.GONE }
        toolbar.addView(stopButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { leftMargin = Ui.dp(this@MainActivity, 4) })
        sendButton = circleButton("arrow", "发送消息") { sendMessage(false) }.apply { visibility = View.GONE }
        toolbar.addView(sendButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { marginStart = Ui.dp(this@MainActivity, 4) })
        continueButton = circleButton("play", "继续中断的任务") { continueTurn() }.apply { visibility = View.GONE }
        toolbar.addView(continueButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { marginStart = Ui.dp(this@MainActivity, 4) })
        composerCard.addView(toolbar, matchWidth())
        zone.addView(composerCard, matchWidth())
        attachmentChoices = buildAttachmentChoices()
        zone.addView(attachmentChoices, matchWidth(top = 8))
        val meta = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), 0) }
        val path = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL }
        cwdLabel = Ui.text(this, "点击设置工作目录", 10f, Ui.DIM).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            minHeight = Ui.dp(context, 48); isFocusable = true
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 6).toFloat())
            setOnClickListener { openWorkspacePicker() }
            setOnLongClickListener { openWorkspaceFiles(currentCwd, currentThreadId) }
        }
        path.addView(cwdLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        workspaceSpinner = Spinner(this, Spinner.MODE_DROPDOWN).apply {
            contentDescription = "选择已有工作目录"
            dropDownWidth = min(Ui.dp(context, 360), resources.displayMetrics.widthPixels - Ui.dp(context, 36))
            setPadding(0, 0, 0, 0)
        }
        path.addView(workspaceSpinner, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        meta.addView(path, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        zone.addView(meta, matchWidth())
        zone.addView(Ui.text(this, "任务在你的电脑上运行  ·  CodexRM 连接每一处灵感", 8f, Ui.DIM).apply {
            gravity = Gravity.CENTER; setPadding(0, Ui.dp(context, 8), 0, 0)
        }, matchWidth())
        body.addView(zone, matchWidth())
        configureSpinners()
    }

    private fun circleButton(icon: String, description: String, click: () -> Unit): FrameLayout = FrameLayout(this).apply {
        contentDescription = description; isClickable = true; isFocusable = true
        background = Ui.ripple(Ui.TEXT, Ui.dp(context, 22).toFloat())
        addView(IconView(context, icon, Ui.BACKGROUND), FrameLayout.LayoutParams(Ui.dp(context, 20), Ui.dp(context, 20), Gravity.CENTER))
        setOnClickListener { click() }
    }

    private fun buildSidebar() {
        sidebarShade = View(this).apply {
            setBackgroundColor(0x99000000.toInt()); alpha = 0f; visibility = View.GONE
            setOnClickListener { closeSidebar() }
        }
        mainRoot.addView(sidebarShade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sidebar = Ui.column(this).apply {
            setPadding(Ui.dp(context, 14), Ui.dp(context, 22), Ui.dp(context, 14), Ui.dp(context, 12))
            setBackgroundColor(Ui.SIDEBAR); elevation = Ui.dp(context, 16).toFloat()
        }
        val brand = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(context, 8), 0, 0, Ui.dp(context, 24)) }
        val mark = FrameLayout(this).apply {
            background = Ui.rounded(Color.TRANSPARENT, Ui.dp(context, 9).toFloat(), 0xff555555.toInt())
            addView(IconView(context, "terminal", Ui.TEXT), FrameLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18), Gravity.CENTER))
        }
        brand.addView(mark, LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)))
        brand.addView(Ui.text(this, "Codex", 20f, bold = true).apply { setPadding(Ui.dp(context, 10), 0, 0, 0) })
        brand.addView(Ui.text(this, "RM", 20f, Ui.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        brand.addView(Ui.iconButton(this, if (Ui.dark) "sun" else "moon", "切换明暗主题") { toggleTheme() })
        brand.addView(Ui.iconButton(this, "close", "关闭侧栏", 42) { closeSidebar() })
        sidebar.addView(brand, matchWidth())
        val search = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(context, 10), 0, 0, 0) }
        search.addView(IconView(this, "search", Ui.DIM), LinearLayout.LayoutParams(Ui.dp(this, 18), Ui.dp(this, 18)))
        searchInput = Ui.input(this, "搜索会话").apply { textSize = 13f; background = null }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { renderWorkspaces() }
        })
        search.addView(searchInput, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        sidebar.addView(search, matchWidth())
        sidebarDeviceButton = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 10).toFloat())
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
            isClickable = true; isFocusable = true
            setOnClickListener { toggleDevices() }
            setOnLongClickListener { device?.let(::confirmDeleteDevice); true }
            val icon = FrameLayout(context).apply {
                background = Ui.rounded(Ui.SURFACE_ALT, Ui.dp(context, 8).toFloat())
                addView(IconView(context, "monitor", Ui.MUTED), FrameLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18), Gravity.CENTER))
            }
            addView(icon, LinearLayout.LayoutParams(Ui.dp(context, 34), Ui.dp(context, 34)))
            val labels = Ui.column(context).apply { setPadding(Ui.dp(context, 10), 0, 0, 0) }
            sidebarDeviceName = Ui.text(context, "选择设备", 12f, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            sidebarDeviceStatus = Ui.text(context, "点击选择工作设备", 10f, Ui.MUTED)
            labels.addView(sidebarDeviceName)
            labels.addView(sidebarDeviceStatus)
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sidebarDeviceChevron = IconView(context, "chevron", Ui.MUTED)
            addView(sidebarDeviceChevron, LinearLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18)))
        }
        sidebar.addView(sidebarDeviceButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))
        deviceDropdown = Ui.column(this).apply {
            visibility = View.GONE
            background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 12).toFloat(), Ui.BORDER)
            setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4))
        }
        deviceListScroll = ScrollView(this).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        }
        deviceRows = Ui.column(this)
        deviceListScroll.addView(deviceRows)
        deviceDropdown.addView(deviceListScroll, matchWidth())
        deviceDropdown.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)).apply {
            topMargin = Ui.dp(this@MainActivity, 4); bottomMargin = Ui.dp(this@MainActivity, 4)
        })
        deviceDropdown.addView(addDeviceRow { showPair() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)))
        sidebar.addView(deviceDropdown, matchWidth(bottom = 12))
        val workspaceHeader = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL }
        val toggle = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL; setOnClickListener { workspacesCollapsed = !workspacesCollapsed; renderWorkspaces() }
            addView(IconView(context, "chevron", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18)))
            addView(Ui.text(context, "工作区", 11f, Ui.MUTED, true))
        }
        workspaceHeader.addView(toggle, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        workspaceHeader.addView(Ui.iconButton(this, "refresh", "刷新工作区与会话", 40) { refreshAll() })
        workspaceHeader.addView(Ui.iconButton(this, "plus", "添加工作区", 40) { openWorkspacePicker() })
        sidebar.addView(workspaceHeader, matchWidth())
        val workspaceScroll = ScrollView(this).apply { isFillViewport = true }
        val workspaceContent = Ui.column(this)
        historyLabel = Ui.text(this, "所有文件夹 · 最近更新优先", 10f, Ui.DIM).apply { setPadding(Ui.dp(context, 10), 0, 0, Ui.dp(context, 4)) }
        workspaceContent.addView(historyLabel, matchWidth())
        workspaceRows = Ui.column(this)
        workspaceContent.addView(workspaceRows, matchWidth())
        workspaceScroll.addView(workspaceContent)
        sidebar.addView(workspaceScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val profile = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(context, 12), 0, 0)
            addView(Ui.iconButton(context, "more", "更多应用选项") { showAppMenu(it) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(Ui.iconButton(context, "settings", "工作区设置") { showSettings() })
        }
        sidebar.addView(profile, matchWidth())
        val width = min(Ui.dp(this, 300), (resources.displayMetrics.widthPixels * 0.86).toInt())
        sidebar.translationX = -width.toFloat()
        mainRoot.addView(sidebar, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun configureSpinners() {
        workspaceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingSpinners) return
                val path = workspaceValues().getOrNull(position)?.path.orEmpty()
                if (path.isNotEmpty() && pathKey(path) != pathKey(currentCwd)) selectWorkspace(path)
            }
        }
        effortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { if (!updatingSpinners) saveModelPreference() }
        }
        refreshWorkspaceSpinner(); refreshModelSpinner()
    }

    private fun setSpinnerItems(
        spinner: Spinner,
        values: List<String>,
        selectedLabels: List<String> = values,
        dropdownNotes: List<String> = emptyList(),
    ) {
        spinner.backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.MUTED)
        spinner.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            private fun style(view: View, position: Int, dropdown: Boolean): View {
                val text = view.findViewById<TextView>(android.R.id.text1)
                val notes = if (dropdown) dropdownNotes.getOrNull(position).orEmpty() else ""
                text.text = when {
                    !dropdown -> selectedLabels[position]
                    notes.isEmpty() -> values[position]
                    else -> "${values[position]}\n${notes.lineSequence().joinToString(" ")}"
                }
                if (notes.isNotEmpty()) text.isSingleLine = false
                text.setTextColor(Ui.MUTED); text.textSize = if (dropdown) 13f else 11f
                text.maxLines = if (notes.isEmpty()) 1 else 2
                text.ellipsize = android.text.TextUtils.TruncateAt.END
                view.setBackgroundColor(if (dropdown) Ui.SURFACE else Color.TRANSPARENT)
                text.setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 4), 0)
                return view
            }
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = style(super.getView(position, convertView, parent), position, false)
            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View = style(super.getDropDownView(position, convertView, parent), position, true)
        }
    }

    private fun toggleSidebar() { if (sidebarOpen) closeSidebar() else openSidebar() }
    private fun openSidebar() {
        mainRoot.stopSidebarSwipe()
        sidebarOpen = true
        updateBackHandling()
        animateSidebar()
    }
    private fun closeSidebar() {
        mainRoot.stopSidebarSwipe()
        sidebarOpen = false
        updateBackHandling()
        animateSidebar()
    }

    private fun drawSidebar(translationX: Float) {
        val width = sidebar.layoutParams.width.coerceAtLeast(1).toFloat()
        val offset = translationX.coerceIn(-width, 0f)
        sidebar.translationX = offset
        sidebarShade.alpha = 1f + offset / width
        val shadeVisibility = if (offset > -width) View.VISIBLE else View.GONE
        if (sidebarShade.visibility != shadeVisibility) sidebarShade.visibility = shadeVisibility
    }

    private fun animateSidebar() {
        sidebar.animate().cancel()
        sidebarShade.animate().cancel()
        val width = sidebar.layoutParams.width.coerceAtLeast(1)
        val targetX = if (sidebarOpen) 0f else -width.toFloat()
        val remaining = kotlin.math.abs(targetX - sidebar.translationX) / width
        val duration = (remaining * 180).toLong().coerceIn(60, 180)
        val easing = AccelerateDecelerateInterpolator()
        if (sidebarOpen) sidebarShade.visibility = View.VISIBLE
        sidebar.animate().translationX(targetX).setDuration(duration).setInterpolator(easing).start()
        sidebarShade.animate().alpha(if (sidebarOpen) 1f else 0f).setDuration(duration).setInterpolator(easing)
            .withEndAction { if (!sidebarOpen) sidebarShade.visibility = View.GONE }.start()
    }

    private fun connect() {
        val current = device ?: return
        if (!started || !mainVisible || client != null) return
        online = false
        client = CodexClient(this, current, object : CodexClient.Listener {
            override fun onState(state: ConnectionState, agent: JSONObject?) {
                if (device !== current) return
                val wasOnline = online
                online = state == ConnectionState.ONLINE; current.online = online
                val announcedVersion = agent?.string("appVersion").orEmpty()
                val versionChanged = announcedVersion.isNotBlank() && announcedVersion != current.appVersion
                agent?.let {
                    val name = it.string("serverName").ifBlank { current.serverName }
                    val os = it.string("os").ifBlank { current.os }
                    if (name != current.serverName || os != current.os || versionChanged) {
                        current.serverName = name
                        current.os = os
                        if (versionChanged) current.appVersion = announcedVersion
                        store.save(devices)
                    }
                }
                renderDevices()
                // A fast restart can replace the peer without an intervening offline status.
                if (online && (!wasOnline || (versionChanged && current.pendingAgentUpdateFrom.isNotBlank()))) {
                    bootstrap()
                } else updateControls()
            }
            override fun onRpcEvent(message: JSONObject) { if (device?.agentId == current.agentId) handleRpcEvent(message) }
            override fun onFailure(message: String) { if (mainVisible) toast(message) }
            override fun onDirectFallback() {
                if (online && device?.agentId == current.agentId) bootstrap()
            }
            override fun onDirectStatusChanged() {
                if (device?.agentId == current.agentId) {
                    updateConnectionIndicator()
                    updateDirectFiles()
                }
            }
            override fun onAuthorizationExpired() {
                pairMessage = "此设备的配对已失效，请重新配对"
                toast("配对已失效，请重新扫码"); showPair()
            }
        })
        client?.start()
    }

    private fun disconnect() {
        generation++
        activePendingSendId.takeIf { it.isNotEmpty() }?.let {
            outbox.failed(it, outcomeUnknown = true)
            activePendingSendId = ""
            persistOutbox()
        }
        client?.stop(); client = null
        updateDirectFiles()
        connectionDialog?.dismiss()
        invalidateSnapshots()
        devicePopup?.dismiss()
        devicesExpanded = false
        updateBackHandling()
        devices.forEach { it.online = false }
        online = false; loaded = false; bootstrapping = false; sending = false; loading = false
        if (requests.isNotEmpty()) approvalRecoveryMessage = "连接中断，原审批仅作保留展示；重连并同步前不可操作。"
        responding.clear(); messageQueues.clear()
        deletingThreads.clear()
        handler.removeCallbacks(elapsedTicker)
        if (mainBuilt) { renderApproval(); renderQueue(); renderDevices(); updateControls() }
    }

    private fun bootstrap() {
        val current = device ?: return
        if (bootstrapping || !online) return
        bootstrapping = true
        bootstrapError = ""
        val currentGeneration = ++generation
        loaded = false
        if (requests.isNotEmpty()) approvalRecoveryMessage = "正在从设备恢复待处理审批…"
        responding.clear(); messageQueues.clear()
        renderApproval(); renderQueue(); updateControls()
        rpc("codexrm/info") { result, error ->
            if (!active(currentGeneration)) return@rpc
            if (error != null || result == null) {
                bootstrapping = false
                bootstrapError = error?.message ?: "无法读取设备信息"
                if (requests.isNotEmpty()) approvalRecoveryMessage = "审批尚未重新同步。请重试初始化，旧审批不会静默清除。"
                renderApproval(); updateControls()
                toast(bootstrapError)
                return@rpc
            }
            // codexrm/info replays the agent's authoritative pending requests after its response.
            val recoveringApprovals = requests.isNotEmpty()
            requests.clear(); requestScopes.clear(); responding.clear()
            approvalRecoveryMessage = if (recoveringApprovals) "审批状态已从设备重新同步；仍待处理的审批会重新显示。" else ""
            info = result
            val version = info.string("version")
            val updated = current.confirmAgentUpdate(version)
            if (version.isNotBlank() && (version != current.appVersion || updated)) {
                current.appVersion = version
                store.save(devices)
                renderDevices()
            }
            if (updated) toast("电脑 Agent 更新成功（v$version）")
            updateDirectFiles()
            welcomeDescription.text = if (info.optBoolean("mock")) "已连接演示设备，可以发送任务体验完整流程。" else "已连接 ${info.string("name")}，让 Codex 继续手边的工作。"
            if (currentCwd.isEmpty()) currentCwd = store.selectedWorkspace(currentAgent()).ifEmpty { info.string("cwd") }
            bindNewSessionDraft()
            renderApproval()
            loadWorkspaces(currentGeneration); loadModels(); loadThreads(currentGeneration, reopenCurrent = true)
        }
    }

    private fun refreshAll() {
        if (!online) { toast("设备尚未连接"); return }
        if (!loaded) { bootstrap(); return }
        loadWorkspaces(generation); loadModels(refresh = true); loadThreads(generation)
        if (currentThreadId.isNotEmpty()) openThread(currentThreadId, silent = true)
    }

    private fun loadWorkspaces(currentGeneration: Int) {
        rpc("codexrm/workspace/list") { result, error ->
            if (!active(currentGeneration)) return@rpc
            if (result != null) {
                val array = result.arrayAt("workspaces")
                workspaces = (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { Workspace(it.string("name"), it.string("path")) }
                }
                val saved = store.selectedWorkspace(currentAgent())
                if (currentCwd.isEmpty() || workspaces.none { pathKey(it.path) == pathKey(currentCwd) }) {
                    currentCwd = saved.takeIf { saved.isNotEmpty() } ?: workspaces.firstOrNull()?.path ?: info.string("cwd")
                    bindNewSessionDraft()
                }
                refreshWorkspaceSpinner(); renderWorkspaces(); updateControls()
            } else if (error != null && info.string("cwd").isNotEmpty()) {
                workspaces = listOf(Workspace(folderName(info.string("cwd")), info.string("cwd")))
                refreshWorkspaceSpinner(); renderWorkspaces()
            }
        }
    }

    private fun loadThreads(currentGeneration: Int = generation, cursor: String? = null, accumulated: List<ThreadSummary> = emptyList(), seen: Set<String> = emptySet(), reopenCurrent: Boolean = false, listVersion: Int = ++threadListVersion) {
        if (cursor == null) threadListLoading = true
        val params = JSONObject().put("limit", 100).put("sortKey", "updated_at").put("sortDirection", "desc")
            .put("modelProviders", JSONArray()).put("archived", false)
        cursor?.let { params.put("cursor", it) }
        rpc("thread/list", params) { result, error ->
            if (!active(currentGeneration) || listVersion != threadListVersion) return@rpc
            if (error != null || result == null) {
                threadListLoading = false
                if (reopenCurrent) {
                    bootstrapping = false
                    bootstrapError = "无法读取会话：${error?.message ?: "响应无效"}"
                }
                renderWorkspaces(); updateControls(); toast("无法读取会话：${error?.message}"); return@rpc
            }
            val array = result.arrayAt("data")
            val merged = LinkedHashMap<String, ThreadSummary>()
            accumulated.forEach { merged[it.id] = it }
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(ProtocolFormatter::thread)?.takeIf { it.id.isNotEmpty() }?.let { merged[it.id] = it }
            threads = merged.values.toList(); threadCursor = if (result.isNull("nextCursor")) null else result.string("nextCursor")
            renderWorkspaces()
            if (threadCursor != null && !seen.contains(threadCursor) && seen.size < 20) {
                loadThreads(currentGeneration, threadCursor, threads, seen + threadCursor!!, reopenCurrent, listVersion)
            } else {
                threadListLoading = false; loaded = true
                if (reopenCurrent) { bootstrapping = false; bootstrapError = "" }
                renderWorkspaces(); updateControls()
                bindNewSessionDraft()
                if (reopenCurrent) {
                    val reopen = restoreThreadId.ifEmpty { currentThreadId }
                    restoreThreadId = ""
                    if (reopen.isNotEmpty() && !loading && !sending) openThread(reopen, silent = true)
                }
            }
        }
    }

    private fun loadModels(refresh: Boolean = false) {
        if (!online) return
        if (info.optBoolean("providerModels")) {
            rpc("codexrm/model/list") { result, error ->
                if (result != null) parseProviderModels(result)
                if (refresh) toast(if (error == null) "模型列表已刷新" else error.message)
            }
        } else rpc("model/list", JSONObject().put("limit", 100)) { result, _ ->
            if (result == null) return@rpc
            val provider = JSONObject().put("name", "当前 Codex 配置").put("models", result.arrayAt("data"))
            modelCatalog = ModelCatalog(JSONObject().put("providers", JSONArray().put(provider)))
            refreshModelSpinner()
        }
    }

    private fun parseProviderModels(result: JSONObject) {
        modelCatalog = ModelCatalog(result)
        refreshModelSpinner()
        val warnings = result.arrayAt("warnings")
        if (warnings.length() > 0) toast((0 until warnings.length()).joinToString("；") { warnings.optString(it) })
    }

    private fun refreshModelSpinner() {
        if (!mainBuilt) return
        val thread = currentThread
        if (thread != null && thread.string("model").isNotEmpty()) {
            showModelOptions(modelCatalog.providerId(thread.string("modelProvider")), thread.string("model"), selectedEffort())
        } else {
            val (provider, model, effort) = store.modelPreference(currentAgent(), pathKey(currentCwd))
            showModelOptions(provider, model, effort)
        }
    }

    private fun showModelOptions(provider: String, model: String, effort: String) {
        models = modelCatalog.options(provider)
        selectedModelOption = models.firstOrNull { it.model == model && it.providerId == provider }
        updatingSpinners = true
        updateModelButton()
        updateEfforts(preserve = false, preferred = effort)
        updatingSpinners = false
    }

    private fun updateModelButton() {
        if (!mainBuilt || !::modelButton.isInitialized) return
        val selected = selectedModelOption
        modelButton.text = selected?.displayName ?: "默认模型"
        modelButton.contentDescription = "选择模型，当前为${selected?.let { "${it.providerName} ${it.displayName}" } ?: "默认模型"}"
    }

    private fun showModelPicker() {
        if (!online || sending || loading || running || readOnly) {
            toast(if (running) "任务运行中，停止后可切换模型" else "设备连接并同步后可切换模型")
            return
        }
        val content = Ui.column(this).apply { setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0) }
        val search = Ui.boxInput(this, "搜索模型或供应商").apply { minHeight = Ui.dp(context, 48) }
        content.addView(search, matchWidth(top = 8, bottom = 8))
        val list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            isVerticalScrollBarEnabled = true
        }
        content.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, min(Ui.dp(this, 520), (resources.displayMetrics.heightPixels * 0.65).toInt())))
        val dialog = AlertDialog.Builder(this).setTitle("选择模型").setView(content).setNegativeButton("关闭", null).create()
        fun renderRows(rows: List<ModelPickerRow>) {
            list.adapter = object : BaseAdapter() {
                override fun getCount(): Int = rows.size
                override fun getItem(position: Int): ModelPickerRow = rows[position]
                override fun getItemId(position: Int): Long = position.toLong()
                override fun isEnabled(position: Int): Boolean = !rows[position].header
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val row = rows[position]
                    if (row.header) return Ui.text(this@MainActivity, row.title, 12f, Ui.MUTED, true).apply {
                        minHeight = Ui.dp(context, 48)
                        setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 4))
                    }
                    val selected = row.option == selectedModelOption
                    val value = Ui.column(this@MainActivity).apply {
                        minimumHeight = Ui.dp(context, 56)
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
                        background = Ui.ripple(if (selected) Ui.SURFACE_ALT else Color.TRANSPARENT, Ui.dp(context, 8).toFloat())
                        contentDescription = "${row.title}，${row.notes}${if (selected) "，当前选中" else ""}"
                    }
                    value.addView(Ui.text(this@MainActivity, "${if (selected) "✓ " else ""}${row.title}", 14f, if (selected) Ui.GREEN else Ui.TEXT, selected))
                    if (row.notes.isNotEmpty()) value.addView(Ui.text(this@MainActivity, row.notes, 11f, Ui.MUTED).apply { maxLines = 2 })
                    return value
                }
            }
        }
        renderRows(ModelPicker.rows(models, ""))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { renderRows(ModelPicker.rows(models, s?.toString().orEmpty())) }
        })
        list.setOnItemClickListener { _, _, position, _ ->
            val row = list.adapter.getItem(position) as ModelPickerRow
            if (row.header) return@setOnItemClickListener
            selectedModelOption = row.option
            updateModelButton()
            updateEfforts(preserve = false, preferred = row.option?.defaultEffort.orEmpty())
            saveModelPreference()
            dialog.dismiss()
        }
        styledDialog(dialog)
    }

    private fun updateEfforts(preserve: Boolean, preferred: String = "") {
        val previous = if (preserve) selectedEffort() else preferred
        val model = selectedModel(); val values = listOf("Default") + model?.efforts.orEmpty()
        updatingSpinners = true; setSpinnerItems(effortSpinner, values)
        val wanted = previous.ifEmpty { model?.defaultEffort.orEmpty() }
        val index = values.indexOf(wanted); effortSpinner.setSelection(if (index >= 0) index else 0); updatingSpinners = false
    }

    private fun saveModelPreference() {
        val model = selectedModel()
        store.saveModelPreference(currentAgent(), pathKey(currentCwd), model?.providerId.orEmpty(), model?.model.orEmpty(), selectedEffort())
    }

    private fun refreshWorkspaceSpinner() {
        if (!mainBuilt) return
        updatingSpinners = true
        val values = workspaceValues()
        val labels = if (values.isEmpty()) listOf("点击设置工作目录") else values.map { it.path }
        setSpinnerItems(workspaceSpinner, labels, labels.map { "" })
        workspaceSpinner.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
        val index = values.indexOfFirst { pathKey(it.path) == pathKey(currentCwd) }
        workspaceSpinner.setSelection(if (index >= 0) index else 0); updatingSpinners = false
    }

    private fun workspaceValues(): List<Workspace> {
        val values = workspaces.filter { it.path.isNotEmpty() }.toMutableList()
        if (currentCwd.isNotEmpty() && values.none { pathKey(it.path) == pathKey(currentCwd) }) values.add(0, Workspace(folderName(currentCwd), currentCwd))
        return values
    }

    private fun selectedModel(): ModelOption? = selectedModelOption
    private fun selectedEffort(): String = selectedModel()?.efforts?.getOrNull(effortSpinner.selectedItemPosition - 1).orEmpty()

    private fun resetConversation() {
        generation++; openGeneration++; invalidateSnapshots()
        currentThread = null; currentThreadId = ""; openingThreadId = ""; currentCwd = ""; info = JSONObject()
        modelCatalog = ModelCatalog(); selectedModelOption = null
        threads = emptyList(); workspaces = emptyList(); models = emptyList(); messageQueues.clear(); requests.clear(); requestScopes.clear()
        outbox = OutboxState(store.pendingSends(currentAgent())); activePendingSendId = ""
        persistOutbox()
        bootstrapping = false; bootstrapError = ""; approvalRecoveryMessage = ""
        running = false; readOnly = false; loading = false; historyCursor = null
        collapsedPaths = store.collapsed(currentAgent())
        rebindComposer(ComposerBinding(currentAgent(), "", ""))
        clearTranscript()
        if (mainBuilt) { refreshModelSpinner(); renderApproval(); renderQueue(); updateControls() }
    }

    private fun newChat() {
        if (sending || loading) { toast("请等待当前请求完成"); return }
        saveDraft(); openGeneration++; invalidateSnapshots()
        currentThread = null; currentThreadId = ""; openingThreadId = ""; running = false; readOnly = false
        activeTurnId = ""; historyCursor = null; historyOlder = false; messageQueues.remove("")
        val restore = rebindComposer(ComposerBinding(currentAgent(), "", currentCwd))
        clearTranscript(); restoreDraft(restore); renderApproval(); renderQueue(); updateControls(); composer.requestFocus()
    }

    private fun clearTranscript() {
        latestTurn = null
        items.clear(); deltaBuffers.clear(); userMessages.clear(); historyMessageIds.clear()
        if (mainBuilt) adapter.clear()
    }

    private fun selectWorkspace(path: String) {
        if (sending || loading) { refreshWorkspaceSpinner(); return }
        store.selectWorkspace(currentAgent(), path); currentCwd = path; collapsedPaths.remove(pathKey(path)); store.saveCollapsed(currentAgent(), collapsedPaths)
        newChat(); refreshWorkspaceSpinner(); refreshModelSpinner(); renderWorkspaces()
    }

    private fun openThread(id: String, silent: Boolean = false) {
        if (!online || loading || sending || id.isEmpty() || id in deletingThreads) return
        saveDraft(); val currentOpen = ++openGeneration
        loading = true; openingThreadId = id; readOnly = false; invalidateSnapshots()
        rebindComposer(ComposerBinding(currentAgent(), id, currentCwd))
        if (!silent) closeSidebar(); updateControls()
        rpc("thread/resume", JSONObject().put("threadId", id)) { result, error ->
            if (!openActive(id, currentOpen)) return@rpc
            if (result != null) { finishOpenThread(id, currentOpen, result, false); return@rpc }
            val message = error?.message.orEmpty()
            when {
                message.contains("active writer", true) -> {
                    val method = if (info.optBoolean("threadSnapshots")) "codexrm/thread/snapshot" else "thread/read"
                    val params = JSONObject().put("threadId", id); if (method == "thread/read") params.put("includeTurns", true)
                    rpc(method, params) { fallback, fallbackError ->
                        if (fallback != null) finishOpenThread(id, currentOpen, fallback, true) else failOpenThread(currentOpen, fallbackError?.message)
                    }
                }
                message.contains("paginat", true) || message.contains("excludeTurns", true) || message.contains("history mode", true) ->
                    rpc("thread/resume", JSONObject().put("threadId", id).put("excludeTurns", true)) { fallback, fallbackError ->
                        if (fallback != null) finishOpenThread(id, currentOpen, fallback, false) else failOpenThread(currentOpen, fallbackError?.message)
                    }
                else -> failOpenThread(currentOpen, message)
            }
        }
    }

    private fun finishOpenThread(id: String, currentOpen: Int, result: JSONObject, isReadOnly: Boolean) {
        if (!openActive(id, currentOpen)) return
        val thread = result.optJSONObject("thread"); if (thread == null) { failOpenThread(currentOpen, "会话响应无效"); return }
        if (thread.string("historyMode") == "paginated" && !(isReadOnly && info.optBoolean("threadSnapshots"))) {
            val params = JSONObject().put("threadId", id).put("limit", 30).put("sortDirection", "desc").put("itemsView", "full")
            rpc("thread/turns/list", params) { page, error ->
                if (!openActive(id, currentOpen)) return@rpc
                if (error != null || page == null) { failOpenThread(currentOpen, error?.message); return@rpc }
                val data = page.arrayAt("data"); val turns = JSONArray()
                for (index in data.length() - 1 downTo 0) turns.put(data.opt(index))
                thread.put("turns", turns); result.put("nextCursor", page.opt("nextCursor") ?: JSONObject.NULL)
                renderOpenThread(id, result, thread, isReadOnly)
            }
        } else renderOpenThread(id, result, thread, isReadOnly)
    }

    private fun renderOpenThread(id: String, result: JSONObject, thread: JSONObject, isReadOnly: Boolean) {
        currentThread = thread; currentThreadId = id; openingThreadId = ""; currentCwd = thread.string("cwd").ifEmpty { currentCwd }
        if (composerBinding.agentId == currentAgent() && composerBinding.threadId == id) {
            val binding = composerBinding.copy(cwd = currentCwd)
            composerBinding = binding
            composerRestoreToken = composerRestoreToken.copy(binding = binding)
        }
        store.selectWorkspace(currentAgent(), currentCwd)
        readOnly = isReadOnly; running = false; activeTurnId = ""; historyOlder = false
        historyCursor = if (result.isNull("nextCursor")) null else result.string("nextCursor").takeIf { it.isNotEmpty() }
        clearTranscript()
        val turns = thread.arrayAt("turns")
        for (index in 0 until turns.length()) renderTurn(turns.optJSONObject(index) ?: continue)
        if (readOnly) adapter.add(ChatMessage("readonly", MessageKind.STATUS, "", "此会话由桌面客户端管理，自动同步已保存的消息。结束原会话后可重新打开继续。"))
        selectThreadModel(result, thread); loading = false; restoreDraft(composerRestoreToken); renderQueue(); loadMessageQueue(id)
        refreshWorkspaceSpinner(); renderWorkspaces(); renderApproval(); messageList.setSelection(adapter.count - 1); updateControls()
        if (readOnly) scheduleSnapshot()
    }

    private fun renderTurn(turn: JSONObject, collector: MutableList<ChatMessage>? = null) {
        if (collector != null) {
            collector += TurnRenderer.messages(turn, readOnly)
            return
        }
        latestTurn = TurnProgress.from(turn)
        userMessages.observeTurn(turn.string("id"))
        if (!readOnly && turn.string("status") == "inProgress") { running = true; activeTurnId = turn.string("id"); startedAt = System.currentTimeMillis() }
        val turnItems = turn.arrayAt("items")
        for (itemIndex in 0 until turnItems.length()) turnItems.optJSONObject(itemIndex)?.let { upsertItem(turn.string("id"), it, immediate = true) }
        TurnRenderer.terminalMessages(turn, readOnly).forEach(adapter::add)
    }

    private fun failOpenThread(currentOpen: Int, message: String?) {
        if (currentOpen != openGeneration) return
        val edited = composerEditVersion != composerRestoreToken.editVersion
        val pendingText = composer.text.toString()
        saveDraft()
        loading = false; openingThreadId = ""
        val restore = rebindComposer(ComposerBinding(currentAgent(), currentThreadId, currentCwd))
        restoreDraft(restore)
        if (edited) { setComposerText(pendingText); composerEditVersion++ }
        updateControls(); toast(message.ifNullOrEmpty("无法打开会话"))
    }

    private fun selectThreadModel(result: JSONObject, thread: JSONObject) {
        val model = result.string("model").ifEmpty { thread.string("model") }
        if (model.isEmpty()) return
        val runtimeId = result.string("modelProvider").ifEmpty { thread.string("modelProvider") }
        thread.put("model", model)
        if (runtimeId.isNotEmpty()) thread.put("modelProvider", runtimeId)
        showModelOptions(modelCatalog.providerId(runtimeId), model, result.string("reasoningEffort"))
    }

    private fun loadOlderTurns() {
        val cursor = historyCursor ?: return
        if (!online || currentThreadId.isEmpty() || loading) return
        loading = true; updateControls()
        val id = currentThreadId
        val currentOpen = openGeneration
        val currentGeneration = generation
        val params = JSONObject().put("threadId", id).put("cursor", cursor).put("limit", 30).put("sortDirection", "desc").put("itemsView", "full")
        rpc("thread/turns/list", params) { page, error ->
            if (!active(currentGeneration) || currentOpen != openGeneration) return@rpc
            loading = false
            if (error != null || page == null || id != currentThreadId || cursor != historyCursor) { toast(error?.message); updateControls(); return@rpc }
            val data = page.arrayAt("data"); val older = ArrayList<ChatMessage>()
            for (index in data.length() - 1 downTo 0) {
                renderTurn(data.optJSONObject(index) ?: continue, older)
            }
            val firstPosition = messageList.firstVisiblePosition
            val firstTop = messageList.getChildAt(0)?.top ?: 0
            val merged = TranscriptState.prepend(adapter.snapshot(), older)
            adapter.replace(merged.messages)
            historyMessageIds += merged.historyIds
            messageList.setSelectionFromTop(firstPosition + merged.historyIds.size, firstTop)
            historyCursor = if (page.isNull("nextCursor")) null else page.string("nextCursor").takeIf { it.isNotEmpty() }
            historyOlder = true; updateControls()
        }
    }

    private fun invalidateSnapshots() {
        snapshotRequestVersion++
        snapshotRunning = false
        handler.removeCallbacks(snapshotTicker)
        detachedThreads.reset()
    }

    private fun scheduleSnapshot() {
        handler.removeCallbacks(snapshotTicker)
        val polling = readOnly || detachedThreads.isDetached(currentThreadId)
        if (polling && currentThreadId.isNotEmpty() && online) handler.postDelayed(snapshotTicker, 3000)
    }

    private fun refreshSnapshot() {
        val detached = detachedThreads.isDetached(currentThreadId)
        if ((!readOnly && !detached) || snapshotRunning || !online || currentThreadId.isEmpty()) return
        snapshotRunning = true
        val requestVersion = ++snapshotRequestVersion
        val id = currentThreadId; val currentGeneration = generation
        rpc("codexrm/thread/snapshot", JSONObject().put("threadId", id)) { result, error ->
            if (requestVersion != snapshotRequestVersion) return@rpc
            snapshotRunning = false
            val polling = readOnly || detachedThreads.isDetached(id)
            if (result != null && active(currentGeneration) && id == currentThreadId && polling) {
                val thread = result.optJSONObject("thread")
                if (thread != null) {
                    if (thread.objectAt("status").string("type") == "active") readOnly = true
                    val cursor = result.optString("nextCursor").takeIf { !result.isNull("nextCursor") && it.isNotEmpty() }
                    replaceLatestSnapshot(thread, cursor)
                    updateControls()
                }
            }
            scheduleSnapshot()
        }
    }

    private fun replaceLatestSnapshot(thread: JSONObject, nextCursor: String?) {
        val turns = thread.arrayAt("turns")
        val previous = currentThread?.arrayAt("turns")
        val oldest = turns.optJSONObject(0)?.string("id")?.takeIf { it.isNotEmpty() }
        val boundary = if (previous == null || oldest == null) -1 else
            (0 until previous.length()).indexOfFirst { previous.optJSONObject(it)?.string("id") == oldest }
        if (boundary < 0) {
            // A disjoint snapshot has a new cursor. Old pages can be loaded again.
            historyOlder = false
        } else if (boundary > 0 && previous != null) {
            for (index in 0 until boundary) {
                val turn = previous.optJSONObject(index) ?: continue
                TurnRenderer.messages(turn, readOnly).mapTo(historyMessageIds) { it.id }
            }
            historyOlder = true
        }
        if (!historyOlder) historyCursor = nextCursor
        currentThread = thread
        latestTurn = turns.optJSONObject(turns.length() - 1)?.let(TurnProgress::from)
        running = false; activeTurnId = ""
        val latest = ArrayList<ChatMessage>()
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            if (!readOnly && turn.string("status") == "inProgress") {
                running = true; activeTurnId = turn.string("id"); startedAt = System.currentTimeMillis()
            }
            renderTurn(turn, latest)
        }
        if (readOnly) latest += ChatMessage("readonly", MessageKind.STATUS, "", "此会话由桌面客户端管理，自动同步已保存的消息。")
        val merged = TranscriptState.latest(adapter.snapshot(), historyMessageIds, latest, historyOlder)

        items.clear(); deltaBuffers.clear(); dirtyDeltaIds.clear(); userMessages.clear()
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            userMessages.observeTurn(turn.string("id"))
            val turnItems = turn.arrayAt("items")
            for (itemIndex in 0 until turnItems.length()) {
                val item = turnItems.optJSONObject(itemIndex) ?: continue
                val itemId = item.string("id").ifEmpty { "${turn.string("id")}-${item.string("type")}" }
                if (!item.has("id")) item.put("id", itemId)
                if (item.string("type") == "commandExecution") item.put("aggregatedOutput", item.string("aggregatedOutput").bounded())
                items[itemId] = item
            }
        }
        historyMessageIds.clear(); historyMessageIds += merged.historyIds
        adapter.replace(merged.messages)
    }

    private fun attachmentDraftKey(): String = composerBinding.attachmentKey(::pathKey)

    private fun buildAttachmentChoices(): LinearLayout = Ui.column(this, false).apply {
        visibility = View.GONE
        fun addChoice(icon: String, label: String, action: () -> Unit) {
            val choice = Ui.column(context).apply {
                gravity = Gravity.CENTER
                minimumHeight = Ui.dp(context, 72)
                background = Ui.ripple(Ui.SURFACE_ALT, Ui.dp(context, 14).toFloat(), Ui.BORDER)
                isFocusable = true
                contentDescription = label
                addView(IconView(context, icon, Ui.TEXT), LinearLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24)))
                addView(Ui.text(context, label, 13f), LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = Ui.dp(context, 4) })
                setOnClickListener {
                    showAttachmentChoices(false)
                    action()
                }
            }
            addView(choice, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (childCount != 0) marginStart = Ui.dp(context, 8)
            })
        }
        addChoice("camera", "拍照", ::startAttachmentCamera)
        addChoice("image", "相册") { pickAttachments(imagesOnly = true) }
        addChoice("attach", "文件") { pickAttachments(imagesOnly = false) }
    }

    private fun toggleAttachmentChoices() {
        if (attachmentChoices.visibility == View.VISIBLE) {
            showAttachmentChoices(false)
            return
        }
        if (sending || loading || preparingAttachments || readOnly || editingQueueDraft == composerBinding) return
        if (!online || !loaded) { toast("设备尚未连接或正在同步"); return }
        if (!info.has("attachments")) { toast("请更新本地 Agent 以支持文件和图片"); return }
        showAttachmentChoices(true)
    }

    private fun showAttachmentChoices(show: Boolean) {
        attachmentChoices.visibility = if (show) View.VISIBLE else View.GONE
        (attachButton.getChildAt(0) as IconView).setIcon(if (show) "close" else "attach")
        attachButton.contentDescription = if (show) "关闭附件选项" else "添加文件或图片"
        updateBackHandling()
    }

    private fun pickAttachments(imagesOnly: Boolean) {
        attachmentPickerKey = attachmentDraftKey()
        try {
            if (imagesOnly && Build.VERSION.SDK_INT >= 33) {
                try {
                    startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                        type = "image/*"
                        putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 10)
                    }, PICK_COMPOSER_ATTACHMENT)
                    return
                } catch (_: ActivityNotFoundException) {
                    // Fall back to the document picker if the system photo picker is absent.
                }
            }
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (imagesOnly) "image/*" else "*/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, PICK_COMPOSER_ATTACHMENT)
        } catch (_: Exception) {
            attachmentPickerKey = ""
            toast(if (imagesOnly) "无法打开相册" else "无法打开文件选择器")
        }
    }

    private fun startAttachmentCamera() {
        attachmentPickerKey = attachmentDraftKey()
        // QR scanning declares CAMERA, so the permission must also be granted for ACTION_IMAGE_CAPTURE.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAPTURE_ATTACHMENT_PERMISSION)
        } else {
            takeAttachmentPhoto()
        }
    }

    private var pendingAlbumSave: (() -> Unit)? = null

    internal fun withAlbumPermission(save: () -> Unit) {
        if (Build.VERSION.SDK_INT >= 29 || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            save()
        } else if (pendingAlbumSave == null) {
            pendingAlbumSave = save
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), ALBUM_WRITE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ALBUM_WRITE_PERMISSION) {
            val save = pendingAlbumSave
            pendingAlbumSave = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) save?.invoke()
            else toast("保存到相册需要存储权限")
            return
        }
        if (requestCode != CAPTURE_ATTACHMENT_PERMISSION || attachmentPickerKey.isEmpty()) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            takeAttachmentPhoto()
        } else if (!shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            showCameraPermissionSettings()
        } else {
            attachmentPickerKey = ""
            toast("拍照需要相机权限，也可以改用相册或文件")
        }
    }

    private fun showCameraPermissionSettings() {
        styledDialog(AlertDialog.Builder(this)
            .setTitle("相机权限已关闭")
            .setMessage("系统不会再次弹出授权框。可在系统设置中允许相机；不授权也可以继续从相册或文件中添加。")
            .setPositiveButton("去系统设置") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Exception) { toast("无法打开系统设置，请改用相册或文件") }
            }
            .setNeutralButton("选择相册") { _, _ -> pickAttachments(imagesOnly = true) }
            .setNegativeButton("选择文件") { _, _ -> pickAttachments(imagesOnly = false) })
    }

    private fun takeAttachmentPhoto() {
        var temporaryPhoto: File? = null
        try {
            val directory = File(cacheDir, "attachment-camera").apply { mkdirs() }
            val photo = File.createTempFile("IMG_", ".jpg", directory)
            temporaryPhoto = photo
            attachmentCameraFile = photo.name
            val uri = attachmentCameraUri(photo)
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, uri)
                clipData = ClipData.newRawUri("附件照片", uri)
                addFlags(ATTACHMENT_CAMERA_URI_FLAGS)
            }, PICK_COMPOSER_ATTACHMENT)
        } catch (_: Exception) {
            temporaryPhoto?.delete()
            attachmentCameraFile = ""
            attachmentPickerKey = ""
            toast("无法打开相机")
        }
    }

    private fun attachmentCameraUri(photo: File): Uri =
        FileProvider.getUriForFile(this, "$packageName.attachment-camera", photo)

    private fun receiveAttachments(resultCode: Int, data: Intent?) {
        val key = attachmentPickerKey
        attachmentPickerKey = ""
        val photo = attachmentCameraFile.takeIf(String::isNotEmpty)?.let { File(cacheDir, "attachment-camera/$it") }
        attachmentCameraFile = ""
        val uris = mutableListOf<Uri>()
        if (photo != null) {
            val uri = attachmentCameraUri(photo)
            revokeUriPermission(uri, ATTACHMENT_CAMERA_URI_FLAGS)
            if (resultCode == RESULT_OK && photo.length() > 0) uris += uri
            else if (resultCode == RESULT_OK) toast("相机没有返回照片")
        } else if (resultCode == RESULT_OK) {
            data?.clipData?.let { clips ->
                for (index in 0 until clips.itemCount) clips.getItemAt(index).uri?.let(uris::add)
            }
            if (uris.isEmpty()) data?.data?.let(uris::add)
        }
        if (key.isEmpty() || uris.isEmpty()) {
            photo?.delete()
            return
        }
        preparingAttachments = true
        renderAttachments()
        updateControls()
        attachmentStore.stage(key, uris) { error ->
            photo?.delete()
            if (isDestroyed) return@stage
            preparingAttachments = false
            renderAttachments()
            updateControls()
            toast(error)
        }
    }

    private fun renderAttachments() {
        if (!mainBuilt) return
        attachmentFiles = attachmentStore.load(attachmentDraftKey())
        attachmentScroll.visibility = if (attachmentFiles.isEmpty()) View.GONE else View.VISIBLE
        attachmentStatus.visibility = if (preparingAttachments || attachmentFiles.isNotEmpty()) View.VISIBLE else View.GONE
        attachmentStatus.text = if (preparingAttachments) "正在读取附件…" else "附件 · ${attachmentFiles.size}"
        attachmentRows.removeAllViews()
        for (file in attachmentFiles) {
            val row = Ui.column(this, false).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rounded(Ui.SURFACE_ALT, Ui.dp(context, 9).toFloat(), Ui.BORDER)
                setPadding(Ui.dp(context, 6), Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4))
            }
            if (file.string("mimeType") in AttachmentStore.IMAGE_TYPES) {
                val local = runCatching { attachmentStore.localFile(file) }.getOrNull()
                if (local != null) {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(local.path, options)
                    options.inJustDecodeBounds = false
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 160) options.inSampleSize *= 2
                    val bitmap = BitmapFactory.decodeFile(local.path, options)
                    if (bitmap != null) row.addView(ImageView(this).apply { setImageBitmap(bitmap); scaleType = ImageView.ScaleType.CENTER_CROP }, LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)))
                }
            }
            row.addView(Ui.text(this, "${file.string("name")}\n${AttachmentStore.sizeLabel(file.optLong("size"))}", 11f).apply {
                maxLines = 2; maxWidth = Ui.dp(context, 140); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 4), 0)
            })
            row.addView(Ui.iconButton(this, "close", "移除 ${file.string("name")}", 36) {
                if (!sending && !preparingAttachments) { attachmentStore.remove(attachmentDraftKey(), file); renderAttachments(); updateSendState() }
            }.apply { isEnabled = !sending && !preparingAttachments })
            attachmentRows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 52)).apply { rightMargin = Ui.dp(this@MainActivity, 6) })
        }
    }

    private fun finishSentAttachments(key: String, files: List<JSONObject>) {
        for (file in files) attachmentStore.remove(key, file)
        saveDraft()
        if (key == attachmentDraftKey()) renderAttachments()
    }

    private fun retainSentAttachments(key: String, files: List<JSONObject>) {
        // Upload responses stay in their original agent/thread/cwd draft. Never migrate
        // Agent paths into whichever conversation happens to be visible on failure.
        if (key == attachmentDraftKey()) renderAttachments()
    }

    private fun canContinueTurn(): Boolean {
        if (currentThreadId.isEmpty() || readOnly || running || latestTurn?.canContinue != true) return false
        if (composer.text.isNotBlank() || attachmentFiles.isNotEmpty() || preparingAttachments || editingQueueDraft == composerBinding) return false
        val queue = messageQueues[currentThreadId]
        return queue == null || (!queue.optBoolean("dispatching") &&
            (queue.arrayAt("entries").length() == 0 || queue.optBoolean("paused")))
    }

    private fun continueTurn() {
        if (!canContinueTurn() || !online || !loaded || sending || loading || currentThreadId in deletingThreads) return
        val unresolved = outbox.matching(composerBinding, ::pathKey)
        if (unresolved.isNotEmpty()) { showPendingSend(unresolved.last().id); return }
        val text = "请从中断处继续完成上一项任务。先核对已完成的工作和当前状态，再处理剩余步骤。"
        val pending = PendingSend(
            UUID.randomUUID().toString(), currentAgent(), currentThreadId, currentCwd, text,
            emptyList(), attachmentDraftKey(), false, "", System.currentTimeMillis(), continuation = true,
        )
        outbox.begin(pending); activePendingSendId = pending.id; persistOutbox()
        sending = true; updateControls()
        startTurn(text, creating = false, openGeneration, emptyList(), pending)
    }

    private fun sendMessage(steer: Boolean) {
        if (currentThreadId in deletingThreads) { toast("正在删除此对话"); return }
        if (editingQueueDraft == composerBinding) return
        val unresolved = outbox.matching(composerBinding, ::pathKey)
        if (unresolved.isNotEmpty()) { showPendingSend(unresolved.last().id); return }
        val submittedText = composer.text.toString()
        val text = submittedText.trim()
        val files = attachmentFiles.toList()
        if ((text.isEmpty() && files.isEmpty()) || sending || loading || preparingAttachments) return
        if (!online || !loaded) { toast("设备尚未连接或正在同步"); return }
        if (readOnly) { toast("此会话为只读，请新建会话"); return }
        if (running && !steer && !info.optBoolean("messageQueue")) { toast("请更新本地 Agent 以支持排队发送"); return }
        if (files.isNotEmpty() && !info.has("attachments")) { toast("请更新本地 Agent 以支持文件和图片"); return }
        val sender = client ?: return
        val key = attachmentDraftKey()
        val pending = PendingSend(
            UUID.randomUUID().toString(), currentAgent(), currentThreadId, currentCwd,
            submittedText, files, key, steer, activeTurnId, System.currentTimeMillis(),
        )
        outbox.begin(pending); activePendingSendId = pending.id; persistOutbox()
        val currentGeneration = generation
        sending = true; updateControls()
        AttachmentUploader(attachmentStore, sender).upload(key, files, { progress ->
            if (active(currentGeneration)) attachmentStatus.text = progress
        }) { error ->
            if (!active(currentGeneration)) return@upload
            if (error != null) {
                outbox.failed(pending.id, outcomeUnknown = false); activePendingSendId = ""; persistOutbox()
                sending = false; renderAttachments(); updateControls(); toast(error.message); return@upload
            }
            imageLoader.retain(files, attachmentStore) { cacheError ->
                if (!active(currentGeneration)) return@retain
                if (cacheError != null) {
                    outbox.failed(pending.id, outcomeUnknown = false); activePendingSendId = ""; persistOutbox()
                    sending = false; renderAttachments(); updateControls(); toast(cacheError.message); return@retain
                }
                val remaining = ComposerText.afterSuccessfulSend(composer.text.toString(), submittedText)
                if (remaining != composer.text.toString()) setComposerText(remaining)
                saveDraft()
                dispatchMessage(text, steer, files, pending)
            }
        }
    }

    private fun dispatchMessage(text: String, steer: Boolean, files: List<JSONObject>, pending: PendingSend) {
        val sendOpenGeneration = openGeneration
        if (running) sendFollowup(text, steer, sendOpenGeneration, files, pending)
        else if (currentThread == null) {
            val params = JSONObject().put("sandbox", store.sandbox).put("approvalPolicy", store.approvalPolicy)
            if (currentCwd.isNotEmpty()) params.put("cwd", currentCwd)
            selectedModel()?.let { params.put("model", it.model).put("codexrmProvider", it.providerId) }
            rpc("thread/start", params) { result, error ->
                if (sendOpenGeneration != openGeneration) return@rpc
                if (error != null || result == null) { failSend(pending, error); return@rpc }
                currentThread = result.optJSONObject("thread"); currentThreadId = currentThread?.string("id").orEmpty()
                if (currentThreadId.isEmpty()) { failSend(pending, null, "新会话响应无效"); return@rpc }
                currentCwd = currentThread?.string("cwd").ifNullOrEmpty(currentCwd)
                val previousBinding = composerBinding
                val pendingText = composer.text.toString()
                val newBinding = ComposerBinding(currentAgent(), currentThreadId, currentCwd)
                rebindComposer(newBinding)
                val newAttachmentKey = newBinding.attachmentKey(::pathKey)
                if (pending.attachmentKey != newAttachmentKey) {
                    val merged = LinkedHashMap<String, JSONObject>()
                    (attachmentStore.load(newAttachmentKey) + attachmentStore.load(pending.attachmentKey)).forEach {
                        merged[it.string("draftId").ifEmpty { it.string("path") }] = it
                    }
                    attachmentStore.save(newAttachmentKey, merged.values.toList())
                    attachmentStore.save(pending.attachmentKey, emptyList())
                }
                val threadPending = outbox.threadStarted(pending.id, currentThreadId, currentCwd, newAttachmentKey)
                    ?: pending.copy(threadId = currentThreadId, cwd = currentCwd, attachmentKey = newAttachmentKey)
                persistOutbox()
                setComposerText(pendingText); renderAttachments(); saveDraft()
                val previousWorkspace = if (previousBinding.threadId.isEmpty()) pathKey(previousBinding.cwd) else null
                store.saveDraft(previousBinding.agentId, previousBinding.threadId, "", previousWorkspace)
                updateControls()
                startTurn(text, creating = true, sendOpenGeneration, files, threadPending)
            }
        } else startTurn(text, creating = false, sendOpenGeneration, files, pending)
    }

    private fun startTurn(text: String, creating: Boolean, sendOpenGeneration: Int, files: List<JSONObject>, pending: PendingSend) {
        val optimisticId = "optimistic-${System.nanoTime()}"
        val input = ProtocolFormatter.input(text, files)
        userMessages.begin(optimisticId, input)
        adapter.add(ProtocolFormatter.fromItem(JSONObject().put("id", optimisticId).put("type", "userMessage").put("content", input)))
        val threadId = currentThreadId
        val previousTurn = latestTurn
        rpc("turn/start", turnParams(threadId, text, files)) { result, error ->
            if (sendOpenGeneration != openGeneration || currentThreadId != threadId) return@rpc
            if (error != null || result == null) {
                userMessages.cancel(optimisticId); adapter.remove(optimisticId)
                retainSentAttachments(pending.attachmentKey, files); failSend(pending, error); return@rpc
            }
            sending = false; activePendingSendId = ""; outbox.confirmed(pending.id); persistOutbox()
            finishSentAttachments(pending.attachmentKey, files)
            val turn = result.optJSONObject("turn"); if (turn == null) { updateControls(); return@rpc }
            val started = TurnProgress.from(turn)
            if (latestTurn == previousTurn) {
                latestTurn = started
                activeTurnId = started.id
                running = started.status == "inProgress"
                startedAt = System.currentTimeMillis()
            }
            reconcileUserMessage(optimisticId, started.id)
            val turnItems = turn.arrayAt("items")
            for (index in 0 until turnItems.length()) {
                val item = turnItems.optJSONObject(index) ?: continue
                upsertItem(started.id, item, immediate = true)
            }
            if (creating) rpc("thread/name/set", JSONObject().put("threadId", threadId).put("name", text.ifEmpty { files.firstOrNull()?.string("name").orEmpty() }.lineSequence().first().take(40))) { _, _ -> }
            updateControls()
        }
    }

    private fun sendFollowup(text: String, steer: Boolean, sendOpenGeneration: Int, files: List<JSONObject>, pending: PendingSend) {
        val threadId = currentThreadId; val optimisticId = "steer-${System.nanoTime()}"
        if (steer) {
            val turnId = pending.expectedTurnId
            val input = ProtocolFormatter.input(text, files)
            userMessages.begin(optimisticId, input, turnId)
            adapter.add(ProtocolFormatter.fromItem(JSONObject().put("id", optimisticId).put("type", "userMessage").put("content", input)))
            val params = JSONObject().put("threadId", threadId).put("expectedTurnId", turnId).put("input", input)
            rpc("turn/steer", params) { result, error ->
                if (sendOpenGeneration != openGeneration) return@rpc
                if (error != null) {
                    userMessages.cancel(optimisticId); adapter.remove(optimisticId)
                    retainSentAttachments(pending.attachmentKey, files); failSend(pending, error)
                } else {
                    sending = false; activePendingSendId = ""; outbox.confirmed(pending.id); persistOutbox()
                    reconcileUserMessage(optimisticId, result?.string("turnId").ifNullOrEmpty(turnId))
                    finishSentAttachments(pending.attachmentKey, files); toast("补充指令已发送"); updateControls()
                }
            }
        } else rpc("codexrm/queue/add", turnParams(threadId, text, files)) { result, error ->
            if (sendOpenGeneration != openGeneration) return@rpc
            if (error != null) { retainSentAttachments(pending.attachmentKey, files); failSend(pending, error) }
            else {
                sending = false; activePendingSendId = ""; outbox.confirmed(pending.id); persistOutbox()
                finishSentAttachments(pending.attachmentKey, files); result?.let(::acceptMessageQueue); toast("消息已排队"); updateControls()
            }
        }
    }

    private fun failSend(pending: PendingSend, error: Exception?, fallback: String = "消息发送失败") {
        val unknown = error is RpcOutcomeUnknown
        sending = false; activePendingSendId = ""
        outbox.failed(pending.id, unknown); persistOutbox()
        if (!pending.continuation) {
            val restored = ComposerText.afterFailedSend(composer.text.toString(), pending.submittedText)
            if (restored != composer.text.toString()) setComposerText(restored)
            saveDraft()
        }
        updateControls()
        toast(if (unknown) "发送状态未确认，内容已保留。请先核对会话历史；不会自动重发。" else error?.message ?: fallback)
    }

    private fun turnParams(threadId: String, text: String, files: List<JSONObject> = emptyList()): JSONObject {
        val params = JSONObject().put("threadId", threadId).put("approvalPolicy", store.approvalPolicy)
            .put("sandboxPolicy", ProtocolFormatter.sandboxPolicy(store.sandbox)).put("input", ProtocolFormatter.input(text, files))
        selectedModel()?.let {
            params.put("model", it.model); val effort = selectedEffort().ifEmpty { it.defaultEffort }
            if (effort.isNotEmpty()) params.put("effort", effort)
        }
        return params
    }

    private fun stopTurn() {
        if (readOnly || !running || activeTurnId.isEmpty() || sending) return
        rpc("turn/interrupt", JSONObject().put("threadId", currentThreadId).put("turnId", activeTurnId)) { _, error -> toast(error?.message ?: "已请求停止任务，排队消息将保留") }
    }

    // Keep a distinct name from Listener.onRpcEvent so forwarding cannot recurse into the listener.
    private fun handleRpcEvent(message: JSONObject) {
        if (message.has("id")) {
            val key = requestKey(message.opt("id"))
            val params = message.objectAt("params")
            requests[key] = message
            requestScopes[key] = ApprovalScope(currentAgent(), params.string("threadId"), params.string("turnId"))
            renderApproval(); return
        }
        val method = message.string("method"); val params = message.objectAt("params")
        when (method) {
            "codexrm/thread/detached" -> {
                if (detachedThreads.detach(currentThreadId, params.string("threadId"))) scheduleSnapshot()
                return
            }
            "codexrm/thread/loaded" -> {
                val id = params.string("threadId")
                if (id == currentThreadId) {
                    val wasDetached = detachedThreads.loaded(id)
                    if (wasDetached || readOnly) {
                        invalidateSnapshots()
                        readOnly = false
                        adapter.remove("readonly")
                        updateControls()
                    }
                }
                return
            }
            "codexrm/queue/updated" -> { acceptMessageQueue(params); return }
            "codexrm/request/resolved", "serverRequest/resolved" -> {
                val id = params.opt("id") ?: params.opt("requestId"); if (id != null) {
                    val key = requestKey(id); requests.remove(key); requestScopes.remove(key); responding.remove(key)
                }
                renderApproval(); return
            }
            "codexrm/request/failed" -> {
                params.opt("id")?.let { responding.remove(requestKey(it)) }
                toast(params.string("message").ifEmpty { "操作未发送，请重试" }); renderApproval(); return
            }
            "thread/started" -> { params.optJSONObject("thread")?.let { loadThreads(generation) }; return }
            "thread/name/updated" -> {
                if (currentThreadId == params.string("threadId")) {
                    currentThread?.put("name", params.string("threadName").ifEmpty { params.string("name") }); updateControls()
                }
                loadThreads(generation); return
            }
            "thread/archived" -> { removeArchivedThread(params.string("threadId")); loadThreads(generation); return }
        }
        val turn = params.optJSONObject("turn")
        val threadId = params.string("threadId").ifEmpty { turn?.string("threadId").orEmpty() }
        if (method == "turn/started" || method == "turn/completed") {
            val updatedAt = System.currentTimeMillis() / 1000
            threads = threads.map {
                if (it.id == threadId) it.copy(active = method == "turn/started", updatedAt = updatedAt) else it
            }
            renderWorkspaces()
        }
        if (method == "turn/completed" && turn != null) {
            val iterator = requests.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next(); val p = entry.value.objectAt("params")
                val nonBlockingQuestion = entry.value.string("method") == "item/tool/requestUserInput" && !p.optBoolean("isBlocking", true)
                if (p.string("threadId") == threadId && p.string("turnId") == turn.string("id") &&
                    (!nonBlockingQuestion || turn.string("status") != "completed")) {
                    responding.remove(entry.key); requestScopes.remove(entry.key); iterator.remove()
                }
            }
            renderApproval()
        }
        if (threadId.isNotEmpty() && threadId != currentThreadId) return
        when (method) {
            "turn/started" -> if (turn != null) {
                latestTurn = TurnProgress.from(turn)
                activeTurnId = turn.string("id"); running = true; startedAt = System.currentTimeMillis()
                userMessages.started(activeTurnId)
                val turnItems = turn.arrayAt("items"); for (index in 0 until turnItems.length()) turnItems.optJSONObject(index)?.let { upsertItem(turn.string("id"), it) }
                updateControls(); handler.removeCallbacks(elapsedTicker); handler.post(elapsedTicker)
            }
            "turn/completed" -> if (turn != null) {
                val finished = TurnProgress.from(turn)
                if (!running || activeTurnId == finished.id) {
                    latestTurn = finished
                    running = false; handler.removeCallbacks(elapsedTicker)
                }
                val turnItems = turn.arrayAt("items"); for (index in 0 until turnItems.length()) turnItems.optJSONObject(index)?.let { upsertItem(turn.string("id"), it) }
                turn.optJSONObject("error")?.let { adapter.add(ChatMessage("error-${turn.string("id")}", MessageKind.ERROR, "错误", it.string("message"))) }
                if (turn.string("status") == "interrupted") adapter.add(ChatMessage("stopped-${turn.string("id")}", MessageKind.STATUS, "", "任务已停止"))
                updateControls(); scheduleRender()
            }
            "item/started", "item/completed" -> params.optJSONObject("item")?.let { upsertItem(params.string("turnId"), it) }
            "item/agentMessage/delta" -> appendDelta(params, "agentMessage", "text")
            "item/commandExecution/outputDelta" -> appendDelta(params, "commandExecution", "aggregatedOutput")
            "item/reasoning/textDelta", "item/reasoning/summaryTextDelta" -> appendDelta(params, "reasoning", "reasoningText")
            "turn/plan/updated" -> upsertItem(params.string("turnId"), JSONObject().put("id", "plan-${params.string("turnId")}").put("type", "plan").put("text", params.string("explanation")).put("plan", params.arrayAt("plan")))
            "turn/diff/updated" -> upsertItem(params.string("turnId"), JSONObject().put("id", "diff-${params.string("turnId")}").put("type", "diff").put("diff", params.string("diff")))
            "error" -> {
                val turnId = params.string("turnId")
                if (threadId == currentThreadId && turnId.isNotEmpty() && !params.optBoolean("willRetry") &&
                    (latestTurn == null || latestTurn?.id == turnId)) {
                    latestTurn = TurnProgress(turnId, "failed")
                    running = false
                    handler.removeCallbacks(elapsedTicker)
                }
                val error = params.optJSONObject("error")
                adapter.add(ChatMessage("error-${System.nanoTime()}", MessageKind.ERROR, "Codex 错误", error?.string("message").ifNullOrEmpty("Codex 发生错误")))
                scheduleRender(); updateControls()
            }
        }
    }

    private fun upsertItem(turnId: String, item: JSONObject, immediate: Boolean = false) {
        val id = item.string("id").ifEmpty { "$turnId-${item.string("type")}" }; if (!item.has("id")) item.put("id", id)
        if (item.string("type") == "commandExecution") item.put("aggregatedOutput", item.string("aggregatedOutput").bounded())
        items[id] = item
        deltaBuffers.remove("$id:text"); deltaBuffers.remove("$id:reasoningText"); deltaBuffers.remove("$id:aggregatedOutput")
        dirtyDeltaIds.remove(id)
        val localId = if (item.string("type") == "userMessage") userMessages.accept(turnId, id, item.arrayAt("content")) else null
        val existing = adapter.message(id)
        if (localId != null) adapter.replaceMessage(localId, ProtocolFormatter.fromItem(item))
        else if (existing == null) adapter.add(ProtocolFormatter.fromItem(item)) else ProtocolFormatter.update(existing, item)
        if (immediate) adapter.notifyDataSetChanged() else scheduleRender()
    }

    private fun reconcileUserMessage(localId: String, turnId: String) {
        val itemId = userMessages.acknowledge(localId, turnId) ?: return
        items[itemId]?.let { adapter.replaceMessage(localId, ProtocolFormatter.fromItem(it)) }
    }

    private fun appendDelta(params: JSONObject, type: String, field: String) {
        val id = params.string("itemId"); if (id.isEmpty()) return
        val key = "$id:$field"
        val buffer = deltaBuffers.getOrPut(key) {
            val baseline = items[id]?.string(field).orEmpty().ifEmpty { adapter.message(id)?.body.orEmpty() }
            DeltaBuffer(id, type, field, StringBuilder(baseline))
        }
        buffer.text.append(params.string("delta"))
        if (type == "commandExecution" && buffer.text.length > 65_536) buffer.text.delete(0, buffer.text.length - 65_536)
        dirtyDeltaIds.add(id)
        scheduleRender()
    }

    private fun flushDeltas() {
        if (dirtyDeltaIds.isEmpty()) return
        val dirty = dirtyDeltaIds.toList(); dirtyDeltaIds.clear()
        dirty.forEach { id ->
            val buffer = deltaBuffers.values.firstOrNull { it.id == id } ?: return@forEach
            val text = buffer.text.toString()
            val existing = adapter.message(id)
            if (existing == null) {
                val message = when (buffer.type) {
                    "agentMessage" -> ChatMessage(id, MessageKind.ASSISTANT, "Codex", text)
                    "reasoning" -> ChatMessage(id, MessageKind.TOOL, "思考过程", text)
                    else -> ChatMessage(id, MessageKind.TOOL, "运行命令", text, "执行中")
                }
                adapter.add(message)
            } else if (buffer.type == "commandExecution") {
                val command = items[id]?.string("command").orEmpty()
                existing.change(body = listOf(command, text).filter { it.isNotEmpty() }.joinToString("\n\n"), status = "执行中")
            } else existing.change(body = text)
        }
    }

    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        messageList.postDelayed({
            renderScheduled = false
            flushDeltas()
            adapter.notifyDataSetChanged()
        }, 80)
    }

    private fun loadMessageQueue(threadId: String) {
        if (!info.optBoolean("messageQueue") || threadId.isEmpty()) return
        rpc("codexrm/queue/list", JSONObject().put("threadId", threadId)) { result, _ -> if (result != null && currentThreadId == threadId) acceptMessageQueue(result) }
    }

    private fun acceptMessageQueue(queue: JSONObject) {
        val id = queue.string("threadId"); if (id.isEmpty()) return
        val previous = messageQueues[id]
        if (previous == null || previous.optLong("version") <= queue.optLong("version")) messageQueues[id] = queue
        if (id == currentThreadId) { renderQueue(); updateControls() }
    }

    private fun renderQueue() {
        if (!mainBuilt) return
        queueArea.removeAllViews(); val queue = messageQueues[currentThreadId]; val entries = queue?.arrayAt("entries")
        if (queue == null || entries == null || entries.length() == 0) { queueArea.visibility = View.GONE; return }
        queueArea.visibility = View.VISIBLE
        val card = Ui.column(this).apply { setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10)); background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 12).toFloat(), Ui.BORDER) }
        val header = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, "${if (queue.optBoolean("paused")) "排队已暂停" else "待发送"} · ${entries.length()} 条", 12f, Ui.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (queue.optBoolean("paused")) header.addView(Ui.button(this, "继续排队") { changeMessageQueue("resume") })
        card.addView(header, matchWidth())
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val row = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL }
            val id = entry.string("id")
            val input = entry.arrayAt("input")
            val item = JSONObject().put("content", input)
            val text = ProtocolFormatter.userText(item).ifBlank {
                MessageImages.fromItem(item).joinToString("\n") { "📎 ${it.name}" }
            }
            row.addView(Ui.text(this, "${index + 1}. $text", 12f).apply { maxLines = 3 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val dispatching = queue.optBoolean("dispatching") && index == 0
            val removable = id.isNotEmpty() && !dispatching && id !in pendingQueueRemovals
            row.addView(Ui.button(this, if (dispatching) "发送中…" else "取消并编辑") {
                val draft = QueuedComposerDraft.fromInput(input)
                if (draft == null) toast("这条待发送消息包含当前输入框无法编辑的内容")
                else changeMessageQueue("remove", id, draft)
            }.apply { isEnabled = removable && editingQueueDraft == null; alpha = if (isEnabled) 1f else .4f })
            row.addView(Ui.iconButton(this, "close", "删除第 ${index + 1} 条待发送消息") {
                changeMessageQueue("remove", id)
            }.apply { isEnabled = removable; alpha = if (isEnabled) 1f else .4f })
            card.addView(row, matchWidth(top = 6))
        }
        queue.string("error").takeIf { it.isNotEmpty() }?.let { card.addView(Ui.text(this, it, 11f, Ui.MUTED), matchWidth(top = 6)) }
        queueArea.addView(card, matchWidth(bottom = 10))
    }

    private fun changeMessageQueue(action: String, id: String = "", draft: QueuedComposerDraft? = null) {
        val threadId = currentThreadId
        if (!online || sending || loading || readOnly || threadId.isEmpty()) return
        val binding = composerBinding
        if (draft != null && (preparingAttachments || editingQueueDraft != null || binding.threadId != threadId)) return
        val requestClient = client ?: return
        if (id.isNotEmpty() && !pendingQueueRemovals.add(id)) return
        if (draft != null) editingQueueDraft = binding
        if (id.isNotEmpty()) { renderQueue(); updateSendState() }

        val params = JSONObject().put("threadId", threadId)
        if (id.isNotEmpty()) params.put("id", id)
        requestClient.request("codexrm/queue/$action", params) { result, error ->
            pendingQueueRemovals.remove(id)
            if (draft != null) editingQueueDraft = null
            if (result != null && draft != null) restoreQueuedDraft(binding, draft)
            if (requestClient === client && result != null) acceptMessageQueue(result)
            if (requestClient === client && result == null) {
                toast(error?.message)
                if (currentThreadId == threadId && id.isNotEmpty()) loadMessageQueue(threadId)
            }
            if (id.isNotEmpty()) { renderQueue(); updateSendState() }
        }
    }

    private fun restoreQueuedDraft(binding: ComposerBinding, draft: QueuedComposerDraft) {
        val visible = mainVisible && mainBuilt && composerBinding == binding
        val existingText = if (visible) composer.text.toString() else store.draft(binding.agentId, binding.threadId)
        val text = draft.appendTo(existingText)
        if (draft.files.isNotEmpty()) {
            val key = binding.attachmentKey(::pathKey)
            val restored = draft.files.map { it.put("draftId", UUID.randomUUID().toString()) }
            attachmentStore.save(key, attachmentStore.load(key) + restored)
        }
        store.saveDraft(binding.agentId, binding.threadId, text)
        if (!visible) return

        composerEditVersion++
        setComposerText(text)
        composer.setSelection(composer.length())
        renderAttachments()
        if (composer.requestFocus()) getSystemService(InputMethodManager::class.java)?.showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun renderApproval() {
        if (!mainBuilt) return
        approvalArea.removeAllViews()
        val visible = requests.keys.mapNotNull { key ->
            if (ApprovalFilter.visible(requestScopes[key], currentAgent(), currentThreadId, activeTurnId)) requests[key] else null
        }
        approvalScroll.visibility = if (visible.isEmpty() && approvalRecoveryMessage.isEmpty()) View.GONE else View.VISIBLE
        if (approvalRecoveryMessage.isNotEmpty()) {
            approvalArea.addView(Ui.text(this, approvalRecoveryMessage, 11f, Ui.MUTED).apply {
                setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6))
            }, matchWidth())
        }
        val permissions = visible.filter(::isPermission)
        val permissionScopes = permissions.map { requestScopes[requestKey(it.opt("id"))] ?: ApprovalScope("", "", "") }
        if (ApprovalFilter.canBatch(permissionScopes)) {
            val batch = Ui.column(this)
            batch.addView(Ui.button(this, "全部允许 (${permissions.size})", primary = true) { permissions.forEach { answerPermission(it, "accept") } })
            batch.addView(Ui.button(this, "全部拒绝") { permissions.forEach { answerPermission(it, "decline") } })
            approvalArea.addView(batch, matchWidth())
        }
        visible.forEach { message ->
            val key = requestKey(message.opt("id")); val busy = responding.contains(key); val permission = isPermission(message)
            val card = Ui.column(this).apply {
                setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14))
                background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 12).toFloat(), 0x55bd9c55); alpha = if (busy) .65f else 1f
            }
            card.addView(Ui.text(this, ProtocolFormatter.requestTitle(message), 13f, bold = true))
            card.addView(Ui.text(this, ProtocolFormatter.requestDetails(message), 12f, Ui.MUTED).apply { maxLines = 7; ellipsize = android.text.TextUtils.TruncateAt.END }, matchWidth(top = 7))
            val actions = Ui.column(this)
            if (permission) {
                actions.addView(Ui.button(this, "允许一次", primary = true) { answerPermission(message, "accept") })
                actions.addView(Ui.button(this, "本次会话允许") { answerPermission(message, "acceptForSession") })
                actions.addView(Ui.button(this, "拒绝") { answerPermission(message, "decline") })
            } else if (message.objectAt("params").has("questions")) {
                actions.addView(Ui.button(this, "提交回答", primary = true) { if (!busy) showUserInput(message) })
                actions.addView(Ui.button(this, "取消") { respondError(message) })
            } else {
                actions.addView(Ui.button(this, "返回结果", primary = true) { if (!busy) showRawResponse(message) })
                actions.addView(Ui.button(this, "取消") { respondError(message) })
            }
            for (index in 0 until actions.childCount) actions.getChildAt(index).isEnabled = !busy && online && loaded
            card.addView(actions, matchWidth(top = 10))
            approvalArea.addView(card, matchWidth(bottom = 12))
        }
    }

    private fun answerPermission(message: JSONObject, decision: String) {
        val key = requestKey(message.opt("id")); if (responding.contains(key)) return
        if (!ApprovalFilter.visible(requestScopes[key], currentAgent(), currentThreadId, activeTurnId)) {
            toast("该审批不属于当前会话或轮次，已阻止操作"); renderApproval(); return
        }
        val id = message.opt("id") ?: return
        if (client?.respond(id, ProtocolFormatter.permissionResult(message, decision)) == true) { responding.add(key); renderApproval() }
    }

    private fun showUserInput(message: JSONObject) {
        val questions = message.objectAt("params").arrayAt("questions"); val scroll = ScrollView(this)
        val column = Ui.column(this).apply { setPadding(Ui.dp(context, 22), Ui.dp(context, 8), Ui.dp(context, 22), 0) }
        data class AnswerField(val id: String, val value: () -> String)
        val fields = ArrayList<AnswerField>()
        for (index in 0 until questions.length()) {
            val question = questions.optJSONObject(index) ?: continue
            column.addView(Ui.text(this, question.string("question"), 14f, bold = true), matchWidth(top = 10))
            val options = question.arrayAt("options")
            if (options.length() > 0) {
                val labels = (0 until options.length()).map { options.optJSONObject(it)?.string("label").orEmpty() }
                val spinner = Spinner(this); setSpinnerItems(spinner, labels); column.addView(spinner, matchWidth())
                val other = Ui.boxInput(this, "也可以输入自己的回答"); column.addView(other, matchWidth())
                fields += AnswerField(question.string("id")) { other.text.toString().ifEmpty { spinner.selectedItem?.toString().orEmpty() } }
            } else {
                val input = Ui.boxInput(this, "请输入回答")
                if (question.optBoolean("isSecret")) input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                column.addView(input, matchWidth()); fields += AnswerField(question.string("id")) { input.text.toString() }
            }
        }
        scroll.addView(column)
        styledDialog(AlertDialog.Builder(this).setTitle("回答 Codex").setView(scroll).setPositiveButton("提交") { _, _ ->
            val answers = JSONObject(); fields.forEach { answers.put(it.id, JSONObject().put("answers", JSONArray().put(it.value()))) }
            respondResult(message, JSONObject().put("answers", answers))
        }.setNegativeButton("取消", null))
    }

    private fun showRawResponse(message: JSONObject) {
        val input = Ui.boxInput(this, "JSON 结果，例如 {}").apply { setText("{}") }
        val container = FrameLayout(this).apply { setPadding(Ui.dp(context, 24), 0, Ui.dp(context, 24), 0) }; container.addView(input)
        styledDialog(AlertDialog.Builder(this).setTitle("返回 JSON 结果").setView(container).setPositiveButton("返回结果") { _, _ ->
            try { respondResult(message, JSONTokener(input.text.toString()).nextValue()) } catch (error: Exception) { toast("JSON 无效：${error.message}") }
        }.setNegativeButton("取消", null))
    }

    private fun respondResult(message: JSONObject, result: Any) {
        val id = message.opt("id") ?: return; val key = requestKey(id)
        if (!ApprovalFilter.visible(requestScopes[key], currentAgent(), currentThreadId, activeTurnId)) return
        if (client?.respond(id, result) == true) { responding.add(key); renderApproval() }
    }
    private fun respondError(message: JSONObject) {
        val id = message.opt("id") ?: return; val key = requestKey(id)
        if (!ApprovalFilter.visible(requestScopes[key], currentAgent(), currentThreadId, activeTurnId)) return
        if (client?.respondError(id, "User cancelled remote interaction") == true) { responding.add(key); renderApproval() }
    }
    private fun isPermission(message: JSONObject): Boolean = message.string("method") in setOf("item/commandExecution/requestApproval", "item/fileChange/requestApproval", "item/permissions/requestApproval")

    private fun toggleDevices() {
        devicesExpanded = !devicesExpanded
        updateBackHandling()
        deviceDropdown.animate().cancel()
        renderDevices()
        if (devicesExpanded) {
            deviceDropdown.alpha = 0f
            deviceDropdown.translationY = -Ui.dp(this, 4).toFloat()
            deviceDropdown.animate().alpha(1f).translationY(0f).setDuration(140).setInterpolator(AccelerateDecelerateInterpolator()).start()
        }
    }

    private fun renderDevices() {
        if (!mainBuilt) return
        val currentName = device?.serverName?.takeIf { it.isNotBlank() } ?: "未命名设备"
        sidebarDeviceName.text = currentName
        sidebarDeviceStatus.text = when {
            online -> "已连接 · 点击切换设备"
            devicesExpanded -> "选择要连接的已配对设备"
            else -> "等待连接 · 点击切换设备"
        }
        sidebarDeviceStatus.setTextColor(if (online) Ui.GREEN else Ui.MUTED)
        sidebarDeviceButton.contentDescription = (if (devicesExpanded) "收起设备列表，当前设备：" else "展开设备列表，当前设备：") + currentName
        sidebarDeviceButton.isSelected = devicesExpanded
        sidebarDeviceChevron.rotation = if (devicesExpanded) 180f else 0f
        deviceDropdown.visibility = if (devicesExpanded) View.VISIBLE else View.GONE
        deviceListScroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (devicesExpanded) min(Ui.dp(this, 56) * devices.size, Ui.dp(this, 224)) else 0,
        )
        deviceRows.removeAllViews()
        devices.forEach { item ->
            val selected = item.agentId == device?.agentId
            val row = deviceRow(item, selected)
            row.setOnClickListener { selectDevice(item, closeSidebarAfter = true) }
            deviceRows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))
        }
    }

    private fun selectDevice(item: Device, closeSidebarAfter: Boolean) {
        devicePopup?.dismiss()
        devicesExpanded = false
        if (closeSidebarAfter) closeSidebar() else updateBackHandling()
        if (item.agentId == device?.agentId) {
            renderDevices()
            return
        }
        saveDraft()
        device?.online = false
        store.selectedId = item.agentId
        device = item
        restoreThreadId = ""
        showMain(reset = true)
    }

    private fun deviceRow(item: Device, selected: Boolean): LinearLayout = Ui.column(this, false).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.ripple(if (selected) Ui.SURFACE_ALT else Color.TRANSPARENT, Ui.dp(context, 9).toFloat())
        setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
        isClickable = true; isFocusable = true; isSelected = selected
        setOnLongClickListener { confirmDeleteDevice(item); true }
        val name = item.serverName.ifBlank { "未命名设备" }
        val version = (if (selected) info.string("version").ifEmpty { item.appVersion } else item.appVersion)
            .takeIf { it.isNotEmpty() }?.let { "Agent v$it · " }.orEmpty()
        val status = when {
            selected && item.online -> "${version}当前设备 · 已连接"
            selected -> "${version}当前设备 · 等待连接"
            else -> "${version}已配对 · 点击切换"
        }
        contentDescription = if (selected) "$name，$status" else "切换到 $name"
        val icon = FrameLayout(context).apply {
            background = Ui.rounded(if (selected) Ui.SURFACE else Ui.SURFACE_ALT, Ui.dp(context, 7).toFloat())
            addView(IconView(context, "monitor", if (selected) Ui.TEXT else Ui.MUTED), FrameLayout.LayoutParams(Ui.dp(context, 17), Ui.dp(context, 17), Gravity.CENTER))
        }
        addView(icon, LinearLayout.LayoutParams(Ui.dp(context, 30), Ui.dp(context, 30)))
        val labels = Ui.column(context).apply { setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 6), 0) }
        labels.addView(Ui.text(context, name, 12f, bold = selected).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(Ui.text(context, status, 10f, if (selected && item.online) Ui.GREEN else Ui.MUTED))
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(IconView(context, if (selected) "check" else "right", if (selected) Ui.GREEN else Ui.DIM), LinearLayout.LayoutParams(Ui.dp(context, 17), Ui.dp(context, 17)))
    }

    private fun addDeviceRow(click: () -> Unit): LinearLayout = Ui.column(this, false).apply {
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 9).toFloat())
        setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
        isClickable = true; isFocusable = true; contentDescription = "添加新设备"
        val icon = FrameLayout(context).apply {
            background = Ui.rounded(Ui.SURFACE_ALT, Ui.dp(context, 7).toFloat())
            addView(IconView(context, "plus", Ui.TEXT), FrameLayout.LayoutParams(Ui.dp(context, 17), Ui.dp(context, 17), Gravity.CENTER))
        }
        addView(icon, LinearLayout.LayoutParams(Ui.dp(context, 30), Ui.dp(context, 30)))
        val labels = Ui.column(context).apply { setPadding(Ui.dp(context, 10), 0, 0, 0) }
        labels.addView(Ui.text(context, "添加新设备", 12f, bold = true))
        labels.addView(Ui.text(context, "扫码或输入配对码", 10f, Ui.MUTED))
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(IconView(context, "right", Ui.DIM), LinearLayout.LayoutParams(Ui.dp(context, 17), Ui.dp(context, 17)))
        setOnClickListener { click() }
    }

    private fun renderWorkspaces() {
        if (!mainBuilt) return
        workspaceRows.removeAllViews(); historyLabel.text = if (threadListLoading) "正在加载全部会话…" else "所有文件夹 · 最近更新优先"
        if (workspacesCollapsed) { workspaceRows.addView(Ui.text(this, "工作区已收起", 11f, Ui.DIM).apply { setPadding(Ui.dp(context, 10), 0, 0, 0) }); return }
        val query = if (::searchInput.isInitialized) searchInput.text.toString().trim().lowercase() else ""
        val groups = LinkedHashMap<String, WorkspaceGroup>()
        workspaces.forEach { groups[pathKey(it.path)] = WorkspaceGroup(it.name, it.path, true) }
        threads.forEach { thread ->
            val key = pathKey(thread.cwd); val group = groups.getOrPut(key) { WorkspaceGroup(folderName(thread.cwd), thread.cwd, false) }
            group.threads += thread
        }
        var shown = 0
        groups.values.sortedWith(compareByDescending<WorkspaceGroup> { group ->
            group.threads.maxOfOrNull { it.lastActivityAt } ?: 0L
        }.thenBy { it.path }).forEach { group ->
            group.threads.sortWith(
                compareByDescending<ThreadSummary> { it.lastActivityAt }.thenByDescending { it.createdAt }.thenByDescending { it.id }
            )
            val matching = group.threads.filter { query.isEmpty() || "${it.title} ${it.cwd}".lowercase().contains(query) }
            if (query.isNotEmpty() && matching.isEmpty()) return@forEach
            shown++
            val key = pathKey(group.path)
            val expanded = query.isNotEmpty() || key !in collapsedPaths
            val heading = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.ripple(if (expanded) Ui.SURFACE else Color.TRANSPARENT, Ui.dp(context, 7).toFloat()) }
            val toggle = Ui.column(this, false).apply {
                gravity = Gravity.CENTER_VERTICAL; setOnClickListener {
                    if (!collapsedPaths.add(key)) collapsedPaths.remove(key)
                    store.saveCollapsed(currentAgent(), collapsedPaths)
                    renderWorkspaces()
                }
                setOnLongClickListener { openWorkspaceFiles(group.path, group.threads.firstOrNull()?.id.orEmpty()) }
                addView(IconView(context, if (expanded) "chevron" else "right", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(context, 16), Ui.dp(context, 16)))
                addView(IconView(context, "folder", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(context, 17), Ui.dp(context, 17)))
                addView(Ui.text(context, "${group.name} (${group.threads.size})", 12f, if (expanded) Ui.TEXT else Ui.MUTED).apply { maxLines = 1; setPadding(Ui.dp(context, 6), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            heading.addView(toggle, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
            if (group.path.isNotEmpty()) {
                heading.addView(Ui.iconButton(this, "plus", "在此工作区新建会话", 36) { selectWorkspace(group.path); closeSidebar() })
                if (group.pinned) heading.addView(Ui.iconButton(this, "close", "取消固定工作区", 36) { removeWorkspace(group.path) })
            }
            workspaceRows.addView(heading, matchWidth())
            if (expanded) {
                val history = Ui.column(this).apply { setPadding(Ui.dp(context, 17), 0, 0, Ui.dp(context, 10)) }
                val folder = currentAgent() to key
                val canShowOlder = query.isEmpty() && matching.size > WORKSPACE_THREAD_FOLD_THRESHOLD
                val showOlder = canShowOlder && folder in expandedWorkspaceThreads
                val visibleThreads = if (canShowOlder && !showOlder) matching.take(WORKSPACE_RECENT_THREAD_LIMIT) else matching
                visibleThreads.forEachIndexed { index, thread ->
                    val row = Ui.column(this, false).apply {
                        gravity = Gravity.CENTER_VERTICAL; background = Ui.ripple(if (thread.id == currentThreadId) Ui.SURFACE else Color.TRANSPARENT, Ui.dp(context, 7).toFloat())
                        isFocusable = true
                        setOnClickListener { openThread(thread.id) }
                        setOnLongClickListener { confirmDeleteThread(thread.id, thread.title); true }
                    }
                    row.addView(Ui.text(this, thread.title, 12f, if (thread.id == currentThreadId) Ui.TEXT else Ui.MUTED).apply {
                        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(Ui.dp(context, 12), 0, 0, 0)
                    }, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
                    if (thread.active) row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
                        isIndeterminate = true
                        indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN)
                        contentDescription = "进行中"
                    }, LinearLayout.LayoutParams(Ui.dp(this, 14), Ui.dp(this, 14)).apply { rightMargin = Ui.dp(this@MainActivity, 10) })
                    history.addView(row, matchWidth())
                    if (canShowOlder && index + 1 == WORKSPACE_RECENT_THREAD_LIMIT) {
                        val olderCount = matching.size - WORKSPACE_RECENT_THREAD_LIMIT
                        val label = if (showOlder) "收起旧对话" else "展开其余 $olderCount 条对话"
                        val older = Ui.button(this, label) {
                            if (showOlder) expandedWorkspaceThreads.remove(folder) else expandedWorkspaceThreads.add(folder)
                            renderWorkspaces()
                        }.apply {
                            gravity = Gravity.CENTER_VERTICAL or Gravity.START
                            setTextColor(Ui.MUTED)
                            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 7).toFloat())
                        }
                        history.addView(older, matchWidth())
                    }
                }
                if (matching.isEmpty()) history.addView(Ui.text(this, "还没有会话", 11f, Ui.DIM).apply { setPadding(Ui.dp(context, 12), 0, 0, 0) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 36)))
                workspaceRows.addView(history, matchWidth())
            }
        }
        if (shown == 0) workspaceRows.addView(Ui.text(this, if (query.isNotEmpty()) "没有匹配的会话" else if (online) "还没有会话，从一个新任务开始" else "连接设备后，会话会出现在这里", 11f, Ui.DIM).apply { setPadding(Ui.dp(context, 10), 0, 0, 0) })
        if (threadCursor != null && !threadListLoading) workspaceRows.addView(Ui.text(this, "加载更多会话", 11f, Ui.MUTED).apply {
            gravity = Gravity.CENTER; setOnClickListener { loadThreads(generation, threadCursor, threads) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))
    }

    private data class WorkspaceGroup(val name: String, val path: String, val pinned: Boolean, val threads: MutableList<ThreadSummary> = mutableListOf())

    private fun removeWorkspace(path: String) {
        rpc("codexrm/workspace/remove", JSONObject().put("path", path)) { result, error ->
            if (result != null) { loadWorkspaces(generation); loadThreads(generation); toast("已取消固定；文件和会话仍保留") } else toast(error?.message)
        }
    }

    private fun openWorkspacePicker() {
        if (!online) { toast("先连接设备，再添加工作区"); return }
        if (!info.optBoolean("workspaces")) { toast("请更新本地连接器，以使用目录浏览"); return }
        val scroll = ScrollView(this); val column = Ui.column(this).apply { setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), Ui.dp(context, 10)) }
        val pathRow = Ui.column(this, false); val pathInput = Ui.boxInput(this, "输入设备上的绝对目录路径")
        pathRow.addView(pathInput, LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f))
        lateinit var browseDirectory: (String) -> Unit
        val go = Ui.button(this, "前往") { browseDirectory(pathInput.text.toString()) }; pathRow.addView(go)
        column.addView(pathRow, matchWidth())
        val shortcuts = Ui.column(this, false); column.addView(shortcuts, matchWidth(top = 10))
        val errorText = Ui.text(this, "", 12f, Ui.DANGER); column.addView(errorText, matchWidth())
        val list = Ui.column(this); column.addView(list, matchWidth())
        val hidden = CheckBox(this).apply { text = "显示隐藏目录"; setTextColor(Ui.MUTED) }
        val add = Ui.button(this, "添加此文件夹", primary = true) { }
        add.isEnabled = false
        val footer = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL; addView(hidden, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); addView(add) }
        column.addView(footer, matchWidth(top = 14))
        column.addView(Ui.text(this, "添加后可切换多个工作区。移出列表只删除记录，保留文件和会话。", 10f, Ui.DIM), matchWidth(top = 10))
        scroll.addView(column)
        val dialog = styledDialog(AlertDialog.Builder(this).setTitle("添加工作区").setView(scroll).setNegativeButton("取消", null))
        var version = 0; var selectedPath = ""
        fun render(result: JSONObject) {
            selectedPath = result.string("path"); pathInput.setText(selectedPath); pathInput.setSelection(pathInput.text.length)
            errorText.text = ""; add.isEnabled = selectedPath.isNotEmpty(); shortcuts.removeAllViews(); list.removeAllViews()
            val links = ArrayList<Workspace>()
            result.string("parent").takeIf { it.isNotEmpty() }?.let { links += Workspace("上一级", it) }
            result.string("home").takeIf { it.isNotEmpty() }?.let { links += Workspace("主目录", it) }
            val roots = result.arrayAt("roots"); for (index in 0 until roots.length()) roots.optJSONObject(index)?.let { links += Workspace(it.string("name"), it.string("path")) }
            links.forEach { link -> shortcuts.addView(Ui.text(this, link.name, 11f, Ui.MUTED).apply {
                background = Ui.ripple(Ui.SURFACE, Ui.dp(context, 6).toFloat()); gravity = Gravity.CENTER
                setOnClickListener { browseDirectory(link.path) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 38)).apply { rightMargin = Ui.dp(this@MainActivity, 6) }) }
            val directories = result.arrayAt("directories")
            for (index in 0 until directories.length()) {
                val directory = directories.optJSONObject(index) ?: continue
                val row = Ui.column(this, false).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(context, 8), 0, 0, 0)
                    addView(IconView(context, "folder", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18)))
                    addView(Ui.text(context, directory.string("name"), 13f).apply { setPadding(Ui.dp(context, 12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(IconView(context, "right", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(context, 16), Ui.dp(context, 16)))
                    setOnClickListener { browseDirectory(directory.string("path")) }
                }
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)))
            }
            if (directories.length() == 0) list.addView(Ui.text(this, "此目录没有可显示的子文件夹，可以直接添加。", 12f, Ui.DIM), matchWidth())
            if (result.optBoolean("truncated")) errorText.text = "仅显示前 500 个子目录，也可以输入完整路径。"
        }
        browseDirectory = fun(path: String) {
            val requestVersion = ++version; add.isEnabled = false; errorText.text = "正在读取…"
            rpc("codexrm/fs/list", JSONObject().put("path", path).put("showHidden", hidden.isChecked)) { result, error ->
                if (requestVersion != version || !dialog.isShowing) return@rpc
                if (result != null) render(result) else { errorText.text = error?.message; add.isEnabled = false }
            }
        }
        go.setOnClickListener { browseDirectory(pathInput.text.toString()) }
        hidden.setOnCheckedChangeListener { _, _ -> if (selectedPath.isNotEmpty()) browseDirectory(selectedPath) }
        add.setOnClickListener {
            rpc("codexrm/workspace/add", JSONObject().put("path", selectedPath)) { result, error ->
                if (result != null) { currentCwd = selectedPath; store.selectWorkspace(currentAgent(), selectedPath); loadWorkspaces(generation); loadThreads(generation); dialog.dismiss(); toast("工作区已添加") }
                else errorText.text = error?.message
            }
        }
        browseDirectory(currentCwd.ifEmpty { info.string("cwd") })
    }

    private fun showDevices() {
        if (devicePopup?.isShowing == true) {
            devicePopup?.dismiss()
            return
        }
        val content = Ui.column(this).apply {
            setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
            background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 16).toFloat(), Ui.BORDER)
            accessibilityPaneTitle = "选择设备"
        }
        val heading = Ui.column(this).apply { setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 8)) }
        heading.addView(Ui.text(this, "选择设备", 16f, bold = true))
        heading.addView(Ui.text(this, "任务将在所选电脑上运行", 10f, Ui.MUTED), matchWidth(top = 2))
        content.addView(heading, matchWidth())
        val rows = Ui.column(this)
        devices.forEach { item ->
            val row = deviceRow(item, item.agentId == device?.agentId)
            row.setOnClickListener { selectDevice(item, closeSidebarAfter = false) }
            rows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true; isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(rows)
        }
        content.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, min(Ui.dp(this, 56) * devices.size, Ui.dp(this, 280))))
        content.addView(View(this).apply { setBackgroundColor(Ui.BORDER) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)).apply {
            topMargin = Ui.dp(this@MainActivity, 6); bottomMargin = Ui.dp(this@MainActivity, 6)
        })
        lateinit var popup: PopupWindow
        content.addView(addDeviceRow { popup.dismiss(); showPair() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 54)))
        val width = min(Ui.dp(this, 320), resources.displayMetrics.widthPixels - Ui.dp(this, 24))
        popup = PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            isClippingEnabled = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(Ui.rounded(Color.TRANSPARENT, 0f))
            elevation = Ui.dp(this@MainActivity, 18).toFloat()
        }
        popup.setOnDismissListener { if (devicePopup === popup) devicePopup = null }
        devicePopup = popup
        popup.showAsDropDown(deviceButton, deviceButton.width - width, Ui.dp(this, 6))
    }

    private fun showAppMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "关于")
            menu.add(0, 2, 1, "检查更新")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showAbout()
                    2 -> checkForUpdates()
                    else -> return@setOnMenuItemClickListener false
                }
                true
            }
            show()
        }
    }

    private fun showAbout() {
        val content = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 8), Ui.dp(context, 24), Ui.dp(context, 16))
        }
        val version = UpdateChecker.currentVersionName(this)
        val code = UpdateChecker.currentVersionCode(this)
        content.addView(Ui.text(this, "已安装 v$version ($code)", 12f, Ui.MUTED))
        content.addView(Ui.text(this, "版本更新日志", 14f, bold = true), matchWidth(top = 16))
        val history = Ui.column(this)
        content.addView(history, matchWidth(top = 8))
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = styledDialog(AlertDialog.Builder(this).setTitle("关于 CodexRM").setView(scroll).setPositiveButton("关闭", null))
        var historyCall: Call? = null

        fun loadHistory() {
            history.removeAllViews()
            history.addView(Ui.text(this, "正在读取更新日志…", 12f, Ui.MUTED))
            historyCall = UpdateChecker.fetchHistory { releases, error ->
                if (!dialog.isShowing || isFinishing || isDestroyed) return@fetchHistory
                history.removeAllViews()
                if (error != null || releases == null) {
                    history.addView(Ui.text(this, "无法读取更新日志", 12f, Ui.MUTED))
                    history.addView(Ui.button(this, "重试") { loadHistory() }, matchWidth(top = 8))
                } else if (releases.isEmpty()) {
                    history.addView(Ui.text(this, "暂无 Android 更新记录", 12f, Ui.MUTED))
                } else {
                    for (release in releases) {
                        val title = listOf("v${release.versionName}", release.date).filter(String::isNotEmpty).joinToString(" · ")
                        history.addView(Ui.text(this, title, 13f, bold = true), matchWidth(top = 12))
                        history.addView(Ui.text(this, release.notes.ifEmpty { "未提供改动说明" }, 12f, Ui.MUTED), matchWidth(top = 4))
                    }
                }
            }
        }

        dialog.setOnDismissListener { historyCall?.cancel() }
        loadHistory()
    }

    private fun showSettings() {
        closeSidebar(); val column = Ui.column(this).apply { setPadding(Ui.dp(context, 22), 0, Ui.dp(context, 22), Ui.dp(context, 12)) }
        column.addView(Ui.text(this, "在左侧“工作区”添加或切换文件夹。同一台设备可以管理多个目录。", 11f, Ui.DIM))
        column.addView(Ui.text(this, "文件访问", 11f, Ui.MUTED), matchWidth(top = 16))
        val sandbox = Spinner(this); setSpinnerItems(sandbox, listOf("允许修改工作目录", "只读", "完整访问"))
        sandbox.setSelection(arrayOf("workspace-write", "read-only", "danger-full-access").indexOf(store.sandbox)); column.addView(sandbox, matchWidth())
        column.addView(Ui.text(this, "审批策略", 11f, Ui.MUTED), matchWidth(top = 16))
        val approval = Spinner(this); setSpinnerItems(approval, listOf("需要时请求审批", "不受信任命令需审批", "从不请求审批"))
        approval.setSelection(arrayOf("on-request", "untrusted", "never").indexOf(store.approvalPolicy)); column.addView(approval, matchWidth())
        val current = device
        val agentVersion = info.string("version").ifEmpty { current?.appVersion.orEmpty() }.ifEmpty { "未知" }
        column.addView(Ui.text(this, "${current?.serverName}\n${current?.os} · ${current?.agentId}\n电脑 Agent v$agentVersion\n${current?.serverUrl}", 11f, Ui.MUTED), matchWidth(top = 18))
        val dialog = styledDialog(AlertDialog.Builder(this).setTitle("工作区设置").setView(column).setPositiveButton("保存设置") { _, _ ->
            store.sandbox = arrayOf("workspace-write", "read-only", "danger-full-access")[sandbox.selectedItemPosition]
            store.approvalPolicy = arrayOf("on-request", "untrusted", "never")[approval.selectedItemPosition]
            toast("设置已保存，将在新会话及下一次发送时生效")
        }.setNegativeButton("取消", null))
        if (online && info.optBoolean("remoteUpdate")) column.addView(Ui.button(this, "检查并更新电脑 Agent") {
            dialog.dismiss()
            confirmAgentUpdate()
        }, matchWidth(top = 10))
        if (info.optBoolean("desktopControl")) column.addView(Ui.button(this, "关闭 Codex 桌面客户端") {
            rpc("codexrm/desktop/close") { result, error -> toast(result?.string("message") ?: error?.message) }
        }, matchWidth(top = 10))
        if (current != null) column.addView(Ui.text(this, "解除当前设备配对", 12f, Ui.DANGER).apply {
            gravity = Gravity.CENTER; background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 7).toFloat(), Ui.BORDER)
            setOnClickListener { dialog.dismiss(); confirmDeleteDevice(current) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46)).apply { topMargin = Ui.dp(this@MainActivity, 12) })
    }

    private fun confirmAgentUpdate() {
        if (!online || !info.optBoolean("remoteUpdate")) { toast("电脑 Agent 离线或不支持远程更新"); return }
        styledDialog(AlertDialog.Builder(this)
            .setTitle("更新电脑 Agent")
            .setMessage("更新包将在电脑端校验，进行中的任务和排队消息结束后自动安装，随后电脑 Agent 会重启。")
            .setPositiveButton("检查并更新") { _, _ -> requestAgentUpdate() }
            .setNegativeButton("取消", null))
    }

    private fun requestAgentUpdate() {
        val current = device ?: return
        val connection = client ?: return
        if (!online || !info.optBoolean("remoteUpdate")) { toast("电脑 Agent 离线或不支持远程更新"); return }
        val requestGeneration = generation
        // Save before sending since a restart can interrupt the update response.
        val previousPending = current.pendingAgentUpdateFrom
        val versionBeforeUpdate = previousPending.ifBlank { current.appVersion.ifBlank { info.string("version") } }
        if (versionBeforeUpdate != previousPending) {
            current.pendingAgentUpdateFrom = versionBeforeUpdate
            store.save(devices)
        }
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        val column = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 4), Ui.dp(context, 24), Ui.dp(context, 8))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(spinner)
            addView(Ui.text(context, "正在检查并校验电脑 Agent 更新…", 13f, Ui.MUTED), matchWidth(top = 12))
        }
        val dialog = styledDialog(AlertDialog.Builder(this).setTitle("更新电脑 Agent").setView(column))
        dialog.setCancelable(false)
        connection.request("codexrm/update", timeoutMs = 600_000) { result, error ->
            dialog.dismiss()
            if (device !== current || current.pendingAgentUpdateFrom != versionBeforeUpdate) return@request
            val pending = when {
                error is RpcOutcomeUnknown -> versionBeforeUpdate
                error != null || result == null -> previousPending
                !result.optBoolean("updateAvailable") -> ""
                else -> versionBeforeUpdate
            }
            if (pending != current.pendingAgentUpdateFrom) {
                current.pendingAgentUpdateFrom = pending
                store.save(devices)
            }
            if (generation != requestGeneration || !online) return@request
            when {
                error is RpcOutcomeUnknown -> toast("更新结果待确认，重连后将检查版本")
                error != null || result == null -> styledDialog(AlertDialog.Builder(this)
                    .setTitle("电脑 Agent 更新")
                    .setMessage(error?.message ?: "更新请求失败")
                    .setPositiveButton("知道了", null))
                !result.optBoolean("updateAvailable") -> toast("电脑 Agent 已是最新版本")
                result.optBoolean("updatePending") -> toast("更新已校验，将在当前任务和排队消息结束后自动安装")
                else -> toast("更新已校验，电脑 Agent 将重启完成安装")
            }
        }
    }

    private fun checkForUpdatesOnStartup() {
        val operation = updateOperation
        updateCall = UpdateChecker.fetchLatest { latest, error ->
            if (operation != updateOperation || isFinishing || isDestroyed) return@fetchLatest
            updateCall = null
            if (error != null || latest == null || latest.versionCode <= UpdateChecker.currentVersionCode(this)) {
                return@fetchLatest
            }
            startupUpdate = latest
            showStartupUpdateIfReady()
        }
    }

    private fun showStartupUpdateIfReady() {
        val latest = startupUpdate ?: return
        // Wait for the main window to regain focus if another dialog or activity is open.
        if (!resumed || !hasWindowFocus() || isFinishing || isDestroyed) return
        startupUpdate = null
        showUpdateAvailable(latest)
    }

    private fun checkForUpdates() {
        cancelUpdateOperation()
        val operation = updateOperation
        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        val column = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 4), Ui.dp(context, 24), Ui.dp(context, 8))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(spinner)
            addView(Ui.text(context, "正在检查新版本…", 13f, Ui.MUTED), matchWidth(top = 12))
        }
        val dialog = styledDialog(
            AlertDialog.Builder(this)
                .setTitle("检查更新")
                .setView(column)
                .setNegativeButton("取消") { _, _ -> cancelUpdateOperation() },
        )
        updateDialog = dialog
        dialog.setOnCancelListener { if (operation == updateOperation) cancelUpdateOperation() }
        updateCall = UpdateChecker.fetchLatest { latest, error ->
            if (operation != updateOperation || isDestroyed) return@fetchLatest
            updateCall = null
            updateDialog = null
            dialog.dismiss()
            when {
                error != null -> showUpdateFailure(error.message ?: "网络或发布信息不可用") { checkForUpdates() }
                latest == null -> showUpdateFailure("更新服务器未返回有效版本") { checkForUpdates() }
                latest.versionCode <= UpdateChecker.currentVersionCode(this) -> styledDialog(
                    AlertDialog.Builder(this)
                        .setTitle("已是最新版本")
                        .setMessage("当前版本 ${UpdateChecker.currentVersionName(this)} (${UpdateChecker.currentVersionCode(this)})")
                        .setPositiveButton("知道了", null),
                )
                else -> showUpdateAvailable(latest)
            }
        }
    }

    private fun cancelUpdateOperation() {
        updateOperation++
        startupUpdate = null
        updateCall?.cancel()
        updateCall = null
        updateFailureReceiver?.let { unregisterReceiver(it) }
        updateFailureReceiver = null
        updateDialog?.dismiss()
        updateDialog = null
    }

    private fun showUpdateFailure(message: String, retry: () -> Unit) {
        styledDialog(
            AlertDialog.Builder(this)
                .setTitle("检查更新")
                .setMessage(message)
                .setPositiveButton("重试") { _, _ -> retry() }
                .setNegativeButton("关闭", null),
        )
    }

    private fun showUpdateAvailable(info: UpdateChecker.VersionInfo) {
        val notes = info.releaseNotes.ifBlank { "本次发布未提供更新说明。" }
        val size = String.format(Locale.US, "%.1f MiB", info.fileSize / 1048576.0)
        styledDialog(
            AlertDialog.Builder(this)
                .setTitle("发现新版本 ${info.versionName}")
                .setMessage("当前版本 ${UpdateChecker.currentVersionName(this)}\n安装包 $size\n\n$notes\n\n下载校验完成后自动安装。")
                .setPositiveButton("立即更新") { _, _ ->
                    if (UpdateChecker.canInstall(this)) {
                        startUpdateDownload(info)
                    } else {
                        pendingUpdate = info
                        styledDialog(
                            AlertDialog.Builder(this)
                                .setTitle("允许安装应用更新")
                                .setMessage("首次更新需要在系统设置中允许 CodexRM 安装未知应用。授权后返回即可继续下载。")
                                .setPositiveButton("去授权") { _, _ ->
                                    runCatching { UpdateChecker.requestInstallPermission(this) }
                                        .onFailure {
                                            pendingUpdate = null
                                            showUpdateFailure("无法打开安装权限设置") { showUpdateAvailable(info) }
                                        }
                                }
                                .setNegativeButton("取消") { _, _ -> pendingUpdate = null },
                        )
                    }
                }
                .setNegativeButton("稍后", null),
        )
    }

    private fun startUpdateDownload(info: UpdateChecker.VersionInfo) {
        if (!UpdateChecker.canInstall(this)) {
            showUpdateAvailable(info)
            return
        }
        cancelUpdateOperation()
        val operation = updateOperation
        val status = Ui.text(this, "正在下载更新…0%", 13f, Ui.MUTED)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        val column = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 8), Ui.dp(context, 24), Ui.dp(context, 8))
            addView(status, matchWidth())
            addView(progress, matchWidth(top = 16))
        }
        val dialog = styledDialog(
            AlertDialog.Builder(this)
                .setTitle("下载 CodexRM ${info.versionName}")
                .setView(column)
                .setNegativeButton("取消下载") { _, _ -> cancelUpdateOperation() },
        )
        updateDialog = dialog
        dialog.setOnCancelListener { if (operation == updateOperation) cancelUpdateOperation() }
        updateCall = UpdateChecker.download(this, info, { value ->
            if (operation != updateOperation || isDestroyed) return@download
            progress.progress = value
            status.text = if (value == 100) "正在校验安装包…" else "正在下载更新…$value%"
        }) { apk, error ->
            if (operation != updateOperation || isDestroyed) return@download
            updateCall = null
            if (error != null || apk == null) {
                updateDialog = null
                dialog.dismiss()
                showUpdateFailure(error?.message ?: "安装包下载失败") { startUpdateDownload(info) }
                return@download
            }

            dialog.setTitle("安装更新")
            dialog.setCancelable(false)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).visibility = View.GONE
            status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            status.text = "即将关闭软件，正在安装更新"
            progress.isIndeterminate = true

            val appContext = applicationContext
            handler.postDelayed({
                if (operation != updateOperation || isFinishing || isDestroyed) return@postDelayed
                val requestId = UUID.randomUUID().toString()
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        if (operation == updateOperation &&
                            intent.getStringExtra(UpdateInstallReceiver.EXTRA_REQUEST_ID) == requestId
                        ) {
                            cancelUpdateOperation()
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    this, receiver, IntentFilter(UpdateInstallReceiver.ACTION_INSTALL_FAILED),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                updateFailureReceiver = receiver
                thread(name = "codexrm-update-install") {
                    // Commit is asynchronous. Keep the dialog visible until Android replaces this process.
                    runCatching { UpdateChecker.install(appContext, apk, info.versionCode, requestId) }
                        .onFailure { error ->
                            handler.post {
                                if (operation != updateOperation || isFinishing || isDestroyed) return@post
                                cancelUpdateOperation()
                                showUpdateFailure("无法提交安装：${error.message ?: "系统安装器不可用"}") { startUpdateDownload(info) }
                            }
                        }
                }
            }, 2000L)
        }
    }

    private fun renameCurrentThread() {
        val thread = currentThread ?: return
        val targetId = currentThreadId
        val targetAgent = currentAgent()
        if (targetId.isEmpty() || loading) return

        val input = Ui.boxInput(this, "会话标题").apply {
            isSingleLine = true
            setText(thread.string("name"))
            selectAll()
        }
        val content = Ui.column(this).apply {
            setPadding(Ui.dp(context, 22), 0, Ui.dp(context, 22), Ui.dp(context, 12))
            addView(input, matchWidth())
        }
        val dialog = styledDialog(AlertDialog.Builder(this)
            .setTitle("修改会话标题")
            .setView(content)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null))
        val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        save.setOnClickListener {
            val name = input.text.toString().trim()
            if (name.isEmpty()) {
                input.error = "请输入会话标题"
                return@setOnClickListener
            }
            if (targetAgent != currentAgent()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            save.isEnabled = false
            val renameGeneration = generation
            rpc("thread/name/set", JSONObject().put("threadId", targetId).put("name", name)) { _, error ->
                if (error != null) {
                    save.isEnabled = true
                    toast(error.message)
                } else {
                    dialog.dismiss()
                    if (renameGeneration == generation && currentAgent() == targetAgent) {
                        if (currentThreadId == targetId) {
                            currentThread?.put("name", name)
                            updateControls()
                        }
                        loadThreads(renameGeneration)
                    }
                }
            }
        }
    }

    private fun confirmDeleteDevice(target: Device) {
        devicePopup?.dismiss()
        styledDialog(AlertDialog.Builder(this).setTitle("删除电脑？")
            .setMessage("将从此手机移除“${target.serverName}”，并尝试撤销配对。网络异常或撤销失败也会继续删除。再次连接需要重新配对。")
            .setPositiveButton("删除设备") { _, _ -> deleteDevice(target) }
            .setNegativeButton("取消", null))
    }

    private fun deleteDevice(target: Device) {
        // Ignore an outdated dialog if this computer has since been paired again.
        if (devices.none { it.agentId == target.agentId && it.token == target.token }) return
        val selected = device?.agentId == target.agentId
        if (selected) { saveDraft(); disconnect() }
        devices.removeAll { it.agentId == target.agentId }
        store.save(devices)
        if (selected) {
            device = devices.firstOrNull(); store.selectedId = device?.agentId.orEmpty(); restoreThreadId = ""
            if (device == null) { resetConversation(); showPair() } else showMain(reset = true)
        } else renderDevices()
        toast("设备已删除")
        // Local deletion is final; the relay request must never block or undo it.
        runCatching { Network.request(target.serverUrl, "/api/v1/client/revoke", target.token, JSONObject()) { _, _ -> } }
    }

    private fun threadDeletionReason(id: String): String? = when {
        !online || !loaded -> "设备连接后可以删除对话"
        sending || loading -> "请等待当前请求完成"
        id in deletingThreads -> "正在删除此对话"
        threads.none { it.id == id } -> "对话已不在列表中"
        threads.any { it.id == id && it.active } || (currentThreadId == id && running) -> "请先停止正在运行的对话"
        else -> null
    }

    private fun confirmDeleteThread(id: String, title: String) {
        threadDeletionReason(id)?.let { toast(it); return }
        val currentGeneration = generation
        styledDialog(AlertDialog.Builder(this).setTitle("删除对话？")
            .setMessage("将“$title”从对话列表移除，历史保留在电脑的本地归档中。")
            .setPositiveButton("删除对话") { _, _ ->
                if (currentGeneration != generation) return@setPositiveButton
                threadDeletionReason(id)?.let { toast(it); return@setPositiveButton }
                deletingThreads.add(id); updateSendState()
                rpc("thread/archive", JSONObject().put("threadId", id)) { _, error ->
                    if (currentGeneration != generation) return@rpc
                    deletingThreads.remove(id); updateSendState()
                    if (error != null) toast("删除对话失败：${error.message}")
                    else { removeArchivedThread(id); loadThreads(); toast("对话已删除") }
                }
            }.setNegativeButton("取消", null))
    }

    private fun removeArchivedThread(id: String) {
        if (id.isEmpty()) return
        // Invalidate pages requested before the archive event or response.
        threadListVersion++; threadListLoading = false
        threads = threads.filterNot { it.id == id }; messageQueues.remove(id)
        val resolved = requests.filterValues { it.objectAt("params").string("threadId") == id }.keys.toSet()
        resolved.forEach { requests.remove(it); requestScopes.remove(it); responding.remove(it) }
        if (currentThreadId == id) newChat()
        renderWorkspaces(); renderApproval(); renderQueue()
    }

    private fun toggleTheme() {
        val wasSidebarOpen = sidebarOpen
        invalidateBackPreview()
        saveDraft(); val savedMessages = if (mainBuilt) adapter.snapshot() else emptyList()
        store.darkTheme = !store.darkTheme; Ui.configure(this, store.darkTheme)
        mainBuilt = false; buildMain(); adapter.replace(savedMessages); mainVisible = true; setContentView(mainRoot)
        restoreDraft(); renderDevices(); renderWorkspaces(); renderApproval(); renderQueue(); refreshWorkspaceSpinner(); refreshModelSpinner(); updateControls()
        if (wasSidebarOpen) {
            sidebarOpen = true
            drawSidebar(0f)
        }
        updateBackHandling()
    }

    private fun updateConnectionIndicator() {
        if (!mainBuilt) return
        val status = client?.directStatus
        connectionButton.visibility = when {
            currentThreadId.isNotEmpty() -> View.GONE
            online && status != null -> View.VISIBLE
            else -> View.INVISIBLE
        }
        if (!online || status == null) {
            connectionDialog?.dismiss()
            return
        }
        val direct = status is DirectStatus.Connected
        connectionIcon.setIcon(if (direct) "zap" else "relay")
        connectionIcon.setColor(if (direct) Ui.GREEN else Ui.MUTED)
        val description = when (status) {
            is DirectStatus.Connected -> if (status.lan) "局域网直连，测试延迟" else "P2P 直连，测试延迟"
            else -> "RM 中继，查看直连状态"
        }
        connectionButton.contentDescription = description
        connectionButton.tooltipText = description
        connectionDialog?.let { updateConnectionDialog(it, status) }
    }

    private fun updateConnectionDialog(dialog: AlertDialog, status: DirectStatus) {
        if (status !is DirectStatus.Connected) {
            dialogShowsDirect = false
            dialog.setTitle("RM 中继")
            dialog.setMessage((status as? DirectStatus.Relay)?.reason ?: "正在尝试 P2P 直连，当前通过 RM 中继。")
            return
        }
        if (dialogShowsDirect) return
        val connectedClient = client ?: return
        dialogShowsDirect = true
        dialog.setTitle(if (status.lan) "局域网直连" else "P2P 直连")
        dialog.setMessage("正在测试延迟…")
        connectedClient.measureDirectLatency { latency, error ->
            if (client === connectedClient && connectionDialog === dialog && connectedClient.usingDirect) {
                dialog.setMessage(if (latency != null) "往返延迟：$latency ms" else error?.message ?: "延迟测试失败。")
            }
        }
    }

    private fun showConnectionDetails() {
        val status = client?.directStatus ?: return
        if (!online) return
        connectionDialog?.dismiss()
        val dialog = AlertDialog.Builder(this)
            .setPositiveButton("关闭", null)
            .create()
        connectionDialog = dialog
        dialogShowsDirect = false
        dialog.setOnDismissListener { if (connectionDialog === dialog) connectionDialog = null }
        updateConnectionDialog(dialog, status)
        dialog.show()
    }

    private fun updateControls() {
        if (!mainBuilt) return
        renderBootstrapState(); renderOutbox(); updateModelButton()
        updateConnectionIndicator()
        val currentDeviceName = device?.serverName?.takeIf { it.isNotBlank() } ?: "连接设备"
        deviceLabel.text = currentDeviceName
        deviceButton.contentDescription = "选择设备，当前设备：$currentDeviceName，${if (online) "已连接" else "离线"}"
        connectionDot.background = Ui.rounded(if (online) Ui.GREEN else Ui.DIM, Ui.dp(this, 3).toFloat())
        val hasOpenThread = currentThreadId.isNotEmpty()
        deviceButton.visibility = if (hasOpenThread) View.GONE else View.VISIBLE
        demoBadge.visibility = if (!hasOpenThread && info.optBoolean("mock")) View.VISIBLE else View.GONE
        topbarTitle.text = if (hasOpenThread) currentThread?.string("name").orEmpty().ifBlank { "无题" } else "Codex Remote"
        val canRename = hasOpenThread && !loading
        topbarTitle.isClickable = canRename
        topbarTitle.isFocusable = canRename
        topbarTitle.tooltipText = if (canRename) "修改会话标题" else null
        cwdLabel.text = currentCwd.ifEmpty { "点击设置工作目录" }
        cwdLabel.contentDescription = if (currentCwd.isEmpty()) "点击设置工作目录" else "设置工作目录，当前目录：$currentCwd"
        cwdLabel.isEnabled = !sending && !loading
        workspaceSpinner.contentDescription = "选择已有工作目录，当前目录：${currentCwd.ifEmpty { "未设置" }}"
        workspaceSpinner.visibility = if (workspaceValues().isEmpty()) View.GONE else View.VISIBLE
        welcome.visibility = if (currentThread == null && adapter.count == 0) View.VISIBLE else View.GONE
        messageList.visibility = if (currentThread == null && adapter.count == 0) View.GONE else View.VISIBLE
        olderButton.visibility = if (historyCursor != null) View.VISIBLE else View.GONE
        activityRow.visibility = if (running) View.VISIBLE else View.GONE
        activityText.text = if (online) "Codex 正在处理" else "连接已断开，恢复后同步任务"
        composer.isEnabled = !readOnly
        workspaceSpinner.isEnabled = online && !sending && !loading
        modelButton.isEnabled = online && !sending && !loading && !running && !readOnly
        effortSpinner.isEnabled = modelButton.isEnabled
        runningActions.visibility = if (running) View.VISIBLE else View.GONE
        updateSendState()
    }

    private fun updateSendState() {
        if (!mainBuilt || !::sendButton.isInitialized) return
        val hasPayload = composer.text.isNotBlank() || attachmentFiles.isNotEmpty()
        val editingQueuedMessage = editingQueueDraft == composerBinding
        val showContinue = canContinueTurn()
        continueButton.visibility = if (showContinue) View.VISIBLE else View.GONE
        continueButton.isEnabled = showContinue && online && loaded && !loading && !sending && currentThreadId !in deletingThreads
        continueButton.alpha = if (continueButton.isEnabled) 1f else .35f
        sendButton.visibility = if (hasPayload) View.VISIBLE else View.GONE
        sendButton.isEnabled = hasPayload && online && loaded && !sending && !loading && !preparingAttachments && !readOnly && !editingQueuedMessage &&
            (!running || info.optBoolean("messageQueue")) && (attachmentFiles.isEmpty() || info.has("attachments"))
        sendButton.alpha = if (sendButton.isEnabled) 1f else .35f
        sendButton.contentDescription = if (running) "排队发送" else "发送消息"
        stopButton.visibility = if (running && !readOnly) View.VISIBLE else View.GONE
        // Keep the button clickable when merely offline or unsupported so a tap explains
        // why (a disabled button gave Android 12 users silent "no response"); dim it instead.
        attachButton.isEnabled = !sending && !loading && !readOnly && !preparingAttachments && !editingQueuedMessage
        val attachReady = attachButton.isEnabled && online && loaded && info.has("attachments")
        attachButton.alpha = if (attachReady) 1f else .35f
        if (!attachReady && attachmentChoices.visibility == View.VISIBLE) showAttachmentChoices(false)
        stopButton.isEnabled = !readOnly && online && !sending && !loading
    }

    private fun persistOutbox() {
        store.savePendingSends(currentAgent(), outbox.values())
        if (mainBuilt) renderOutbox()
    }

    private fun renderBootstrapState() {
        if (!mainBuilt) return
        bootstrapArea.removeAllViews()
        if (!bootstrapping && bootstrapError.isEmpty()) {
            bootstrapArea.visibility = View.GONE
            return
        }
        bootstrapArea.visibility = View.VISIBLE
        val card = Ui.column(this).apply {
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
            background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 12).toFloat(), if (bootstrapError.isEmpty()) Ui.BORDER else Ui.DANGER)
        }
        card.addView(Ui.text(this, if (bootstrapping) "正在同步设备信息与会话…" else "设备初始化失败：$bootstrapError", 12f, if (bootstrapError.isEmpty()) Ui.MUTED else Ui.DANGER))
        if (!bootstrapping && bootstrapError.isNotEmpty()) {
            card.addView(Ui.button(this, "重试初始化", primary = true) { bootstrap() }, matchWidth(top = 8))
        }
        bootstrapArea.addView(card, matchWidth(bottom = 8))
    }

    private fun renderOutbox() {
        if (!mainBuilt) return
        outboxArea.removeAllViews()
        val pending = outbox.values()
        outboxArea.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE
        pending.forEach { value ->
            val waiting = value.id == activePendingSendId && value.stage == PendingSendStage.WAITING
            val preview = value.submittedText.trim().ifEmpty { value.files.firstOrNull()?.string("name").orEmpty() }.take(80)
            val target = if (value.threadId.isNotEmpty()) "会话 ${value.threadId}" else "新会话 · ${value.cwd}"
            val row = Ui.column(this).apply {
                setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
                minimumHeight = Ui.dp(context, 56)
                background = Ui.ripple(Ui.SURFACE, Ui.dp(context, 12).toFloat(), Ui.BORDER)
                isFocusable = true
                contentDescription = "${if (waiting) "等待发送确认" else "发送状态未确认"}：$preview"
                setOnClickListener { showPendingSend(value.id) }
            }
            row.addView(Ui.text(this, if (waiting) "等待设备确认发送…" else "发送状态未确认 · 不会自动重发", 12f, if (waiting) Ui.MUTED else Ui.DANGER, true))
            row.addView(Ui.text(this, "$target\n$preview", 11f, Ui.MUTED).apply { maxLines = 3 })
            outboxArea.addView(row, matchWidth(bottom = 8))
        }
    }

    private fun showPendingSend(id: String) {
        val value = outbox.get(id) ?: return
        if (id == activePendingSendId && value.stage == PendingSendStage.WAITING) {
            toast("请求仍在等待设备确认")
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("发送状态未确认")
            .setMessage("内容已保留，但无法确定设备是否已接受。请先核对会话历史；应用不会自动重发。\n\n${value.submittedText.take(500)}")
            .setPositiveButton("核对会话") { _, _ -> verifyPendingSend(value) }
            .setNeutralButton("清除标记") { _, _ -> resolvePendingSend(value) }
        if (!value.continuation) dialog.setNegativeButton("仍然重发") { _, _ -> retryPendingSend(value) }
        styledDialog(dialog)
    }

    private fun preservePendingDraft(value: PendingSend) {
        val workspace = if (value.threadId.isEmpty()) pathKey(value.cwd) else null
        val saved = store.draft(value.agentId, value.threadId, workspace)
        store.saveDraft(value.agentId, value.threadId, OutboxRecovery.draft(saved, listOf(value)), workspace)
    }

    private fun verifyPendingSend(value: PendingSend) {
        preservePendingDraft(value)
        if (value.threadId.isNotEmpty()) {
            if (sending || loading) { toast("请等待当前请求完成后核对"); return }
            openThread(value.threadId)
        } else {
            refreshAll()
            toast("尚未确认新会话是否创建，请核对会话列表后再决定是否重发")
        }
    }

    private fun retryPendingSend(value: PendingSend) {
        if (outbox.matching(composerBinding, ::pathKey).none { it.id == value.id }) {
            toast("先打开对应会话并核对内容")
            return
        }
        preservePendingDraft(value)
        restoreDraft(ComposerRestoreToken(composerBinding, composerEditVersion))
        outbox.confirmed(value.id); persistOutbox()
        sendMessage(value.steer && running)
    }

    private fun resolvePendingSend(value: PendingSend) {
        preservePendingDraft(value)
        outbox.confirmed(value.id); persistOutbox()
        restoreDraft(ComposerRestoreToken(composerBinding, composerEditVersion))
        toast("已清除未确认标记，草稿与附件保留")
    }

    private fun recoverPendingAttachments(binding: ComposerBinding) {
        val targetKey = binding.attachmentKey(::pathKey)
        var changed = false
        outbox.matching(binding, ::pathKey).forEach { value ->
            if (value.attachmentKey.isEmpty() || value.attachmentKey == targetKey) return@forEach
            val source = attachmentStore.load(value.attachmentKey)
            val target = attachmentStore.load(targetKey)
            val merged = LinkedHashMap<String, JSONObject>()
            (target + source).forEach { merged[it.string("draftId").ifEmpty { it.string("path") }] = it }
            attachmentStore.save(targetKey, merged.values.toList())
            attachmentStore.save(value.attachmentKey, emptyList())
            outbox.update(value.copy(attachmentKey = targetKey))
            changed = true
        }
        if (changed) persistOutbox()
    }

    private fun rebindComposer(binding: ComposerBinding): ComposerRestoreToken {
        if (mainBuilt && attachmentChoices.visibility == View.VISIBLE) showAttachmentChoices(false)
        composerBinding = binding
        if (mainBuilt) {
            setComposerText("")
            renderAttachments()
        } else attachmentFiles = mutableListOf()
        return ComposerRestoreToken(binding, composerEditVersion).also { composerRestoreToken = it }
    }

    private fun bindNewSessionDraft() {
        if (!mainBuilt || currentThreadId.isNotEmpty() || openingThreadId.isNotEmpty() || restoreThreadId.isNotEmpty()) return
        if (composerBinding.agentId != currentAgent() || composerBinding.threadId.isNotEmpty()) return
        val previous = composerRestoreToken
        val binding = ComposerBinding(currentAgent(), "", currentCwd)
        if (binding != composerBinding) {
            composerBinding = binding
            composerRestoreToken = previous.copy(binding = binding)
        }
        // If input arrived while info/workspaces were in flight, keep it verbatim;
        // only an untouched binding may be populated from persistent state.
        restoreDraft(composerRestoreToken)
    }

    private fun restoreDraft(token: ComposerRestoreToken = ComposerRestoreToken(composerBinding, composerEditVersion)) {
        if (!mainBuilt || token.binding != composerBinding) return
        val binding = token.binding
        val workspace = if (binding.threadId.isEmpty()) pathKey(binding.cwd) else null
        recoverPendingAttachments(binding)
        if (token.editVersion == composerEditVersion) {
            val saved = store.draft(binding.agentId, binding.threadId, workspace)
            setComposerText(OutboxRecovery.draft(saved, outbox.matching(binding, ::pathKey)))
        }
        renderAttachments(); updateSendState()
    }

    private fun saveDraft() {
        if (!mainBuilt) return
        val binding = composerBinding
        val workspace = if (binding.threadId.isEmpty()) pathKey(binding.cwd) else null
        store.saveDraft(binding.agentId, binding.threadId, composer.text.toString(), workspace)
    }

    private fun setComposerText(value: String) {
        if (composer.text.toString() == value) return
        applyingComposerText = true
        try {
            composer.setText(value)
            composer.setSelection(composer.text.length)
        } finally {
            applyingComposerText = false
        }
    }
    private fun copyMessage(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("CodexRM", text))
        // Android 13+ shows its own clipboard confirmation overlay.
        if (Build.VERSION.SDK_INT < 33) toast("消息已复制")
    }

    private fun rpc(method: String, params: JSONObject = JSONObject(), callback: RpcCallback) {
        val current = client; if (current == null) callback.onComplete(null, IOException("设备未连接")) else current.request(method, params, callback = callback)
    }
    private fun active(value: Int): Boolean = value == generation && online && mainVisible
    private fun openActive(id: String, value: Int): Boolean = value == openGeneration && id == openingThreadId && online
    private fun currentAgent(): String = device?.agentId.orEmpty()
    private fun directFilesAvailable(): Boolean = client?.usingDirect == true && info.optBoolean("directFiles")
    private fun updateDirectFiles() {
        workspaceFiles.connectionChanged()
        if (::adapter.isInitialized) adapter.showFileCards = directFilesAvailable()
    }
    private fun downloadMessageFile(file: ArtifactCard) {
        if (!directFilesAvailable()) {
            toast(if (client?.usingDirect == true) "当前 Agent 不支持直连文件下载" else "P2P 直连后可下载此文件")
            return
        }
        workspaceFiles.download(RemoteFile(currentCwd, currentThreadId, file.path), file.name)
    }

    private fun openWorkspaceFiles(path: String, threadId: String): Boolean {
        if (path.isEmpty()) return false
        if (!directFilesAvailable()) {
            toast(if (client?.usingDirect == true) "当前 Agent 不支持直连文件管理" else "P2P 直连后才能管理文件")
            return true
        }
        workspaceFiles.open(RemoteFile(path, threadId, ""))
        return true
    }
    private fun requestKey(id: Any?): String = "${id?.javaClass?.simpleName}:${id.toString()}"
    private fun pathKey(path: String): String {
        var value = if (info.string("os") == "windows" || device?.os == "windows") path.replace('\\', '/').lowercase() else path
        value = value.trimEnd('/'); return value.ifEmpty { "/" }
    }
    private fun folderName(path: String): String = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
    private fun matchWidth(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(this@MainActivity, top); bottomMargin = Ui.dp(this@MainActivity, bottom) }
    private fun String?.ifNullOrEmpty(fallback: String): String = if (isNullOrEmpty()) fallback else this
    private fun toast(message: String?) { if (!message.isNullOrEmpty()) Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun styledDialog(builder: AlertDialog.Builder): AlertDialog = styledDialog(builder.create())
    private fun styledDialog(dialog: AlertDialog): AlertDialog = dialog.apply {
        show()
        setOnShowListener { window?.setBackgroundDrawable(Ui.rounded(Ui.BACKGROUND, Ui.dp(context, 20).toFloat(), Ui.BORDER)) }
    }


    private fun currentBackTarget(): BackTarget? = when {
        mainVisible && sidebarOpen && devicesExpanded -> BackTarget.DEVICES
        mainVisible && sidebarOpen -> BackTarget.SIDEBAR
        mainVisible && attachmentChoices.visibility == View.VISIBLE -> BackTarget.ATTACHMENTS
        !mainVisible && devices.isNotEmpty() -> BackTarget.PAIR
        else -> null
    }

    private fun performBack(target: BackTarget) = when (target) {
        BackTarget.DEVICES -> toggleDevices()
        BackTarget.SIDEBAR -> closeSidebar()
        BackTarget.ATTACHMENTS -> showAttachmentChoices(false)
        BackTarget.PAIR -> showMain()
    }

    private fun startBackGesture(progress: Float, fromRightEdge: Boolean) {
        if (backGesture != null) finishBackGesture(commit = backGesture?.completion != null)
        val target = currentBackTarget() ?: return
        val preview = when (target) {
            BackTarget.DEVICES -> panelBackPreview(deviceDropdown, -Ui.dp(this, 16).toFloat())
            BackTarget.SIDEBAR -> sidebarBackPreview()
            BackTarget.ATTACHMENTS -> panelBackPreview(attachmentChoices, Ui.dp(this, 16).toFloat())
            BackTarget.PAIR -> pairBackPreview(if (fromRightEdge) -1 else 1)
        }
        backGesture = BackGesture(target, preview)
        progressBackGesture(progress)
    }

    private fun sidebarBackPreview(): BackPreview {
        sidebar.animate().cancel()
        sidebarShade.animate().cancel()
        val startX = sidebar.translationX
        val width = sidebar.layoutParams.width.coerceAtLeast(1).toFloat()
        return BackPreview(
            draw = { progress -> drawSidebar(startX + (-width - startX) * progress) },
            restore = { committing -> if (!committing) animateSidebar() },
        )
    }

    private fun panelBackPreview(panel: View, offset: Float): BackPreview {
        panel.animate().cancel()
        val startY = panel.translationY
        val startAlpha = panel.alpha
        return BackPreview(
            draw = { progress ->
                panel.translationY = startY + (offset - startY) * progress
                panel.alpha = startAlpha * (1f - progress)
            },
            restore = {
                panel.translationY = 0f
                panel.alpha = 1f
            },
        )
    }

    private fun backPageParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun pairBackPreview(direction: Int): BackPreview? {
        val page = pairPage ?: return null
        val host = page.parent as? FrameLayout ?: return null
        if (!mainBuilt || mainRoot.parent != null) return null
        val conversation = mainRoot
        val previousAccessibility = conversation.importantForAccessibility
        val previousFocusability = conversation.descendantFocusability
        // Reveal the conversation while keeping focus and input on the pairing form.
        conversation.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        conversation.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val touchShield = View(this).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        host.addView(conversation, 0, backPageParams())
        host.addView(touchShield, 1, backPageParams())
        host.requestApplyInsets()
        return BackPreview(
            draw = { progress -> page.translationX = direction * host.width * progress },
            restore = {
                page.translationX = 0f
                host.removeView(touchShield)
                host.removeView(conversation)
                conversation.importantForAccessibility = previousAccessibility
                conversation.descendantFocusability = previousFocusability
            },
        )
    }

    private fun progressBackGesture(progress: Float) {
        val gesture = backGesture ?: return
        if (gesture.invalidated || gesture.completion != null) return
        gesture.progress = progress.coerceIn(0f, 1f)
        gesture.preview?.draw?.invoke(gesture.progress)
    }

    private fun invokeSystemBack() {
        val gesture = backGesture
        if (gesture == null) {
            currentBackTarget()?.let(::performBack)
            return
        }
        val preview = gesture.preview
        if (gesture.invalidated || gesture.completion != null || preview == null || gesture.progress >= 1f) {
            finishBackGesture(commit = true)
            return
        }
        // The platform may commit before handing us progress 1 (for example after a fling).
        val completion = ValueAnimator.ofFloat(gesture.progress, 1f).apply {
            duration = (150 * (1f - gesture.progress)).toLong().coerceIn(50, 150)
            addUpdateListener { preview.draw(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishBackGesture(commit = true)
            })
        }
        gesture.completion = completion
        completion.start()
    }

    private fun invalidateBackPreview() {
        val gesture = backGesture ?: return
        if (gesture.invalidated) return
        // A network or UI update can replace the destination mid-swipe. Consume this swipe
        // without applying it to the new destination, even if the previous panel reopens.
        gesture.invalidated = true
        gesture.preview?.restore?.invoke(false)
        gesture.preview = null
        if (gesture.completion != null) finishBackGesture(commit = false)
    }

    private fun finishBackGesture(commit: Boolean) {
        val gesture = backGesture ?: return
        backGesture = null
        gesture.completion?.removeAllListeners()
        gesture.completion?.cancel()
        val committing = commit && !gesture.invalidated && currentBackTarget() == gesture.target
        gesture.preview?.restore?.invoke(committing)
        if (committing) performBack(gesture.target)
        updateBackHandling()
    }

    @SuppressLint("GestureBackNavigation") // API 28-32 fallback; newer versions use the platform dispatcher.
    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val target = currentBackTarget()
        if (target == null) super.onBackPressed() else performBack(target)
    }

    private fun updateBackHandling() {
        val callback = backCallback ?: return
        val target = currentBackTarget()
        if (backGesture?.let { it.target != target } == true) invalidateBackPreview()
        val interceptBack = target != null || backGesture != null
        if (interceptBack == backCallbackRegistered) return
        if (interceptBack) {
            SystemBackCompat.register(this, callback)
        } else {
            SystemBackCompat.unregister(this, callback)
        }
        backCallbackRegistered = interceptBack
    }
}
