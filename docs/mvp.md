# Scope

## Agreed requirements

- French and English first (French often contains English terms), Dutch too; no translation.
- The user grants "display over other apps" and accessibility.
- Dictation of up to **15 minutes**, not ended by pauses; explicit stop, then insertion at the cursor, keeping the current keyboard.
- A separate mode to record a **meeting or long spoken notes of up to 2 hours**, then transcribe it into a complete plain transcript.
- Local models, no subscription or paid API; an initial model download is allowed, all content stays on the phone.

**No summary in the app** (maintainer decision, 3 November 2026): the in-app extractive summary of v0.3.0–v0.3.1 was confusing and has been removed. The user copies or exports the transcript (plain text or Markdown) and asks the LLM of their choice for a summary; that export is an explicit user action, nothing is sent by the app.

## Delivery order

1. **Foundation**: repository, Android project, distinct presentation of both modes, limits and tests, CI on `main`.
2. **Working dictation**: prepare a verified FR/EN model, capture in a foreground service, segment without losing words, stop cleanly; bubble and safe insertion with an explicit fallback.
3. **Dictation hardening**: interruptions, denied/revoked permissions, target changes, text recovery, 15 minutes on a phone, memory/latency/heat/privacy.
4. **Meeting**: durable capture bounded in memory, pause/stop, progressive transcript kept on disk, explicit local export; validation of a 2-hour session.
5. **Meeting transcript quality**: as complete a transcript as possible for long spoken notes (FR/EN, then NL), plain-text copy and Markdown export for an external LLM.

Both modes are in scope; dictation comes first. An in-app summary is out of scope unless the maintainer decides otherwise.

## Current implementation

The real dictation chain is wired: Parakeet/Silero preparation, microphone service armed from the visible activity, bubble, capture/segmentation/recognition, private transcript and conditional insertion. The meeting mode is wired (sequential capture then transcription, copy and export, see [meeting.md](meeting.md)) but not verified on a phone. The boxes below are **phone** criteria: JVM fixtures do not tick them.

Automatic insertion uses the accessibility editing connection on API 33+, without replacing the keyboard. At stop time it requires the same focused field, a fully observable context and a collapsed cursor, and it keeps any word being composed by the keyboard; nothing is replaced. Otherwise (and on API 29–32) the text is copied to the clipboard and stays in the history of the last 5 dictations; the bubble stays ready. Details and field tests: [insertion.md](insertion.md). See also the [architecture](architecture.md) and the [phone test plan](acceptation-pixel.md). Audio still in RAM is lost if the process dies.

## Dictation acceptance

- [x] A non-focusable bubble keeps the input target while recording is controlled (Pixel, v0.2.0).
- [ ] Microphone permission and microphone foreground service, visible notification/stop; nothing starts when the app is opened.
- [x] FR recognized by a real model on the phone (Pixel). EN and the absence of network calls during use are not verified on the phone yet.
- [ ] A pause does not end the session; reaching the limit stops capture, then finalizes the accepted text without silent truncation.
- [ ] Insertion at the cursor; a selection is never replaced; the rest of the field is kept.
- [ ] Password/incompatible field, changed target or closed app: nothing overwritten, text recoverable, explicit fallback.
- [ ] A 15-minute session stays bounded in RAM and loses no accepted segment.
- [ ] Real checks on the phone in several native apps and a web field; no "works in every app" promise.

## Meeting acceptance

- [ ] Deliberate start and visible indicator; the user handles participants' consent.
- [ ] 2-hour capture, private files written progressively, safe stop on low storage/interruption.
- [ ] Transcript kept on any failure; explicit copy/export/deletion.
- [ ] No pasting into another app, and no speaker identification promised without implementation and tests.
- [ ] Complete transcript: every spoken passage present, readable FR/EN (and NL) text, plain-text copy and Markdown export usable as LLM input.
- [ ] Real endurance, storage, RAM, heat and processing-time test on the phone.

## Privacy limits

The absence of analytics and the inspection of dependencies/manifest are static evidence. Airplane mode alone does not prove the absence of deferred collection: network tests must also watch the return of connectivity. Host tests are never phone measurements.
