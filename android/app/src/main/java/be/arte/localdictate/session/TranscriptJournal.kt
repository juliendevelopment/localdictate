package be.arte.localdictate.session

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.UUID

data class TranscriptRecord(
    val id: String = "",
    val text: String = "",
    val interrupted: Boolean = false,
    val insertionAttempted: Boolean = false,
)

/** One private, atomic recovery record. No target contents or raw audio are persisted. */
class TranscriptJournal(private val file: File) {
    @Synchronized fun read(): TranscriptRecord {
        if (!file.exists()) return TranscriptRecord()
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == 1) { "Format de récupération inconnu" }
            val id = input.readUTF()
            val interrupted = input.readBoolean()
            val attempted = input.readBoolean()
            val size = input.readInt()
            check(size in 0..MAX_TEXT_BYTES) { "Transcription trop volumineuse" }
            val text = ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
            return TranscriptRecord(id, text, interrupted, attempted)
        }
    }
    @Synchronized fun begin(): TranscriptRecord {
        check(read().text.isBlank()) { "Copiez puis effacez la dictée précédente avant de recommencer." }
        return TranscriptRecord(UUID.randomUUID().toString(), interrupted = true).also(::write)
    }
    @Synchronized fun append(id: String, text: String): TranscriptRecord {
        val current = read()
        check(current.id == id)
        val combined = listOf(current.text, text.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        return current.copy(text = combined).also(::write)
    }
    @Synchronized fun finish(id: String, interrupted: Boolean): TranscriptRecord {
        val current = read()
        check(current.id == id)
        return current.copy(interrupted = interrupted).also(::write)
    }
    /** Persist before dispatch: a process death must never cause an automatic duplicate paste. */
    @Synchronized fun claimInsertion(id: String): Boolean {
        val current = read()
        if (current.id != id || current.insertionAttempted || current.interrupted || current.text.isBlank()) return false
        write(current.copy(insertionAttempted = true))
        return true
    }
    @Synchronized fun clear() = write(TranscriptRecord())
    private fun write(record: TranscriptRecord) {
        val bytes = record.text.toByteArray(Charsets.UTF_8)
        check(bytes.size <= MAX_TEXT_BYTES) { "Transcription trop volumineuse : arrêt, texte précédent conservé." }
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val pending = File(file.parentFile, file.name + ".pending")
        FileOutputStream(pending).use { stream ->
            val output = DataOutputStream(stream)
            output.writeInt(1)
            output.writeUTF(record.id)
            output.writeBoolean(record.interrupted)
            output.writeBoolean(record.insertionAttempted)
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    companion object { private const val MAX_TEXT_BYTES = 2_000_000 }
}
