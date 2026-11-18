package com.codexrm.mobile

import android.app.Activity
import android.app.Dialog
import android.content.Context
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
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import org.json.JSONObject
import java.io.IOException
import kotlin.concurrent.thread

internal data class PhoneFolder(val tree: Uri, val id: String) {
    val uri: Uri get() = DocumentsContract.buildDocumentUriUsingTree(tree, id)
}

internal class WorkspaceFilesDialog(
    private val activity: Activity,
    private val source: RemoteFile,
    private val client: () -> CodexClient?,
    private val chooseFolder: () -> Unit,
    private val download: (RemoteFile, String, PhoneFolder) -> Unit,
    private val dismissed: () -> Unit,
) {
    private data class Entry(val key: String, val name: String, val dir: Boolean, val size: Long)
    private val prefs = activity.getSharedPreferences("file-manager", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private var tree = prefs.getString("tree", null)?.let(Uri::parse)
    private val remotePath = mutableListOf<String>()
    private val localPath = mutableListOf<Entry>()
    private var remoteEpoch = 0
    private var localEpoch = 0
    private val dialog = Dialog(activity)
    private val remote = Pane("电脑 · 工作区", { up(remotePath, ::loadRemote) })
    private val phone = Pane("手机", { up(localPath, ::loadPhone) })

    fun show() {
        val root = Ui.column(activity).apply { setBackgroundColor(Ui.BACKGROUND) }
        val toolbar = Ui.column(activity, false).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.SURFACE) }
        toolbar.addView(Ui.iconButton(activity, "close", "关闭文件管理") { dialog.dismiss() })
        toolbar.addView(Ui.text(activity, "文件管理", 17f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, Ui.dp(activity, 52), 1f))
        toolbar.addView(Ui.iconButton(activity, "refresh", "刷新两侧目录") { loadRemote(); loadPhone() })
        root.addView(toolbar)
        val panes = Ui.column(activity, false)
        panes.addView(remote.root, LinearLayout.LayoutParams(0, -1, 1f))
        panes.addView(View(activity).apply { setBackgroundColor(Ui.BORDER) }, LinearLayout.LayoutParams(Ui.dp(activity, 1), -1))
        panes.addView(phone.root, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(panes, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnDismissListener { remoteEpoch++; localEpoch++; dismissed() }
        dialog.show()
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(-1, -1) }
        loadRemote(); loadPhone()
    }

    fun dismiss() = dialog.dismiss()
    fun refreshRemote() { if (dialog.isShowing) loadRemote() }
    fun refreshPhone() { if (dialog.isShowing) loadPhone() }

    fun setFolder(uri: Uri, flags: Int) {
        if (!DocumentsContract.isTreeUri(uri)) return
        val granted = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val previous = tree
        activity.contentResolver.takePersistableUriPermission(uri, granted)
        if (previous != null && previous != uri) {
            val old = activity.contentResolver.persistedUriPermissions.firstOrNull { it.uri == previous }
            old?.let {
                val oldFlags = (if (it.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                    (if (it.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
                runCatching { activity.contentResolver.releasePersistableUriPermission(previous, oldFlags) }
            }
        }
        tree = uri
        prefs.edit().putString("tree", uri.toString()).apply()
        localPath.clear()
        loadPhone()
    }

    private fun selectedFolder(): PhoneFolder? = tree?.let { PhoneFolder(it, localPath.lastOrNull()?.key ?: DocumentsContract.getTreeDocumentId(it)) }

    private fun loadRemote() {
        val id = ++remoteEpoch
        val active = client()?.takeIf { it.usingDirect }
        remote.up.isEnabled = remotePath.isNotEmpty()
        remote.path.text = remotePath.joinToString("/").ifEmpty { source.workspace }
        remote.bind(emptyList(), if (active == null) "等待 P2P 直连" else "加载中…")
        if (active == null) return
        val path = remotePath.joinToString("/")
        active.requestDirectFile("list", source.copy(path = path).params()) { result, error ->
            if (id != remoteEpoch || !dialog.isShowing) return@requestDirectFile
            if (error != null || result == null) { remote.bind(emptyList(), error?.message ?: "读取失败"); return@requestDirectFile }
            val entries = result.arrayAt("entries")
            remote.bind((0 until entries.length()).mapNotNull { i ->
                val row = entries.optJSONObject(i) ?: return@mapNotNull null
                val name = row.string("name")
                Entry(name, name, row.optBoolean("directory"), row.optLong("size", -1))
            }, if (result.optBoolean("truncated")) "仅显示前 2,000 项" else "") { entry ->
                if (entry.dir) { remotePath += entry.key; loadRemote() }
                else {
                    val target = selectedFolder()
                    if (target == null) chooseFolder()
                    else download(source.copy(path = (remotePath + entry.key).joinToString("/")), entry.name, target)
                }
            }
        }
    }

    private fun loadPhone() {
        val id = ++localEpoch
        val folder = selectedFolder()
        phone.up.isEnabled = localPath.isNotEmpty()
        phone.path.text = localPath.joinToString("/") { it.name }.ifEmpty { folder?.id?.substringAfter(':')?.ifEmpty { "已授权目录" } ?: "未选择目录" }
        phone.extra.visibility = View.VISIBLE
        phone.bind(emptyList(), if (folder == null) "未选择目录" else "加载中…")
        if (folder == null) return
        thread(name = "codexrm-phone-files") {
            val result = runCatching {
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(folder.tree, folder.id)
                val projection = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE)
                activity.contentResolver.query(children, projection, null, null, null)?.use { cursor ->
                    val entries = ArrayList<Entry>()
                    while (entries.size < 2_001 && cursor.moveToNext()) {
                        entries += Entry(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2) == Document.MIME_TYPE_DIR, if (cursor.isNull(3)) -1 else cursor.getLong(3))
                    }
                    entries
                } ?: throw IOException("无法读取手机目录")
            }
            handler.post {
                if (id != localEpoch || !dialog.isShowing) return@post
                val entries = result.getOrNull()
                if (entries == null) phone.bind(emptyList(), "无法读取目录，请重新选择")
                else phone.bind(entries.take(2_000).sortedWith(compareByDescending<Entry> { it.dir }.thenBy { it.name.lowercase() }), if (entries.size > 2_000) "仅显示前 2,000 项" else "") { entry ->
                    if (entry.dir) { localPath += entry; loadPhone() }
                    else runCatching {
                        val document = DocumentsContract.buildDocumentUriUsingTree(folder.tree, entry.key)
                        activity.startActivity(Intent(Intent.ACTION_VIEW).setData(document).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    }.onFailure { android.widget.Toast.makeText(activity, "没有可打开此文件的应用", android.widget.Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun <T> up(path: MutableList<T>, refresh: () -> Unit) {
        if (path.isNotEmpty()) { path.removeAt(path.lastIndex); refresh() }
    }

    private inner class Pane(title: String, goUp: () -> Unit) {
        val root = Ui.column(activity)
        val path = Ui.text(activity, size = 11f, color = Ui.MUTED).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START }
        val up = Ui.iconButton(activity, "arrow", "$title：上一级", 36) { goUp() }
        val extra = Ui.iconButton(activity, "folder", "选择手机目录", 36) { chooseFolder() }.apply { visibility = View.GONE }
        private val list = ListView(activity).apply { divider = ColorDrawable(Ui.BORDER); dividerHeight = Ui.dp(activity, 1) }
        private val status = Ui.text(activity, size = 11f, color = Ui.MUTED).apply { setPadding(Ui.dp(activity, 8), Ui.dp(activity, 6), Ui.dp(activity, 8), Ui.dp(activity, 6)) }

        init {
            val bar = Ui.column(activity, false).apply { gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Ui.SURFACE_ALT) }
            val labels = Ui.column(activity).apply { setPadding(Ui.dp(activity, 8), Ui.dp(activity, 6), 0, Ui.dp(activity, 6)) }
            labels.addView(Ui.text(activity, title, 13f, Ui.TEXT, bold = true).apply { maxLines = 1 }); labels.addView(path)
            bar.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(up); bar.addView(extra)
            root.addView(bar); root.addView(status); root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        fun bind(entries: List<Entry>, message: String, clicked: (Entry) -> Unit = {}) {
            status.text = message.ifEmpty { if (entries.isEmpty()) "空文件夹" else "" }
            status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
            list.adapter = object : BaseAdapter() {
                override fun getCount() = entries.size
                override fun getItem(position: Int) = entries[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val entry = getItem(position)
                    return Ui.column(activity, false).apply {
                        gravity = Gravity.CENTER_VERTICAL; minimumHeight = Ui.dp(activity, 49)
                        setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 5), 0)
                        addView(IconView(activity, if (entry.dir) "folder" else "file", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(activity, 19), Ui.dp(activity, 19)))
                        val labels = Ui.column(activity).apply { setPadding(Ui.dp(activity, 6), Ui.dp(activity, 4), 0, Ui.dp(activity, 4)) }
                        labels.addView(Ui.text(activity, entry.name, 13f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                        if (!entry.dir && entry.size >= 0) labels.addView(Ui.text(activity, AttachmentStore.sizeLabel(entry.size), 10f, Ui.MUTED))
                        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                        if (!entry.dir && this@Pane === remote) addView(IconView(activity, "update", Ui.MUTED), LinearLayout.LayoutParams(Ui.dp(activity, 15), Ui.dp(activity, 15)))
                    }
                }
            }
            list.setOnItemClickListener { _, _, pos, _ -> clicked(entries[pos]) }
        }
    }
}
