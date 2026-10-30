package be.arte.localdictate.models

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SafeModelExtractorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val contents = "tiny model fixture".toByteArray()
    private val member = ModelAsset("encoder.int8.onnx", contents.size.toLong(), sha(contents))
    private val prefix = "parakeet"

    private fun archive(vararg entries: Pair<TarArchiveEntry, ByteArray>): File {
        val file = temporary.newFile()
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            for ((entry, bytes) in entries) {
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }
    private fun normal(name: String = "$prefix/${member.name}", bytes: ByteArray = contents) =
        TarArchiveEntry(name) to bytes
    private fun destination() = File(temporary.newFolder(), "extracted")

    @Test fun writesOnlyAllowlistedModelsAndSkipsTestWavs() = runBlocking {
        val file = archive(
            TarArchiveEntry("$prefix/") to byteArrayOf(),
            normal("$prefix/test_wavs/example.wav"), normal(),
        )
        val destination = destination()
        val progress = mutableListOf<Float>()
        SafeModelExtractor(prefix, listOf(member)).extract(file, destination) { progress += it }
        assertArrayEquals(contents, File(destination, member.name).readBytes())
        assertEquals(listOf(member.name), destination.listFiles()!!.map { it.name })
        assertEquals(1f, progress.last())
    }

    @Test fun rejectsTraversalBeforeWritingOutsideDestination() = runBlocking {
        val file = archive(normal("$prefix/../escaped"))
        val destination = destination()
        expectFailure<IOException> { SafeModelExtractor(prefix, listOf(member)).extract(file, destination) {} }
        assertFalse(destination.exists())
        assertFalse(File(destination.parentFile, "escaped").exists())
    }

    @Test fun refusesSymlinksAndHardlinksEvenIfUnlisted() = runBlocking {
        for (type in listOf(TarConstants.LF_SYMLINK, TarConstants.LF_LINK)) {
            val link = TarArchiveEntry("$prefix/test_wavs/link", type).apply { linkName = "../../outside" }
            val destination = destination()
            expectFailure<IOException> {
                SafeModelExtractor(prefix, listOf(member)).extract(archive(link to byteArrayOf()), destination) {}
            }
            assertFalse(destination.exists())
        }
    }

    @Test fun rejectsDuplicates() = runBlocking {
        val destination = destination()
        expectFailure<IntegrityException> {
            SafeModelExtractor(prefix, listOf(member)).extract(archive(normal(), normal()), destination) {}
        }
        assertFalse(destination.exists())
    }

    @Test fun rejectsMissingMember() = runBlocking {
        val destination = destination()
        expectFailure<IntegrityException> {
            SafeModelExtractor(prefix, listOf(member)).extract(archive(normal("$prefix/unlisted")), destination) {}
        }
        assertFalse(destination.exists())
    }

    @Test fun rejectsWrongSizeAndDigest() = runBlocking {
        for (bytes in listOf(contents + byteArrayOf(0), contents.copyOf().also { it[0] = 0 })) {
            val destination = destination()
            expectFailure<IntegrityException> {
                SafeModelExtractor(prefix, listOf(member)).extract(archive(normal(bytes = bytes)), destination) {}
            }
            assertFalse(destination.exists())
        }
    }

    @Test fun cancellationRemovesUnpublishedExtraction() = runBlocking {
        val destination = destination()
        expectFailure<CancellationException> {
            coroutineScope {
                SafeModelExtractor(prefix, listOf(member)).extract(archive(normal()), destination) { cancel() }
            }
        }
        assertFalse(destination.exists())
    }

    @Test fun pathValidationRejectsAbsoluteWindowsTraversalAndOtherPrefixes() {
        listOf("/parakeet/model", "C:/parakeet/model", "parakeet\\model", "parakeet/../x",
            "parakeet/./model", "parakeet//model", "other/model", "parakeet2/model",
            "parakeet/model\u0000suffix", "../parakeet/model").forEach { path ->
            try { safeArchivePath(path, prefix); fail("Accepted $path") } catch (_: IOException) {}
        }
        assertEquals("parakeet", safeArchivePath("parakeet/", prefix))
        assertEquals("parakeet/tokens.txt", safeArchivePath("parakeet/tokens.txt", prefix))
    }

    @Test fun existingDestinationIsNeverOverwritten() = runBlocking {
        val destination = temporary.newFolder()
        val sentinel = File(destination, "keep").apply { writeText("keep") }
        expectFailure<IllegalStateException> {
            SafeModelExtractor(prefix, listOf(member)).extract(archive(normal()), destination) {}
        }
        assertEquals("keep", sentinel.readText())
    }
}
