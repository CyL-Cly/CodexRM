package com.codexrm.mobile

import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.window.WindowListPopup

/** Supplies a lifecycle for Compose dialog content while the activity uses Views. */
internal class MiuixDialogHost(context: Context) : ComponentDialog(context) {
    private var dismissible = true
    private val owner = context as? LifecycleOwner
    private val ownerObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { dismiss() }
    }


    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        }
    }

    override fun setCancelable(flag: Boolean) {
        dismissible = flag
        super.setCancelable(flag)
    }

    fun setMiuixContent(content: @Composable () -> Unit) {
        setContentView(ComposeView(context).apply {
            setContent { CodexMiuixTheme(content) }
        })
    }

    fun setContent(
        title: @Composable () -> String?,
        summary: String? = null,
        content: @Composable (close: () -> Unit) -> Unit,
    ) {
        setMiuixContent {
            WindowDialog(
                show = true,
                title = title(),
                summary = summary,
                onDismissRequest = { if (dismissible) cancel() },
            ) {
                MiuixPredictiveBack(canDismiss = { dismissible }, onBack = ::cancel)
                content(::dismiss)
            }
        }
    }

    fun setPopup(anchor: View, content: @Composable (close: () -> Unit) -> Unit) {
        val location = IntArray(2).also(anchor::getLocationInWindow)
        val bounds = IntRect(location[0], location[1], location[0] + anchor.width, location[1] + anchor.height)
        val dropdown = ListPopupDefaults.DropdownPositionProvider
        val position = object : PopupPositionProvider by dropdown {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowBounds: IntRect,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
                popupMargin: IntRect,
                alignment: PopupPositionProvider.Align,
            ): IntOffset = dropdown.calculatePosition(bounds, windowBounds, layoutDirection, popupContentSize, popupMargin, alignment)
        }
        setMiuixContent {
            Box(Modifier.fillMaxSize()) {
                WindowListPopup(
                    show = true,
                    popupPositionProvider = position,
                    alignment = PopupPositionProvider.Align.End,
                    enableWindowDim = false,
                    onDismissRequest = { cancel() },
                    maxHeight = 360.dp,
                ) {
                    MiuixPredictiveBack(onBack = ::cancel)
                    // BasicComponent rows need a bounded width. ListPopupColumn's intrinsic
                    // measurement can pass a negative text width to rows with end actions.
                    Column(Modifier.width(248.dp).verticalScroll(rememberScrollState())) {
                        content(::dismiss)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        owner?.lifecycle?.addObserver(ownerObserver)
    }

    override fun onStop() {
        owner?.lifecycle?.removeObserver(ownerObserver)
        super.onStop()
    }

    override fun show() {
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
}

/** A stateful View-to-Compose bridge for dialogs with existing asynchronous controllers. */
internal class MiuixDialog private constructor(
    private val host: MiuixDialogHost,
    title: CharSequence?,
    message: CharSequence?,
    internal val contentView: View?,
    private val composeBody: (@Composable () -> Unit)?,
    private val choices: List<CharSequence>,
    private val choiceListener: DialogInterface.OnClickListener?,
    private val choiceLongPress: Pair<String, DialogInterface.OnClickListener>?,
    selectedIndex: Int?,
    private val buttons: Map<Int, Action>,
) : DialogInterface by host {
    private var title by mutableStateOf(title?.toString())
    private var message by mutableStateOf(message?.toString())
    private var selectedIndex by mutableStateOf(selectedIndex)

    val isShowing: Boolean get() = host.isShowing
    fun setTitle(value: CharSequence?) { title = value?.toString() }
    fun setMessage(value: CharSequence?) { message = value?.toString() }
    fun setCancelable(value: Boolean) = host.setCancelable(value)
    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) = host.setOnDismissListener(listener)
    fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) = host.setOnCancelListener(listener)
    fun getButton(which: Int): Action = buttons.getValue(which)
    fun show(): MiuixDialog = apply { host.show() }

    init {
        host.setContent(title = { this.title }) { DialogContents() }
    }

    @Composable
    private fun DialogContents() {
        Column {
            message?.let {
                Text(
                    text = it,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                )
            }
            if (contentView != null || composeBody != null || choices.isNotEmpty()) {
                Column(Modifier.weight(1f, fill = false)) {
                    contentView?.let { view -> AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth()) }
                    composeBody?.invoke()
                    if (choices.isNotEmpty()) {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                            itemsIndexed(choices) { index, label ->
                                val select = {
                                    if (selectedIndex == null) dismiss() else selectedIndex = index
                                    choiceListener?.onClick(this@MiuixDialog, index)
                                    Unit
                                }
                                val longPress = choiceLongPress
                                when {
                                    longPress != null -> BasicComponent(
                                        title = label.toString(),
                                        modifier = Modifier.combinedClickable(
                                            onClick = select,
                                            onLongClickLabel = longPress.first,
                                            onLongClick = {
                                                dismiss()
                                                longPress.second.onClick(this@MiuixDialog, index)
                                            },
                                        ),
                                    )
                                    selectedIndex == null -> BasicComponent(title = label.toString(), onClick = select)
                                    else -> RadioButtonPreference(title = label.toString(), selected = index == selectedIndex, onClick = select)
                                }
                            }
                        }
                    }
                }
            }
            val visibleButtons = listOfNotNull(buttons[NEUTRAL], buttons[NEGATIVE], buttons[POSITIVE]).filter { it.visible }
            if (visibleButtons.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleButtons.forEach { action ->
                        TextButton(
                            text = action.label,
                            onClick = { action.performClick() },
                            modifier = Modifier.weight(1f),
                            enabled = action.isEnabled,
                            colors = if (action === buttons[POSITIVE]) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
                            insideMargin = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        }
    }

    internal class Action(val label: String, private var onClick: () -> Unit) {
        var isEnabled by mutableStateOf(true)
        var visible by mutableStateOf(true)

        fun setOnClickListener(listener: () -> Unit) { onClick = listener }
        fun performClick(): Boolean {
            if (!isEnabled || !visible) return false
            onClick()
            return true
        }
    }

    internal class Builder(private val context: Context) {
        private var title: CharSequence? = null
        private var message: CharSequence? = null
        private var body: View? = null
        private var composeBody: (@Composable () -> Unit)? = null
        private var cancelable = true
        private var choices = emptyList<CharSequence>()
        private var choiceListener: DialogInterface.OnClickListener? = null
        private var selected: Int? = null
        private var choiceLongPress: Pair<String, DialogInterface.OnClickListener>? = null
        private val actions = linkedMapOf<Int, Pair<String, DialogInterface.OnClickListener?>>()

        fun setTitle(value: CharSequence?) = apply { title = value }
        fun setMessage(value: CharSequence?) = apply { message = value }
        fun setView(value: View) = apply { body = value; composeBody = null }
        fun setContent(value: @Composable () -> Unit) = apply { composeBody = value; body = null }
        fun setCancelable(value: Boolean) = apply { cancelable = value }
        fun setItems(items: Array<out CharSequence>, listener: DialogInterface.OnClickListener) = apply {
            choices = items.toList()
            choiceListener = listener
            selected = null
        }

        fun setItemLongPress(label: String, listener: DialogInterface.OnClickListener) = apply {
            choiceLongPress = label to listener
        }

        fun setSingleChoiceItems(items: Array<out CharSequence>, index: Int, listener: DialogInterface.OnClickListener) = apply {
            setItems(items, listener)
            selected = index
        }

        fun setPositiveButton(label: String, listener: DialogInterface.OnClickListener?) = apply { actions[POSITIVE] = label to listener }
        fun setNegativeButton(label: String, listener: DialogInterface.OnClickListener?) = apply { actions[NEGATIVE] = label to listener }
        fun setNeutralButton(label: String, listener: DialogInterface.OnClickListener?) = apply { actions[NEUTRAL] = label to listener }

        fun create(): MiuixDialog {
            val host = MiuixDialogHost(context).apply { setCancelable(cancelable) }
            lateinit var dialog: MiuixDialog
            val buttons = actions.mapValues { (which, action) ->
                Action(action.first) {
                    dialog.dismiss()
                    action.second?.onClick(dialog, which)
                }
            }
            dialog = MiuixDialog(host, title, message, body, composeBody, choices, choiceListener, choiceLongPress, selected, buttons)
            return dialog
        }

        fun show(): MiuixDialog = create().show()
    }

    companion object {
        const val POSITIVE = DialogInterface.BUTTON_POSITIVE
        const val NEGATIVE = DialogInterface.BUTTON_NEGATIVE
        const val NEUTRAL = DialogInterface.BUTTON_NEUTRAL
    }
}
