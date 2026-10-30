package be.arte.localdictate.models

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

internal fun safeArchivePath(name: String, prefix: String): String {
    val path = name.removeSuffix("/")
    if (path.isEmpty() || path.startsWith('/') || '\\' in path || ':' in path || '\u0000' in path ||
        path.split('/').any { it.isEmpty() || it == "." || it == ".." } ||
        (path != prefix && !path.startsWith("$prefix/"))) {
        throw IOException("Unsafe archive path: $name")
    }
    return path
}

/** Bounds even metadata consumed internally by the tar reader. */
private class LimitedInput(
    input: InputStream,
    private var remaining: Long,
    private val checkActive: () -> Unit,
) : FilterInputStream(input) {
    override fun read(): Int {
        checkActive()
        val result = super.read()
        if (result >= 0 && --remaining < 0) throw IOException("Expanded archive limit exceeded")
        return result
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkActive()
        val result = `in`.read(buffer, offset, length)
        if (result > 0) {
            remaining -= result.toLong()
            if (remaining < 0) throw IOException("Expanded archive limit exceeded")
        }
        return result
    }
    override fun skip(count: Long): Long {
        val buffer = ByteArray(8192)
        var skipped = 0L
        while (skipped < count) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), count - skipped).toInt())
            if (n < 0) break
            skipped += n
        }
        return skipped
    }
}

internal class SafeModelExtractor(
    private val prefix: String = ModelCatalog.PARAKEET,
    private val members: List<ModelAsset> = ModelCatalog.members,
) {
    suspend fun extract(archive: File, destination: File, onFraction: (Float) -> Unit) {
        check(!destination.exists()) { "Extraction destination must be fresh" }
        check(destination.mkdirs()) { "Cannot create extraction directory" }
        val expected = members.associateBy { "$prefix/${it.name}" }
        val seen = mutableSetOf<String>()
        val expectedBytes = members.sumOf { it.size }
        val context = currentCoroutineContext()
        var extracted = 0L
        var skippedBytes = 0L
        var entries = 0
        try {
            archive.inputStream().buffered().use { raw ->
                BZip2CompressorInputStream(raw, true).use { bzip ->
                    TarArchiveInputStream(LimitedInput(bzip, expectedBytes + 132_000_000L) { context.ensureActive() }).use { tar ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = tar.nextTarEntry ?: break
                            if (++entries > 4096) throw IOException("Too many archive entries")
                            val name = safeArchivePath(entry.name, prefix)
                            if (entry.isSymbolicLink || entry.isLink || entry.isSparse ||
                                (!entry.isDirectory && !entry.isFile) || !tar.canReadEntryData(entry)) {
                                throw IOException("Unsupported archive entry: $name")
                            }
                            if (entry.isDirectory) {
                                if (entry.size != 0L) throw IOException("Nonempty directory entry")
                                continue
                            }
                            val asset = expected[name]
                            if (entry.size < 0) throw IOException("Invalid archive entry size")
                            if (asset == null) {
                                // Upstream includes test_wavs: discard, never install them.
                                if (entry.size > 32_000_000L || entry.size > 128_000_000L - skippedBytes) {
                                    throw IOException("Unlisted archive entry exceeds size budget")
                                }
                                skippedBytes += entry.size
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    if (tar.read(buffer) < 0) break
                                }
                                continue
                            }
                            if (!seen.add(name) || entry.size != asset.size) {
                                throw IntegrityException("Duplicate or wrong-sized model: $name")
                            }
                            val outputFile = File(destination, asset.name)
                            val digest = MessageDigest.getInstance("SHA-256")
                            var written = 0L
                            var lastPercent = -1
                            outputFile.outputStream().buffered().use { output ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = tar.read(buffer)
                                    if (count < 0) break
                                    if (count.toLong() > asset.size - written) throw IntegrityException("Oversized model")
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    written += count
                                    extracted += count
                                    val percent = (extracted.toDouble() * 100 / expectedBytes).toInt()
                                    if (percent != lastPercent) {
                                        onFraction(extracted.toFloat() / expectedBytes.toFloat())
                                        lastPercent = percent
                                    }
                                }
                            }
                            val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                            if (written != asset.size || hash != asset.sha256) {
                                throw IntegrityException("Invalid extracted model: ${asset.name}")
                            }
                        }
                    }
                }
            }
            if (seen != expected.keys) throw IntegrityException("Archive is missing required models")
            onFraction(1f)
        } catch (error: Exception) {
            deleteTree(destination)
            throw error
        }
    }
}
