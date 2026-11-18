package com.codexrm.mobile

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ImageFileCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun cachedSentImageSurvivesDraftDeletionAndCacheRecreation() {
        val source = temporary.newFile("draft").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val directory = File(temporary.root, "images")
        ImageFileCache(directory).write("server\nagent\nattachment:photo") { source.inputStream().use { input -> input.copyTo(it) } }
        source.delete()
        assertArrayEquals(byteArrayOf(1, 2, 3), ImageFileCache(directory).read("server\nagent\nattachment:photo")!!.readBytes())
    }

    @Test fun refreshingTheSameLocalPathReplacesCachedBytes() {
        val cache = ImageFileCache(temporary.newFolder())
        cache.write("agent\n/tmp/image.png") { it.write(byteArrayOf(1, 2, 3)) }
        cache.write("agent\n/tmp/image.png") { it.write(byteArrayOf(4, 5, 6, 7)) }
        assertArrayEquals(byteArrayOf(4, 5, 6, 7), cache.read("agent\n/tmp/image.png")!!.readBytes())
    }

    @Test fun failedTransferLeavesPreviousCacheAndNoPartialFile() {
        val directory = temporary.newFolder()
        val cache = ImageFileCache(directory)
        cache.write("photo") { it.write(byteArrayOf(1)) }
        try {
            cache.write("photo") { it.write(byteArrayOf(2)); throw IOException("disconnected") }
            fail("expected a failed transfer")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(1), cache.read("photo")!!.readBytes())
        assertEquals(1, directory.listFiles()!!.size)
    }

    @Test fun cacheKeysCannotEscapeThePrivateDirectory() {
        val directory = temporary.newFolder()
        val cache = ImageFileCache(directory)
        val file = cache.write("../../private?token=secret") { it.write(byteArrayOf(1)) }
        assertEquals(directory.canonicalFile, file.parentFile.canonicalFile)
        assertTrue(file.name.matches(Regex("[a-f0-9]{64}\\.image")))
        assertNull(cache.read("another-device"))
    }

    @Test fun oldImagesAreEvictedToBoundDiskUsage() {
        val directory = temporary.newFolder()
        val cache = ImageFileCache(directory, maxBytes = 4)
        cache.write("old") { it.write(byteArrayOf(1, 2)) }.setLastModified(1)
        cache.write("recent") { it.write(byteArrayOf(3, 4)) }.setLastModified(2)
        cache.write("new") { it.write(byteArrayOf(5, 6)) }
        assertNull(cache.read("old"))
        assertNotNull(cache.read("recent"))
        assertNotNull(cache.read("new"))
        assertEquals(4L, directory.listFiles()!!.sumOf { it.length() })
    }

    @Test fun emptyAndOversizedTransfersAreNeverCached() {
        val directory = temporary.newFolder()
        val cache = ImageFileCache(directory)
        for (key in listOf("empty", "oversized")) {
            try {
                cache.write(key) { if (key == "oversized") it.write(ByteArray(AttachmentStore.MAX_SIZE.toInt() + 1)) }
                fail("invalid size was accepted")
            } catch (_: IOException) { }
            assertNull(cache.read(key))
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
