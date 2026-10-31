# LocalDictate — project rules

Read `docs/mvp.md`, `docs/architecture.md`, `docs/ci.md` and `docs/status.md` before any implementation, and `docs/insertion.md` before touching insertion (phone test log and the reasons behind each rule). The goal is local FR/EN dictation, plus a second meeting mode.

## Invariants

- Audio, transcripts and summaries stay on the phone. No cloud recognition/summary, analytics, remote crash reporting or deferred telemetry. Do not use the system SpeechRecognizer without proof of a strictly local mode with no network fallback.
- Network only for an explicit model preparation. No weights in Git or the APK; check licenses, sizes and SHA-256 of the real artifacts before integrating them.
- Dictation: up to 15 minutes, not ended by mere silence; insertion at the cursor after stop. Meeting: up to 2 hours, incremental storage, full transcript and a separate summary; never paste automatically from that mode.
- The user grants overlay and accessibility, not permanent monitoring. Collect only the insertion target that is needed; never read every screen or log content. Never write into password fields.
- No existing text may be overwritten. A target that disappeared or changed after the dictation must lead to an explicit fallback, not a blind paste. A dictation must never be lost (history and clipboard fallback).
- No fake engine, invented transcript or start button that pretends to work. Distinguish features shipped, prepared and verified on a phone.
- No models/SDKs under incompatible or non-commercial licenses without a decision by the maintainer. No purchase or paid service. No license change or new publication channel without the maintainer's request.
- Secrets and the dedicated signing key stay outside the checkout. Never reuse another app's key.

## Conventions

- Code and documentation in English; the app's interface is in French. Kotlin/Compose in `android/`, docs in `docs/`, workflows at the root.
- JDK 17, pinned wrapper and dependencies. Use the wrapper, not a global Gradle.
- Small commits `type: description`. Push only this project's content.
- After a change: JVM tests, real activity under Robolectric, lint and APK; green CI for the exact SHA. A host test proves neither JNI, insertion in other apps, nor endurance on a phone.
- Later phases are not delivered by a green skeleton. Update `docs/status.md` with the precise next action and the remaining checks.
