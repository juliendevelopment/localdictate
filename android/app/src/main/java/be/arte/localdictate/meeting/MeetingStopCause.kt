package be.arte.localdictate.meeting

import java.util.concurrent.atomic.AtomicReference

/** Why a meeting run was stopped before the end, saved in the session diagnostic (no content, French UI text). */
enum class MeetingStopCause(val message: String) {
    USER_APP("Traitement annulé depuis l’application."),
    USER_NOTIFICATION("Traitement annulé depuis la notification."),
    SERVICE_DESTROYED("Service arrêté par Android (application fermée, mémoire ou batterie) avant la fin."),
    FOREGROUND_TIMEOUT("Délai foreground Android atteint ; fichiers conservés."),
    PROCESS_LIMIT("Délai de traitement maximal atteint (6 h). Relance explicite possible."),
}

/** First cause wins: stopSelf() after a timeout also destroys the service, which must not hide the timeout. */
class FirstStopCause {
    private val cause = AtomicReference<MeetingStopCause?>(null)
    val value: MeetingStopCause? get() = cause.get()
    fun record(stop: MeetingStopCause) { cause.compareAndSet(null, stop) }
    /** Diagnostic text for a run that ended cancelled. */
    fun describe(): String = value?.message
        ?: "Traitement interrompu sans cause enregistrée (annulation interne) ; à signaler."
}
