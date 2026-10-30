package be.arte.localdictate.insertion

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** No traversal, history or event text harvesting. findFocus only for an explicit START/insertion. */
open class InsertionService : AccessibilityService() {
    private class Target(val node: AccessibilityNodeInfo, val state: TargetState, val editor: DirectEditorSession) {
        var invalidated = false
        var attempted = false
        var dispatching = false
    }
    private var target: Target? = null
    private val inserting = AtomicBoolean(false)
    internal var directEditor = DirectEditor {
        if (Build.VERSION.SDK_INT >= 33) Api33DirectEditor.acquire(this) else null
    }
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val captured = target ?: return
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> captured.invalidated = true
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                if (event.windowId == captured.state.windowId) {
                    // Only callbacks from our target may be ignored after our single commit.
                    // Window/focus/editor changes still invalidate while waiting for observation.
                    val ownCallback = captured.dispatching && event.packageName?.toString() == captured.state.packageName &&
                        event.source == captured.node
                    if (!ownCallback) captured.invalidated = true
                }
            }
        }
    }
    override fun onInterrupt() { forget() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (connected === this) connected = null
        forget()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        forget()
        super.onDestroy()
    }
    fun snapshot(): Boolean {
        if (inserting.get()) return false
        forget()
        // The active window can be our overlay; query input focus across interactive windows.
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!node.refresh()) return false
        val state = state(node)
        if (!TargetPolicy.eligible(state, packageName) || state.length > DirectContextPolicy.MAX_FIELD_LENGTH) return false
        val editor = directEditor.acquire() ?: return false
        if (!editor.isCurrent() || !DirectContextPolicy.sameEditor(state, editor.identity)) return false
        target = Target(node, state, editor)
        return true
    }
    fun forget() { target?.invalidated = true; target = null }
    private fun state(node: AccessibilityNodeInfo): TargetState {
        // Password content is never read, even for hashing.
        val password = node.isPassword || TargetPolicy.password(node.inputType)
        val content = if (password) "" else node.text?.toString().orEmpty()
        return TargetState(node.packageName?.toString().orEmpty(), node.windowId,
            node.viewIdResourceName, node.className?.toString().orEmpty(), TargetPolicy.hash(content),
            content.length, node.textSelectionStart, node.textSelectionEnd, node.isEditable,
            password, node.isFocused, node.isVisibleToUser, node.isEnabled, node.inputType)
    }
    private fun focused(captured: Target): AccessibilityNodeInfo? {
        if (target !== captured || captured.invalidated || connected !== this || !captured.editor.isCurrent() ||
            !captured.node.refresh()) return null
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        val current = state(focus)
        if (focus != captured.node || !TargetPolicy.eligible(current, packageName) ||
            current.packageName != captured.state.packageName || current.windowId != captured.state.windowId ||
            current.viewId != captured.state.viewId || current.className != captured.state.className) return null
        return focus
    }
    private fun unchanged(captured: Target): AccessibilityNodeInfo? = focused(captured)?.takeIf {
        TargetPolicy.unchanged(captured.state, state(it), packageName)
    }
    suspend fun insert(text: String): String = withContext(Dispatchers.Main.immediate) {
        if (!inserting.compareAndSet(false, true)) return@withContext "Insertion déjà en cours : aucun second envoi."
        val captured = target
        var requested = false
        try {
            if (captured == null || captured.attempted || unchanged(captured) == null || text.isBlank())
                return@withContext FALLBACK
            captured.attempted = true
            // One potentially slow IPC on IO. No main-thread polling of surrounding text.
            val context = withContext(Dispatchers.IO) { captured.editor.surrounding() }
            if (!DirectContextPolicy.matches(captured.state, captured.editor.identity, context))
                return@withContext "Éditeur non observable, texte en composition ou contexte incompatible : insertion annulée. Utilisez Copier."
            if (unchanged(captured) == null || !captured.editor.canCommit(captured.state.start, captured.state.end))
                return@withContext FALLBACK
            val expected = TargetPolicy.insertedText(checkNotNull(context).text, captured.state.start, captured.state.end, text)
            if (expected.length > DirectContextPolicy.MAX_FIELD_LENGTH)
                return@withContext "Texte trop long pour une insertion vérifiable : transcription conservée, utilisez Copier."
            val cursor = captured.state.start + text.length // UTF16, as required by the platform.
            captured.dispatching = true
            requested = true // commitText is void; an exception is also uncertain, never retried.
            captured.editor.commit(text)
            // One fresh observation, not a main-thread IPC polling loop. Never resend if delayed.
            delay(350)
            val observed = focused(captured) ?: return@withContext UNCERTAIN
            if (observed.text?.toString() == expected && observed.textSelectionStart == cursor &&
                observed.textSelectionEnd == cursor) "Insertion observée dans le champ. Transcription conservée."
            else UNCERTAIN
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { if (requested) UNCERTAIN else FALLBACK }
        finally {
            if (target === captured) forget()
            inserting.set(false)
        }
    }
    companion object {
        private const val FALLBACK = "Cible ou éditeur absent, modifié ou incompatible : texte conservé. Utilisez Copier puis collez vous-même."
        private const val UNCERTAIN = "Insertion demandée mais non confirmée. Vérifiez le champ avant toute copie pour éviter un doublon."
        var connected: InsertionService? = null
            private set
    }
}
