package com.codexrm.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.text.TextUtils
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer

private data class GalleryWork(
    val title: String,
    val displayUrl: String?,
    val credit: String,
    val licenseType: String,
    val licenseNote: String,
    val detailUrl: String,
) {
    fun creditText(): String = buildString {
        append(credit.ifEmpty { "作者未标注" })
        append(" · ")
        append(when (licenseType) {
            "unknown", "" -> "授权状态不明"
            else -> "授权信息见作品来源"
        })
        if (licenseNote.isNotBlank()) append("\n").append(licenseNote)
    }
}

private data class GalleryEntry(val title: String, val url: String, val count: Int)
private data class GalleryTile(val work: GalleryWork, val preview: Bitmap?)

private fun JSONObject.value(name: String): String = if (isNull(name)) "" else optString(name)
private fun JSONObject.optional(name: String): String? = value(name).takeIf(String::isNotBlank)
private fun JSONObject.list(urlKey: String): List<GalleryEntry> {
    if (optInt("apiVersion") != 1) throw IOException("Unsupported gallery API")
    val items = getJSONArray("items")
    return (0 until items.length()).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val url = item.optional(urlKey)?.takeIf { it.startsWith(AppearanceImages.API) } ?: return@mapNotNull null
        GalleryEntry(item.value("name"), url, item.optInt("count"))
    }
}

internal class AppearanceGallery(private val activity: MainActivity) {
    val view = Ui.column(activity)
    private val cache = LruCache<String, GalleryTile>(40)
    private val thumbnails = mutableMapOf<GalleryCell, AppearanceImages.Job>()
    private var listJob: AppearanceImages.Job? = null
    private var detail: MiuixDialog? = null
    var showingWorks = false
        private set
    private var closed = false

    init { showCharacters() }

    fun back(): Boolean {
        if (!showingWorks) return false
        showCharacters()
        return true
    }

    fun close() {
        closed = true
        clear()
        cache.evictAll()
    }

    private fun clear() {
        listJob?.cancel()
        listJob = null
        detail?.dismiss()
        detail = null
        thumbnails.values.forEach { it.cancel() }
        thumbnails.clear()
        view.removeAllViews()
    }

    private fun showCharacters() {
        showingWorks = false
        load(
            AppearanceImages.API + "characters.json", "worksUrl", ::showCharacters,
        ) { entries ->
            val column = Ui.column(activity).apply { setPadding(dp(16), dp(4), dp(16), dp(16)) }
            for (entry in entries) {
                val label = if (entry.count > 0) "${entry.title} · ${entry.count} 张" else entry.title
                column.addView(Ui.button(activity, label) { showWorks(entry) }, row(bottom = 8))
            }
            view.addView(ScrollView(activity).apply { addView(column) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun showWorks(character: GalleryEntry) {
        showingWorks = true
        load(character.url, "detailUrl", { showWorks(character) }) { entries ->
            view.addView(Ui.text(activity, character.title, 15f, bold = true).apply {
                setPadding(dp(16), dp(8), dp(16), dp(8))
                isAccessibilityHeading = true
            }, row())
            val grid = GridView(activity).apply {
                numColumns = GridView.AUTO_FIT
                columnWidth = dp(107)
                stretchMode = GridView.STRETCH_COLUMN_WIDTH
                horizontalSpacing = dp(8)
                verticalSpacing = dp(8)
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), dp(12))
                clipToPadding = false
                setRecyclerListener { (it as? GalleryCell)?.let { cell -> thumbnails.remove(cell)?.cancel() } }
                adapter = object : BaseAdapter() {
                    override fun getCount(): Int = entries.size
                    override fun getItem(position: Int): GalleryEntry = entries[position]
                    override fun getItemId(position: Int): Long = position.toLong()
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                        (convertView as? GalleryCell ?: GalleryCell()).apply { bind(getItem(position)) }
                }
                setOnItemClickListener { cellParent, cell, position, _ ->
                    val entry = (cellParent.adapter as BaseAdapter).getItem(position) as GalleryEntry
                    val tile = (cell as? GalleryCell)?.tile ?: cache.get(entry.url)
                    if (tile != null) showWork(tile) else fetchWork(entry)
                }
            }
            view.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        }
    }

    private fun load(url: String, urlField: String, retry: () -> Unit, display: (List<GalleryEntry>) -> Unit) {
        clear()
        showStatus("正在加载${if (showingWorks) "图片列表" else "角色分类"}…")
        listJob = AppearanceImages.submit({ json(url).list(urlField) }) { job, result ->
            if (closed || listJob !== job) return@submit
            listJob = null
            view.removeAllViews()
            val entries = result.getOrNull()
            when {
                entries == null -> showStatus("图库连接失败，请重试", retry)
                entries.isEmpty() -> showStatus("暂无可选图片")
                else -> display(entries)
            }
        }
    }

    private fun showStatus(message: String, retry: (() -> Unit)? = null) {
        view.addView(Ui.text(activity, message, 14f, Ui.MUTED).apply {
            setPadding(dp(16), dp(12), dp(16), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, row())
        if (retry != null) view.addView(Ui.button(activity, "重试") { retry() }, row())
    }

    private fun fetchWork(entry: GalleryEntry) {
        listJob?.cancel()
        val loading = MiuixDialog.Builder(activity).setTitle(entry.title).setMessage("正在加载作品…")
            .setNegativeButton("取消", null).show()
        detail = loading
        listJob = AppearanceImages.submit({ loadTile(entry) }) { job, result ->
            if (closed || listJob !== job || !loading.isShowing) return@submit
            listJob = null
            loading.dismiss()
            val tile = result.getOrNull()
            if (tile != null) showWork(tile)
            else detail = MiuixDialog.Builder(activity).setTitle("无法加载作品")
                .setPositiveButton("重试") { _, _ -> fetchWork(entry) }.setNegativeButton("取消", null).show()
        }
        loading.setOnDismissListener {
            if (detail === loading) {
                listJob?.cancel()
                listJob = null
            }
        }
    }

    private fun showWork(tile: GalleryTile) {
        val work = tile.work
        val body = Ui.column(activity).apply { setPadding(dp(16), dp(6), dp(16), dp(8)) }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(tile.preview)
            contentDescription = "${work.title}预览"
        }
        body.addView(image, LinearLayout.LayoutParams(-1, dp(170)))
        body.addView(Ui.text(activity, work.creditText(), 13f, Ui.MUTED).apply { maxLines = 5 }, row())
        body.addView(Ui.text(activity,
            "请前往原站查看授权并下载图片。下载后，回到「背景与透明度」，通过「选择本地图片」设为背景。",
            14f, Ui.TEXT).apply { setPadding(0, dp(8), 0, 0) }, row())
        val dialog = MiuixDialog.Builder(activity).setTitle(work.title).setView(body)
            .setPositiveButton("前往原站") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(work.detailUrl)))
                } catch (_: ActivityNotFoundException) {
                    Toast.makeText(activity, "找不到可打开原站的浏览器", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("返回", null)
            .show()
        detail = dialog
        val previewJob = work.displayUrl?.let { url ->
            AppearanceImages.submit({ loadPreview(url, 640) }) { _, result ->
                if (dialog.isShowing) result.getOrNull()?.let(image::setImageBitmap)
            }
        }
        dialog.setOnDismissListener { previewJob?.cancel() }
    }

    private fun AppearanceImages.Job.loadTile(entry: GalleryEntry): GalleryTile {
        val work = json(entry.url).getJSONObject("work")
        val image = work.getJSONObject("image")
        val author = work.optJSONObject("author")?.optional("name")
        val attribution = work.optJSONObject("attribution")?.optional("name")
        val license = work.optJSONObject("license") ?: JSONObject()
        val slug = work.optional("slug")?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]+")) }
        val artwork = GalleryWork(
            work.optional("name") ?: entry.title,
            image.optional("displayUrl"),
            author?.let { "作者 $it" } ?: attribution?.let { "收录署名 $it" }.orEmpty(),
            license.value("type"), license.value("note"),
            slug?.let { "${AppearanceImages.GALLERY}/works/$it.html" } ?: "${AppearanceImages.GALLERY}/browse.html",
        )
        val thumbnail = image.optional("thumbnailUrl")?.let { runCatching { loadPreview(it, 280) }.getOrNull() }
        return GalleryTile(artwork, thumbnail)
    }

    private fun AppearanceImages.Job.loadPreview(url: String, maxEdge: Int): Bitmap = read(url, 4L * 1024 * 1024) { input ->
        val output = java.io.ByteArrayOutputStream()
        AppearanceImages.copyLimited(input, output, 4L * 1024 * 1024)
        AppearanceImages.decode(ImageDecoder.createSource(ByteBuffer.wrap(output.toByteArray())), maxEdge)
    }

    private inner class GalleryCell : LinearLayout(activity) {
        private val picture = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val caption: TextView = Ui.text(activity, "", 12f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(7), 0, dp(7), 0)
        }
        var tile: GalleryTile? = null
            private set
        private var boundUrl: String? = null

        init {
            orientation = VERTICAL
            background = Ui.rounded(Ui.SURFACE, dp(10).toFloat())
            clipToOutline = true
            addView(picture, LinearLayout.LayoutParams(-1, dp(104)))
            addView(caption, LinearLayout.LayoutParams(-1, dp(40)))
        }

        fun bind(entry: GalleryEntry) {
            if (boundUrl == entry.url && (tile != null || thumbnails.containsKey(this))) return
            thumbnails.remove(this)?.cancel()
            boundUrl = entry.url
            caption.text = entry.title
            setTile(cache.get(entry.url))
            if (tile != null) return
            thumbnails[this] = AppearanceImages.submit({ loadTile(entry) }) { job, result ->
                if (closed || thumbnails[this] !== job) return@submit
                thumbnails.remove(this)
                result.getOrNull()?.let { cache.put(entry.url, it); setTile(it) }
            }
        }

        private fun setTile(value: GalleryTile?) {
            tile = value
            picture.setImageBitmap(value?.preview)
        }
    }

    private fun dp(value: Int) = Ui.dp(activity, value)
    private fun row(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
}
