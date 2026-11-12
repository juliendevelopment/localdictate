package be.arte.localdictate

import be.arte.localdictate.meeting.MeetingPhase

/** A duration for the French interface: « 45 s », « 12 min 05 », « 1 h 03 min ». */
fun formatDuration(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min ${two(seconds % 60)}"
        else -> "${seconds / 3600} h ${two(seconds / 60 % 60)} min"
    }
}

/** A position in a recording: « 4:07 », « 1:02:09 ». */
fun formatClock(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds < 3600) "${seconds / 60}:${two(seconds % 60)}"
        else "${seconds / 3600}:${two(seconds / 60 % 60)}:${two(seconds % 60)}"
}

/** Every meeting phase in French; the enum names are storage values, never shown. */
fun meetingPhaseLabel(phase: MeetingPhase): String = when (phase) {
    MeetingPhase.CREATED -> "créé, pas encore enregistré"
    MeetingPhase.RECORDING -> "enregistrement local en cours"
    MeetingPhase.RECORDED -> "audio sauvegardé, prêt pour traitement"
    MeetingPhase.TRANSCRIBING -> "transcription locale"
    MeetingPhase.SUMMARIZING -> "interrompu (ancienne version)" // Only read from sessions saved by v0.3.0–v0.3.1.
    MeetingPhase.COMPLETE -> "terminé"
    MeetingPhase.INTERRUPTED -> "interrompu"
    MeetingPhase.CANCELLED -> "annulé"
    MeetingPhase.ERROR -> "erreur"
}

private fun two(value: Long) = value.toString().padStart(2, '0')
