package com.codexrm.mobile

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.activity.findViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

internal enum class BackMotion { PAGE, SUBPAGE, SHEET, POPUP }

private class DialogBackCallback(
    private val preview: () -> View?,
    private val motion: () -> BackMotion,
    private val previousPage: () -> View? = { null },
    private val canSlide: () -> Boolean = { true },
    private val onBack: () -> Unit,
) : OnBackPressedCallback(true) {
    private var restore: (() -> Unit)? = null
    private var draw: ((Float) -> Unit)? = null
    private var gestureActive = false
    private var invalidated = false
    private var progress = 0f
    private var completion: ValueAnimator? = null

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
        if (completion != null) finishBack()
        reset()
        gestureActive = true
        val view = preview()?.takeIf { it.isAttachedToWindow } ?: return
        val x = view.translationX
        val kind = motion()
        val direction = if (backEvent.swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        if (kind == BackMotion.PAGE || kind == BackMotion.SUBPAGE) {
            if (!canSlide()) return
            val hidePreviousPage = revealPreviousPage(view)
            restore = {
                view.translationX = x
                hidePreviousPage?.invoke()
            }
            draw = { view.translationX = x + direction * view.width * it }
            handleOnBackProgressed(backEvent)
            return
        }
        val y = view.translationY
        val alpha = view.alpha
        val sheet = kind == BackMotion.SHEET
        val distance = if (sheet) view.height * 0.35f else Ui.dp(view.context, 20).toFloat()
        restore = {
            view.translationX = x
            view.translationY = y
            view.alpha = alpha
        }
        draw = { progress ->
            if (sheet) view.translationY = y + distance * progress
            else view.translationX = x + direction * distance * progress
            view.alpha = alpha * (1f - (if (sheet) 0.15f else 0.55f) * progress)
        }
        handleOnBackProgressed(backEvent)
    }

    private fun revealPreviousPage(view: View): (() -> Unit)? {
        val previous = previousPage()?.takeIf { it.parent == null } ?: return null
        val host = view.parent as? FrameLayout ?: return null
        val accessibility = previous.importantForAccessibility
        val group = previous as? ViewGroup
        val focusability = group?.descendantFocusability
        previous.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        group?.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        val touchShield = View(view.context).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val index = host.indexOfChild(view)
        host.addView(previous, index, FrameLayout.LayoutParams(-1, -1))
        host.addView(touchShield, index + 1, FrameLayout.LayoutParams(-1, -1))
        previous.requestApplyInsets()
        return {
            host.removeView(touchShield)
            host.removeView(previous)
            previous.importantForAccessibility = accessibility
            if (focusability != null) group.descendantFocusability = focusability
        }
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
        if (completion != null) return
        progress = backEvent.progress.coerceIn(0f, 1f)
        draw?.invoke(progress)
    }

    override fun handleOnBackCancelled() = reset()

    override fun handleOnBackPressed() {
        if (completion != null) return
        val frame = draw
        val kind = motion()
        if (frame == null || progress >= 1f || kind == BackMotion.SHEET || kind == BackMotion.POPUP || !canSlide()) {
            finishBack()
            return
        }
        completion = ValueAnimator.ofFloat(progress, 1f).apply {
            duration = (150 * (1f - progress)).toLong().coerceIn(50, 150)
            addUpdateListener { frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finishBack()
            })
        }
        completion?.start()
    }

    private fun finishBack() {
        val commit = !invalidated
        reset()
        if (commit) onBack()
    }

    fun invalidatePreview() {
        completion?.let {
            it.removeAllListeners()
            it.cancel()
            completion = null
            gestureActive = false
        }
        restore?.invoke()
        restore = null
        draw = null
        progress = 0f
        invalidated = gestureActive
    }

    fun reset() {
        gestureActive = false
        invalidatePreview()
    }
}

/** Provides progress for the dialog window while leaving IME back dispatch to Android. */
internal class PredictiveBackDialog(context: Context, theme: Int = 0) : ComponentDialog(context, theme) {
    private var preview: View? = null
    private var motion = BackMotion.PAGE
    private var goBack: () -> Unit = { cancel() }
    private var previousPage: View? = null
    private var canSlide: () -> Boolean = { true }
    private val callback = DialogBackCallback({ preview }, { motion }, { previousPage }, { canSlide() }, { goBack() })

    init { onBackPressedDispatcher.addCallback(callback) }

    fun setBackContent(
        view: View,
        motion: BackMotion = BackMotion.PAGE,
        previousPage: View? = null,
        canSlide: () -> Boolean = { true },
        goBack: () -> Unit = { cancel() },
    ) {
        callback.invalidatePreview()
        preview = view
        this.motion = motion
        this.previousPage = previousPage
        this.canSlide = canSlide
        this.goBack = goBack
        setContentView(view)
    }

    override fun onStop() {
        callback.reset()
        super.onStop()
    }
}

/** Miuix WindowDialog/WindowListPopup create a second window inside the host dialog. */
@Composable
internal fun MiuixPredictiveBack(canDismiss: () -> Boolean = { true }, onBack: () -> Unit) {
    val view = LocalView.current
    val currentBack = rememberUpdatedState(onBack)
    val currentCanDismiss = rememberUpdatedState(canDismiss)
    DisposableEffect(view) {
        val dialogLayout = generateSequence(view) { it.parent as? View }
            .firstOrNull { it is DialogWindowProvider }
        val dispatcher = dialogLayout?.findViewTreeOnBackPressedDispatcherOwner()?.onBackPressedDispatcher
        val callback = dispatcher?.let {
            DialogBackCallback({ dialogLayout.takeIf { currentCanDismiss.value() } }, { BackMotion.POPUP }) {
                if (currentCanDismiss.value()) currentBack.value()
            }.also(dispatcher::addCallback)
        }
        onDispose {
            callback?.reset()
            callback?.remove()
        }
    }
}
