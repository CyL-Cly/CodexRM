package com.codexrm.mobile

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

internal class SettingsPage(
    private val activity: MainActivity,
    private val deviceName: String,
    private val supported: () -> String?,
    private val direct: () -> Boolean,
    private val request: (String, JSONObject, Boolean, RpcCallback) -> Unit,
    private val catalogChanged: () -> Unit,
    private val actions: List<Action>,
) {
    data class Action(val title: String, val open: () -> Unit, val summary: (() -> String)? = null)

    private val actionSummaries = mutableMapOf<TextView, () -> String>()
    private var back: () -> Unit = { dismiss() }
    private val dialog = object : Dialog(activity) {
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onBackPressed() = back()
    }
    private lateinit var content: LinearLayout
    private var pageVersion = 0
    private var savingModels = false
    private var editorSaving = false
    private var modelDraft: ProviderModelDraft? = null
    private var catalogs = emptyList<JSONObject>()

    fun show() {
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(false)
        showHome()
        dialog.show()
        @Suppress("DEPRECATION")
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    fun dismiss() { dialog.dismiss() }

    fun refreshActionSummaries() {
        actionSummaries.forEach { (view, summary) -> view.text = summary() }
    }

    private fun page(title: String, goBack: () -> Unit) {
        actionSummaries.clear()
        pageVersion++
        back = goBack
        val root = Ui.column(activity).apply { setBackgroundColor(Ui.BACKGROUND) }
        val header = Ui.column(activity, false).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(Ui.iconButton(activity, "back", "返回") { goBack() })
        header.addView(Ui.text(activity, title, 21f, bold = true).apply {
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        content = Ui.column(activity).apply { setPadding(dp(16), dp(8), dp(16), dp(20)) }
        val scroll = ScrollView(activity).apply { isFillViewport = true; addView(content) }
        root.addView(header, match())
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
    }

    private fun showHome() {
        page("设置") { dismiss() }
        section("模型与连接")
        row("模型供应商", deviceName) { loadCatalog() }
        val appActions = actions.filter { it.title == "工作区设置" || it.title == "下载位置" }
        val supportActions = actions.filterNot { it in appActions }
        if (appActions.isNotEmpty()) {
            section("应用与数据")
            appActions.forEach(::actionRow)
        }
        if (supportActions.isNotEmpty()) {
            section("支持")
            supportActions.forEach(::actionRow)
        }
    }

    private fun actionRow(action: Action) {
        val summary = action.summary
        val view = row(action.title, summary?.invoke(), action.open)
        if (view != null && summary != null) actionSummaries[view] = summary
    }

    private fun section(title: String) {
        content.addView(Ui.text(activity, title, 13f, Ui.MUTED, bold = true).apply {
            setPadding(dp(4), dp(12), dp(4), dp(4))
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, match(4))
    }

    private fun loadCatalog(open: String? = null) {
        val blocked = supported()
        val previous = open?.let { id -> catalogs.firstOrNull { it.string("id") == id } }
        page(if (open == null) "模型供应商" else "模型管理") {
            when {
                previous != null -> showProvider(previous)
                open == null -> showHome()
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
            val groups = result.arrayAt("providers")
            catalogs = (0 until groups.length()).mapNotNull { groups.optJSONObject(it) }
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
        page("模型供应商") { showHome() }
        note(deviceName)
        if (warning.isNotEmpty()) note(warning)
        button("添加供应商") { showEditor() }
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
        page(provider.string("name")) { saveModelsAndReturn(provider, draft, status) }
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
        content.addView(status, match(8))
        content.addView(empty, match(8))
        content.addView(rows, match())
        preview.forEachIndexed { index, model -> updateRow(model, index, ::toggle) }
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
        val input = Ui.boxInput(activity, "上游模型 ID").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val form = Ui.column(activity).apply {
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(input, match())
        }
        val prompt = AlertDialog.Builder(activity).setTitle("添加模型").setView(form)
            .setPositiveButton("添加", null).setNegativeButton("取消", null).create()
        prompt.show()
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val id = input.text.toString().trim()
            if (id.isEmpty() || id.toByteArray(Charsets.UTF_8).size > 256 || id.any { it == '\r' || it == '\n' || it == '\u0000' }) {
                input.error = if (id.isEmpty()) "模型 ID 为必填项" else "请输入有效的模型 ID"
                input.requestFocus()
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
                AlertDialog.Builder(activity).setTitle("模型改动未保存").setMessage(message)
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
        page(if (id.isEmpty()) "添加供应商" else "编辑供应商") {
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
        val endpoint = if (official) null else field("接入地址", initialBase).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val key = if (official) null else field("API Key", "", "留空保留现有密钥").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            if (android.os.Build.VERSION.SDK_INT >= 26) importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        if (official) note("官方账号登录由电脑端 Codex 管理。")
        else {
            note("修改现有接入地址或密钥需要 P2P 直连。保存后，选择该供应商即可使用更新后的接入配置。")
            if (id.isNotEmpty() && initialBase.isEmpty()) note("现有地址未显示，留空可保留。")
        }
        val defaultModel = field("默认模型 ID（可选）", initialModel)
        editorDirty = {
            name.text.toString() != initialName || notes.text.toString() != initialNotes ||
                (endpoint?.text?.toString() ?: initialBase) != initialBase ||
                defaultModel.text.toString() != initialModel || key?.text?.isNotEmpty() == true
        }
        button("保存", primary = true) { view ->
            val save = view as android.widget.Button
            val params = JSONObject().put("id", id)
            val label = name.text.toString().trim()
            if (label.isEmpty()) {
                name.error = "供应商名称为必填项"
                name.requestFocus()
                return@button
            }
            if (id.isEmpty() || label != initialName) params.put("name", label)
            val note = notes.text.toString()
            if (note != initialNotes) params.put("notes", note)
            val base = endpoint?.text?.toString()?.trim().orEmpty()
            if (id.isEmpty() && base.isEmpty()) {
                endpoint?.error = "接入地址为必填项"
                endpoint?.requestFocus()
                return@button
            }
            if (base != initialBase) params.put("baseUrl", base)
            val model = defaultModel.text.toString().trim()
            if (model != initialModel) params.put("defaultModel", model)
            val apiKey = key?.text?.toString()?.trim().orEmpty()
            val requiresDirect = apiKey.isNotEmpty() || id.isNotEmpty() && params.has("baseUrl")
            if (requiresDirect && !direct()) {
                if (apiKey.isNotEmpty()) key?.error = "请等电脑 P2P 直连后再修改密钥"
                else endpoint?.error = "请等电脑 P2P 直连后再修改接入地址"
                (if (apiKey.isNotEmpty()) key else endpoint)?.requestFocus()
                return@button
            }
            if (apiKey.isNotEmpty()) params.put("apiKey", apiKey)
            val version = pageVersion
            editorSaving = true
            save.isEnabled = false
            save.text = "正在保存…"
            setEnabledRecursively(content, false)
            request("save", params, requiresDirect) { result, error ->
                editorSaving = false
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
        AlertDialog.Builder(activity).setTitle("放弃未保存的更改？")
            .setMessage("供应商配置尚未保存。")
            .setPositiveButton("放弃更改") { _, _ -> leave() }
            .setNegativeButton("继续编辑", null)
            .show()
    }

    private fun field(label: String, value: String, hint: String = label) = Ui.boxInput(activity, hint).apply {
        content.addView(Ui.text(activity, label, 12f, Ui.MUTED), match(12))
        setText(value)
        contentDescription = label
        content.addView(this, match(4))
    }

    private fun showError(error: Exception?) {
        AlertDialog.Builder(activity).setTitle("操作未完成").setMessage(error?.message ?: "操作失败，请重试")
            .setPositiveButton("确定", null).show()
    }

    private fun note(text: String) {
        content.addView(Ui.text(activity, text, 13f, Ui.MUTED).apply { setPadding(dp(4), dp(4), dp(4), dp(8)) }, match())
    }

    private fun button(text: String, primary: Boolean = false, click: (View) -> Unit) {
        content.addView(Ui.button(activity, text, primary, click), match(8))
    }

    private fun row(title: String, subtitle: String? = null, click: () -> Unit): TextView? {
        val summary = subtitle?.takeIf(String::isNotEmpty)?.let { Ui.text(activity, it, 12f, Ui.MUTED) }
        val tile = Ui.column(activity, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(14), dp(8), dp(10), dp(8))
            background = Ui.ripple(Ui.SURFACE, dp(10).toFloat())
            isFocusable = true
            setOnClickListener { click() }
            val labels = Ui.column(activity).apply {
                addView(Ui.text(activity, title, 16f), match())
                if (summary != null) addView(summary, match(3))
            }
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            addView(IconView(activity, "right", Ui.MUTED), LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        content.addView(tile, match(8))
        return summary
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
    private fun match(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}
