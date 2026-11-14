# Models, runtime and licenses

LocalDictate's own code is licensed under the Apache License 2.0 (see `LICENSE`). Third-party components keep their own licenses. Their notices are bundled in `assets/THIRD_PARTY_NOTICES.txt` and readable offline in the app under « Modèles, licences et limites » → « Lire les notices complètes ».

| Component | Attribution / license | Primary source |
| --- | --- | --- |
| Parakeet TDT 0.6B v3 | NVIDIA, **CC BY 4.0**; ONNX conversion and int8 quantization by k2-fsa/sherpa-onnx, a format change of the original model; no endorsement by NVIDIA | [NVIDIA model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), [sherpa artifacts](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| sherpa-onnx 2.0.1 | k2-fsa and contributors, Apache 2.0 | [License at the pinned commit](https://github.com/k2-fsa/sherpa-onnx/blob/af2d8d81dbfa61c4cea4c568da78f3982edca005/LICENSE) |
| Silero VAD | Silero Team, MIT | [License](https://github.com/snakers4/silero-vad/blob/066409f3d9db45a30d21dfcbf9147826ffdd7052/LICENSE) |
| Native ONNX Runtime linked in sherpa | Microsoft, MIT | [License](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| numpy 2.5.4 (Linux) | NumPy developers, BSD 3-Clause (wheels bundle OpenBLAS, BSD) | [License](https://github.com/numpy/numpy/blob/main/LICENSE.txt) |
| sherpa-onnx / sherpa-onnx-core 2.0.1 Python wheels (Linux) | k2-fsa and contributors, Apache 2.0; bundle ONNX Runtime (MIT) | [PyPI](https://pypi.org/project/sherpa-onnx/2.0.1/) |
| PyGObject, GTK 4, libadwaita, GStreamer (Linux, from the distribution, not redistributed) | LGPL 2.1+ | [PyGObject](https://gitlab.gnome.org/GNOME/pygobject), [GTK](https://gitlab.gnome.org/GNOME/gtk), [libadwaita](https://gitlab.gnome.org/GNOME/libadwaita), [GStreamer](https://gstreamer.freedesktop.org/) |
| Kotlin/coroutines, AndroidX/Compose | JetBrains, Google and contributors, Apache 2.0 | [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt), [AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |

The NVIDIA model card explicitly covers French and English, automatic language identification, punctuation and capitalization. It is not a measurement of this integration on a phone.

Model weights are not in the repository or the APK: each user downloads them from the sources above when tapping **Prepare**.

## Pinned artifacts

| Artifact | Size (bytes) | SHA-256 |
| --- | ---: | --- |
| sherpa-onnx-static-link-onnxruntime-2.0.1.aar | 38 577 050 | `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575` |
| encoder.int8.onnx | 652 184 281 | `acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247` |
| decoder.int8.onnx | 11 845 275 | `179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e` |
| joiner.int8.onnx | 6 355 277 | `3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3` |
| tokens.txt | 93 939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` |
| silero_vad.onnx | 643 854 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

The four Parakeet files come from the immutable Hugging Face revision [`2bda32ec`](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78) (same CC BY 4.0 model, same exporter), on Android since v0.3.9 and on Linux. They are the files of the former `sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2` release archive (487 170 055 bytes, SHA-256 `5793d0fd…16bf`), which was **replaced upstream on 7 November 2026** by a new export with a different encoder and decoder; see [linux.md](linux.md#model-sources-13-november-2026). Silero VAD still comes from the sherpa-onnx `asr-models` release.

Hashes and sizes are in `ModelCatalog.kt`. The build re-checks the bytes of the real AAR. JVM fixtures download no model: they build tiny data to exercise the same checks.

Relevant Android restrictions: [foreground services and microphone](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [clipboard and focus](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data). Clipboard reads are never needed: automatic insertion uses the public API 33+ editing connection, and the fallback only writes a clip. Overlay access is not used to work around the microphone foreground-service start rules.
