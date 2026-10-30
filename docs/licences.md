# Modèles, runtime et licences

Aucune licence n’est ajoutée au code propre de LocalDictate. Les composants tiers gardent leurs licences. Les notices sont embarquées dans `assets/THIRD_PARTY_NOTICES.txt` et consultables hors ligne dans « Modèles, licences et limites » → « Lire les notices complètes ».

| Composant | Attribution / licence | Origine primaire |
| --- | --- | --- |
| Parakeet TDT 0.6B v3 | NVIDIA, **CC BY 4.0** ; conversion ONNX et quantification int8 par k2-fsa/sherpa-onnx, changement de format du modèle d’origine ; aucune caution de NVIDIA | [Fiche NVIDIA](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), [licence CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), [artefacts sherpa](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models) |
| sherpa-onnx 2.0.1 | k2-fsa et contributeurs, Apache 2.0 | [Licence au commit figé](https://github.com/k2-fsa/sherpa-onnx/blob/af2d8d81dbfa61c4cea4c568da78f3982edca005/LICENSE) |
| Silero VAD | Silero Team, MIT | [Licence](https://github.com/snakers4/silero-vad/blob/066409f3d9db45a30d21dfcbf9147826ffdd7052/LICENSE) |
| ONNX Runtime natif lié dans sherpa | Microsoft, MIT ; aucun binding Java de traduction ajouté | [Licence](https://github.com/microsoft/onnxruntime/blob/main/LICENSE) |
| Commons Compress 1.28.0 | Apache Software Foundation, Apache 2.0 | [Licence et notice](https://github.com/apache/commons-compress/tree/rel/commons-compress-1.28.0) |
| Kotlin/coroutines, AndroidX/Compose | JetBrains, Google et contributeurs, Apache 2.0 | [Kotlin](https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt), [AndroidX](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt) |

La fiche NVIDIA décrit explicitement français et anglais, identification automatique de langue, ponctuation et capitalisation. Elle ne fournit pas une mesure Pixel de cette intégration. Qualité, mémoire et délai restent à tester sur le téléphone.

## Artefacts fixés

| Artefact | Taille (octets) | SHA-256 |
| --- | ---: | --- |
| sherpa-onnx-static-link-onnxruntime-2.0.1.aar | 38 577 050 | `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575` |
| sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2 | 487 170 055 | `5793d0fd397c5778d2cf2126994d58e9d56b1be7c04d13c7a15bb1b4eafb16bf` |
| silero_vad.onnx | 643 854 | `9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6` |

Les hashes/tailles de chaque fichier extrait sont dans `ModelCatalog.kt`, conservés de la vérification de l’archive réelle dans un projet voisin. Cette implémentation n’a pas téléchargé à nouveau le demi-gigaoctet de poids. Le build a recontrôlé les octets du vrai AAR, et `javap` ses classes/API. Aucun modèle téléchargé dans les fixtures JVM ; elles fabriquent des données minuscules et des archives réelles, y compris corrompues/malveillantes, pour exercer les mêmes vérifications.

Liens de restrictions Android : [FGS et microphone](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start), [presse-papiers et focus](https://developer.android.com/about/versions/10/privacy/changes#clipboard-data). L’accès overlay/accessibilité n’est pas présenté comme un contournement de ces restrictions.
