# LocalDictate for Linux

A port of the Android dictation to the Linux desktop (`linux/`), same engine and same principles: local
FR/EN recognition, an explicit model preparation as the only network use, nothing lost. The interface is in
French. **Status: host-tested only** (see [verification](#verification)); nothing has been tried on a real
desktop with a microphone yet.

## Maintainer decisions (12 November 2026)

- **A configurable global shortcut replaces the bubble.** One press starts the dictation, the next press
  stops it and pastes the text; the key is not held while speaking. A second, optional shortcut cancels
  (text kept in the history, not pasted).
- **Recordings are kept**: each dictation keeps its WAV next to its transcript in the private history (the
  Android dictation never writes audio). The recording can be played back and transcribed again with the
  current microphone settings.
- **Microphone sensitivity and input device are adjustable** (gain, automatic gain, speech detection
  threshold, live level test).
- **Paste = clipboard + Ctrl+V.** Linux offers no reliable, general way to read the focused field, so the
  Android rule "never replace a selection" cannot be enforced: a selection in the target is replaced, as with
  a manual paste. The text is always in the history first, and stays in the clipboard.
- **Targets: GNOME and KDE on Wayland through the XDG portals**, with a fallback command
  (`localdictate --toggle`) to bind in the desktop's own shortcut settings.
- **Dictation first.** The meeting mode is not ported yet.
- **Music paused during a dictation** (added the same day): MPRIS players that were playing are paused after
  the microphone opens and resumed when it closes.
- **Status icon** (added the same day): in the KDE system tray and the GNOME top bar, showing *au repos*,
  *écoute* or *transcription*, with a menu to the window, the settings and the folder holding the recordings
  and texts.

## Using it

```sh
git clone https://github.com/juliendevelopment/localdictate && cd localdictate/linux
./install.sh     # per-user, no root; prints the distribution packages to install if some are missing
```

Requirements: Python 3.12–3.14, PyGObject, GTK 4, libadwaita, GStreamer with `plugins-base` and the
PipeWire (or PulseAudio) source. `install.sh` creates a private virtual environment
(`~/.local/share/localdictate-app`) with the hash-pinned wheels of `requirements.lock` (numpy, sherpa-onnx),
copies the code, and adds the `localdictate` command, a menu entry and an icon. `uninstall.sh` removes the
program and keeps the data (`--all` deletes everything).

1. Open **LocalDictate**, tap **Préparer** (about 488 MB, once; 1.5 GB free needed).
2. Accept the shortcut proposed by the desktop (default suggestion `CTRL+ALT+d`, editable in the window).
3. In any app, put the cursor in a text field, press the shortcut, speak, press it again. The first paste
   asks the desktop once for virtual keyboard and clipboard access (« Autoriser » in the settings does it
   in advance).
4. The status icon shows the state: blue = au repos, red = écoute, orange = transcription. Its menu starts or
   stops a dictation, opens the window, the settings, or the folder of the dictations (one subfolder per
   dictation with `audio.wav` and `transcript.txt`). On GNOME the icon needs the "AppIndicator and
   KStatusNotifierItem Support" extension (shipped by Ubuntu; on Fedora
   `sudo dnf install gnome-shell-extension-appindicator`, then enable it in Extensions). The window says
   when no tray is found.
5. The window can be closed: the app keeps running in the background. « Lancer à l’ouverture de session »
   starts it hidden at login, with the model already in memory.

Command line: `localdictate` (window), `--toggle`, `--cancel`, `--background`, `--quit`, `--version`. The
first instance owns everything; later invocations are forwarded to it (GApplication).

## Architecture

```
GlobalShortcuts portal / `localdictate --toggle`
  → Controller (main loop) → Capture (GStreamer → S16LE 16 kHz mono, own thread)
  → DictationPipeline: WAV writer (raw) → InputChain (gain) → PCM queue (≤ 60 s)
  → SileroSegmenter (sherpa VAD, padding, splitting) → segment queue (8) → Recognizer (Parakeet)
  → history entry (transcript rewritten atomically per segment)
  → Paster: RemoteDesktop + Clipboard portals (clipboard, then Ctrl+V)
```

| Module | Role |
|---|---|
| `dsp.py` | `VoiceGain`, `SegmentPadder`, `split_lengths`, `PcmFramer` ported from Android; manual gain |
| `engine.py` | sherpa-onnx 2.0.1 Python binding: Parakeet TDT 0.6B v3 int8 (`nemo_transducer`, greedy) and Silero (threshold from the settings, 0.4 s silence, 0.25 s min speech, 20 s max) |
| `pipeline.py` | Threaded port of `DictationPipeline`: 15-minute budget, bounded queues, overload stops the microphone and drains accepted audio, content-free diagnostic line |
| `capture.py` | GStreamer device list (`DeviceMonitor`, Audio/Source, monitors excluded) and capture; state changes on a worker thread (a missing PipeWire source blocks 30 s) |
| `history.py`, `wav.py` | Private history with WAV + transcript + metadata; crash recovery |
| `models.py` | Same pinned artifacts and checks as Android (HTTPS, resume, Content-Range, allow-listed tar.bz2, quotas, atomic install, versioned marker) |
| `portals.py`, `paste.py` | Portal clients (Registry, GlobalShortcuts, RemoteDesktop, Clipboard) and delivery with fallbacks |
| `media.py` | MPRIS pause/resume of other players |
| `tray.py` | StatusNotifierItem + DBusMenu (no libappindicator), icon names plus generated pixmaps |
| `controller.py` | States, press/press logic, 5 s no-audio watchdog, finalisation, re-transcription, preparation, level test |
| `app.py`, `window.py` | Single-instance `Adw.Application`, notifications, French window (Dictée, Historique, Réglages) |

### Audio and recognition

- The microphone is opened right after the press. The history entry and WAV file are created just before
  (a few milliseconds), so no captured block is missing from the recording.
- If the model is still loading (about 10 s after login), audio waits in the PCM queue (up to 60 s).
- Gain chain, applied to what Silero, Parakeet and the level meter see: manual gain (−12 to +30 dB, clipped),
  then optionally `VoiceGain` (up to +18 dB, never attenuates, no clipping). The WAV keeps the **raw**
  audio, so a re-transcription applies the current settings.
- The speech detection threshold (0.1–0.9, default 0.5) is Silero's probability threshold; lower is more
  sensitive. A pause segments and never ends the session; only the shortcut, the 15-minute limit or a
  failure does.
- No audio for 5 s is reported as a failure, not as silence.

### Other players' media

After the microphone start (asynchronous, never delays it), every `org.mpris.MediaPlayer2.*` name whose
`PlaybackStatus` is `Playing` receives `Pause`. When the microphone closes (stop, cancel, limit, failure),
only those players are sent `Play`, and only if they are still `Paused`: a player the user stopped or
restarted meanwhile is left alone. If the dictation ends before a pause completes, the player is resumed at
once. Players without MPRIS keep playing. Switch: Réglages › Pendant la dictée.

### Status icon

`tray.py` exports `org.kde.StatusNotifierItem` at `/StatusNotifierItem` and `com.canonical.dbusmenu` at
`/MenuBar`, owns `org.kde.StatusNotifierItem-<pid>-1` and registers it with `org.kde.StatusNotifierWatcher`,
again whenever the watcher reappears (Plasma restart, extension enabled). The icon has a name from the
installed hicolor theme (`be.arte.LocalDictate-idle|listening|processing`) and generated ARGB pixmaps
(22/32/48 px) as a fallback. Left click opens the window; the menu holds the state line, start/stop,
cancel (while listening), the window, the settings, the dictations folder and quit. The status is always
`Active` so the icon is not hidden in an overflow.

### Delivery (paste)

1. The text is already in the history (rewritten after each segment).
2. A keyboard-only RemoteDesktop session with clipboard access is started on the first paste: the desktop
   asks once; `persist_mode=2` and the restore token (stored in the settings, rotated at each start) avoid
   asking again. The session stays open while the app runs, because it owns the clipboard: closing it would
   empty the clipboard.
3. `SetSelection` offers the text; the target app pulls it through `SelectionTransfer`/`SelectionWrite`.
4. After 150 ms (shortcut keys released), `NotifyKeyboardKeysym` sends Ctrl+V (or Ctrl+Shift+V for
   terminals, or Shift+Insert, chosen in the settings).

Fallbacks: no Clipboard portal → `wl-copy` on non-GNOME Wayland (GNOME only lets a focused window own the
clipboard), the GTK clipboard on X11; no RemoteDesktop portal → `xdotool` on X11; otherwise the text is
copied and a notification asks for a manual paste. A failed or cancelled dictation is never pasted
automatically. The clipboard is never read.

### Shortcut

At startup the app registers its ID with `org.freedesktop.host.portal.Registry` (before GTK's own portal
calls), creates a GlobalShortcuts session and binds `toggle` (with the preferred trigger) and `cancel`
(no default). The desktop shows its own dialog to confirm or change the keys; « Modifier… » opens it again
(`ConfigureShortcuts`, portal version 2) or rebinds with the new preferred trigger. Without the portal
(GNOME < 48, X11, others) the window explains how to bind `localdictate --toggle` as a custom shortcut.

## Privacy

Everything lives under `~/.local/share/localdictate` (models, history) and `~/.config/localdictate`
(settings), directories 0700, files 0600. No logs of text or audio, no analytics, no network except
« Préparer ». The diagnostic line holds numbers only and is not persisted. The history keeps the last 50
dictations by default (1–1000), about 1.9 MB of audio per minute; « Tout effacer » and per-entry deletion
remove files immediately. Recordings are not encrypted at rest beyond what the user's disk provides.

## Verification

Host evidence (VPS without a desktop, microphone or speakers), 12 November 2026:

- 52 unit tests (`python -m unittest discover -s tests -t .` from `linux/`): DSP ports, WAV and crash repair,
  history, settings, model download/extraction including malicious archives, pipeline (order, limit,
  overload, failures, concurrent stop), controller (press/press, cancel, model loading, microphone failure,
  recognition failure, watchdog, re-transcription, pruning) with test doubles.
- Real engine (`LOCALDICTATE_TEST_MODELS=…`, the pinned files): French, 1 s pause, English through the real
  pipeline in 0.1 s blocks, both sentences recognized in two segments; soft speech (−26 dB) recognized with
  the automatic gain.
- Portal clients against a fake portal on a private D-Bus: shortcut binding and activation, paste session
  (restore token, `persist_mode`, clipboard before Start), clipboard served through the file descriptor,
  Ctrl+V keysyms, session reuse. This checks signatures and flow, **not** GNOME's or KDE's behaviour.
- MPRIS pause/resume against three fake players (playing, paused, stopped), including a player stopped by
  the user during the dictation and a dictation shorter than the pause call.
- Status icon against a fake watcher/host: registration, properties, pixmap sizes, icon and menu changes per
  state, menu events and Activate. The real app registered with a fake watcher under Broadway and the
  settings showed "Affichée dans la barre".
- GStreamer capture with `audiotestsrc` (16 kHz mono blocks); default source failing without blocking.
- The application started under GTK Broadway with the real model ("Prêt"), screenshots of the three tabs;
  `localdictate --toggle` forwarded to the running instance; `install.sh` in a throwaway HOME.

**Not verified**: a real desktop session, a microphone, the GNOME/KDE shortcut and paste dialogs, paste in
real apps (GTK, Qt, Firefox, Chromium/Electron, terminals), the icon as drawn by Plasma and by the GNOME
extension, the music pause with real players, the GNOME screen-sharing indicator possibly shown
while the paste session is open, notifications, autostart, a 15-minute dictation, memory and CPU use.

## Desktop test plan (next action)

On the maintainer's Linux desktop, from a clone of `main`:

1. `./install.sh`, open LocalDictate, « Préparer ». Note the desktop and version (GNOME/KDE, Wayland/X11).
2. Réglages › Microphone: pick the microphone, « Tester », set the gain so speech moves the bar clearly.
3. Accept the shortcut dialog; note the shortcut shown in the window. If no dialog appears, note the
   message under « Démarrer / arrêter ».
4. In a text editor: shortcut, a French sentence, shortcut. Expected: the consent dialog once, then the
   text pasted. Report the « Dernière dictée » diagnostic and the message under the state.
5. Repeat in Firefox, a chat app, a terminal (with Ctrl+Maj+V selected), and with text already selected.
6. Check the top bar: is a sharing/remote-control indicator shown permanently after the first paste?
7. Historique: play a recording, « Retranscrire », delete one entry. Tray menu › « Dossier des dictées »:
   the folder opens with one subfolder per dictation.
8. Status icon: blue at rest, red while dictating, orange while transcribing; menu entries work. On GNOME,
   with the AppIndicator extension enabled.
9. Music in Spotify/a browser: paused at the first press, resumed after the second; not restarted if you
   stopped it yourself during the dictation.
10. A 5–10 minute dictation with pauses; then start one and quit LocalDictate while recording, reopen: the
   entry must be in the history with its audio.

## Not done yet

Meeting mode, weekly statistics, packaging (Flatpak, RPM/DEB), Dutch checks.
