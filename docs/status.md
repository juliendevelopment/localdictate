# LocalDictate status

## Situation on 14 November 2026

**Dictation** ships as signed APKs on GitHub releases. Versions 0.1.0 to 0.2.0 were published while the repository was private (kept in a private archive with the original history); the public repository starts with **v0.2.1**, same app as 0.2.0 plus the Apache 2.0 license and English documentation. The app is installed and tested by the maintainer on a Pixel with Google Keep, a messaging app and Gboard.

The **meeting** mode (sequential 2-hour recording, then local transcript to copy or export, see [meeting.md](meeting.md)) ships since **v0.3.0**. It has had three phone tests (short French notes, a speakerphone call, a real meeting of about 1 h 40), each followed by a fix (v0.3.1–v0.3.4); a full 2-hour session and the fixes themselves are not verified on the phone yet.

**v0.3.8** is released (12 November 2026, [release notes](releases/v0.3.8.md)), host-tested only. Seven releases wait for a phone check: they are grouped in the next phone session below.

**v0.3.9** is prepared on `main` (14 November 2026, [release notes](releases/v0.3.9.md)), not released yet: « Mes mots » (the user's hotwords and `entendu => voulu` replacements, maintainer request: the maintainer reports dictation works well apart from a few unknown words) and model preparation from the immutable Hugging Face revision (known issue 0). Host-tested only (JVM/Robolectric tests, lint, debug and release builds, plus the same sherpa-onnx configuration in Python on the FR/EN samples). Precise next action: maintainer's go to tag `android-v0.3.9` once CI is green on that SHA, then steps 0 and 6 of the phone session.

Verified on the phone:

- installation (after pausing Play Protect scanning) and in-place updates with the same key;
- permissions, including "Allow restricted settings" for accessibility;
- Parakeet + Silero preparation, JNI loading, good French recognition;
- **reliable insertion at the cursor since v0.1.8**: several dictations in a row in Keep and in a messaging app, including a long text;
- automatic copy fallback and history of the last 5 texts;
- the v0.2.0 bubble (square at rest, ✕ | level | ✓ pill), approved by the maintainer;
- meeting: recording, transcription and copy on short notes and a call (v0.3.0–v0.3.1, see item 5).

Insertion used to fail because of a design error (requiring a "frozen world" during the dictation), fixed by the v0.1.8 redesign. Full analysis, version-by-version test log, current rules and how to read the messages: **[insertion.md](insertion.md)**.

## Linux version

A Linux desktop port of the dictation lives in `linux/` (Python, GTK 4/libadwaita, GStreamer, sherpa-onnx 2.0.1, same pinned models): global shortcut (press to start, press to stop and paste), adjustable microphone (device, gain, automatic gain, speech detection threshold, level test), clipboard + Ctrl+V through the XDG portals, private history keeping each recording. Maintainer decisions, architecture and evidence: [linux.md](linux.md). **Host-tested only** (12 November 2026): 52 unit tests, the real engine on FR/EN WAVs, the portal clients against a fake portal, the app under GTK Broadway. Not yet run on a real desktop.

**Precise next action (Linux):** the [desktop test plan](linux.md#desktop-test-plan-next-action) on the maintainer's machine, reporting the desktop (GNOME/KDE, Wayland/X11), the shortcut line and the « Dernière dictée » diagnostic. Then: meeting mode, packaging.

## Next phone session

**Precise next action.** One sitting of about 30 minutes on the Pixel, with the v0.3.9 APK from the GitHub release (v0.3.8 until it is released). Steps and boxes: [acceptation-pixel.md](acceptation-pixel.md). For each step, if something fails, report the « Diagnostic : … » line and the message under « Durée capturée » (dictation), or the state and message in the « Réunion » tab (meeting).

0. **Model preparation** (v0.3.9): on the updated Pixel, « Modèles prêts » with no download. If a phone or work profile without the model is available, « Préparer » downloads about 670 MB and ends « Modèles prêts ».
1. **Start of speech and soft speech** (v0.3.7, v0.3.8; items 1 and 2): a short phrase spoken right after the tap, three times; then soft, normal and loud voice in one dictation. Note the diagnostic line each time.
2. **Empty chat fields** (v0.2.5, v0.3.5; item 3): Discord and Paseo with an empty field, then with text already in it, then Keep (no regression).
3. **Media pause** (v0.3.6; item 6): music or a podcast playing pauses on the tap and resumes after ✓ and after ✕; nothing starts when nothing was playing; note any player that ignores it.
4. **Weekly statistics** (v0.2.3; item 7): one dictation increments the current week; the duration matches « Durée capturée ».
5. **Meeting** (v0.3.3, v0.3.4, v0.3.8; item 5): « Retranscrire » on the 3784 s recording and read the final state and cause; then one recording of 1 h 30 to 2 h with the screen off, then « Retranscrire » on it; it must end « terminé ».
6. **Mes mots** (v0.3.9; item 9): dictate a sentence with a usually missed word, add it in « Mes mots », dictate again; then an `entendu => voulu` line. Report the decode time of the diagnostic line compared with before, and any listed word that appears where it was not said (if so, the score 1.5 is too strong for this list).

## Known issues

0. **Model preparation on Android was broken for new installs (13 November 2026).** sherpa-onnx replaced the pinned `asr-models` archive on 7 November with a new export of different size and hashes; the app refuses it (as designed), so « Préparer » fails on a phone without the model. Phones that already have the model are not affected. v0.3.9 downloads the same four verified files from the immutable Hugging Face revision already used on Linux ([linux.md](linux.md#model-sources-13-november-2026)); the extraction code and Commons Compress are removed; the v1 marker of existing installations stays valid (no new download). Host-tested only. Next: step 0 of the phone session.

1. **Start of speech**: one still had to wait 1–2 s after tapping the bubble before speaking, otherwise the beginning was sometimes missing (about one short phrase in two). v0.1.9 opened the microphone before loading Silero; v0.3.8 also moves the target lookup, media pause, wake lock and journal write after the microphone start, and shows the measured delay from the tap to the first audio. Next: step 1 of the phone session. A delay under about 300 ms with the beginning still missing points at Silero or at the segment margins; a longer delay points at `AudioRecord` start-up, and a short audio buffer kept from the tap is the next design to consider.
2. **Soft speech**: the maintainer reported (10 November) that soft speech close to the microphone is often not picked up while a loud voice works. The VOICE_RECOGNITION source has no gain, so v0.3.7 adds an automatic software gain to dictation only (`VoiceGain`: up to +18 dB, peaks towards −6 dBFS, never below 1×, no clipping, 1 s release), host-tested. Next: step 1 of the phone session. Reading the diagnostic: a peak below about −40 dBFS even with gain ×8 → capture (source or microphone routing); a reasonable peak but few or no segments → Silero (threshold 0.5 to lower, measured on real sentences); segments but wrong text → Parakeet.
3. **Insertion in chat apps** (Discord, messaging apps): refused in 0.2.3 while Keep works. 0.2.4 (hint read as empty) did not fix the reported app: its focused field is not exposed as editable (`modifiable=false`, no selection). 0.2.5 adds an editor-only target (see `insertion.md`). Phone test on v0.3.4 (4 November): Discord and an empty Paseo field refused with `modifiable=true … sélection=-1--1/0` (an empty `TextView` reports no cursor to accessibility); Paseo inserts when the field already holds text. v0.3.5 treats an empty field without a reported cursor as cursor 0, host-tested. Next: step 2 of the phone session.
4. If an insertion refusal comes back, read the reason shown (table in `insertion.md`) and fix **that rule**; do not reintroduce event-based invalidation or frozen editor sessions.
5. **Meeting mode / long spoken notes**. History:
   - v0.3.0, 3 min 40 of French notes: every voiced region produced text, the gaps were real silences, but the transcript was fragmented with English filler words and the export was not practical to give to an LLM. v0.3.1 merges segments separated by ≤ 3 s before decoding, adds « Copier la transcription (texte brut) » and « Retranscrire »; v0.3.2 removes the in-app summary (maintainer decision) and adds « Supprimer ».
   - v0.3.1, 4 min 33 speakerphone call: transcription failed at 129 s on every run. Replaying the WAV with sherpa-onnx 2.0.1 showed a 23 s VAD segment although the maximum is 20 s, and the transcriber rejected anything over 22 s. v0.3.3 splits over-long segments at their quietest point (`SpeechSplitter`, shared with dictation) and reports the failure position and cause; replayed on that WAV: 17 blocks, longest 19.3 s.
   - v0.3.1, real meeting of about 1 h 40: recording stopped at 3784 s with « File d’écriture saturée ». The capture queue held well under a second of audio and every block was synced; one slow sync closed the microphone and nothing alerted the user. v0.3.4 bounds the queue by audio duration (60 s), syncs about once per second, saves metadata every 10 s and posts a high-priority notification when a recording stops on its own. That 3784 s session later ended `CANCELLED` after a re-transcription, cause unknown: v0.3.8 records the cause of every cancellation.

   Next: step 5 of the phone session. Then, still to do: a Dutch sample; screen off and locked during processing; a 30–60 minute session measuring processing time, heat and battery; Markdown/WAV export and deletion.
6. **Media pause during dictation** (v0.3.6, audio focus): next, step 3 of the phone session.
7. **Weekly statistics** (words, dictated time, dictations per week, app-private, shown under the history; v0.2.3): next, step 4 of the phone session.
8. Then: 10–15 minute dictation endurance (memory, heat), other apps (web fields), English.
9. **Mes mots** (v0.3.9): hotwords through modified beam search (score 1.5, per stream) and replacements of the decoded text; design and host measurements in [architecture.md](architecture.md#users-words-v039). Not measured yet on the maintainer's real words. If hotwords do not help enough, raise the score carefully (4 already forces a word over unrelated speech) or rely on replacements; if decoding gets too slow on the Pixel, fall back to greedy search when the list has no hotwords.

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
| 0.3.0 | Meeting mode: 2-hour private recording, then local transcript and extractive summary, export/deletion |
| 0.3.1 | Meeting: segments joined across short pauses before decoding, plain-text transcript copy, optional summary, re-transcription |
| 0.3.2 | In-app summary removed; Markdown export is the plain transcript for an LLM; selected mode highlighted; delete button per meeting |
| 0.3.3 | Over-long speech segments split instead of failing the transcription (speakerphone calls); failure position and cause shown |
| 0.3.4 | Meeting capture: 60 s disk buffer and one sync per second instead of a sub-second queue synced per block; alert when a recording stops on its own |
| 0.3.5 | Insertion into empty fields that report no cursor (Discord, Paseo) |
| 0.3.6 | Other apps' media paused during a dictation (transient audio focus) |
| 0.3.7 | Dictation: automatic software gain so soft speech close to the microphone is picked up |
| 0.3.8 | Dictation: microphone opened first on tap, 5 s audio queue, content-free diagnostic line. Meeting: cancellation cause recorded, French states, readable durations. Host-tested only |
| 0.3.9 | « Mes mots »: user hotwords (beam search) and replacements, dictation and meeting; models downloaded per file from an immutable Hugging Face revision. Prepared, not released |

## Agreed scope

- French and English; local processing, no cloud, paid API or analytics. Models prepared explicitly, never bundled in Git or the APK.
- Dictation up to 15 minutes (typical use: 5 to 10), not ended by silence: bubble, user start/stop, then insertion at the cursor; overlay and accessibility accepted, the user's keyboard kept. Several dictations in a row, in as many apps as possible.
- **Never lose a dictation**: on failure, automatic copy to the clipboard (accepted even though it replaces the previous clip) and history in the app.
- Separate mode: meeting or long spoken notes up to 2 hours, recording then a complete local transcript (FR/EN first, NL too). No in-app summary: the user copies or exports the transcript to the LLM of their choice.

Linux desktop (12 November 2026): same engine and principles, a configurable global shortcut instead of the bubble (press to start, press to stop and paste), recordings kept in the history, adjustable microphone, paste through clipboard + Ctrl+V (a selection in the target is replaced). Dictation first; meeting mode later. See [linux.md](linux.md).

See the [scope](mvp.md), [architecture](architecture.md), [insertion](insertion.md), [CI](ci.md) and [phone test plan](acceptation-pixel.md).

## Branches

- **`main`**: published dictation and meeting (see releases).
- **`work/dictation`**: merged into `main`.
- **`work/meeting`**: meeting mode wired onto the `main` architecture. Merged into `main` on 3 November 2026 after 168 JVM/Robolectric tests, lint and debug/release builds.
- **`work/meeting-core`**: earlier meeting core, kept in the private archive only; superseded by `work/meeting`.

## Open review points

1. Insertion: the remaining risks listed in `insertion.md` (composition set before input started and never reported, web fields, recreated editors, Android 29–32).
2. Microphone FGS armed from a visible activity, microphone closed at rest, stop/revocation/mute/notification reachable. No service silently recreated from an old command. Applies to both `DictationService` and `MeetingService`.
3. Meeting: background recording endurance (screen off, lock, phone call, another app taking the microphone), post-recording transcription time, RAM, heat and battery on the Pixel.
4. Dependencies (Kotlin 2.1.0, Compose BOM 2025.01.01, Robolectric 4.14.1, target SDK 35…) are pinned but about two years old. Upgrade only after the next phone session, one group per commit; raising `targetSdk` or changing sherpa-onnx needs a maintainer decision and its own phone check. An automated update notifier (Dependabot or Renovate) is a maintainer decision.

## Validation

See [ci.md](ci.md#local-build): JVM/Robolectric tests, lint, debug and release builds, Python release-tooling tests. About 6 minutes per full build on a small VPS; avoid parallel builds when RAM is limited.

## Not verified on a phone yet

Everything listed in the next phone session; insertion in apps other than Keep and one messaging app, web fields, English quality, 15-minute dictations, keeping the FGS/microphone across app switches and screen lock, airplane mode then return of connectivity, memory, latency, battery and heat, Android 29–32; in meeting mode, a full 2-hour recording, processing with the screen off, Dutch, export and deletion. Host tests are never presented as phone results.
