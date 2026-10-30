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
            override fun canCommit(start: Int, end: Int): Boolean {
                val range = method.observed
                return isCurrent() && range.known && range.start == start && range.end == end &&
                    range.composingStart == -1 && range.composingEnd == -1
            }
            override fun surrounding(): EditorSurrounding? {
                val context = connection.getSurroundingText(DirectContextPolicy.MAX_FIELD_LENGTH,
                    DirectContextPolicy.MAX_FIELD_LENGTH, InputConnection.GET_TEXT_WITH_STYLES) ?: return null
                val text = context.text
                val range = method.observed
                val composing = !range.known || range.composingStart != -1 || range.composingEnd != -1 ||
                    (text is Spanned && (
                    (text is Spannable && (BaseInputConnection.getComposingSpanStart(text) >= 0 ||
                        BaseInputConnection.getComposingSpanEnd(text) >= 0)) ||
                    text.getSpans(0, text.length, Any::class.java).any {
                        text.getSpanFlags(it) and Spanned.SPAN_COMPOSING != 0
                    }))
                return EditorSurrounding(text.toString(), context.selectionStart, context.selectionEnd,
                    context.offset, composing)
            }
            override fun commit(text: String) = connection.commitText(text, 1, null)
        }
    }
}
