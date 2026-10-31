# Models, runtime and licenses

LocalDictate's own code is licensed under the Apache License 2.0 (see `LICENSE`). Third-party components keep their own licenses. Their notices are bundled in `assets/THIRD_PARTY_NOTICES.txt` and readable offline in the app under « Modèles, licences et limites » → « Lire les notices complètes ».

| Component | Attribution / license | Primary source |
| --- | --- | --- |
| Parakeet TDT 0.6B v3 | NVIDIA, **CC BY 4.0**; ONNX conversion and int8 quantization by k2-fsa/sherpa-onnx, a format change of the original model; no endorsement by NVIDIA | [NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), [sherpa artifacts](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| sherpa-onnx 2.0.1 | k2-fsa and contributors, Apache 2.0 | [License at the pinned commit](https://github.com/k2-fsa/sherpa-onnx/blob/af2d8d81dbfa61c4cea4c568da78f3982edca005/LICENSE) |
| Silero VAD | Silero Team, MIT | [License](https://github.com/snakers4/silero-vad/blob/066409f3d9db45a30d21dfcbf9147826ffdd7052/LICENSE) |
| Native ONNX Runtime linked in sherpa | Microsoft, MIT | [License](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| Commons Compress 1.28.0 | Apache Software Foundation, Apache 2.0 | [License and notice](https://github.com/apache/commons-compress/tree/rel/commons-compress-1.28.0) |
| Kotlin/coroutines, AndroidX/Compose | JetBrains, Google and contributors, Apache 2.0 | [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt), [AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |

The NVIDIA model card explicitly covers French and English, automatic language identification, punctuation and capitalization. It is not a measurement of this integration on a phone.

Model weights are not in the repository or the APK: each user downloads them from the sources above when tapping **Prepare**.

## Pinned artifacts

| Artifact | Size (bytes) | SHA-256 |
| --- | ---: | --- |
| sherpa-onnx-static-link-onnxruntime-2.0.1.aar | 38 577 050 | `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575` |
| sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2 | 487 170 055 | `5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf` |
| silero_vad.onnx | 643 854 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

Hashes and sizes of every extracted file are in `ModelCatalog.kt`, taken from a verification of the real archive. The build re-checks the bytes of the real AAR. JVM fixtures download no model: they build tiny data and real archives, including corrupted and malicious ones, to exercise the same checks.

Relevant Android restrictions: [foreground services and microphone](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [clipboard and focus](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data). Clipboard reads are never needed: automatic insertion uses the public API 33+ editing connection, and the fallback only writes a clip. Overlay access is not used to work around the microphone foreground-service start rules.
