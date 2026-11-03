# Meeting mode — sequential recording and local transcript

## What is shipped, and what is not

The « Réunion » tab records up to 2 hours of audio directly to app-private storage, then, **after stop**, transcribes it locally with the dictation engine (Parakeet + Silero). The main use is long spoken notes whose plain transcript is copied or exported as Markdown into another tool, typically an LLM that writes the summary. Everything stays on the phone; export is an explicit share of one file.

- Capture and transcription are **sequential**, not live: the microphone is closed before any model is loaded. Transcription takes extra time after the meeting (not measured on a phone yet).
- Language is detected by Parakeet TDT v3 for each decoded block, with no setting: French and English (including English terms inside French sentences) are the focus, Dutch is supported by the model. Quality per language is not measured on the phone.
- **No summary in the app.** v0.3.0–v0.3.1 had a local extractive summary (selected original sentences); it was removed in v0.3.2 by maintainer decision because it was confusing. Any summary is done by the user's own tool from the exported text. No speaker identification.
- No automatic pasting into another app. The user handles participants' consent (shown in the UI and notification).
- Host-tested only (JVM/Robolectric). **No 2-hour endurance, transcription time, RAM, heat, battery or screen-off/lock behaviour has been checked on a phone.**

## Flow

1. « Démarrer une réunion (FR) » is enabled only when the models are prepared, microphone and notification permissions are granted and no dictation, preparation or other meeting holds the shared usage lock (`UseGate`, `Use.MEETING`).
2. `MeetingController.reserve()` takes the lock and issues a one-shot launch token; `MeetingService` only starts with that token (no boot receiver, no sticky restart, no capture in `onCreate`).
3. The service goes foreground with type `microphone`, holds a partial wake lock and records through `MicrophoneCapture` into a `PcmRecording` spool. A bounded queue (32 blocks) separates the microphone from disk writes; if the writer falls behind, the microphone is closed and accepted blocks are drained, never silently dropped. Duration is persisted every second.
4. Stop (app button or notification action), the 2-hour cap, Android silencing the microphone or an error closes the microphone. Only an explicit stop chains into processing; any other end leaves the session in `RECORDED` with a diagnostic, and the user starts processing explicitly (« Transcrire et résumer »).
5. Processing (« Transcrire », or « Retranscrire » on a finished session, which writes a new journal and keeps the old one) switches the foreground type to `mediaProcessing` (API 35+) or `dataSync`, loads the models, reads the spool block by block through Silero and Parakeet, and appends each recognized block to a new `TranscriptJournal` with timestamps from the audio sample positions. The ASR handles are released as soon as decoding ends, then the session is `COMPLETE`.

   **Merging (`SpeechMerger`, v0.3.1).** Silero ends a segment after 0.4 s of silence, so spoken notes with thinking pauses came out as many fragments, each decoded without context: broken sentences, poor punctuation and lone hesitations heard as English words (« Just », « One », « Uh » on the first phone recording, where every voiced region did produce text). Consecutive segments separated by at most 3 s are now joined, back to back without the pause audio (the 0.3 s padding of each segment keeps a short silence at the join), into one decode of at most 20 s. The journal interval spans from the first start to the last end. Longer pauses still separate blocks.
6. Processing is bounded to 6 hours and can be cancelled; audio and already-recognized segments are kept.

Phases: `CREATED`, `RECORDING`, `RECORDED`, `TRANSCRIBING`, `COMPLETE`, `INTERRUPTED`, `CANCELLED`, `ERROR`. Out-of-memory, storage or native failures end in `ERROR` with the files kept. `SUMMARIZING` is only read from sessions saved by v0.3.0–v0.3.1 (shown as interrupted); their old `language`/`summary` properties are ignored and their summary file is removed with the meeting.

## Storage

« Copier la transcription (texte brut) » copies the current journal as sensitive clipboard content: segment texts joined by spaces, a new paragraph after a pause of 8 s or more, no timestamps or anchors, at most 200,000 characters (otherwise use the Markdown export). The Markdown export (`transcription-<uuid>.md`) is the same text, streamed without a size limit, under a short header (date, approximate duration, a note that the transcript is automatic and may contain recognition errors). Only the verified prefix is copied if the journal tail is damaged, and the message says so.

Each meeting lives in `filesDir/meetings/<uuid>/` (`MeetingRepository`): session metadata, the PCM spool, one or more `transcript-<uuid>.dat` journals (a new processing run creates a new journal, never overwrites one). Nothing is deleted automatically; « Supprimer » (in the list or on an opened meeting) deletes one meeting after a confirmation. Exports (Markdown transcript or WAV) are written to `cacheDir/meeting-exports/` and shared through a non-exported `FileProvider` with a read grant.

- **Audio spool** (`PcmRecording`): private `LDPCM001` header (32 bytes) then PCM16 little-endian, mono, 16 kHz. Hard cap of 115,200,000 samples (2 h, 230,400,000 bytes). Every append is synced (`FileChannel.force`). Creation uses `CREATE_NEW`; recovery of an interrupted spool is explicit and drops at most one half sample, which is reported. There is no per-block checksum.
- **WAV export** is a streamed copy, not a rename: at 2 h, spool and WAV need about 440 MiB together.
- **Transcript journal** (`TranscriptJournal`): `LDTXT001` header, then length-prefixed frames (`id`, `startMs`, `endMs`, UTF-8 text) with CRC32. Segments are ordered, non-overlapping, at most 100,000. A reader stops at the first unverifiable frame and reports it; tail repair is a separate explicit call.
- **Quotas** (`MeetingRepository`): at most 20 meetings and 2 GiB in total; a session is never evicted to make room. Starting a meeting requires room for a full 2-hour spool (about 221 MiB) plus a 64 MiB free-space margin, checked before each append and before a WAV export. This is a check, not a disk reservation.

## Files

Package `be.arte.localdictate.meeting`: `MeetingController` (state, lock, browse/export/delete), `MeetingService` (capture and processing), `MeetingPipeline` (capture queue, segment merger, transcriber), `MeetingRepository`, `MeetingStorage`, `PcmRecording`, `SavedPcm`, `TranscriptJournal`, `MeetingExport`. UI in `MainActivity` (« Réunion » tab).

Tests in `android/app/src/test/java/be/arte/localdictate/meeting/`: storage, PCM spool (including a synthetic 2-hour file streamed under a 64 MiB heap), journal corruption/recovery, segment merging, plain text and Markdown export, legacy sessions, and an integration test of capture → transcription with synthetic audio and a fake decoder. These are host tests: they prove neither JNI decoding nor phone endurance.

## Next checks on the phone

See [status.md](status.md#known-issues-and-next-steps) and the meeting section of the [phone test plan](acceptation-pixel.md).
