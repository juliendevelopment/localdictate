package be.arte.localdictate.models

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class IntegrityException(message: String) : IOException(message)

internal fun regularFileWithSize(file: File, size: Long): Boolean =
    Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == size

internal suspend fun verified(file: File, asset: ModelAsset): Boolean {
    if (!regularFileWithSize(file, asset.size)) return false
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == asset.sha256
}

internal fun atomicMove(source: File, target: File) {
    // Both paths live under filesDir/models on the same filesystem. Never copy a
    // partially published installation into place as a fallback.
    Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE)
}

internal fun readyInstallation(
    installed: File,
    members: List<ModelAsset> = ModelCatalog.members,
    vad: ModelAsset = ModelCatalog.vad,
    markerText: String = ModelCatalog.marker,
    modelDirectory: String = ModelCatalog.PARAKEET,
): Boolean = try {
    val marker = File(installed, ".ready")
    val parakeet = File(installed, modelDirectory)
    Files.isDirectory(installed.toPath(), NOFOLLOW_LINKS) &&
        Files.isDirectory(parakeet.toPath(), NOFOLLOW_LINKS) &&
        regularFileWithSize(marker, markerText.toByteArray(Charsets.UTF_8).size.toLong()) &&
        marker.readText(Charsets.UTF_8) == markerText &&
        members.all { regularFileWithSize(File(parakeet, it.name), it.size) } &&
        regularFileWithSize(File(installed, vad.name), vad.size)
} catch (_: IOException) {
    false
} catch (_: SecurityException) {
    false
}

internal fun deleteTree(file: File) {
    if (Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)) {
        val children = file.listFiles() ?: throw IOException("Cannot list ${file.name}")
        children.forEach(::deleteTree)
    }
    // Never follow links, including dangling links left by a damaged installation.
    Files.deleteIfExists(file.toPath())
}
