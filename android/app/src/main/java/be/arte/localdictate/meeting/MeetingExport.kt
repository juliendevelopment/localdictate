package be.arte.localdictate.meeting

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/** Only this bounded cache subtree is exposed by FileProvider. Never exposes a session/model path. */
class MeetingExport(private val cache: File, private val repository: MeetingRepository) {
    private fun writePlain(session: MeetingSession, write: (String) -> Unit) = repository.writePlainText(session, write = write)
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
        val destination = File(cache, "${if (wav) "audio" else "transcription"}-${UUID.randomUUID()}.${if (wav) "wav" else "md"}")
        try {
            if (wav) {
                SavedPcm(repository.audio(session.id)).use { check(it.tornBytes == 0L) { "Explicit recovery required" } }
                PcmRecording.recover(repository.audio(session.id), repository.guard).recording.use { it.finishWav(destination) }
                check(destination.length() <= MAX_EXPORT_BYTES)
            } else {
                guard.requireSpace(destination, 32_768)
                FileOutputStream(destination).use { stream ->
                    val writer = stream.bufferedWriter(Charsets.UTF_8)
                    val created = java.time.Instant.ofEpochMilli(session.createdMs).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy HH:mm", java.util.Locale.FRENCH))
                    val minutes = (session.durationMs + 59_999) / 60_000
                    // Ready to hand to an LLM: a short header, then the plain transcript in paragraphs.
                    writer.append("# Notes orales du $created\n\n")
                        .append("Durée : environ $minutes min. Transcription locale automatique : elle peut contenir des erreurs de reconnaissance.\n\n")
                    val complete = writePlain(session) { piece ->
                        guard.requireSpace(destination, piece.length * 4L + 512)
                        writer.append(piece)
                        writer.flush() // Bound both buffering and quota accounting on long journals.
                    }
                    writer.append("\n")
                    if (!complete) writer.append("\nTRANSCRIPTION PARTIELLE : seul le début vérifié du journal est exporté ; l’original privé est conservé.\n")
                    writer.flush(); stream.fd.sync()
                }
            }
            return destination
        } catch (failure: Throwable) { destination.delete(); throw failure }
    }
}
