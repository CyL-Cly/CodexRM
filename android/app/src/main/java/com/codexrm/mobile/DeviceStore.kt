package com.codexrm.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class PendingDeviceRevocation(val serverUrl: String, val token: String) {
    fun toJson(): JSONObject = JSONObject().put("serverUrl", serverUrl).put("token", token)

    companion object {
        fun fromJson(value: JSONObject): PendingDeviceRevocation? {
            val serverUrl = value.string("serverUrl")
            val token = value.string("token")
            return if (serverUrl.isBlank() || token.isBlank()) null else PendingDeviceRevocation(serverUrl, token)
        }
    }
}

internal class DeviceStore(context: Context) {
    private val appContext = context.applicationContext
    private val securePrefs = appContext.getSharedPreferences("secure_devices", Context.MODE_PRIVATE)
    private val uiPrefs = appContext.getSharedPreferences("ui_state", Context.MODE_PRIVATE)

    var selectedId: String
        get() = uiPrefs.getString("selected", "") ?: ""
        set(value) = uiPrefs.edit().putString("selected", value).apply()

    var lastServer: String
        get() = uiPrefs.getString("last_server", "https://codexrm.dagongren.tech") ?: ""
        set(value) = uiPrefs.edit().putString("last_server", value).apply()

    var sandbox: String
        get() = uiPrefs.getString("sandbox", null) ?: "danger-full-access"
        set(value) = uiPrefs.edit().putString("sandbox", value).apply()

    var approvalPolicy: String
        get() = uiPrefs.getString("approval_policy", null) ?: "never"
        set(value) = uiPrefs.edit().putString("approval_policy", value).apply()

    var shareToNewDraft: Boolean
        get() = uiPrefs.getBoolean("share_to_new_draft", false)
        set(value) = uiPrefs.edit().putBoolean("share_to_new_draft", value).apply()

    var darkTheme: Boolean
        get() = uiPrefs.getBoolean("dark_theme", true)
        set(value) = uiPrefs.edit().putBoolean("dark_theme", value).apply()

    var legacySystemWarningShown: Boolean
        get() = uiPrefs.getBoolean("legacy_system_warning_shown", false)
        set(value) = uiPrefs.edit().putBoolean("legacy_system_warning_shown", value).apply()

    fun selectedWorkspace(agentId: String): String = uiPrefs.getString("workspace.$agentId", "") ?: ""
    fun selectWorkspace(agentId: String, path: String) = uiPrefs.edit().putString("workspace.$agentId", path).apply()

    fun collapsed(agentId: String): MutableSet<String> =
        uiPrefs.getStringSet("collapsed.$agentId", emptySet())?.toMutableSet() ?: mutableSetOf()
    fun saveCollapsed(agentId: String, paths: Set<String>) {
        uiPrefs.edit().putStringSet("collapsed.$agentId", HashSet(paths)).apply()
    }

    fun modelPreference(agentId: String, cwd: String): Triple<String, String, String> {
        val key = "model.$agentId.$cwd"
        return Triple(
            uiPrefs.getString("$key.provider", "") ?: "",
            uiPrefs.getString("$key.model", "") ?: "",
            uiPrefs.getString("$key.effort", "") ?: "",
        )
    }
    fun saveModelPreference(agentId: String, cwd: String, provider: String, model: String, effort: String) {
        val key = "model.$agentId.$cwd"
        uiPrefs.edit().putString("$key.provider", provider).putString("$key.model", model).putString("$key.effort", effort).apply()
    }

    fun load(): MutableList<Device> {
        val encrypted = securePrefs.getString("devices", null) ?: return mutableListOf()
        return try {
            val array = JSONArray(Crypto.decrypt(encrypted))
            val result = ArrayList<Device>(array.length())
            for (index in 0 until array.length()) {
                val device = Device.fromJson(array.getJSONObject(index))
                if (device.token.isNotEmpty() && device.agentId.isNotEmpty()) result += device
            }
            result
        } catch (_: Exception) {
            // Keystore material and preferences must belong to the same installation.
            securePrefs.edit().remove("devices").apply()
            mutableListOf()
        }
    }

    fun save(devices: List<Device>) {
        securePrefs.edit().putString("devices", encodeDevices(devices)).apply()
    }

    private fun encodeDevices(devices: List<Device>): String =
        Crypto.encrypt(JSONArray(devices.map(Device::toJson)).toString())

    // Like the device list, the revocation queue is accessed only on the main thread.
    fun saveRemoval(devices: List<Device>, removed: Device) {
        val pending = PendingDeviceRevocation(removed.serverUrl, removed.token)
        // Remove the pairing and retain only its relay URL and old token in the same transaction.
        securePrefs.edit().putString("devices", encodeDevices(devices))
            .putString("pending_revocations", encodeRevocations((pendingRevocations() + pending).distinct()))
            .apply()
    }

    fun pendingRevocations(): List<PendingDeviceRevocation> {
        val encrypted = securePrefs.getString("pending_revocations", null) ?: return emptyList()
        return try {
            val array = JSONArray(Crypto.decrypt(encrypted))
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(PendingDeviceRevocation::fromJson) }
        } catch (_: Exception) {
            securePrefs.edit().remove("pending_revocations").apply()
            emptyList()
        }
    }

    fun finishRevocation(pending: PendingDeviceRevocation, failed: Boolean) {
        val remaining = pendingRevocations() - pending
        val edit = securePrefs.edit()
        if (remaining.isEmpty()) edit.remove("pending_revocations")
        else edit.putString("pending_revocations", encodeRevocations(remaining))
        if (failed) edit.putBoolean("revocation_failed", true)
        edit.apply()
    }

    fun consumeRevocationFailure(): Boolean {
        if (!securePrefs.getBoolean("revocation_failed", false)) return false
        securePrefs.edit().remove("revocation_failed").apply()
        return true
    }

    private fun encodeRevocations(items: List<PendingDeviceRevocation>): String =
        Crypto.encrypt(JSONArray(items.map(PendingDeviceRevocation::toJson)).toString())

    fun draft(agentId: String, threadId: String, workspaceKey: String? = null): String {
        val legacyKey = "draft.$agentId.$threadId"
        if (threadId.isNotEmpty() || workspaceKey == null) return uiPrefs.getString(legacyKey, "") ?: ""
        // New-session drafts belong to a cwd just like their attachments. Read the
        // former unscoped value once so an upgrade does not discard an existing draft.
        val scopedKey = "draft.$agentId..workspace.$workspaceKey"
        return uiPrefs.getString(scopedKey, null) ?: uiPrefs.getString(legacyKey, "") ?: ""
    }

    fun saveDraft(agentId: String, threadId: String, text: String, workspaceKey: String? = null) {
        if (agentId.isEmpty()) return
        val editor = uiPrefs.edit()
        val legacyKey = "draft.$agentId.$threadId"
        val key = if (threadId.isEmpty() && workspaceKey != null) {
            "draft.$agentId..workspace.$workspaceKey"
        } else legacyKey
        if (text.isEmpty()) editor.remove(key) else editor.putString(key, text)
        if (key != legacyKey) editor.remove(legacyKey)
        editor.apply()
    }

    fun pendingSends(agentId: String): List<PendingSend> {
        if (agentId.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(uiPrefs.getString("outbox.$agentId", "[]"))
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(PendingSend::fromJson)
            }
        } catch (_: Exception) {
            // Keep malformed data for diagnosis; an unreadable record must not trigger a resend.
            emptyList()
        }
    }

    fun savePendingSends(agentId: String, values: List<PendingSend>): Boolean {
        if (agentId.isEmpty()) return true
        val editor = uiPrefs.edit()
        if (values.isEmpty()) editor.remove("outbox.$agentId")
        else editor.putString("outbox.$agentId", JSONArray(values.map(PendingSend::toJson)).toString())
        // The outbox is the durability boundary before the visible draft is cleared.
        return editor.commit()
    }

    private object Crypto {
        private const val KEY_ALIAS = "codexrm.device.tokens.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        @Synchronized
        fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val payload = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(cipher.iv + payload, Base64.NO_WRAP)
        }

        @Synchronized
        fun decrypt(encoded: String): String {
            val payload = Base64.decode(encoded, Base64.NO_WRAP)
            require(payload.size > 12) { "Encrypted payload is truncated" }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload, 0, 12))
            return String(cipher.doFinal(payload, 12, payload.size - 12), Charsets.UTF_8)
        }

        private fun key(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
