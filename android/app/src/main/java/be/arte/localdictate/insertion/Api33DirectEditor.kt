package be.arte.localdictate.insertion

import android.accessibilityservice.AccessibilityService
import android.text.Spanned
import android.text.Spannable
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import androidx.annotation.RequiresApi

/** Loaded only behind SDK_INT >= 33. Uses a passive limited accessibility editor, never an IME switch. */
@RequiresApi(33)
internal object Api33DirectEditor {
    fun acquire(service: AccessibilityService): DirectEditorSession? {
        val method = service.inputMethod as? GuardedInputMethod ?: return null
        val generation = method.observed.generation
        if (!method.currentInputStarted) return null
        val info = method.currentInputEditorInfo ?: return null
        val connection = method.currentInputConnection ?: return null
        val viewId = if (info.fieldId > 0) runCatching {
            service.packageManager.getResourcesForApplication(info.packageName).getResourceName(info.fieldId)
        }.getOrNull() else null
        val identity = EditorIdentity(info.packageName.orEmpty(), info.fieldId, info.fieldName, info.inputType, viewId)
        if (identity.packageName.isBlank() || TargetPolicy.password(identity.inputType)) return null
        return object : DirectEditorSession {
            override val identity = identity
            override fun isCurrent(): Boolean {
                // AOSP creates a fresh public connection wrapper on each getter call. EditorInfo is
                // retained for the bound session and replaced on start/restart/invalidate, even for
                // the same field ID. Do not compare the wrapper instances or only field values.
                return service.inputMethod === method && method.observed.generation == generation && method.currentInputStarted &&
                    method.currentInputEditorInfo === info && method.currentInputConnection != null &&
                    EditorIdentity(info.packageName.orEmpty(), info.fieldId, info.fieldName, info.inputType, viewId) == identity
            }
            override fun canCommit(context: EditorSurrounding): Boolean {
                if (!isCurrent()) return false
                val range = method.observed
                // No selection report since the input started: nothing was composed since then.
                if (!range.known) return context.composingStart < 0 && context.composingEnd < 0
                return range.start == context.start && range.end == context.end &&
                    range.composingStart == context.composingStart && range.composingEnd == context.composingEnd
            }
            override fun surrounding(): EditorSurrounding? {
                val context = connection.getSurroundingText(DirectContextPolicy.MAX_FIELD_LENGTH,
                    DirectContextPolicy.MAX_FIELD_LENGTH, InputConnection.GET_TEXT_WITH_STYLES) ?: return null
                val text = context.text
                val range = method.observed
                // The keyboard reports each composition through onUpdateSelection. Unknown here means no report
                // since the input started, so no composition was set since then (sessions start without one).
                val placed = range.known && range.composingStart >= 0 && range.composingEnd >= range.composingStart
                val marked = text is Spanned && (
                    (text is Spannable && (BaseInputConnection.getComposingSpanStart(text) >= 0 ||
                        BaseInputConnection.getComposingSpanEnd(text) >= 0)) ||
                    text.getSpans(0, text.length, Any::class.java).any {
                        text.getSpanFlags(it) and Spanned.SPAN_COMPOSING != 0
                    })
                // Reports that disagree with the editor's own selection cannot locate a composition.
                val stale = range.known && (range.start != context.selectionStart || range.end != context.selectionEnd)
                return EditorSurrounding(text.toString(), context.selectionStart, context.selectionEnd,
                    context.offset, composing = (marked && !placed) || (stale && (placed || marked)),
                    composingStart = if (placed) range.composingStart else -1,
                    composingEnd = if (placed) range.composingEnd else -1)
            }
            override fun commit(text: String) = connection.commitText(text, 1, null)
            override fun describe(): String {
                val range = method.observed
                return "courant=${isCurrent()} connu=${range.known} sélection=${range.start}-${range.end} " +
                    "composition=${range.composingStart}-${range.composingEnd}"
            }
        }
    }
}
