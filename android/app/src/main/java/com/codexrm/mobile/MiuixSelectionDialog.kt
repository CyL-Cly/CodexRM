package com.codexrm.mobile

import android.app.Dialog
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun showMiuixSelectionDialog(
    context: Context,
    title: String,
    summary: String? = null,
    content: @Composable (close: () -> Unit) -> Unit,
): Dialog = MiuixDialogHost(context).apply {
    setContent(title = { title }, summary = summary, content = content)
    show()
}

@Composable
internal fun MiuixModelChoices(
    options: List<ModelOption>,
    selected: ModelOption?,
    onClose: () -> Unit,
    onSelect: (ModelPickerRow) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val rows = remember(options, query) { ModelPicker.rows(options, query) }
    Column {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索模型或供应商",
            useLabelAsPlaceholder = true,
            singleLine = true,
            textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSecondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        )
        val emptyResult = rows.singleOrNull()?.takeIf { it.emptyChoice }
        if (emptyResult != null) {
            Text(emptyResult.title, Modifier.padding(top = 12.dp), color = MiuixTheme.colorScheme.onSurfaceSecondary)
            TextButton("清除搜索", { query = "" }, Modifier.fillMaxWidth().padding(top = 8.dp))
        } else {
            LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 420.dp)) {
                items(rows) { row ->
                    if (row.header) {
                        SmallTitle(row.title, modifier = Modifier.semantics { heading() })
                    } else {
                        val modelId = row.option?.model?.takeIf { it != row.title }
                        val summary = listOfNotNull(modelId, row.notes.takeIf { it.isNotBlank() }).joinToString("\n")
                        RadioButtonPreference(
                            title = row.title,
                            summary = summary.ifEmpty { null },
                            selected = if (row.defaultChoice) selected == null else row.option == selected,
                            onClick = { onSelect(row) },
                        )
                    }
                }
            }
        }
        TextButton("关闭", onClose, Modifier.fillMaxWidth().padding(top = 8.dp))
    }
}

@Composable
internal fun MiuixWorkspaceChoices(
    initialSandbox: Int,
    initialApproval: Int,
    onClose: () -> Unit,
    onSave: (Int, Int) -> Unit,
) {
    var sandbox by remember { mutableIntStateOf(initialSandbox) }
    var approval by remember { mutableIntStateOf(initialApproval) }
    Column {
        MiuixDropdownPreference(
            title = "文件访问",
            items = listOf("允许修改工作目录", "只读", "完整访问"),
            selectedIndex = sandbox,
            onSelectedIndexChange = { sandbox = it },
        )
        MiuixDropdownPreference(
            title = "审批策略",
            items = listOf("需要时请求审批", "不受信任命令需审批", "从不请求审批"),
            selectedIndex = approval,
            onSelectedIndexChange = { approval = it },
        )
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", onClose, Modifier.weight(1f))
            TextButton("保存设置", { onSave(sandbox, approval) }, Modifier.weight(1f), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
}

@Composable
internal fun MiuixShareTargets(threads: List<ThreadSummary>, select: (ThreadSummary?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(threads, query) {
        val filter = query.trim()
        threads.filter { it.title.contains(filter, ignoreCase = true) || it.cwd.contains(filter, ignoreCase = true) }
    }
    Column {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = "搜索对话",
            useLabelAsPlaceholder = true,
            singleLine = true,
            textStyle = MiuixTheme.textStyles.main.copy(color = MiuixTheme.colorScheme.onSecondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 350.dp)) {
            item { top.yukonga.miuix.kmp.preference.ArrowPreference(title = "新对话", onClick = { select(null) }) }
            items(results, key = { it.id }) { thread ->
                top.yukonga.miuix.kmp.preference.ArrowPreference(
                    title = thread.title,
                    summary = thread.cwd.takeIf(String::isNotBlank),
                    onClick = { select(thread) },
                )
            }
        }
    }
}
