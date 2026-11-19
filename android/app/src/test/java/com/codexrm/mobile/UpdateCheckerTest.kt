package com.codexrm.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class UpdateCheckerTest {
    private val validManifest = """
        {
          "versionCode": 4,
          "versionName": "1.0.3",
          "downloadUrl": "updates/CodexRM.apk",
          "releaseNotes": "修复连接问题",
          "fileSize": 123456,
          "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        }
    """.trimIndent()

    @Test fun parsesManifestAndResolvesRelativeDownload() {
        val info = UpdateChecker.parseManifest(validManifest, "https://rm.example/base/android-update.json")
        assertEquals(4L, info.versionCode)
        assertEquals("1.0.3", info.versionName)
        assertEquals("https://rm.example/base/updates/CodexRM.apk", info.downloadUrl)
        assertEquals("修复连接问题", info.releaseNotes)
        assertEquals(123456L, info.fileSize)
    }

    @Test fun historyShowsOnlyAndroidVersionsAndKeepsMultilineNotes() {
        val releases = UpdateChecker.parseHistory("""
            {
              "schemaVersion": 1,
              "releases": [
                {"platform":"android","versionName":"1.0.25","releaseNotes":"修复断线\n支持分页"},
                {"platform":"linux","versionName":"0.6.19","releaseNotes":"Agent"},
                {"platform":"android","versionName":"1.0.24","releaseNotes":"改善缓存"}
              ]
            }
        """.trimIndent())
        assertEquals(listOf("1.0.25", "1.0.24"), releases.map { it.versionName })
        assertEquals("修复断线\n支持分页", releases.first().notes)
    }

    @Test fun retainsCloudreveQueryString() {
        val manifest = validManifest.replace(
            "updates/CodexRM.apk",
            "https://cloud.example/f/permanent/CodexRM.apk?key=public",
        )
        val info = UpdateChecker.parseManifest(manifest)
        assertEquals("https://cloud.example/f/permanent/CodexRM.apk?key=public", info.downloadUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpDownload() {
        UpdateChecker.parseManifest(validManifest.replace("updates/CodexRM.apk", "file:///tmp/update.apk"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialsInDownload() {
        UpdateChecker.parseManifest(
            validManifest.replace("updates/CodexRM.apk", "https://user:secret@cloud.example/update.apk"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSchemeRelativeDownload() {
        UpdateChecker.parseManifest(validManifest.replace("updates/CodexRM.apk", "//cloud.example/update.apk"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSha256() {
        UpdateChecker.parseManifest(validManifest.replace("0123456789abcdef", "not-a-sha256-value"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPackage() {
        UpdateChecker.parseManifest(validManifest.replace("123456", (UpdateChecker.MAX_PACKAGE_SIZE + 1L).toString()))
    }

    @Test fun silentInstallFlagIsLimitedToAndroid12AndNewer() {
        assertEquals(false, UpdateChecker.shouldUseSilentInstallFlag(28))
        assertEquals(false, UpdateChecker.shouldUseSilentInstallFlag(29))
        assertEquals(false, UpdateChecker.shouldUseSilentInstallFlag(30))
        assertEquals(true, UpdateChecker.shouldUseSilentInstallFlag(31))
        assertEquals(true, UpdateChecker.shouldUseSilentInstallFlag(35))
    }

    @Test fun hashesFilesAsLowercaseHex() {
        val file = File.createTempFile("codexrm-update", ".bin")
        try {
            file.writeBytes("CodexRM update".toByteArray())
            val expected = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, UpdateChecker.sha256Hex(file))
        } finally {
            file.delete()
        }
    }
}
