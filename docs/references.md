# Références techniques

Références primaires à consulter pour l’implémentation ; cette liste ne signifie pas que toutes les intégrations sont déjà vérifiées.

- [Services foreground microphone et prérequis](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
- [Restrictions de lancement depuis l’arrière-plan](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Fenêtres TYPE_APPLICATION_OVERLAY](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)
- [Settings.canDrawOverlays](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context))
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Actions des champs accessibles, dont ACTION_PASTE et ACTION_SET_TEXT](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction)
- [Presse-papiers Android et contenus sensibles](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
- [Vérification du wrapper Gradle](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:verification)

Le socle utilise les versions de build connues du projet voisin un projet voisin ; ses modèles, poids, secrets, licence du code et données ne sont pas copiés.


## Intégration dictée (lane isolée)

- [Modèle NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) : FR/EN, détection automatique, ponctuation et capitalisation, CC BY 4.0. Il ne s’agit pas d’une mesure Pixel.
- [sherpa-onnx 2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) et [commit source figé](https://github.com/k2-fsa/sherpa-onnx/tree/af2d8d81dbfa61c4cea4c568da78f3982edca005) : AAR réel vérifié par taille/SHA, classes/API comparées avec javap. Tailles et digests dans [licences.md](licences.md) et le catalogue Kotlin.
- [Restrictions FGS microphone](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) : démarrage du service depuis activité RESUMED, pas depuis overlay en arrière-plan.
- [InputMethod limité pour accessibilité](https://developer.android.com/reference/android/accessibilityservice/InputMethod), [commitText et getSurroundingText](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection), [flagInputMethodEditor](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR) : insertion automatique API33+, sans dépendance au presse-papiers et sans remplacement du clavier. Garde de composition et de session détaillée dans l’architecture.
- Réutilisation adaptée des composants de préparation et de capture lus dans un projet voisin ; aucun changement du dépôt de référence, aucun composant de traduction/TTS.
