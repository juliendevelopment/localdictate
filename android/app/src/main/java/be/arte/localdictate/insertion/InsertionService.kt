package be.arte.localdictate.insertion

import android.accessibilityservice.AccessibilityService
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/** No traversal, history or event text harvesting. Only findFocus on an explicit bubble START. */
class InsertionService : AccessibilityService() {
    private var target: AccessibilityNodeInfo? = null
    private var original: TargetState? = null
    private var invalidated = false
    private var dispatching = false
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val snapshot = original ?: return
        if (dispatching || event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            (event.windowId == snapshot.windowId && event.eventType in setOf(
                AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED))) {
            // Conservative fallback even if a field later returns to its former contents.
            invalidated = true
        }
    }
    override fun onInterrupt() { forget() }
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        connected = null
        forget()
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        if (connected === this) connected = null
        forget()
        super.onDestroy()
    }
    fun snapshot(): Boolean {
        forget()
        val focused = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val state = state(focused)
        if (!TargetPolicy.eligible(state, packageName)) return false
        target = focused
        original = state
        return true
    }
    fun forget() { target = null; original = null; invalidated = false }
    private fun state(node: AccessibilityNodeInfo): TargetState {
        // Password content is never read, even for hashing.
        val inputClass = node.inputType and InputType.TYPE_MASK_CLASS
        val variation = node.inputType and InputType.TYPE_MASK_VARIATION
        val password = node.isPassword ||
            (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        val content = if (password) "" else node.text?.toString().orEmpty()
        return TargetState(node.packageName?.toString().orEmpty(), node.windowId,
            node.viewIdResourceName, node.className?.toString().orEmpty(), TargetPolicy.hash(content),
            content.length, node.textSelectionStart, node.textSelectionEnd, node.isEditable,
            password, node.isFocused, node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE },
            node.isVisibleToUser, node.isEnabled)
    }
    private fun valid(): AccessibilityNodeInfo? {
        val node = target ?: return null
        val before = original ?: return null
        if (invalidated || connected !== this || !node.refresh()) return null
        val focus = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (focus != node || !TargetPolicy.unchanged(before, state(focus), packageName)) return null
        return focus
    }
    suspend fun insert(text: String): String {
        var token: String? = null
        val clipboard = PrivateClipboard(this)
        try {
            val node = valid() ?: return "Cible absente ou modifiée : texte conservé. Utilisez Copier puis collez vous-même."
            val before = checkNotNull(original)
            val expected = TargetPolicy.insertedText(node.text?.toString().orEmpty(), before.start, before.end, text)
            token = clipboard.put(text)
            if (!clipboard.owns(token, text))
                return "Android ne permet pas de vérifier notre presse-papiers ou celui-ci a changé : collage annulé. Ouvrez LocalDictate et utilisez Copier."
            if (valid() == null)
                return "Collage annulé : cible modifiée. Texte conservé."
            dispatching = true
            if (!node.performAction(AccessibilityNodeInfo.ACTION_PASTE))
                return "Ce champ refuse le collage : texte conservé, copie manuelle disponible."
            repeat(10) {
                delay(100)
                if (node.refresh() && !node.isPassword && node.text?.toString() == expected)
                    return "Insertion observée dans le champ. Transcription conservée."
            }
            return "Collage demandé mais non confirmé. Vérifiez le champ avant toute copie pour éviter un doublon."
        } catch (_: Exception) {
            return "Insertion non confirmée : vérifiez le champ. Texte conservé et copie manuelle disponible."
        } finally {
            token?.let { runCatching { clipboard.clearIfOwned(it, text) } }
            dispatching = false
            forget()
        }
    }
    companion object {
        var connected: InsertionService? = null
            private set
    }
}
