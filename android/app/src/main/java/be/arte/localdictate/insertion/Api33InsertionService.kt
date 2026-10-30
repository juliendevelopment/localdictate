package be.arte.localdictate.insertion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi

/** Enabled by versioned resources only on API33+. The API29 service never exposes these types. */
@RequiresApi(33)
class Api33InsertionService : InsertionService() {
    override fun onCreateInputMethod(): InputMethod = GuardedInputMethod(this)
}

/** Passive bookkeeping only. Never commit, finish composition, or move selection in callbacks. */
@RequiresApi(33)
internal class GuardedInputMethod(service: AccessibilityService) : InputMethod(service) {
    internal data class State(val generation: Long = 0, val known: Boolean = false,
        val start: Int = -1, val end: Int = -1, val composingStart: Int = -1, val composingEnd: Int = -1)
    @Volatile internal var observed = State()
        private set
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        observed = State(generation = observed.generation + 1)
    }
    override fun onFinishInput() { observed = State(generation = observed.generation + 1) }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int) {
        observed = observed.copy(known = true, start = newSelStart, end = newSelEnd,
            composingStart = candidatesStart, composingEnd = candidatesEnd)
    }
}
