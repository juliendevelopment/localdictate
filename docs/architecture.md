# Dictation architecture

## Pipeline

`MainActivity` (Compose, explicit setup) → `DictationController` (state held by the Application, mutual exclusion of preparation/use) → private `DictationService` → non-focusable bubble (`FloatingControl`) → `MicrophoneCapture` → bounded PCM queue → `SileroSegmenter` → bounded ASR queue → Parakeet `LocalRecognizer` → `TranscriptJournal` → history → fresh target check and insertion attempt.

The service is created **from the RESUMED activity**, after microphone, notification, overlay and accessibility are granted. Foreground promotion happens before any native loading. `START_NOT_STICKY`, no boot receiver, no silent restart. The bubble never creates the service. Arming loads the model but creates no `AudioRecord`. The notification (« Arrêter et désarmer ») and the app disarm the service; « Afficher la bulle » shows a hidden bubble again.

Foreground-service start restrictions and "while-in-use" microphone access are distinct: [Android documentation](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). Overlay/accessibility are not used as permission to start a microphone FGS from the background. Whether microphone access is kept in an already armed service after switching apps or locking, depending on the Android version, still has to be tested on a phone.

## Bubble

`FloatingControl` is a `TYPE_APPLICATION_OVERLAY` window with `FLAG_NOT_FOCUSABLE`, so the target field keeps the cursor. At rest it is a small rounded square with a waveform icon, docked to the nearest screen edge. While dictating it becomes a pill **✕ | live level | ✓**: dots until the microphone delivers its first audio, then bars following the real input level (posted at most once per frame from the capture thread). ✓ (or tapping the level) stops and inserts; ✕ stops and inserts nothing, but the text is still recognized and kept in the history. After a dictation that was not inserted, a short notice is shown for 5 s; an amber « ! » square means a text is waiting in the app.

Dragging the bubble (outside a dictation) shows a ✕ target at the bottom of the screen; dropping it there removes the overlay window while the service stays armed with the microphone closed. A receiver for `ACTION_USER_PRESENT`/`ACTION_SCREEN_ON`, the notification action and a button in the app add it back. A drag never starts a capture.

## Audio and compute

- PCM16 mono 16 kHz, built-in microphone when available; partial reads kept, scaled by 1/32768. No audio file is ever written.
- `AudioRecord` is created only after an explicit tap on the bubble, and **first**: Silero is loaded on the segmentation thread while PCM waits in the queue, so words spoken right after the tap are kept. On API 30+ the builder calls `setPrivacySensitive(true)` (VOICE_RECOGNITION is not sensitive by default). `stop()` stops and releases the recorder under a lock even if a downstream queue is busy. A read error or a microphone silenced by the system visibly interrupts the session.
- Two separate consumers: PCM (64 blocks, about 4.1 s) then ASR (8 segments). Saturation stops the microphone, keeps the block/segment that hit the full queue, then drains accepted data. It is shown, and the possibly incomplete text is not inserted automatically.
- Silero: 512-sample windows, threshold 0.5, 400 ms silence, 250 ms minimum speech, 20 s maximum segment, 1 CPU thread. Each segment is widened by 0.3 s before and after with real captured audio (`SegmentPadder`, 40 s ring), never overlapping the previous segment, because the VAD ends a segment exactly where silence starts. The final window is padded, the VAD flushed, and output bounded to real samples. A pause segments but never ends the session.
- Parakeet: `OfflineRecognizer`, `nemo_transducer`, 128 features, 2 CPU threads, greedy search. A single consumer serializes the engine; each stream is released in `finally`.
- Limit of 14,400,000 samples (900 s): the last read is cut at the limit before the microphone stops and the queues drain. The audio clock does not depend on worker speed; silent samples count. No data for 5 s is a failure, not silence.
- Partial wake lock only during capture/processing, released on stop/destroy, 30-minute safety timeout. No wake lock while merely armed.

RAM never holds the 15 minutes of PCM: queues, the 40 s ring, one VAD segment and one decode are bounded. The model and ONNX internal allocations remain large; only a phone measurement can establish their real peak and speed. No NPU is promised.

## Preparation and network

`ModelCatalog`, `ModelStorage`, `ResumableDownloader`, `SafeModelExtractor` and `ModelManager`: HTTPS, pinned sizes and SHA-256, `.part` resume, strict Content-Range/Length/Encoding checks, cancellation that disconnects blocking reads, allow-listed tar+bzip2 extraction with quotas, rejection of links/traversals/duplicates, atomic install and a versioned marker. A marker alone is not enough: sizes and regular files without symlinks are checked at launch. File SHAs are verified at install; startup does not rehash 640 MiB.

About 488 MB downloaded, 640 MiB installed; at least 1.5 GB free before preparation. No weights in Git or the APK. Only the Prepare action calls the network. A shared exclusion forbids preparation during arming, loading, recording, draining, insertion and native handle closing. Cancel keeps the exclusion until the I/O really ends. Clear is also serialized with the start of a session.

sherpa static-link AAR 2.0.1: 38,577,050 bytes, SHA-256 `07e042d97f3a6bd5b7fe74d4eef0513dc01a714b16c15a903250095aba43a575`, tag `af2d8d81dbfa61c4cea4c568da78f3982edca005`. `fetchSherpa`/`builtBy` ties the pinned fetch to Gradle. arm64-v8a only, other ABIs excluded before merge; no pickFirst rule. R8 explicitly keeps the JNI classes. No ONNX Java binding, no cloud engine.

## Target and direct insertion (API 33+)

Detailed rules, the field-test log and the reasons behind each choice: **[insertion.md](insertion.md)**. Summary:

On an explicit START only, `AccessibilityService.findFocus(FOCUS_INPUT)` finds the input field across interactive windows (`flagRetrieveInteractiveWindows`), even when the active window is the bubble. No window/tree traversal and no content reading in events, which are not used. The snapshot only records the chosen field: node, app, window, view id, class. Password fields (including visible/web/numeric types), our own app and fields longer than 32,768 characters are refused. ACTION_PASTE is not a compatibility condition.

`DirectEditorSession` exposes no recent platform type to the common class. `Api33DirectEditor` calls the public `getInputMethod`, `getCurrentInputConnection`, `getCurrentInputEditorInfo`, `getCurrentInputStarted` and **commitText(text, 1, null)**. `flagInputMethodEditor` gives an editor limited to accessibility, without selecting another keyboard. Min SDK stays 29: a common component is enabled on 29–32 (copy fallback), a dedicated API 33 component from 33 through versioned resources; only the latter exposes the recent InputMethod types.

At stop time everything is read again: the same node has the input focus, in the same app and window, editable and not a password; the editor is acquired **at that moment** (input restarted on the same field is normal) and must match the field (app, input class + variation, resolved view id); one off-main-thread `getSurroundingText(32768, 32768, GET_TEXT_WITH_STYLES)` read must agree with the node (text, length, selection, offset 0). A non-empty selection is refused: nothing is ever replaced.

**Keyboard composition**: commitText replaces the composing region. `GuardedInputMethod` stays passive (no writes, never finishes a composition, never moves the cursor) and records the selection and composition bounds reported by `onUpdateSelection`. A known composition containing the cursor is re-sent with the dictation inside it (`DirectContextPolicy.plan`), so no character is lost. A composition away from the cursor, or a composing marker without a known position, is refused. No report since the input started means no composition was set since then: insert. Right before the single commit, the field is checked again and the editor must not have reported a change since the read.

After the commit, the focused field (same app and view id) is read up to 6 × 250 ms: exactly the expected text and cursor → confirmed. Otherwise "requested but not confirmed"; never repaired or repeated. The attempt is persisted in the journal before the call, and a guard prevents two concurrent calls.

Fallback: the dictation is added to the private history of the last 5 texts **before** any attempt. When insertion is refused or not confirmed, `PrivateClipboard` copies the text automatically (clip flagged IS_SENSITIVE, replacing the previous one, a deliberate choice of the maintainer) and the bubble is immediately ready. The sensitive flag hides previews but is not access control. The clipboard is never read, cleared or restored. The automatic path uses neither ACTION_PASTE nor ACTION_SET_TEXT.

Primary references: [InputMethod](https://developer.android.com/reference/android/accessibilityservice/InputMethod), [AccessibilityInputConnection](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection), [flagInputMethodEditor](https://developer.android.com/reference/android/accessibilityservice/AccessibilityServiceInfo#FLAG_INPUT_METHOD_EDITOR), [AOSP InputMethod API 35](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/accessibilityservice/InputMethod.java), [span copying in SurroundingText](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-15.0.0_r1/core/java/android/view/inputmethod/SurroundingText.java). Wrapper/shadow tests are fixtures, not phone evidence.

## Recovery and privacy

Each recognized segment is written atomically and synced to app-private storage. No shared storage, Android backup/transfer disabled, no text/audio logs, no analytics. The journal holds the current dictation; when a dictation ends without interruption it is cleared once the text is written to the history (last 5 texts, private storage, « Effacer l’historique »). A new dictation never replaces recoverable text. The insertion marker is written **before** the dispatch: after a crash or recreation no insertion is retried. A crash may leave an "attempted" state without an actual insertion; the user checks and copies explicitly.

Process death loses audio segments still in RAM; text already synced is recovered and presented as partial. There is no audio resume. A disk failure leaves the last atomic journal readable and visibly stops the dictation. JVM tests cover these policies without loading the native engine.

## Meeting

A separate, visible and **unavailable** mode: no 2-hour capture, no simulated summary, no pasting. The future pipeline must store progressively, keep the full transcript and run a separate local summary engine. Work in progress lives on the `work/meeting-core` branch.
