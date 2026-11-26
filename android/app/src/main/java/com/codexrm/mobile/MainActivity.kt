package com.codexrm.mobile

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import okhttp3.Call
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.RadioButton
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
private const val PICK_APPEARANCE_IMAGE = 48
private const val CAPTURE_ATTACHMENT_PERMISSION = 46
private const val ALBUM_WRITE_PERMISSION = 47
private const val ATTACHMENT_CAMERA_URI_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

internal class MainActivity : ComponentActivity() {
    private lateinit var store: DeviceStore
    private val localCodexDevice = Device("", "", "", LocalCodex.AGENT, "本机 Codex", os = "android")
    private var offerLocalPermission = false
    private var waitingLocalInstall = false
    private var waitingLocalInstallSource = false
    private var downloadingLocalCodex = false
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
    // Session-only marks: a 401 means the token is dead until re-paired, so never auto-return to it.
    private val expiredAgentIds = mutableSetOf<String>()
    private var device: Device? = null
    private var client: CodexClient? = null
    private var pairCall: Call? = null
    private var cancelPairVerification: (() -> Unit)? = null
    private var pairOperation = 0
    private var outboxOwnerAgentId = ""
    private data class DeferredOpen(val agentId: String, val threadId: String, val silent: Boolean, val archived: Boolean)
    private var pendingOpenThread: DeferredOpen? = null
    private val processPendingOpen = Runnable { drainPendingOpen() }
    private val pendingShares = ArrayDeque<Intent>()
    private var bootstrapListVersion = 0
    private val conversationResults = LinkedHashMap<Int, MiuixDialog>()
    private var nextConversationPanelId = 0
    private var updateCall: Call? = null
    private var updateDialog: MiuixDialog? = null
    private enum class UpdateInstallPhase { IDLE, SILENT, INTERACTIVE, HANDED_OFF, FINISHED }

    private var updateFailureReceiver: BroadcastReceiver? = null
    private var updateInstallStatus: TextView? = null
    private var updateInstallApk: File? = null
    private var updateInstallInfo: UpdateChecker.VersionInfo? = null
    private var updateInstallOperation = 0
    private var updateInstallRequest = ""
    private var ignoredAbortRequest = ""
    private var updateInstallPhase = UpdateInstallPhase.IDLE
    private val updateSilentFallback = Runnable { fallBackFromSilentInstall() }
    private var connectionDialog: MiuixDialog? = null
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

    private lateinit var chatBody: View
    private var transferPage: View? = null
    private lateinit var mainRoot: SidebarSwipeLayout
    private lateinit var contentRoot: LinearLayout
    private lateinit var topbar: LinearLayout
    private lateinit var composerZone: LinearLayout
    private var readingMode = false
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
    private var referenceSearch: ReferenceSearch? = null
    private lateinit var transfers: PhoneTransferUi
    private var configSheet: PredictiveBackDialog? = null
    private var configModelValue: TextView? = null
    private var configEffortValue: TextView? = null
    private lateinit var deviceButton: LinearLayout
    private lateinit var connectionButton: FrameLayout
    private var sidebarConnectionButton: FrameLayout? = null
    private lateinit var deviceLabel: TextView
    private lateinit var connectionDot: View
    private lateinit var connectionNotice: TextView
    private lateinit var topbarTitle: TextView
    private lateinit var messageList: ChatListView
    private lateinit var latestButton: View
    private lateinit var diffButton: TextView
    private val conversationCache by lazy { ConversationCache(this) }
    private val conversationPrefs by lazy { getSharedPreferences("conversation_ui", MODE_PRIVATE) }
    private var cachedAt = 0L
    private var latestDiff = ""
    private var diffTurn = ""
    private val reasoningStarted = mutableMapOf<String, Long>()
    private val reasoningStatus = mutableMapOf<String, String>()
    private val attachmentProgress = mutableMapOf<String, TextView>()
    private var uploadingDraft = ""
    private var failedUploadDraft = ""
    private var markupDialog: MiuixDialog? = null
    private var openingMarkup = false
    private val reviewPendingShare = Runnable { reviewSharedDraft() }
    private var shareDialog: MiuixDialog? = null
    private var shareForThread: Pair<ComposerBinding, SharedContent>? = null
    private var pendingBranchDraft: BranchDraft? = null
    private var conversationJump: ConversationJump? = null
    private var archivedThreadId = ""
    private var pinnedThreadIds = emptySet<String>()
    private var pinnedThreads = emptyList<ThreadSummary>()
    private var pinRequestVersion = 0
    private lateinit var welcome: ScrollView
    private lateinit var welcomeDescription: TextView
    private lateinit var welcomePrimary: MiuixButtonView
    private lateinit var welcomeSecondary: MiuixButtonView
    private lateinit var adapter: MessageAdapter
    private lateinit var composer: EditText
    private lateinit var attachButton: FrameLayout
    private var attachmentSheet: Dialog? = null
    private lateinit var sendButton: FrameLayout
    private lateinit var continueButton: FrameLayout
    private lateinit var attachmentScroll: HorizontalScrollView
    private lateinit var attachmentRows: LinearLayout
    private lateinit var attachmentStatus: TextView
    private lateinit var sendHint: TextView
    private lateinit var stopButton: FrameLayout
    private lateinit var workspaceButton: LinearLayout
    private lateinit var workspaceFilesButton: View
    private lateinit var modelButton: TextView
    private lateinit var cwdLabel: TextView
    private lateinit var steerButton: MiuixButtonView
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
    private var pairSubmit: MiuixButtonView? = null

    private var online = false
    private var connectionState = ConnectionState.CONNECTING
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
    private val providerUnavailable get() = currentThread?.optBoolean("codexrmProviderUnavailable") == true
    private val providerSwitchHint = "请切换到可用的供应商后继续对话"
    private var activeTurnId = ""
    private var latestTurn: TurnProgress? = null
    private var startedAt = 0L
    private var currentThread: JSONObject? = null
    private var currentThreadId = ""
    private var restoreThreadId = ""
    private var openingThreadId = ""
    private var currentCwd = ""
    private var info = JSONObject()
    private val threadList = ThreadListState()
    private val threads: List<ThreadSummary> get() = threadList.entries
    private var threadCursor: String? = null
    private var threadListLoading = false
    private var threadListError = ""
    private var threadListVersion = 0
    private val deletingThreads = HashSet<String>()
    private var workspaces = listOf<Workspace>()
    private var modelCatalog = ModelCatalog()
    private var modelLoadSequence = 0
    private var modelCatalogLoaded = false
    private var settingsPage: SettingsPage? = null
    private lateinit var appearance: ChatAppearance
    private var pendingBackgroundPicker: AppearanceArea? = null
    private var selectionDialog: Dialog? = null
    private val modelRefresh = object : Runnable {
        override fun run() {
            if (!resumed) return
            if (loaded && online) loadModels()
            handler.postDelayed(this, 180_000L)
        }
    }
    private var models = listOf<ModelOption>()
    private var selectedModelOption: ModelOption? = null
    private var explicitModelChoice: ModelChoice? = null
    private var effortSelection = ""
    private var generation = 0
    private var openGeneration = 0
    private var sidebarOpen = false
    private enum class BackTarget { DEVICES, SIDEBAR, TRANSFERS, PAIR }

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
    private var showSidebarWorkspaces = false
    private var sidebarThreadLimit = 30
    private lateinit var recentTab: TextView
    private lateinit var workspacesTab: TextView
    private lateinit var workspaceActions: LinearLayout
    private lateinit var sidebarScroll: ScrollView
    private var devicesExpanded = false
    private var devicePopup: MiuixDialogHost? = null
    private val items = LinkedHashMap<String, JSONObject>()
    private val userMessages = UserMessageReconciler()
    private data class DeltaBuffer(val id: String, val type: String, val turnId: String, val text: StringBuilder)
    private val deltaBuffers = HashMap<String, DeltaBuffer>()
    private val dirtyDeltaIds = LinkedHashSet<String>()
    private val dirtyMessageIds = LinkedHashSet<String>()
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

    private val replySpeech by lazy { ReplySpeech(this, ::toast) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DeviceStore(this)
        transfers = PhoneTransferUi(this, { device }, { client.takeIf { online && mainVisible } }, { info }, ::toast)
        appearance = ChatAppearance(this, ::applyAppearance)
        pendingBackgroundPicker = AppearanceArea.entries.firstOrNull { it.key == savedInstanceState?.getString("backgroundPicker") }
        attachmentStore = AttachmentStore(this)
        attachmentStore.cleanupStaleFiles()
        imageLoader = MessageImageLoader(this) {
            ImageEnvironment(
                "${device?.serverUrl.orEmpty().trimEnd('/')}\n${currentAgent()}", currentCwd, client,
                info.optBoolean("images"), info.optJSONObject("attachments")?.optBoolean("relayImages") == true, currentThreadId,
            )
        }
        workspaceFiles = WorkspaceFileUi(this, { client }, { device }, { supportsFileDownloads() }) {
            if (::adapter.isInitialized) adapter.refreshFileCards()
        }
        attachmentPickerKey = savedInstanceState?.getString("attachmentPickerKey").orEmpty()
        attachmentCameraFile = savedInstanceState?.getString("attachmentCameraFile").orEmpty()
        Ui.configure(this, store.darkTheme)
        devices = store.load()
        restoreThreadId = savedInstanceState?.getString("threadId").orEmpty()
        waitingLocalInstall = savedInstanceState?.getBoolean("waitingLocalInstall") == true
        waitingLocalInstallSource = savedInstanceState?.getBoolean("waitingLocalInstallSource") == true
        device = if (store.selectedId == LocalCodex.AGENT && LocalCodex.supported) localCodexDevice
            else devices.firstOrNull { it.agentId == store.selectedId } ?: devices.firstOrNull()
        outboxOwnerAgentId = currentAgent()
        if (device == null) showPair() else showMain(reset = true)
        restoreModelChoice(savedInstanceState)
        if (savedInstanceState == null) acceptExternalIntent(intent)
        else {
            @Suppress("DEPRECATION")
            val queued = savedInstanceState.getParcelableArrayList<Intent>("pendingShares")
            if (queued != null) pendingShares.addAll(queued)
        }
        ChatEntryPoints.registerShortcuts(this)
        if (Build.VERSION.SDK_INT < 33 && !store.legacySystemWarningShown) {
            store.legacySystemWarningShown = true
            styledDialog(
                MiuixDialog.Builder(this)
                    .setTitle("系统版本提示")
                    .setMessage("当前系统版本低于 Android 13，本应用可能无法完全兼容 Android 13 以下的系统，部分功能可能出现异常。建议将系统更新到 Android 13 或更高版本。")
                    .setPositiveButton("我知道了", null),
            )
        }
        checkForUpdatesOnStartup()
    }

    override fun onStart() {
        super.onStart()
        TaskMonitor.stop(this)
        started = true
        appearance.start()
        if (mainVisible && client == null) connect()
        else if (mainVisible && online && !loaded && !bootstrapping) bootstrap()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptExternalIntent(intent)
    }
    override fun onResume() {
        super.onResume()
        TaskMonitor.stop(this)
        resumed = true
        transfers.resume()
        if (waitingLocalInstall && LocalCodex.available(this)) {
            waitingLocalInstall = false
            chooseLocalCodex()
        }
        if (waitingLocalInstallSource) {
            waitingLocalInstallSource = false
            if (UpdateChecker.canInstall(this)) downloadLocalCodex()
        }
        DeviceRevokeJob.onFailure = ::showRevocationFailure
        showRevocationFailure()
        if (store.pendingRevocations().isNotEmpty()) DeviceRevokeJob.schedule(this)
        handler.postDelayed(modelRefresh, 180_000L)
        val pending = pendingUpdate
        if (pending != null) {
            pendingUpdate = null
            if (UpdateChecker.canInstall(this)) startUpdateDownload(pending) else showUpdateAvailable(pending)
        }
        showStartupUpdateIfReady()
        reviewSharedDraft()
    }
    override fun onPause() {
        resumed = false
        transfers.pause()
        DeviceRevokeJob.onFailure = null
        handler.removeCallbacks(modelRefresh)
        if (mainVisible && online && currentAgent() != LocalCodex.AGENT && !isChangingConfigurations && TaskMonitor.enabled(this, currentAgent())) {
            val activeThreads = threadList.activeIds.toMutableSet()
            if (running && currentThreadId.isNotEmpty()) activeThreads.add(currentThreadId)
            if (activeThreads.isNotEmpty()) {
                val monitor = Intent(this, TaskMonitor::class.java)
                    .putExtra(TaskMonitor.DEVICE, currentAgent())
                    .putExtra(TaskMonitor.ACTIVE_THREADS, activeThreads.toTypedArray())
                runCatching { startForegroundService(monitor) }
            }
        }
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) showStartupUpdateIfReady()
    }
    override fun onStop() {
        started = false
        appearance.stop()
        finishBackGesture(commit = false)
        replySpeech.stop()
        workspaceFiles.activityStopped()
        saveDraft()
        devicePopup?.dismiss()
        handler.removeCallbacks(snapshotTicker)
        disconnect()
        super.onStop()
    }
    override fun onDestroy() {
        appearance.close()
        pairOperation++
        cancelPairRequest()
        pairCall = null
        transfers.destroy()
        referenceSearch?.dismiss()
        configSheet?.dismiss()
        shareDialog?.dismiss()
        settingsPage?.dismiss()
        settingsPage = null
        selectionDialog?.dismiss()
        selectionDialog = null
        attachmentSheet?.dismiss()
        markupDialog?.dismiss()
        if (backCallbackRegistered) {
            SystemBackCompat.unregister(this, backCallback)
        }
        cancelUpdateOperation()
        cancelPairRequest()
        pendingUpdate = null
        if (client != null) disconnect()
        handler.removeCallbacksAndMessages(null)
        replySpeech.close()
        imageLoader.close()
        workspaceFiles.close()
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("waitingLocalInstall", waitingLocalInstall)
        outState.putBoolean("waitingLocalInstallSource", waitingLocalInstallSource)
        pendingBackgroundPicker?.let { outState.putString("backgroundPicker", it.key) }
        saveDraft()
        outState.putString("threadId", currentThreadId)
        explicitModelChoice?.let { choice ->
            outState.putString("modelChoiceThread", choice.threadId)
            outState.putString("modelChoiceProvider", choice.providerId)
            outState.putString("modelChoiceModel", choice.model)
            outState.putString("modelChoiceEffort", choice.effort)
        }
        outState.putString("attachmentPickerKey", attachmentPickerKey)
        outState.putString("attachmentCameraFile", attachmentCameraFile)
        outState.putParcelableArrayList("pendingShares", ArrayList(pendingShares))
        super.onSaveInstanceState(outState)
    }

    private fun showPair() {
        closeTransfers()
        settingsPage?.dismiss()
        settingsPage = null
        pairOperation++
        cancelPairRequest()
        pairCall = null
        invalidateBackPreview()
        saveDraft()
        workspaceFiles.closeManager()
        disconnect()
        mainVisible = false
        if (mainBuilt) {
            sidebarOpen = false
            updateSidebarAccessibility()
            mainRoot.stopSidebarSwipe()
            sidebar.animate().cancel()
            sidebarShade.animate().cancel()
            drawSidebar(-sidebar.layoutParams.width.toFloat())
        }
        Ui.configure(this, store.darkTheme)
        renderPairPage()
    }

    private fun renderPairPage() {
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
        names.addView(Ui.text(this, "REMOTE WORKSPACE", 12f, Ui.DIM))
        brand.addView(names)
        card.addView(brand)
        card.addView(Ui.text(this, "连接你的电脑", 24f).apply { setPadding(0, Ui.dp(context, 28), 0, Ui.dp(context, 8)) })
        card.addView(Ui.text(this, "扫描连接器显示的二维码自动配对，或输入一次性配对码。", 13f, Ui.MUTED).apply {
            setLineSpacing(0f, 1.4f); setPadding(0, 0, 0, Ui.dp(context, 22))
        })
        card.addView(Ui.button(this, "扫描二维码", primary = true) {
            startActivityForResult(Intent(this, ScannerActivity::class.java), 42)
        }, matchWidth())
        card.addView(Ui.text(this, "设备配对码 / 完整配对链接", 12f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 22), 0, Ui.dp(context, 7)) })
        val code = Ui.boxInput(this, "rm2.?").apply {
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
        }
        codeInput = code
        card.addView(code, matchWidth())
        val server = Ui.boxInput(this, "服务器地址（手动配对码需要）").apply { setText(store.lastServer); textSize = 12f }
        serverInput = server
        card.addView(server, matchWidth(top = 9))
        val error = Ui.text(this, pairMessage, 12f, Ui.DANGER).apply {
            visibility = if (pairMessage.isEmpty() || pairCall != null) View.GONE else View.VISIBLE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        pairError = error
        card.addView(error, matchWidth(top = 8))
        val submit = Ui.button(this, if (pairCall == null) "使用配对码连接" else "正在连接…") { submitManualPair() }.apply {
            isEnabled = pairCall == null
        }
        pairSubmit = submit
        card.addView(submit, matchWidth(top = 10))
        card.addView(Ui.text(this, "配对码有效期 10 分钟，仅可使用一次。\n只有完成配对的设备才能访问这台电脑。", 12f, Ui.DIM).apply {
            gravity = Gravity.CENTER; setLineSpacing(0f, 1.4f); setPadding(0, Ui.dp(context, 22), 0, 0)
        })
        if (device?.agentId == LocalCodex.AGENT || devices.any { it.agentId !in expiredAgentIds }) card.addView(Ui.button(this, "返回设备") { showMain() }, matchWidth(top = 12))
        if (LocalCodex.supported) card.addView(Ui.button(this, "本机 Codex") { chooseLocalCodex() }, matchWidth(top = 8))
        card.addView(Ui.button(this, "设置") { showAppSettings() }, matchWidth(top = 8))
        Ui.applyWindowInsets(scroll)
        pairPage = scroll
        setContentView(FrameLayout(this).apply { addView(scroll, backPageParams()) })
        updateBackHandling()
    }

    private fun submitManualPair() {
        try { pair(PairingLink.parse(codeInput?.text?.toString().orEmpty(), serverInput?.text?.toString().orEmpty())) }
        catch (error: Exception) { showPairError(error.message ?: "配对信息无效") }
    }

    private fun cancelPairRequest() {
        pairCall?.cancel()
        cancelPairVerification?.invoke()
        cancelPairVerification = null
    }

    private fun pair(data: PairingData) {
        val invitation = try { SecureInvitation.parse(data.code) } catch (error: IllegalArgumentException) {
            showPairError(error.message ?: "?????")
            return
        }
        if (!NativeP2P.available) {
            showPairError("??????????????? Android App")
            return
        }
        connectPair(data.serverUrl, invitation)
    }

    private fun connectPair(serverUrl: String, invitation: SecureInvitation) {
        cancelPairRequest()
        val operation = ++pairOperation
        pairSubmit?.isEnabled = false
        pairSubmit?.text = "?????"
        pairError?.visibility = View.GONE
        fun finishPairRequest() {
            pairSubmit?.isEnabled = true
            pairSubmit?.text = "???????"
            pairCall = null
            cancelPairVerification = null
        }
        val body = JSONObject()
            .put("route", invitation.route)
            .put("clientName", "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(80))
            .put("deviceType", "android")
        pairCall = Network.request(serverUrl, "/api/v2/client/route", body = body) { result, error ->
            if (isDestroyed || operation != pairOperation) return@request
            if (error != null || result == null) {
                finishPairRequest()
                showPairError(when (error?.status) {
                    404 -> "????????????????????????"
                    409 -> "????????????"
                    410 -> "????????????????"
                    else -> error?.message ?: "????"
                })
                return@request
            }
            val routed = try { Device.fromClaim(serverUrl, result) } catch (exception: Exception) {
                finishPairRequest()
                showPairError(exception.message ?: "??????")
                return@request
            }
            cancelPairVerification = verifySecurePairing(routed, invitation) { key, failure ->
                if (isDestroyed || operation != pairOperation) return@verifySecurePairing
                finishPairRequest()
                if (key == null) {
                    Network.request(routed.serverUrl, "/api/v1/client/revoke", routed.token, JSONObject()) { _, _ -> }
                    showPairError(failure ?: "????")
                    return@verifySecurePairing
                }
                val paired = routed.copy(secureKey = key)
                expiredAgentIds.remove(paired.agentId)
                val old = devices.firstOrNull { it.agentId == paired.agentId }
                devices.removeAll { it.agentId == paired.agentId }; devices.add(0, paired)
                store.save(devices); store.selectedId = paired.agentId; store.lastServer = paired.serverUrl
                disconnect()
                device = paired; pairMessage = ""; restoreThreadId = ""
                if (old != null && old.token != paired.token) Network.request(old.serverUrl, "/api/v1/client/revoke", old.token, JSONObject()) { _, _ -> }
                toast("??? ${paired.serverName}")
                settingsPage?.dismiss()
                settingsPage = null
                showMain(reset = true)
            }
        }
    }

    private fun showPairError(message: String) {
        pairMessage = message; pairError?.text = message; pairError?.visibility = View.VISIBLE
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 7361) {
            if (device?.agentId == LocalCodex.AGENT && !online) { client?.stop(); client = null; connect() }
            return
        }
        if (workspaceFiles.onActivityResult(requestCode, resultCode, data)) {
            settingsPage?.refreshActionSummaries()
            return
        }
        if (requestCode == PICK_APPEARANCE_IMAGE) {
            val area = pendingBackgroundPicker
            pendingBackgroundPicker = null
            val uri = data?.data
            if (area != null && uri != null && resultCode == RESULT_OK) {
                if (settingsPage?.isShowing != true) showAppSettings()
                settingsPage?.cropLocalImage(area, uri)
            }
            return
        }
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
        if (reset) { closeTransfers(); disconnect() }
        if (!mainBuilt) buildMain()
        mainVisible = true
        pairPage = null
        setContentView(mainRoot)
        device = if (store.selectedId == LocalCodex.AGENT && LocalCodex.supported) localCodexDevice
            else devices.firstOrNull { it.agentId == store.selectedId && it.agentId !in expiredAgentIds }
                ?: devices.firstOrNull { it.agentId !in expiredAgentIds }.also { store.selectedId = it?.agentId.orEmpty() }
        if (device == null) { showPair(); return }
        outboxOwnerAgentId = currentAgent()
        if (reset) {
            resetConversation()
            val id = restoreThreadId.ifEmpty { conversationPrefs.getString("last:${currentAgent()}", "").orEmpty() }
            if (id.isNotEmpty()) openCachedThread(id)
        }
        renderDevices(); renderWorkspaces(); updateControls()
        updateBackHandling()
        reviewSharedDraft()
        if (started) connect()
    }

    private fun buildMain() {
        mainBuilt = true
        sidebarOpen = false
        devicesExpanded = false
        devicePopup?.dismiss()
        adapter = MessageAdapter(this, imageLoader, { file, done ->
            workspaceFiles.size(RemoteFile(currentCwd, currentThreadId, file.path), done)
        }, ::openMessageFile, { file ->
            workspaceFiles.isDownloaded(RemoteFile(currentCwd, currentThreadId, file.path))
        }, ::showAsyncQuestions, ::asyncQuestionStatus, { message -> replySpeech.toggle(message.id, message.body) })
        updateDirectFiles()
        mainRoot = SidebarSwipeLayout(
            this,
            canOpenSidebar = { mainVisible && !sidebarOpen && !readingMode },
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
        chatBody = body
        contentRoot.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        welcome = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            val greeting = Ui.column(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPaddingRelative(Ui.dp(context, 28), Ui.dp(context, 16), Ui.dp(context, 28), Ui.dp(context, 16))
                addView(Ui.text(context, "接下来做什么？", 30f).apply {
                    isAccessibilityHeading = true
                }, matchWidth(bottom = 10))
                welcomeDescription = Ui.text(context, "连接电脑后即可发送任务", 14f, Ui.MUTED).apply {
                    accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                }
                addView(welcomeDescription, matchWidth(bottom = 12))
                welcomePrimary = Ui.button(context, "开始输入", primary = true) { performHomeAction(HomeAction.START_INPUT) }
                welcomePrimary.visibility = View.GONE
                addView(welcomePrimary, matchWidth(top = 4))
                welcomeSecondary = Ui.button(context, "选择设备") { performHomeAction(HomeAction.CHOOSE_DEVICE) }
                welcomeSecondary.visibility = View.GONE
                addView(welcomeSecondary, matchWidth(top = 8))
            }
            addView(greeting, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val transcript = Ui.column(this)
        diffButton = Ui.text(this, "查看改动", 13f, Ui.GREEN).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
            setOnClickListener { showConversationDiff() }
        }
        body.addView(diffButton, LinearLayout.LayoutParams(-1, Ui.dp(this, 40)))
        transcript.addView(welcome, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        messageList = ChatListView(this).apply {
            divider = null; dividerHeight = 0; cacheColorHint = Color.TRANSPARENT; selector = Ui.rounded(Color.TRANSPARENT, 0f)
            clipToPadding = false; setStackFromBottom(true)
            // Check the last row's bottom before relayout, including growth within a long reply.
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            adapter = this@MainActivity.adapter
            setPadding(Ui.dp(context, Ui.CHAT_LIST_GUTTER), Ui.dp(context, 8), Ui.dp(context, Ui.CHAT_LIST_GUTTER), 0)
            setOnItemLongClickListener { _, _, position, _ ->
                showMessageActions(this@MainActivity.adapter.getItem(position))
                true
            }
        }
        val list = messageList
        val messageAdapter = adapter
        messageAdapter.onStreamRendered = { id -> messageAdapter.refreshRows(list, setOf(id)) }
        val messagePane = FrameLayout(this)
        messagePane.addView(messageList, FrameLayout.LayoutParams(-1, -1))
        latestButton = Ui.button(this, "↓ 最新消息") { jumpToLatest() }.apply { visibility = View.GONE }
        messagePane.addView(latestButton, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = Ui.dp(this@MainActivity, 12)
        })
        messageList.setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: android.widget.AbsListView?, scrollState: Int) = Unit
            override fun onScroll(view: android.widget.AbsListView?, first: Int, visible: Int, total: Int) {
                latestButton.visibility = if (visible > 0 && messageList.canScrollVertically(1)) View.VISIBLE else View.GONE
                if (first == 0 && visible > 0 && historyCursor != null) loadOlderTurns()
            }
        })
        transcript.addView(messagePane, LinearLayout.LayoutParams(-1, -1))
        body.addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildComposer(body)
        buildSidebar()
        applyAppearance()
        applyReadingMode(resources.configuration)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (mainBuilt) applyReadingMode(newConfig)
    }

    private fun applyReadingMode(config: Configuration) {
        val compactLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
            config.smallestScreenWidthDp in 1 until 600
        val enabled = compactLandscape
        val changed = enabled != readingMode
        readingMode = enabled
        topbar.visibility = if (enabled && transferPage == null) View.GONE else View.VISIBLE
        composerZone.visibility = if (enabled) View.GONE else View.VISIBLE
        if (changed && enabled) {
            composer.clearFocus()
            if (sidebarOpen) closeSidebar()
            WindowCompat.getInsetsController(window, mainRoot).hide(WindowInsetsCompat.Type.ime())
        }
    }

    private fun buildTopbar() {
        val compact = resources.configuration.screenWidthDp < 400
        val actionSize = if (compact) 38 else 48
        fun iconWidth() = LinearLayout.LayoutParams(Ui.dp(this, actionSize), Ui.dp(this, 48))
        topbar = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
        }
        topbar.addView(ReferenceUi.circle(this, "menu-minimal", "打开侧栏", 44) { toggleSidebar() }, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        topbarTitle = Ui.text(this, "CodexRM", 15f, bold = true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0)
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 8).toFloat())
            setOnClickListener { renameCurrentThread() }
        }
        topbar.addView(topbarTitle, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        connectionButton = Ui.iconButton(this, "relay", "RM 中继", 36) { showConnectionDetails() }.apply {
            visibility = View.GONE
            layoutParams = iconWidth()
        }
        topbar.addView(connectionButton)
        deviceButton = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 20).toFloat(), Ui.BORDER)
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 8), 0)
            setOnClickListener { showDevices(this) }
        }
        connectionDot = Ui.dot(this, Ui.DIM)
        deviceButton.addView(connectionDot, LinearLayout.LayoutParams(Ui.dp(this, 6), Ui.dp(this, 6)))
        deviceLabel = Ui.text(this, "连接设备", 12f, Ui.MUTED).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(Ui.dp(context, 7), 0, Ui.dp(context, 5), 0)
        }
        deviceButton.addView(deviceLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        deviceButton.addView(IconView(this, "chevron", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(this, 12), Ui.dp(this, 12)))
        topbar.addView(deviceButton, LinearLayout.LayoutParams(Ui.dp(this, if (compact) 84 else 132), Ui.dp(this, 48)).apply { leftMargin = Ui.dp(this@MainActivity, 4) })
        topbar.addView(Ui.iconButton(this, "transfer", "文件传输助手", 40) { openTransfers() }, iconWidth())
        topbar.addView(Ui.iconButton(this, "chat-plus", "新会话", 42) { newChat(showKeyboard = true) }.apply {
            (getChildAt(0) as IconView).apply {
                setColor(Ui.TEXT)
                val iconSize = Ui.dp(context, 24)
                layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            }
        }, iconWidth())
        contentRoot.addView(topbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 60)))
    }

    private fun buildComposer(body: LinearLayout) {
        val zone = Ui.column(this).apply {
            val gutter = Ui.dp(context, Ui.CHAT_GUTTER)
            setPadding(gutter, Ui.dp(context, 8), gutter, Ui.dp(context, 2))
        }
        composerZone = zone
        connectionNotice = Ui.text(this, "", 12f, Ui.MUTED).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        zone.addView(connectionNotice, matchWidth(bottom = 8))
        bootstrapArea = Ui.column(this).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        zone.addView(bootstrapArea, matchWidth())
        outboxArea = Ui.column(this).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        zone.addView(outboxArea, matchWidth())
        approvalScroll = ScrollView(this).apply { visibility = View.GONE; isFillViewport = false }
        approvalArea = Ui.column(this).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        approvalScroll.addView(approvalArea)
        zone.addView(approvalScroll, matchWidth())
        queueArea = Ui.column(this).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        zone.addView(queueArea, matchWidth())
        activityRow = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), Ui.dp(context, 10))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            addView(Ui.dot(context, Ui.GREEN, 5), LinearLayout.LayoutParams(Ui.dp(context, 5), Ui.dp(context, 5)))
            activityText = Ui.text(context, "Codex 正在处理", 12f, Ui.MUTED).apply { setPadding(Ui.dp(context, 8), 0, 0, 0) }
            addView(activityText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            elapsedText = Ui.text(context, "", 12f, Ui.DIM); addView(elapsedText)
        }
        zone.addView(activityRow, matchWidth())
        val composerCard = Ui.column(this).apply {
            setPadding(Ui.dp(context, 9), Ui.dp(context, 12), Ui.dp(context, 9), Ui.dp(context, 8))
            background = Ui.rounded(ReferenceUi.card, Ui.dp(context, 26).toFloat(), ReferenceUi.border)
        }
        attachmentStatus = Ui.text(this, "", 12f, Ui.MUTED).apply {
            visibility = View.GONE
            maxLines = 2
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        composerCard.addView(attachmentStatus, matchWidth())
        attachmentRows = Ui.column(this, false)
        attachmentScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; visibility = View.GONE
            addView(attachmentRows)
        }
        composerCard.addView(attachmentScroll, matchWidth(bottom = 4))
        composer = Ui.input(this, "给 Codex 发消息…", multiLine = true).apply {
            setHintTextColor(Ui.MUTED); minHeight = Ui.dp(context, 46); maxHeight = Ui.dp(context, 200)
            background = null; textSize = 16f; setPadding(Ui.dp(context, 10), Ui.dp(context, 7), Ui.dp(context, 10), Ui.dp(context, 4))
            filters = arrayOf(android.text.InputFilter.LengthFilter(200000))
            contentDescription = "消息输入，回车换行"
            imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        }
        ViewCompat.setOnReceiveContentListener(composer, arrayOf("image/*")) { _, payload ->
            val parts = payload.partition { it.uri?.scheme == "content" && it.text == null && it.intent == null }
            val images = parts.first ?: return@setOnReceiveContentListener payload
            when {
                !attachButton.isEnabled -> toast("当前无法添加图片，请稍后重试")
                !online || !loaded -> toast("设备尚未连接或正在同步")
                !info.has("attachments") -> toast("请更新本地 Agent 以支持图片")
                else -> {
                    val uris = images.clip.let { clip -> (0 until minOf(10, clip.itemCount)).map { clip.getItemAt(it).uri } }.distinct()
                    // Keep the payload and any temporary IME grant alive until the copies finish.
                    stageAttachments(attachmentDraftKey(), uris) { java.lang.ref.Reference.reachabilityFence(payload) }
                }
            }
            parts.second
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
        val toolbar = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL }
        attachButton = Ui.iconButton(this, "plus", "添加文件或图片") { showAttachmentSheet() }.apply {
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 24).toFloat(), Ui.BORDER)
            (getChildAt(0) as IconView).setColor(Ui.TEXT)
        }
        toolbar.addView(attachButton)
        modelButton = Ui.text(this, InteractionCopy.composerModelLabel(null), 16f).apply {
            isSingleLine = true
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setWidth(Ui.dp(context, 80))
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(Ui.dp(context, 6), 0, Ui.dp(context, 6), 0)
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 23).toFloat())
            setOnClickListener { showConfiguration() }
        }
        // Only the model slot flexes; the action buttons are measured first.
        val modelSlot = FrameLayout(this).apply {
            addView(modelButton, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END,
            ))
        }
        toolbar.addView(modelSlot, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        steerButton = MiuixButtonView(this, "立即发送", compact = true).apply {
            visibility = View.GONE
            tooltipText = "将消息交给当前任务，不排队"
            setOnClickListener { sendMessage(steer = true) }
        }
        toolbar.addView(steerButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48)).apply {
            marginStart = Ui.dp(this@MainActivity, 4)
        })
        stopButton = circleButton("stop", "停止任务") { stopTurn() }.apply { visibility = View.GONE }
        toolbar.addView(stopButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { leftMargin = Ui.dp(this@MainActivity, 4) })
        sendButton = circleButton("arrow", "发送消息") { sendMessage(false) }
        toolbar.addView(sendButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { marginStart = Ui.dp(this@MainActivity, 4) })
        continueButton = circleButton("play", "继续中断的任务") { continueTurn() }.apply { visibility = View.GONE }
        toolbar.addView(continueButton, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)).apply { marginStart = Ui.dp(this@MainActivity, 4) })
        composerCard.addView(toolbar, matchWidth())
        sendHint = Ui.text(this, "", 12f, Ui.MUTED).apply {
            visibility = View.GONE
            maxLines = 2
            setPadding(Ui.dp(context, 4), Ui.dp(context, 4), Ui.dp(context, 4), 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        composerCard.addView(sendHint, matchWidth())
        zone.addView(composerCard, matchWidth())
        val meta = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(Ui.dp(context, 4), Ui.dp(context, 2), Ui.dp(context, 4), 0) }
        cwdLabel = Ui.text(this, "点击设置工作目录", 12f, Ui.MUTED).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        workspaceButton = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = Ui.dp(context, 48)
            isFocusable = true
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 6).toFloat())
            setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 12), 0)
            addView(cwdLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(IconView(context, "chevron", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(context, 24), Ui.dp(context, 24)))
            setOnClickListener { showWorkspaceChoices() }
            setOnLongClickListener { openWorkspaceFiles(currentCwd, currentThreadId) }
        }
        meta.addView(workspaceButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        workspaceFilesButton = Ui.iconButton(this, "folder", "电脑文件管理") {
            openWorkspaceFiles(currentCwd, currentThreadId, startAtComputer = true)
        }.apply { visibility = View.GONE }
        meta.addView(workspaceFilesButton)
        // Keep workspace actions accessible from the configuration sheet; avoid a redundant footer.
        zone.addView(meta, matchWidth())
        meta.visibility = View.GONE
        body.addView(zone, matchWidth())
        refreshModelChoices()
    }

    private fun circleButton(icon: String, description: String, click: () -> Unit): FrameLayout = FrameLayout(this).apply {
        contentDescription = description; isClickable = true; isFocusable = true
        background = Ui.ripple(if (icon == "arrow") ReferenceUi.blue else Ui.TEXT, Ui.dp(context, 24).toFloat())
        addView(IconView(context, icon, if (icon == "arrow") Color.WHITE else Ui.BACKGROUND), FrameLayout.LayoutParams(Ui.dp(context, 20), Ui.dp(context, 20), Gravity.CENTER))
        setOnClickListener { click() }
    }

    private fun buildSidebar() {
        sidebarShade = View(this).apply {
            setBackgroundColor(0x99000000.toInt()); alpha = 0f; visibility = View.GONE
            contentDescription = "关闭侧栏"
            isClickable = true
            isFocusable = true
            setOnClickListener { closeSidebar() }
        }
        mainRoot.addView(sidebarShade, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        sidebar = Ui.column(this).apply {
            setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
            setBackgroundColor(Ui.SIDEBAR)
            elevation = Ui.dp(context, 16).toFloat()
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val brand = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.iconButton(context, "menu", "关闭侧栏", 38) { closeSidebar() })
            addView(Ui.text(context, "CodexRM", 20f, bold = true))
            addView(ReferenceUi.circle(this@MainActivity, "search", "搜索对话", 44) { openSearchEntry() },
                LinearLayout.LayoutParams(Ui.dp(context, 44), Ui.dp(context, 44)).apply { marginStart = Ui.dp(context, 9) })
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(Ui.iconButton(context, "transfer", "文件传输助手", 40) { openTransfers() })
            addView(Ui.iconButton(context, "chat-plus", "新建会话", 40) { newChat(showKeyboard = true) })
        }
        sidebar.addView(brand, matchWidth(bottom = 12))
        val tabs = Ui.column(this, false).apply { isBaselineAligned = false }
        recentTab = sidebarTab("最近", false)
        workspacesTab = sidebarTab("工作区", true)
        tabs.addView(recentTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        tabs.addView(workspacesTab, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply { marginStart = Ui.dp(this@MainActivity, 6) })
        sidebar.addView(tabs, matchWidth(bottom = 4))
        workspaceActions = Ui.column(this, false).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(Ui.iconButton(context, "refresh", "刷新工作区与会话") { refreshAll() })
            addView(Ui.iconButton(context, "plus", "添加工作区") { openWorkspacePicker() })
        }
        val workspaceContent = Ui.column(this)
        workspaceContent.addView(workspaceActions, matchWidth())
        historyLabel = Ui.text(this, "", 12f, Ui.MUTED).apply {
            isAccessibilityHeading = true
            setPaddingRelative(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 5))
        }
        workspaceContent.addView(historyLabel, matchWidth())
        workspaceRows = Ui.column(this)
        workspaceContent.addView(workspaceRows, matchWidth())
        sidebarScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(workspaceContent)
        }
        sidebar.addView(sidebarScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        sidebarDeviceButton = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 10).toFloat())
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
            isClickable = true; isFocusable = true
            setOnClickListener { toggleDevices() }
            val icon = FrameLayout(context).apply {
                background = Ui.rounded(Ui.SURFACE_ALT, Ui.dp(context, 8).toFloat())
                addView(IconView(context, "monitor", Ui.MUTED), FrameLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18), Gravity.CENTER))
            }
            addView(icon, LinearLayout.LayoutParams(Ui.dp(context, 34), Ui.dp(context, 34)))
            val labels = Ui.column(context).apply { setPadding(Ui.dp(context, 10), 0, 0, 0) }
            sidebarDeviceName = Ui.text(context, "选择设备", 12f, bold = true).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            sidebarDeviceStatus = Ui.text(context, "点击选择工作设备", 12f, Ui.MUTED).apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            labels.addView(sidebarDeviceName)
            labels.addView(sidebarDeviceStatus)
            addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            sidebarDeviceChevron = IconView(context, "chevron", Ui.MUTED)
            addView(sidebarDeviceChevron, LinearLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18)))
        }
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
        sidebar.addView(deviceDropdown, matchWidth(top = 4))
        val profile = Ui.column(this, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(sidebarDeviceButton, LinearLayout.LayoutParams(0, Ui.dp(context, 56), 1f))
            addView(Ui.iconButton(context, "wrench", "工具") { showTools() })
            addView(Ui.iconButton(context, "settings", "设置") { showAppSettings() })
        }
        sidebar.addView(profile, matchWidth(top = 6))
        val width = min(Ui.dp(this, 336), (resources.displayMetrics.widthPixels * 0.88).toInt())
        sidebar.translationX = -width.toFloat()
        mainRoot.addView(sidebar, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun sidebarTab(label: String, workspaces: Boolean): TextView = Ui.text(this, label, 14f, bold = true).apply {
        gravity = Gravity.CENTER
        isFocusable = true
        setOnClickListener {
            if (showSidebarWorkspaces == workspaces) return@setOnClickListener
            showSidebarWorkspaces = workspaces
            sidebarThreadLimit = 30
            sidebarScroll.scrollTo(0, 0)
            renderWorkspaces()
        }
    }


    private fun toggleSidebar() { if (sidebarOpen) closeSidebar() else openSidebar() }
    private fun openSidebar() {
        mainRoot.stopSidebarSwipe()
        sidebarOpen = true
        sidebar.requestFocus()
        WindowCompat.getInsetsController(window, sidebar).hide(WindowInsetsCompat.Type.ime())
        renderWorkspaces()
        updateSidebarAccessibility()
        updateBackHandling()
        animateSidebar()
    }
    private fun closeSidebar(hideKeyboard: Boolean = true) {
        mainRoot.stopSidebarSwipe()
        if (hideKeyboard) WindowCompat.getInsetsController(window, sidebar).hide(WindowInsetsCompat.Type.ime())
        if (sidebar.hasFocus()) sidebar.clearFocus()
        sidebarOpen = false
        updateSidebarAccessibility()
        updateBackHandling()
        animateSidebar()
    }

    private fun updateSidebarAccessibility() {
        sidebar.importantForAccessibility = if (sidebarOpen) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        contentRoot.importantForAccessibility = if (sidebarOpen) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
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
        if (client != null) return
        if (!started || !mainVisible) return
        online = false
        client = CodexClient(this, current, object : CodexClient.Listener {
            override fun onState(state: ConnectionState, agent: JSONObject?) {
                if (device !== current) return
                val wasOnline = online
                connectionState = state
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
                    conversationPrefs.edit().putLong("seen:${current.agentId}", System.currentTimeMillis()).apply()
                    bootstrap()
                } else updateControls()
            }
            override fun onRpcEvent(message: JSONObject) { if (device?.agentId == current.agentId) handleRpcEvent(message) }
            override fun onFailure(message: String) { if (mainVisible) toast(message) }
            override fun onLocalPermissionRequired() {
                if (device !== current || !mainVisible) return
                if (!offerLocalPermission) { toast(LocalCodex.PERMISSION); return }
                offerLocalPermission = false
                if (runCatching { startActivityForResult(LocalCodex.permissionIntent(), 7361) }.isFailure) {
                    val fallback = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    if (runCatching { startActivityForResult(fallback, 7361) }.isFailure) toast(LocalCodex.PERMISSION)
                }
            }
            override fun onDirectFallback() {
                if (online && device?.agentId == current.agentId) bootstrap()
            }
            override fun onDirectStatusChanged() {
                if (device?.agentId == current.agentId) {
                    updateConnectionIndicator()
                    updateDirectFiles()
                    transfers.connectionChanged()
                }
            }
            override fun onAuthorizationExpired() {
                expiredAgentIds.add(current.agentId)
                val next = devices.firstOrNull { it.agentId !in expiredAgentIds }
                if (next != null) {
                    toast("“${current.serverName}”配对已失效，已切换到“${next.serverName}”")
                    disconnect()
                    store.selectedId = next.agentId
                    device = next
                    restoreThreadId = ""
                    showMain(reset = true)
                } else {
                    disconnect()
                    pairMessage = "此设备的配对已失效，请重新配对"
                    toast("配对已失效，请重新扫码"); showPair()
                }
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
        bootstrapListVersion = 0
        pendingOpenThread = null
        conversationResults.values.forEach { it.dismiss() }
        conversationResults.clear()
        client?.stop(); client = null
        updateDirectFiles()
        connectionDialog?.dismiss()
        invalidateSnapshots()
        devicePopup?.dismiss()
        devicesExpanded = false
        updateBackHandling()
        devices.forEach { it.online = false }
        online = false; loaded = false; bootstrapping = false; sending = false; loading = false
        connectionState = ConnectionState.CONNECTING
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
            settingsPage?.refreshActionSummaries()
            loadConversationPins()
            val version = info.string("version")
            val updated = current.confirmAgentUpdate(version)
            if (version.isNotBlank() && (version != current.appVersion || updated)) {
                current.appVersion = version
                store.save(devices)
                renderDevices()
            }
            if (updated) toast("电脑 Agent 更新成功（v$version）")
            updateDirectFiles()
            if (currentCwd.isEmpty()) currentCwd = store.selectedWorkspace(currentAgent()).ifEmpty { info.string("cwd") }
            bindNewSessionDraft()
            renderApproval()
            loadWorkspaces(currentGeneration); loadModels(); loadThreads(currentGeneration, reopenCurrent = true).also { bootstrapListVersion = it }
        }
    }

    private fun refreshAll() {
        loadConversationPins()
        if (!online) { toast("设备尚未连接"); return }
        if (!loaded) { bootstrap(); return }
        loadWorkspaces(generation); loadModels(refresh = true); loadThreads(generation)
        if (currentThreadId.isNotEmpty()) openThread(currentThreadId, silent = true)
    }

    private fun loadWorkspaces(currentGeneration: Int) {
        rpc("codexrm/workspace/list") { result, _ ->
            if (!active(currentGeneration)) return@rpc
            if (result != null) {
                val array = result.arrayAt("workspaces")
                workspaces = (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { Workspace(it.string("name"), it.string("path")) }
                }
                val saved = store.selectedWorkspace(currentAgent())
                val inThread = currentThreadId.isNotEmpty() || openingThreadId.isNotEmpty()
                if (!inThread && (currentCwd.isEmpty() || workspaces.none { pathKey(it.path) == pathKey(currentCwd) })) {
                    currentCwd = saved.takeIf { saved.isNotEmpty() } ?: workspaces.firstOrNull()?.path ?: info.string("cwd")
                    bindNewSessionDraft()
                }
                renderWorkspaces(); updateControls()
            }
        }
    }

    private fun loadThreads(
        currentGeneration: Int = generation,
        cursor: String? = null,
        accumulated: List<ThreadSummary> = emptyList(),
        seen: Set<String> = emptySet(),
        reopenCurrent: Boolean = false,
        listVersion: Int = ++threadListVersion,
    ): Int {
        if (cursor == null) {
            threadListLoading = true
            threadListError = ""
        }
        // A generic event refresh must never supersede the bootstrap's only completion path.
        if (!reopenCurrent && bootstrapListVersion != 0 && listVersion <= bootstrapListVersion) {
            return ++threadListVersion
        }
        val params = JSONObject().put("limit", 100).put("sortKey", "updated_at").put("sortDirection", "desc")
            .put("modelProviders", JSONArray()).put("archived", false)
        cursor?.let { params.put("cursor", it) }
        rpc("thread/list", params) { result, error ->
            val authoritative = reopenCurrent || listVersion == bootstrapListVersion || bootstrapListVersion == 0
            if (!active(currentGeneration) || !authoritative || (!reopenCurrent && listVersion != threadListVersion)) return@rpc
            if (error != null || result == null) {
                threadListLoading = false
                threadListError = "会话加载失败：${error?.message ?: "响应无效"}"
                if (reopenCurrent) {
                    bootstrapListVersion = 0
                    bootstrapping = false
                    bootstrapError = "无法读取会话：${error?.message ?: "响应无效"}"
                }
                renderWorkspaces(); updateControls(); toast(threadListError); return@rpc
            }
            val array = result.arrayAt("data")
            val page = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(ProtocolFormatter::thread)?.takeIf { it.id.isNotEmpty() }
            }
            val staged = threadList.mergePage(accumulated, page)
            val nextCursor = result.string("nextCursor").takeIf { !result.isNull("nextCursor") && it.isNotEmpty() }
            if (nextCursor != null && !seen.contains(nextCursor) && seen.size < 20) {
                threadCursor = nextCursor
                loadThreads(currentGeneration, nextCursor, staged, seen + nextCursor, reopenCurrent, listVersion)
                return@rpc
            }
            // Repeated cursor/page limit is not a complete success: keep the last
            // committed list and expose a retryable error instead of truncating it.
            if (nextCursor != null) {
                threadListLoading = false
                threadListError = "会话列表未完整加载，请重试"
                if (reopenCurrent) {
                    bootstrapListVersion = 0
                    bootstrapping = false
                    bootstrapError = threadListError
                }
                renderWorkspaces(); updateControls(); toast(threadListError)
                return@rpc
            }
            threadList.commit(staged)
            threadListError = ""
            threadCursor = null
            threadListLoading = false; loaded = true
            if (reopenCurrent) { bootstrapListVersion = 0; bootstrapping = false; bootstrapError = "" }
            renderWorkspaces(); updateControls()
            bindNewSessionDraft()
            if (reopenCurrent) {
                val reopen = restoreThreadId.ifEmpty { currentThreadId }
                restoreThreadId = ""
                if (pendingOpenThread == null && reopen.isNotEmpty() && !loading && !sending) openThread(reopen, silent = true)
            }
            drainPendingOpen()
        }
        return listVersion
    }

    private fun loadModels(refresh: Boolean = false) {
        if (!online) return
        val requestGeneration = generation
        val sequence = ++modelLoadSequence
        if (info.optBoolean("providerModels")) {
            rpc("codexrm/model/list") { result, error ->
                if (requestGeneration != generation || sequence != modelLoadSequence) return@rpc
                if (result != null) parseProviderModels(result, showWarnings = refresh || !modelCatalogLoaded)
                if (refresh) toast(if (error == null) "模型列表已刷新" else error.message)
            }
        } else rpc("model/list", JSONObject().put("limit", 100)) { result, _ ->
            if (result == null || requestGeneration != generation || sequence != modelLoadSequence) return@rpc
            val provider = JSONObject().put("name", "当前 Codex 配置").put("models", result.arrayAt("data"))
            parseProviderModels(JSONObject().put("providers", JSONArray().put(provider)), showWarnings = false)
        }
    }

    private fun parseProviderModels(result: JSONObject, showWarnings: Boolean) {
        modelCatalogLoaded = true
        modelCatalog = ModelCatalog(result, localOnly = currentAgent() == LocalCodex.AGENT)
        refreshModelChoices()
        updateSendState()
        val warnings = result.arrayAt("warnings")
        if (showWarnings && warnings.length() > 0) toast((0 until warnings.length()).joinToString("；") { warnings.optString(it) })
    }

    private fun refreshModelChoices() {
        if (!mainBuilt) return
        val thread = currentThread
        val threadProvider = thread?.takeIf { it.string("model").isNotEmpty() }?.let(modelCatalog::threadProviderId)
        if (applyExplicitChoice(threadProvider)) return
        if (thread != null && thread.string("model").isNotEmpty()) {
            showModelOptions(modelCatalog.threadProviderId(thread), thread.string("model"), selectedEffort())
        } else {
            val (provider, model, effort) = store.modelPreference(currentAgent(), pathKey(currentCwd))
            showModelOptions(provider, model, effort)
        }
    }

    private fun showModelOptions(provider: String, model: String, effort: String) {
        models = modelCatalog.options(provider)
        selectedModelOption = modelCatalog.selectedOption(models, provider, model)
        updateModelButton()
        updateEfforts(preferred = effort)
    }

    private fun updateModelButton() {
        if (!mainBuilt || !::modelButton.isInitialized) return
        val selected = selectedModelOption
        val model = selected?.displayName ?: "选择模型"
        val intensity = InteractionCopy.effortLabel(effortSelection)
        modelButton.text = InteractionCopy.composerModelLabel(selected?.displayName)
        configModelValue?.text = "$model  ⌄"
        configEffortValue?.text = "$intensity  ⌄"
        modelButton.contentDescription = selected?.let { "选择模型，当前为${it.providerName} ${it.displayName}" } ?: "选择模型"
    }

    private fun showConfiguration() {
        if (configSheet?.isShowing == true) return
        val sheet = PredictiveBackDialog(this)
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        sheet.setTitle("配置")
        val panel = Ui.column(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                val r = Ui.dp(context, 28).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setColor(ReferenceUi.card)
            }
            setPadding(Ui.dp(context, 25), Ui.dp(context, 9), Ui.dp(context, 25), Ui.dp(context, 24))
        }
        panel.addView(View(this).apply { background = Ui.rounded(ReferenceUi.raised, Ui.dp(context, 3).toFloat()) },
            LinearLayout.LayoutParams(Ui.dp(this, 39), Ui.dp(this, 4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        panel.addView(Ui.text(this, "配置", 18f).apply { gravity = Gravity.CENTER; isAccessibilityHeading = true }, matchWidth(top = 12, bottom = 24))
        val card = Ui.column(this).apply {
            background = Ui.rounded(ReferenceUi.card, Ui.dp(context, 29).toFloat(), ReferenceUi.border)
            setPadding(Ui.dp(context, 17), 0, Ui.dp(context, 17), 0)
        }
        fun row(title: String, value: String, action: () -> Unit): TextView {
            if (card.childCount > 0) card.addView(View(this).apply { setBackgroundColor(ReferenceUi.border) }, LinearLayout.LayoutParams(-1, Ui.dp(this, 1)))
            val line = Ui.column(this, false).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dp(context, 59)
                background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 16).toFloat()); setOnClickListener { action() }
            }
            line.addView(Ui.text(this, title, 17f), LinearLayout.LayoutParams(0, -2, 1f))
            val suffix = Ui.text(this, value, 16f, Ui.MUTED).apply { maxLines = 1; maxWidth = Ui.dp(context, 180); ellipsize = android.text.TextUtils.TruncateAt.END }
            line.addView(suffix)
            card.addView(line, matchWidth())
            return suffix
        }
        configModelValue = row("模型", "", ::showModelPicker)
        configEffortValue = row("强度", "") {
            val blocked = InteractionCopy.modelBlockReason(online, readOnly, running, sending, loading)
            if (blocked == null) showEffortChoices() else toast(blocked)
        }
        row("工作区", folderName(currentCwd).ifBlank { "选择" } + "  ⌄") { sheet.dismiss(); showWorkspaceChoices() }
        panel.addView(card, matchWidth())
        if (info.optBoolean("computerFiles") || info.optBoolean("directFiles")) {
            panel.addView(Ui.button(this, "电脑文件管理") { sheet.dismiss(); openWorkspaceFiles(currentCwd, currentThreadId, startAtComputer = true) }, matchWidth(top = 8))
        }
        panel.addView(Ui.text(this, "完成", 18f, if (Ui.dark) Color.BLACK else Color.WHITE).apply {
            gravity = Gravity.CENTER; minHeight = Ui.dp(context, 49)
            background = Ui.ripple(if (Ui.dark) Color.WHITE else Color.BLACK, Ui.dp(context, 25).toFloat())
            setOnClickListener { sheet.dismiss() }
        }, matchWidth(top = 26))
        val container = ScrollView(this).apply { addView(panel) }
        Ui.applyWindowInsets(container)
        sheet.setBackContent(container, BackMotion.SHEET)
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.BOTTOM)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.45f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        sheet.setOnDismissListener { configSheet = null; configModelValue = null; configEffortValue = null }
        configSheet = sheet
        updateModelButton()
        sheet.show()
        sheet.window?.setLayout(-1, -2)
    }

    private fun showModelPicker() {
        InteractionCopy.modelBlockReason(online, readOnly, running, sending, loading)?.let {
            toast(it)
            return
        }
        selectionDialog?.dismiss()
        if (currentAgent() == LocalCodex.AGENT && models.isEmpty()) {
            if (!modelCatalogLoaded) {
                toast("正在获取本机模型，请稍后重试")
                loadModels()
            } else {
                configSheet?.dismiss()
                showAppSettings(openProviders = true)
            }
            return
        }
        selectionDialog = showMiuixSelectionDialog(this, "选择模型") { close ->
            MiuixModelChoices(models, selectedModelOption, close) { row ->
                close()
                row.option?.let(::chooseModel)
            }
        }
    }

    private fun chooseModel(model: ModelOption) {
        val thread = currentThread
        if (thread != null && model.providerId != modelCatalog.threadProviderId(thread)) {
            val sameModel = model.model == thread.string("model")
            val canSwitch = info.optBoolean("threadModelProviderSwitch") ||
                (sameModel && info.optBoolean("sameModelProviderSwitch"))
            val endpoint = if (canSwitch) modelCatalog.switchEndpoint(thread, model) else null
            if (endpoint == null) {
                toast("当前 Agent 或供应商不支持在此会话中切换到该模型")
                return
            }
            val currentOpen = openGeneration
            val effort = if (sameModel) selectedEffort() else model.defaultEffort
            loading = true; updateControls()
            val threadId = currentThreadId
            val params = JSONObject().put("threadId", threadId).put("providerId", endpoint).put("model", model.model)
            rpc("codexrm/thread/switchProvider", params) { result, error ->
                if (currentOpen != openGeneration || currentThreadId != threadId) return@rpc
                loading = false
                if (result != null && error == null) {
                    val provider = result.string("providerId")
                    val switchedModel = result.string("model")
                    currentThread?.put("codexrmProvider", provider)?.put("model", switchedModel)?.remove("codexrmProviderUnavailable")
                    renderQueue()
                    showModelOptions(provider, switchedModel, effort)
                    rememberExplicitChoice()
                    saveModelPreference()
                    toast("已在当前会话切换模型")
                } else toast(error?.message ?: "无法切换模型")
                updateControls()
            }
            return
        }
        selectedModelOption = model
        updateModelButton()
        updateEfforts(preferred = model.defaultEffort)
        rememberExplicitChoice()
        saveModelPreference()
        updateSendState()
    }

    private fun updateEfforts(preferred: String = "") {
        val model = selectedModel()
        val wanted = preferred.ifEmpty { model?.defaultEffort.orEmpty() }
        effortSelection = wanted.takeIf { it in model?.efforts.orEmpty() }.orEmpty()
        updateEffortButton()
    }

    private fun updateEffortButton() {
        updateModelButton()
    }

    private fun showEffortChoices() {
        val model = selectedModel()
        val values = listOf("") + model?.efforts.orEmpty()
        val pickerGeneration = generation
        MiuixDialog.Builder(this).setTitle("选择思考强度")
            .setSingleChoiceItems(values.map(InteractionCopy::effortLabel).toTypedArray(), values.indexOf(effortSelection)) { dialog, index ->
                dialog.dismiss()
                if (active(pickerGeneration) && selectedModel() == model && !running) {
                    effortSelection = values[index]
                    updateEffortButton()
                    rememberExplicitChoice()
                    saveModelPreference()
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun saveModelPreference() {
        val model = selectedModel()
        store.saveModelPreference(currentAgent(), pathKey(currentCwd), model?.providerId.orEmpty(), model?.model.orEmpty(), selectedEffort())
    }

    private fun rememberExplicitChoice() {
        val model = selectedModel() ?: return
        explicitModelChoice = ModelChoice(currentThreadId, model.providerId, model.model, selectedEffort())
    }

    private fun applyExplicitChoice(threadProviderId: String?): Boolean {
        val retained = ModelSelection.retain(modelCatalog, explicitModelChoice, currentThreadId, threadProviderId) ?: return false
        explicitModelChoice = explicitModelChoice?.copy(providerId = retained.first, model = retained.second)
        showModelOptions(retained.first, retained.second, retained.third)
        return true
    }

    private fun restoreModelChoice(state: Bundle?) {
        val model = state?.getString("modelChoiceModel").orEmpty()
        if (model.isEmpty()) return
        explicitModelChoice = ModelChoice(
            state?.getString("modelChoiceThread").orEmpty(),
            state?.getString("modelChoiceProvider").orEmpty(),
            model,
            state?.getString("modelChoiceEffort").orEmpty(),
        )
        if (mainBuilt) refreshModelChoices()
    }

    private fun showWorkspaceChoices() {
        if (!online || sending || loading) return
        val currentKey = pathKey(currentCwd)
        val paths = (sequenceOf(currentCwd) + workspaces.asSequence().map { it.path } +
            threads.asSequence().sortedByDescending { it.lastActivityAt }.map { it.cwd })
            .filter { it.isNotBlank() }.distinctBy(::pathKey).toList()
        val pickerGeneration = generation
        styledDialog(MiuixDialog.Builder(this)
            .setTitle("选择工作目录")
            .setSingleChoiceItems(paths.toTypedArray(), paths.indexOfFirst { pathKey(it) == currentKey }) { dialog, index ->
                dialog.dismiss()
                val path = paths[index]
                if (active(pickerGeneration) && pathKey(path) != pathKey(currentCwd)) selectWorkspace(path)
            }
            .setNeutralButton("浏览其他目录") { _, _ ->
                if (active(pickerGeneration)) openWorkspacePicker()
            }
            .setNegativeButton("取消", null))
    }

    private fun selectedModel(): ModelOption? = selectedModelOption
    private fun selectedEffort(): String = effortSelection.takeIf { it in selectedModel()?.efforts.orEmpty() }.orEmpty()

    private fun resetConversation() {
        generation++; openGeneration++; invalidateSnapshots()
        currentThread = null; currentThreadId = ""; openingThreadId = ""; currentCwd = ""; info = JSONObject()
        modelCatalog = ModelCatalog(); selectedModelOption = null
        explicitModelChoice = null
        modelCatalogLoaded = false
        threadList.clear()
        pinRequestVersion++
        pinnedThreads = emptyList()
        pinnedThreadIds = emptySet()
        conversationJump = null
        archivedThreadId = ""
        pendingBranchDraft = null
        threadListError = ""
        workspaces = emptyList(); models = emptyList(); messageQueues.clear(); requests.clear(); requestScopes.clear()
        outboxOwnerAgentId = currentAgent()
        outbox = OutboxState(store.pendingSends(outboxOwnerAgentId)); activePendingSendId = ""
        persistOutbox()
        bootstrapping = false; bootstrapError = ""; approvalRecoveryMessage = ""
        running = false; readOnly = false; loading = false; historyCursor = null
        collapsedPaths = store.collapsed(currentAgent())
        rebindComposer(ComposerBinding(currentAgent(), "", ""))
        clearTranscript()
        if (mainBuilt) { refreshModelChoices(); renderApproval(); renderQueue(); updateControls() }
    }

    private fun newChat(showKeyboard: Boolean = false) {
        if (sending || loading) { toast("请等待当前请求完成"); return }
        if (showKeyboard) { closeTransfers(); restoreThreadId = "" }
        saveDraft(); openGeneration++; invalidateSnapshots()
        currentThread = null; currentThreadId = ""; openingThreadId = ""; running = false; readOnly = false
        archivedThreadId = ""; conversationJump = null; pendingBranchDraft = null
        activeTurnId = ""; historyCursor = null; historyOlder = false; messageQueues.remove("")
        val restore = rebindComposer(ComposerBinding(currentAgent(), "", currentCwd))
        clearTranscript(); restoreDraft(restore); renderApproval(); renderQueue(); refreshModelChoices(); updateControls()
        if (showKeyboard) {
            if (sidebarOpen) closeSidebar(hideKeyboard = false)
            val input = composer
            val session = openGeneration
            if (input.requestFocus()) input.post {
                if (mainVisible && !sidebarOpen && composer === input && session == openGeneration && input.isFocused) {
                    WindowCompat.getInsetsController(window, input).show(WindowInsetsCompat.Type.ime())
                }
            }
        }
    }

    private fun clearTranscript() {
        replySpeech.stop()
        if (mainBuilt) messageList.restoreReadingPosition(null)
        latestTurn = null
        latestDiff = ""
        diffTurn = ""
        cachedAt = 0
        reasoningStarted.clear()
        reasoningStatus.clear()
        items.clear(); deltaBuffers.clear(); dirtyDeltaIds.clear(); dirtyMessageIds.clear(); userMessages.clear(); historyMessageIds.clear()
        if (mainBuilt) adapter.clear()
    }

    private fun selectWorkspace(path: String, showKeyboard: Boolean = false) {
        if (sending || loading) return
        store.selectWorkspace(currentAgent(), path); currentCwd = path; collapsedPaths.remove(pathKey(path)); store.saveCollapsed(currentAgent(), collapsedPaths)
        restoreThreadId = ""
        newChat(showKeyboard); refreshModelChoices(); renderWorkspaces()
    }

    private fun openThread(
        id: String,
        silent: Boolean = false,
        archived: Boolean = false,
        onOpened: ((readOnly: Boolean) -> Unit)? = null,
    ) {
        if (id.isEmpty() || id in deletingThreads) return
        if (loading || sending || bootstrapping) {
            pendingOpenThread = DeferredOpen(currentAgent(), id, silent, archived)
            return
        }
        pendingOpenThread = null
        if (!silent) closeTransfers()
        if (!online) {
            saveDraft()
            if (!openCachedThread(id)) toast("电脑离线，此会话还没有本机记录")
            else if (!silent) closeSidebar()
            return
        }
        saveDraft(); val currentOpen = ++openGeneration
        loading = true; openingThreadId = id; invalidateSnapshots()
        if (conversationJump?.thread != id) conversationJump = null
        pendingBranchDraft = pendingBranchDraft?.takeIf { it.thread == id }
        if (!silent) closeSidebar(); updateControls()
        if (archived) {
            rpc("codexrm/thread/snapshot", JSONObject().put("threadId", id)) { result, error ->
                if (!openActive(id, currentOpen)) return@rpc
                if (result == null) failOpenThread(currentOpen, error?.message)
                else finishOpenThread(id, currentOpen, result.put("codexrmArchived", true), true)
            }
            return
        }
        rpc("thread/resume", JSONObject().put("threadId", id)) { result, error ->
            if (!openActive(id, currentOpen)) return@rpc
            if (result != null) { finishOpenThread(id, currentOpen, result, false, onOpened); return@rpc }
            val message = error?.message.orEmpty()
            when {
                desktopWriterConflict(error) -> {
                    val method = if (info.optBoolean("threadSnapshots")) "codexrm/thread/snapshot" else "thread/read"
                    val params = JSONObject().put("threadId", id); if (method == "thread/read") params.put("includeTurns", true)
                    rpc(method, params) fallback@{ fallback, fallbackError ->
                        if (!openActive(id, currentOpen)) return@fallback
                        if (fallback != null) finishOpenThread(id, currentOpen, fallback, true, onOpened)
                        else failOpenThread(currentOpen, fallbackError?.message)
                    }
                }
                message.contains("paginat", true) || message.contains("excludeTurns", true) || message.contains("history mode", true) ->
                    rpc("thread/resume", JSONObject().put("threadId", id).put("excludeTurns", true)) { fallback, fallbackError ->
                        if (fallback != null) finishOpenThread(id, currentOpen, fallback, false, onOpened)
                        else failOpenThread(currentOpen, fallbackError?.message)
                    }
                else -> failOpenThread(currentOpen, message)
            }
        }
    }

    private fun drainPendingOpen() {
        val pending = pendingOpenThread ?: return
        if (loading || sending || bootstrapping) return
        pendingOpenThread = null
        if (pending.agentId == currentAgent()) openThread(pending.threadId, pending.silent, pending.archived)
    }

    private fun finishOpenThread(
        id: String,
        currentOpen: Int,
        result: JSONObject,
        isReadOnly: Boolean,
        onOpened: ((readOnly: Boolean) -> Unit)? = null,
    ) {
        if (!openActive(id, currentOpen)) return
        val thread = result.optJSONObject("thread"); if (thread == null) { failOpenThread(currentOpen, "会话响应无效"); return }
        fun render() {
            renderOpenThread(id, result, thread, isReadOnly)
            onOpened?.invoke(readOnly)
        }
        val hasSnapshotHistory = (isReadOnly && info.optBoolean("threadSnapshots")) ||
            (thread.optBoolean("codexrmProviderUnavailable") && result.has("nextCursor"))
        if (thread.string("historyMode") == "paginated" && !hasSnapshotHistory) {
            val params = JSONObject().put("threadId", id).put("limit", 30).put("sortDirection", "desc").put("itemsView", "full")
            rpc("thread/turns/list", params) { page, error ->
                if (!openActive(id, currentOpen)) return@rpc
                if (error != null || page == null) { failOpenThread(currentOpen, error?.message); return@rpc }
                val data = page.arrayAt("data"); val turns = JSONArray()
                for (index in data.length() - 1 downTo 0) turns.put(data.opt(index))
                thread.put("turns", turns); result.put("nextCursor", page.opt("nextCursor") ?: JSONObject.NULL)
                render()
            }
        } else render()
    }

    private fun renderOpenThread(id: String, result: JSONObject, thread: JSONObject, isReadOnly: Boolean) {
        val sameThread = id == currentThreadId
        val readingPosition = if (sameThread) messageList.readingPosition() else null
        val nextCursor = result.string("nextCursor").takeIf { !result.isNull("nextCursor") && it.isNotEmpty() }
        if (sameThread && nextCursor != null) retainOverlappingHistory(thread.arrayAt("turns"), readOnly)
        val retainedHistory = if (sameThread && nextCursor != null && historyOlder) {
            adapter.snapshot().filter { it.id in historyMessageIds }
        } else emptyList()
        val previousCursor = historyCursor
        saveDraft()
        currentThread = thread; currentThreadId = id; openingThreadId = ""; currentCwd = thread.string("cwd").ifEmpty { currentCwd }
        if (composerBinding.agentId != currentAgent() || composerBinding.threadId != id) {
            rebindComposer(ComposerBinding(currentAgent(), id, currentCwd))
        } else {
            val binding = composerBinding.copy(cwd = currentCwd)
            composerBinding = binding
            composerRestoreToken = composerRestoreToken.copy(binding = binding)
        }
        store.selectWorkspace(currentAgent(), currentCwd)
        archivedThreadId = if (result.optBoolean("codexrmArchived")) id else ""
        readOnly = isReadOnly; running = false; activeTurnId = ""; historyOlder = false
        historyCursor = nextCursor
        clearTranscript()
        val turns = thread.arrayAt("turns")
        for (index in 0 until turns.length()) renderTurn(turns.optJSONObject(index) ?: continue)
        if (readOnly) adapter.add(readOnlyMessage())
        if (retainedHistory.isNotEmpty()) {
            val merged = TranscriptState.prepend(adapter.snapshot(), retainedHistory)
            if (merged.historyIds.isNotEmpty()) {
                adapter.replace(merged.messages)
                historyMessageIds += merged.historyIds
                historyOlder = true
                historyCursor = previousCursor
            }
        }
        selectThreadModel(result, thread); loading = false; restoreDraft(composerRestoreToken); renderQueue(); loadMessageQueue(id)
        acceptShareForThread(id)
        if (archivedThreadId != id) {
            cacheConversation(thread)
            conversationPrefs.edit().putString("last:${currentAgent()}", id).apply()
        }
        pendingBranchDraft?.takeIf { it.thread == id }?.let(::applyBranchDraft)
        renderWorkspaces(); renderApproval(); updateControls()
        if (readingPosition != null && (readingPosition.anchor == null || adapter.positionOf(readingPosition.anchor.id) >= 0)) {
            messageList.restoreReadingPosition(readingPosition)
        } else {
            messageList.setSelection(adapter.count - 1)
        }
        if (archivedThreadId != id && readOnly) scheduleSnapshot()
        locateConversationJump()
    }

    private fun renderTurn(turn: JSONObject, collector: MutableList<ChatMessage>? = null) {
        if (collector != null) {
            collector += TurnRenderer.messages(turn, readOnly).onEach { it.turnId = turn.string("id") }
            return
        }
        latestTurn = TurnProgress.from(turn)
        userMessages.observeTurn(turn.string("id"))
        if (!readOnly && turn.string("status") == "inProgress") { running = true; activeTurnId = turn.string("id"); startedAt = System.currentTimeMillis() }
        val turnItems = turn.arrayAt("items")
        for (itemIndex in 0 until turnItems.length()) turnItems.optJSONObject(itemIndex)?.let {
            if (it.string("type") == "diff") latestDiff = it.string("diff")
            upsertItem(turn.string("id"), it, immediate = true)
        }
        TurnRenderer.terminalMessages(turn, readOnly).forEach(adapter::add)
    }

    private fun failOpenThread(currentOpen: Int, message: String?) {
        if (currentOpen != openGeneration) return
        shareForThread = null
        conversationJump = null
        pendingBranchDraft = null
        loading = false; openingThreadId = ""
        if (readOnly && archivedThreadId.isEmpty()) scheduleSnapshot()
        updateControls(); toast(message.ifNullOrEmpty("无法打开会话"))
    }

    private fun selectThreadModel(result: JSONObject, thread: JSONObject) {
        val model = result.string("model").ifEmpty { thread.string("model") }
        if (model.isEmpty()) return
        val runtimeId = result.string("modelProvider").ifEmpty { thread.string("modelProvider") }
        thread.put("model", model)
        if (runtimeId.isNotEmpty()) thread.put("modelProvider", runtimeId)
        val provider = modelCatalog.threadProviderId(thread)
        if (applyExplicitChoice(provider)) return
        showModelOptions(provider, model, result.string("reasoningEffort"))
    }

    private fun loadOlderTurns(onLoaded: (() -> Unit)? = null) {
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
            if (error != null || page == null || id != currentThreadId || cursor != historyCursor) {
                conversationJump = null
                toast(error?.message ?: "无法读取更早的消息")
                updateControls()
                return@rpc
            }
            val data = page.arrayAt("data"); val older = ArrayList<ChatMessage>()
            for (index in data.length() - 1 downTo 0) {
                renderTurn(data.optJSONObject(index) ?: continue, older)
            }
            prependHistory(older)
            historyCursor = if (page.isNull("nextCursor")) null else page.string("nextCursor").takeIf { it.isNotEmpty() }
            historyOlder = true; updateControls()
            onLoaded?.invoke()
        }
    }

    private fun prependHistory(older: List<ChatMessage>) {
        val merged = TranscriptState.prepend(adapter.snapshot(), older)
        val readingPosition = messageList.readingPosition()
        adapter.prepend(merged.messages.subList(0, merged.historyIds.size))
        historyMessageIds += merged.historyIds
        messageList.restoreReadingPosition(readingPosition)
    }

    private fun invalidateSnapshots() {
        snapshotRequestVersion++
        snapshotRunning = false
        handler.removeCallbacks(snapshotTicker)
        detachedThreads.reset()
    }

    private fun scheduleSnapshot() {
        handler.removeCallbacks(snapshotTicker)
        val polling = archivedThreadId != currentThreadId && (readOnly || detachedThreads.isDetached(currentThreadId))
        if (polling && currentThreadId.isNotEmpty() && online) handler.postDelayed(snapshotTicker, 3000)
    }

    private fun refreshSnapshot() {
        val detached = detachedThreads.isDetached(currentThreadId)
        if ((!readOnly && !detached) || snapshotRunning || !online || currentThreadId.isEmpty()) return
        snapshotRunning = true
        val requestVersion = ++snapshotRequestVersion
        val id = currentThreadId; val currentGeneration = generation
        rpc("codexrm/thread/snapshot", JSONObject().put("threadId", id)) { result, _ ->
            if (requestVersion != snapshotRequestVersion) return@rpc
            snapshotRunning = false
            val polling = readOnly || detachedThreads.isDetached(id)
            if (result != null && active(currentGeneration) && id == currentThreadId && polling) {
                val thread = result.optJSONObject("thread")
                if (thread != null) {
                    val cursor = result.optString("nextCursor").takeIf { !result.isNull("nextCursor") && it.isNotEmpty() }
                    replaceLatestSnapshot(thread, cursor)
                }
            }
            scheduleSnapshot()
        }
    }

    private fun retainOverlappingHistory(turns: JSONArray, wasReadOnly: Boolean) {
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
                TurnRenderer.messages(turn, wasReadOnly).mapTo(historyMessageIds) { it.id }
            }
            historyOlder = true
        }
    }

    private fun readOnlyMessage(): ChatMessage {
        val text = if (archivedThreadId == currentThreadId) "此会话已归档，可在侧栏底部的「工具」中恢复。"
            else "此会话被其他客户端占用，自动同步已保存的消息。结束原会话后可重新打开继续。"
        return ChatMessage("readonly", MessageKind.STATUS, "", text)
    }

    private fun replaceLatestSnapshot(thread: JSONObject, nextCursor: String?) {
        val wasReadOnly = readOnly
        val wasRunning = running
        val previousTurnId = activeTurnId
        val couldContinue = latestTurn?.canContinue == true
        val previousName = currentThread?.string("name").orEmpty()
        if (thread.objectAt("status").string("type") == "active") readOnly = true
        val turns = thread.arrayAt("turns")
        retainOverlappingHistory(turns, wasReadOnly)
        if (!historyOlder) historyCursor = nextCursor
        currentThread?.let { current ->
            for (key in listOf("model", "codexrmProvider", "codexrmProviderUnavailable")) {
                if (!thread.has(key) && current.has(key)) thread.put(key, current.opt(key))
            }
        }
        currentThread = thread
        latestTurn = turns.optJSONObject(turns.length() - 1)?.let(TurnProgress::from)
        running = false; activeTurnId = ""
        val latest = ArrayList<ChatMessage>()
        for (index in 0 until turns.length()) {
            val turn = turns.optJSONObject(index) ?: continue
            if (!readOnly && turn.string("status") == "inProgress") {
                running = true
                activeTurnId = turn.string("id")
            }
            renderTurn(turn, latest)
        }
        if (running && (!wasRunning || previousTurnId != activeTurnId)) startedAt = System.currentTimeMillis()
        if (readOnly) latest += readOnlyMessage()
        val merged = TranscriptState.latest(adapter.snapshot(), historyMessageIds, latest, historyOlder)

        items.clear(); deltaBuffers.clear(); dirtyDeltaIds.clear(); dirtyMessageIds.clear(); userMessages.clear()
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
        adapter.replaceSnapshot(merged.messages, messageList)
        if (wasReadOnly != readOnly || wasRunning != running || couldContinue != (latestTurn?.canContinue == true) ||
            previousName != thread.string("name")
        ) updateControls()
    }

    private fun attachmentDraftKey(): String = composerBinding.attachmentKey(::pathKey)

    private fun showAttachmentSheet() {
        if (attachmentSheet != null || !attachButton.isEnabled) return
        if (!online || !loaded) { toast("设备尚未连接或正在同步"); return }
        if (!info.has("attachments")) { toast("请更新本地 Agent 以支持文件和图片"); return }

        val sheet = PredictiveBackDialog(this)
        sheet.requestWindowFeature(Window.FEATURE_NO_TITLE)
        sheet.setTitle("添加附件")
        val panel = Ui.column(this).apply {
            accessibilityPaneTitle = "添加附件"
            background = Ui.rounded(ReferenceUi.card, Ui.dp(context, 30).toFloat(), ReferenceUi.border)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 16))
        }
        fun addChoice(icon: String, label: String, action: () -> Unit) {
            val choice = Ui.column(this, false).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(context, 65)
                background = Ui.ripple(Color.TRANSPARENT, Ui.dp(context, 22).toFloat())
                isFocusable = true
                contentDescription = label
                val bubble = FrameLayout(context).apply {
                    background = Ui.rounded(ReferenceUi.raised, Ui.dp(context, 24).toFloat())
                    addView(IconView(context, icon, Ui.TEXT), FrameLayout.LayoutParams(Ui.dp(context, 23), Ui.dp(context, 23), Gravity.CENTER))
                }
                addView(bubble, LinearLayout.LayoutParams(Ui.dp(context, 47), Ui.dp(context, 47)))
                addView(Ui.text(context, label, 17f).apply {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    setPaddingRelative(Ui.dp(context, 14), 0, 0, 0)
                })
                setOnClickListener { sheet.dismiss(); action() }
            }
            panel.addView(choice, matchWidth())
        }
        addChoice("camera", "拍照", ::startAttachmentCamera)
        addChoice("image", "照片") { pickAttachments(imagesOnly = true) }
        addChoice("paperclip", "文件") { pickAttachments(imagesOnly = false) }

        val container = ScrollView(this).apply {
            val margin = Ui.dp(context, 12)
            setPadding(margin, 0, margin, margin)
            addView(panel)
        }
        Ui.applyWindowInsets(container)
        sheet.setBackContent(container, BackMotion.SHEET)
        sheet.setCanceledOnTouchOutside(true)
        sheet.setOnDismissListener { if (attachmentSheet === sheet) attachmentSheet = null }
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM or Gravity.START)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.24f)
            addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        attachmentSheet = sheet
        sheet.show()
        sheet.window?.setLayout(Ui.dp(this, minOf(320, (resources.configuration.screenWidthDp * .75).toInt())), ViewGroup.LayoutParams.WRAP_CONTENT)
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 907) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toast("任务通知已开启")
            else TaskMonitor.enable(this, currentAgent(), false)
            settingsPage?.refreshActionSummaries()
            return
        }
        if (workspaceFiles.onRequestPermissionsResult(requestCode, grantResults)) return
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
        styledDialog(MiuixDialog.Builder(this)
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
        stageAttachments(key, uris) { photo?.delete() }
    }

    private fun stageAttachments(key: String, uris: List<Uri>, cleanup: () -> Unit = {}) {
        preparingAttachments = true
        renderAttachments()
        updateControls()
        attachmentStore.stage(key, uris) { error ->
            cleanup()
            if (isDestroyed) return@stage
            preparingAttachments = false
            renderAttachments()
            updateControls()
            toast(error)
        }
    }

    private fun editAttachmentMarkup(file: JSONObject, local: java.io.File) {
        if (openingMarkup || markupDialog != null || sending || preparingAttachments) return
        val key = attachmentDraftKey()
        val composerSession = openGeneration
        openingMarkup = true
        Thread({
            val result = runCatching { decodeMarkupBitmap(local) }
            handler.post {
                openingMarkup = false
                if (isDestroyed || !mainVisible || !resumed || sending || loading || preparingAttachments || composerSession != openGeneration ||
                    key != attachmentDraftKey() || attachmentFiles.none { it.string("draftId") == file.string("draftId") }) {
                    result.getOrNull()?.recycle()
                    return@post
                }
                result.onSuccess { bitmap ->
                    markupDialog = showAttachmentMarkup(this, bitmap, "标注图片") { annotated ->
                        preparingAttachments = true
                        renderAttachments()
                        updateControls()
                        attachmentStore.replaceWithMarkup(key, file, annotated) { error ->
                            if (!isDestroyed) {
                                preparingAttachments = false
                                renderAttachments()
                                updateControls()
                                toast(error ?: "标注已保存到草稿")
                            }
                        }
                    }.apply {
                        setOnDismissListener { markupDialog = null }
                    }
                }.onFailure { toast(if (it is IllegalArgumentException) it.message else "无法读取图片") }
            }
        }, "attachment-markup").start()
    }

    private fun renderAttachments() {
        if (!mainBuilt) return
        attachmentFiles = attachmentStore.load(attachmentDraftKey())
        attachmentScroll.visibility = if (attachmentFiles.isEmpty()) View.GONE else View.VISIBLE
        attachmentStatus.visibility = if (preparingAttachments || attachmentFiles.isNotEmpty()) View.VISIBLE else View.GONE
        attachmentStatus.text = if (preparingAttachments) "正在读取附件…" else "附件 · ${attachmentFiles.size}"
        attachmentRows.removeAllViews()
        attachmentProgress.clear()
        for (file in attachmentFiles) {
            val draftId = file.string("draftId")
            var imageProgress: TextView? = null
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
                    if (bitmap != null) {
                        val thumbnail = FrameLayout(this)
                        if (file.string("mimeType") != "image/gif" && !sending && !preparingAttachments) {
                            thumbnail.contentDescription = "标注 ${file.string("name")}"
                            thumbnail.isFocusable = true
                            thumbnail.background = Ui.ripple(Color.TRANSPARENT, Ui.dp(this, 6).toFloat())
                            thumbnail.setOnClickListener { editAttachmentMarkup(file, local) }
                        }
                        thumbnail.addView(ImageView(this).apply {
                            setImageBitmap(bitmap)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }, FrameLayout.LayoutParams(-1, -1))
                        imageProgress = Ui.text(this, "", 11f, Color.WHITE).apply {
                            gravity = Gravity.CENTER
                            setBackgroundColor(0x99000000.toInt())
                            visibility = View.GONE
                        }
                        thumbnail.addView(imageProgress, FrameLayout.LayoutParams(-1, -1))
                        row.addView(thumbnail, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
                    }
                }
            }
            val state = if (draftId == failedUploadDraft) "上传失败 · 发送可重试" else AttachmentStore.sizeLabel(file.optLong("size"))
            val label = Ui.text(this, "${file.string("name")}\n$state", 12f).apply {
                maxLines = 2; maxWidth = Ui.dp(context, 140); ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 4), 0)
            }
            attachmentProgress[draftId] = imageProgress ?: label
            row.addView(label)
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
        if (currentThreadId.isEmpty() || readOnly || providerUnavailable || localModelBlockReason() != null || running || latestTurn?.canContinue != true) return false
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

    private fun localModelBlockReason(): String? {
        if (currentAgent() != LocalCodex.AGENT) return null
        if (currentThread != null) {
            return if (currentThread?.let(modelCatalog::threadProviderId).isNullOrEmpty())
                "本机未登录 GPT，请新建会话并选择第三方模型" else null
        }
        return when {
            selectedModel() != null -> null
            !modelCatalogLoaded -> "正在获取本机模型"
            models.isEmpty() -> "请先在模型供应商中配置第三方模型"
            else -> "请选择第三方模型"
        }
    }

    private fun sendMessage(steer: Boolean) = submitMessage(steer)

    private fun submitMessage(steer: Boolean) {
        if (providerUnavailable) { toast(providerSwitchHint); return }
        if (currentThreadId in deletingThreads) { toast("正在归档此对话"); return }
        if (editingQueueDraft == composerBinding) return
        val unresolved = outbox.matching(composerBinding, ::pathKey)
        if (unresolved.isNotEmpty()) { showPendingSend(unresolved.last().id); return }
        val submittedText = composer.text.toString()
        val text = submittedText.trim()
        val files = attachmentFiles.toList()
        if ((text.isEmpty() && files.isEmpty()) || sending || loading || preparingAttachments) return
        if (!online || !loaded) { toast("设备尚未连接或正在同步"); return }
        if (readOnly) {
            toast("此会话为只读，请新建会话")
            return
        }
        localModelBlockReason()?.let { toast(it); return }
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
        uploadingDraft = ""
        failedUploadDraft = ""
        sending = true; updateControls()
        val relayImages = info.optJSONObject("attachments")?.optBoolean("relayImages") == true
        AttachmentUploader(attachmentStore, sender, relayImages).upload(key, files, { file, percent ->
            if (active(currentGeneration)) {
                uploadingDraft = file.string("draftId")
                attachmentProgress[uploadingDraft]?.apply {
                    this.text = if (parent is FrameLayout) "$percent%" else "${file.string("name")}\n上传 $percent%"
                    visibility = View.VISIBLE
                }
            }
        }) { error ->
            if (!active(currentGeneration)) return@upload
            if (error != null) {
                failedUploadDraft = uploadingDraft
                outbox.failed(pending.id, outcomeUnknown = false); activePendingSendId = ""; persistOutbox()
                sending = false; renderAttachments(); updateControls(); toast(error.message); return@upload
            }
            uploadingDraft = ""
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
        localModelBlockReason()?.let { failSend(pending, null, it); return }
        val sendOpenGeneration = openGeneration
        if (running) sendFollowup(text, steer, sendOpenGeneration, files, pending)
        else if (currentThread == null) {
            val params = JSONObject().put("sandbox", store.sandbox).put("approvalPolicy", store.approvalPolicy)
            if (currentCwd.isNotEmpty()) params.put("cwd", currentCwd)
            selectedModel()?.let { params.put("model", it.model).put("codexrmProvider", it.providerId) }
            rpc("thread/start", params) { result, error ->
                if (sendOpenGeneration != openGeneration) return@rpc
                if (error != null || result == null) { failSend(pending, error); return@rpc }
                val startedThread = result.optJSONObject("thread")
                val threadId = startedThread?.string("id").orEmpty()
                if (startedThread == null || threadId.isEmpty()) {
                    failSend(pending, null, "新会话响应无效")
                    return@rpc
                }
                currentThread = startedThread
                currentThreadId = threadId
                result.string("model").takeIf { it.isNotEmpty() }?.let { startedThread.put("model", it) }
                val summary = ProtocolFormatter.thread(startedThread)
                currentCwd = summary.cwd.ifEmpty { currentCwd }
                threadList.addCreated(summary.copy(
                    cwd = currentCwd,
                    updatedAt = summary.lastActivityAt.takeIf { it != 0L } ?: System.currentTimeMillis() / 1000,
                ))
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
                renderWorkspaces()
                updateControls()
                startTurn(text, creating = true, sendOpenGeneration, files, threadPending)
            }
        } else startTurn(text, creating = false, sendOpenGeneration, files, pending)
    }

    private fun startTurn(
        text: String,
        creating: Boolean,
        sendOpenGeneration: Int,
        files: List<JSONObject>,
        pending: PendingSend,
        resumeIfUnloaded: Boolean = !creating,
    ) {
        val optimisticId = "optimistic-${System.nanoTime()}"
        val input = ProtocolFormatter.input(text, files)
        userMessages.begin(optimisticId, input)
        adapter.add(ProtocolFormatter.fromItem(JSONObject().put("id", optimisticId).put("type", "userMessage").put("content", input)))
        val threadId = currentThreadId
        val previousTurn = latestTurn
        val params = turnParams(threadId, text, files)
        rpc("turn/start", params) { result, error ->
            if (sendOpenGeneration != openGeneration || currentThreadId != threadId || activePendingSendId != pending.id) return@rpc
            if (error != null || result == null) {
                userMessages.cancel(optimisticId); adapter.remove(optimisticId)
                if (resumeIfUnloaded && threadNeedsResume(error, threadId)) {
                    val params = JSONObject().put("threadId", threadId).put("excludeTurns", true)
                    rpc("thread/resume", params) resume@{ resumed, resumeError ->
                        if (sendOpenGeneration != openGeneration || currentThreadId != threadId || activePendingSendId != pending.id) return@resume
                        val resumedThread = resumed?.optJSONObject("thread")
                        if (resumedThread?.optBoolean("codexrmProviderUnavailable") == true) {
                            currentThread?.put("codexrmProviderUnavailable", true)
                            renderQueue()
                            failStartTurn(pending, files, IllegalStateException(providerSwitchHint))
                        } else if (resumedThread?.string("id") == threadId) {
                            startTurn(text, creating, sendOpenGeneration, files, pending, resumeIfUnloaded = false)
                        } else failStartTurn(pending, files, resumeError ?: error)
                    }
                } else failStartTurn(pending, files, error)
                return@rpc
            }
            sending = false; activePendingSendId = ""; outbox.confirmed(pending.id); persistOutbox()
            finishSentAttachments(pending.attachmentKey, files)
            params.string("model").takeIf { it.isNotEmpty() }?.let { currentThread?.put("model", it) }
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

    private fun failStartTurn(pending: PendingSend, files: List<JSONObject>, error: Exception?) {
        val conflict = desktopWriterConflict(error)
        retainSentAttachments(pending.attachmentKey, files)
        failSend(pending, error, showToast = !conflict)
        if (conflict) toast("会话仍被占用，请关闭桌面 Codex 后重试")
    }

    private fun failSend(pending: PendingSend, error: Exception?, fallback: String = "消息发送失败", showToast: Boolean = true) {
        val unknown = error is RpcOutcomeUnknown
        sending = false; activePendingSendId = ""
        outbox.failed(pending.id, unknown); persistOutbox()
        if (!unknown && !pending.continuation) {
            val restored = ComposerText.afterFailedSend(composer.text.toString(), pending.submittedText)
            if (restored != composer.text.toString()) setComposerText(restored)
            saveDraft()
        }
        updateControls()
        if (showToast) toast(if (unknown) "发送状态未确认，内容已保留。请先核对会话历史；不会自动重发。" else error?.message ?: fallback)
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
            "codexrm/thread/providerChanged" -> {
                if (currentThreadId != params.string("threadId")) return
                val provider = params.string("providerId")
                val model = params.string("model")
                val effort = if (currentThread?.string("model") == model) selectedEffort() else ""
                currentThread?.put("codexrmProvider", provider)?.put("model", model)?.remove("codexrmProviderUnavailable")
                showModelOptions(provider, model, effort)
                if (model.isNotEmpty()) explicitModelChoice = ModelChoice(currentThreadId, provider, model, selectedEffort())
                renderQueue(); updateControls()
                return
            }
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
            "thread/archived" -> { removeArchivedThread(params.string("threadId")); loadThreads(generation); loadConversationPins(); return }
            "thread/unarchived" -> { loadThreads(generation); loadConversationPins(); return }
        }
        val turn = params.optJSONObject("turn")
        val threadId = params.string("threadId").ifEmpty { turn?.string("threadId").orEmpty() }
        if (method == "turn/started" || method == "turn/completed") {
            val updatedAt = System.currentTimeMillis() / 1000
            threadList.updateActivity(threadId, active = method == "turn/started", updatedAt = updatedAt)
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
                val replyCompleted = running && finished.id.isNotEmpty() && activeTurnId == finished.id &&
                    finished.status == "completed"
                if (!running || activeTurnId == finished.id) {
                    latestTurn = finished
                    running = false; handler.removeCallbacks(elapsedTicker)
                }
                val turnItems = turn.arrayAt("items"); for (index in 0 until turnItems.length()) turnItems.optJSONObject(index)?.let { upsertItem(turn.string("id"), it) }
                // Some terminal events omit items that have already been streamed.
                flushDeltas()
                val finishedReplies = deltaBuffers.values.filter { it.turnId == finished.id && it.type == "agentMessage" }
                for (reply in finishedReplies) {
                    deltaBuffers.remove("${reply.id}:text")
                    adapter.message(reply.id)?.let { adapter.prepareReply(it, streaming = false) }
                }
                val retryId = TurnRenderer.retryErrorId(finished.id)
                val error = turn.optJSONObject("error")
                if (error != null) {
                    adapter.replaceMessage(retryId, TurnRenderer.errorMessage(finished.id, error))
                } else {
                    adapter.remove(retryId)
                }
                if (turn.string("status") == "interrupted") adapter.add(ChatMessage("stopped-${turn.string("id")}", MessageKind.STATUS, "", "任务已停止"))
                adapter.snapshot().filter { it.turnId == finished.id && it.title == "思考过程" }.forEach { message ->
                    if (message.status == "思考中" || message.status.isBlank()) {
                        finishReasoning(message.id, finished.status == "interrupted")
                        dirtyMessageIds += message.id
                    }
                }
                val completedThread = currentThreadId
                val completedGeneration = generation
                if (completedThread.isNotEmpty() && info.optBoolean("threadSnapshots")) {
                    rpc("codexrm/thread/snapshot", JSONObject().put("threadId", completedThread)) { snapshot, _ ->
                        if (completedGeneration == generation && completedThread == currentThreadId) {
                            snapshot?.optJSONObject("thread")?.let(::cacheConversation)
                        }
                    }
                }
                updateControls(); scheduleRender()
                if (replyCompleted && resumed && threadId.isNotEmpty()) {
                    mainRoot.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            "item/started", "item/completed" -> params.optJSONObject("item")?.let {
                if (it.string("type") == "reasoning") {
                    val id = it.string("id")
                    if (method == "item/started") reasoningStarted.putIfAbsent(id, android.os.SystemClock.elapsedRealtime())
                    else finishReasoning(id, false, it.optLong("durationMs", -1))
                }
                upsertItem(params.string("turnId"), it, streaming = method == "item/started")
            }
            "item/agentMessage/delta" -> appendDelta(params, "agentMessage", "text")
            "item/commandExecution/outputDelta" -> appendDelta(params, "commandExecution", "aggregatedOutput")
            "item/reasoning/textDelta", "item/reasoning/summaryTextDelta" -> appendDelta(params, "reasoning", "reasoningText")
            "turn/plan/updated" -> upsertItem(params.string("turnId"), JSONObject().put("id", "plan-${params.string("turnId")}").put("type", "plan").put("text", params.string("explanation")).put("plan", params.arrayAt("plan")))
            "turn/diff/updated" -> {
                diffTurn = params.string("turnId")
                latestDiff = params.string("diff")
                updateDiffButton()
                upsertItem(diffTurn, JSONObject().put("id", "diff-$diffTurn").put("type", "diff").put("diff", latestDiff))
            }
            "error" -> {
                val turnId = params.string("turnId")
                val willRetry = params.optBoolean("willRetry")
                if (threadId == currentThreadId && turnId.isNotEmpty() && !willRetry &&
                    (latestTurn == null || latestTurn?.id == turnId)) {
                    latestTurn = TurnProgress(turnId, "failed")
                    running = false
                    handler.removeCallbacks(elapsedTicker)
                }
                val error = params.optJSONObject("error")
                val retryId = TurnRenderer.retryErrorId(turnId)
                when {
                    turnId.isEmpty() -> adapter.add(TurnRenderer.errorMessage(turnId, error, "error-${System.nanoTime()}"))
                    !willRetry -> adapter.replaceMessage(retryId, TurnRenderer.errorMessage(turnId, error))
                    adapter.message(TurnRenderer.errorId(turnId)) == null ->
                        adapter.replaceMessage(retryId, TurnRenderer.errorMessage(turnId, error, retryId))
                }
                scheduleRender(); updateControls()
            }
        }
    }

    private fun upsertItem(turnId: String, item: JSONObject, immediate: Boolean = false, streaming: Boolean = false) {
        val id = item.string("id").ifEmpty { "$turnId-${item.string("type")}" }; if (!item.has("id")) item.put("id", id)
        if (item.string("type") == "commandExecution") item.put("aggregatedOutput", item.string("aggregatedOutput").bounded())
        items[id] = item
        deltaBuffers.remove("$id:text"); deltaBuffers.remove("$id:reasoningText"); deltaBuffers.remove("$id:aggregatedOutput")
        dirtyDeltaIds.remove(id)
        val localId = if (item.string("type") == "userMessage") userMessages.accept(turnId, id, item.arrayAt("content")) else null
        val existing = adapter.message(id)
        if (localId != null) {
            adapter.replaceMessage(localId, ProtocolFormatter.fromItem(item))
            return
        }
        val message = existing ?: ProtocolFormatter.fromItem(item)
        message.turnId = turnId
        val previous = message.rendered
        if (existing != null) ProtocolFormatter.update(message, item)
        if (item.string("type") == "reasoning") {
            val duration = item.optLong("durationMs", -1)
            message.change(status = reasoningStatus[id] ?: when {
                streaming -> "思考中"
                duration >= 0 -> "思考 ${duration / 1000} 秒"
                else -> ""
            })
        }
        if (message.kind == MessageKind.ASSISTANT && !immediate) adapter.prepareReply(message, streaming, previous)
        if (existing == null) adapter.add(message)
        else if (immediate) adapter.refreshRows(messageList, setOf(id))
        else if (message.kind != MessageKind.ASSISTANT || message.questions.isNotEmpty()) {
            dirtyMessageIds.add(id)
            scheduleRender()
        }
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
            DeltaBuffer(id, type, params.string("turnId"), StringBuilder(baseline))
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
            val message = existing ?: when (buffer.type) {
                "agentMessage" -> ChatMessage(id, MessageKind.ASSISTANT, "Codex", text)
                "reasoning" -> ChatMessage(id, MessageKind.TOOL, "思考过程", text)
                else -> ChatMessage(id, MessageKind.TOOL, "运行命令", text, "执行中")
            }
            message.turnId = buffer.turnId
            val previous = message.rendered
            if (buffer.type == "commandExecution") {
                val command = items[id]?.string("command").orEmpty()
                message.change(body = listOf(command, text).filter { it.isNotEmpty() }.joinToString("\n\n"), status = "执行中")
            } else if (buffer.type == "reasoning") {
                reasoningStarted.putIfAbsent(id, android.os.SystemClock.elapsedRealtime())
                message.change(body = text, status = "思考中")
            }
            else message.change(body = text)
            if (message.kind == MessageKind.ASSISTANT) adapter.prepareReply(message, streaming = true, previous = previous)
            if (existing == null) adapter.add(message)
            else if (message.kind != MessageKind.ASSISTANT) dirtyMessageIds.add(id)
        }
    }

    private fun scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        messageList.postDelayed({
            renderScheduled = false
            flushDeltas()
            adapter.refreshRows(messageList, dirtyMessageIds)
            dirtyMessageIds.clear()
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
        if (id == currentThreadId) { renderQueue(); updateControls(); adapter.notifyDataSetChanged() }
    }

    private fun renderQueue() {
        if (!mainBuilt) return
        queueArea.removeAllViews(); val queue = messageQueues[currentThreadId]; val entries = queue?.arrayAt("entries")
        if (queue == null || entries == null || entries.length() == 0) { queueArea.visibility = View.GONE; return }
        queueArea.visibility = View.VISIBLE
        val card = Ui.column(this).apply { setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10)); background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 12).toFloat(), Ui.BORDER) }
        val header = Ui.column(this, false).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.text(this, "${if (queue.optBoolean("paused")) "排队已暂停" else "待发送"} · ${entries.length()} 条", 12f, Ui.MUTED), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (queue.optBoolean("paused") && !providerUnavailable && localModelBlockReason() == null) header.addView(Ui.button(this, "继续排队") { changeMessageQueue("resume") })
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
        queue.string("error").takeIf { it.isNotEmpty() }?.let { card.addView(Ui.text(this, it, 12f, Ui.MUTED), matchWidth(top = 6)) }
        queueArea.addView(card, matchWidth(bottom = 10))
    }

    private fun changeMessageQueue(action: String, id: String = "", draft: QueuedComposerDraft? = null) {
        val threadId = currentThreadId
        if (!online || sending || loading || readOnly || threadId.isEmpty()) return
        if (action == "resume" && (providerUnavailable || localModelBlockReason() != null)) return
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
        if (!readingMode && composer.requestFocus()) getSystemService(InputMethodManager::class.java)?.showSoftInput(composer, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun renderApproval() {
        if (!mainBuilt) return
        approvalArea.removeAllViews()
        val visible = requests.keys.mapNotNull { key ->
            if (ApprovalFilter.visible(requestScopes[key], currentAgent(), currentThreadId, activeTurnId)) requests[key] else null
        }
        approvalScroll.visibility = if (visible.isEmpty() && approvalRecoveryMessage.isEmpty()) View.GONE else View.VISIBLE
        if (approvalRecoveryMessage.isNotEmpty()) {
            approvalArea.addView(Ui.text(this, approvalRecoveryMessage, 12f, Ui.MUTED).apply {
                setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6))
            }, matchWidth())
        }
        val permissions = visible.filter(::isPermission)
        val firstPermission = permissions.firstOrNull()
        val permissionKeys = permissions.map { requestKey(it.opt("id")) }
        val permissionScopes = permissionKeys.map { requestScopes[it] ?: ApprovalScope("", "", "") }
        if (ApprovalFilter.canBatch(permissionScopes)) {
            val batchEnabled = online && loaded && permissionKeys.none { it in responding }
            val batch = Ui.column(this)
            if (permissions.all { "accept" in ProtocolFormatter.permissionDecisions(it) }) {
                batch.addView(Ui.button(this, "全部允许 (${permissions.size})", primary = true) {
                    answerPermissions(permissions, "accept")
                }.apply { isEnabled = batchEnabled })
            }
            if (permissions.all { "decline" in ProtocolFormatter.permissionDecisions(it) }) {
                batch.addView(Ui.button(this, "全部拒绝") {
                    answerPermissions(permissions, "decline")
                }.apply { isEnabled = batchEnabled })
            }
            if (batch.childCount > 0) approvalArea.addView(batch, matchWidth())
        }
        for (message in visible) {
            val permission = isPermission(message)
            // Keep the head visible until its resolution arrives, including while sending a reply.
            if (permission && message !== firstPermission) continue
            val key = requestKey(message.opt("id"))
            val busy = key in responding
            val card = Ui.column(this).apply {
                setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 14))
                background = Ui.rounded(Ui.SURFACE, Ui.dp(context, 12).toFloat(), 0x55bd9c55); alpha = if (busy) .65f else 1f
            }
            card.addView(Ui.text(this, ProtocolFormatter.requestTitle(message), 13f, bold = true))
            card.addView(Ui.text(this, ProtocolFormatter.requestDetails(message), 12f, Ui.MUTED).apply { maxLines = 7; ellipsize = android.text.TextUtils.TruncateAt.END }, matchWidth(top = 7))
            val actions = Ui.column(this)
            if (permission) {
                for (decision in ProtocolFormatter.permissionDecisions(message)) {
                    val label = when (decision) {
                        "accept" -> "允许一次"
                        "acceptForSession" -> "本次会话允许"
                        "decline" -> "拒绝"
                        "cancel" -> "取消当前任务"
                        else -> continue
                    }
                    actions.addView(Ui.button(this, label, primary = decision == "accept") {
                        answerPermissions(listOf(message), decision)
                    })
                }
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

    private fun answerPermissions(messages: List<JSONObject>, decision: String) {
        if (!online || !loaded) return
        val connection = client ?: return
        for (message in messages) {
            val id = message.opt("id") ?: continue
            val key = requestKey(id)
            if (key in responding || requests[key] !== message ||
                !ApprovalFilter.visible(requestScopes[key], currentAgent(), currentThreadId, activeTurnId) ||
                decision !in ProtocolFormatter.permissionDecisions(message)) continue
            if (!connection.respond(id, ProtocolFormatter.permissionResult(message, decision))) break
            responding.add(key)
        }
        renderApproval()
    }

    private data class QuestionSubmission(val agentId: String, val threadId: String, val itemId: String)
    private val pendingQuestionSubmissions = HashSet<QuestionSubmission>()
    private val acceptedQuestionSubmissions = HashSet<QuestionSubmission>()

    private fun asyncQuestionStatus(message: ChatMessage): String? {
        val scope = QuestionSubmission(currentAgent(), currentThreadId, message.id)
        if (scope in pendingQuestionSubmissions) return "正在提交…"
        if (scope in acceptedQuestionSubmissions) return "已回答"
        val ids = message.questions.mapTo(HashSet()) { it.id }
        if (adapter.snapshot().any { row -> row.questionReplies.any { it.id in ids } }) return "已回答"
        val entries = messageQueues[currentThreadId]?.arrayAt("entries") ?: return null
        for (index in 0 until entries.length()) {
            val input = entries.optJSONObject(index)?.arrayAt("input") ?: continue
            if (AsyncQuestions.repliesIn(input).any { it.id in ids }) return "回答已排队"
        }
        return null
    }

    private fun showAsyncQuestions(message: ChatMessage) {
        if (message.questions.isEmpty() || asyncQuestionStatus(message) != null) return
        if (!online || !loaded || loading) { toast("设备尚未连接或正在同步"); return }
        if (readOnly) { toast("此会话目前为只读"); return }
        val scope = QuestionSubmission(currentAgent(), currentThreadId, message.id)
        val column = Ui.column(this).apply { setPadding(Ui.dp(context, 22), Ui.dp(context, 8), Ui.dp(context, 22), 0) }
        val inputs = message.questions.map { question ->
            column.addView(Ui.text(this, question.title, 15f, bold = true), matchWidth(top = 12))
            val custom = MiuixFieldView(this, if (question.options.isEmpty()) "请输入回答" else "也可以输入自己的回答")
            val choices = MiuixChoicesView(this, question.options) { custom.text = "" }
            if (question.options.isNotEmpty()) column.addView(choices, matchWidth())
            column.addView(custom, matchWidth(bottom = 10))
            choices to custom
        }
        val scroll = ScrollView(this).apply { addView(column) }
        val dialog = styledDialog(MiuixDialog.Builder(this).setTitle("回答 Codex").setView(scroll)
            .setPositiveButton("提交", null).setNegativeButton("取消", null))
        val submit = dialog.getButton(MiuixDialog.POSITIVE)
        submit.setOnClickListener {
            val answers = inputs.map { (choices, custom) ->
                custom.text.trim().ifEmpty {
                    choices.value
                }
            }
            val missing = answers.indexOfFirst(String::isBlank)
            if (missing >= 0) {
                inputs[missing].second.error = "请输入回答"
                inputs[missing].second.focusInput()
                return@setOnClickListener
            }
            if (scope.agentId != currentAgent() || scope.threadId != currentThreadId ||
                adapter.message(scope.itemId)?.questions != message.questions ||
                asyncQuestionStatus(message) != null || scope.threadId in deletingThreads) {
                dialog.dismiss()
                return@setOnClickListener
            }
            if (!online || !loaded || loading || readOnly) { toast("当前会话暂时无法发送"); return@setOnClickListener }
            if (running && !info.optBoolean("messageQueue")) { toast("任务结束后可提交回答"); return@setOnClickListener }
            val queued = running
            pendingQuestionSubmissions.add(scope)
            submit.isEnabled = false
            adapter.notifyDataSetChanged()
            val params = turnParams(scope.threadId, AsyncQuestions.reply(message.questions, answers))
            rpc(if (queued) "codexrm/queue/add" else "turn/start", params) { result, error ->
                pendingQuestionSubmissions.remove(scope)
                if (result != null) {
                    if (queued && scope.agentId == currentAgent()) acceptMessageQueue(result)
                    if (!queued) acceptedQuestionSubmissions.add(scope)
                    dialog.dismiss()
                    if (scope.agentId == currentAgent() && scope.threadId == currentThreadId) {
                        toast(if (queued) "回答已排队" else "回答已发送")
                    }
                } else {
                    submit.isEnabled = true
                    if (error is RpcOutcomeUnknown) {
                        dialog.dismiss()
                        toast("提交状态未确认，请查看会话和排队消息")
                        if (scope.agentId == currentAgent() && scope.threadId == currentThreadId) refreshAll()
                    } else toast(error?.message.ifNullOrEmpty("回答发送失败"))
                }
                if (mainBuilt) adapter.notifyDataSetChanged()
            }
        }
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
                val choices = MiuixChoicesView(this, labels, dropdown = true)
                column.addView(choices, matchWidth())
                val other = MiuixFieldView(this, "也可以输入自己的回答"); column.addView(other, matchWidth())
                fields += AnswerField(question.string("id")) { other.text.ifEmpty { choices.value } }
            } else {
                val secret = question.optBoolean("isSecret")
                val input = MiuixFieldView(
                    this, "请输入回答", keyboardType = if (secret) KeyboardType.Password else KeyboardType.Text,
                    secret = secret,
                )
                column.addView(input, matchWidth())
                fields += AnswerField(question.string("id")) { input.text }
            }
        }
        scroll.addView(column)
        styledDialog(MiuixDialog.Builder(this).setTitle("回答 Codex").setView(scroll).setPositiveButton("提交") { _, _ ->
            val answers = JSONObject(); fields.forEach { answers.put(it.id, JSONObject().put("answers", JSONArray().put(it.value()))) }
            respondResult(message, JSONObject().put("answers", answers))
        }.setNegativeButton("取消", null))
    }

    private fun showRawResponse(message: JSONObject) {
        val input = MiuixFieldView(this, "JSON 结果", "{}", supportingText = "例如 {}")
        val dialog = MiuixDialog.Builder(this).setTitle("返回 JSON 结果").setView(input)
            .setPositiveButton("返回结果", null).setNegativeButton("取消", null).show()
        dialog.getButton(MiuixDialog.POSITIVE).setOnClickListener {
            val parsed = try {
                JSONTokener(input.text).nextValue()
            } catch (_: org.json.JSONException) {
                input.error = "请输入有效的 JSON"
                input.focusInput()
                return@setOnClickListener
            }
            dialog.dismiss()
            respondResult(message, parsed)
        }
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
            online -> "已连接 · 点击切换"
            devicesExpanded -> "选择已配对设备"
            else -> "待连接 · 点击切换"
        }
        sidebarDeviceStatus.setTextColor(if (online) Ui.GREEN else Ui.MUTED)
        sidebarDeviceButton.contentDescription = (if (devicesExpanded) "收起设备列表，当前设备：" else "展开设备列表，当前设备：") + currentName
        sidebarDeviceButton.isSelected = devicesExpanded
        sidebarDeviceChevron.rotation = if (devicesExpanded) 180f else 0f
        deviceDropdown.visibility = if (devicesExpanded) View.VISIBLE else View.GONE
        deviceListScroll.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (devicesExpanded) min(Ui.dp(this, 56) * (devices.size + if (LocalCodex.supported) 1 else 0), Ui.dp(this, 224)) else 0,
        )
        sidebarConnectionButton = null
        deviceRows.removeAllViews()
        devices.forEach { item ->
            val selected = item.agentId == device?.agentId
            val row = deviceRow(item, selected, inSidebar = true) { scheduleDeleteDevice(item) }
            row.setOnClickListener { selectDevice(item, closeSidebarAfter = true) }
            deviceRows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))
        }
        if (LocalCodex.supported) {
            val row = deviceRow(localCodexDevice, device?.agentId == LocalCodex.AGENT, inSidebar = true)
            row.setOnClickListener { chooseLocalCodex(closeSidebarAfter = true) }
            deviceRows.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)))
        }
    }

    private fun chooseLocalCodex(closeSidebarAfter: Boolean = false) {
        if (!LocalCodex.supported) { toast(LocalCodex.UNSUPPORTED); return }
        if (!LocalCodex.available(this)) {
            if (LocalCodex.installed(this)) { toast("运行扩展与 CodexRM 签名不一致，请安装对应来源的版本。"); return }
            if (downloadingLocalCodex) { toast("正在下载本机运行扩展。"); return }
            styledDialog(MiuixDialog.Builder(this).setTitle("Codex 本地运行扩展")
                .setMessage("首次使用需要下载并安装独立运行扩展。安装后需要允许它访问设备文件，才能读取和修改工作区。")
                .setPositiveButton("下载扩展") { _, _ ->
                    requestLocalCodexDownload()
                }.setNegativeButton("取消", null))
            return
        }
        offerLocalPermission = true
        if (device?.agentId == LocalCodex.AGENT && !mainVisible) {
            showMain()
        } else if (device?.agentId == LocalCodex.AGENT && !online) {
            if (closeSidebarAfter) closeSidebar()
            disconnect(); connect()
        } else selectDevice(localCodexDevice, closeSidebarAfter)
    }

    private fun requestLocalCodexDownload() {
        if (UpdateChecker.canInstall(this)) downloadLocalCodex()
        else {
            waitingLocalInstallSource = true
            val settings = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:$packageName"))
            if (runCatching { startActivity(settings) }.isFailure) {
                waitingLocalInstallSource = false
                toast("请先在系统设置中允许 CodexRM 安装未知来源应用。")
            }
        }
    }

    private fun downloadLocalCodex() {
        if (downloadingLocalCodex) return
        downloadingLocalCodex = true
        val progress = Ui.text(this, "正在准备下载…", 14f)
        val dialog = styledDialog(MiuixDialog.Builder(this).setTitle("下载本机运行扩展")
            .setView(progress).setCancelable(false))
        val app = applicationContext
        Thread({
            val result = runCatching { LocalCodexDownload.download(app) { percent ->
                handler.post { if (!isDestroyed) progress.text = "已下载 $percent%" }
            } }
            handler.post {
                if (isDestroyed) return@post
                downloadingLocalCodex = false
                dialog.dismiss()
                result.onSuccess { apk ->
                    waitingLocalInstall = true
                    if (!UpdateChecker.launchNormalPackageInstall(this, apk)) {
                        waitingLocalInstall = false
                        toast("无法打开 Android 安装界面。")
                    }
                }.onFailure { toast(it.message ?: "下载扩展失败。") }
            }
        }, "codex-local-download").start()
    }

    private fun selectDevice(item: Device, closeSidebarAfter: Boolean) {
        if (item.agentId != LocalCodex.AGENT) { offerLocalPermission = false; waitingLocalInstall = false }
        devicePopup?.dismiss()
        devicesExpanded = false
        if (closeSidebarAfter) closeSidebar() else updateBackHandling()
        if (item.agentId in expiredAgentIds) {
            pairMessage = "“${item.serverName}”的配对已失效，请重新配对"
            showPair()
            return
        }
        if (item.agentId == device?.agentId) {
            renderDevices()
            return
        }
        saveDraft()
        // Persist the old owner's outbox before changing currentAgent(), otherwise
        // disconnect() would save those records under the newly selected device.
        disconnect()
        device?.online = false
        store.selectedId = item.agentId
        device = item
        restoreThreadId = ""
        showMain(reset = true)
    }

    private fun deviceStatus(item: Device, selected: Boolean): String {
        if (item.agentId == LocalCodex.AGENT) return when {
            selected && item.online -> "在此手机上运行 · 已连接"
            LocalCodex.available(this) -> "在此手机上运行 · 点击连接"
            else -> "按需安装运行扩展"
        }
        val version = (if (selected) info.string("version").ifEmpty { item.appVersion } else item.appVersion)
            .takeIf { it.isNotEmpty() }?.let { "Agent v$it · " }.orEmpty()
        return when {
            item.agentId in expiredAgentIds -> "配对已失效 · 点击重新配对"
            selected && item.online -> "${version}当前设备 · 已连接"
            selected -> "${version}当前设备 · 等待连接"
            else -> "${version}已配对 · 点击切换"
        }
    }

    private fun deviceRow(
        item: Device,
        selected: Boolean,
        inSidebar: Boolean = false,
        onDelete: (() -> Unit)? = null,
    ): LinearLayout = Ui.column(this, false).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = Ui.dp(context, 56)
        background = Ui.ripple(if (selected) Ui.SURFACE_ALT else Color.TRANSPARENT, Ui.dp(context, 9).toFloat())
        setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 10), 0)
        isClickable = true; isFocusable = true; isSelected = selected
        val name = item.serverName.ifBlank { "未命名设备" }
        val status = deviceStatus(item, selected)
        contentDescription = if (selected) "$name，$status" else "$name，$status，点击切换"
        if (onDelete != null) {
            contentDescription = "$contentDescription，长按删除"
            setOnLongClickListener { onDelete(); true }
        }
        val icon = FrameLayout(context).apply {
            background = Ui.rounded(if (selected) Ui.SURFACE else Ui.SURFACE_ALT, Ui.dp(context, 7).toFloat())
            addView(IconView(context, "monitor", if (selected) Ui.TEXT else Ui.MUTED), FrameLayout.LayoutParams(Ui.dp(context, 17), Ui.dp(context, 17), Gravity.CENTER))
        }
        addView(icon, LinearLayout.LayoutParams(Ui.dp(context, 30), Ui.dp(context, 30)))
        val labels = Ui.column(context).apply { setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 6), 0) }
        labels.addView(Ui.text(context, name, 12f, bold = selected).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        labels.addView(Ui.text(context, status, 12f, if (selected && item.online) Ui.GREEN else Ui.MUTED))
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val iconSize = Ui.dp(context, 17)
        if (inSidebar && selected) {
            val button = Ui.iconButton(context, "check", "") { showConnectionDetails() }
            button.getChildAt(0).layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.END or Gravity.CENTER_VERTICAL)
            if (onDelete != null) button.setOnLongClickListener { onDelete(); true }
            sidebarConnectionButton = button
            updateSidebarConnectionButton(activeConnectionStatus())
            addView(button)
        } else {
            addView(IconView(context, if (selected) "check" else "right", if (selected) Ui.GREEN else Ui.DIM), LinearLayout.LayoutParams(iconSize, iconSize))
        }
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
        labels.addView(Ui.text(context, "扫码或输入配对码", 12f, Ui.MUTED))
        addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(IconView(context, "right", Ui.DIM), LinearLayout.LayoutParams(Ui.dp(context, 17), Ui.dp(context, 17)))
        setOnClickListener { click() }
    }

    private fun renderWorkspaces() {
        if (!mainBuilt) return
        workspaceRows.removeAllViews()
        for ((tab, selected) in listOf(recentTab to !showSidebarWorkspaces, workspacesTab to showSidebarWorkspaces)) {
            tab.isSelected = selected
            tab.contentDescription = "${tab.text}页签，${if (selected) "已选中" else "未选中"}"
            tab.setTextColor(if (selected) Ui.TEXT else Ui.MUTED)
            tab.background = Ui.ripple(if (selected) Ui.SURFACE_ALT else Color.TRANSPARENT, Ui.dp(this, 24).toFloat())
        }
        workspaceActions.visibility = if (showSidebarWorkspaces) View.VISIBLE else View.GONE
        historyLabel.text = when {
            threadListLoading -> "正在加载会话…"
            threadListError.isNotEmpty() -> "会话加载失败"
            showSidebarWorkspaces -> "所有工作区"
            else -> "最近会话"
        }
        val pinned = pinnedThreads
        if (pinned.isNotEmpty() && !showSidebarWorkspaces) {
            workspaceRows.addView(Ui.text(this, "已置顶", 12f, Ui.MUTED), matchWidth(top = 8))
            pinned.forEach { thread -> workspaceRows.addView(sidebarThreadRow(threads.find { it.id == thread.id } ?: thread), matchWidth()) }
        }
        val matchingThreads = threads
        if (!showSidebarWorkspaces) {
            val recent = matchingThreads.filterNot { item -> pinned.any { it.id == item.id } }.sortedWith(compareByDescending<ThreadSummary> { it.lastActivityAt }
                .thenByDescending { it.createdAt }.thenByDescending { it.id })
            for (thread in recent.take(sidebarThreadLimit)) workspaceRows.addView(sidebarThreadRow(thread), matchWidth())
            if (recent.size > sidebarThreadLimit) workspaceRows.addView(Ui.button(this, "显示更多会话") {
                sidebarThreadLimit += 30
                renderWorkspaces()
            }, matchWidth(top = 4))
            renderSidebarListEnd(recent.isEmpty() && pinned.isEmpty())
            return
        }
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
                compareByDescending<ThreadSummary> { it.id in pinnedThreadIds }.thenByDescending { it.lastActivityAt }.thenByDescending { it.createdAt }.thenByDescending { it.id }
            )
            val matching = group.threads
            shown++
            val key = pathKey(group.path)
            val expanded = key !in collapsedPaths
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
            heading.addView(toggle, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
            if (group.path.isNotEmpty()) {
                heading.addView(Ui.iconButton(this, "plus", "在此工作区新建会话", 36) { selectWorkspace(group.path, showKeyboard = true) })
                if (group.pinned) heading.addView(Ui.iconButton(this, "close", "取消固定工作区", 36) { removeWorkspace(group.path) })
            }
            workspaceRows.addView(heading, matchWidth())
            if (expanded) {
                val history = Ui.column(this).apply { setPadding(Ui.dp(context, 17), 0, 0, Ui.dp(context, 10)) }
                val folder = currentAgent() to key
                val canShowOlder = matching.size > WORKSPACE_THREAD_FOLD_THRESHOLD
                val showOlder = canShowOlder && folder in expandedWorkspaceThreads
                val visibleThreads = if (canShowOlder && !showOlder) matching.take(WORKSPACE_RECENT_THREAD_LIMIT) else matching
                visibleThreads.forEachIndexed { index, thread ->
                    history.addView(sidebarThreadRow(thread), matchWidth())
                    if (canShowOlder && index + 1 == WORKSPACE_RECENT_THREAD_LIMIT) {
                        val olderCount = matching.size - WORKSPACE_RECENT_THREAD_LIMIT
                        val label = if (showOlder) "收起旧对话" else "展开其余 $olderCount 条对话"
                        val older = Ui.button(this, label) {
                            if (showOlder) expandedWorkspaceThreads.remove(folder) else expandedWorkspaceThreads.add(folder)
                            renderWorkspaces()
                        }
                        history.addView(older, matchWidth())
                    }
                }
                if (matching.isEmpty()) history.addView(Ui.text(this, "还没有会话", 12f, Ui.DIM).apply {
                    setPadding(Ui.dp(context, 12), 0, 0, 0)
                    minimumHeight = Ui.dp(context, 48)
                }, matchWidth())
                workspaceRows.addView(history, matchWidth())
            }
        }
        renderSidebarListEnd(shown == 0)
    }

    private fun sidebarThreadRow(thread: ThreadSummary): LinearLayout = Ui.column(this, false).apply {
        val selected = thread.id == currentThreadId
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isSelected = selected
        background = Ui.ripple(if (selected) Ui.SURFACE_ALT else Color.TRANSPARENT, Ui.dp(context, 20).toFloat())
        setPaddingRelative(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0)
        setOnClickListener { openThread(thread.id) }
        setOnLongClickListener { showSidebarThreadActions(thread); true }
        addView(Ui.text(context, thread.title, 14f, if (selected) Ui.TEXT else Ui.MUTED).apply {
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1f))
        if (thread.id in pinnedThreadIds && showSidebarWorkspaces) {
            addView(Ui.text(context, "置顶", 11f, Ui.MUTED))
        }
        if (thread.active) addView(MiuixProgressView(context, diameter = 14).apply {
            contentDescription = "进行中"
        }, LinearLayout.LayoutParams(Ui.dp(context, 14), Ui.dp(context, 14)).apply { marginStart = Ui.dp(this@MainActivity, 8) })
        addView(Ui.iconButton(context, "more", "会话操作：${thread.title}") { anchor ->
            showSidebarThreadActions(thread)
        })
    }

    private fun renderSidebarListEnd(empty: Boolean) {
        if (!threadListLoading && threadListError.isNotEmpty()) {
            workspaceRows.addView(Ui.text(this, threadListError, 12f, Ui.DANGER).apply {
                setPaddingRelative(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
            }, matchWidth())
            workspaceRows.addView(Ui.button(this, "重试加载会话") {
                loadThreads(generation)
            }, matchWidth(top = 4))
        }
        if (empty && !threadListLoading && threadListError.isEmpty()) {
            val message = when {
                showSidebarWorkspaces -> "还没有工作区"
                online -> "还没有会话"
                else -> "连接设备后，会话会出现在这里"
            }
            workspaceRows.addView(Ui.text(this, message, 12f, Ui.MUTED).apply {
                setPaddingRelative(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
            }, matchWidth())
            when {
                showSidebarWorkspaces -> workspaceRows.addView(Ui.button(this, "添加工作区", primary = true) {
                    openWorkspacePicker()
                }, matchWidth(top = 4))
            }
        }
        if (threadCursor != null && !threadListLoading) workspaceRows.addView(Ui.button(this, "加载更多会话") {
            loadThreads(generation)
        }, matchWidth(top = 4))
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
        // Baseline alignment probes weighted Compose views with an unspecified width.
        val pathRow = Ui.column(this, false).apply { isBaselineAligned = false }
        val pathInput = MiuixFieldView(this, "输入设备上的绝对目录路径")
        pathRow.addView(pathInput, LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f))
        lateinit var browseDirectory: (String) -> Unit
        val canBrowse = currentAgent() == LocalCodex.AGENT || info.optBoolean("directFiles")
        val go = Ui.button(this, "前往") { browseDirectory(pathInput.text) }; pathRow.addView(go)
        go.visibility = if (canBrowse) View.VISIBLE else View.GONE
        column.addView(pathRow, matchWidth())
        val shortcuts = Ui.column(this, false); column.addView(shortcuts, matchWidth(top = 10))
        val errorText = Ui.text(this, "", 12f, Ui.DANGER); column.addView(errorText, matchWidth())
        val list = Ui.column(this); column.addView(list, matchWidth())
        val hidden = MiuixCheckboxView(this, "显示隐藏目录").apply { visibility = if (canBrowse) View.VISIBLE else View.GONE }
        val add = Ui.button(this, "添加此文件夹", primary = true) { }
        add.isEnabled = false
        // Give the checkbox the full row, including on narrow screens or at large font sizes.
        column.addView(hidden, matchWidth(top = 14))
        column.addView(add, matchWidth(top = 8))
        column.addView(Ui.text(this, "添加后可切换多个工作区。移出列表只删除记录，保留文件和会话。", 12f, Ui.DIM), matchWidth(top = 10))
        scroll.addView(column)
        val dialog = styledDialog(MiuixDialog.Builder(this).setTitle("添加工作区").setView(scroll).setNegativeButton("取消", null))
        val pickerGeneration = generation
        var version = 0; var selectedPath = ""
        var adding = false
        fun render(result: JSONObject) {
            selectedPath = result.string("path"); pathInput.text = selectedPath
            errorText.text = ""; add.isEnabled = selectedPath.isNotEmpty(); shortcuts.removeAllViews(); list.removeAllViews()
            val links = ArrayList<Workspace>()
            result.string("parent").takeIf { it.isNotEmpty() }?.let { links += Workspace("上一级", it) }
            result.string("home").takeIf { it.isNotEmpty() }?.let { links += Workspace("主目录", it) }
            val roots = result.arrayAt("roots"); for (index in 0 until roots.length()) roots.optJSONObject(index)?.let { links += Workspace(it.string("name"), it.string("path")) }
            links.forEach { link -> shortcuts.addView(Ui.text(this, link.name, 12f, Ui.MUTED).apply {
                background = Ui.ripple(Ui.SURFACE, Ui.dp(context, 6).toFloat()); gravity = Gravity.CENTER
                setOnClickListener { browseDirectory(link.path) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 48)).apply { rightMargin = Ui.dp(this@MainActivity, 6) }) }
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
                list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))
            }
            if (directories.length() == 0) list.addView(Ui.text(this, "此目录没有可显示的子文件夹，可以直接添加。", 12f, Ui.DIM), matchWidth())
            if (result.optBoolean("truncated")) errorText.text = "仅显示前 500 个子目录，也可以输入完整路径。"
        }
        browseDirectory = fun(path: String) {
            if (adding || !active(pickerGeneration)) return
            val requestVersion = ++version; add.isEnabled = false; errorText.text = "正在读取…"
            rpc("codexrm/fs/list", JSONObject().put("path", path).put("showHidden", hidden.checked)) { result, error ->
                if (!active(pickerGeneration) || requestVersion != version || !dialog.isShowing) return@rpc
                if (result != null) render(result) else { errorText.text = error?.message; add.isEnabled = false }
            }
        }
        go.setOnClickListener { browseDirectory(pathInput.text) }
        hidden.onChanged = { if (selectedPath.isNotEmpty()) browseDirectory(selectedPath) }
        add.setOnClickListener {
            if (adding || !active(pickerGeneration) || sending || loading) return@setOnClickListener
            val path = if (canBrowse) selectedPath else pathInput.text.trim()
            if (path.isEmpty()) { errorText.text = "请输入电脑上的工作区绝对路径。"; return@setOnClickListener }
            adding = true
            add.isEnabled = false
            rpc("codexrm/workspace/add", JSONObject().put("path", path)) { result, error ->
                if (!active(pickerGeneration)) return@rpc
                adding = false
                if (result != null) {
                    if (dialog.isShowing && pathKey(path) != pathKey(currentCwd)) selectWorkspace(path)
                    loadWorkspaces(pickerGeneration)
                    dialog.dismiss()
                    toast("工作区已添加")
                } else {
                    add.isEnabled = true
                    errorText.text = error?.message
                }
            }
        }
        val initialPath = currentCwd.ifEmpty { info.string("cwd") }
        if (canBrowse) browseDirectory(initialPath) else {
            pathInput.text = initialPath
            add.isEnabled = true
        }
    }

    private fun showDevices(anchor: View = deviceButton) {
        if (devicePopup?.isShowing == true) {
            devicePopup?.dismiss()
            return
        }
        val popup = MiuixDialogHost(this)
        val paired = devices.toList()
        popup.setPopup(anchor) { close ->
            SmallTitle("选择设备")
            paired.forEach { item ->
                val selected = item.agentId == device?.agentId
                BasicComponent(
                    title = item.serverName.ifBlank { "未命名设备" },
                    summary = deviceStatus(item, selected),
                    endActions = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = { close(); selectDevice(item, closeSidebarAfter = false) },
                        onLongClickLabel = "删除电脑",
                        onLongClick = { scheduleDeleteDevice(item) },
                    ),
                )
            }
            if (LocalCodex.supported) BasicComponent(title = "本机 Codex", summary = deviceStatus(localCodexDevice, device?.agentId == LocalCodex.AGENT),
                endActions = { RadioButton(selected = device?.agentId == LocalCodex.AGENT, onClick = null) },
                onClick = { close(); chooseLocalCodex() })
            BasicComponent(title = "添加新设备", onClick = { close(); showPair() })
        }
        popup.setOnDismissListener { if (devicePopup === popup) devicePopup = null }
        devicePopup = popup
        popup.show()
    }

    private fun applyAppearance(area: AppearanceArea? = null) {
        if (mainBuilt) {
            if (area == null || area == AppearanceArea.CHAT) mainRoot.background = appearance.drawable(AppearanceArea.CHAT)
            if (area == null || area == AppearanceArea.SIDEBAR) sidebar.background = appearance.drawable(AppearanceArea.SIDEBAR)
            if (area == null) {
                adapter.bubbleOpacity = appearance.bubbleOpacity()
                renderBootstrapState()
            }
        }
        settingsPage?.refreshAppearance()
    }

    internal fun backgroundTarget(area: AppearanceArea): View? = when {
        !mainBuilt -> null
        area == AppearanceArea.CHAT -> mainRoot
        area == AppearanceArea.SIDEBAR -> sidebar
        else -> null
    }

    private fun pickBackgroundImage(area: AppearanceArea) {
        pendingBackgroundPicker = area
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }, PICK_APPEARANCE_IMAGE)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingBackgroundPicker = null
            reportBackgroundResult(false)
        }
    }

    private fun reportBackgroundResult(success: Boolean) {
        val message = if (success) "背景已更新" else "无法读取图片，请换一张或重试"
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showAppSettings(openProviders: Boolean = false) {
        if (mainVisible) closeSidebar()
        settingsPage?.dismiss()
        val agent = currentAgent()
        val local = agent == LocalCodex.AGENT
        fun availability(): String? = when {
            agent != currentAgent() || !online -> if (local) "请启动本机运行扩展后重试。" else "请连接这台电脑后重试。"
            !info.optBoolean("providerManagement") -> "此电脑 Agent 不支持供应商管理，请更新后重试。"
            else -> null
        }
        settingsPage = SettingsPage(
            this, device?.serverName.orEmpty(), ::availability,
            appearance = appearance,
            pickBackground = ::pickBackgroundImage,
            reportBackground = ::reportBackgroundResult,
            local = local,
            direct = { client?.usingDirect == true || device?.agentId == LocalCodex.AGENT },
            request = { operation, params, requireDirect, callback ->
                val current = client
                val blocked = availability()
                if (blocked != null || current == null) callback.onComplete(null, IOException(blocked ?: "设备未连接"))
                else current.requestProviderSettings(operation, params, requireDirect) { result, error ->
                    if (current === client && agent == currentAgent()) callback.onComplete(result, error)
                    else callback.onComplete(null, IOException("电脑连接已切换，请重试"))
                }
            },
            catalogChanged = { if (agent == currentAgent()) loadModels() },
            sections = { agentSettings ->
                listOfNotNull(
                    SettingsPage.Section(
                        "外观", actions = listOf(SettingsPage.Action("背景与透明度", { settingsPage?.showAppearance() })),
                        toggle = SettingsPage.Toggle("深色模式", { store.darkTheme }, ::setDarkTheme),
                    ),
                    device?.takeUnless { it.agentId == LocalCodex.AGENT }?.let { current ->
                        SettingsPage.Section("通知", toggle = SettingsPage.Toggle(
                            title = "后台任务通知",
                            checked = { TaskMonitor.enabled(this, current.agentId) },
                            change = ::toggleTaskMonitor,
                            summary = current.serverName,
                        ))
                    },
                    SettingsPage.Section("电脑与连接", actions = listOf(
                        agentSettings,
                        SettingsPage.Action("已配对电脑", ::showPairedComputers) { "${devices.size} 台" },
                        SettingsPage.Action("添加电脑", ::showPair),
                    )),
                    SettingsPage.Section(
                        title = "文件与系统分享",
                        actions = listOf(SettingsPage.Action("下载位置", ::showDownloadSettings) { workspaceFiles.downloadLocation }),
                        choice = SettingsPage.Choice(
                            title = "系统分享",
                            options = listOf("每次选择去向", "直接加入新会话草稿"),
                            selectedIndex = { if (store.shareToNewDraft) 1 else 0 },
                            select = { store.shareToNewDraft = it == 1 },
                        ),
                    ),
                    SettingsPage.Section("应用", actions = listOfNotNull(
                        SettingsPage.Action("检查更新", ::checkForUpdates).takeIf { UpdateChecker.isAvailable },
                        SettingsPage.Action("关于", ::showAbout) { UpdateChecker.currentVersionName(this) },
                        SettingsPage.Action("开放源代码许可", { settingsPage?.showOpenSourceLicenses() }),
                        SettingsPage.Action("开源仓库", { settingsPage?.openRepository() }) {
                            OpenSourceLicenses.REPOSITORY.removePrefix("https://")
                        },
                    )),
                )
            },
            agentSections = { providers ->
                val current = device
                buildList {
                    add(SettingsPage.Section("权限", actions = listOf(
                        SettingsPage.Action("工作区设置", ::showSettings) { "文件访问与审批策略" },
                    )))
                    if (current != null) {
                        add(SettingsPage.Section("服务与模型", actions = buildList {
                            add(providers)
                            if (!local) add(SettingsPage.Action("检查并更新电脑 Agent", ::confirmAgentUpdate))
                            else if (LocalCodexDownload.updateAvailable(this@MainActivity)) {
                                add(SettingsPage.Action("更新本机运行扩展", ::requestLocalCodexDownload))
                            }
                        }))
                    }
                }
            },
        ).also { it.show(openProviders) }
    }

    private fun showDownloadSettings() {
        val builder = MiuixDialog.Builder(this)
            .setTitle("下载位置")
            .setMessage(workspaceFiles.downloadLocation)
            .setPositiveButton("选择文件夹") { _, _ -> workspaceFiles.chooseDownloadLocation() }
            .setNegativeButton("返回", null)
        if (workspaceFiles.hasCustomDownloadLocation) {
            builder.setNeutralButton("恢复默认") { _, _ ->
                workspaceFiles.resetDownloadLocation()
                settingsPage?.refreshActionSummaries()
            }
        }
        styledDialog(builder)
    }

    private fun showAbout() {
        val content = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 8), Ui.dp(context, 24), Ui.dp(context, 16))
        }
        val version = UpdateChecker.currentVersionName(this)
        val code = UpdateChecker.currentVersionCode(this)
        content.addView(Ui.text(this, "已安装 v$version ($code)", 12f, Ui.MUTED))
        val scroll = ScrollView(this).apply { addView(content) }
        val dialog = styledDialog(MiuixDialog.Builder(this).setTitle("关于 CodexRM").setView(scroll).setPositiveButton("关闭", null))
        if (!UpdateChecker.isAvailable) return

        content.addView(Ui.text(this, "版本更新日志", 14f, bold = true), matchWidth(top = 16))
        val history = Ui.column(this)
        content.addView(history, matchWidth(top = 8))
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
        val sandboxModes = listOf("workspace-write", "read-only", "danger-full-access")
        val approvalPolicies = listOf("on-request", "untrusted", "never")
        val initialSandbox = sandboxModes.indexOf(store.sandbox).coerceAtLeast(0)
        val initialApproval = approvalPolicies.indexOf(store.approvalPolicy).coerceAtLeast(0)
        selectionDialog?.dismiss()
        selectionDialog = showMiuixSelectionDialog(this, "工作区设置", "在侧栏的“工作区”添加或切换文件夹。") { close ->
            MiuixWorkspaceChoices(initialSandbox, initialApproval, close) { sandbox, approval ->
                store.sandbox = sandboxModes[sandbox]
                store.approvalPolicy = approvalPolicies[approval]
                close()
                toast("设置已保存，将在新会话及下一次发送时生效")
            }
        }
    }

    private fun showPairedComputers() {
        val paired = devices.toList()
        val builder = MiuixDialog.Builder(this).setTitle("已配对电脑").setNegativeButton("返回", null)
        if (paired.isEmpty()) {
            builder.setMessage("暂无已配对电脑").setPositiveButton("添加电脑") { _, _ -> showPair() }
        } else {
            builder.setItems(paired.map { computer ->
                val status = when (computer.agentId) {
                    in expiredAgentIds -> " · 配对已失效"
                    currentAgent() -> " · 当前电脑"
                    else -> ""
                }
                computer.serverName + status
            }.toTypedArray()) { _, index -> showComputerSettings(paired[index]) }
                .setItemLongPress("删除电脑") { _, index -> confirmDeleteDevice(paired[index]) }
        }
        styledDialog(builder)
    }

    private fun showComputerSettings(computer: Device) {
        val current = computer.agentId == currentAgent()
        val agentVersion = (if (current) info.string("version") else "")
            .ifBlank { computer.appVersion }.ifBlank { "未知" }
        val builder = MiuixDialog.Builder(this).setTitle(computer.serverName)
            .setMessage("${computer.os} · ${computer.agentId}\n电脑 Agent v$agentVersion\n${computer.serverUrl}")
            .setNegativeButton("返回") { _, _ -> showPairedComputers() }
            .setNeutralButton("解除配对") { _, _ -> confirmDeleteDevice(computer) }
        when {
            computer.agentId in expiredAgentIds -> builder.setPositiveButton("重新配对") { _, _ -> showPair() }
            current && mainVisible -> builder.setPositiveButton("重新连接") { _, _ -> disconnect(); connect() }
            current -> builder.setPositiveButton("返回已配对电脑") { _, _ ->
                settingsPage?.dismiss()
                showMain()
            }
            else -> builder.setPositiveButton("切换到此电脑") { _, _ ->
                settingsPage?.dismiss()
                selectDevice(computer, closeSidebarAfter = mainVisible)
            }
        }
        styledDialog(builder)
    }

    private fun confirmAgentUpdate() {
        if (!online || !info.optBoolean("remoteUpdate")) { toast("电脑 Agent 离线或不支持远程更新"); return }
        styledDialog(MiuixDialog.Builder(this)
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
        val spinner = MiuixProgressView(this)
        val column = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 4), Ui.dp(context, 24), Ui.dp(context, 8))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(spinner)
            addView(Ui.text(context, "正在检查并校验电脑 Agent 更新…", 13f, Ui.MUTED), matchWidth(top = 12))
        }
        val dialog = styledDialog(MiuixDialog.Builder(this).setTitle("更新电脑 Agent").setView(column))
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
                error != null || result == null -> styledDialog(MiuixDialog.Builder(this)
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
        if (!UpdateChecker.isAvailable) return
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
        if (!UpdateChecker.isAvailable) return
        cancelUpdateOperation()
        val operation = updateOperation
        val spinner = MiuixProgressView(this)
        val column = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 4), Ui.dp(context, 24), Ui.dp(context, 8))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(spinner)
            addView(Ui.text(context, "正在检查新版本…", 13f, Ui.MUTED), matchWidth(top = 12))
        }
        val dialog = styledDialog(
            MiuixDialog.Builder(this)
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
                    MiuixDialog.Builder(this)
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
        handler.removeCallbacks(updateSilentFallback)
        updateFailureReceiver?.let { runCatching { unregisterReceiver(it) } }
        updateFailureReceiver = null
        updateDialog?.dismiss()
        updateDialog = null
        updateInstallStatus = null
        updateInstallApk = null
        updateInstallInfo = null
        updateInstallOperation = 0
        updateInstallRequest = ""
        ignoredAbortRequest = ""
        updateInstallPhase = UpdateInstallPhase.IDLE
    }

    private fun showUpdateFailure(message: String, retry: () -> Unit) {
        styledDialog(
            MiuixDialog.Builder(this)
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
            MiuixDialog.Builder(this)
                .setTitle("发现新版本 ${info.versionName}")
                .setMessage("当前版本 ${UpdateChecker.currentVersionName(this)}\n安装包 $size\n\n$notes\n\n下载校验完成后自动安装。")
                .setPositiveButton("立即更新") { _, _ ->
                    if (UpdateChecker.canInstall(this)) {
                        startUpdateDownload(info)
                    } else {
                        pendingUpdate = info
                        styledDialog(
                            MiuixDialog.Builder(this)
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
        val progress = MiuixProgressView(this, linear = true)
        val column = Ui.column(this).apply {
            setPadding(Ui.dp(context, 24), Ui.dp(context, 8), Ui.dp(context, 24), Ui.dp(context, 8))
            addView(status, matchWidth())
            addView(progress, matchWidth(top = 16))
        }
        val dialog = styledDialog(
            MiuixDialog.Builder(this)
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
            dialog.getButton(MiuixDialog.NEGATIVE).visible = false
            status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            status.text = "即将关闭软件，正在安装更新"
            progress.isIndeterminate = true
            updateInstallStatus = status
            updateInstallApk = apk
            updateInstallInfo = info
            updateInstallOperation = operation

            handler.postDelayed({
                if (operation != updateOperation || isFinishing || isDestroyed) return@postDelayed
                // Keep this window and the activity open. Android kills the process when a
                // silent update is applied. Unsupported devices fall back to the normal installer.
                val silent = UpdateChecker.shouldUseSilentInstallFlag(Build.VERSION.SDK_INT)
                if (!silent) status.text = "当前系统不支持静默安装，正在打开普通安装"
                submitPackageInstall(operation, info, apk, silent)
            }, 2000L)
        }
    }

    private fun submitPackageInstall(
        operation: Int,
        info: UpdateChecker.VersionInfo,
        apk: File,
        silent: Boolean,
    ) {
        if (operation != updateOperation || isFinishing || isDestroyed) return
        handler.removeCallbacks(updateSilentFallback)
        updateFailureReceiver?.let { runCatching { unregisterReceiver(it) } }
        updateFailureReceiver = null
        val requestId = UUID.randomUUID().toString()
        updateInstallRequest = requestId
        updateInstallPhase = if (silent) UpdateInstallPhase.SILENT else UpdateInstallPhase.INTERACTIVE
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (operation != updateOperation || isFinishing || isDestroyed) return
                if (intent.getStringExtra(UpdateInstallReceiver.EXTRA_REQUEST_ID) != requestId) return
                when (intent.action) {
                    UpdateInstallReceiver.ACTION_INSTALL_FINISHED -> {
                        handler.removeCallbacks(updateSilentFallback)
                        updateInstallPhase = UpdateInstallPhase.FINISHED
                    }
                    UpdateInstallReceiver.ACTION_INSTALL_INTERACTIVE ->
                        openInteractiveInstall(operation, info, apk, intent)
                    UpdateInstallReceiver.ACTION_INSTALL_FAILED ->
                        onUpdateInstallFailed(operation, info, apk, intent)
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            receiver,
            IntentFilter().apply {
                addAction(UpdateInstallReceiver.ACTION_INSTALL_FAILED)
                addAction(UpdateInstallReceiver.ACTION_INSTALL_INTERACTIVE)
                addAction(UpdateInstallReceiver.ACTION_INSTALL_FINISHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        updateFailureReceiver = receiver
        val appContext = applicationContext
        thread(name = "codexrm-update-install") {
            runCatching { UpdateChecker.install(appContext, apk, info.versionCode, requestId, silent) }
                .onSuccess {
                    if (!silent) return@onSuccess
                    handler.post {
                        if (operation != updateOperation || updateInstallPhase != UpdateInstallPhase.SILENT ||
                            updateInstallRequest != requestId
                        ) {
                            return@post
                        }
                        handler.postDelayed(updateSilentFallback, UpdateChecker.SILENT_INSTALL_FALLBACK_MS)
                    }
                }
                .onFailure { error ->
                    handler.post {
                        if (operation != updateOperation || isFinishing || isDestroyed) return@post
                        handler.removeCallbacks(updateSilentFallback)
                        cancelUpdateOperation()
                        showUpdateFailure("无法提交安装：${error.message ?: "系统安装器不可用"}") {
                            startUpdateDownload(info)
                        }
                    }
                }
        }
    }

    private fun onUpdateInstallFailed(
        operation: Int,
        info: UpdateChecker.VersionInfo,
        apk: File,
        intent: Intent,
    ) {
        if (updateInstallPhase == UpdateInstallPhase.FINISHED) return
        val statusCode = intent.getIntExtra(UpdateInstallReceiver.EXTRA_STATUS, -1)
        if (updateInstallRequest == ignoredAbortRequest && UpdateChecker.isUserCancelledInstall(statusCode)) {
            ignoredAbortRequest = ""
            return
        }
        if (updateInstallPhase == UpdateInstallPhase.HANDED_OFF) {
            if (!UpdateChecker.isUserCancelledInstall(statusCode)) return
            val message = intent.getStringExtra(UpdateInstallReceiver.EXTRA_MESSAGE) ?: "已取消安装更新"
            cancelUpdateOperation()
            showUpdateFailure(message) { startUpdateDownload(info) }
            return
        }
        handler.removeCallbacks(updateSilentFallback)
        if (updateInstallPhase == UpdateInstallPhase.SILENT &&
            UpdateChecker.shouldRetryWithInteractiveInstall(
                statusCode,
                silentAttempt = true,
                interactiveAlreadyStarted = false,
            )
        ) {
            updateInstallStatus?.text = "当前系统不支持静默安装，正在打开普通安装"
            submitPackageInstall(operation, info, apk, silent = false)
            return
        }
        val message = intent.getStringExtra(UpdateInstallReceiver.EXTRA_MESSAGE) ?: "更新安装失败"
        cancelUpdateOperation()
        showUpdateFailure(message) { startUpdateDownload(info) }
    }

    private fun openInteractiveInstall(
        operation: Int,
        info: UpdateChecker.VersionInfo,
        apk: File,
        intent: Intent,
    ) {
        if (updateInstallPhase == UpdateInstallPhase.HANDED_OFF || updateInstallPhase == UpdateInstallPhase.FINISHED) return
        handler.removeCallbacks(updateSilentFallback)
        val confirmation = UpdateInstallReceiver.interactiveConfirmation(intent)
        if (confirmation == null) {
            ignoredAbortRequest = updateInstallRequest
            UpdateChecker.abandonMySessions(applicationContext)
        }
        if (!handOffToSystemInstaller(operation, apk, confirmation)) {
            cancelUpdateOperation()
            showUpdateFailure("无法打开系统安装界面") { startUpdateDownload(info) }
        }
    }

    private fun fallBackFromSilentInstall() {
        if (updateInstallPhase != UpdateInstallPhase.SILENT) return
        val info = updateInstallInfo ?: return
        val apk = updateInstallApk ?: return
        val operation = updateInstallOperation
        if (operation != updateOperation || isFinishing || isDestroyed) return
        updateInstallStatus?.text = "当前系统不支持静默安装，正在打开普通安装"
        ignoredAbortRequest = updateInstallRequest
        UpdateChecker.abandonMySessions(applicationContext)
        if (!handOffToSystemInstaller(operation, apk, confirmation = null)) {
            submitPackageInstall(operation, info, apk, silent = false)
        }
    }

    private fun handOffToSystemInstaller(operation: Int, apk: File, confirmation: Intent?): Boolean {
        if (operation != updateOperation || isFinishing || isDestroyed) return false
        updateInstallPhase = UpdateInstallPhase.HANDED_OFF
        var opened = confirmation != null && openInstallConfirmation(confirmation)
        if (!opened) {
            if (confirmation != null) {
                ignoredAbortRequest = updateInstallRequest
                UpdateChecker.abandonMySessions(applicationContext)
            }
            opened = UpdateChecker.launchNormalPackageInstall(this, apk)
        }
        if (!opened) {
            updateInstallPhase = UpdateInstallPhase.IDLE
            return false
        }
        updateInstallStatus?.text = "请在系统界面确认安装"
        updateDialog?.setCancelable(true)
        return true
    }

    private fun openInstallConfirmation(confirmation: Intent): Boolean {
        if (runCatching { startActivity(Intent(confirmation)) }.isSuccess) return true
        return runCatching {
            startActivity(Intent(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
    }

    private fun renameCurrentThread() {
        val thread = currentThread ?: return
        renameThread(currentThreadId, thread.string("name"))
    }

    private fun renameThread(targetId: String, currentName: String) {
        val targetAgent = currentAgent()
        if (targetId.isEmpty() || loading) return

        val input = MiuixFieldView(this, "会话标题", currentName)
        val dialog = styledDialog(MiuixDialog.Builder(this)
            .setTitle("修改会话标题")
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null))
        val save = dialog.getButton(MiuixDialog.POSITIVE)
        save.setOnClickListener {
            val name = input.text.trim()
            if (name.isEmpty()) {
                input.error = "请输入会话标题"
                input.focusInput()
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

    private fun scheduleDeleteDevice(target: Device) {
        // Closing the Compose device popup inside the long-press callback detaches its
        // pointer node before the gesture ends and crashes, so the delete dialog never appears.
        handler.post {
            if (!isFinishing && !isDestroyed) confirmDeleteDevice(target)
        }
    }

    private fun confirmDeleteDevice(target: Device) {
        devicePopup?.dismiss()
        styledDialog(MiuixDialog.Builder(this).setTitle("删除电脑？")
            .setMessage("将从此手机移除“${target.serverName}”，有网时会尝试解除授权，离线时等恢复网络再上报。上报失败不影响删除，再次连接需要重新配对。")
            .setPositiveButton("删除设备") { _, _ -> deleteDevice(target) }
            .setNegativeButton("取消", null))
    }

    private fun deleteDevice(target: Device) {
        // Ignore an outdated dialog if this computer has since been paired again.
        if (devices.none { it.agentId == target.agentId && it.token == target.token }) return
        val selected = device?.agentId == target.agentId
        if (selected) {
            settingsPage?.dismiss()
            settingsPage = null
            saveDraft()
            disconnect()
        }
        conversationCache.removeAgent(target.agentId)
        conversationPrefs.edit().remove("last:${target.agentId}").remove("seen:${target.agentId}").apply()
        TaskMonitor.enable(this, target.agentId, false)
        devices.removeAll { it.agentId == target.agentId }
        expiredAgentIds.remove(target.agentId)
        store.saveRemoval(devices, target)
        if (selected) {
            device = devices.firstOrNull { it.agentId !in expiredAgentIds }; store.selectedId = device?.agentId.orEmpty(); restoreThreadId = ""
            if (device == null) { resetConversation(); showPair() } else showMain(reset = true)
        } else {
            if (mainBuilt) renderDevices()
            settingsPage?.refreshActionSummaries()
        }
        toast(if (DeviceRevokeJob.schedule(this)) "设备已删除" else "设备已删除，暂未能安排解除授权上报")
    }

    private fun showRevocationFailure() {
        if (store.consumeRevocationFailure()) toast("解除授权上报失败，电脑仍已从此手机删除")
    }

    private fun threadDeletionReason(id: String): String? = when {
        !online || !loaded -> "设备连接后可以归档对话"
        sending || loading -> "请等待当前请求完成"
        id in deletingThreads -> "正在归档此对话"
        threads.none { it.id == id } && pinnedThreads.none { it.id == id } -> "对话已不在列表中"
        threads.any { it.id == id && it.active } || (currentThreadId == id && running) -> "请先停止正在运行的对话"
        else -> null
    }

    private fun confirmArchiveThread(id: String, title: String) {
        threadDeletionReason(id)?.let { toast(it); return }
        val currentGeneration = generation
        styledDialog(MiuixDialog.Builder(this).setTitle("归档对话？")
            .setMessage("将“$title”移到已归档会话，之后可以恢复。")
            .setPositiveButton("归档") { _, _ ->
                if (currentGeneration != generation) return@setPositiveButton
                threadDeletionReason(id)?.let { toast(it); return@setPositiveButton }
                deletingThreads.add(id); updateSendState()
                rpc("thread/archive", JSONObject().put("threadId", id)) { _, error ->
                    if (currentGeneration != generation) return@rpc
                    deletingThreads.remove(id); updateSendState()
                    if (error != null) toast("归档失败：${error.message}")
                    else { removeArchivedThread(id); loadThreads(); loadConversationPins(); toast("对话已归档") }
                }
            }.setNegativeButton("取消", null))
    }

    private fun removeArchivedThread(id: String) {
        if (id.isEmpty()) return
        conversationCache.remove(currentAgent(), id)
        if (conversationPrefs.getString("last:${currentAgent()}", null) == id) {
            conversationPrefs.edit().remove("last:${currentAgent()}").apply()
        }
        // Invalidate pages requested before the archive event or response.
        threadListVersion++; threadListLoading = false
        threadList.remove(id)
        pinnedThreads = pinnedThreads.filterNot { it.id == id }
        messageQueues.remove(id)
        val resolved = requests.filterValues { it.objectAt("params").string("threadId") == id }.keys.toSet()
        resolved.forEach { requests.remove(it); requestScopes.remove(it); responding.remove(it) }
        if (currentThreadId == id) newChat()
        renderWorkspaces(); renderApproval(); renderQueue()
    }

    private fun restylePairPage() {
        val oldCode = codeInput?.text?.toString()
        val oldServer = serverInput?.text?.toString()
        renderPairPage()
        if (oldCode != null) codeInput?.setText(oldCode)
        if (oldServer != null) serverInput?.setText(oldServer)
    }

    private fun setDarkTheme(dark: Boolean) {
        if (store.darkTheme == dark) return
        store.darkTheme = dark
        Ui.configure(this, dark)
        if (!mainVisible) {
            restylePairPage()
            return
        }
        invalidateBackPreview()
        saveDraft(); val savedMessages = if (mainBuilt) adapter.snapshot() else emptyList()
        val reopenTransfers = transferPage != null
        closeTransfers()
        mainBuilt = false; buildMain(); adapter.replace(savedMessages); mainVisible = true; setContentView(mainRoot)
        if (reopenTransfers) openTransfers()
        restoreDraft(); renderDevices(); renderWorkspaces(); renderApproval(); renderQueue(); refreshModelChoices(); updateControls()
        updateSidebarAccessibility()
        updateBackHandling()
    }

    private fun activeConnectionStatus(): DirectStatus? = client?.directStatus?.takeIf { online && device?.agentId != LocalCodex.AGENT }

    private fun updateConnectionIndicator() {
        if (!mainBuilt) return
        val status = activeConnectionStatus()
        updateSidebarConnectionButton(status)
        connectionButton.visibility = when {
            currentThreadId.isNotEmpty() -> View.GONE
            status != null -> View.VISIBLE
            else -> View.GONE
        }
        if (status == null) {
            connectionDialog?.dismiss()
            return
        }
        updateConnectionButton(connectionButton, status)
        connectionDialog?.let { updateConnectionDialog(it, status) }
    }

    private fun updateSidebarConnectionButton(status: DirectStatus?) {
        val button = sidebarConnectionButton ?: return
        if (status == null) {
            (button.getChildAt(0) as IconView).apply {
                setIcon("check")
                setColor(Ui.GREEN)
            }
            button.contentDescription = null
            button.tooltipText = null
        } else {
            updateConnectionButton(button, status)
        }
        button.isClickable = status != null
        button.isFocusable = status != null
    }

    private fun updateConnectionButton(button: FrameLayout, status: DirectStatus) {
        val connectionIcon = button.getChildAt(0) as IconView
        val direct = status is DirectStatus.Connected
        connectionIcon.setIcon(if (direct) "zap" else "relay")
        connectionIcon.setColor(if (direct) Ui.GREEN else Ui.MUTED)
        val description = when (status) {
            is DirectStatus.Connected -> if (status.lan) "局域网直连，测试延迟" else "P2P 直连，测试延迟"
            else -> "RM 中继，查看直连状态"
        }
        button.contentDescription = description
        button.tooltipText = description
    }

    private fun updateConnectionDialog(dialog: MiuixDialog, status: DirectStatus) {
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
        val status = activeConnectionStatus() ?: return
        connectionDialog?.dismiss()
        val dialog = MiuixDialog.Builder(this)
            .setNegativeButton("诊断") { _, _ -> showNATDiagnostic() }
            .setPositiveButton("关闭", null)
            .create()
        connectionDialog = dialog
        dialogShowsDirect = false
        dialog.setOnDismissListener { if (connectionDialog === dialog) connectionDialog = null }
        updateConnectionDialog(dialog, status)
        dialog.show()
    }

    private fun showNATDiagnostic() {
        val connectedClient = client ?: return
        val progress = "正在同时测试手机和 Agent 的 RFC 3489 / RFC 5780…"
        val dialog = MiuixDialog.Builder(this)
            .setTitle("NAT 诊断")
            .setMessage(progress)
            .setNegativeButton("重新诊断", null)
            .setPositiveButton("关闭", null)
            .create()
        val retry = dialog.getButton(MiuixDialog.NEGATIVE)
        var latestReport: String? = null
        val enableRetry = Runnable {
            if (dialog.isShowing && !isDestroyed) {
                latestReport?.let(dialog::setMessage)
                retry.isEnabled = true
            }
        }
        val runDiagnosis: () -> Unit = {
            handler.removeCallbacks(enableRetry)
            if (client !== connectedClient) {
                retry.isEnabled = false
                dialog.setMessage("当前连接已变更，请关闭后从连接状态重新打开诊断。")
            } else {
                retry.isEnabled = false
                latestReport = null
                dialog.setMessage(progress)
                connectedClient.diagnoseNAT { result ->
                    if (dialog.isShowing && !isDestroyed) {
                        val waitMs = (result.retryAtMs - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0)
                        val hint = if (waitMs > 0) "\n\n冷却结束后可重新诊断。" else ""
                        val report = result.displayText()
                        latestReport = report
                        dialog.setMessage(report + hint)
                        if (waitMs == 0L) enableRetry.run() else handler.postDelayed(enableRetry, waitMs)
                    }
                }
            }
        }
        retry.setOnClickListener(runDiagnosis)
        dialog.setOnDismissListener { handler.removeCallbacks(enableRetry) }
        dialog.show()
        runDiagnosis()
    }

    private fun performHomeAction(action: HomeAction) {
        when (action) {
            HomeAction.START_INPUT -> focusComposer()
            HomeAction.CHOOSE_WORKSPACE -> showWorkspaceChoices()
            HomeAction.CHOOSE_DEVICE -> showDevices(welcomeSecondary.takeIf { it.isShown } ?: deviceButton)
            HomeAction.RETRY_CONNECTION -> {
                if (currentAgent() == LocalCodex.AGENT) chooseLocalCodex()
                else { disconnect(); connect(); toast("正在重新连接设备") }
            }
            HomeAction.RETRY_SYNC -> {
                if (online && !bootstrapping) bootstrap() else {
                    disconnect()
                    connect()
                }
            }
        }
    }

    private fun focusComposer() {
        if (composer.requestFocus()) composer.post {
            if (mainVisible && composer.isFocused) WindowCompat.getInsetsController(window, composer).show(WindowInsetsCompat.Type.ime())
        }
    }

    private fun updateWelcome() {
        if (welcome.visibility != View.VISIBLE) return
        if (online && loaded) { welcome.visibility = View.INVISIBLE; return }
        val guidance = InteractionCopy.home(online, bootstrapping || online && !loaded, currentCwd)
        welcomeDescription.text = guidance.description
        welcomePrimary.visibility = if (guidance.connectionActions) View.VISIBLE else View.GONE
        welcomePrimary.text = guidance.primaryLabel
        welcomePrimary.setOnClickListener { performHomeAction(guidance.primaryAction) }
        welcomeSecondary.visibility = if (guidance.connectionActions) View.VISIBLE else View.GONE
        welcomeSecondary.text = guidance.secondaryLabel
        welcomeSecondary.setOnClickListener { performHomeAction(guidance.secondaryAction) }
    }

    private fun updateControls() {
        if (!mainBuilt) return
        renderBootstrapState(); renderOutbox(); updateModelButton()
        updateConnectionIndicator()
        val notice = if (cachedAt > 0) {
            val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(cachedAt))
            "本机记录 · $time · 等待同步"
        } else if (currentAgent() == LocalCodex.AGENT) {
            when (connectionState) {
                ConnectionState.ONLINE -> ""
                ConnectionState.CONNECTING, ConnectionState.RECONNECTING -> "正在启动本机 Codex…"
                ConnectionState.OFFLINE -> "本机 Codex 未运行，点击重试。"
            }
        } else if (!online && getSystemService(android.net.ConnectivityManager::class.java).activeNetwork == null) {
            "手机未连接网络 · 查看连接状态"
        } else when (connectionState) {
            ConnectionState.CONNECTING -> "正在连接中继…"
            ConnectionState.RECONNECTING -> "中继连接已断开，正在自动重连…"
            ConnectionState.OFFLINE -> "中继已连接，等待电脑 Agent 上线。"
            ConnectionState.ONLINE -> ""
        }
        if (connectionNotice.text.toString() != notice) connectionNotice.text = notice
        connectionNotice.visibility = if (device != null && started && notice.isNotEmpty()) View.VISIBLE else View.GONE
        connectionNotice.setOnClickListener { showConnectionActions() }
        updateDiffButton()
        val currentDeviceName = device?.serverName?.takeIf { it.isNotBlank() } ?: "连接设备"
        deviceLabel.text = currentDeviceName
        deviceButton.contentDescription = "选择设备，当前设备：$currentDeviceName，${if (online) "已连接" else "离线"}"
        connectionDot.background = Ui.rounded(if (online) Ui.GREEN else Ui.DIM, Ui.dp(this, 3).toFloat())
        val hasOpenThread = currentThreadId.isNotEmpty()
        val inTransfers = transferPage != null
        deviceButton.visibility = if (hasOpenThread && !inTransfers) View.GONE else View.VISIBLE
        topbarTitle.text = when {
            inTransfers -> "文件传输助手"
            hasOpenThread -> currentThread?.string("name").orEmpty().ifBlank { "无题" }
            else -> "CodexRM"
        }
        val canRename = hasOpenThread && !loading && !inTransfers
        topbarTitle.isClickable = canRename
        topbarTitle.isFocusable = canRename
        topbarTitle.tooltipText = if (canRename) "修改会话标题" else null
        cwdLabel.text = currentCwd.ifEmpty { "点击设置工作目录" }
        workspaceButton.contentDescription = "选择工作目录，当前目录：${currentCwd.ifEmpty { "未设置" }}"
        workspaceButton.isEnabled = online && !sending && !loading
        welcome.visibility = if (currentThread == null && adapter.count == 0) View.VISIBLE else View.GONE
        updateWelcome()
        messageList.visibility = if (currentThread == null && adapter.count == 0) View.GONE else View.VISIBLE
        activityRow.visibility = if (running) View.VISIBLE else View.GONE
        activityText.text = if (online) "Codex 正在处理" else "等待同步任务状态"
        composer.isEnabled = !readOnly || cachedAt > 0
        val modelBlockReason = InteractionCopy.modelBlockReason(online, readOnly, running, sending, loading)
        modelButton.isEnabled = true
        modelButton.alpha = if (modelBlockReason == null) 1f else .65f
        modelButton.contentDescription = (selectedModelOption?.let { "选择模型，当前为${it.providerName} ${it.displayName}" } ?: "选择模型") +
            modelBlockReason?.let { "，当前不可用：$it" }.orEmpty()
        updateSendState()
        schedulePendingShare()
        if (pendingOpenThread != null && !loading && !sending && !bootstrapping) {
            handler.removeCallbacks(processPendingOpen)
            handler.post(processPendingOpen)
        }
    }

    private fun updateSendState() {
        if (!mainBuilt || !::sendButton.isInitialized) return
        val hasPayload = composer.text.isNotBlank() || attachmentFiles.isNotEmpty()
        val editingQueuedMessage = editingQueueDraft == composerBinding
        val sendState = SendUiState(
            hasPayload = hasPayload,
            online = online,
            loaded = loaded,
            sending = sending,
            loading = loading,
            preparingAttachments = preparingAttachments,
            readOnly = readOnly,
            editingQueuedMessage = editingQueuedMessage,
            running = running,
            messageQueue = info.optBoolean("messageQueue"),
            hasAttachments = attachmentFiles.isNotEmpty(),
            attachmentsSupported = info.has("attachments"),
        )
        val localBlock = if (online && loaded && !readOnly) localModelBlockReason() else null
        val blockReason = when {
            providerUnavailable -> providerSwitchHint
            else -> localBlock ?: InteractionCopy.sendBlockReason(sendState)
        }
        steerButton.visibility = if (running) View.VISIBLE else View.GONE
        steerButton.isEnabled = running && !providerUnavailable && localBlock == null && InteractionCopy.sendBlockReason(sendState, steer = true) == null
        val showContinue = canContinueTurn()
        continueButton.visibility = if (showContinue) View.VISIBLE else View.GONE
        continueButton.isEnabled = showContinue && online && loaded && !loading && !sending && currentThreadId !in deletingThreads
        continueButton.alpha = if (continueButton.isEnabled) 1f else .35f
        sendButton.visibility = View.VISIBLE
        sendButton.isEnabled = blockReason == null
        sendButton.alpha = if (sendButton.isEnabled) 1f else .35f
        val sendAction = when {
            running -> "排队发送"
            else -> "发送消息"
        }
        sendButton.contentDescription = sendAction + blockReason?.let { "，当前不可用：$it" }.orEmpty()
        sendHint.text = blockReason.orEmpty()
        sendHint.visibility = if (blockReason != null && (hasPayload || readOnly || providerUnavailable || localBlock != null || preparingAttachments || sending)) View.VISIBLE else View.GONE
        stopButton.visibility = if (running && !readOnly) View.VISIBLE else View.GONE
        // Keep the button clickable when merely offline or unsupported so a tap explains
        // why (a disabled button gave Android 12 users silent "no response"); dim it instead.
        attachButton.isEnabled = !sending && !loading && !readOnly && !preparingAttachments && !editingQueuedMessage
        val attachReady = attachButton.isEnabled && online && loaded && info.has("attachments")
        attachButton.alpha = if (attachReady) 1f else .35f
        if (!attachReady) attachmentSheet?.dismiss()
        stopButton.isEnabled = !readOnly && online && !sending && !loading
    }

    private fun persistOutbox() {
        if (outboxOwnerAgentId.isNotEmpty() && !store.savePendingSends(outboxOwnerAgentId, outbox.values())) {
            toast("无法保存待确认的消息，请保留此页面并检查存储空间")
        }
        if (mainBuilt) renderOutbox()
    }

    private fun renderBootstrapState() {
        if (!mainBuilt) return
        bootstrapArea.removeAllViews()
        if (!online || (!bootstrapping && bootstrapError.isEmpty())) {
            bootstrapArea.visibility = View.GONE
            return
        }
        bootstrapArea.visibility = View.VISIBLE
        val card = Ui.column(this).apply {
            Ui.padChatCard(this)
            background = Ui.chatCardBackground(context, adapter.bubbleOpacity, error = bootstrapError.isNotEmpty())
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
            row.addView(Ui.text(this, "$target\n$preview", 12f, Ui.MUTED).apply { maxLines = 3 })
            outboxArea.addView(row, matchWidth(bottom = 8))
        }
    }

    private fun showPendingSend(id: String) {
        val value = outbox.get(id) ?: return
        if (id == activePendingSendId && value.stage == PendingSendStage.WAITING) {
            toast("请求仍在等待设备确认")
            return
        }
        val dialog = MiuixDialog.Builder(this)
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
        attachmentSheet?.dismiss()
        markupDialog?.dismiss()
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

    private fun jumpToLatest() {
        if (adapter.count == 0) return
        messageList.restoreReadingPosition(ChatListView.ReadingPosition(null))
        latestButton.visibility = View.GONE
    }

    private fun openCachedThread(id: String): Boolean {
        val cached = conversationCache.read(currentAgent(), id) ?: return false
        val thread = cached.optJSONObject("thread") ?: return false
        if (thread.string("id") != id) return false
        currentThread = thread
        currentThreadId = id
        restoreThreadId = id
        currentCwd = thread.string("cwd").ifBlank { currentCwd }
        clearTranscript()
        cachedAt = cached.optLong("savedAt")
        running = false
        activeTurnId = ""
        readOnly = true
        historyCursor = null
        val turns = thread.arrayAt("turns")
        for (index in 0 until turns.length()) turns.optJSONObject(index)?.let(::renderTurn)
        latestDiff = cached.string("diff").ifBlank { latestDiff }
        rebindComposer(ComposerBinding(currentAgent(), id, currentCwd))
        restoreDraft(composerRestoreToken)
        acceptShareForThread(id)
        renderApproval()
        renderQueue()
        updateControls()
        jumpToLatest()
        return true
    }

    private fun cacheConversation(thread: JSONObject) {
        val id = thread.string("id")
        if (id.isBlank() || id != currentThreadId) return
        val snapshot = JSONObject().put("thread", thread)
            .put("savedAt", System.currentTimeMillis()).put("diff", latestDiff)
        conversationCache.write(currentAgent(), id, snapshot.toString())
    }

    private fun finishReasoning(id: String, interrupted: Boolean, duration: Long = -1) {
        val start = reasoningStarted.remove(id)
        val elapsed = if (duration >= 0) duration else start?.let { android.os.SystemClock.elapsedRealtime() - it }
        val status = when {
            interrupted -> "已停止"
            elapsed != null -> "思考 ${elapsed / 1000} 秒"
            else -> "思考完成"
        }
        reasoningStatus[id] = status
        adapter.message(id)?.change(status = status)
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (!mainVisible || !mainBuilt || !event.hasModifiers(KeyEvent.META_CTRL_ON)) {
            return super.onKeyShortcut(keyCode, event)
        }
        val action: () -> Unit = when (keyCode) {
            KeyEvent.KEYCODE_N -> ::openNewChatEntry
            KeyEvent.KEYCODE_K -> ::openSearchEntry
            KeyEvent.KEYCODE_F -> if (adapter.count > 0) ::searchConversation else return super.onKeyShortcut(keyCode, event)
            else -> return super.onKeyShortcut(keyCode, event)
        }
        if (event.repeatCount == 0) action()
        return true
    }

    override fun onProvideKeyboardShortcuts(groups: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        super.onProvideKeyboardShortcuts(groups, menu, deviceId)
        if (!mainVisible || !mainBuilt) return
        val shortcuts = mutableListOf(
            KeyboardShortcutInfo("新建会话", KeyEvent.KEYCODE_N, KeyEvent.META_CTRL_ON),
            KeyboardShortcutInfo("搜索会话", KeyEvent.KEYCODE_K, KeyEvent.META_CTRL_ON),
        )
        if (adapter.count > 0) shortcuts += KeyboardShortcutInfo("搜索当前会话", KeyEvent.KEYCODE_F, KeyEvent.META_CTRL_ON)
        groups.add(KeyboardShortcutGroup("CodexRM", shortcuts))
    }

    private fun openNewChatEntry() {
        if (!mainVisible || !mainBuilt) return
        if (currentCwd.isEmpty()) currentCwd = store.selectedWorkspace(currentAgent())
        newChat(showKeyboard = true)
    }

    private fun openSearchEntry() {
        if (!mainVisible) return
        closeSidebar()
        showAllConversationSearch()
    }

    private fun openTransfers() {
        if (!mainVisible || !mainBuilt) return
        if (sidebarOpen) closeSidebar()
        if (transferPage != null) { closeTransfers(); return }
        if (device == null) { toast("请先配对一台电脑"); return }
        composer.clearFocus()
        WindowCompat.getInsetsController(window, mainRoot).hide(WindowInsetsCompat.Type.ime())
        chatBody.visibility = View.GONE
        val page = transfers.createPage()
        transferPage = page
        contentRoot.addView(page, LinearLayout.LayoutParams(-1, 0, 1f))
        topbar.visibility = View.VISIBLE
        updateControls()
        updateBackHandling()
    }

    private fun closeTransfers() {
        val page = transferPage ?: return
        transferPage = null
        if (page.hasFocus()) {
            page.findFocus()?.clearFocus()
            WindowCompat.getInsetsController(window, mainRoot).hide(WindowInsetsCompat.Type.ime())
        }
        contentRoot.removeView(page)
        transfers.detachPage()
        chatBody.visibility = View.VISIBLE
        topbar.visibility = if (readingMode) View.GONE else View.VISIBLE
        updateControls()
        updateBackHandling()
    }


    private fun acceptExternalIntent(intent: Intent) {
        when (intent.action) {
            ChatEntryPoints.NEW_CHAT -> openNewChatEntry()
            ChatEntryPoints.SEARCH -> openSearchEntry()
            TaskMonitor.OPEN -> {
                val target = devices.firstOrNull { it.agentId == intent.getStringExtra(TaskMonitor.DEVICE) }
                    ?: return toast("找不到此会话绑定的电脑")
                val thread = intent.getStringExtra(TaskMonitor.THREAD).orEmpty()
                if (target.agentId != currentAgent()) selectDevice(target, closeSidebarAfter = true)
                if (thread.isNotEmpty()) {
                    restoreThreadId = thread
                    if (online && loaded && !loading && !sending) openThread(thread)
                    else if (!online && mainVisible) openCachedThread(thread)
                }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_PROCESS_TEXT -> {
                pendingShares.addLast(intent)
                reviewSharedDraft()
            }
        }
    }

    private fun canReviewShare() = mainVisible && mainBuilt && resumed && !isFinishing && !isDestroyed &&
        shareDialog == null && !sending && !loading && !preparingAttachments

    private fun schedulePendingShare() {
        if (pendingShares.isEmpty() || !canReviewShare()) return
        handler.removeCallbacks(reviewPendingShare)
        handler.post(reviewPendingShare)
    }

    private fun showShareDialog(builder: MiuixDialog.Builder): MiuixDialog = styledDialog(builder).also { dialog ->
        shareDialog = dialog
        dialog.setOnDismissListener {
            if (shareDialog === dialog) shareDialog = null
            schedulePendingShare()
        }
    }

    @Suppress("DEPRECATION")
    private fun reviewSharedDraft() {
        if (!canReviewShare()) return
        val share = pendingShares.removeFirstOrNull() ?: return
        val text = runCatching {
            share.getCharSequenceExtra(if (share.action == Intent.ACTION_PROCESS_TEXT) Intent.EXTRA_PROCESS_TEXT else Intent.EXTRA_TEXT)
                ?.toString().orEmpty().take(60_000)
        }.getOrDefault("")
        val uris = linkedSetOf<Uri>()
        share.clipData?.let { clips ->
            for (index in 0 until clips.itemCount) clips.getItemAt(index).uri?.let(uris::add)
        }
        when (share.action) {
            Intent.ACTION_SEND_MULTIPLE -> runCatching {
                share.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
                    .orEmpty().filterIsInstance<Uri>().forEach(uris::add)
            }
            Intent.ACTION_SEND -> runCatching {
                (share.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let(uris::add)
            }
        }
        val attachments = uris.filter { it.scheme == "content" }
        if (text.isBlank() && attachments.isEmpty()) {
            toast("没有可导入的分享内容")
            schedulePendingShare()
            return
        }
        val content = SharedContent(text, attachments)
        if (store.shareToNewDraft && (attachments.isEmpty() || currentCwd.isNotEmpty() || store.selectedWorkspace(currentAgent()).isNotEmpty())) {
            addToNewSharedDraft(content)
            schedulePendingShare()
            return
        }
        showShareDialog(MiuixDialog.Builder(this)
            .setTitle("分享至 CodexRM")
            .setItems(arrayOf("加入新会话草稿", "选择已有会话", "发给电脑")) { _, index ->
                when (index) {
                    0 -> addToNewSharedDraft(content)
                    1 -> showShareConversations(content)
                    2 -> showShareComputers(content)
                }
            }.setNegativeButton("取消", null))
    }

    private fun addToNewSharedDraft(content: SharedContent) {
        if (sending || loading || preparingAttachments) { toast("请等待当前操作完成"); return }
        restoreThreadId = ""
        if (currentCwd.isEmpty()) currentCwd = store.selectedWorkspace(currentAgent())
        if (content.files.isNotEmpty() && currentCwd.isEmpty()) {
            toast("连接电脑并选择工作目录后即可导入附件")
            return
        }
        closeTransfers()
        newChat(showKeyboard = false)
        if (sidebarOpen) closeSidebar()
        addToDraft(content)
    }

    private fun showShareConversations(content: SharedContent) {
        val current = currentThread?.takeIf { it.string("id").isNotBlank() }?.let(ProtocolFormatter::thread)
        val targetThreads = (listOfNotNull(current) + threads).distinctBy { it.id }
        lateinit var dialog: MiuixDialog
        dialog = showShareDialog(
            MiuixDialog.Builder(this).setTitle("加入对话").setContent {
                MiuixShareTargets(targetThreads) select@{ thread ->
                    if (sending || loading || preparingAttachments) {
                        toast("请等待当前操作完成")
                        return@select
                    }
                    if (thread != null && thread.id != currentThreadId && !online &&
                        conversationCache.read(currentAgent(), thread.id) == null) {
                        toast("电脑离线，暂时无法打开该对话")
                        return@select
                    }
                    dialog.dismiss()
                    when {
                        thread == null -> addToNewSharedDraft(content)
                        thread.id == currentThreadId -> addToDraft(content)
                        else -> {
                            shareForThread = ComposerBinding(currentAgent(), thread.id, thread.cwd) to content
                            openThread(thread.id)
                        }
                    }
                }
            }.setNegativeButton("取消", null),
        )
    }

    private fun acceptShareForThread(threadId: String) {
        val (target, content) = shareForThread ?: return
        if (target.agentId != currentAgent() || target.threadId != threadId) return
        shareForThread = null
        addToDraft(content)
    }

    private fun addToDraft(content: SharedContent) {
        if (content.text.isNotBlank()) {
            setComposerText(listOf(composer.text.toString(), content.text).filter(String::isNotBlank).joinToString("\n\n"))
            saveDraft()
        }
        if (content.files.isNotEmpty()) stageAttachments(attachmentDraftKey(), content.files)
        updateControls()
        toast("已加入对话草稿")
    }

    private fun showShareComputers(content: SharedContent) {
        val targets = devices.filterNot { it.agentId in expiredAgentIds }
        if (targets.isEmpty()) { toast("请先配对电脑"); return }
        showShareDialog(MiuixDialog.Builder(this).setTitle("发给电脑")
            .setItems(targets.map { it.serverName.ifBlank { "已配对电脑" } }.toTypedArray()) { _, index ->
                sendSystemShare(targets[index], content)
            }.setNegativeButton("取消", null))
    }

    private fun sendSystemShare(target: Device, content: SharedContent) {
        transfers.enqueue(target, content)
        if (target === device && transferPage == null) openTransfers()
    }

    private fun showTools() {
        closeSidebar()
        val actions = linkedMapOf<String, () -> Unit>()
        if (adapter.count > 0) actions["搜索当前会话"] = ::searchConversation
        if (currentThreadId.isNotEmpty()) actions["查看改动"] = ::showConversationDiff
        if (info.optBoolean("conversationTools")) {
            actions["搜索所有会话正文"] = ::showAllConversationSearch
            actions["已归档会话"] = ::showArchivedConversations
            if (currentThreadId.isNotEmpty()) actions["关联分支"] = { showRelatedConversations(currentThreadId) }
            if (currentThreadId.isNotEmpty() && currentThreadId == archivedThreadId) {
                actions["恢复当前会话"] = { restoreConversation(currentThreadId) { openThread(it) } }
            }
        }
        actions["连接与重试"] = ::showConnectionActions
        styledDialog(MiuixDialog.Builder(this).setTitle("工具")
            .setItems(actions.keys.toTypedArray()) { _, which -> actions.values.elementAt(which).invoke() })
    }

    private fun toggleTaskMonitor(enabled: Boolean) {
        if (currentAgent().isEmpty()) return
        TaskMonitor.enable(this, currentAgent(), enabled)
        if (!enabled) {
            TaskMonitor.stop(this)
        } else if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 907)
        } else toast("任务通知已开启，有任务运行时退到后台会继续监听")
    }

    private fun searchConversation() {
        val query = Ui.input(this, "搜索消息正文").apply { isSingleLine = true }
        val results = Ui.column(this)
        val controls = Ui.column(this, false)
        val resultList = ScrollView(this).apply { addView(results) }
        val panel = Ui.column(this).apply {
            setPadding(Ui.dp(context, 20), 0, Ui.dp(context, 20), 0)
            addView(query, matchWidth())
            addView(controls, matchWidth())
            addView(resultList, LinearLayout.LayoutParams(-1, Ui.dp(context, 300)))
        }
        val dialog = styledDialog(MiuixDialog.Builder(this).setTitle("搜索当前会话").setView(panel)
            .setNegativeButton("关闭", null))
        var selected = -1
        fun goTo(hit: ChatMessage) {
            adapter.reveal(hit.id)
            val position = adapter.positionOf(hit.id)
            if (position < 0) return
            adapter.notifyDataSetChanged()
            messageList.scrollToMessage(position)
        }
        fun matches() {
            results.removeAllViews()
            controls.removeAllViews()
            selected = -1
            val term = query.text.toString().trim()
            if (term.isEmpty()) return
            val hits = adapter.snapshot().filter { it.body.contains(term, ignoreCase = true) }.take(100)
            fun cycle(by: Int) {
                if (hits.isEmpty()) return
                if (selected >= 0) results.getChildAt(selected).setBackgroundColor(Color.TRANSPARENT)
                selected = if (selected < 0 && by < 0) hits.lastIndex else (selected + by + hits.size) % hits.size
                val row = results.getChildAt(selected)
                row.setBackgroundColor(Ui.SURFACE_ALT)
                resultList.smoothScrollTo(0, row.top)
                goTo(hits[selected])
            }
            if (hits.isNotEmpty()) {
                controls.addView(Ui.iconButton(this, "left", "上一个匹配项", 40) { cycle(-1) })
                controls.addView(Ui.iconButton(this, "right", "下一个匹配项", 40) { cycle(1) })
                controls.addView(Ui.text(this, "${hits.size} 条匹配", 12f, Ui.MUTED).apply { gravity = Gravity.CENTER_VERTICAL },
                    LinearLayout.LayoutParams(-2, Ui.dp(this, 40)))
            }
            if (hits.isEmpty()) results.addView(Ui.text(this, "已加载的消息中没有匹配项", 14f, Ui.MUTED), matchWidth(top = 12))
            if (historyCursor != null && online) {
                val older = Ui.button(this, "继续搜索更早的消息") {
                    loadOlderTurns { if (dialog.isShowing) matches() }
                }
                results.addView(older, matchWidth(top = 8))
            }
            for ((index, hit) in hits.withIndex()) {
                val offset = hit.body.indexOf(term, ignoreCase = true)
                val start = (offset - 36).coerceAtLeast(0)
                val excerpt = hit.body.substring(start, (offset + term.length + 84).coerceAtMost(hit.body.length))
                val highlighted = android.text.SpannableString(excerpt)
                highlighted.setSpan(android.text.style.BackgroundColorSpan(0x5575b7ff), offset - start,
                    offset - start + term.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                results.addView(Ui.text(this, "", 14f).apply {
                    text = highlighted
                    setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 10))
                    setOnClickListener {
                        dialog.dismiss()
                        goTo(hit)
                    }
                }, index, matchWidth())
            }
        }
        query.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = matches()
            override fun afterTextChanged(text: android.text.Editable?) = Unit
        })
    }

    private fun loadConversationPins() {
        if (!online || !info.optBoolean("conversationTools")) return
        val deviceGeneration = generation
        val request = ++pinRequestVersion
        rpc("codexrm/thread/pins") { page, _ ->
            if (!active(deviceGeneration) || request != pinRequestVersion || page == null) return@rpc
            val ids = page.arrayAt("ids")
            pinnedThreadIds = (0 until ids.length()).map { ids.optString(it) }.toSet()
            val data = page.arrayAt("data")
            pinnedThreads = (0 until data.length()).mapNotNull { data.optJSONObject(it)?.let(ProtocolFormatter::thread) }
            renderWorkspaces()
        }
    }

    private fun sidebarThreadActions(thread: ThreadSummary): Map<String, () -> Unit> {
        val actions = linkedMapOf<String, () -> Unit>("重命名" to { renameThread(thread.id, thread.title) })
        if (info.optBoolean("conversationTools")) {
            val pinned = thread.id in pinnedThreadIds
            actions[if (pinned) "取消置顶" else "置顶会话"] = {
                val deviceGeneration = generation
                val params = JSONObject().put("threadId", thread.id).put("pinned", !pinned)
                rpc("codexrm/thread/pin", params) { _, error ->
                    if (!active(deviceGeneration)) return@rpc
                    if (error != null) toast(error.message) else loadConversationPins()
                }
            }
            actions["关联分支"] = { showRelatedConversations(thread.id) }
        }
        if (ChatEntryPoints.canPinThread(this)) {
            val agent = currentAgent()
            actions["添加到主屏幕"] = {
                if (!ChatEntryPoints.pinThread(this, agent, thread.id, thread.title)) toast("桌面暂时无法添加此快捷方式")
            }
        }
        actions["归档"] = { confirmArchiveThread(thread.id, thread.title) }
        return actions
    }

    private fun showSidebarThreadActions(thread: ThreadSummary) {
        val actions = sidebarThreadActions(thread)
        styledDialog(MiuixDialog.Builder(this).setTitle(thread.title)
            .setItems(actions.keys.toTypedArray()) { _, index -> actions.values.elementAt(index).invoke() })
    }

    private fun showAllConversationSearch() {
        val owner = device
        val version = generation
        fun sameDevice() = version == generation && owner === device
        referenceSearch?.dismiss()
        referenceSearch = ReferenceSearch(this, local = { query ->
            if (!sameDevice()) emptyList() else buildList {
                fun matches(vararg values: String) = values.any { it.contains(query, ignoreCase = true) }
                (threads + pinnedThreads).distinctBy { it.id }.filter { matches(it.title, it.cwd) }.take(60).forEach { entry ->
                    add(SearchResult("chat:${entry.id}", SearchKind.CHAT, entry.title, entry.cwd) {
                        if (sameDevice() && !loading && !sending) openThread(entry.id)
                    })
                }
                val projects = (workspaces + threads.mapNotNull { it.cwd.takeIf(String::isNotBlank)?.let { path -> Workspace(folderName(path), path) } }).distinctBy { pathKey(it.path) }
                projects.filter { matches(it.name, it.path) }.take(30).forEach { project ->
                    add(SearchResult("project:${project.path}", SearchKind.PROJECT, project.name, project.path) {
                        if (sameDevice()) selectWorkspace(project.path)
                    })
                }
                if (owner != null) transfers.searchableEntries(owner).filter { matches(it.name) }.take(60).forEach { entry ->
                    val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    val kind = if (ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "heic", "avif", "bmp", "tiff")) SearchKind.IMAGE else SearchKind.FILE
                    add(SearchResult("file:${entry.id}", kind, entry.name, "文件传输助手 · ${owner.serverName}") { if (sameDevice() && transferPage == null) openTransfers() })
                }
            }
        }, fetch = { query, cursor, complete ->
            if (!active(version) || !info.optBoolean("conversationTools")) {
                complete(emptyList(), "", "连接已更新的电脑 Agent 后可搜索聊天正文")
            } else rpc("codexrm/thread/search", JSONObject().put("query", query).put("cursor", cursor)) { page, error ->
                if (!sameDevice() || page == null || error != null) {
                    complete(emptyList(), "", error?.message ?: "未能搜索聊天正文")
                    return@rpc
                }
                val data = page.arrayAt("hits")
                val hits = (0 until data.length()).mapNotNull { index ->
                    val hit = data.optJSONObject(index) ?: return@mapNotNull null
                    val thread = ProtocolFormatter.thread(hit)
                    val item = hit.string("itemId")
                    if (thread.id.isBlank() || item.isBlank()) return@mapNotNull null
                    val archived = hit.optBoolean("archived")
                    val title = if (archived) "${thread.title} · 已归档" else thread.title
                    SearchResult("chat:${thread.id}", SearchKind.CHAT, title, hit.string("snippet")) {
                        if (active(version) && !loading && !sending) {
                            conversationJump = ConversationJump(thread.id, item, archived)
                            if (currentThreadId == thread.id && (archivedThreadId == thread.id) == archived) locateConversationJump()
                            else openThread(thread.id, archived = archived)
                        }
                    }
                }.distinctBy { it.id }
                complete(hits, page.string("nextCursor"), if (page.optInt("skipped") > 0) "有部分历史无法读取" else "")
            }
        }).also { it.show() }
    }

    private fun locateConversationJump() {
        val target = conversationJump?.takeIf { it.thread == currentThreadId } ?: return
        adapter.reveal(target.item)
        val position = adapter.positionOf(target.item)
        if (position >= 0) {
            conversationJump = null
            adapter.notifyDataSetChanged()
            messageList.scrollToMessage(position)
        } else if (historyCursor != null && online) {
            loadOlderTurns(::locateConversationJump)
        } else {
            conversationJump = null
            toast("这条消息已无法定位")
        }
    }

    private fun showArchivedConversations() {
        val deviceGeneration = generation
        if (!online) { toast("连接电脑后可查看归档会话"); return }
        val panel = ConversationResults(this, "已归档会话", ::styledDialog)
        val shown = mutableSetOf<String>()
        var cursor = ""
        fun load() {
            if (!active(deviceGeneration) || !panel.dialog.isShowing) return
            panel.status.text = "正在加载…"
            panel.more.visibility = View.GONE
            val params = JSONObject().put("archived", true).put("limit", 30)
            if (cursor.isNotEmpty()) params.put("cursor", cursor)
            rpc("thread/list", params) { page, error ->
                if (!active(deviceGeneration) || !panel.dialog.isShowing) return@rpc
                if (page == null || error != null) {
                    panel.status.text = error?.message ?: "无法读取归档会话"
                    panel.more.text = "重试"
                    panel.more.visibility = View.VISIBLE
                    return@rpc
                }
                val data = page.arrayAt("data")
                for (index in 0 until data.length()) {
                    val thread = ProtocolFormatter.thread(data.optJSONObject(index) ?: continue)
                    if (thread.id.isEmpty() || !shown.add(thread.id)) continue
                    val row = panel.row(thread.title, thread.cwd) {
                        if (active(deviceGeneration) && !loading && !sending) {
                            panel.dialog.dismiss()
                            openThread(thread.id, archived = true)
                        }
                    }
                    val restore = Ui.button(this, "恢复") { button ->
                        if (active(deviceGeneration)) {
                            button.isEnabled = false
                            restoreConversation(thread.id, onFailure = { button.isEnabled = true }) {
                                panel.results.removeView(row)
                                shown.remove(thread.id)
                                panel.status.text = if (shown.isEmpty() && cursor.isEmpty()) "暂无归档会话" else ""
                            }
                        }
                    }
                    row.addView(restore, LinearLayout.LayoutParams(-2, -2))
                }
                val next = page.string("nextCursor")
                cursor = next.takeUnless { it == cursor }.orEmpty()
                panel.status.text = if (shown.isEmpty() && cursor.isEmpty()) "暂无归档会话" else ""
                panel.more.text = "加载更多归档"
                panel.more.visibility = if (cursor.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
        panel.more.setOnClickListener { load() }
        load()
    }

    private fun restoreConversation(id: String, onFailure: () -> Unit = {}, onRestored: (String) -> Unit) {
        val deviceGeneration = generation
        rpc("thread/unarchive", JSONObject().put("threadId", id)) { _, error ->
            if (!active(deviceGeneration)) return@rpc
            if (error != null) { onFailure(); toast(error.message); return@rpc }
            loadThreads()
            loadConversationPins()
            toast("会话已恢复")
            onRestored(id)
        }
    }

    private fun showRelatedConversations(id: String) {
        if (!online) { toast("连接电脑后可查看关联分支"); return }
        val panel = ConversationResults(this, "关联分支", ::styledDialog)
        val deviceGeneration = generation
        panel.status.text = "正在加载…"
        rpc("codexrm/thread/related", JSONObject().put("threadId", id)) { page, error ->
            if (!active(deviceGeneration) || !panel.dialog.isShowing) return@rpc
            if (page == null || error != null) { panel.status.text = error?.message ?: "无法读取关联分支"; return@rpc }
            val threads = page.arrayAt("data")
            panel.status.text = if (threads.length() < 2) "此会话还没有关联分支" else ""
            for (index in 0 until threads.length()) {
                val data = threads.optJSONObject(index) ?: continue
                val thread = ProtocolFormatter.thread(data)
                val archived = data.optBoolean("archived")
                val label = when {
                    thread.id == currentThreadId -> " · 当前会话"
                    archived -> " · 已归档"
                    data.string("forkedFromId").isEmpty() -> " · 源会话"
                    else -> ""
                }
                if (threads.length() > 1) panel.row(thread.title + label, thread.cwd) {
                    if (!active(deviceGeneration) || loading || sending) return@row
                    panel.dialog.dismiss()
                    if (thread.id != currentThreadId) openThread(thread.id, archived = archived)
                }
            }
        }
    }

    private fun showMessageActions(message: ChatMessage) {
        if (message.kind != MessageKind.USER || message.turnId.isEmpty() || !online || running || loading || sending || archivedThreadId.isNotEmpty()) {
            copyMessage(message.body)
            return
        }
        val actions = linkedMapOf<String, () -> Unit>(
            "复制消息" to { copyMessage(message.body) },
            "编辑并创建分支" to { forkMessage(message, edit = true) },
        )
        if (info.optBoolean("conversationTools")) {
            actions["用原指令重试"] = { forkMessage(message, edit = false) }
            actions["关联分支"] = { showRelatedConversations(currentThreadId) }
        }
        styledDialog(MiuixDialog.Builder(this).setItems(actions.keys.toTypedArray()) { _, index -> actions.values.elementAt(index).invoke() })
    }

    private fun branchDraft(message: ChatMessage): BranchDraft {
        val input = message.userInput
        val text = mutableListOf<String>()
        val files = mutableListOf<JSONObject>()
        var needsAttachments = false
        for (index in 0 until (input?.length() ?: 0)) {
            val item = input?.optJSONObject(index) ?: continue
            if (item.string("type") == "text") {
                val value = item.string("text")
                val file = MessageImages.attachment(value)
                if (file == null) text += value
                else if (file.string("path").isNotEmpty()) files += file.put("draftId", java.util.UUID.randomUUID().toString())
                else needsAttachments = true
            }
        }
        for (index in 0 until (input?.length() ?: 0)) {
            val item = input?.optJSONObject(index) ?: continue
            if (item.string("type") !in listOf("text", "localImage") ||
                item.string("type") == "localImage" && files.none { it.string("path") == item.string("path") }) needsAttachments = true
        }
        return BranchDraft("", if (input == null) message.body else text.joinToString("\n"), files, needsAttachments)
    }

    private fun applyBranchDraft(draft: BranchDraft) {
        pendingBranchDraft = null
        val previous = composer.text.toString()
        setComposerText(listOf(previous, draft.text).filter(String::isNotBlank).joinToString("\n\n"))
        val key = attachmentDraftKey()
        if (draft.files.isNotEmpty()) {
            attachmentStore.save(key, attachmentStore.load(key) + draft.files)
            renderAttachments()
        }
        saveDraft()
        composer.requestFocus()
        if (draft.needsAttachments) toast("部分原附件需要重新添加")
    }

    private fun forkMessage(message: ChatMessage, edit: Boolean) {
        if (currentThreadId.isEmpty() || running || loading || sending || !online) return
        val draft = branchDraft(message)
        val source = currentThreadId
        val currentGeneration = generation
        val params = JSONObject().put("threadId", source).put("excludeTurns", true)
        if (info.optBoolean("conversationTools")) params.put("beforeTurnId", message.turnId)
        else {
            val turns = adapter.snapshot().map(ChatMessage::turnId).filter(String::isNotEmpty).distinct()
            val index = turns.indexOf(message.turnId)
            if (index < 0) return
            if (index == 0) {
                if (historyCursor != null) { toast("请先加载此消息之前的历史"); return }
                newChat(showKeyboard = true)
                applyBranchDraft(draft)
                return
            }
            params.put("lastTurnId", turns[index - 1])
        }
        loading = true
        updateControls()
        rpc("thread/fork", params) { result, error ->
            if (currentGeneration != generation) return@rpc
            loading = false
            val id = result?.optJSONObject("thread")?.string("id").orEmpty()
            if (error != null || id.isBlank() || id == source) {
                toast(error?.message ?: "无法创建会话分支")
                updateControls()
                return@rpc
            }
            pendingBranchDraft = draft.copy(thread = id)
            openThread(id)
            loadThreads(generation)
            if (!edit) toast("原指令已放入新分支草稿")
        }
    }

    private fun updateDiffButton() {
        if (!::diffButton.isInitialized) return
        diffButton.visibility = if (latestDiff.isNotBlank() && currentThreadId.isNotEmpty()) View.VISIBLE else View.GONE
        if (diffButton.visibility == View.VISIBLE) {
            val files = diffFiles(latestDiff)
            diffButton.text = "本轮改动 · ${files.size} 个文件  +${files.sumOf { it.added }}  −${files.sumOf { it.removed }}"
        }
    }

    private fun showConversationDiff() {
        val choices = arrayOf("最新一轮改动", "工作区未提交改动")
        styledDialog(MiuixDialog.Builder(this).setTitle("查看改动").setItems(choices) { _, which ->
            if (which == 0) showDiffFiles(choices[0], latestDiff)
            else if (!online || currentCwd.isBlank()) toast("连接电脑并选择工作区后可查看")
            else {
                val agent = currentAgent()
                val cwd = currentCwd
                rpc("codexrm/workspace/diff", JSONObject().put("workspace", cwd).put("threadId", currentThreadId)) { result, error ->
                    if (agent != currentAgent() || cwd != currentCwd) return@rpc
                    if (result != null) {
                        val names = result.arrayAt("untracked")
                        val untracked = (0 until names.length()).mapNotNull { names.optString(it).takeIf(String::isNotBlank) }
                        showDiffFiles(choices[1], result.string("diff"), untracked)
                    }
                    else toast(error?.message ?: "无法读取工作区改动")
                }
            }
        })
    }

    private fun showDiffFiles(title: String, patch: String, untracked: List<String> = emptyList()) {
        val files = diffFiles(patch)
        if (files.isEmpty() && untracked.isEmpty()) { toast("没有可显示的改动"); return }
        val labels = files.map { "${it.name}  +${it.added}  −${it.removed}" } + untracked.map { "$it  未跟踪" }
        styledDialog(MiuixDialog.Builder(this).setTitle(title)
            .setItems(labels.toTypedArray()) { _, index ->
                if (index >= files.size) {
                    val path = untracked[index - files.size]
                    if (directFilesAvailable()) workspaceFiles.openOrDownload(RemoteFile(currentCwd, currentThreadId, path), path.substringAfterLast('/'))
                    else toast("电脑允许远程管理文件且 P2P 直连后，才可打开未跟踪文件")
                    return@setItems
                }
                val file = files[index]
                val content = Ui.text(this, file.patch, 12f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setTextIsSelectable(true)
                    setPadding(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 10))
                }
                styledDialog(MiuixDialog.Builder(this).setTitle(file.name)
                    .setView(ScrollView(this).apply { addView(content) })
                    .setNeutralButton("复制差异") { _, _ -> copyMessage(file.patch) }.setPositiveButton("关闭", null))
            }.setNegativeButton("关闭", null))
    }

    private fun showConnectionActions() {
        val current = device ?: return
        val actions = linkedMapOf<String, () -> Unit>()
        actions["重新连接"] = { if (current.agentId == LocalCodex.AGENT) chooseLocalCodex() else { disconnect(); connect() } }
        val onlineAt = conversationPrefs.getLong("seen:${current.agentId}", 0)
        val lastSeen = if (onlineAt > 0) "\n上次在线：${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(onlineAt))}" else ""
        val status = connectionNotice.text.toString().ifBlank { "电脑已连接" }
        styledDialog(MiuixDialog.Builder(this).setTitle("${current.serverName}\n$status$lastSeen")
            .setItems(actions.keys.toTypedArray()) { _, which -> actions.values.elementAt(which).invoke() })
    }

    private fun rpc(method: String, params: JSONObject = JSONObject(), callback: RpcCallback) {
        val current = client; if (current == null) callback.onComplete(null, IOException("设备未连接")) else current.request(method, params, callback = callback)
    }
    private fun active(value: Int): Boolean = value == generation && online && mainVisible
    private fun openActive(id: String, value: Int): Boolean = value == openGeneration && id == openingThreadId && online
    private fun currentAgent(): String = device?.agentId.orEmpty()
    private fun supportsFileDownloads(): Boolean = info.optBoolean("mentionedFileDownloads", info.optBoolean("directFiles"))
    private fun directFilesAvailable(): Boolean = client?.usingDirect == true && info.optBoolean("directFiles")
    private fun updateDirectFiles() {
        val management = online && info.optBoolean("directFiles")
        workspaceFiles.connectionChanged()
        if (!management) workspaceFiles.closeManager()
        if (::workspaceFilesButton.isInitialized) workspaceFilesButton.visibility = if (management) View.VISIBLE else View.GONE
        if (::workspaceButton.isInitialized) workspaceButton.isLongClickable = management
        if (::adapter.isInitialized) {
            adapter.showUnlinkedFileCards = directFilesAvailable()
            adapter.showFileCards = client?.usingDirect == true && supportsFileDownloads()
        }
    }
    private fun openMessageFile(file: ArtifactCard) {
        workspaceFiles.openOrDownload(RemoteFile(currentCwd, currentThreadId, file.path), file.name)
    }

    private fun openWorkspaceFiles(path: String, threadId: String, startAtComputer: Boolean = false): Boolean {
        if (!info.optBoolean("directFiles")) return false
        val computerFiles = info.optBoolean("computerFiles")
        if (path.isEmpty() && !computerFiles) return false
        if (!directFilesAvailable()) {
            toast(if (client?.usingDirect == true) "此电脑未允许远程管理文件" else "P2P 直连后才能管理文件")
            return true
        }
        workspaceFiles.open(RemoteFile(path, threadId, ""), computerFiles, startAtComputer)
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
    private fun styledDialog(builder: MiuixDialog.Builder): MiuixDialog = builder.show()


    private fun currentBackTarget(): BackTarget? = when {
        mainVisible && sidebarOpen && devicesExpanded -> BackTarget.DEVICES
        mainVisible && sidebarOpen -> BackTarget.SIDEBAR
        mainVisible && transferPage != null -> BackTarget.TRANSFERS
        !mainVisible && devices.any { it.agentId !in expiredAgentIds } -> BackTarget.PAIR
        else -> null
    }

    private fun performBack(target: BackTarget) = when (target) {
        BackTarget.DEVICES -> toggleDevices()
        BackTarget.SIDEBAR -> closeSidebar()
        BackTarget.TRANSFERS -> closeTransfers()
        BackTarget.PAIR -> showMain()
    }

    private fun startBackGesture(progress: Float, fromRightEdge: Boolean) {
        if (backGesture != null) finishBackGesture(commit = backGesture?.completion != null)
        val target = currentBackTarget() ?: return
        val preview = when (target) {
            BackTarget.DEVICES -> panelBackPreview(deviceDropdown, -Ui.dp(this, 16).toFloat())
            BackTarget.SIDEBAR -> sidebarBackPreview()
            BackTarget.TRANSFERS -> null
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
