package com.codexrm.mobile

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.PatternMatcher
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.GridView
import android.widget.TextView
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object AppearanceCheck {
    fun run(test: ImageRenderingInstrumentation) {
        var activity: MainActivity? = null
        var appearance: ChatAppearance? = null
        var page: SettingsPage? = null
        try {
            val context = test.targetContext
            check(context.packageName.endsWith(".imageqa"))
            activity = test.startActivitySync(Intent().setClassName(context.packageName, MainActivity::class.java.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val screen = activity
            val style = ChatAppearance(context) {}
            appearance = style
            val sample = File(context.cacheDir, "appearance-qa.png")
            sample.outputStream().use { file ->
                Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(Color.rgb(230, 62, 106))
                    compress(Bitmap.CompressFormat.PNG, 100, file)
                    recycle()
                }
            }
            test.runOnMainSync { AppearanceArea.entries.forEach(style::reset) }
            val original = android.graphics.BitmapFactory.decodeFile(sample.path)
            val fullImage = RectF(0f, 0f, original.width.toFloat(), original.height.toFloat())
            for (area in AppearanceArea.entries) {
                awaitImage(test) { done -> style.saveCrop(area, original, fullImage, done) }
            }
            test.runOnMainSync {
                style.setBubbleOpacity(45)
                style.setMask(AppearanceArea.CHAT, 0)
                style.setMask(AppearanceArea.SIDEBAR, 35)
                style.setMask(AppearanceArea.SETTINGS, 100)
                val output = Bitmap.createBitmap(24, 36, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(output)
                fun pixel(area: AppearanceArea): Int {
                    style.drawable(area).apply { setBounds(0, 0, output.width, output.height); draw(canvas) }
                    return output.getPixel(8, 8)
                }
                check(pixel(AppearanceArea.CHAT) == Color.rgb(230, 62, 106))
                check(pixel(AppearanceArea.SETTINGS) == AppearanceArea.SETTINGS.baseColor())
                check(pixel(AppearanceArea.SIDEBAR) != pixel(AppearanceArea.CHAT))
                output.recycle()
            }
            awaitImage(test, expectSuccess = false) { done ->
                style.loadLocal(Uri.fromFile(File(context.cacheDir, "missing.png"))) { _, result -> done(result.isSuccess) }
            }
            test.runOnMainSync {
                check(style.bitmap(AppearanceArea.CHAT) != null)
                check(style.source(AppearanceArea.CHAT) == BackgroundSource.LOCAL)
                style.close()
            }
            val restored = ChatAppearance(context) {}
            appearance = restored
            test.runOnMainSync { restored.start() }
            waitFor(test) { AppearanceArea.entries.all { restored.bitmap(it) != null } }
            check(restored.mask(AppearanceArea.SIDEBAR) == 35 && restored.bubbleOpacity() == 45)
            check(randomRefreshDelay(500L, 500L + RANDOM_REFRESH_MS - 1) == 1L)
            check(randomRefreshDelay(500L, 500L + RANDOM_REFRESH_MS) == 0L)
            awaitImage(test) { done -> restored.random(AppearanceArea.CHAT, done) }
            check(restored.source(AppearanceArea.CHAT) == BackgroundSource.RANDOM)
            test.runOnMainSync {
                MainActivity::class.java.getDeclaredMethod("buildMain").apply { isAccessible = true }.invoke(screen)
                screen.setContentView(screen.backgroundTarget(AppearanceArea.CHAT))
                page = SettingsPage(screen, "", { null }, { false }, { _, _, _, _ -> }, {}, { emptyList() }, appearance = restored)
                    .apply { show(); showAppearance() }
            }
            fun root() = SettingsPage::class.java.getDeclaredField("settingsRoot").apply { isAccessible = true }.get(page) as View
            fun editor() = descendants(root()).filterIsInstance<WallpaperCropView>().firstOrNull()
            fun click(label: String): Boolean = descendants(root()).filterIsInstance<MiuixButtonView>()
                .firstOrNull { it.text == label }?.performClick() == true
            fun verifyAspect(area: AppearanceArea, crop: WallpaperCropView): RectF {
                val target = screen.backgroundTarget(area) ?: root()
                val region = checkNotNull(crop.selectedRegion())
                check(kotlin.math.abs(region.width() / region.height() - target.width.toFloat() / target.height) < 0.001f)
                return region
            }
            for (area in AppearanceArea.entries) {
                val previous = restored.bitmap(area)
                test.runOnMainSync { page?.cropLocalImage(area, Uri.fromFile(sample)) }
                waitFor(test) { editor()?.selectedRegion() != null }
                var expectedAspect = 0f
                waitFor(test) {
                    val crop = checkNotNull(editor())
                    val initial = verifyAspect(area, crop)
                    crop.zoomBy(1.25f)
                    val zoomed = verifyAspect(area, crop)
                    check(zoomed.width() < initial.width() && zoomed.height() < initial.height())
                    val t = SystemClock.uptimeMillis()
                    for ((action, x) in listOf(0 to 0.5f, 2 to 0.6f, 2 to 0.76f, 1 to 0.76f)) {
                        val event = android.view.MotionEvent.obtain(t, t + 45 * action, action, crop.width * x, crop.height / 2f, 0)
                        crop.dispatchTouchEvent(event)
                        event.recycle()
                    }
                    val moved = verifyAspect(area, crop)
                    check(moved.centerX() < zoomed.centerX())
                    expectedAspect = moved.width() / moved.height()
                    click(if (area == AppearanceArea.SIDEBAR) "取消" else "确认裁剪")
                }
                if (area == AppearanceArea.SIDEBAR) {
                    check(restored.bitmap(area) === previous)
                } else {
                    waitFor(test) { restored.bitmap(area) !== previous }
                    val saved = checkNotNull(restored.bitmap(area))
                    check(kotlin.math.abs(saved.width.toFloat() / saved.height - expectedAspect) < 0.01f)
                }
            }
            test.runOnMainSync {
                SettingsPage::class.java.getDeclaredMethod("showGallery")
                    .apply { isAccessible = true }.invoke(page)
            }
            waitFor(test) {
                val character = descendants(root()).filterIsInstance<MiuixButtonView>().firstOrNull { it.text.startsWith("Claude娘") }
                character?.performClick() == true
            }
            waitFor(test) {
                val grid = descendants(root()).filterIsInstance<GridView>().firstOrNull()
                val cell = grid?.getChildAt(0)
                cell != null && descendants(cell).filterIsInstance<android.widget.ImageView>().any { it.drawable != null } &&
                    grid.performItemClick(cell, grid.firstVisiblePosition, grid.firstVisiblePosition.toLong())
            }
            val workPageFilter = IntentFilter(Intent.ACTION_VIEW).apply {
                addDataScheme("https")
                addDataAuthority(checkNotNull(Uri.parse(AppearanceImages.GALLERY).host), null)
                addDataPath("/works/", PatternMatcher.PATTERN_PREFIX)
            }
            val monitor = test.addMonitor(workPageFilter, null, true)
            try {
                waitFor(test) {
                    val gallery = SettingsPage::class.java.getDeclaredField("gallery").apply { isAccessible = true }.get(page)
                    val detail = AppearanceGallery::class.java.getDeclaredField("detail").apply { isAccessible = true }.get(gallery) as? MiuixDialog
                    val openSite = runCatching { detail?.getButton(MiuixDialog.POSITIVE) }.getOrNull()
                    val hasGuide = detail?.contentView?.let { dialogView ->
                        descendants(dialogView).filterIsInstance<TextView>().any { it.text.contains("选择本地图片") }
                    } == true
                    openSite?.label == "前往原站" && hasGuide && openSite.performClick()
                }
                check(monitor.hits == 1) { "Expected a browser intent for the gallery work page" }
                test.runOnMainSync {
                    check(editor() == null)
                    check(restored.source(AppearanceArea.SIDEBAR) == BackgroundSource.LOCAL)
                    check(restored.workPage(AppearanceArea.SIDEBAR) == null)
                }
            } finally {
                test.removeMonitor(monitor)
            }
            test.finish(Activity.RESULT_OK, Bundle().apply { putString("stream", "\nPASS appearance: three masks, local persistence, failed replacement, random refresh, three crop ratios, drag, zoom, cancel and gallery work-page handoff\n") })
        } catch (failure: Throwable) {
            test.finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", "\nFAIL appearance: " + failure.stackTraceToString()) })
        } finally {
            test.runOnMainSync { page?.dismiss(); appearance?.close(); activity?.finish() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun awaitImage(test: ImageRenderingInstrumentation, expectSuccess: Boolean = true, operation: ((Boolean) -> Unit) -> Unit) {
        val completed = CountDownLatch(1)
        var success = false
        test.runOnMainSync { operation { success = it; completed.countDown() } }
        check(completed.await(60, TimeUnit.SECONDS)) { "Image operation timed out" }
        check(success == expectSuccess) { "Image operation success = " + success }
    }

    private fun waitFor(test: ImageRenderingInstrumentation, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            test.runOnMainSync { done = predicate() }
            if (done) return
            SystemClock.sleep(150)
        }
        error("Timed out waiting for appearance view or bitmap")
    }
}
