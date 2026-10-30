package be.arte.localdictate.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class ResponsePlan(val append: Boolean, val bodySize: Long)

/** Validate all range metadata before opening a file for append/truncate. */
internal fun responsePlan(
    status: Int,
    requestedOffset: Long,
    expectedSize: Long,
    contentRange: String?,
    contentLength: Long,
): ResponsePlan {
    val plan = when (status) {
        HttpURLConnection.HTTP_OK -> ResponsePlan(false, expectedSize)
        HttpURLConnection.HTTP_PARTIAL -> {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(contentRange.orEmpty())
                ?: throw IOException("Missing or invalid Content-Range")
            val (start, end, total) = match.groupValues.drop(1).map {
                it.toLongOrNull() ?: throw IOException("Content-Range overflow")
            }
            if (start != requestedOffset || total != expectedSize || end != expectedSize - 1L || start > end) {
                throw IOException("Unexpected Content-Range")
            }
            ResponsePlan(requestedOffset > 0, expectedSize - start)
        }
        else -> throw IOException("Model download failed: HTTP $status")
    }
    if (contentLength >= 0 && contentLength != plan.bodySize) {
        throw IOException("Unexpected Content-Length")
    }
    return plan
}

internal class ResumableDownloader(
    private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    suspend fun download(
        url: String,
        asset: ModelAsset,
        directory: File,
        onFraction: (Float) -> Unit,
    ): File {
        require(URL(url).protocol == "https") { "Models require HTTPS" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create model download directory" }
        val complete = File(directory, asset.name)
        val partial = File(directory, "${asset.name}.part")
        if (verified(complete, asset)) {
            onFraction(1f)
            return complete
        }
        deleteTree(complete)
        if (java.nio.file.Files.exists(partial.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
            (!java.nio.file.Files.isRegularFile(partial.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) || partial.length() > asset.size)) {
            deleteTree(partial)
        }
        try {
            if (partial.length() != asset.size) {
                transfer(url, partial, asset.size, onFraction)
            }
            currentCoroutineContext().ensureActive()
            if (!verified(partial, asset)) throw IntegrityException("SHA-256 mismatch: ${asset.name}")
            atomicMove(partial, complete)
            onFraction(1f)
            return complete
        } catch (error: IntegrityException) {
            deleteTree(partial)
            throw error
        }
        // Network errors/cancellation leave .part intact. A subsequent call starts
        // at its actual length, without relying on a stale persisted byte counter.
    }

    private suspend fun transfer(
        url: String,
        partial: File,
        expectedSize: Long,
        onFraction: (Float) -> Unit,
    ) = coroutineScope {
        val offset = partial.length()
        val connection = connect(URL(url)).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        // Cancellation disconnects blocking reads, rather than waiting for the
        // next buffer or relying only on Thread.interrupt (not portable for HTTP).
        val cancellation = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { connection.disconnect() }
        }
        try {
            currentCoroutineContext().ensureActive()
            val status = connection.responseCode
            if (connection.url.protocol != "https") throw IOException("Insecure model redirect")
            if (connection.contentEncoding?.let { !it.equals("identity", ignoreCase = true) } == true) {
                throw IOException("Unexpected Content-Encoding")
            }
            val plan = responsePlan(
                status, offset, expectedSize,
                connection.getHeaderField("Content-Range"), connection.contentLengthLong,
            )
            var downloaded = if (plan.append) offset else 0L
            var bodyRead = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(partial, plan.append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count.toLong() > plan.bodySize - bodyRead) {
                            throw IntegrityException("Model response exceeds pinned size")
                        }
                        output.write(buffer, 0, count)
                        downloaded += count.toLong()
                        bodyRead += count.toLong()
                        val percent = (downloaded.toDouble() * 100 / expectedSize).toInt()
                        if (percent != lastPercent) {
                            onFraction(downloaded.toFloat() / expectedSize.toFloat())
                            lastPercent = percent
                        }
                    }
                    output.fd.sync()
                }
            }
            if (bodyRead != plan.bodySize) throw IOException("Download interrupted; retry to resume")
        } finally {
            cancellation.cancel()
            connection.disconnect()
        }
    }
}
