# Technical references

Primary references used for the implementation; being listed here does not mean an integration is verified.

- [Microphone foreground services and prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types#microphone)
- [Background start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [TYPE_APPLICATION_OVERLAY windows](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY)
- [Settings.canDrawOverlays](https://developer.android.com/reference/android/provider/Settings#canDrawOverlays(android.content.Context))
- [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService)
- [Accessible field actions, including ACTION_PASTE and ACTION_SET_TEXT](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo.AccessibilityAction)
- [Android clipboard and sensitive content](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- [whisper.cpp](https://github.com/ggml-org/whisper.cpp)
- [Gradle wrapper verification](https://docs.gradle.org/current/userguide/gradle_wrapper.html#sec:verification)

## Dictation

- [NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3): FR/EN, automatic language detection, punctuation and capitalization, CC BY 4.0. Not a phone measurement.
- [sherpa-onnx 2.0.1](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.1) and the [pinned source commit](https://github.com/k2-fsa/sherpa-onnx/tree/af2d8d81dbfa61c4cea4c568da78f3982edca005): real AAR verified by size/SHA, classes and API compared with `javap`. Sizes and digests in [licences.md](licences.md) and the Kotlin catalog.
- [Microphone FGS restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start): the service is started from a RESUMED activity, never from the overlay in the background.
- [Limited InputMethod for accessibility](https://developer.android.com/reference/android/accessibilityservice/InputMethod), [commitText and getSurroundingText](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection), [flagInputMethodEditor](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR): automatic insertion on API 33+ without replacing the keyboard. Rules in [insertion.md](insertion.md).
