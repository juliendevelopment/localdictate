package be.arte.localdictate.models

import java.io.File

/** Paths are private app files; no model is bundled in the APK. */
data class ModelFiles(val parakeetDir: File, val vadFile: File)

data class PreparationProgress(
    val label: String,
    val fraction: Float? = null,
    val ready: Boolean = false,
    val error: String? = null,
)

internal data class ModelAsset(val name: String, val size: Long, val sha256: String)

internal object ModelCatalog {
    const val PARAKEET = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
    /**
     * Immutable Hugging Face revision (16 August 2025) of the sherpa-onnx export, whose files have exactly the
     * hashes below. The GitHub `asr-models` archive used before is a mutable release asset: it was replaced on
     * 7 November 2026 by a new export, which the pinned size refused. A revision URL cannot change.
     */
    const val PARAKEET_REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
    const val PARAKEET_URL = "https://huggingface.co/csukuangfj/$PARAKEET/resolve/$PARAKEET_REVISION/"
    /** Silero VAD is still the unchanged release file, checked by hash (a replacement would be refused). */
    const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"
    const val MIN_FREE_BYTES = 1_500_000_000L
    val vad = ModelAsset(
        "silero_vad.onnx", 643854L,
        "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    )
    val members = listOf(
        ModelAsset("encoder.int8.onnx", 652184281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
        ModelAsset("decoder.int8.onnx", 11845275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
        ModelAsset("joiner.int8.onnx", 6355277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
        ModelAsset("tokens.txt", 93939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )
    val downloadBytes = members.sumOf { it.size } + vad.size
    // Changing any pinned asset invalidates previous readiness markers.
    val marker = (listOf(vad) + members).joinToString("\n", "verified-models-v2\n") {
        "${it.name}:${it.size}:${it.sha256}"
    }
    /**
     * Marker written by v0.3.8 and earlier, which extracted the same four files (same sizes and SHA-256) from the
     * former archive: those installations stay valid and are not downloaded again.
     */
    val legacyMarkers = listOf((listOf(
        ModelAsset("$PARAKEET.tar.bz2", 487170055L, "5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf"), vad,
    ) + members).joinToString("\n", "verified-models-v1\n") { "${it.name}:${it.size}:${it.sha256}" })
}
