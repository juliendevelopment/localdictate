# LocalDictate

**On-device** dictation for Android, French and English: speak, and the text is typed at the cursor of whatever app you are in. No account, no subscription, no cloud speech service, no analytics. Audio and text never leave the phone.

Speech recognition runs locally with NVIDIA Parakeet TDT 0.6B v3 through sherpa-onnx, with Silero VAD for segmentation. A floating bubble starts and stops dictation (up to 15 minutes, pauses never end a session) and the text is inserted at the cursor through Android's accessibility editing API, keeping your usual keyboard (e.g. Gboard).

Status: early but working. Dictation has been tested by the maintainer on a Pixel in Google Keep and a messaging app, including long texts; other apps, long sessions and battery use are not verified yet. A separate meeting mode (2-hour recording, local transcript and summary) is planned but **not available** in the app yet. See [status](docs/status.md).

## Using it

1. Install the signed APK from the [releases](https://github.com/juliendevelopment/localdictate/releases) (arm64 phones). Android may block a sideloaded app that asks for accessibility; see [installation notes](docs/insertion.md#installing-on-a-pixel).
2. In the app, tap **Prepare** to download the models once (about 488 MB; 1.5 GB free space needed). This is the only network use.
3. Grant microphone, notifications, "display over other apps" and the LocalDictate accessibility service.
4. Tap **Arm the bubble**. A small square appears at the screen edge; the microphone stays closed.
5. In any app, put the cursor in a text field and tap the bubble. When the level bars move, speak. Tap **✓** to insert the text at the cursor, or **✕** to cancel (the text is still kept in the app).
6. Drag the bubble onto the ✕ at the bottom of the screen to hide it; it comes back when the phone is woken or unlocked.

Nothing is ever overwritten: insertion only happens at a collapsed cursor in the same field, never into password fields. If the text cannot be inserted, it is copied to the clipboard (replacing the previous clip) and the last five dictations are always available in the app. On Android 10–12 the text is copied instead of inserted.

## Privacy

No model weights in the repository or APK. Network is used only by the explicit model preparation, with pinned sizes and SHA-256. Accessibility is used only to find the focused field when you start a dictation and to insert the text when you finish; screens are not read or logged. Recognized text stays in app-private storage; audio is never saved.

## Building

Kotlin/Compose in `android/`, JDK 17, compile/target SDK 35, min SDK 29, ABI arm64-v8a. The Gradle wrapper and dependencies are pinned; the build downloads only the pinned native sherpa-onnx AAR, never model weights.

```sh
cd android
ANDROID_HOME=/path/to/sdk ./gradlew --no-daemon --max-workers=1 --console=plain \
  '-Dorg.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8' \
  -Pkotlin.compiler.execution.strategy=in-process \
  testDebugUnitTest lintDebug assembleDebug assembleRelease
```

## Documentation

[Status and releases](docs/status.md) · [Architecture](docs/architecture.md) · [Text insertion: design and field tests](docs/insertion.md) · [Scope](docs/mvp.md) · [Manual phone test plan](docs/acceptation-pixel.md) · [Models and licenses](docs/licences.md) · [Build and CI](docs/ci.md) · [References](docs/references.md)

The app's interface is in French.
