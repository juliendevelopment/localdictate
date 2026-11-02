# LocalDictate status

## Situation on 3 November 2026

**Dictation** ships as signed APKs on GitHub releases. Versions 0.1.0 to 0.2.0 were published while the repository was private (kept in a private archive with the original history); the public repository starts with **v0.2.1**, same app as 0.2.0 plus the Apache 2.0 license and English documentation. The app is installed and tested by the maintainer on a Pixel with Google Keep, a messaging app and Gboard. The **meeting** mode (sequential 2-hour recording, then local transcript and extractive summary, see [meeting.md](meeting.md)) is merged into `main` but not in any release yet and **not tested on a phone**.

Verified on the phone:

- installation (after pausing Play Protect scanning) and in-place updates with the same key;
- permissions, including "Allow restricted settings" for accessibility;
- Parakeet + Silero preparation, JNI loading, good French recognition;
- **reliable insertion at the cursor since v0.1.8**: several dictations in a row in Keep and in a messaging app, including a long text;
- automatic copy fallback and history of the last 5 texts;
- the v0.2.0 bubble (square at rest, ✕ | level | ✓ pill), approved by the maintainer.

Insertion used to fail because of a design error (requiring a "frozen world" during the dictation), fixed by the v0.1.8 redesign. Full analysis, version-by-version test log, current rules and how to read the messages: **[insertion.md](insertion.md)**.

## Known issues and next steps

1. **Start of speech**: one still has to wait 1–2 s after tapping the bubble before speaking, otherwise the beginning is sometimes missing (about one short phrase in two). v0.1.9 already opens the microphone first. Next: measure on the phone the real delay between the tap and the first audio (AudioRecord start, wake lock, journal write), and consider keeping a short audio buffer from the tap.
2. **Transcription errors**: some remain, fewer when speaking close to the microphone. Next: compare microphone level/AGC (the VOICE_RECOGNITION source has no gain) and Silero settings, with real sentences and their expected text.
3. **Insertion in chat apps** (Discord, messaging apps): refused in 0.2.3 while Keep works. 0.2.4 (hint read as empty) did not fix the reported app: its focused field is not exposed as editable (`modifiable=false`, no selection). 0.2.5 adds an editor-only target (see `insertion.md`), host-tested only. Next: on the Pixel, the same chat app with an empty and a non-empty field, then Discord and Keep again (no regression); read the message under « Durée capturée » if it fails.
4. If an insertion refusal comes back, read the reason shown (table in `insertion.md`) and fix **that rule**; do not reintroduce event-based invalidation or frozen editor sessions.
5. **Meeting mode (merged, host-tested only)**. Next: install a build from `main` on the Pixel (CI development APK or the next release), prepare the models, then record a 2–5 minute French meeting, stop, let it transcribe and summarize; check the transcript, the summary, Markdown/WAV export and deletion. Then the same with the screen off and locked, then a 30–60 minute session measuring processing time, heat and battery; finally 2 hours. Report the phase and message shown in the « Réunion » tab if anything fails.
6. Then: 10–15 minute dictation endurance (memory, heat), other apps (web fields), English.

7. **Weekly statistics** (words, dictated time, dictations per week, app-private, shown under the history) are released in v0.2.3 but not checked on the phone yet. Next: check on the Pixel that a dictation increments the current week and that the duration matches « Durée capturée ».

## Releases

Procedure: [ci.md](ci.md#releases). Never move a tag whose release exists. Versions up to 0.2.0 were released before the repository became public; their tags and APKs are not in this repository.

| Version | Content |
|---|---|
| 0.1.0 | First signed dictation build (strict API 33 insertion) |
| 0.1.1 | Target refusal reason shown |
| 0.1.2 | Input type compared on class + variation (Keep) |
| 0.1.3 | Confirmation by read-back; bubble freed after success |
| 0.1.4 | Detailed reasons; Copy no longer hides the result |
| 0.1.5 | Confirmation by read-back despite events |
| 0.1.6 | Automatic copy fallback; history of the last 5 texts |
| 0.1.7 | Events from other apps ignored |
| 0.1.8 | Redesign: single decision at stop on fresh reads, composition kept, selection never replaced, bubble always ready. **Insertion confirmed on the phone** |
| 0.1.9 | Microphone opened on tap (Silero loaded in parallel), segments widened by 0.3 s, forced cut at 20 s instead of 8 s |
| 0.2.0 | New bubble (square at rest, ✕/level/✓ pill, drag onto ✕ to hide, comes back on wake); ✕ cancels without inserting but keeps the text |
| 0.2.1 | First public release: Apache 2.0 license, English documentation |
| 0.2.2 | Screen kept on while dictating and processing (bubble window flag) |
| 0.2.3 | Weekly statistics: words, dictated time and dictations per week, app-private counts only |
| 0.2.4 | Insertion into empty fields showing a placeholder (Discord, messaging apps) |
| 0.2.5 | Editor-only insertion target for focused fields not exposed as editable (web fields, custom views) |

## Agreed scope

- French and English; local processing, no cloud, paid API or analytics. Models prepared explicitly, never bundled in Git or the APK.
- Dictation up to 15 minutes (typical use: 5 to 10), not ended by silence: bubble, user start/stop, then insertion at the cursor; overlay and accessibility accepted, the user's keyboard kept. Several dictations in a row, in as many apps as possible.
- **Never lose a dictation**: on failure, automatic copy to the clipboard (accepted even though it replaces the previous clip) and history in the app.
- Separate mode: meeting up to 2 hours, recording then local transcript and summary. The summary being developed is **extractive** (timestamped original sentences), not a generative LLM.

See the [scope](mvp.md), [architecture](architecture.md), [insertion](insertion.md), [CI](ci.md) and [phone test plan](acceptation-pixel.md).

## Branches

- **`main`**: published dictation (see releases).
- **`work/dictation`**: merged into `main`.
- **`work/meeting`**: meeting mode wired onto the `main` architecture (direct-to-disk audio capped at 2 h, CRC transcript journal, deterministic local extractive summary, dictation/preparation/meeting mutual exclusion, foreground service switching from microphone to processing, WAV/Markdown export, Compose UI). Merged into `main` on 3 November 2026 after 168 JVM/Robolectric tests, lint and debug/release builds.
- **`work/meeting-core`**: earlier meeting core, kept in the private archive only; superseded by `work/meeting`.

## Open review points

1. Insertion: the remaining risks listed in `insertion.md` (composition set before input started and never reported, web fields, recreated editors, Android 29–32).
2. Microphone FGS armed from a visible activity, microphone closed at rest, stop/revocation/mute/notification reachable. No service silently recreated from an old command. Applies to both `DictationService` and `MeetingService`.
3. Meeting: background recording endurance (screen off, lock, phone call, another app taking the microphone), post-recording transcription time, RAM, heat and battery on the Pixel. The extractive summary does not separate facts, decisions and actions (an `mvp.md` acceptance criterion); a better summary engine needs a separate decision.
4. Release of a version including the meeting mode, once the first phone checks are done.

## Validation

See [ci.md](ci.md#local-build): JVM/Robolectric tests, lint, debug and release builds, Python release-tooling tests. About 5 minutes per full build on a small VPS; avoid parallel builds when RAM is limited.

## Not verified on a phone yet

Insertion in apps other than Keep and one messaging app, web fields, English quality, 15-minute dictations, keeping the FGS/microphone across app switches and screen lock, airplane mode then return of connectivity, memory, latency, battery and heat, Android 29–32, the whole meeting mode. Host tests are never presented as phone results.
