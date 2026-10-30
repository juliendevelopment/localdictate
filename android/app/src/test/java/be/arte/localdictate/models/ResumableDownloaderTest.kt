package be.arte.localdictate.models

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ResumableDownloaderTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "a small deterministic model fixture".toByteArray()
    private val asset = ModelAsset("model.bin", bytes.size.toLong(), sha(bytes))
    private val url = "https://example.invalid/model.bin"
    private fun directory() = temporary.newFolder()

    @Test fun interruptedDownloadKeepsPartialAndNextCallResumes() = runBlocking {
        val directory = directory()
        val first = FakeConnection(bytes, failAfter = 8)
        expectFailure<IOException> {
            ResumableDownloader { first }.download(url, asset, directory) {}
        }
        assertEquals(8L, File(directory, "model.bin.part").length())
        val second = FakeConnection(bytes.copyOfRange(8, bytes.size), status = 206,
            range = "bytes 8-${bytes.lastIndex}/${bytes.size}")
        val result = ResumableDownloader { second }.download(url, asset, directory) {}
        assertEquals("bytes=8-", second.getRequestProperty("Range"))
        assertArrayEquals(bytes, result.readBytes())
        assertFalse(File(directory, "model.bin.part").exists())
        assertTrue(second.disconnected)
    }

    @Test fun ignoredRangeRestartsInsteadOfAppending() = runBlocking {
        val directory = directory()
        File(directory, "model.bin.part").writeBytes(bytes.copyOf(7))
        val connection = FakeConnection(bytes)
        val result = ResumableDownloader { connection }.download(url, asset, directory) {}
        assertArrayEquals(bytes, result.readBytes())
        assertEquals("bytes=7-", connection.getRequestProperty("Range"))
    }

    @Test fun malformedRangeNeverChangesExistingPartial() = runBlocking {
        val directory = directory()
        val partial = File(directory, "model.bin.part").apply { writeBytes(bytes.copyOf(7)) }
        val connection = FakeConnection(bytes.copyOfRange(7, bytes.size), 206,
            "bytes 6-${bytes.lastIndex}/${bytes.size}")
        expectFailure<IOException> { ResumableDownloader { connection }.download(url, asset, directory) {} }
        assertArrayEquals(bytes.copyOf(7), partial.readBytes())
    }

    @Test fun wrongDigestDeletesPartial() = runBlocking {
        val directory = directory()
        val corrupt = bytes.copyOf().also { it[0] = 0 }
        expectFailure<IntegrityException> {
            ResumableDownloader { FakeConnection(corrupt) }.download(url, asset, directory) {}
        }
        assertFalse(File(directory, "model.bin.part").exists())
        assertFalse(File(directory, "model.bin").exists())
    }

    @Test fun oversizedBodyDeletesPartial() = runBlocking {
        val directory = directory()
        expectFailure<IntegrityException> {
            ResumableDownloader { FakeConnection(bytes + byteArrayOf(1), declaredLength = -1) }
                .download(url, asset, directory) {}
        }
        assertFalse(File(directory, "model.bin.part").exists())
    }

    @Test fun completePartialIsVerifiedWithoutNetwork() = runBlocking {
        val directory = directory()
        File(directory, "model.bin.part").writeBytes(bytes)
        val result = ResumableDownloader { error("Network must not be used") }.download(url, asset, directory) {}
        assertArrayEquals(bytes, result.readBytes())
    }

    @Test fun verifiedCacheIsReusedWithoutNetwork() = runBlocking {
        val directory = directory()
        File(directory, "model.bin").writeBytes(bytes)
        val result = ResumableDownloader { error("Network must not be used") }.download(url, asset, directory) {}
        assertArrayEquals(bytes, result.readBytes())
    }

    @Test fun oversizedPartialRestartsWithoutRange() = runBlocking {
        val directory = directory()
        File(directory, "model.bin.part").writeBytes(bytes + bytes)
        val connection = FakeConnection(bytes)
        ResumableDownloader { connection }.download(url, asset, directory) {}
        assertNull(connection.getRequestProperty("Range"))
    }

    @Test fun cancellationRetainsPartialAndDisconnects() = runBlocking {
        val directory = directory()
        val connection = FakeConnection(bytes)
        expectFailure<CancellationException> {
            coroutineScope {
                ResumableDownloader { connection }.download(url, asset, directory) { cancel() }
            }
        }
        assertTrue(connection.disconnected)
        assertTrue(File(directory, "model.bin.part").length() > 0L)
        assertFalse(File(directory, "model.bin").exists())
    }

    @Test fun invalidContentRangeVariantsAreRejected() {
        listOf(null, "bytes 1-9/10", "bytes 0-8/10", "bytes 0-9/*", "bytes 0-9/11",
            "bytes 0-9/999999999999999999999999").forEach { range ->
            try { responsePlan(206, 0, 10, range, -1); fail("Accepted $range") } catch (_: IOException) {}
        }
        assertEquals(ResponsePlan(true, 6), responsePlan(206, 4, 10, "bytes 4-9/10", 6))
        assertEquals(ResponsePlan(false, 10), responsePlan(200, 4, 10, null, 10))
    }

    @Test fun unexpectedStatusOrLengthIsRejected() {
        for (status in listOf(404, 416, 500)) {
            try { responsePlan(status, 3, 10, null, -1); fail() } catch (_: IOException) {}
        }
        try { responsePlan(200, 0, 10, null, 9); fail() } catch (_: IOException) {}
    }

    @Test fun readyMarkerAloneDoesNotDeclareReady() {
        val root = directory()
        File(root, ".ready").writeText("marker")
        assertFalse(readyInstallation(root, listOf(asset), asset, "marker", "parakeet"))
        File(root, "parakeet").mkdir()
        File(root, "parakeet/model.bin").writeBytes(bytes)
        File(root, "model.bin").writeBytes(bytes)
        assertTrue(readyInstallation(root, listOf(asset), asset, "marker", "parakeet"))
        File(root, "parakeet/model.bin").appendBytes(byteArrayOf(1))
        assertFalse(readyInstallation(root, listOf(asset), asset, "marker", "parakeet"))
    }

    @Test fun readinessRejectsSymlinksAndStaleMarker() {
        val root = directory()
        File(root, "parakeet").mkdir()
        val external = temporary.newFile().apply { writeBytes(bytes) }
        Files.createSymbolicLink(File(root, "parakeet/model.bin").toPath(), external.toPath())
        File(root, "model.bin").writeBytes(bytes)
        File(root, ".ready").writeText("marker")
        assertFalse(readyInstallation(root, listOf(asset), asset, "marker", "parakeet"))
        Files.delete(File(root, "parakeet/model.bin").toPath())
        File(root, "parakeet/model.bin").writeBytes(bytes)
        File(root, ".ready").writeText("stale!")
        assertFalse(readyInstallation(root, listOf(asset), asset, "marker", "parakeet"))
    }

    @Test fun atomicDirectoryPublicationPreservesContents() {
        val root = directory()
        val staging = File(root, "staging").apply { mkdir() }
        File(staging, "payload").writeBytes(bytes)
        val installed = File(root, "installed")
        atomicMove(staging, installed)
        assertFalse(staging.exists())
        assertArrayEquals(bytes, File(installed, "payload").readBytes())
    }

    @Test fun cancellationDisconnectsAnActuallyBlockedRead() = runBlocking {
        val directory = directory()
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : HttpURLConnection(URL(url)) {
            override fun connect() {}
            override fun disconnect() { disconnected.countDown() }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getContentLengthLong() = asset.size
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    check(disconnected.await(3, TimeUnit.SECONDS)) { "Cancellation did not disconnect blocking IO" }
                    throw IOException("Disconnected fixture")
                }
            }
        }
        val job = launch(Dispatchers.IO) {
            try { ResumableDownloader { connection }.download(url, asset, directory) {} }
            catch (_: IOException) { /* Disconnect can finish the read before cancellation is observed. */ }
        }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        job.cancel()
        withTimeout(3_000) { job.join() }
        assertEquals(0L, disconnected.count)
        assertFalse(File(directory, asset.name).exists())
    }

    private class FakeConnection(
        private val body: ByteArray,
        private val status: Int = 200,
        private val range: String? = null,
        private val failAfter: Int? = null,
        private val declaredLength: Long = body.size.toLong(),
    ) : HttpURLConnection(URL("https://example.invalid/model.bin")) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getContentLengthLong() = declaredLength
        override fun getHeaderField(name: String?): String? = if (name == "Content-Range") range else null
        override fun getInputStream(): InputStream {
            val threshold = failAfter ?: return ByteArrayInputStream(body)
            return object : InputStream() {
                private var position = 0
                override fun read(): Int {
                    if (position >= threshold) throw IOException("Simulated network interruption")
                    return body[position++].toInt() and 0xff
                }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (position >= threshold) throw IOException("Simulated network interruption")
                    val count = minOf(length, threshold - position)
                    body.copyInto(buffer, offset, position, position + count)
                    position += count
                    return count
                }
            }
        }
    }
}

internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

internal suspend inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
    try { block(); fail("Expected ${T::class.java.simpleName}") } catch (error: Throwable) {
        if (error !is T) throw error
    }
}
