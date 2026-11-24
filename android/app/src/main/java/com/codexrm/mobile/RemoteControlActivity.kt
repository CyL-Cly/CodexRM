package com.codexrm.mobile

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.Locale
import kotlin.math.roundToInt

internal class RemoteControlActivity : ComponentActivity() {
    private enum class Sheet { NONE, ACTIONS, MENU, DISPLAY, SECURITY, DEVICES, MORE, SHORTCUTS }

    private lateinit var home: View
    private lateinit var session: FrameLayout
    private lateinit var screen: RemoteScreenView
    private lateinit var panel: ScrollView
    private lateinit var panelBody: LinearLayout
    private lateinit var keyboard: LinearLayout
    private lateinit var usage: TextView
    private lateinit var status: TextView
    private lateinit var online: TextView
    private lateinit var name: TextView
    private val inputThread = HandlerThread("remote-input")
    private lateinit var input: Handler
    private var inSession = false
    private var viewOnly = false
    private var smallWindow = false
    private var touchMode = true
    private var tablet = false
    private var virtualMouse = true
    private var keyMapping = false
    private var sheet = Sheet.NONE
    private var keyboardTab = 2
    private var keyPage = 0
    private var generation = 0
    private val mods = linkedMapOf(0x10 to false, 0x11 to false, 0x12 to false, 0x5B to false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        inputThread.start()
        input = Handler(inputThread.looper)
        val root = FrameLayout(this)
        session = buildSession()
        home = buildHome()
        root.addView(session)
        root.addView(home)
        (panel.parent as? android.view.ViewGroup)?.removeView(panel)
        root.addView(panel, FrameLayout.LayoutParams(panelWidth(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply {
            marginEnd = dp(72); topMargin = dp(8); bottomMargin = dp(8)
        })
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
    }

    override fun onStart() {
        super.onStart()
        RemoteBridge.listener = { renderConnection() }
        renderConnection()
    }

    override fun onStop() {
        RemoteBridge.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        generation++
        if (!isChangingConfigurations) RemoteBridge.transport?.stopDesktop()
        inputThread.quitSafely()
        super.onDestroy()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK || !keyMapping || !inSession || viewOnly) return super.dispatchKeyEvent(event)
        val vk = virtualKey(event.keyCode)
        if (vk == 0 || event.repeatCount != 0) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) sendKey(RemoteProtocol.KEY_DOWN.toInt(), vk)
        if (event.action == KeyEvent.ACTION_UP) sendKey(RemoteProtocol.KEY_UP.toInt(), vk)
        return true
    }

    private fun buildHome(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF3F5F8.toInt())
            setPadding(dp(16), dp(36), dp(16), dp(18))
        }
        val bar = row()
        bar.addView(iconButton("left", "\u8fd4\u56de") { finish() })
        online = text("\u5728\u7ebf", 12f, Color.WHITE).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = pill(Color.BLACK, dp(14))
        }
        bar.addView(online)
        name = text(RemoteBridge.deviceName, 18f, 0xFF1C1C1E.toInt(), true).apply { ellipsize() }
        bar.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(iconButton("more", "\u66f4\u591a") { openMenu() })
        page.addView(bar, wide(bottom = 14))
        val preview = FrameLayout(this).apply { background = round(0xFF2E7D32.toInt(), 18) }
        preview.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; tag = "preview" }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        preview.addView(text("\u8fdb\u5165\u684c\u9762  >", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { enter(false) }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        page.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190)))
        val actions = row().apply { background = round(Color.WHITE, 16); setPadding(dp(6), dp(12), dp(6), dp(12)) }
        actions.addView(action("folder", "\u6587\u4ef6\u4f20\u8f93") { toast("\u6587\u4ef6\u4f20\u8f93\u4ecd\u4ece\u4f1a\u8bdd\u9644\u4ef6\u4f7f\u7528") }, weight())
        actions.addView(action("eye", "\u89c2\u770b\u6a21\u5f0f") { enter(true) }, weight())
        actions.addView(action("terminal", "\u7ec8\u7aef") { toast("\u7ec8\u7aef\u7a0d\u540e\u63a5\u5165") }, weight())
        actions.addView(action("more", "\u66f4\u591a") { openMenu() }, weight())
        page.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = -dp(18) })
        page.addView(text("\u5feb\u901f\u542f\u52a8", 20f, 0xFF1C1C1E.toInt(), true), wide(top = 22, bottom = 12))
        val launch = FrameLayout(this).apply { background = dashed() }
        launch.addView(text("+\n\u6dfb\u52a0\u5e94\u7528", 14f, 0xFF8E8E93.toInt()).apply {
            gravity = Gravity.CENTER
            setOnClickListener { toast("\u5feb\u901f\u542f\u52a8\u7a0d\u540e\u63a5\u5165") }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        page.addView(launch, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        status = text("", 12f, 0xFF8E8E93.toInt()).apply { gravity = Gravity.CENTER }
        page.addView(status, wide(top = 8))
        return page
    }

    private fun buildSession(): FrameLayout {
        val host = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        screen = RemoteScreenView(this).apply {
            listener = RemotePointerListener { action, id, x, y, buttons ->
                if (!inSession || viewOnly) return@RemotePointerListener
                val message = RemoteProtocol.pointer(action, id, x, y, buttons)
                input.post { RemoteBridge.transport?.sendDesktop(message) }
            }
        }
        host.addView(screen, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        panelBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(16), dp(14), dp(18)) }
        panel = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            background = round(0xE645474A.toInt(), 22)
            addView(panelBody)
        }
        host.addView(panel, FrameLayout.LayoutParams(panelWidth(), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END).apply { marginEnd = dp(72); topMargin = dp(8); bottomMargin = dp(8) })
        host.addView(rail(), FrameLayout.LayoutParams(dp(68), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END))
        keyboard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setBackgroundColor(0xF22A2D31.toInt()) }
        host.addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return host
    }

    private fun rail(): LinearLayout {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(18), 0, dp(12))
            setBackgroundColor(0x66000000)
        }
        rail.addView(railButton("sliders", "\u64cd\u4f5c") { showSheet(if (sheet == Sheet.NONE) Sheet.ACTIONS else Sheet.NONE) })
        rail.addView(railButton("keyboard", "\u952e\u76d8") { toggleKeyboard() })
        rail.addView(railButton("monitor", "\u663e\u793a\u684c\u9762") { shortcut(0x5B, 0x44) })
        rail.addView(railButton("layout", "\u5c55\u793a\u6240\u6709\u7a97\u53e3") { shortcut(0x5B, 0x09) })
        return rail
    }

    private fun enter(watch: Boolean) {
        val transport = RemoteBridge.transport
        if (!RemoteBridge.direct || transport == null) {
            toast("\u9700\u8981 P2P \u76f4\u8fde\u540e\u624d\u80fd\u63a7\u5236\u8fd9\u53f0\u7535\u8111")
            return
        }
        viewOnly = watch
        screen.viewOnly = watch
        inSession = true
        home.visibility = View.GONE
        session.visibility = View.VISIBLE
        immersive(true)
        showSheet(Sheet.ACTIONS)
        val token = ++generation
        Thread({
            if (!transport.startDesktop() || token != generation) {
                if (token == generation) runOnUiThread { failSession("\u684c\u9762\u8fde\u63a5\u5931\u8d25") } else transport.stopDesktop()
                return@Thread
            }
            sendMode()
            val started = SystemClock.elapsedRealtime()
            var gotFrame = false
            var inactiveSince = 0L
            while (token == generation) {
                val frame = transport.pollDesktop()
                if (frame != null) {
                    gotFrame = true
                    runOnUiThread { if (token == generation) showFrame(frame, transport.desktopBytes()) }
                }
                val now = SystemClock.elapsedRealtime()
                val active = transport.desktopActive()
                if (active) inactiveSince = 0 else if (inactiveSince == 0L) inactiveSince = now
                val expired = now - started > 8_000
                val dropped = inactiveSince != 0L && (gotFrame || now - inactiveSince > 1_000 || expired)
                if (dropped || (!gotFrame && expired)) {
                    val message = if (gotFrame) "\u684c\u9762\u8fde\u63a5\u5df2\u65ad\u5f00" else "\u6ca1\u6709\u6536\u5230\u684c\u9762\u753b\u9762"
                    runOnUiThread { if (token == generation) failSession(message) }
                    return@Thread
                }
                SystemClock.sleep(30)
            }
            transport.stopDesktop()
        }, "remote-desktop").start()
    }

    private fun failSession(message: String) { toast(message); leave() }

    private fun leave() {
        generation++
        inSession = false
        RemoteBridge.transport?.stopDesktop()
        releaseMods()
        keyboard.visibility = View.GONE
        sheet = Sheet.NONE
        panel.visibility = View.GONE
        session.visibility = View.GONE
        home.visibility = View.VISIBLE
        immersive(false)
    }

    private fun showFrame(jpeg: ByteArray, bytes: Long) {
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        screen.setFrame(bitmap)
        home.findViewWithTag<ImageView>("preview")?.setImageBitmap(bitmap)
        if (this::usage.isInitialized) usage.text = String.format(Locale.US, "%.2fMB", bytes / (1024.0 * 1024.0))
    }

    private fun openMenu() {
        sheet = Sheet.MENU
        renderSheet()
    }

    private fun showSheet(next: Sheet) {
        sheet = if (sheet == next) Sheet.NONE else next
        renderSheet()
    }

    private fun renderSheet() {
        panel.visibility = if (sheet == Sheet.NONE || keyboard.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        if (sheet == Sheet.NONE) return
        panelBody.removeAllViews()
        when (sheet) {
            Sheet.NONE -> return
            Sheet.ACTIONS -> renderActions()
            Sheet.MENU -> renderMenu()
            Sheet.DISPLAY -> renderDisplay()
            Sheet.SECURITY -> renderSecurity()
            Sheet.DEVICES -> renderDevicesSheet()
            Sheet.MORE -> renderMore()
            Sheet.SHORTCUTS -> renderShortcuts()
        }
    }

    private fun renderActions() {
        usage = text("0.00MB", 13f, Color.WHITE)
        val head = row()
        head.addView(text("\u672c\u6b21\u8fde\u63a5\u5df2\u4f7f\u7528\u6570\u636e\u6d41\u91cf", 13f, Color.WHITE), weight())
        head.addView(usage)
        panelBody.addView(head, wide(bottom = 10))
        panelBody.addView(text("\u9690\u79c1\u5c4f\u672a\u5f00\u542f", 13f, 0xFFFF9F1A.toInt()), wide(bottom = 12))
        val scroller = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val chips = row()
        chips.addView(chip("logout", "\u9000\u51fa\u8fdc\u63a7", false) { leave() })
        chips.addView(chip("rotate", "\u65cb\u8f6c\u5c4f\u5e55", false) { screen.quarter = (screen.quarter + 1) % 4 })
        chips.addView(chip("speaker", "\u58f0\u97f3", false) { toast("\u8fdc\u7a0b\u58f0\u97f3\u5c1a\u672a\u63a5\u5165") })
        chips.addView(chip("eye", "\u4ec5\u89c2\u770b", viewOnly) { viewOnly = !viewOnly; screen.viewOnly = viewOnly; renderSheet() })
        chips.addView(chip("window", "\u5c0f\u7a97\u6a21\u5f0f", smallWindow) { smallWindow = !smallWindow; applyWindow(); renderSheet() })
        chips.addView(chip("fullscreen", "\u5168\u5c4f\u7a97\u53e3", !smallWindow) { smallWindow = false; applyWindow(); renderSheet() })
        scroller.addView(chips)
        panelBody.addView(scroller, wide(bottom = 14))
        panelBody.addView(text("\u64cd\u4f5c", 14f, 0xFFB0B4BA.toInt()), wide(bottom = 8))
        panelBody.addView(toggleRow("A", "\u542f\u7528\u6309\u952e\u6620\u5c04", keyMapping) { keyMapping = it }, wide(bottom = 12))
        val segment = row().apply { background = round(0xFF2C2F33.toInt(), 12); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        segment.addView(segmentButton("\u5c4f\u5e55\u89e6\u63a7", touchMode) { touchMode = true; tablet = false; applyPointing() }, weight())
        segment.addView(segmentButton("\u9f20\u6807\u6307\u9488", !touchMode) { touchMode = false; tablet = false; applyPointing() }, weight())
        panelBody.addView(segment, wide(bottom = 16))
        panelBody.addView(text("\u5e73\u677f\u89e6\u63a7\u6a21\u5f0f", 16f, Color.WHITE, true))
        val touchRow = row()
        touchRow.addView(text("\u652f\u6301\u591a\u70b9\u89e6\u63a7\uff0c\u5efa\u8bae\u73a9\u6e38\u620f\u7684\u89e6\u5c4f\u7248\u65f6\u5f00\u542f\u3002", 12f, 0xFFC5C8CE.toInt()).apply { setPadding(0, dp(4), dp(8), 0) }, weight())
        touchRow.addView(switchView(tablet) { tablet = it; if (it) touchMode = true; applyPointing() })
        panelBody.addView(touchRow, wide(bottom = 16))
        panelBody.addView(toggleRow("", "\u865a\u62df\u9f20\u6807", virtualMouse) { virtualMouse = it; screen.virtualCursor = it; screen.invalidate() })
        panelBody.addView(menuRow("\u66f4\u591a\u8bbe\u7f6e") { sheet = Sheet.MENU; renderSheet() }, wide(top = 12))
    }

    private fun renderMenu() {
        panelBody.addView(toggleRow("", "\u865a\u62df\u9f20\u6807", virtualMouse) { virtualMouse = it; screen.virtualCursor = it }, wide(bottom = 8))
        panelBody.addView(menuRow("\u663e\u793a") { sheet = Sheet.DISPLAY; renderSheet() })
        panelBody.addView(menuRow("\u5b89\u5168") { sheet = Sheet.SECURITY; renderSheet() })
        panelBody.addView(menuRow("\u5916\u8bbe") { sheet = Sheet.DEVICES; renderSheet() })
        panelBody.addView(menuRow("\u66f4\u591a") { sheet = Sheet.MORE; renderSheet() })
        panelBody.addView(menuRow("\u7cfb\u7edf\u5feb\u6377\u64cd\u4f5c") { sheet = Sheet.SHORTCUTS; renderSheet() })
    }

    private fun renderDisplay() {
        backRow("\u663e\u793a")
        panelBody.addView(text("\u5f53\u524d\u753b\u9762\u6309\u624b\u673a\u7a97\u53e3\u7b49\u6bd4\u9002\u914d\u3002", 13f, Color.WHITE), wide(bottom = 8))
        panelBody.addView(menuRow(if (smallWindow) "\u5168\u5c4f\u7a97\u53e3" else "\u5c0f\u7a97\u6a21\u5f0f") { smallWindow = !smallWindow; applyWindow(); renderSheet() })
    }

    private fun renderSecurity() {
        backRow("\u5b89\u5168")
        panelBody.addView(toggleRow("eye", "\u4ec5\u89c2\u770b", viewOnly) { viewOnly = it; screen.viewOnly = it })
        panelBody.addView(text("\u9690\u79c1\u5c4f\u672a\u5f00\u542f\u3002\u8fdc\u7a0b\u64cd\u4f5c\u4f1a\u79fb\u52a8\u8fd9\u53f0\u7535\u8111\u7684\u771f\u5b9e\u6307\u9488\u3002", 13f, 0xFFFF9F1A.toInt()), wide(top = 10))
    }

    private fun renderDevicesSheet() {
        backRow("\u5916\u8bbe")
        panelBody.addView(toggleRow("", "\u865a\u62df\u9f20\u6807", virtualMouse) { virtualMouse = it; screen.virtualCursor = it })
        panelBody.addView(menuRow("\u58f0\u97f3") { toast("\u8fdc\u7a0b\u58f0\u97f3\u5c1a\u672a\u63a5\u5165") }, wide(top = 8))
    }

    private fun renderMore() {
        backRow("\u66f4\u591a")
        panelBody.addView(toggleRow("A", "\u542f\u7528\u6309\u952e\u6620\u5c04", keyMapping) { keyMapping = it })
        panelBody.addView(toggleRow("", "\u5e73\u677f\u89e6\u63a7\u6a21\u5f0f", tablet) { tablet = it; if (it) touchMode = true; applyPointing() }, wide(top = 8))
    }

    private fun renderShortcuts() {
        backRow("\u7cfb\u7edf\u5feb\u6377\u64cd\u4f5c")
        shortcutRow("\u663e\u793a\u684c\u9762", 0x5B, 0x44)
        shortcutRow("\u4efb\u52a1\u89c6\u56fe", 0x5B, 0x09)
        shortcutRow("\u5207\u6362\u7a97\u53e3", 0x12, 0x09)
        shortcutRow("\u5173\u95ed\u7a97\u53e3", 0x12, 0x73)
        shortcutRow("\u4efb\u52a1\u7ba1\u7406\u5668", 0x11, 0x10, 0x1B)
        shortcutRow("\u9501\u5b9a", 0x5B, 0x4C)
    }

    private fun backRow(title: String) {
        val line = row()
        line.addView(iconButton("left", "\u8fd4\u56de", Color.WHITE) { sheet = Sheet.MENU; renderSheet() })
        line.addView(text(title, 16f, Color.WHITE, true))
        panelBody.addView(line, wide(bottom = 10))
    }

    private fun shortcutRow(label: String, vararg codes: Int) { panelBody.addView(menuRow(label) { shortcut(*codes) }) }

    private fun shortcut(vararg codes: Int) {
        if (!inSession) { toast("\u8bf7\u5148\u8fdb\u5165\u684c\u9762"); return }
        releaseMods()
        val messages = RemoteProtocol.chord(*codes)
        input.post { messages.forEach { RemoteBridge.transport?.sendDesktop(it) } }
    }

    private fun applyPointing() {
        screen.pointing = when {
            tablet -> RemotePointing.TABLET
            touchMode -> RemotePointing.TOUCH
            else -> RemotePointing.POINTER
        }
        screen.virtualCursor = virtualMouse || !touchMode
        sendMode()
        if (sheet == Sheet.ACTIONS || sheet == Sheet.MORE) renderSheet()
    }

    private fun sendMode() {
        val message = RemoteProtocol.mode(tablet)
        input.post { RemoteBridge.transport?.sendDesktop(message) }
    }

    private fun applyWindow() {
        val margin = if (smallWindow) dp(28) else 0
        val params = screen.layoutParams as FrameLayout.LayoutParams
        params.setMargins(margin, margin, margin, margin)
        screen.layoutParams = params
    }

    private fun toggleKeyboard() {
        val show = keyboard.visibility != View.VISIBLE
        keyboard.visibility = if (show) View.VISIBLE else View.GONE
        if (show) { panel.visibility = View.GONE; rebuildKeyboard() } else renderSheet()
    }

    private fun rebuildKeyboard() {
        keyboard.removeAllViews()
        keyboard.addView(keyboardTabs())
        keyboard.addView(if (keyboardTab == 1) shortcutPad() else keyPad())
    }

    private fun keyboardTabs(): LinearLayout {
        val tabs = row().apply { setPadding(dp(12), dp(8), dp(8), dp(4)); gravity = Gravity.CENTER_VERTICAL }
        tabs.addView(tab("\u8f93\u5165\u6cd5", 0), weight())
        tabs.addView(tab("\u5feb\u6377\u952e", 1), weight())
        tabs.addView(tab("\u7535\u8111\u952e\u76d8", 2), weight())
        tabs.addView(iconButton("close", "\u5173\u95ed", Color.WHITE) { toggleKeyboard() })
        return tabs
    }

    private fun tab(label: String, index: Int) = text(label, 14f, if (keyboardTab == index) 0xFF4C9AFF.toInt() else Color.WHITE, keyboardTab == index).apply {
        gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        setOnClickListener { keyboardTab = index; rebuildKeyboard() }
    }

    private fun keyPad(): LinearLayout {
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(8)) }
        val rows = when (keyPage) {
            1 -> listOf("1234567890", "-=[]\\;',./", "!@#\$%^&*()", "")
            2 -> listOf("\uff0c\u3002\uff1f\uff01\u3001", "\uff1b\uff1a\u201c\u201d\u2018", "\uff08\uff09\u3010\u3011", "")
            else -> listOf("1234567890", "QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")
        }
        rows.filter { it.isNotEmpty() }.forEach { pad.addView(keyRow(it.map { ch -> keyCap(ch) })) }
        val bottom = row()
        bottom.addView(special("\u7b26", 1.2f) { keyPage = if (keyPage == 2) 0 else 2; rebuildKeyboard() })
        bottom.addView(special("123", 1.2f) { keyPage = if (keyPage == 1) 0 else 1; rebuildKeyboard() })
        bottom.addView(special(",", 1f) { tapText(",") })
        bottom.addView(special(" ", 4f) { tapVk(0x20) })
        bottom.addView(special(".", 1f) { tapText(".") })
        bottom.addView(special("\u4e2d", 1.2f) { keyPage = 2; rebuildKeyboard() })
        bottom.addView(special("\u21b5", 1.6f) { tapVk(0x0D) })
        pad.addView(bottom)
        return pad
    }

    private fun keyRow(keys: List<Pair<String, () -> Unit>>): LinearLayout {
        val line = row().apply { gravity = Gravity.CENTER }
        keys.forEach { (label, click) -> line.addView(special(label, 1f, click)) }
        return line
    }

    private fun keyCap(ch: Char): Pair<String, () -> Unit> {
        val label = ch.toString()
        return label to { if (ch.isLetter()) tapVk(ch.uppercaseChar().code) else tapText(label) }
    }

    private fun shortcutPad(): LinearLayout {
        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(8), dp(10), dp(10)) }
        listOf(
            "Esc" to intArrayOf(0x1B), "Tab" to intArrayOf(0x09), "Ctrl C" to intArrayOf(0x11, 0x43),
            "Ctrl V" to intArrayOf(0x11, 0x56), "Ctrl Z" to intArrayOf(0x11, 0x5A), "Ctrl A" to intArrayOf(0x11, 0x41),
            "Alt Tab" to intArrayOf(0x12, 0x09), "Alt F4" to intArrayOf(0x12, 0x73), "Win" to intArrayOf(0x5B),
            "Del" to intArrayOf(0x2E), "Left" to intArrayOf(0x25), "Up" to intArrayOf(0x26),
            "Down" to intArrayOf(0x28), "Right" to intArrayOf(0x27),
        ).chunked(3).forEach { group ->
            val line = row()
            group.forEach { (label, codes) -> line.addView(special(label, 1f) { shortcut(*codes) }, weight()) }
            pad.addView(line)
        }
        return pad
    }

    private fun tapVk(vk: Int) {
        sendKey(RemoteProtocol.KEY_DOWN.toInt(), vk)
        sendKey(RemoteProtocol.KEY_UP.toInt(), vk)
    }

    private fun tapText(value: String) { value.forEach { sendKey(RemoteProtocol.KEY_UNICODE.toInt(), it.code) } }

    private fun sendKey(action: Int, code: Int) {
        val message = RemoteProtocol.key(action, code)
        input.post { RemoteBridge.transport?.sendDesktop(message) }
    }

    private fun releaseMods() {
        mods.filterValues { it }.keys.forEach { sendKey(RemoteProtocol.KEY_UP.toInt(), it) }
        mods.keys.forEach { mods[it] = false }
    }

    private fun renderConnection() {
        if (!this::name.isInitialized) return
        name.text = RemoteBridge.deviceName
        val connected = RemoteBridge.online
        online.text = if (connected) "  \u5728\u7ebf" else "  \u79bb\u7ebf"
        online.setTextColor(if (connected) Color.WHITE else 0xFF1C1C1E.toInt())
        online.background = pill(if (connected) Color.BLACK else 0xFFE5E7EB.toInt(), dp(14))
        status.text = when {
            RemoteBridge.direct -> "P2P \u76f4\u8fde\u540e\u53ef\u63a7\u5236"
            connected -> "\u5728\u7ebf\uff0c\u6b63\u5728\u7b49\u5f85 P2P \u76f4\u8fde"
            else -> "\u7535\u8111\u672a\u8fde\u63a5"
        }
    }

    private fun goBack() {
        when {
            keyboard.visibility == View.VISIBLE -> toggleKeyboard()
            sheet != Sheet.NONE && sheet != Sheet.ACTIONS && sheet != Sheet.MENU -> { sheet = Sheet.MENU; renderSheet() }
            sheet != Sheet.NONE -> {
                sheet = Sheet.NONE
                renderSheet()
                if (!inSession) session.visibility = View.GONE
            }
            inSession -> leave()
            else -> finish()
        }
    }

    private fun immersive(on: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (on) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun panelWidth(): Int {
        val preferred = (resources.displayMetrics.widthPixels * 0.62f).roundToInt()
        return preferred.coerceIn(dp(260), dp(420))
    }

    private fun action(icon: String, label: String, click: () -> Unit): LinearLayout {
        val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setOnClickListener { click() }; setPadding(dp(4), 0, dp(4), 0) }
        item.addView(IconView(this, icon, 0xFF1C1C1E.toInt()), LinearLayout.LayoutParams(dp(28), dp(28), 0f).apply { gravity = Gravity.CENTER_HORIZONTAL })
        item.addView(text(label, 12f, 0xFF1C1C1E.toInt()).apply { gravity = Gravity.CENTER })
        return item
    }

    private fun railButton(icon: String, label: String, click: () -> Unit): LinearLayout {
        val item = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(2), dp(10), dp(2), dp(10)); setOnClickListener { click() } }
        item.addView(IconView(this, icon, Color.WHITE), LinearLayout.LayoutParams(dp(24), dp(24)))
        item.addView(text(label, 10f, Color.WHITE).apply { gravity = Gravity.CENTER; maxLines = 2 }, LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT))
        return item
    }

    private fun chip(icon: String, label: String, selected: Boolean, click: () -> Unit): LinearLayout {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = round(if (selected) 0xFF2F80ED.toInt() else 0xFF5C6168.toInt(), 16)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { click() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(74)).apply { marginEnd = dp(8) }
        }
        item.addView(IconView(this, icon, Color.WHITE), LinearLayout.LayoutParams(dp(22), dp(22)))
        item.addView(text(label, 11f, Color.WHITE).apply { gravity = Gravity.CENTER })
        return item
    }

    private fun toggleRow(icon: String, label: String, on: Boolean, change: (Boolean) -> Unit): LinearLayout {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; background = round(0xFF3A3D42.toInt(), 16); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        if (icon == "A") line.addView(text("A", 16f, Color.WHITE, true).apply { setPadding(0, 0, dp(8), 0) })
        else if (icon.isNotEmpty()) line.addView(IconView(this, icon, Color.WHITE), LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
        line.addView(text(label, 15f, Color.WHITE), weight())
        line.addView(switchView(on, change))
        return line
    }

    private fun menuRow(label: String, click: () -> Unit): LinearLayout {
        val line = row().apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(16), dp(4), dp(16)); setOnClickListener { click() } }
        line.addView(text(label, 16f, Color.WHITE), weight())
        line.addView(IconView(this, "right", Color.WHITE), LinearLayout.LayoutParams(dp(16), dp(16)))
        return line
    }

    private fun segmentButton(label: String, selected: Boolean, click: () -> Unit) = text(label, 15f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = round(if (selected) 0xFF2F80ED.toInt() else Color.TRANSPARENT, 10)
        setOnClickListener { click() }
    }

    private fun switchView(initial: Boolean, change: (Boolean) -> Unit): View {
        val view = View(this)
        var on = initial
        view.background = switchDrawable(on)
        view.setOnClickListener {
            on = !on
            view.background = switchDrawable(on)
            change(on)
        }
        view.layoutParams = LinearLayout.LayoutParams(dp(48), dp(28))
        return view
    }

    private fun switchDrawable(on: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(14).toFloat()
        setColor(if (on) 0xFF2F80ED.toInt() else 0xFF6B7078.toInt())
    }

    private fun special(label: String, weight: Float, click: () -> Unit) = text(if (label == " ") "" else label, 15f, Color.WHITE).apply {
        gravity = Gravity.CENTER
        background = round(0xFF3C3F44.toInt(), 8)
        setPadding(dp(4), dp(12), dp(4), dp(12))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply { setMargins(dp(3), dp(3), dp(3), dp(3)) }
    }

    private fun iconButton(icon: String, description: String, tint: Int = 0xFF1C1C1E.toInt(), click: () -> Unit) = FrameLayout(this).apply {
        contentDescription = description
        setOnClickListener { click() }
        addView(IconView(context, icon, tint), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun weight() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    private fun wide(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun TextView.ellipsize() { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
    private fun round(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun pill(color: Int, radius: Int) = round(color, radius)
    private fun dashed() = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), 0xFFD0D5DD.toInt(), dp(6).toFloat(), dp(4).toFloat())
    }
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun virtualKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_ENTER -> 0x0D
        KeyEvent.KEYCODE_SPACE -> 0x20
        KeyEvent.KEYCODE_DEL -> 0x08
        KeyEvent.KEYCODE_TAB -> 0x09
        KeyEvent.KEYCODE_ESCAPE -> 0x1B
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x2E
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x25
        KeyEvent.KEYCODE_DPAD_UP -> 0x26
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x27
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x28
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> code - KeyEvent.KEYCODE_A + 0x41
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0 + 0x30
        else -> 0
    }
}
