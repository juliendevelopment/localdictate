package be.arte.localdictate.models

import android.content.Context
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** ASR/VAD only. Called exclusively while DictationController holds the preparation gate.
 * Progress callbacks run on IO: callers must publish state rather than mutate views.
 * Errors are reported and rethrown; cancellation is never converted into a UI error.
 */
class ModelManager(context: Context) {
    private val root = File(context.applicationContext.filesDir, "models")
    private val installed = File(root, "installed")
    val modelFiles: ModelFiles = ModelFiles(
        parakeetDir = File(installed, ModelCatalog.PARAKEET),
        vadFile = File(installed, ModelCatalog.vad.name),
    )

    /** Derived from the verified tokens.txt when the recognizer loads (hotword splitting); not a model file. */
    val bpeVocab = File(root, "bpe.vocab")

    /** Fast startup: a versioned verification marker AND exact file sizes are required. */
    fun isReady(): Boolean = readyInstallation(installed, legacyMarkers = ModelCatalog.legacyMarkers)

    suspend fun prepare(onProgress: (PreparationProgress) -> Unit) = withContext(Dispatchers.IO) {
        preparationLock.withLock {
            try {
                currentCoroutineContext().ensureActive()
                if (isReady()) {
                    onProgress(PreparationProgress("Modèles prêts", 1f, ready = true))
                    return@withLock
                }
                check(root.isDirectory || root.mkdirs()) { "Impossible de créer le dossier des modèles" }
                val staging = File(root, "staging")
                deleteTree(staging)
                // A damaged installation is never loaded. Release its space before retry.
                deleteTree(installed)
                if (root.usableSpace < ModelCatalog.MIN_FREE_BYTES) {
                    throw IOException("Espace insuffisant : libérez au moins 1,5 Go avant la préparation")
                }
                val downloads = File(root, "downloads")
                val downloader = ResumableDownloader()
                // Leftovers of the former archive download (v0.3.8 and earlier) would only take space.
                listOf("", ".part").forEach { deleteTree(File(downloads, "${ModelCatalog.PARAKEET}.tar.bz2$it")) }
                // One file at a time (no archive to extract); each is checked by size and SHA-256 before use.
                var done = 0L
                suspend fun fetch(url: String, asset: ModelAsset, label: String): File {
                    onProgress(PreparationProgress("Vérification / téléchargement : $label"))
                    val file = downloader.download(url + asset.name, asset, downloads) { fraction ->
                        val bytes = done + (fraction * asset.size).toLong()
                        onProgress(PreparationProgress("Téléchargement : $label", bytes.toFloat() / ModelCatalog.downloadBytes))
                    }
                    done += asset.size
                    return file
                }
                val parakeet = ModelCatalog.members.map { fetch(ModelCatalog.PARAKEET_URL, it, "Parakeet (${it.name})") to it }
                val vad = fetch(ModelCatalog.VAD_URL, ModelCatalog.vad, "Silero VAD")
                currentCoroutineContext().ensureActive()
                onProgress(PreparationProgress("Installation des modèles vérifiés"))
                val stagedParakeet = File(staging, ModelCatalog.PARAKEET)
                check(stagedParakeet.mkdirs()) { "Impossible de préparer le dossier temporaire" }
                // Same filesystem: verified files are moved, never copied or re-downloaded.
                parakeet.forEach { (file, asset) -> atomicMove(file, File(stagedParakeet, asset.name)) }
                atomicMove(vad, File(staging, ModelCatalog.vad.name))
                currentCoroutineContext().ensureActive()
                File(staging, ".ready").writeText(ModelCatalog.marker, Charsets.UTF_8)
                if (!readyInstallation(staging)) throw IOException("Installation des modèles incomplète")
                atomicMove(staging, installed)
                if (!isReady()) throw IOException("Impossible de valider les modèles installés")
                // The installed tree is now authoritative; download caches are expendable.
                runCatching { deleteTree(downloads) }
                onProgress(PreparationProgress("Modèles prêts", 1f, ready = true))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                onProgress(PreparationProgress("Préparation interrompue", error = when (error) {
                    is IntegrityException -> "Modèle invalide : taille ou SHA-256 incorrect. Réessayez pour télécharger une copie vérifiée."
                    else -> "Préparation impossible : vérifiez connexion, espace libre (1,5 Go) et stockage privé, puis réessayez pour reprendre."
                }))
                throw error
            }
        }
    }

    private companion object {
        // Multiple UI owners/managers cannot append the same .part concurrently.
        val preparationLock = Mutex()
    }
}
