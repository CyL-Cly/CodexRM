package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject

internal data class AsyncQuestion(val id: String, val title: String, val options: List<String>)

internal data class AsyncQuestionReply(val id: String, val question: String, val answer: String)

internal object AsyncQuestions {
    private const val OPEN = "<send_user_message_question_reply>"
    private const val CLOSE = "</send_user_message_question_reply>"

    fun fromItem(item: JSONObject): List<AsyncQuestion> {
        val id = item.string("id")
        if (id.isEmpty() || item.string("type") != "agentMessage" || item.string("delivery") != "async") return emptyList()
        val questions = item.arrayAt("questions")
        return (0 until questions.length()).mapNotNull { index ->
            val question = questions.optJSONObject(index) ?: return@mapNotNull null
            val title = question.opt("title") as? String ?: return@mapNotNull null
            if (title.isBlank()) return@mapNotNull null
            val options = question.arrayAt("options")
            // The ID is a JSON string containing a compact JSON array. Match Codex's escaping.
            val questionId = "[\"request_user_input_async\",${JSONObject.quote(id).replace("\\/", "/")},$index]"
            AsyncQuestion(questionId, title, (0 until options.length()).mapNotNull {
                (options.opt(it) as? String)?.takeIf(String::isNotBlank)
            })
        }
    }

    fun reply(questions: List<AsyncQuestion>, answers: List<String>): String {
        val replies = JSONArray()
        for ((question, answer) in questions.zip(answers)) {
            val title = buildString {
                var size = 0
                val characters = question.title.codePoints().iterator()
                while (characters.hasNext()) {
                    val character = characters.nextInt()
                    val value = String(Character.toChars(character))
                    size += value.toByteArray(Charsets.UTF_8).size
                    if (size > 512) break
                    append(if (value == "\r" || value == "\n") " " else value)
                }
            }
            replies.put(JSONObject().put("questionItemId", question.id).put("question", title).put("answer", answer))
        }
        return "$OPEN\n$replies\n$CLOSE"
    }

    fun parse(text: String): List<AsyncQuestionReply> {
        val trimmed = text.trim()
        val content = if (trimmed.startsWith("# Context from my IDE setup:\n")) {
            trimmed.substringAfterLast("\n## My request for Codex:\n").trim()
        } else trimmed
        if (!content.startsWith(OPEN) || !content.endsWith(CLOSE)) return emptyList()
        val json = content.removePrefix(OPEN).removeSuffix(CLOSE).trim()
        val replies = runCatching {
            if (json.startsWith("[")) JSONArray(json) else JSONArray().put(JSONObject(json))
        }.getOrElse { return emptyList() }
        return (0 until replies.length()).mapNotNull { index ->
            val value = replies.optJSONObject(index) ?: return@mapNotNull null
            AsyncQuestionReply(
                value.opt("questionItemId") as? String ?: return@mapNotNull null,
                value.opt("question") as? String ?: return@mapNotNull null,
                value.opt("answer") as? String ?: return@mapNotNull null,
            )
        }
    }

    fun repliesIn(input: JSONArray): List<AsyncQuestionReply> =
        if (input.length() == 1 && input.optJSONObject(0)?.string("type") == "text") {
            parse(input.getJSONObject(0).string("text"))
        } else emptyList()
}
