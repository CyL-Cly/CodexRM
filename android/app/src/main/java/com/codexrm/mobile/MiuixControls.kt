package com.codexrm.mobile

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

@Composable
internal fun CodexMiuixTheme(content: @Composable () -> Unit) {
    MiuixTheme(colors = if (Ui.dark) darkColorScheme() else lightColorScheme(), content = content)
}

internal class MiuixButtonView(
    context: Context,
    label: String,
    private val primary: Boolean = false,
    private val compact: Boolean = false,
) : AbstractComposeView(context) {
    var text by mutableStateOf(label)
    private var enabledState by mutableStateOf(true)

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState = enabled
    }

    @Composable
    override fun Content() = CodexMiuixTheme {
        Button(
            onClick = { performClick() },
            modifier = if (compact) Modifier.widthIn(max = 96.dp) else Modifier,
            enabled = enabledState,
            colors = if (primary) ButtonDefaults.buttonColorsPrimary() else ButtonDefaults.buttonColors(),
            minWidth = 0.dp,
            minHeight = 48.dp,
            insideMargin = PaddingValues(horizontal = if (compact) 8.dp else 10.dp, vertical = 6.dp),
        ) {
            val style = MiuixTheme.textStyles.button
            Text(
                text = text,
                style = if (compact) style.copy(fontSize = 14.sp) else style,
                maxLines = if (compact) 1 else Int.MAX_VALUE,
                overflow = if (compact) TextOverflow.Ellipsis else TextOverflow.Clip,
            )
        }
    }
}

internal class MiuixProgressView(
    context: Context,
    private val linear: Boolean = false,
    private val maximum: Int = 100,
    private val diameter: Int = 28,
) : AbstractComposeView(context) {
    var isIndeterminate by mutableStateOf(!linear)
    var progress by mutableIntStateOf(0)

    @Composable
    override fun Content() = CodexMiuixTheme {
        val fraction = if (isIndeterminate) null else (progress.toFloat() / maximum).coerceIn(0f, 1f)
        if (linear) LinearProgressIndicator(Modifier.fillMaxWidth(), progress = fraction)
        else CircularProgressIndicator(progress = fraction, size = diameter.dp)
    }
}

internal class MiuixCheckboxView(context: Context, private val label: String) : AbstractComposeView(context) {
    var checked by mutableStateOf(false)
        private set
    var onChanged: () -> Unit = {}

    @Composable
    override fun Content() = CodexMiuixTheme {
        CheckboxPreference(title = label, checked = checked, onCheckedChange = {
            checked = it
            onChanged()
        })
    }
}

internal class MiuixChoicesView(
    context: Context,
    private val labels: List<String>,
    private val dropdown: Boolean = false,
    private val onSelected: () -> Unit = {},
) : AbstractComposeView(context) {
    private var selected by mutableIntStateOf(0)
    val value: String get() = labels.getOrNull(selected).orEmpty()

    @Composable
    override fun Content() = CodexMiuixTheme {
        if (dropdown) {
            MiuixDropdownPreference(title = "选择回答", items = labels, selectedIndex = selected, onSelectedIndexChange = ::select)
        } else {
            Column {
                labels.forEachIndexed { index, label ->
                    RadioButtonPreference(title = label, selected = selected == index, onClick = { select(index) })
                }
            }
        }
    }

    private fun select(index: Int) {
        selected = index
        onSelected()
    }
}

internal class MiuixFieldView(
    context: Context,
    private val label: String,
    value: String = "",
    private val keyboardType: KeyboardType = KeyboardType.Text,
    private val secret: Boolean = false,
    private val supportingText: String? = null,
) : AbstractComposeView(context) {
    private var fieldValue by mutableStateOf(TextFieldValue(value, TextRange(value.length)))
    var text: String
        get() = fieldValue.text
        set(value) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
            error = null
        }
    var error by mutableStateOf<String?>(null)
    private var enabledState by mutableStateOf(true)
    private var focusRequest by mutableIntStateOf(0)

    init {
        if (secret) importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    fun focusInput() { focusRequest++ }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        enabledState = enabled
    }

    @Composable
    override fun Content() = CodexMiuixTheme {
        val focus = remember { FocusRequester() }
        LaunchedEffect(focusRequest) {
            if (focusRequest > 0 && enabledState) focus.requestFocus()
        }
        Column {
            val validation = error
            TextField(
                value = fieldValue,
                onValueChange = { fieldValue = it; error = null },
                label = label,
                enabled = enabledState,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                borderColor = if (validation != null) androidx.compose.ui.graphics.Color(Ui.DANGER) else MiuixTheme.colorScheme.primary,
                textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSecondaryContainer),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics {
                    validation?.let { error(it) }
                },
            )
            (validation ?: supportingText)?.let {
                Text(
                    text = it,
                    modifier = Modifier.padding(start = 6.dp, top = 4.dp),
                    color = if (validation != null) androidx.compose.ui.graphics.Color(Ui.DANGER) else MiuixTheme.colorScheme.onSurfaceSecondary,
                )
            }
        }
    }
}

@Composable
internal fun MiuixActionIcon(icon: String, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
        minWidth = 48.dp,
        minHeight = 48.dp,
    ) {
        val color = Ui.MUTED
        AndroidView(
            factory = { IconView(it, icon, color) },
            update = { it.setColor(color) },
            modifier = Modifier.size(20.dp),
        )
    }
}

internal fun showMiuixMenu(anchor: View, actions: Map<String, () -> Unit>): MiuixDialogHost =
    MiuixDialogHost(anchor.context).apply {
        setPopup(anchor) { close ->
            actions.forEach { (label, action) ->
                BasicComponent(title = label, onClick = { close(); action() })
            }
        }
        show()
    }
