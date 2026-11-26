package com.codexrm.mobile

import android.content.pm.PackageInstaller
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

    private val manifestUrl = "https://updates.example/base/android-update.json"

    @Test fun updateAvailabilityFollowsTheBuildConfiguration() {
        assertEquals(
            UpdateChecker.updateEndpoint(BuildConfig.DEFAULT_UPDATE_SERVER, "android-update.json") != null,
            UpdateChecker.isAvailable,
        )
    }

    @Test fun acceptsOnlyAnExplicitHttpsUpdateServer() {
        assertEquals("", UpdateChecker.normalizeUpdateServer("  "))
        assertEquals(null, UpdateChecker.updateEndpoint("", "android-update.json"))
        assertEquals(manifestUrl, UpdateChecker.updateEndpoint(" https://updates.example/base/ ", "android-update.json"))
        assertEquals("https://updates.example/base/release-history.json",
            UpdateChecker.updateEndpoint("https://updates.example/base", "release-history.json"))
        for (invalid in listOf("http://updates.example", "https:updates.example", "https://user@updates.example",
            "https://@updates.example", "https://updates.example?x=1", "https://updates.example#fragment")) {
            assertEquals(null, UpdateChecker.normalizeUpdateServer(invalid))
        }
    }

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
        val info = UpdateChecker.parseManifest(manifest, manifestUrl)
        assertEquals("https://cloud.example/f/permanent/CodexRM.apk?key=public", info.downloadUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonHttpDownload() {
        UpdateChecker.parseManifest(validManifest.replace("updates/CodexRM.apk", "file:///tmp/update.apk"), manifestUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsCredentialsInDownload() {
        UpdateChecker.parseManifest(
            validManifest.replace("updates/CodexRM.apk", "https://user:secret@cloud.example/update.apk"),
            manifestUrl,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSchemeRelativeDownload() {
        UpdateChecker.parseManifest(validManifest.replace("updates/CodexRM.apk", "//cloud.example/update.apk"), manifestUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidSha256() {
        UpdateChecker.parseManifest(validManifest.replace("0123456789abcdef", "not-a-sha256-value"), manifestUrl)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOversizedPackage() {
        UpdateChecker.parseManifest(validManifest.replace("123456", (UpdateChecker.MAX_PACKAGE_SIZE + 1L).toString()), manifestUrl)
    }

    @Test fun runtimeAndAppUseSharedManifestValidationWithSeparateTargets() {
        val runtime = UpdateChecker.parseManifest(validManifest, manifestUrl, UpdateComponent.RUNTIME)
        assertEquals(LocalCodex.PACKAGE, runtime.component.packageName("com.codexrm.mobile"))
        assertEquals("runtime", runtime.component.cacheKey)
        val app = UpdateChecker.parseManifest(validManifest, manifestUrl)
        assertEquals("com.codexrm.mobile", app.component.packageName("com.codexrm.mobile"))
        assertEquals("app", app.component.cacheKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttpDowngradeForRuntime() {
        UpdateChecker.parseManifest(validManifest.replace("updates/CodexRM.apk", "http://cloud.example/runtime.apk"),
            manifestUrl, UpdateComponent.RUNTIME)
    }

    @Test fun runtimePackageMustContainAllFourNonEmptyArm64Components() {
        val apk = File.createTempFile("runtime-check", ".apk")
        val names = listOf("libcodex_android.so", "libcodexrm_local_agent.so", "libssl_3.so", "libcrypto_3.so")
        fun writePackage(emptyFile: String? = null, missingFile: String? = null) {
            java.util.zip.ZipOutputStream(apk.outputStream()).use { zip ->
                names.filter { it != missingFile }.forEach { name ->
                    zip.putNextEntry(java.util.zip.ZipEntry("lib/arm64-v8a/$name"))
                    if (name != emptyFile) zip.write(1)
                    zip.closeEntry()
                }
            }
        }
        try {
            writePackage()
            UpdateChecker.verifyRuntimeComponents(apk)
            names.forEach { name ->
                writePackage(missingFile = name)
                check(runCatching { UpdateChecker.verifyRuntimeComponents(apk) }.exceptionOrNull()?.message?.contains(name) == true)
                writePackage(emptyFile = name)
                check(runCatching { UpdateChecker.verifyRuntimeComponents(apk) }.exceptionOrNull()?.message?.contains(name) == true)
            }
        } finally { apk.delete() }
    }

    @Test fun silentInstallFlagIsLimitedToAndroid12AndNewer() {
        assertEquals(false, UpdateChecker.shouldUseSilentInstallFlag(28))
        assertEquals(false, UpdateChecker.shouldUseSilentInstallFlag(29))
        assertEquals(false, UpdateChecker.shouldUseSilentInstallFlag(30))
        assertEquals(true, UpdateChecker.shouldUseSilentInstallFlag(31))
        assertEquals(true, UpdateChecker.shouldUseSilentInstallFlag(35))
        assertEquals(false, UpdateChecker.shouldUseSilentInstallFlag(35, isUpdate = false))
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
    @Test fun silentInstallFailureFallsBackOnce() {
        assertEquals(
            true,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_FAILURE,
                silentAttempt = true,
                interactiveAlreadyStarted = false,
            ),
        )
        assertEquals(
            true,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_FAILURE_ABORTED,
                silentAttempt = true,
                interactiveAlreadyStarted = false,
            ),
        )
        assertEquals(
            true,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                silentAttempt = true,
                interactiveAlreadyStarted = false,
            ),
        )
        assertEquals(
            true,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_FAILURE_TIMEOUT,
                silentAttempt = true,
                interactiveAlreadyStarted = false,
            ),
        )
        assertEquals(
            false,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_PENDING_USER_ACTION,
                silentAttempt = true,
                interactiveAlreadyStarted = false,
            ),
        )
        assertEquals(
            false,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_SUCCESS,
                silentAttempt = true,
                interactiveAlreadyStarted = false,
            ),
        )
        assertEquals(
            false,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_FAILURE,
                silentAttempt = false,
                interactiveAlreadyStarted = false,
            ),
        )
        assertEquals(
            false,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_FAILURE,
                silentAttempt = true,
                interactiveAlreadyStarted = true,
            ),
        )
        assertEquals(
            false,
            UpdateChecker.shouldRetryWithInteractiveInstall(
                PackageInstaller.STATUS_FAILURE_STORAGE,
                silentAttempt = true,
                interactiveAlreadyStarted = false,
            ),
        )
    }

}
