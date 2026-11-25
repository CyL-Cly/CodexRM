package com.codexrm.mobile

internal enum class HomeAction { START_INPUT, CHOOSE_WORKSPACE, CHOOSE_DEVICE, RETRY_CONNECTION, RETRY_SYNC }

internal data class HomeGuidance(
    val description: String,
    val primaryAction: HomeAction,
    val primaryLabel: String,
    val secondaryAction: HomeAction,
    val secondaryLabel: String,
    val connectionActions: Boolean = false,
)

internal data class SendUiState(
    val hasPayload: Boolean,
    val online: Boolean,
    val loaded: Boolean,
    val sending: Boolean,
    val loading: Boolean,
    val preparingAttachments: Boolean,
    val readOnly: Boolean,
    val editingQueuedMessage: Boolean,
    val running: Boolean,
    val messageQueue: Boolean,
    val hasAttachments: Boolean,
    val attachmentsSupported: Boolean,
)

internal object InteractionCopy {
    fun home(online: Boolean, syncing: Boolean, cwd: String): HomeGuidance = when {
        !online -> HomeGuidance(
            "设备未连接。选择工作设备，或重试当前连接。",
            HomeAction.CHOOSE_DEVICE, "选择设备",
            HomeAction.RETRY_CONNECTION, "重试连接",
            connectionActions = true,
        )
        syncing -> HomeGuidance(
            "正在同步设备信息、模型和会话，完成后即可开始。",
            HomeAction.CHOOSE_DEVICE, "选择设备",
            HomeAction.RETRY_SYNC, "重新同步",
            connectionActions = true,
        )
        cwd.isBlank() -> HomeGuidance(
            "设备已连接。先选择电脑上的工作目录，也可以直接使用默认目录。",
            HomeAction.CHOOSE_WORKSPACE, "选择工作目录",
            HomeAction.START_INPUT, "使用默认目录开始输入",
        )
        else -> HomeGuidance(
            "设备已就绪。描述任务，Codex 会在所选工作目录中处理。",
            HomeAction.START_INPUT, "开始输入",
            HomeAction.CHOOSE_WORKSPACE, "切换工作目录",
        )
    }

    fun sendBlockReason(state: SendUiState, steer: Boolean = false): String? = when {
        state.readOnly -> "此会话为只读，请新建会话后继续。"
        state.sending -> "正在等待设备确认发送，请稍候。"
        state.loading -> "正在打开或同步会话，请稍候。"
        state.preparingAttachments -> "正在读取附件，完成后即可发送。"
        !state.online -> "设备离线，重新连接后可发送。"
        !state.loaded -> "正在同步设备信息，请稍候。"
        state.editingQueuedMessage -> "正在恢复待发送消息，请稍候。"
        state.hasAttachments && !state.attachmentsSupported -> "当前电脑 Agent 不支持附件，请更新后重试。"
        state.running && !steer && !state.messageQueue -> "当前 Agent 不支持排队。可立即发送，或停止任务后发送。"
        !state.hasPayload -> "输入消息或添加附件后即可发送。"
        else -> null
    }

    fun modelBlockReason(online: Boolean, readOnly: Boolean, running: Boolean, sending: Boolean, loading: Boolean): String? = when {
        readOnly -> "此会话为只读，不能切换模型。"
        running -> "任务运行中，停止后可切换模型。"
        sending -> "正在发送消息，完成后可切换模型。"
        loading -> "正在打开或同步会话，完成后可切换模型。"
        !online -> "设备离线，重新连接后可切换模型。"
        else -> null
    }

    fun modelSaveError(message: String?): String = when {
        message?.contains("未知的供应商设置操作") == true ->
            "电脑 Agent 版本过旧，不支持批量保存模型改动。请先在“工作区设置”中检查并更新电脑 Agent，再重试。改动已保留。"
        else -> message ?: "保存模型改动失败"
    }

    fun composerModelLabel(displayName: String?): String {
        val name = displayName.orEmpty().trim()
        val shortName = if (name.startsWith("gpt-", ignoreCase = true)) name.drop(4) else name
        return shortName.ifBlank { "选择模型" }
    }

    // 请勿翻译模型和思考强度名
    fun effortLabel(value: String): String = value.ifEmpty { "默认" }
}
