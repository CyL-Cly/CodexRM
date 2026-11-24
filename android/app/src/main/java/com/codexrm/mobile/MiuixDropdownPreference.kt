package com.codexrm.mobile

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup

@Composable
internal fun MiuixDropdownPreference(
    title: String,
    items: List<String>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var heldDown by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val close = { expanded = false }
    val actionColor = if (items.isNotEmpty()) MiuixTheme.colorScheme.onSurfaceVariantActions
        else MiuixTheme.colorScheme.disabledOnSecondaryVariant
    BasicComponent(
        title = title,
        role = Role.DropdownList,
        holdDownState = heldDown,
        enabled = items.isNotEmpty(),
        onClick = {
            expanded = !expanded
            if (expanded) {
                heldDown = true
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        },
        endActions = {
            items.getOrNull(selectedIndex)?.let { selected ->
                Text(
                    text = selected,
                    modifier = Modifier.padding(end = 8.dp),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = actionColor,
                    textAlign = TextAlign.End,
                )
            }
            DropdownArrowEndAction(actionColor = actionColor)
            WindowListPopup(
                show = expanded,
                alignment = PopupPositionProvider.Align.End,
                onDismissRequest = close,
                onDismissFinished = { heldDown = false },
            ) {
                MiuixPredictiveBack(onBack = close)
                ListPopupColumn {
                    items.forEachIndexed { index, item ->
                        DropdownImpl(text = item, optionSize = items.size, isSelected = index == selectedIndex, index = index) { selected ->
                            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            onSelectedIndexChange(selected)
                            close()
                        }
                    }
                }
            }
        },
    )
}
