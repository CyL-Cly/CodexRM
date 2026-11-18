package com.codexrm.mobile

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageProps
import org.commonmark.node.Image
import java.util.WeakHashMap

internal class MessageImagesPlugin(private val context: Context, private val images: MessageImageLoader) : AbstractMarkwonPlugin() {
    private val requests = WeakHashMap<AsyncDrawable, Any>()
    private val loader = object : AsyncDrawableLoader() {
        override fun load(drawable: AsyncDrawable) {
            val token = Any()
            requests[drawable] = token
            val image = MessageImage.fromSource(drawable.destination)
            if (image == null) { drawable.setResult(label("图片地址不支持")); return }
            images.load(image) { result ->
                if (requests[drawable] !== token || !drawable.isAttached) return@load
                drawable.setResult(result.fold(
                    onSuccess = { bitmap -> BitmapDrawable(context.resources, bitmap).apply {
                        // Markwon's resolver reads result.bounds, not intrinsic size.
                        // Empty bounds otherwise collapse an already decoded image.
                        setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                    } },
                    onFailure = { label("图片加载失败，点击重试") },
                ))
            }
        }
        override fun cancel(drawable: AsyncDrawable) { requests.remove(drawable) }
        override fun placeholder(drawable: AsyncDrawable): Drawable = label("正在加载图片…")
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(Image::class.java) { configuration, props ->
            val source = ImageProps.DESTINATION.require(props)
            val drawable = AsyncDrawable(source, loader, configuration.imageSizeResolver(), ImageProps.IMAGE_SIZE.get(props))
            arrayOf<Any>(
                AsyncDrawableSpan(configuration.theme(), drawable, AsyncDrawableSpan.ALIGN_BOTTOM, false),
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val bitmap = (drawable.result as? BitmapDrawable)?.bitmap
                        if (bitmap != null) showImage(context, bitmap, "图片") else loader.load(drawable)
                    }
                    override fun updateDrawState(ds: TextPaint) { ds.isUnderlineText = false }
                },
            )
        }
    }

    override fun beforeSetText(textView: TextView, markdown: Spanned) = AsyncDrawableScheduler.unschedule(textView)
    override fun afterSetText(textView: TextView) = AsyncDrawableScheduler.schedule(textView)

    private fun label(text: String): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = Ui.dp(context, 12).toFloat() }
        override fun getIntrinsicWidth(): Int = Ui.dp(context, 240)
        override fun getIntrinsicHeight(): Int = Ui.dp(context, 110)
        override fun draw(canvas: Canvas) {
            paint.color = Ui.SURFACE_ALT
            canvas.drawRect(bounds, paint)
            paint.color = Ui.MUTED
            canvas.drawText(text, bounds.left + Ui.dp(context, 12).toFloat(), bounds.exactCenterY(), paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }
}

internal fun showImage(context: Context, bitmap: Bitmap, name: String) {
    val image = ImageView(context).apply {
        setImageBitmap(bitmap)
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
        contentDescription = name
        setPadding(Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8), Ui.dp(context, 8))
    }
    AlertDialog.Builder(context).setTitle(name).setView(image).setPositiveButton("关闭", null).show()
}
