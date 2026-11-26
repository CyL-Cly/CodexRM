package com.codexrm.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.KeyboardType
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

internal class SettingsPage(
    private val activity: MainActivity,
    private val deviceName: String,
    private val supported: () -> String?,
    private val direct: () -> Boolean,
    private val request: (String, JSONObject, Boolean, RpcCallback) -> Unit,
    private val catalogChanged: () -> Unit,
    private val sections: (Action) -> List<Section>,
    private val agentSections: (Action) -> List<Section> = { emptyList() },
    private val appearance: ChatAppearance? = null,
    private val pickBackground: (AppearanceArea) -> Unit = {},
    private val reportBackground: (Boolean) -> Unit = {},
    private val local: Boolean = false,
) {
    data class Action(val title: String, val open: () -> Unit, val summary: (() -> String)? = null)
    data class Toggle(val title: String, val checked: () -> Boolean, val change: (Boolean) -> Unit, val summary: String? = null)
    data class Choice(val title: String, val options: List<String>, val selectedIndex: () -> Int, val select: (Int) -> Unit)
    data class Section(
        val title: String,
        val actions: List<Action> = emptyList(),
        val toggle: Toggle? = null,
        val choice: Choice? = null,
    )

    private enum class Page(val parent: Page? = null) {
        HOME,
        LICENSES(HOME),
        LICENSE(LICENSES),
        APPEARANCE(HOME),
        GALLERY(APPEARANCE),
        CROP(APPEARANCE),
        AGENT(HOME),
        CATALOG(AGENT),
        PROVIDER(CATALOG),
        ADD_PROVIDER(CATALOG),
        NATIVE_PROVIDER(CATALOG),
        EDIT_PROVIDER(PROVIDER),
        REFRESH_PROVIDER(PROVIDER),
        REFRESH_NEW_PROVIDER(CATALOG),
    }

    private var refresh by mutableIntStateOf(0)
    private var settingsRoot: View? = null
    private val pageViews = mutableMapOf<Page, View>()
    private var refreshPreviews: (() -> Unit)? = null
    private var gallery: AppearanceGallery? = null
    private var cropLoad: AppearanceImages.Job? = null
    private val dialog = PredictiveBackDialog(activity).apply {
        setOnDismissListener {
            pageVersion++
            cropLoad?.cancel()
            cropLoad = null
            gallery?.close()
            gallery = null
            settingsRoot = null
            pageViews.clear()
            refreshPreviews = null
        }
    }
    private lateinit var content: LinearLayout
    private var pageVersion = 0
    private var savingModels = false
    private var editorSaving = false
    private var modelDraft: ProviderModelDraft? = null
    private var catalogs = emptyList<JSONObject>()
    private var gptProviderSettings = false

    fun show(openProviders: Boolean = false) {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        showHome()
        dialog.show()
        if (openProviders) loadCatalog()
        @Suppress("DEPRECATION")
        dialog.window?.apply {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun dismiss() { dialog.dismiss() }

    fun refreshActionSummaries() {
        refresh++
    }

    fun refreshAppearance() {
        appearance?.let { style ->
            pageViews.values.forEach { it.background = style.drawable(AppearanceArea.SETTINGS) }
        }
        refreshPreviews?.invoke()
        refreshActionSummaries()
    }

    private fun page(
        destination: Page,
        title: String,
        fixedContent: View? = null,
        canSlide: () -> Boolean = { true },
        goBack: () -> Unit,
    ) {
        refreshPreviews = null
        pageVersion++
        val root = Ui.column(activity).apply {
            background = appearance?.drawable(AppearanceArea.SETTINGS) ?: ColorDrawable(Ui.BACKGROUND)
        }
        val header = ComposeView(activity).apply {
            setContent {
                CodexMiuixTheme {
                    refresh
                    SmallTopAppBar(
                        title = title,
                        color = if (appearance?.bitmap(AppearanceArea.SETTINGS) != null)
                            androidx.compose.ui.graphics.Color.Transparent else MiuixTheme.colorScheme.surface,
                        defaultWindowInsetsPadding = false,
                        navigationIcon = { MiuixActionIcon("back", "返回", goBack) },
                    )
                }
            }
        }
        content = Ui.column(activity).apply { setPadding(dp(16), dp(8), dp(16), dp(20)) }
        (fixedContent?.parent as? ViewGroup)?.removeView(fixedContent)
        val body = fixedContent ?: ScrollView(activity).apply { isFillViewport = true; addView(content) }
        root.addView(header, match())
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        showPageContent(destination, root, canSlide, goBack)
    }

    private val agentTitle get() = if (local) "本机 Codex 设置" else "电脑 Agent 设置"

    private fun showHome() = composePage(Page.HOME, "设置", ::dismiss) {
        sections(Action(agentTitle, ::showAgentSettings) { deviceName })
    }

    fun showOpenSourceLicenses() = composePage(Page.LICENSES, "开放源代码许可", ::showHome) {
        listOf(Section("第三方项目", actions = OpenSourceLicenses.projects.map { project ->
            Action(project.name, { showLicense(project) }) { project.license }
        }))
    }

    private fun showLicense(project: OpenSourceLicenses.Project) {
        val text = try {
            project.assets.joinToString("\n\n") { path ->
                activity.assets.open(path).bufferedReader().use { it.readText().trimEnd() }
            }
        } catch (_: IOException) {
            showError(IOException("无法读取许可文本"))
            return
        }
        page(Page.LICENSE, project.name, goBack = ::showOpenSourceLicenses)
        row("项目主页", project.url) { openExternalUrl(project.url) }
        content.addView(Ui.text(activity, text, 14f, Ui.TEXT).apply {
            setTextIsSelectable(true)
            setLineSpacing(dp(3).toFloat(), 1f)
        }, match(12))
    }

    fun openRepository() = openExternalUrl(OpenSourceLicenses.REPOSITORY)

    private fun openExternalUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            showError(IllegalStateException("没有可打开链接的应用\n$url"))
        }
    }

    private fun showAgentSettings() = composePage(Page.AGENT, agentTitle, ::showHome) {
        agentSections(Action("模型供应商", { loadCatalog() }) { deviceName })
    }

    private fun composePage(
        destination: Page,
        title: String,
        goBack: () -> Unit,
        groups: () -> List<Section>,
    ) {
        pageVersion++
        refreshPreviews = null
        val compose = ComposeView(activity).apply {
            background = appearance?.drawable(AppearanceArea.SETTINGS)
            setContent {
                MiuixSettingsScreen(
                    title = title,
                    darkTheme = { Ui.dark },
                    sections = {
                        // Observe changes made by child dialogs and system pickers.
                        refresh
                        groups()
                    },
                    onBack = goBack,
                    onChanged = ::refreshActionSummaries,
                    hasWallpaper = {
                        refresh
                        appearance?.bitmap(AppearanceArea.SETTINGS) != null
                    },
                )
            }
        }
        showPageContent(destination, compose, { true }, goBack)
    }

    private fun showPageContent(destination: Page, view: View, canSlide: () -> Boolean, goBack: () -> Unit) {
        val ancestors = generateSequence(destination.parent) { it.parent }.toSet()
        pageViews.keys.retainAll(ancestors)
        val previous = pageViews[destination.parent]
        pageViews[destination] = view
        settingsRoot = view
        val motion = if (destination.parent == null) BackMotion.PAGE else BackMotion.SUBPAGE
        dialog.setBackContent(view, motion, previous, canSlide, goBack)
        dialog.window?.setBackgroundDrawable(ColorDrawable(
            if (motion == BackMotion.PAGE) Color.TRANSPARENT else Ui.BACKGROUND,
        ))
    }

    fun showAppearance() {
        val style = appearance ?: return
        gallery?.close()
        gallery = null
        page(Page.APPEARANCE, "背景与透明度", goBack = ::showHome)
        val previews = mutableListOf<() -> Unit>()
        for (area in AppearanceArea.entries) {
            val card = appearanceCard(area.title)
            val preview = android.widget.FrameLayout(activity).apply {
                background = style.drawable(area)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            card.addView(preview, LinearLayout.LayoutParams(-1, dp(88)).apply { topMargin = dp(8) })
            val source = Ui.text(activity, style.summary(area), 13f, Ui.MUTED).apply {
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            card.addView(source, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            card.addView(Ui.button(activity, "更换${area.title}") { chooseBackground(area, style) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addOpacity(card, "遮罩不透明度", style.mask(area), preview = { value ->
                (preview.background as? WallpaperDrawable)?.mask = value
            }) { style.setMask(area, it) }
            previews += {
                preview.background = style.drawable(area)
                source.text = style.summary(area)
            }
        }
        val bubbles = appearanceCard("对话气泡")
        val sample = android.widget.FrameLayout(activity)
        val bubble = Ui.text(activity, "存在问题可反馈给开发者", 14f).apply {
            setPadding(dp(14), dp(9), dp(14), dp(9))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        sample.addView(bubble, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply {
            marginEnd = dp(12)
        })
        bubbles.addView(sample, LinearLayout.LayoutParams(-1, dp(58)).apply { topMargin = dp(8) })
        fun renderBubble(value: Int) {
            bubble.background = Ui.rounded(appearanceOpacity(Ui.SURFACE_ALT, value), dp(19).toFloat())
        }
        addOpacity(bubbles, "气泡不透明度", style.bubbleOpacity(), ::renderBubble) { style.setBubbleOpacity(it) }
        previews += {
            sample.background = style.drawable(AppearanceArea.CHAT)
            renderBubble(style.bubbleOpacity())
        }
        refreshPreviews = { previews.forEach { it() } }
        refreshPreviews?.invoke()
    }

    private fun appearanceCard(title: String) = Ui.column(activity).apply {
        background = Ui.rounded(Ui.SURFACE, dp(14).toFloat())
        setPadding(dp(12), dp(10), dp(12), dp(8))
        addView(Ui.text(activity, title, 15f, bold = true).apply { isAccessibilityHeading = true }, match())
        content.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
    }

    private fun addOpacity(card: LinearLayout, title: String, initial: Int, preview: (Int) -> Unit, save: (Int) -> Unit) {
        val value = Ui.text(activity, "", 13f, Ui.TEXT)
        fun display(percent: Int) { value.text = "$title · $percent%" }
        display(initial)
        card.addView(value, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        card.addView(SeekBar(activity).apply {
            max = 100
            progress = initial
            contentDescription = title
            val tint = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
            progressTintList = tint
            thumbTintList = tint
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var dragging = false
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    display(progress)
                    preview(progress)
                    if (!dragging) save(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(bar: SeekBar) { dragging = false; save(bar.progress) }
            })
        }, match())
    }

    private fun chooseBackground(area: AppearanceArea, style: ChatAppearance) {
        val workPage = style.workPage(area)
        val options = listOfNotNull("选择本地图片", "随机图片 · 每 3 天更新", "AI 拟人图库", "恢复默认背景",
            workPage?.let { "查看当前作品来源与授权" })
        MiuixDialog.Builder(activity).setTitle(area.title)
            .setItems(options.toTypedArray()) { _, index ->
                when (index) {
                    0 -> pickBackground(area)
                    1 -> style.random(area, reportBackground)
                    2 -> showGallery()
                    3 -> style.reset(area)
                    4 -> workPage?.let {
                        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                    }
                }
            }.setNegativeButton("取消", null).show()
    }

    private fun showGallery() {
        gallery?.close()
        val browser = AppearanceGallery(activity)
        gallery = browser
        page(Page.GALLERY, "AI 拟人图库", browser.view, canSlide = { !browser.showingWorks }) {
            if (!browser.back()) showAppearance()
        }
    }

    fun cropLocalImage(area: AppearanceArea, uri: Uri) {
        val style = appearance ?: return
        cropLoad?.cancel()
        val body = Ui.column(activity)
        fun cancel() {
            cropLoad?.cancel()
            cropLoad = null
            showAppearance()
        }
        if (Page.APPEARANCE !in pageViews) showAppearance()
        page(Page.CROP, "裁剪 · ${area.title}", body, goBack = ::cancel)
        val loading = Ui.text(activity, "正在读取图片…", 14f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(loading, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(Ui.button(activity, "取消") { cancel() }, match())
        val version = pageVersion
        cropLoad = style.loadLocal(uri) { job, result ->
            if (cropLoad !== job || pageVersion != version || !dialog.isShowing) return@loadLocal
            cropLoad = null
            val image = result.getOrNull()
            if (image == null) {
                loading.text = "无法读取图片，请返回后重试"
                return@loadLocal
            }
            body.removeAllViews()
            val editor = WallpaperCropView(activity, image) { backgroundAspect(area) }
            body.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
            val controls = Ui.column(activity, vertical = false).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(4), dp(12), 0)
            }
            controls.addView(Ui.text(activity, "拖动图片调整位置，双指缩放", 13f, Ui.TEXT), LinearLayout.LayoutParams(0, -2, 1f))
            controls.addView(Ui.iconButton(activity, "minus", "缩小图片") { editor.zoomBy(0.8f) })
            controls.addView(Ui.iconButton(activity, "plus", "放大图片") { editor.zoomBy(1.25f) })
            body.addView(controls, match())
            val actions = Ui.column(activity, vertical = false).apply {
                setPadding(dp(12), dp(4), dp(12), dp(8))
            }
            fun addAction(label: String, primary: Boolean = false, run: () -> Unit) {
                actions.addView(Ui.button(activity, label, primary) { run() }, LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (actions.childCount != 0) marginStart = dp(6)
                })
            }
            addAction("取消", run = ::cancel)
            addAction("重置", run = editor::reset)
            addAction("确认裁剪", primary = true) confirm@{
                val region = editor.selectedRegion() ?: return@confirm
                showAppearance()
                style.saveCrop(area, image, region, reportBackground)
            }
            body.addView(actions, match())
        }
    }

    private fun backgroundAspect(area: AppearanceArea): Float {
        val target = if (area == AppearanceArea.SETTINGS) settingsRoot else activity.backgroundTarget(area)
        val decor = dialog.window?.decorView ?: activity.window.decorView
        val width = target?.width?.takeIf { it > 0 } ?: target?.layoutParams?.width?.takeIf { it > 0 }
            ?: decor.width.coerceAtLeast(1)
        val height = target?.height?.takeIf { it > 0 } ?: decor.height.coerceAtLeast(1)
        return width.toFloat() / height
    }

    private fun loadCatalog(open: String? = null) {
        val blocked = supported()
        val previous = open?.let { id -> catalogs.firstOrNull { it.string("id") == id } }
        val destination = when {
            previous != null -> Page.REFRESH_PROVIDER
            open == null -> Page.CATALOG
            else -> Page.REFRESH_NEW_PROVIDER
        }
        page(destination, if (open == null) "模型供应商" else "模型管理") {
            when {
                previous != null -> showProvider(previous)
                open == null -> showAgentSettings()
                else -> showCatalog()
            }
        }
        if (blocked != null) {
            note(blocked)
            button("重试") { loadCatalog(open) }
            return
        }
        val version = pageVersion
        note("正在获取 $deviceName 的模型列表…")
        request("catalog", JSONObject(), false) { result, error ->
            if (!dialog.isShowing || version != pageVersion) return@request
            if (result == null || error != null) {
                content.removeAllViews()
                note(error?.message ?: "无法加载模型供应商")
                button("重试") { loadCatalog(open) }
                return@request
            }
            gptProviderSettings = result.optBoolean("gptProviderSettings")
            val groups = result.arrayAt("providers")
            catalogs = (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }
                .filter { !local || it.isLocalThirdPartyProvider() }
            val selected = open?.let { id -> catalogs.firstOrNull { it.string("id") == id } }
            when {
                selected != null -> showProvider(selected)
                previous != null && modelDraft?.hasChanges == true -> {
                    showProvider(previous)
                    showError(IllegalStateException("这台电脑暂未返回该供应商，请检查后重试。"))
                }
                else -> showCatalog(result.arrayAt("warnings").optString(0))
            }
        }
    }

    private fun showCatalog(warning: String = "") {
        modelDraft = null
        page(Page.CATALOG, "模型供应商", goBack = ::showAgentSettings)
        note(deviceName)
        if (warning.isNotEmpty()) note(warning)
        button("添加供应商") { showEditor() }
        if (gptProviderSettings) row("当前 Codex 供应商设置", if (local) "编辑手机端 config.toml" else "编辑电脑端 config.toml") { loadNativeProviderSettings() }
        button("刷新模型列表") { loadCatalog() }
        catalogs.forEach { provider ->
            val modelCount = provider.arrayAt("models").length()
            val subtitle = listOf(provider.string("notes"), "$modelCount 个模型").filter(String::isNotEmpty).joinToString(" · ")
            row(provider.string("name"), subtitle) { showProvider(provider) }
        }
    }

    private fun showProvider(provider: JSONObject) {
        val id = provider.string("id")
        val models = provider.arrayAt("models")
        val draft = modelDraft?.takeIf { it.providerId == id }?.apply { replaceModels(models) }
            ?: ProviderModelDraft(id, models).also { modelDraft = it }
        val status = Ui.text(activity, if (draft.hasChanges) "有未保存改动，返回时自动保存" else "暂无未保存改动", 13f, Ui.MUTED)
        var deleting = false
        page(Page.PROVIDER, provider.string("name"), canSlide = { !savingModels && !deleting && !draft.hasChanges }) {
            if (!deleting) saveModelsAndReturn(provider, draft, status)
        }
        if (id.isNotEmpty()) button("编辑供应商配置") { if (!savingModels) loadEditor(id) }
        provider.string("warning").takeIf(String::isNotEmpty)?.let(::note)
        val rows = Ui.column(activity)
        val rowViews = mutableMapOf<String, View>()

        fun updateStatus() {
            status.text = if (draft.hasChanges) "有未保存改动，返回时自动保存" else "暂无未保存改动"
        }

        fun updateRow(model: JSONObject, index: Int, onChange: (String, Boolean) -> Unit) {
            val modelId = model.string("model").ifEmpty { model.string("id") }
            rowViews[modelId]?.let(rows::removeView)
            val view = modelRow(model, modelId, onChange)
            rowViews[modelId] = view
            rows.addView(view, index, match(6))
        }

        fun refreshRow(modelId: String, onChange: (String, Boolean) -> Unit) {
            val preview = draft.preview()
            val index = preview.indexOfFirst { it.string("model").ifEmpty { it.string("id") } == modelId }
            if (index >= 0) updateRow(preview[index], index, onChange)
        }

        fun toggle(modelId: String, blacklisted: Boolean) {
            if (savingModels) return
            draft.setBlacklisted(modelId, blacklisted)
            updateStatus()
            refreshRow(modelId, ::toggle)
        }

        val preview = draft.preview()
        val empty = Ui.text(activity, "暂无可用模型。可以手动添加模型 ID。", 13f, Ui.MUTED)
        empty.visibility = if (preview.isEmpty()) View.VISIBLE else View.GONE
        button("添加模型") {
            if (!savingModels) promptModel { modelId ->
                draft.add(modelId)
                updateStatus()
                empty.visibility = View.GONE
                refreshRow(modelId, ::toggle)
            }
        }
        button("刷新模型列表") { if (!savingModels) loadCatalog(id) }
        if (id.isNotEmpty()) button("删除供应商") {
            if (!savingModels && !deleting) {
                confirmProviderDeletion(provider, draft) {
                    val version = pageVersion
                    deleting = true
                    status.text = "正在删除供应商…"
                    setEnabledRecursively(content, false)
                    request("delete", JSONObject().put("id", id), false) { result, error ->
                        deleting = false
                        val deleted = error == null && result?.optBoolean("deleted") == true
                        if (deleted) catalogChanged()
                        if (!dialog.isShowing || version != pageVersion) return@request
                        if (deleted) {
                            catalogs = catalogs.filterNot { it.string("id") == id }
                            modelDraft = null
                            android.widget.Toast.makeText(activity, "供应商已删除", android.widget.Toast.LENGTH_SHORT).show()
                            loadCatalog()
                        } else {
                            setEnabledRecursively(content, true)
                            updateStatus()
                            showError(error)
                        }
                    }
                }
            }
        }
        content.addView(status, match(8))
        content.addView(empty, match(8))
        content.addView(rows, match())
        preview.forEachIndexed { index, model -> updateRow(model, index, ::toggle) }
    }

    private fun confirmProviderDeletion(provider: JSONObject, draft: ProviderModelDraft, delete: () -> Unit) {
        val unsaved = if (draft.hasChanges) "\n此供应商未保存的模型改动将被丢弃。" else ""
        MiuixDialog.Builder(activity).setTitle("删除「${provider.string("name")}」？")
            .setMessage("仅从此 Agent 删除，不影响 CC Switch、Codex 登录或聊天记录。使用此供应商的会话可能需要重新选择供应商。$unsaved")
            .setPositiveButton("删除") { _, _ -> delete() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun modelRow(model: JSONObject, modelId: String, onChange: (String, Boolean) -> Unit): View {
        val name = model.string("displayName").ifEmpty { modelId }
        val hidden = model.optBoolean("blacklisted")
        val line = Ui.column(activity, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(8), dp(4))
            background = Ui.rounded(Ui.SURFACE, dp(10).toFloat())
        }
        val labels = Ui.column(activity)
        fun label(text: String, size: Float, muted: Boolean) = Ui.text(activity, text, size, if (muted || hidden) Ui.MUTED else Ui.TEXT).apply {
            if (hidden) paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
        labels.addView(label(name, 14f, false), match())
        if (name != modelId) labels.addView(label(modelId, 12f, true), match())
        line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        val verb = if (hidden) "显示" else "隐藏"
        line.addView(Ui.button(activity, verb) { onChange(modelId, !hidden) }.apply {
            contentDescription = "$verb $modelId"
        }, LinearLayout.LayoutParams(-2, dp(48)))
        return line
    }

    private fun promptModel(add: (String) -> Unit) {
        val input = MiuixFieldView(activity, "上游模型 ID")
        val prompt = MiuixDialog.Builder(activity).setTitle("添加模型").setView(input)
            .setPositiveButton("添加", null).setNegativeButton("取消", null).create()
        prompt.show()
        prompt.getButton(MiuixDialog.POSITIVE).setOnClickListener {
            val id = input.text.trim()
            if (id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 256 || id.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                input.error = if (id.isEmpty()) "模型 ID 为必填项" else "请输入有效的模型 ID"
                input.focusInput()
                return@setOnClickListener
            }
            add(id)
            prompt.dismiss()
        }
    }

    private fun saveModelsAndReturn(provider: JSONObject, draft: ProviderModelDraft, status: TextView) {
        if (savingModels) {
            @Suppress("DEPRECATION")
            status.announceForAccessibility("正在保存模型改动，请稍候")
            android.widget.Toast.makeText(activity, "正在保存模型改动，请稍候", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val changes = draft.changes()
        if (changes.length() == 0) {
            showCatalog()
            return
        }
        savingModels = true
        status.text = "正在保存模型改动…"
        setEnabledRecursively(content, false)
        val version = pageVersion
        val params = JSONObject().put("id", draft.providerId).put("changes", changes)
        request("models", params, false) { result, error ->
            savingModels = false
            val saved = error == null && result?.optBoolean("saved") == true
            if (saved) catalogChanged()
            if (!dialog.isShowing || version != pageVersion) return@request
            if (saved) {
                provider.put("models", JSONArray(draft.preview()))
                showCatalog()
            } else {
                setEnabledRecursively(content, true)
                status.text = "保存失败，改动仍保留；可重试或继续编辑"
                val message = InteractionCopy.modelSaveError(error?.message)
                MiuixDialog.Builder(activity).setTitle("模型改动未保存").setMessage(message)
                    .setPositiveButton("重试") { _, _ -> saveModelsAndReturn(provider, draft, status) }
                    .setNegativeButton("继续编辑", null)
                    .setNeutralButton("放弃改动") { _, _ -> showCatalog() }
                    .show()
            }
        }
    }

    private fun setEnabledRecursively(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            setEnabledRecursively(view.getChildAt(index), enabled)
        }
    }

    private fun loadNativeProviderSettings() {
        page(Page.NATIVE_PROVIDER, "当前 Codex 供应商设置", goBack = { showCatalog() })
        val version = pageVersion
        note("正在读取 $deviceName 的 Codex 配置…")
        request("nativeCapabilities", JSONObject(), false) { result, error ->
            if (!dialog.isShowing || version != pageVersion) return@request
            if (result != null && error == null) {
                showNativeProviderSettings(result)
            } else {
                content.removeAllViews()
                note(error?.message ?: "无法读取 Codex 配置")
                button("重试") { loadNativeProviderSettings() }
            }
        }
    }

    private fun showNativeProviderSettings(details: JSONObject) {
        val initial = details.optBoolean("gptOfficial")
        var checked by mutableStateOf(initial)
        var saving by mutableStateOf(false)
        page(Page.NATIVE_PROVIDER, "当前 Codex 供应商设置", canSlide = { !saving && checked == initial }) {
            if (!saving) {
                if (checked == initial) showCatalog() else confirmProviderChanges { showCatalog() }
            }
        }
        if (!details.optBoolean("editable")) {
            note("当前 Codex 配置没有可编辑的自定义供应商。内置官方供应商无需开启此选项。")
            return
        }
        gptProviderToggle({ checked }, { !saving }) { checked = it }
        note("保存到 $deviceName 的 Codex 配置。保存后请在新建或重新打开的会话中使用。")
        button("保存", primary = true) { view ->
            if (checked == initial) {
                showCatalog()
                return@button
            }
            val save = view as MiuixButtonView
            val version = pageVersion
            saving = true
            save.isEnabled = false
            save.text = "正在保存…"
            val params = JSONObject().put("gptOfficial", checked).put("version", details.string("version"))
            request("nativeCapabilities", params, false) { result, error ->
                if (!dialog.isShowing || version != pageVersion) return@request
                saving = false
                if (result != null && error == null) {
                    catalogChanged()
                    loadCatalog()
                } else {
                    save.isEnabled = true
                    save.text = "保存"
                    showError(error)
                }
            }
        }
    }

    private fun gptProviderToggle(checked: () -> Boolean, enabled: () -> Boolean, change: (Boolean) -> Unit) {
        val title = "模型提供商是否为GPT官方"
        val tile = ComposeView(activity).apply {
            setContent {
                CodexMiuixTheme {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SwitchPreference(
                            title = title,
                            checked = checked(),
                            enabled = enabled(),
                            onCheckedChange = change,
                            modifier = Modifier.weight(1f),
                        )
                        MiuixActionIcon("help", "$title：说明") {
                            MiuixDialog.Builder(activity).setTitle(title)
                                .setMessage("如果选项开启会打开wss和远程压缩（Compact），这有助于加快响应和减少压缩上下文的Token消耗\n\n仅在接入地址支持这两项能力时开启。远程压缩配置需要 Codex CLI 0.162.0 或更高版本。")
                                .setPositiveButton("确定", null).show()
                        }
                    }
                }
            }
        }
        content.addView(tile, match(8))
    }

    private fun loadEditor(id: String) {
        val version = pageVersion
        request("details", JSONObject().put("id", id), false) { result, error ->
            if (!dialog.isShowing || version != pageVersion || savingModels) return@request
            if (result != null && error == null) showEditor(id, result) else showError(error)
        }
    }

    private fun showEditor(id: String = "", details: JSONObject = JSONObject()) {
        editorSaving = false
        var editorDirty: () -> Boolean = { false }
        val destination = if (id.isEmpty()) Page.ADD_PROVIDER else Page.EDIT_PROVIDER
        page(destination, if (id.isEmpty()) "添加供应商" else "编辑供应商", canSlide = { !editorSaving && !editorDirty() }) {
            if (editorSaving) {
                android.widget.Toast.makeText(activity, "正在保存供应商配置，请稍候", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                confirmEditorBack(editorDirty, id)
            }
        }
        note(if (id.isEmpty() || details.optBoolean("local")) "保存到 $deviceName 的 Agent" else "保存到 $deviceName 的 CC Switch")
        val initialName = details.string("name")
        val initialNotes = details.string("notes")
        val initialBase = details.string("baseUrl")
        val initialModel = details.string("defaultModel")
        val name = field("名称", initialName)
        val notes = field("备注", initialNotes)
        val official = details.optBoolean("official")
        val endpoint = if (official) null else field("接入地址", initialBase, KeyboardType.Uri)
        val key = if (official) null else MiuixFieldView(
            activity, "API Key", keyboardType = KeyboardType.Password, secret = true,
            supportingText = "留空保留现有密钥",
        ).also { content.addView(it, match(12)) }
        if (official) note("官方账号登录由电脑端 Codex 管理。")
        else {
            note(if (local) "配置保存在手机上的本机运行扩展中。保存后，选择该供应商即可使用。"
                else "修改现有接入地址或密钥需要 P2P 直连。保存后，选择该供应商即可使用更新后的接入配置。")
            if (id.isNotEmpty() && initialBase.isEmpty()) note("现有地址未显示，留空可保留。")
        }
        val defaultModel = field("默认模型 ID（可选）", initialModel)
        val initialGptOfficial = details.optBoolean("gptOfficial")
        var gptOfficial by mutableStateOf(initialGptOfficial)
        var toggleEnabled by mutableStateOf(true)
        if (gptProviderSettings) gptProviderToggle({ gptOfficial }, { !official && toggleEnabled }) { gptOfficial = it }
        editorDirty = {
            name.text != initialName || notes.text != initialNotes ||
                (endpoint?.text ?: initialBase) != initialBase ||
                defaultModel.text != initialModel || gptOfficial != initialGptOfficial || key?.text?.isNotEmpty() == true
        }
        button("保存", primary = true) { view ->
            val save = view as MiuixButtonView
            val params = JSONObject().put("id", id)
            val label = name.text.trim()
            if (label.isEmpty()) {
                name.error = "供应商名称为必填项"
                name.focusInput()
                return@button
            }
            if (id.isEmpty() || label != initialName) params.put("name", label)
            val note = notes.text
            if (note != initialNotes) params.put("notes", note)
            val base = endpoint?.text?.trim().orEmpty()
            if (id.isEmpty() && base.isEmpty()) {
                endpoint?.error = "接入地址为必填项"
                endpoint?.focusInput()
                return@button
            }
            if (base != initialBase) params.put("baseUrl", base)
            val model = defaultModel.text.trim()
            if (model != initialModel) params.put("defaultModel", model)
            if (gptOfficial != initialGptOfficial) params.put("gptOfficial", gptOfficial)
            val apiKey = key?.text?.trim().orEmpty()
            val requiresDirect = apiKey.isNotEmpty() || id.isNotEmpty() && params.has("baseUrl")
            if (requiresDirect && !direct()) {
                if (apiKey.isNotEmpty()) key?.error = "请等电脑 P2P 直连后再修改密钥"
                else endpoint?.error = "请等电脑 P2P 直连后再修改接入地址"
                (if (apiKey.isNotEmpty()) key else endpoint)?.focusInput()
                return@button
            }
            if (apiKey.isNotEmpty()) params.put("apiKey", apiKey)
            val version = pageVersion
            editorSaving = true
            toggleEnabled = false
            save.isEnabled = false
            save.text = "正在保存…"
            setEnabledRecursively(content, false)
            request("save", params, requiresDirect) { result, error ->
                editorSaving = false
                toggleEnabled = true
                if (error == null) catalogChanged()
                if (!dialog.isShowing || version != pageVersion) return@request
                setEnabledRecursively(content, true)
                save.isEnabled = true
                save.text = "保存"
                if (result != null && error == null) loadCatalog(result.string("id")) else showError(error)
            }
        }
        content.isFocusableInTouchMode = true
        content.requestFocus()
    }

    private fun confirmEditorBack(isDirty: () -> Boolean, id: String) {
        val leave = {
            catalogs.firstOrNull { id.isNotEmpty() && it.string("id") == id }?.let(::showProvider) ?: showCatalog()
        }
        if (!isDirty()) {
            leave()
            return
        }
        confirmProviderChanges(leave)
    }

    private fun confirmProviderChanges(leave: () -> Unit) {
        MiuixDialog.Builder(activity).setTitle("放弃未保存的更改？")
            .setMessage("供应商配置尚未保存。")
            .setPositiveButton("放弃更改") { _, _ -> leave() }
            .setNegativeButton("继续编辑", null)
            .show()
    }

    private fun field(label: String, value: String, keyboardType: KeyboardType = KeyboardType.Text) =
        MiuixFieldView(activity, label, value, keyboardType).also { content.addView(it, match(12)) }

    private fun showError(error: Exception?) {
        MiuixDialog.Builder(activity).setTitle("操作未完成").setMessage(error?.message ?: "操作失败，请重试")
            .setPositiveButton("确定", null).show()
    }

    private fun note(text: String) {
        content.addView(Ui.text(activity, text, 13f, Ui.MUTED).apply { setPadding(dp(4), dp(4), dp(4), dp(8)) }, match())
    }

    private fun button(text: String, primary: Boolean = false, click: (View) -> Unit) {
        content.addView(Ui.button(activity, text, primary, click), match(8))
    }

    private fun row(title: String, subtitle: String? = null, click: () -> Unit) {
        val tile = ComposeView(activity).apply {
            setContent { CodexMiuixTheme { ArrowPreference(title = title, summary = subtitle, onClick = click) } }
        }
        content.addView(tile, match(8))
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
    private fun match(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
