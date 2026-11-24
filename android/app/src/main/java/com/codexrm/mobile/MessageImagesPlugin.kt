package com.codexrm.mobile

import android.content.Context
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
import android.widget.TextView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.LinkResolver
import io.noties.markwon.core.CoreProps
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableLoader
import io.noties.markwon.image.AsyncDrawableScheduler
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageProps
import org.commonmark.node.Image
import org.commonmark.node.Link
import java.util.Collections
import java.util.WeakHashMap

internal class MessageImagesPlugin(private val context: Context, private val images: MessageImageLoader) : AbstractMarkwonPlugin() {
    var messageId = ""
    private val sources = Collections.synchronizedMap(WeakHashMap<AsyncDrawable, MessageImage>())
    private val requests = WeakHashMap<AsyncDrawable, Any>()
    private val loader = object : AsyncDrawableLoader() {
        override fun load(drawable: AsyncDrawable) {
            val token = Any()
            requests[drawable] = token
            val image = sources[drawable]
            if (image == null) { drawable.setResult(label("图片地址不支持")); return }
            images.load(image) { result ->
                if (requests[drawable] !== token || !drawable.isAttached) return@load
                drawable.setResult(result.fold(
                    onSuccess = { bitmap -> BitmapDrawable(context.resources, bitmap).apply {
                        // Markwon's resolver reads result.bounds, not intrinsic size.
                        // Empty bounds otherwise collapse an already decoded image.
                        setBounds(0, 0, intrinsicWidth, intrinsicHeight)
                    } },
                    onFailure = { label("图片无法显示，点击查看详情") },
                ))
            }
        }
        override fun cancel(drawable: AsyncDrawable) { requests.remove(drawable) }
        override fun placeholder(drawable: AsyncDrawable): Drawable = label("正在加载图片…")
    }

    override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
        builder.setFactory(Link::class.java) { configuration, props ->
            val destination = CoreProps.LINK_DESTINATION.require(props)
            val image = MessageImage.fromMarkdown(destination, link = true)?.copy(messageId = messageId)
            val resolver = if (image == null) configuration.linkResolver() else LinkResolver { view, _ ->
                showImagePreview(view.context, images, image)
            }
            LinkSpan(configuration.theme(), destination, resolver)
        }
        builder.setFactory(Image::class.java) { configuration, props ->
            val source = ImageProps.DESTINATION.require(props)
            val drawable = AsyncDrawable(source, loader, configuration.imageSizeResolver(), ImageProps.IMAGE_SIZE.get(props))
            val image = MessageImage.fromMarkdown(source)?.copy(messageId = messageId)
            if (image != null) sources[drawable] = image
            arrayOf<Any>(
                AsyncDrawableSpan(configuration.theme(), drawable, AsyncDrawableSpan.ALIGN_BOTTOM, false),
                object : ClickableSpan() {
                    override fun onClick(widget: View) {
                        val bitmap = (drawable.result as? BitmapDrawable)?.bitmap
                        if (image != null) showImagePreview(widget.context, images, image, bitmap)
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
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.OPAQUE
    }.apply { setBounds(0, 0, intrinsicWidth, intrinsicHeight) }
}
