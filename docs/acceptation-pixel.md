# Manual phone test plan (Pixel)

Install a signed release APK. Record Android version, device, APK SHA, duration, target app and result. Never write private content into test logs. Results already obtained are logged in [insertion.md](insertion.md) and [status.md](status.md); unticked boxes are not verified.

- [ ] Fresh start: no microphone indicator, service, download or permission prompt starts by itself. No meeting recording starts by itself.
- [x] Explicit preparation completed on the phone (v0.1.0). Still to test: progress display, cancel mid-transfer, resume after a network cut, insufficient space/error, restart offline after a complete install.
- [ ] Deny/grant microphone, notifications, overlay and accessibility; coming back from settings never arms automatically. Revoke during arming/capture/processing.
- [x] Arm from the visible screen; in another app, tapping/dragging the bubble does not steal focus (v0.2.0). Still to test: notification shown immediately, microphone closed while loading and waiting; on API 30+, capture marked privacy-sensitive and capture conflicts/silencing reported.
- [ ] FR then EN, automatic language, punctuation; long pauses do not end the session, explicit stop, full drain. Measure quality and delay (FR works on the Pixel; quality not measured).
- [ ] 15 minutes with pauses and continuous speech: microphone stops exactly at 900 s captured, no full PCM in RAM, final segments processed; measure peak RAM, lag, heat, battery and final time. Screen off/lock, phone call, another app using the microphone.
- [ ] Force an overload: microphone stops immediately, error visible in bubble/notification/app; accepted text drained, no silent loss and no automatic insertion of an incomplete result.
- [ ] Native notes field, messaging and a web field: cursor in the middle/start/end; text outside the cursor untouched. Check the observed result, do not confuse dispatch with insertion.
- [x] Only the LocalDictate accessibility service is enabled; Gboard stays the current keyboard (Pixel). The limited service edits nothing outside an explicit stop.
- [ ] Copy a witness text, make an **inserted** dictation without ACTION_PASTE: clipboard witness **strictly unchanged**. Make a **non-inserted** dictation (e.g. with selected text): the dictation replaces the witness in the clipboard (deliberate choice), bubble ready, text listed in « Derniers textes dictés ».
- [ ] Start a word with Gboard without committing it, then dictate: the composed word stays intact and the dictation is inserted next to it (or copied), never a lost word. Also: composition starting during processing, truncated/long context, emoji before the cursor (UTF-16 indices).
- [ ] Android 29–32 and refused direct connection: no ACTION_PASTE attempt; text copied, sensitive flag without any promise of access protection. On an old device, check the common service loads and the API 33 service is not enabled.
- [x] Several dictations in a row in the same field (Keep) without touching the screen in between: all inserted (v0.1.8). Still to test: commit without observable result or with a wrong cursor reports "not confirmed" with no second attempt; double stop sends once; switching app during dictation falls back to copy.
- [ ] Switch app/window/field during dictation and come back; close the target; password/visible password field; disable accessibility: no blind paste, text always available.
- [ ] Rotation/activity recreation during recording/processing: no second session, no double insertion. Kill the process/service and reopen: already recognized text recovered as partial, no automatic microphone/bubble restart or paste.
- [ ] After preparation: airplane mode, then firewall/network observation during arming, capture, processing, recovery and return of connectivity. No upload, analytics or deferred telemetry. Airplane mode alone does not prove the last point.
- [ ] Dictation diagnostic (v0.3.8): after each dictation a « Diagnostic : … » line under « Durée capturée » gives the delay from the tap to the first audio, the peak level, the gain, the segments and the decode time, and no text. A short phrase spoken right after the tap is complete; note the delay.
- [ ] Soft, normal and loud voice in one dictation (v0.3.7): all recognized; if soft speech is missed, note the peak and segment count of the diagnostic.
- [ ] Media playing (music, YouTube, podcast): pauses on the tap, resumes after ✓ and after ✕; nothing starts when nothing was playing (v0.3.6). Note any player that ignores it.
- [ ] Weekly statistics: a dictation increments the current week; its duration matches « Durée capturée » (v0.2.3).
- [ ] « Mes mots » (v0.3.9): dictate a sentence with a word it usually gets wrong, note the result; add the word on its own line, « Enregistrer les mots », dictate again. Then a `entendu => voulu` line for an error that comes back the same way. Note whether the diagnostic's decode time grew noticeably compared with v0.3.8, and any word that appears where it was not said.
- [ ] « Préparer » on a phone or profile without the model (v0.3.9): about 670 MB in five files, ends « Modèles prêts »; after the update, a phone that already had the model shows « Modèles prêts » with no download.
- [ ] New bubble (v0.2.0): drag onto ✕ hides it, waking/unlocking shows it again, « Afficher la bulle » from the notification; ✕ during dictation inserts nothing and keeps the text in history.

## Meeting ([meeting.md](meeting.md))

- [ ] A run cancelled from the app, then one cancelled from the notification: each ends `CANCELLED` (shown « annulé ») with its own cause in the message (v0.3.8). « Retranscrire » on the 3784 s recording: read the final phase and cause.
- [ ] Short meeting (2–5 min, French): « Démarrer un enregistrement », notification shown, duration counting; stop from the app, then from the notification. Phases go `RECORDING` → `TRANSCRIBING` → `COMPLETE`; the transcript matches what was said, no invented text; « Copier la transcription » and the Markdown export give the same plain text.
- [ ] While a meeting records or processes, dictation cannot be armed, and the reverse.
- [ ] Screen off and locked during recording, then during processing: no gap in the audio (compare the WAV export), processing goes on.
- [ ] Interruptions: phone call, another app taking the microphone, revoke microphone, kill the app: session left `RECORDED`/`CANCELLED`/`ERROR` with a message, audio kept, « Transcrire » / « Retranscrire (nouveau journal) » resumes explicitly.
- [ ] 30–60 min, then 2 h: stop exactly at 7200 s, transcription time, peak RAM, heat, battery, free space before/after.
- [ ] Markdown and WAV export through the share sheet; deletion removes the meeting; nothing pasted into another app.

Host fixtures never count as passing any of these checks.
