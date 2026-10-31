# Inserting text at the cursor — design, field tests and diagnosis

This is the reference on automatic insertion of dictated text. It records the maintainer's tests on a Pixel on 30–31 October 2026 (v0.1.0 to v0.1.7), the analysis of their failures and the rules adopted in v0.1.8. **Read it before touching `insertion/` or the end of a session in `DictationService`.**

## Expected flow

1. Arm the bubble once from the app.
2. In any app: place the cursor, tap the bubble, speak (up to 15 minutes, often 5 to 10), tap **✓**.
3. The text appears at the cursor; the bubble is immediately ready for the next dictation, without going back to the app.
4. If something goes wrong, the text is in the clipboard (long press → Paste) and in the app (last 5 texts). **A dictation must never be lost**: repeating 10 minutes of speech is not acceptable.

## Field-test log (Google Keep, Gboard)

| Version | Observed on the phone | Message shown by the app | Cause | Change |
|---|---|---|---|---|
| 0.1.0 | Play Protect blocks installation, then accessibility is denied | "App blocked…", "App was denied access" | Sideloaded app asking for accessibility | Pause Play Protect scanning; "Allow restricted settings" (see below) |
| 0.1.0 | Bubble not found | — | The bubble appears only after « Armer la bulle » | Explained; no code change |
| 0.1.0 | Never inserted in Keep | "accessibilité/cible indisponible" | No reason shown | 0.1.1 shows the reason |
| 0.1.1 | Same | "éditeur différent du champ (type champ=704513 éditeur=2801665)" | EditorInfo adds a flag (0x200000) to the input type | 0.1.2 compares input class + variation only |
| 0.1.2 | **Inserted**, but bubble stuck on « Ouvrir le texte » | "Insertion demandée mais non confirmée" | Single read-back at 350 ms invalidated by Keep/Gboard events | 0.1.3 reads back 6 × 250 ms; confirmed insertion frees the bubble |
| 0.1.3 | Not inserted (note already containing text) | "Cible ou éditeur absent, modifié ou incompatible" | Several refusals behind one message | 0.1.4 details every refusal; Copy no longer hides the reason |
| 0.1.4 | Inserted, not confirmed | "(fenêtre ou focus modifié)" | Focus event right after the commit | 0.1.5 confirms by reading the field |
| 0.1.5 | Not inserted (10 s dictation) | "invalidée : événement 4194304 de null" | `TYPE_WINDOWS_CHANGED` (keyboard, bubble) | 0.1.6 ignores it; automatic copy; history of 5 texts |
| 0.1.6 | 1st dictation inserted, 2nd not | "invalidée : événement 32 de com.android.systemui" | System UI window (the foreground notification updated by the dictation itself) | 0.1.7 ignores events from other apps |
| 0.1.7 | Several dictations, none inserted; copy and history OK | "Éditeur non observable, texte en composition ou contexte incompatible" | See analysis: "unknown" composition refused | 0.1.8: redesign below |
| 0.1.8 | **Inserted reliably**: several dictations in a row in Keep and in a messaging app | — | — | Confirmed by the maintainer |

What worked on the phone early on: update-in-place with the same key, Parakeet + Silero preparation, good French recognition (punctuation, capitals), draggable bubble, real insertion in Keep, automatic copy and history.

## Why the fixes went in circles

The initial design required **a frozen world** between start and stop, i.e. for seconds to minutes: same node and same editor session (same generation), no window/focus/text event, same text and selection, and a composition state already reported by the keyboard. On a real phone these signals change harmlessly during a dictation: keyboard and bubble windows change, the foreground notification is updated at each phase, Keep and Gboard emit focus/window events, the editor may restart input. Each version lifted one of those conditions and revealed the next: symptoms of one design error, not independent bugs.

The last refusal (0.1.7) shows it. The accessibility editor learns the selection and composition only through `onUpdateSelection`, called **only when they change**. After a simple tap in the field nothing changes afterwards: the state stayed "unknown", treated as a possible composition, hence refused. The first dictation sometimes passed because the cursor had moved just before.

## Rules since v0.1.8

One decision, taken at stop time, on fresh reads, limited to what actually protects the text (`InsertionService.insert`, `DirectContextPolicy.plan`):

1. **At start**, only *which field* the user chose is recorded: node, app, window, view id, class. Password field, our own app, non-editable field or more than 32,768 characters: no target (the dictation still runs; fallback at stop).
2. **Accessibility events decide nothing** (`onAccessibilityEvent` is empty).
3. **At stop**: the focused field must be the same node, in the same app and window, editable, not a password. Its text may have changed (insertion at the current cursor).
4. The editor is acquired **at that moment** (restarted input on the same field is normal) and must match the field (app, input class + variation, view id).
5. One complete `getSurroundingText` read: text, length and selection identical to the node's, offset 0.
6. **Non-empty selection → refused**: nothing is ever replaced.
7. **Gboard composition**: `commitText` replaces the composing region. If its position is known (`onUpdateSelection`) and it contains the cursor, the composed word is re-sent with the dictation inside it: every existing character stays. Composition away from the cursor, or a composing marker without a position: refused. No report since the input started = no composition set since then: insert.
8. The field is checked again right before the send, **a single send** (attempt persisted in the journal first), then read back: same field with exactly the expected text → confirmed.
9. **End of dictation** (`DictationService`): the text goes to the history **before** any attempt. Confirmed → journal cleared. Otherwise → automatic copy to the clipboard (replacing the previous clip, explicitly accepted by the maintainer), journal cleared once the history is written, bubble ready with the notice « Non inséré • texte copié, collez-le ». Interrupted dictation: journal kept as before. Since v0.2.0, ✕ on the bubble cancels: nothing inserted or copied, text kept in the history.

These rules replace the earlier ones (unknown composition refused, invalidation by events, frozen editor session, replacement of the selection).

## Remaining risks, to check on a phone

- Assumption behind rule 7: a composition existing *before* the input started and never reported afterwards. Keyboards end their composition when input finishes; if a word ever disappeared after an insertion, this would be the case. The read-back would then report "not confirmed", with the text copied.
- Web fields (browser), apps with custom editors, RecyclerViews recreating the field (different node → copy fallback). Only Google Keep and one messaging app were tried.
- Long dictations (5–10 min): one long text tried successfully; memory, heat and battery not measured.
- Android 29–32: no direct insertion (copy fallback), not checked on a device.

## Reading a refusal message

Messages are in French, like the app. None contains text from the field: only lengths, positions and package names.

| Message starts with | Meaning |
|---|---|
| « Pas de champ compatible au démarrage (…) » | When the bubble was tapped, no usable field (reason in brackets) |
| « Insertion refusée sans rien modifier dans le champ. (…) » | At stop: other field/app, selected text, no editor, composition away from the cursor… |
| « Éditeur non observable ou contexte incompatible : insertion annulée (… ; éditeur : …) » | Field and editor disagree; `éditeur :` gives known/selection/composition |
| « Insertion demandée mais non confirmée (…) » | Sent, but the read-back differs: check the field before pasting (risk of duplicate) |

## Installing on a Pixel

- Download the APK from the GitHub release `android-vX.Y.Z` (not a CI `app-debug.apk`).
- Play Protect may block the APK ("App blocked to protect your device", no install button): Play Store → profile picture → Play Protect → settings gear → turn off scanning while installing, then turn it back on.
- Accessibility denied ("App was denied access"): App info → ⋮ menu → **Allow restricted settings**, then enable the service again. The overlay permission may need the same.
- Each new version installs over the previous one and keeps the models.
