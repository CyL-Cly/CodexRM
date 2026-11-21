package com.codexrm.mobile

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Toast
import java.io.IOException
import kotlin.concurrent.thread

internal data class PhoneFolder(val tree: Uri, val id: String) {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    val label: String get() = id.substringAfter(':').ifEmpty { "已授权目录" }
}

internal class WorkspaceFilesDialog(
    private val activity: Activity,
    private val source: RemoteFile,
    private val client: () -> CodexClient?,
    private val downloads: WorkspaceDownloads,
    private val chooseFolder: () -> Unit,
    private val openRemote: (RemoteFile, String, PhoneFolder?) -> Unit,
    private val copyToPhone: (List<Pair<RemoteFile, String>>, PhoneFolder?) -> Boolean,
    private val openLocal: (Uri, String) -> Unit,
    private val dismissed: () -> Unit,
) {
    private data class Entry(val key: String, val name: String, val dir: Boolean, val size: Long, val flags: Int = 0)

    private val handler = Handler(Looper.getMainLooper())
    private var tree = rootFolder()?.tree
    private var selectionMode = false
    private val selected = linkedSetOf<String>()
    private var actionDialog: Dialog? = null
    private var localWrite = false
    private val dialog = Dialog(activity)
    private val remote = Pane("电脑", "monitor")
    private val phone = Pane("手机", "phone")
    private var active = remote
    private val pathLabel = Ui.text(activity, size = 16f, bold = true).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.START
    }
    private val summary = Ui.text(activity, size = 12f, color = Ui.MUTED).apply {
        maxLines = 2
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val bottom = Ui.column(activity, false).apply { setBackgroundColor(Ui.SURFACE) }
    private val panes = Ui.column(activity, false)

    fun show() {
        val root = Ui.column(activity).apply {
            setBackgroundColor(Ui.BACKGROUND)
            Ui.applyWindowInsets(this)
        }
        val toolbar = Ui.column(activity, false).apply {
            minimumHeight = dp(62)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Ui.SURFACE)
        }
        toolbar.addView(Ui.iconButton(activity, "close", "关闭文件管理") { dismiss() })
        val location = Ui.column(activity).apply { addView(pathLabel); addView(summary) }
        toolbar.addView(location, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(Ui.iconButton(activity, "more", "文件管理菜单") { showOverflow(it) })
        root.addView(toolbar)
        root.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        panes.addView(remote.root, LinearLayout.LayoutParams(0, -1, 1f))
        panes.addView(divider(), LinearLayout.LayoutParams(dp(1), -1))
        panes.addView(phone.root, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        root.addView(bottom)
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            actionDialog?.dismiss()
            remote.epoch++
            phone.epoch++
            dismissed()
        }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setLayout(-1, -1)
        }
        updateControls()
        loadRemote()
        loadPhone()
    }

    fun dismiss() = dialog.dismiss()
    fun refreshRemote() { if (dialog.isShowing) loadRemote() }
    fun refreshPhone() { if (dialog.isShowing) loadPhone() }

    fun setFolder(uri: Uri, flags: Int) {
        downloads.setBrowserFolder(uri, flags)
        activate(phone)
        loadPhone()
    }

    private fun rootFolder(): PhoneFolder? = downloads.browserFolder ?: downloads.location

    private fun currentPhoneFolder(): PhoneFolder? = rootFolder()?.let { root ->
        phone.path.lastOrNull()?.let { PhoneFolder(root.tree, it.key) } ?: root
    }

    private fun remoteFile(entry: Entry) = source.copy(path = (remote.path.map { it.key } + entry.key).joinToString("/"))

    private fun pathOf(pane: Pane): String {
        val base = if (pane === remote) source.workspace.ifBlank { "工作区" } else rootFolder()?.label ?: downloads.locationLabel
        return if (pane.path.isEmpty()) base else base.trimEnd('\\', '/') + "/" + pane.path.joinToString("/") { it.name }
    }

    private fun loadRemote() {
        val id = ++remote.epoch
        val connection = client()?.takeIf { it.usingDirect }
        if (connection == null) {
            remote.fail("等待 P2P 直连", "P2P 直连后可浏览电脑文件")
            return
        }
        remote.loading("加载中…")
        val path = remote.path.joinToString("/") { it.key }
        connection.requestDirectFile("list", source.copy(path = path).params()) { result, error ->
            if (id != remote.epoch || !dialog.isShowing) return@requestDirectFile
            if (error != null || result == null) {
                remote.fail("读取失败", error?.message ?: "无法读取电脑目录")
                return@requestDirectFile
            }
            val rows = result.arrayAt("entries")
            val entries = (0 until rows.length()).mapNotNull { i ->
                val row = rows.optJSONObject(i) ?: return@mapNotNull null
                val name = row.string("name")
                Entry(name, name, row.optBoolean("directory"), row.optLong("size", -1))
            }
            remote.bind(entries, if (result.optBoolean("truncated")) "仅显示前 2,000 项" else "")
        }
    }

    private fun loadPhone() {
        val id = ++phone.epoch
        val rootTree = rootFolder()?.tree
        if (tree != rootTree) {
            tree = rootTree
            phone.resetPath()
            if (active === phone) clearSelection()
        }
        val folder = currentPhoneFolder()
        phone.choose.visibility = if (folder == null) View.VISIBLE else View.GONE
        if (folder == null) {
            phone.noFolder()
            return
        }
        phone.loading("加载中…")
        thread(name = "codexrm-phone-files") {
            val result = runCatching {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree, folder.id)
                val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_SIZE, Document.COLUMN_FLAGS)
                activity.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                    val entries = ArrayList<Entry>()
                    while (entries.size < 2_001 && cursor.moveToNext()) {
                        entries += Entry(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2) == Document.MIME_TYPE_DIR,
                            if (cursor.isNull(3)) -1 else cursor.getLong(3), cursor.getInt(4))
                    }
                    entries
                } ?: throw IOException("无法读取手机目录")
            }
            handler.post {
                if (id != phone.epoch || !dialog.isShowing) return@post
                val entries = result.getOrNull()
                if (entries == null) {
                    val error = result.exceptionOrNull()
                    if (error is SecurityException) {
                        phone.choose.visibility = View.VISIBLE
                        phone.fail("没有目录访问权限", "请重新选择手机目录，或重试当前目录", permission = true)
                    } else {
                        phone.choose.visibility = View.GONE
                        phone.fail("读取失败", error?.message ?: "无法读取此目录")
                    }
                } else {
                    phone.choose.visibility = View.GONE
                    phone.bind(entries.take(2_000).sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() }),
                        if (entries.size > 2_000) "仅显示前 2,000 项" else "")
                }
            }
        }
    }

    private fun activate(pane: Pane) {
        if (pane !== active) {
            val previous = active
            clearSelection()
            active = pane
            previous.redraw()
        }
        updateControls()
    }

    private fun clearSelection() {
        selectionMode = false
        selected.clear()
        active.redraw()
    }

    private fun updateControls() {
        remote.markActive(active === remote)
        phone.markActive(active === phone)
        pathLabel.text = pathOf(active)
        pathLabel.contentDescription = "当前路径：${pathLabel.text}"
        val counts = if (active.ready) "文件夹 ${active.directories}   文件 ${active.entries.size - active.directories}" else ""
        summary.text = listOf(active.title, counts, if (selectionMode) "已选 ${selected.size}" else "")
            .filter { it.isNotEmpty() }.joinToString("  ·  ")
        summary.contentDescription = summary.text
        bottom.removeAllViews()
        if (selectionMode) {
            bottomButton("select-all", "全选", active.ready && active.entries.isNotEmpty()) {
                selected.addAll(active.entries.map { it.key }); selectionChanged()
            }
            bottomButton("invert-selection", "反选", active.ready && active.entries.isNotEmpty()) {
                val inverse = active.entries.filterNot { it.key in selected }
                selected.clear(); selected.addAll(inverse.map { it.key }); selectionChanged()
            }
            bottomButton("close", "退出多选") { clearSelection(); updateControls() }
            bottomButton("swap", if (active === remote) "手机" else "电脑") { activate(if (active === remote) phone else remote) }
            bottomButton("actions", "操作", active.ready && selected.isNotEmpty(), "所选文件的操作") {
                showActions(active, active.entries.filter { it.key in selected })
            }
        } else {
            bottomButton("left", "后退", active.canBack) { active.back() }
            bottomButton("right", "前进", active.canForward) { active.forward() }
            bottomButton("select-all", "多选", active.ready && active.entries.isNotEmpty()) { selectionMode = true; selectionChanged() }
            bottomButton("swap", if (active === remote) "手机" else "电脑") { activate(if (active === remote) phone else remote) }
            bottomButton("arrow", "上一级", active.path.isNotEmpty()) { active.goTo(active.path.dropLast(1)) }
        }
    }

    private fun selectionChanged() {
        active.redraw()
        updateControls()
    }

    private fun bottomButton(
        icon: String,
        label: String,
        enabled: Boolean = true,
        description: String = label,
        action: () -> Unit,
    ) {
        val button = Ui.column(activity).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(58)
            setPadding(dp(2), dp(4), dp(2), dp(3))
            contentDescription = description
            isEnabled = enabled
            isFocusable = true
            alpha = if (enabled) 1f else 0.36f
            background = Ui.ripple(Color.TRANSPARENT, dp(8).toFloat())
            addView(IconView(activity, icon, if (enabled) Ui.MUTED else Ui.DIM), LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(Ui.text(activity, label, 12f, if (enabled) Ui.TEXT else Ui.DIM).apply {
                gravity = Gravity.CENTER
                maxLines = 2
            })
            if (enabled) setOnClickListener { action() }
        }
        bottom.addView(button, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun showOverflow(anchor: View) {
        PopupMenu(activity, anchor).apply {
            menu.add("刷新两侧目录").setOnMenuItemClickListener { loadRemote(); loadPhone(); true }
            menu.add("选择手机目录").setOnMenuItemClickListener { chooseFolder(); true }
            show()
        }
    }

    private data class Action(
        val icon: String,
        val label: String,
        val run: (() -> Unit)? = null,
        val disabledReason: String = "",
    )

    private fun activateEntry(pane: Pane, entry: Entry) {
        if (entry.dir) {
            pane.goTo(pane.path + entry)
        } else if (pane === remote) {
            openRemote(remoteFile(entry), entry.name, currentPhoneFolder())
        } else {
            currentPhoneFolder()?.let { openLocal(documentUri(it, entry), entry.name) }
        }
    }

    private fun showActions(pane: Pane, entries: List<Entry>) {
        if (entries.isEmpty()) return
        if (!pane.ready) {
            Toast.makeText(activity, "目录尚未加载完成，请先重试", Toast.LENGTH_SHORT).show()
            return
        }
        val single = entries.singleOrNull()
        val folder = currentPhoneFolder()
        val fromComputer = pane === remote
        val direction = if (fromComputer) "复制 →" else "← 复制"
        val move = if (fromComputer) "移动 →" else "← 移动"
        val canCopy = fromComputer && entries.none { it.dir } && client()?.usingDirect == true && (folder == null || phone.ready)
        val copyReason = when {
            !fromComputer -> "仅支持从电脑复制到手机"
            entries.any { it.dir } -> "暂不支持复制文件夹"
            client()?.usingDirect != true -> "需要 P2P 直连"
            folder != null && !phone.ready -> "手机目录尚未加载完成"
            else -> ""
        }
        val copy = if (canCopy) {
            val files = entries.map { remoteFile(it) to it.name }
            Action("copy", direction, run = { if (copyToPhone(files, folder)) { clearSelection(); updateControls() } })
        } else Action("copy", direction, disabledReason = copyReason)
        val writable = !fromComputer && folder != null && !localWrite
        val canDelete = writable && entries.all { it.flags and Document.FLAG_SUPPORTS_DELETE != 0 }
        val deleteReason = when {
            fromComputer -> "电脑文件暂不支持删除"
            folder == null -> "请先选择手机目录"
            localWrite -> "另一项手机文件操作正在进行"
            !canDelete -> "此目录或项目不支持删除"
            else -> ""
        }
        val rename = single?.takeIf { writable && it.flags and Document.FLAG_SUPPORTS_RENAME != 0 }
        val renameReason = when {
            fromComputer -> "电脑文件暂不支持重命名"
            folder == null -> "请先选择手机目录"
            localWrite -> "另一项手机文件操作正在进行"
            single == null -> "一次只能重命名一项"
            rename == null -> "此目录或项目不支持重命名"
            else -> ""
        }
        val shareReason = when {
            fromComputer -> "请先复制到手机后分享"
            folder == null -> "请先选择手机目录"
            entries.any { it.dir } -> "暂不支持分享文件夹"
            else -> ""
        }
        val actions = listOf(
            copy,
            Action("scissors", move, disabledReason = "当前版本不支持移动；请使用复制"),
            Action("trash", "删除", if (canDelete) ({ confirmDelete(folder, entries) }) else null, deleteReason),
            Action("edit", "重命名", if (rename != null && folder != null) ({ rename(folder, rename) }) else null, renameReason),
            Action("info", "属性", run = { showProperties(pane, entries) }),
            Action("share", "分享", if (!fromComputer && folder != null && entries.none { it.dir }) ({ share(folder, entries) }) else null, shareReason),
            Action("open", "打开", single?.let { { activateEntry(pane, it) } }, if (single == null) "一次只能打开一项" else ""),
            Action("select-all", "多选", if (!selectionMode && single != null) ({
                selectionMode = true
                selected.add(single.key)
                selectionChanged()
            }) else null, if (selectionMode) "已在多选模式" else "一次只能选择一项"),
        )
        val menu = Dialog(activity)
        actionDialog = menu
        val panel = Ui.column(activity).apply { background = Ui.rounded(Ui.SURFACE_ALT, dp(16).toFloat(), Ui.BORDER) }
        val header = Ui.column(activity, false).apply { gravity = Gravity.CENTER_VERTICAL }
        val labels = Ui.column(activity).apply {
            setPadding(dp(16), dp(9), 0, dp(9))
            addView(Ui.text(activity, single?.name ?: "已选 ${entries.size} 项", 16f, bold = true).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            val subtitle = if (fromComputer) "共 ${entries.size} 项 · 电脑 → 手机（${folder?.label ?: downloads.locationLabel}）"
            else "共 ${entries.size} 项 · 来源：手机（${folder?.label ?: downloads.locationLabel}）"
            addView(Ui.text(activity, subtitle, 12f, Ui.MUTED).apply { maxLines = 2 })
        }
        header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.iconButton(activity, "close", "关闭操作菜单") { menu.dismiss() })
        panel.addView(header)
        panel.addView(divider(), LinearLayout.LayoutParams(-1, dp(1)))
        val grid = Ui.column(activity).apply { setPadding(dp(8), dp(6), dp(8), dp(6)) }
        actions.chunked(2).forEach { pair ->
            val row = Ui.column(activity, false)
            pair.forEach { action -> row.addView(actionCell(menu, action), LinearLayout.LayoutParams(0, -2, 1f)) }
            if (pair.size == 1) row.addView(View(activity), LinearLayout.LayoutParams(0, -2, 1f))
            grid.addView(row)
        }
        panel.addView(grid)
        menu.setContentView(ScrollView(activity).apply { addView(panel) })
        menu.setOnDismissListener { if (actionDialog === menu) actionDialog = null }
        menu.show()
        val availableWidth = dialog.window?.decorView?.width?.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        menu.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.32f)
            setLayout(minOf(availableWidth - dp(40), dp(420)), -2)
            setGravity(Gravity.CENTER)
        }
    }

    private fun actionCell(menu: Dialog, action: Action): View = Ui.column(activity, false).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(10), dp(6), dp(6), dp(6))
        val enabled = action.run != null
        val color = if (!enabled) Ui.DIM else if (action.icon == "copy") Ui.GREEN else Ui.TEXT
        addView(IconView(activity, action.icon, color), LinearLayout.LayoutParams(dp(22), dp(22)))
        val labels = Ui.column(activity).apply {
            addView(Ui.text(activity, action.label, 15f, color))
            if (!enabled && action.disabledReason.isNotEmpty()) {
                addView(Ui.text(activity, action.disabledReason, 12f, Ui.MUTED).apply { maxLines = 3 })
            }
        }
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        contentDescription = if (enabled) action.label else "${action.label}，不可用：${action.disabledReason}"
        isEnabled = enabled
        isFocusable = true
        if (enabled) {
            background = Ui.ripple(Color.TRANSPARENT, dp(8).toFloat())
            setOnClickListener { menu.dismiss(); action.run.invoke() }
        }
    }

    private fun documentUri(folder: PhoneFolder, entry: Entry) = DocumentsContract.buildDocumentUriUsingTree(folder.tree, entry.key)

    private fun showProperties(pane: Pane, entries: List<Entry>) {
        val single = entries.singleOrNull()
        val directories = entries.count { it.dir }
        val files = entries.filterNot { it.dir }
        val size = if (files.isNotEmpty() && files.all { it.size >= 0 }) "\n文件大小 ${AttachmentStore.sizeLabel(files.sumOf { it.size })}" else ""
        AlertDialog.Builder(activity).setTitle(single?.name ?: "已选 ${entries.size} 项")
            .setMessage("${pane.title} · ${pathOf(pane)}\n文件夹 $directories  ·  文件 ${files.size}$size")
            .setPositiveButton("关闭", null).show()
    }

    private fun confirmDelete(folder: PhoneFolder, entries: List<Entry>) {
        val target = entries.singleOrNull()?.name ?: "所选的 ${entries.size} 项"
        val description = if (entries.any { it.dir }) "文件夹内的内容也会删除。" else "此操作无法撤销。"
        AlertDialog.Builder(activity).setTitle("删除 $target？").setMessage(description)
            .setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                modifyPhone(entries.size) {
                    entries.count { entry -> runCatching { DocumentsContract.deleteDocument(activity.contentResolver, documentUri(folder, entry)) }.getOrDefault(false) }
                }
            }.show()
    }

    private fun rename(folder: PhoneFolder, entry: Entry) {
        val input = Ui.input(activity, "名称").apply { setText(entry.name); setSelectAllOnFocus(true) }
        val container = Ui.column(activity).apply { setPadding(dp(24), 0, dp(24), 0); addView(input) }
        val prompt = AlertDialog.Builder(activity).setTitle("重命名").setView(container)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        prompt.show()
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString()
            if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name) {
                input.error = "请输入有效的名称"
            } else {
                prompt.dismiss()
                if (name != entry.name) modifyPhone(1) {
                    if (DocumentsContract.renameDocument(activity.contentResolver, documentUri(folder, entry), name) != null) 1 else 0
                }
            }
        }
    }

    private fun modifyPhone(total: Int, change: () -> Int) {
        if (localWrite) return
        localWrite = true
        thread(name = "codexrm-phone-file-operation") {
            val done = runCatching(change).getOrDefault(0)
            handler.post {
                localWrite = false
                if (!dialog.isShowing) return@post
                if (active === phone && done == total) clearSelection()
                loadPhone()
                if (done != total) Toast.makeText(activity, "已完成 $done/$total 项，部分操作未成功", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun share(folder: PhoneFolder, entries: List<Entry>) {
        val uris = ArrayList(entries.map { documentUri(folder, it) })
        val types = entries.map { WorkspaceDownloads.mime(it.name) }.distinct()
        val shared = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = types.singleOrNull() ?: "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            clipData = ClipData.newUri(activity.contentResolver, entries.first().name, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { activity.startActivity(Intent.createChooser(shared, "分享文件")) }
            .onFailure { Toast.makeText(activity, "无法分享所选文件", Toast.LENGTH_SHORT).show() }
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
    private fun divider() = View(activity).apply { setBackgroundColor(Ui.BORDER) }

    private inner class Pane(val title: String, icon: String) {
        var epoch = 0
        private val history = mutableListOf<List<Entry>>(emptyList())
        private var cursor = 0
        val path: List<Entry> get() = history[cursor]
        val canBack: Boolean get() = cursor > 0
        val canForward: Boolean get() = cursor < history.lastIndex
        var entries: List<Entry> = emptyList()
            private set
        var directories = 0
            private set
        var ready = false
            private set
        val root = Ui.column(activity).apply { setBackgroundColor(Ui.BACKGROUND) }
        private val heading = Ui.column(activity, false).apply {
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(8), 0)
            setOnClickListener { activate(this@Pane) }
            isFocusable = true
        }
        private val marker = View(activity)
        private val status = Ui.column(activity).apply {
            setPadding(dp(10), dp(10), dp(8), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        private val progress = ProgressBar(activity).apply {
            isIndeterminate = true
            contentDescription = "正在加载${title}目录"
        }
        private val statusTitle = Ui.text(activity, size = 14f, bold = true)
        private val statusDetail = Ui.text(activity, size = 12f, color = Ui.MUTED)
        private val retry = Ui.button(activity, "重试") {
            if (this@Pane === remote) loadRemote() else loadPhone()
        }
        val choose = Ui.button(activity, "选择手机目录") { activate(this); chooseFolder() }.apply { visibility = View.GONE }
        private val list = ListView(activity).apply { divider = ColorDrawable(Ui.BORDER); dividerHeight = dp(1) }
        private val hasParent get() = path.isNotEmpty()
        private val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size + if (hasParent) 1 else 0
            override fun getItem(position: Int) = if (hasParent) entries.getOrNull(position - 1) else entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = (convertView as? FileRow) ?: FileRow()
                row.bind(getItem(position), this@Pane === active)
                return row
            }
        }

        init {
            heading.addView(IconView(activity, icon, Ui.MUTED), LinearLayout.LayoutParams(dp(18), dp(18)))
            heading.addView(Ui.text(activity, title, 13f, bold = true), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            root.addView(heading)
            root.addView(marker, LinearLayout.LayoutParams(-1, dp(2)))
            val statusHeader = Ui.column(activity, false).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(progress, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(statusTitle, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            }
            status.addView(statusHeader)
            status.addView(statusDetail)
            status.addView(retry, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            root.addView(status)
            root.addView(choose, LinearLayout.LayoutParams(-1, -2).apply { marginStart = dp(8); marginEnd = dp(8) })
            root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
            list.adapter = adapter
            list.setOnItemClickListener { _, _, pos, _ ->
                activate(this)
                val entry = adapter.getItem(pos)
                if (entry == null) goTo(path.dropLast(1))
                else if (selectionMode) {
                    if (!selected.add(entry.key)) selected.remove(entry.key)
                    selectionChanged()
                } else activateEntry(this, entry)
            }
            list.setOnItemLongClickListener { _, _, pos, _ ->
                val entry = adapter.getItem(pos) ?: return@setOnItemLongClickListener false
                activate(this)
                if (selectionMode) {
                    selected.add(entry.key)
                    selectionChanged()
                    showActions(this, entries.filter { it.key in selected })
                } else showActions(this, listOf(entry))
                true
            }
        }

        fun markActive(focused: Boolean) {
            heading.isSelected = focused
            heading.contentDescription = if (focused) "$title，当前窗格" else "切换到$title"
            heading.setBackgroundColor(if (focused) Ui.SURFACE_ALT else Ui.SURFACE)
            marker.setBackgroundColor(if (focused) Ui.GREEN else Ui.BORDER)
        }

        fun back() { if (canBack) { cursor--; navigate() } }
        fun forward() { if (canForward) { cursor++; navigate() } }
        fun goTo(destination: List<Entry>) {
            history.subList(cursor + 1, history.size).clear()
            history.add(destination)
            cursor++
            navigate()
        }

        fun resetPath() { history.clear(); history.add(emptyList()); cursor = 0 }

        private fun navigate() {
            clearSelection()
            if (this === remote) loadRemote() else loadPhone()
            list.setSelection(0)
        }

        fun loading(message: String) {
            ready = false
            showStatus(message, "正在读取当前目录，请稍候", loading = true)
            redraw()
            updateControls()
        }

        fun noFolder() {
            ready = false
            showStatus("尚未授权手机目录", "选择目录后可浏览和保存文件", permission = true, retryable = false)
            redraw()
            updateControls()
        }

        fun fail(title: String, message: String, permission: Boolean = false) {
            ready = false
            showStatus(title, message, permission = permission, retryable = true)
            redraw()
            updateControls()
        }

        fun bind(items: List<Entry>, message: String, ready: Boolean = true, retainSelection: Boolean = false) {
            this.ready = ready
            entries = items
            directories = items.count { it.dir }
            if (this === active && !retainSelection) selected.retainAll(items.map { it.key }.toSet())
            when {
                !ready -> showStatus("读取失败", message, retryable = true)
                items.isEmpty() -> showStatus("空文件夹", "此目录没有可显示的项目")
                message.isNotEmpty() -> showStatus(message, "当前目录项目过多；未列出的项目不会加入选择集合")
                else -> status.visibility = View.GONE
            }
            redraw()
            updateControls()
        }

        private fun showStatus(
            title: String,
            detail: String,
            loading: Boolean = false,
            permission: Boolean = false,
            retryable: Boolean = false,
        ) {
            status.visibility = View.VISIBLE
            statusTitle.text = title
            statusDetail.text = detail
            statusDetail.contentDescription = detail
            status.contentDescription = "$title。$detail"
            status.accessibilityLiveRegion = if (permission || retryable) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            else View.ACCESSIBILITY_LIVE_REGION_POLITE
            progress.visibility = if (loading) View.VISIBLE else View.GONE
            retry.visibility = if (retryable) View.VISIBLE else View.GONE
        }

        fun redraw() {
            list.isEnabled = ready
            adapter.notifyDataSetChanged()
        }
    }

    private inner class FileRow : LinearLayout(activity) {
        private val icon = IconView(activity, "file", Ui.MUTED)
        private val name = Ui.text(activity, size = 14f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val detail = Ui.text(activity, size = 12f, color = Ui.MUTED)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(dp(7), 0, dp(6), 0)
            val badge = Ui.frame(activity).apply {
                background = Ui.rounded(Ui.SURFACE_ALT, dp(8).toFloat())
                addView(icon, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
            }
            addView(badge, LinearLayout.LayoutParams(dp(32), dp(32)))
            val labels = Ui.column(activity).apply { addView(name); addView(detail) }
            addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(7) })
        }

        fun bind(entry: Entry?, focused: Boolean) {
            val checked = focused && entry != null && entry.key in selected
            background = if (checked) Ui.rounded(Ui.GREEN and 0x00ffffff or 0x37000000, 0f) else null
            isActivated = checked
            icon.setIcon(if (checked) "check" else if (entry == null || entry.dir) "folder" else "file")
            icon.setColor(if (checked) Ui.GREEN else Ui.MUTED)
            name.text = entry?.name ?: ".."
            detail.visibility = if (entry != null && !entry.dir && entry.size >= 0) View.VISIBLE else View.GONE
            if (detail.visibility == View.VISIBLE && entry != null) detail.text = AttachmentStore.sizeLabel(entry.size)
            contentDescription = when {
                entry == null -> "上一级目录"
                else -> listOf(
                    entry.name,
                    if (entry.dir) "文件夹" else "文件",
                    if (detail.visibility == View.VISIBLE) detail.text else "",
                    if (checked) "已选择" else "",
                ).filter { it.isNotEmpty() }.joinToString("，")
            }
        }
    }
}
