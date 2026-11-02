package be.arte.localdictate.meeting

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Only this bounded cache subtree is exposed by FileProvider. Never exposes a session/model path. */
class MeetingExport(private val cache: File, private val repository: MeetingRepository) {
    companion object { const val MAX_EXPORT_BYTES = 300L * 1024 * 1024 }
    fun create(session: MeetingSession, wav: Boolean): File {
        check(cache.isDirectory || cache.mkdirs())
        check(!java.nio.file.Files.isSymbolicLink(cache.toPath()))
        // An explicit new export renews the single bounded export slot; no private session is removed.
        cache.listFiles().orEmpty().forEach { file ->
            check(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()))
            check(file.delete())
        }
        val guard = StorageGuard { file, bytes ->
            if (bytes > MAX_EXPORT_BYTES || cache.listFiles().orEmpty().sumOf { it.length() } > MAX_EXPORT_BYTES - bytes)
                throw IOException("Export quota exhausted")
            ReservedSpaceGuard(64L * 1024 * 1024).requireSpace(file, bytes)
        }
        val destination = File(cache, "reunion-${UUID.randomUUID()}.${if (wav) "wav" else "md"}")
        try {
            if (wav) {
                SavedPcm(repository.audio(session.id)).use { check(it.tornBytes == 0L) { "Explicit recovery required" } }
                PcmRecording.recover(repository.audio(session.id), repository.guard).recording.use { it.finishWav(destination) }
                check(destination.length() <= MAX_EXPORT_BYTES)
            } else {
                guard.requireSpace(destination, 32_768)
                FileOutputStream(destination).use { stream ->
                    val writer = stream.bufferedWriter(Charsets.UTF_8)
                    writer.append("# Réunion locale\n\nÉtat : ${session.phase}. ${session.diagnostic}\n\n")
                    if (session.summary.isNotEmpty()) repository.summary(session).bufferedReader().use { reader ->
                        val buffer = CharArray(4096)
                        while (true) { val n = reader.read(buffer); if (n < 0) break; writer.write(buffer, 0, n) }
                    }
                    writer.append("\n# Transcription complète du journal courant\n\n")
                    if (session.journal.isNotEmpty()) TranscriptJournal.openReader(repository.journal(session)).use { reader ->
                        val report = reader.scan { segment ->
                            guard.requireSpace(destination, segment.text.length * 4L + 512)
                            writer.append("<a id=\"${segment.anchor}\"></a>\n[${segment.startMs / 1000}–${segment.endMs / 1000} s] ")
                                .append(segment.text).append("\n\n")
                            writer.flush() // Bound both buffering and quota accounting on long journals.
                        }
                        if (!report.isComplete) writer.append("\nJOURNAL PARTIEL : ${report.tailIssue}, ${report.tailBytes} octets non vérifiés. Préfixe vérifié seulement ; original privé conservé.\n")
                    }
                    writer.flush(); stream.fd.sync()
                }
            }
            return destination
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }
}
