package be.arte.localdictate.session

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

/** The last few dictated texts, app-private and atomic, so a text is never lost when insertion fails. */
class DictationHistory(private val file: File, private val limit: Int = 5) {
    @Synchronized fun read(): List<String> {
        if (!file.exists()) return emptyList()
        DataInputStream(file.inputStream().buffered()).use { input ->
            check(input.readInt() == 1) { "Format d’historique inconnu" }
            val count = input.readInt()
            check(count in 0..limit) { "Historique invalide" }
            return List(count) {
                val size = input.readInt()
                check(size in 0..MAX_TEXT_BYTES) { "Texte d’historique trop volumineux" }
                ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
            }
        }
    }
    @Synchronized fun add(text: String): List<String> {
        val current = runCatching { read() }.getOrDefault(emptyList())
        return listOf(text.trim()).filter { it.isNotEmpty() }.plus(current).take(limit).also(::write)
    }
    @Synchronized fun clear() { file.delete() }
    private fun write(items: List<String>) {
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val pending = File(file.parentFile, file.name + ".pending")
        FileOutputStream(pending).use { stream ->
            val output = DataOutputStream(stream)
            output.writeInt(1)
            output.writeInt(items.size)
            items.forEach { item ->
                val bytes = item.toByteArray(Charsets.UTF_8)
                check(bytes.size <= MAX_TEXT_BYTES) { "Texte trop volumineux pour l’historique" }
                output.writeInt(bytes.size); output.write(bytes)
            }
            output.flush()
            stream.fd.sync()
        }
        Files.move(pending.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    companion object { private const val MAX_TEXT_BYTES = 200_000 }
}
