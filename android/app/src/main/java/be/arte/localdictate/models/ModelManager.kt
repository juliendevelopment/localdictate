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

    /** Fast startup: a versioned verification marker AND exact file sizes are required. */
    fun isReady(): Boolean = readyInstallation(installed)

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
                onProgress(PreparationProgress("Vérification / téléchargement de Parakeet"))
                val archive = downloader.download(
                    ModelCatalog.BASE_URL + ModelCatalog.archive.name, ModelCatalog.archive, downloads,
                ) { onProgress(PreparationProgress("Téléchargement de Parakeet", it)) }
                onProgress(PreparationProgress("Vérification / téléchargement de Silero VAD"))
                val vad = downloader.download(
                    ModelCatalog.BASE_URL + ModelCatalog.vad.name, ModelCatalog.vad, downloads,
                ) { onProgress(PreparationProgress("Téléchargement de Silero VAD", it)) }
                currentCoroutineContext().ensureActive()
                check(staging.mkdirs()) { "Impossible de préparer le dossier temporaire" }
                onProgress(PreparationProgress("Extraction et vérification de Parakeet"))
                SafeModelExtractor().extract(archive, File(staging, ModelCatalog.PARAKEET)) {
                    onProgress(PreparationProgress("Extraction et vérification de Parakeet", it))
                }
                val stagedVad = File(staging, ModelCatalog.vad.name)
                vad.copyTo(stagedVad)
                if (!verified(stagedVad, ModelCatalog.vad)) throw IntegrityException("Silero VAD invalide après copie")
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
