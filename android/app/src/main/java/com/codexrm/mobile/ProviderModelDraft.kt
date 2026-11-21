package com.codexrm.mobile

import org.json.JSONArray
import org.json.JSONObject

internal class ProviderModelDraft(val providerId: String, models: JSONArray) {
    private val originals = linkedMapOf<String, JSONObject>()
    private val additions = linkedSetOf<String>()
    private val visibility = linkedMapOf<String, Boolean>()

    val hasChanges: Boolean get() = additions.isNotEmpty() || visibility.isNotEmpty()

    init { replaceModels(models) }

    fun replaceModels(models: JSONArray) {
        val previous = originals.toMap()
        originals.clear()
        for (index in 0 until models.length()) {
            val model = models.optJSONObject(index) ?: continue
            val id = model.string("model").ifEmpty { model.string("id") }
            if (id.isNotEmpty()) originals.putIfAbsent(id, model)
        }
        // An edited model can cease to be advertised while the page is open.
        for (id in visibility.keys) previous[id]?.let { originals.putIfAbsent(id, it) }
    }

    fun add(id: String) {
        additions.add(id)
        visibility.remove(id)
    }

    fun setBlacklisted(id: String, blacklisted: Boolean) {
        val original = id !in additions && originals[id]?.optBoolean("blacklisted") == true
        if (original == blacklisted) visibility.remove(id) else visibility[id] = blacklisted
    }

    fun preview(): List<JSONObject> {
        val visible = mutableListOf<JSONObject>()
        val hidden = mutableListOf<JSONObject>()
        for (id in originals.keys + additions) {
            val original = originals[id]
            val blacklisted = visibility[id] ?: (id !in additions && original?.optBoolean("blacklisted") == true)
            val row = when {
                original == null -> JSONObject().put("model", id).put("id", id).put("displayName", id).put("blacklisted", blacklisted)
                original.optBoolean("blacklisted") != blacklisted -> JSONObject(original.toString()).put("blacklisted", blacklisted)
                else -> original
            }
            (if (blacklisted) hidden else visible).add(row)
        }
        return visible + hidden
    }

    fun changes(): JSONArray = JSONArray().apply {
        for (id in additions) put(change(id, "add"))
        for ((id, blacklisted) in visibility) put(change(id, if (blacklisted) "hide" else "restore"))
    }

    private fun change(model: String, action: String) = JSONObject().put("model", model).put("action", action)
}
