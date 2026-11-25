package com.codexrm.mobile

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

internal class PhoneTransferUi(
    private val activity: ComponentActivity,
    private val selected: () -> Device?,
    private val connection: () -> CodexClient?,
    private val capabilities: () -> JSONObject,
    private val toast: (String) -> Unit,
) {
    private class Active(val owner: Device, val item: TransferEntry) {
        var cancel: (() -> Unit)? = null
        var cancelled = false
        var progress = ""
    }
    private data class Row(
        val item: TransferEntry, val working: Boolean, val cancellable: Boolean, val saved: Boolean, val fileActionBusy: Boolean,
    )

    private val store = TransferStorage(activity)
    private val downloads = WorkspaceDownloads(activity)
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var rows: TransferAdapter? = null
    private var status: TextView? = null
    private var pickOwner: Device? = null
    private var pendingStoragePermission: Pair<Device, TransferEntry>? = null
    private val fileActions = mutableSetOf<String>()
    private var knownOwner: Device? = null
    private var remote = emptyList<TransferEntry>()
    private var requesting: CodexClient? = null
    private var active: Active? = null
    private val changing = mutableSetOf<String>()
    private val staging = mutableSetOf<String>()
    private val drafts = mutableMapOf<String, String>()
    private val retryAfter = android.util.LruCache<String, Long>(128)
    private var error = ""
    private var sendNext = true
    private var download: DirectFileDownload? = null
    private val poll = Runnable { sync() }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { files ->
        val owner = pickOwner.also { pickOwner = null }
        if (owner != null && files.isNotEmpty()) enqueue(owner, SharedContent("", files))
    }
    private val storagePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val (owner, item) = pendingStoragePermission.also { pendingStoragePermission = null } ?: return@registerForActivityResult
        if (selected() !== owner) return@registerForActivityResult
        if (granted) saveToDownloads(owner, item) else toast("需要存储权限才能保存到下载目录")
    }

    fun resume() { resumed = true; sync() }
    fun pause() { resumed = false; handler.removeCallbacks(poll) }
    fun destroy() { pause(); download?.cancel(); detachPage() }
    fun connectionChanged() { if (resumed) sync() else render() }
    fun searchableEntries(owner: Device): List<TransferEntry> =
        (store.entries(owner) + if (owner === knownOwner) remote else emptyList()).distinctBy { it.id }

    fun createPage(): View {
        val owner = checkNotNull(selected())
        val page = Ui.column(activity)
        val pane = FrameLayout(activity)
        val gutter = Ui.dp(activity, Ui.CHAT_GUTTER)
        val list = ChatListView(activity).apply {
            divider = null; dividerHeight = 0; cacheColorHint = Color.TRANSPARENT
            selector = Ui.rounded(Color.TRANSPARENT, 0f)
            clipToPadding = false; setStackFromBottom(true)
            transcriptMode = ListView.TRANSCRIPT_MODE_NORMAL
            setPadding(gutter, Ui.dp(activity, 8), gutter, Ui.dp(activity, 8))
        }
        val empty = Ui.column(activity).apply {
            gravity = Gravity.CENTER
            setPadding(gutter, 0, gutter, 0)
            addView(Ui.text(activity, "暂无传输记录", 24f, bold = true))
            addView(Ui.text(activity, "在手机和电脑之间发送文件或文字", 14f, Ui.MUTED).apply { gravity = Gravity.CENTER })
        }
        pane.addView(list, FrameLayout.LayoutParams(-1, -1))
        pane.addView(empty, FrameLayout.LayoutParams(-1, -1))
        rows = TransferAdapter(owner).also { list.adapter = it }
        list.emptyView = empty
        page.addView(pane, LinearLayout.LayoutParams(-1, 0, 1f))
        val zone = Ui.column(activity).apply { setPadding(gutter, 0, gutter, Ui.dp(context, 4)) }
        status = Ui.text(activity, "", 12f, Ui.MUTED).also { zone.addView(it) }
        val card = Ui.column(activity, false).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
            background = Ui.rounded(ReferenceUi.card, Ui.dp(context, 26).toFloat(), ReferenceUi.border)
        }
        card.addView(Ui.iconButton(activity, "plus", "选择文件发送") {
            if (pickOwner != null) return@iconButton
            pickOwner = owner
            runCatching { picker.launch("*/*") }.onFailure { pickOwner = null; toast("无法打开文件选择器") }
        })
        val input = Ui.input(activity, "给电脑发消息…", multiLine = true).apply {
            background = null; textSize = 16f
            minHeight = Ui.dp(context, 46); maxHeight = Ui.dp(context, 120)
            setPadding(Ui.dp(context, 8), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6))
            filters = arrayOf(android.text.InputFilter.LengthFilter(200000))
            setText(drafts[owner.agentId].orEmpty())
        }
        card.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        val send = ReferenceUi.circle(activity, "arrow", "发送到电脑", 42) {
            val text = input.text.toString()
            if (text.isNotBlank()) {
                enqueue(owner, SharedContent(text, emptyList()))
                input.text.clear()
            }
        }
        send.isEnabled = input.text.isNotBlank()
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s.toString()
                if (value.isEmpty()) drafts.remove(owner.agentId) else drafts[owner.agentId] = value
                send.isEnabled = value.isNotBlank()
                send.alpha = if (send.isEnabled) 1f else 0.45f
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        send.alpha = if (send.isEnabled) 1f else 0.45f
        card.addView(send)
        zone.addView(card)
        page.addView(zone)
        sync()
        return page
    }

    fun detachPage() { rows = null; status = null }

    fun enqueue(owner: Device, content: SharedContent) {
        val batch = UUID.randomUUID().toString()
        staging.add(batch)
        TransferStorage.worker.execute {
            var queued = 0
            var issue: String? = null
            val sendText = content.text.isNotBlank()
            val count = minOf(content.files.size, if (sendText) 63 else 64)
            for (index in 0 until count + if (sendText) 1 else 0) {
                try { store.stage(owner, if (index < count) content.files[index] else null, content.text, batch); queued++ }
                catch (e: Exception) { issue = e.message ?: "无法缓存文件"; break }
            }
            if (content.files.size > count && issue == null) issue = "最多一次发送 64 项"
            handler.post {
                staging.remove(batch)
                if (activity.isDestroyed) return@post
                val saved = if (queued == 0) "" else "已将 $queued 项加入 ${owner.serverName} 的传输队列"
                toast(listOfNotNull(saved.takeIf { it.isNotEmpty() }, issue).joinToString("；"))
                connectionChanged()
            }
        }
    }

    private fun schedule(delay: Long = 8_000) {
        handler.removeCallbacks(poll)
        if (resumed) handler.postDelayed(poll, delay)
    }

    private fun sync() {
        val owner = selected()
        if (owner !== knownOwner) { knownOwner = owner; remote = emptyList(); error = "" }
        render()
        if (!resumed || owner == null) return
        schedule()
        val client = connection() ?: return
        if (!capabilities().optBoolean("phoneTransfer") || requesting != null || active != null) return
        requesting = client
        client.request("codexrm/phoneTransfer/list", timeoutMs = 6_000) { result, failure ->
            requesting = null
            if (!resumed || selected() !== owner || connection() !== client || activity.isDestroyed) return@request
            if (failure != null || result == null) return@request
            val files = result.arrayAt("files")
            remote = (0 until minOf(files.length(), 64)).mapNotNull { i ->
                files.optJSONObject(i)?.let { TransferEntry.parse(it, "remote") }
            }
            val local = store.entries(owner)
            render(local)
            val remoteIds = remote.mapTo(HashSet()) { it.id }
            local.filter { it.state == "cancelledIncoming" && it.id !in remoteIds && it.id !in changing }.forEach { item ->
                changeRecord(item.id, { store.acknowledgeCancellation(owner, item) })
            }
            if (!client.usingDirect) return@request
            val now = SystemClock.elapsedRealtime()
            fun eligible(id: String) = id !in changing && (retryAfter.get(id) ?: 0L) <= now
            val completedBatch = completedPhoneTransferBatch(local, staging, ::eligible)
            if (completedBatch != null) {
                if (capabilities().optBoolean("phoneTransferBatches")) {
                    requesting = client
                    client.requestPhoneTransfer("finishBatch", JSONObject().put("id", completedBatch)) { _, issue ->
                        requesting = null
                        if (issue == null) changeRecord(completedBatch, { store.finishBatch(owner, completedBatch) })
                        else retryAfter.put(completedBatch, SystemClock.elapsedRealtime() + 30_000)
                        schedule(200)
                    }
                } else changeRecord(completedBatch, { store.finishBatch(owner, completedBatch) })
                return@request
            }
            val byId = local.associateBy { it.id }
            val incoming = remote.firstOrNull { eligible(it.id) }
            val outgoing = local.lastOrNull { it.state == "pending" && it.batch !in staging &&
                capabilities().optBoolean("desktopShare") && eligible(it.id) }
            when {
                outgoing != null && (incoming == null || sendNext) -> { sendNext = false; send(owner, client, outgoing) }
                incoming != null -> { sendNext = true; receive(owner, client, incoming, byId[incoming.id]) }
            }
        }
    }

    private fun begin(owner: Device, item: TransferEntry): Active = Active(owner, item).also { active = it; error = "" }

    private fun send(owner: Device, client: CodexClient, item: TransferEntry) {
        val direct = client.directTransport() ?: return
        val source = store.payload(owner, item)
        val task = begin(owner, item)
        if (!source.isFile || source.length() != item.size) { finished(task, IOException("${item.name} 的本地缓存不存在")); return }
        val control = DesktopUploadControl {
            task.cancel = null
            progress(task, "正在保存到电脑…")
            render()
        }
        task.cancel = control::cancel
        val metadata = item.json().put("destination", "downloads").put("sender", TransferStorage.sender).put("transferId", item.id)
        if (item.batch.isNotEmpty() && capabilities().optBoolean("phoneTransferBatches")) metadata.put("batchId", item.batch)
        render()
        client.uploadDesktopFile(direct, metadata, source, { percent -> progress(task, "发送 $percent%") }, control) { result, issue ->
            if (result != null && issue == null) {
                task.cancel = null
                persist({ store.sent(owner, item) }) { finished(task, it) }
            } else finished(task, issue ?: IOException("发送失败"))
        }
    }

    private fun receive(owner: Device, client: CodexClient, item: TransferEntry, local: TransferEntry?) {
        val task = begin(owner, item)
        fun ack(onAck: (() -> Unit)? = null) {
            if (connection() !== client || selected() !== owner) { finished(task, null); return }
            client.requestPhoneTransfer("ack", JSONObject().put("id", item.id)) { _, issue ->
                if (selected() === owner && issue == null) remote = remote.filterNot { it.id == item.id }
                if (issue == null && onAck != null) persist(onAck) { finished(task, it) } else finished(task, issue)
            }
        }
        if (local?.state == "cancelledIncoming" || local?.state == "cancelledReceive") {
            ack { store.acknowledgeCancellation(owner, local) }
            return
        }
        if (local?.state == "received" && store.payload(owner, local).isFile) { ack(); return }
        val transfer = DirectFileDownload(client, phoneTransfer = true)
        download = transfer
        task.cancel = transfer::cancel
        render()
        transfer.start(activity.cacheDir, JSONObject().put("id", item.id), { count, total ->
            progress(task, "接收 ${if (total == 0L) 100 else count * 100 / total}%")
        }, reportCancellation = true) { outcome ->
            download = null
            task.cancel = null
            render()
            outcome.fold({ staged ->
                persist({ try { store.receive(owner, item, staged) } finally { staged.file.delete() } }) { failure ->
                    if (failure == null) ack() else finished(task, failure)
                }
            }, { finished(task, it) })
        }
    }

    private fun cancel(owner: Device, item: TransferEntry) {
        val task = active?.takeIf { it.owner === owner && it.item.id == item.id }
        if (item.id in changing || (task != null && task.cancel == null)) return
        if (task != null) {
            task.cancelled = true
            task.cancel?.invoke()
            task.cancel = null
        }
        changeRecord(item.id, { store.cancel(owner, item) })
        render()
    }

    private fun progress(task: Active, message: String) {
        if (task.cancelled) return
        task.progress = "${task.item.name} · $message"
        if (task.owner === knownOwner) status?.text = task.progress
    }

    private fun finished(task: Active, issue: Throwable?) {
        if (active !== task) return
        val failure = issue.takeUnless { task.cancelled }
        if (failure != null) retryAfter.put(task.item.id, SystemClock.elapsedRealtime() + 30_000)
        else retryAfter.remove(task.item.id)
        if (task.owner === knownOwner) error = failure?.message.orEmpty()
        active = null
        render(); schedule(200)
    }

    private fun persist(action: () -> Unit, result: (Throwable?) -> Unit) {
        TransferStorage.worker.execute {
            val issue = runCatching(action).exceptionOrNull()
            handler.post { if (!activity.isDestroyed) result(issue) }
        }
    }

    private fun changeRecord(id: String, action: () -> Unit) {
        if (!changing.add(id)) return
        persist(action) {
            changing.remove(id)
            if (it != null) { toast(it.message ?: "无法更新传输记录"); retryAfter.put(id, SystemClock.elapsedRealtime() + 30_000) }
            render(); schedule(200)
        }
    }

    private fun save(owner: Device, item: TransferEntry) {
        if (Build.VERSION.SDK_INT < 29 &&
            activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            if (pendingStoragePermission != null) return
            pendingStoragePermission = owner to item
            runCatching { storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }.onFailure {
                pendingStoragePermission = null
                toast("无法请求存储权限")
            }
        } else saveToDownloads(owner, item)
    }

    private fun saveToDownloads(owner: Device, item: TransferEntry) {
        val key = WorkspaceDownloads.transferKey(owner, item.id)
        fileAction(item, {
            if (downloads.findReadable(key) == null) {
                val file = store.payload(owner, item)
                if (!file.isFile) throw IOException("手机上的接收缓存已不存在")
                downloads.save(key, StagedDownload(item.name, file), folder = null)
            }
        }) { result -> toast(result.exceptionOrNull()?.message ?: "已保存到「下载」") }
    }

    private fun open(owner: Device, item: TransferEntry) {
        val key = WorkspaceDownloads.transferKey(owner, item.id)
        fileAction(item, { downloads.findReadable(key) }) { result ->
            if (rows?.owner !== owner || activity.isFinishing || !resumed) return@fileAction
            result.onSuccess { uri ->
                if (uri == null) { toast("文件已移动或删除，可重新保存"); return@onSuccess }
                try {
                    val mime = activity.contentResolver.getType(uri) ?: WorkspaceDownloads.mime(item.name)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: ActivityNotFoundException) {
                    toast("没有可打开此文件的应用")
                } catch (_: SecurityException) {
                    toast("无法打开此文件，请检查文件权限")
                }
            }.onFailure { toast(it.message ?: "无法打开此文件") }
        }
    }

    private fun <T> fileAction(item: TransferEntry, action: () -> T, result: (Result<T>) -> Unit) {
        if (!fileActions.add(item.id)) return
        render()
        TransferStorage.worker.execute {
            val outcome = runCatching(action)
            handler.post {
                fileActions.remove(item.id)
                if (!activity.isDestroyed) { render(); result(outcome) }
            }
        }
    }

    private fun render(entries: List<TransferEntry>? = null) {
        val adapter = rows ?: return
        val owner = knownOwner ?: return
        if (adapter.owner !== owner) return
        val direct = connection()?.usingDirect == true
        val local = entries ?: store.entries(owner)
        val pending = local.count { it.state == "pending" }
        val ids = local.mapTo(HashSet()) { it.id }
        val awaiting = remote.filterNot { it.id in ids }
        val task = active?.takeIf { it.owner === owner }
        status?.text = listOfNotNull(
            if (direct) "P2P 直连" else "缓存管道 · 等待 P2P 直连",
            "$pending 个待发送 · ${awaiting.size} 个待接收",
            if (connection() != null && !capabilities().optBoolean("phoneTransfer")) "需更新电脑 Agent 后同步" else null,
            if (direct && pending > 0 && !capabilities().optBoolean("desktopShare")) "请在电脑 Agent 设置中允许远程管理文件" else null,
            task?.progress?.takeIf { it.isNotEmpty() && !task.cancelled } ?: error.takeIf { it.isNotEmpty() },
        ).joinToString("\n")
        adapter.update((local + awaiting).sortedWith(compareBy({ it.created }, { it.id })).map { item ->
            val working = task?.item?.id == item.id
            val cancellable = item.state in setOf("pending", "remote") && item.id !in changing && (!working || task.cancel != null)
            val saved = item.state == "received" && downloads.contains(WorkspaceDownloads.transferKey(owner, item.id))
            Row(item, working, cancellable, saved, item.id in fileActions)
        })
    }

    private inner class TransferAdapter(val owner: Device) : BaseAdapter() {
        private var items = emptyList<Row>()
        fun update(value: List<Row>) { if (items != value) { items = value; notifyDataSetChanged() } }
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = false
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val (item, working, cancellable, saved, fileActionBusy) = getItem(position)
            val outgoing = item.state in setOf("pending", "sent", "cancelledSend")
            val row = FrameLayout(activity).apply {
                val inset = Ui.dp(context, 32)
                setPaddingRelative(if (outgoing) inset else 0, Ui.dp(context, 5), if (outgoing) 0 else inset, Ui.dp(context, 5))
            }
            val bubble = Ui.column(activity).apply {
                setPadding(Ui.dp(context, 14), Ui.dp(context, 9), Ui.dp(context, 14), Ui.dp(context, 7))
                background = Ui.rounded(if (outgoing) Ui.SURFACE_ALT else ReferenceUi.card, Ui.dp(context, 18).toFloat(), ReferenceUi.border)
            }
            row.addView(bubble, FrameLayout.LayoutParams(-2, -2, if (outgoing) Gravity.END else Gravity.START))
            bubble.addView(Ui.text(activity, item.name, 15f, bold = true).apply { maxLines = 4 })
            val label = when (item.state) {
                "pending" -> if (working) "正在发送" else "待发送"
                "sent" -> "已发送"
                "remote" -> if (working) "正在接收" else "待接收"
                "cancelledSend", "cancelledIncoming", "cancelledReceive" -> "已取消"
                else -> if (saved) "已保存" else "已接收"
            }
            val direction = if (outgoing) "手机 → 电脑" else "电脑 → 手机"
            bubble.addView(Ui.text(activity, "$direction · $label · ${Formatter.formatFileSize(activity, item.size)}", 12f, Ui.MUTED))
            val actions = Ui.column(activity, false)
            if (cancellable) actions.addView(Ui.button(activity, "取消传输") { cancel(owner, item) })
            if (item.state == "received" && !working) {
                val actionLabel = if (fileActionBusy && !saved) "正在保存…" else if (saved) "打开" else "保存"
                actions.addView(Ui.button(activity, actionLabel) {
                    if (saved) open(owner, item) else save(owner, item)
                }.apply { isEnabled = !fileActionBusy })
            }
            if (!working && !fileActionBusy && item.state in setOf("received", "sent", "cancelledSend", "cancelledReceive") &&
                !(item.state == "sent" && item.batch.isNotEmpty()) && remote.none { it.id == item.id }) {
                actions.addView(Ui.iconButton(activity, "close", "移除 ${item.name}", 36) {
                    MiuixDialog.Builder(activity).setTitle("移除文件记录？")
                        .setMessage(when {
                            item.state == "sent" -> "不会删除电脑中的文件。"
                            saved -> "将删除传输记录和缓存，不会删除已保存到下载目录的文件。"
                            else -> "将删除手机上的私有缓存。"
                        })
                        .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ ->
                            changeRecord(item.id, {
                                store.remove(owner, item)
                                if (saved) downloads.forget(WorkspaceDownloads.transferKey(owner, item.id))
                            })
                        }.show()
                })
            }
            if (actions.childCount > 0) bubble.addView(actions)
            return row
        }
    }
}
