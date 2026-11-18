package com.codexrm.mobile

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.text.Spanned
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.noties.markwon.image.AsyncDrawableSpan
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// Native-device regression checks, run with -Pcodexrm.imageQa in an isolated app.
// Uses the platform runner API so production gains no test or UI dependencies.
class ImageRenderingInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        var activity: Activity? = null
        var images: MessageImageLoader? = null
        try {
            check(targetContext.packageName.endsWith(".imageqa")) { "Use -Pcodexrm.imageQa to protect the user's installed app" }
            activity = startActivitySync(Intent().setClassName(targetContext.packageName, "com.codexrm.mobile.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val screen = activity
            val env = ImageEnvironment("image-rendering-qa", "/workspace", null, true)
            val png = ByteArrayOutputStream().apply {
                val bitmap = Bitmap.createBitmap(600, 240, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(28, 94, 132))
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(101, 214, 173); textSize = 42f }
                canvas.drawCircle(100f, 120f, 65f, paint)
                paint.color = Color.WHITE
                canvas.drawText("CodexRM image", 200f, 135f, paint)
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, this)
                bitmap.recycle()
            }.toByteArray()
            val uri = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
            val store = AttachmentStore(targetContext)
            val draft = File(targetContext.filesDir, "attachment-drafts/qa-photo").apply { parentFile!!.mkdirs(); writeBytes(png) }
            val file = JSONObject().put("draftId", "qa-photo").put("id", "sent-photo").put("name", "已发送照片.png")
                .put("path", "/agent/uploads/sent-photo.png").put("localPath", draft.path).put("mimeType", "image/png").put("size", png.size)
            val cached = CountDownLatch(1)
            var cacheError: Exception? = null
            runOnMainSync {
                images = MessageImageLoader(screen) { env }
                images!!.retain(listOf(file), store) { cacheError = it; cached.countDown() }
            }
            check(cached.await(10, TimeUnit.SECONDS)) { "cache write timed out" }
            check(cacheError == null) { cacheError.toString() }
            store.remove("qa-draft", file)
            check(!draft.exists()) { "sent draft was not removed" }

            val loaded = CountDownLatch(1)
            var loadResult: Result<Bitmap>? = null
            runOnMainSync {
                images!!.close()
                images = MessageImageLoader(screen) { env }
                images!!.load(MessageImage(file.string("path"), file.string("name"), file.string("id"))) {
                    loadResult = it; loaded.countDown()
                }
            }
            check(loaded.await(10, TimeUnit.SECONDS)) { "cache read timed out" }
            check(loadResult?.getOrNull()?.width == 600) { "sent image failed after draft deletion and loader recreation: $loadResult" }

            lateinit var root: LinearLayout
            lateinit var assistant: TextView
            lateinit var adapter: MessageAdapter
            lateinit var userView: View
            runOnMainSync {
                root = Ui.column(screen).apply { setBackgroundColor(Ui.BACKGROUND); setPadding(0, Ui.dp(screen, 38), 0, 0) }
                screen.setContentView(ScrollView(screen).apply { addView(root) })
                adapter = MessageAdapter(screen, images!!)
                val user = ProtocolFormatter.fromItem(JSONObject().put("type", "userMessage").put("id", "user")
                    .put("content", AttachmentStore.input("已发送图片：删除草稿后仍从缓存显示", listOf(file))))
                adapter.add(user)
                userView = adapter.getView(0, null, root)
                root.addView(userView)
                assistant = Ui.text(screen).apply {
                    setPadding(24, 16, 24, 16)
                    movementMethod = MessageMovement.instance
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
                root.addView(assistant)
                Markdown(screen, images!!).bind(assistant, ChatMessage("reply", MessageKind.ASSISTANT, "Codex", "Codex 返回的图片（Markdown）\n\n![返回图片]($uri)\n\n公式仍正常：\$x^2\$"))
                val tool = ProtocolFormatter.fromItem(JSONObject().put("id", "tool").put("type", "mcpToolCall").put("server", "images").put("tool", "preview")
                    .put("result", JSONObject().put("content", org.json.JSONArray().put(JSONObject().put("type", "image").put("mimeType", "image/png").put("data", Base64.encodeToString(png, Base64.NO_WRAP))))))
                adapter.add(tool)
                root.addView(adapter.getView(1, null, root))
            }
            val deadline = System.currentTimeMillis() + 10_000
            var rendered = false
            while (!rendered && System.currentTimeMillis() < deadline) {
                runOnMainSync {
                    val spans = (assistant.text as? Spanned)?.getSpans(0, assistant.length(), AsyncDrawableSpan::class.java).orEmpty()
                    rendered = spans.any { it.drawable.result is BitmapDrawable } && imageViews(root).count { it.drawable is BitmapDrawable } >= 2
                }
                if (!rendered) Thread.sleep(100)
            }
            check(rendered) { "Markdown or native image failed to render" }
            waitForIdleSync()
            Thread.sleep(350) // Let SurfaceFlinger present the frame being verified.
            runOnMainSync {
                val renderedBitmap = Bitmap.createBitmap(assistant.width, assistant.height, Bitmap.Config.ARGB_8888)
                assistant.draw(Canvas(renderedBitmap))
                var imagePixels = 0
                val expected = Color.rgb(28, 94, 132)
                for (y in 0 until renderedBitmap.height step 4) for (x in 0 until renderedBitmap.width step 4) {
                    if (renderedBitmap.getPixel(x, y) == expected) imagePixels++
                }
                renderedBitmap.recycle()
                check(imagePixels > 1000) { "decoded Markdown image was not actually painted ($imagePixels image pixels)" }
            }
            val screenshot = File(targetContext.getExternalFilesDir(null), "image-display.png")
            uiAutomation.takeScreenshot().also { bitmap -> screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }

            runOnMainSync {
                adapter.add(ChatMessage("plain", MessageKind.USER, "", "只有文字"))
                val recycled = adapter.getView(2, userView, root)
                check(imageViews(recycled).isEmpty()) { "recycled row kept the previous image" }
            }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: sent-image cache after draft cleanup/recreation; native user/tool images; Markdown images with LaTeX; recycled rows. Screenshot: ${screenshot.path}\n")
            })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "FAIL: ${error.stackTraceToString()}\n") })
        } finally { runOnMainSync { images?.close(); activity?.finish() } }
    }

    private fun imageViews(view: View): List<android.widget.ImageView> = when (view) {
        is android.widget.ImageView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { imageViews(view.getChildAt(it)) }
        else -> emptyList()
    }
}
