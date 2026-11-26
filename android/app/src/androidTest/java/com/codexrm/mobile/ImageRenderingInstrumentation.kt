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
    private lateinit var args: Bundle
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }

    override fun onStart() {
        if (args.getString("visualizationCheck") == "true") {
            VisualizationRenderingCheck.run(this, liveChart = args.getString("liveChart") == "true")
            return
        }
        if (args.getString("predictiveBack") == "true") {
            PredictiveBackCheck.run(this, realGesture = args.getString("deviceGesture") == "true")
            return
        }
        if (args.getString("appearanceCheck") == "true") {
            AppearanceCheck.run(this)
            return
        }
        if (args.getString("claudeUx") == "true") {
            ClaudeUxCheck.run(this)
            return
        }
        if (args.getString("snapshotCheck") == "true") {
            SnapshotRenderingCheck.run(this)
            return
        }
        if (args.getString("conversationUpgrade") == "true") {
            ConversationUpgradeCheck.run(this, args)
            return
        }
        if (args.getString("pairingWarning") == "true") {
            PairingWarningCheck.run(this)
            return
        }
        val settingsOnly = args.getString("settingsUi") == "true"
        val workspaceOnly = args.getString("workspacePicker") == "true"
        val composerOnly = args.getString("composerCheck") == "true"
        if (settingsOnly || workspaceOnly || composerOnly || args.getString("uiReview") == "true") {
            UiReviewCheck.run(this, settingsOnly, workspaceOnly, composerOnly)
            return
        }
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
            lateinit var toolView: View
            lateinit var markdownUserView: View
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
                toolView = adapter.getView(1, null, root)
                root.addView(toolView)
                adapter.add(ChatMessage("markdown-user", MessageKind.USER, "", "![图片]($uri)"))
                markdownUserView = adapter.getView(2, null, root)
                root.addView(markdownUserView)
            }
            val deadline = System.currentTimeMillis() + 10_000
            var rendered = false
            while (!rendered && System.currentTimeMillis() < deadline) {
                runOnMainSync {
                    val spans = (assistant.text as? Spanned)?.getSpans(0, assistant.length(), AsyncDrawableSpan::class.java).orEmpty()
                    val markdownUser = requireNotNull(adapter.messageBody(markdownUserView))
                    val userSpans = (markdownUser.text as? Spanned)?.getSpans(0, markdownUser.length(), AsyncDrawableSpan::class.java).orEmpty()
                    rendered = spans.any { it.drawable.result is BitmapDrawable } &&
                        userSpans.any { it.drawable.result is BitmapDrawable } &&
                        imageViews(root).count { it.drawable is BitmapDrawable } >= 2
                }
                if (!rendered) Thread.sleep(100)
            }
            check(rendered) { "Markdown or native image failed to render" }
            // Drawing needs a layout even when the test surface has not received a window traversal.
            // Bitmap decoding can complete before that traversal.
            var imagePixels = 0
            runOnMainSync {
                val width = assistant.width.takeIf { it > 0 } ?: Ui.dp(screen, 360)
                assistant.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                val height = assistant.measuredHeight.coerceAtLeast(1)
                assistant.layout(0, 0, width, height)
                val renderedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    assistant.draw(Canvas(renderedBitmap))
                    val expected = Color.rgb(28, 94, 132)
                    for (y in 0 until height step 4) for (x in 0 until width step 4) {
                        if (renderedBitmap.getPixel(x, y) == expected) imagePixels++
                    }
                } finally { renderedBitmap.recycle() }
            }
            check(imagePixels > 1000) { "decoded Markdown image was not actually painted ($imagePixels image pixels)" }
            uiAutomation.waitForIdle(250, 5_000)
            val screenshot = File(targetContext.getExternalFilesDir(null), "image-display.png")
            uiAutomation.takeScreenshot().also { bitmap -> screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle() }

            if (android.os.Build.VERSION.SDK_INT >= 29) runOnMainSync {
                check(imageViews(userView).single().performClick()) { "Preview could not be opened" }
                val buttons = ArrayList<View>()
                for (window in android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                    window.findViewsWithText(buttons, "关闭图片预览", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION)
                }
                val closePreview = checkNotNull(buttons.singleOrNull()) { "Full-screen preview did not appear" }
                check(closePreview.performClick()) { "Preview could not close" }
            }

            runOnMainSync {
                val width = Ui.dp(screen, 400)
                fun layout(view: View) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.layout(0, 0, width, view.measuredHeight)
                }
                fun checkPhoto(row: View, right: Boolean) {
                    layout(row)
                    val photo = imageViews(row).single()
                    var ancestor: View = photo
                    var left = 0
                    while (ancestor !== row) {
                        check(ancestor.background == null) { "Image still has a card background" }
                        left += ancestor.left
                        ancestor = ancestor.parent as View
                    }
                    check(row.background == null) { "Image row still has a bubble" }
                    val inset = Ui.dp(screen, 14)
                    check(photo.width in 1 until width - 2 * inset && kotlin.math.abs(photo.width - photo.height * 2.5) <= 3) {
                        "Image size or aspect ratio changed: ${photo.width}x${photo.height}"
                    }
                    check(if (right) left + photo.width == width - inset else left == inset) {
                        "Photo not aligned to the sender's edge: left=$left, width=${photo.width}, row=$width, right=$right"
                    }
                    check(photo.hasOnClickListeners()) { "Image preview is no longer clickable" }
                }
                checkPhoto(userView, right = true)
                checkPhoto(toolView, right = false)
                check(adapter.messageBody(userView)?.background != null) { "Text sent with an attachment lost its bubble" }
                layout(markdownUserView)
                val markdownUser = requireNotNull(adapter.messageBody(markdownUserView))
                check(markdownUser.background == null) { "Standalone Markdown image still has a bubble" }
                val line = markdownUser.layout.getLineForOffset(0)
                val lineLeft = markdownUser.layout.getLineLeft(line)
                val lineRight = markdownUser.layout.getLineRight(line)
                check(lineLeft > 0f && kotlin.math.abs(lineRight - markdownUser.width) <= 1f) {
                    "Markdown image is not right-aligned: $lineLeft..$lineRight of ${markdownUser.width}"
                }

                adapter.add(ChatMessage("plain", MessageKind.USER, "", "只有文字"))
                val recycled = adapter.getView(3, userView, root)
                check(imageViews(recycled).isEmpty()) { "recycled row kept the previous image" }
                val recycledMarkdown = adapter.getView(3, markdownUserView, root)
                check(adapter.messageBody(recycledMarkdown)?.background != null) { "Recycled text row lost its bubble" }
            }
            finish(Activity.RESULT_OK, Bundle().apply {
                putString("stream", "PASS: sent-image cache after draft cleanup/recreation; bubble-free photos aligned to sender; clickable previews; Markdown images with LaTeX; recycled rows. Screenshot: ${screenshot.path}\n")
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
