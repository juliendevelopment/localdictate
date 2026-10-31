package be.arte.localdictate.insertion

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * No traversal, history or event text harvesting. findFocus only for an explicit START/insertion.
 *
 * START records which field the user chose. STOP decides from fresh reads only: the same field still has
 * the input focus, the editor connected to it agrees with it, the cursor is collapsed and any keyboard
 * composition is preserved. Window/focus events and editor restarts during a dictation are normal on a
 * phone and are not used (see docs/insertion.md).
 */
open class InsertionService : AccessibilityService() {
    private class Target(val node: AccessibilityNodeInfo, val state: TargetState) {
        var attempted = false
    }
    private var target: Target? = null
    private val inserting = AtomicBoolean(false)
    private var refusal = ""
    internal var directEditor = DirectEditor {
        if (Build.VERSION.SDK_INT >= 33) Api33DirectEditor.acquire(this) else null
    }
    override fun onServiceConnected() { connected = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
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
    /** Non-content reason for the last refused snapshot, shown to the user to diagnose targets. */
    @Volatile var lastRefusal: String = ""
        private set
    fun snapshot(): Boolean {
        lastRefusal = ""
        if (inserting.get()) { lastRefusal = "insertion en cours"; return false }
        forget()
        // The active window can be our overlay; query input focus across interactive windows.
        val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null) { lastRefusal = "aucun champ au focus de saisie"; return false }
        if (!node.refresh()) { lastRefusal = "champ non rafraîchissable"; return false }
        val state = state(node)
        if (!TargetPolicy.eligible(state, packageName) || state.length > DirectContextPolicy.MAX_FIELD_LENGTH) {
            lastRefusal = "champ non éligible (modifiable=${state.editable} visible=${state.visible} " +
                "actif=${state.enabled} mot de passe=${state.password} focus=${state.focused} " +
                "sélection=${state.start}-${state.end}/${state.length})"
            return false
        }
        val editor = directEditor.acquire()
        if (editor == null) { lastRefusal = "éditeur direct indisponible (entrée de saisie non démarrée)"; return false }
        if (!DirectContextPolicy.sameEditor(state, editor.identity)) {
            lastRefusal = "éditeur différent du champ (type champ=${state.inputType} éditeur=${editor.identity.inputType} " +
                "id champ=${state.viewId} id éditeur=${editor.identity.viewId})"
            return false
        }
        target = Target(node, state)
        return true
    }
    fun forget() { target = null }
    private fun state(node: AccessibilityNodeInfo): TargetState {
        // Password content is never read, even for hashing.
        val password = node.isPassword || TargetPolicy.password(node.inputType)
        val content = if (password) "" else node.text?.toString().orEmpty()
        return TargetState(node.packageName?.toString().orEmpty(), node.windowId,
            node.viewIdResourceName, node.className?.toString().orEmpty(), TargetPolicy.hash(content),
            content.length, node.textSelectionStart, node.textSelectionEnd, node.isEditable,
            password, node.isFocused, node.isVisibleToUser, node.isEnabled, node.inputType)
    }
    /** The focused field now, if it is still the field chosen at START and can receive text without loss. */
    private fun currentField(captured: Target): AccessibilityNodeInfo? {
        if (target !== captured || connected !== this) { refusal = "service d’accessibilité déconnecté"; return null }
        val focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focus == null || !focus.refresh()) { refusal = "plus de champ au focus"; return null }
        val now = state(focus)
        val old = captured.state
        refusal = when {
            focus != captured.node || now.packageName != old.packageName || now.windowId != old.windowId ||
                now.viewId != old.viewId || now.className != old.className -> "autre champ ou autre application au focus"
            !TargetPolicy.eligible(now, packageName) -> "champ non modifiable, masqué ou mot de passe"
            now.length > DirectContextPolicy.MAX_FIELD_LENGTH -> "champ trop long"
            now.start != now.end -> "texte sélectionné dans le champ : rien n’est remplacé"
            else -> return focus
        }
        return null
    }
    suspend fun insert(text: String): String = withContext(Dispatchers.Main.immediate) {
        if (!inserting.compareAndSet(false, true)) return@withContext "Insertion déjà en cours : aucun second envoi."
        val captured = target
        var requested = false
        try {
            if (captured == null) return@withContext "$FALLBACK (aucune cible au démarrage)"
            if (captured.attempted) return@withContext "$FALLBACK (déjà tentée)"
            if (text.isBlank()) return@withContext "$FALLBACK (texte vide)"
            val field = currentField(captured) ?: return@withContext "$FALLBACK ($refusal)"
            val state = state(field)
            // Acquired now: the keyboard or the app may restart input on the same field during a dictation.
            val editor = directEditor.acquire() ?: return@withContext "$FALLBACK (éditeur direct indisponible)"
            if (!editor.isCurrent() || !DirectContextPolicy.sameEditor(state, editor.identity))
                return@withContext "$FALLBACK (éditeur différent du champ)"
            captured.attempted = true
            // One potentially slow IPC on IO. No main-thread polling of surrounding text.
            val context = withContext(Dispatchers.IO) { editor.surrounding() }
            DirectContextPolicy.mismatch(state, editor.identity, context)?.let { reason ->
                return@withContext "Éditeur non observable ou contexte incompatible : insertion annulée " +
                    "($reason ; éditeur : ${editor.describe()})."
            }
            val again = currentField(captured)
                ?: return@withContext "$FALLBACK (champ modifié pendant la lecture : $refusal)"
            if (state(again) != state) return@withContext "$FALLBACK (champ modifié pendant la lecture)"
            val surrounding = checkNotNull(context)
            if (!editor.canCommit(surrounding))
                return@withContext "$FALLBACK (le clavier vient de changer le champ : ${editor.describe()})"
            val plan = DirectContextPolicy.plan(surrounding, text)
                ?: return@withContext "$FALLBACK (composition du clavier hors du curseur)"
            if (plan.expected.length > DirectContextPolicy.MAX_FIELD_LENGTH)
                return@withContext "Texte trop long pour une insertion vérifiable : utilisez Copier."
            requested = true // commitText is void; an exception is also uncertain, never retried.
            editor.commit(plan.text)
            // Read-only confirmation, never a resend: the same field must show exactly the expected text.
            var seen = "aucun champ observé"
            repeat(6) {
                delay(250)
                val node = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (node != null && node.refresh() && node.packageName?.toString() == captured.state.packageName &&
                    node.viewIdResourceName == captured.state.viewId) {
                    val observed = node.text?.toString().orEmpty()
                    if (observed == plan.expected && (node.textSelectionStart == plan.cursor &&
                            node.textSelectionEnd == plan.cursor || node.textSelectionStart < 0)) return@withContext OBSERVED
                    seen = "texte ${observed.length}/${plan.expected.length} car., curseur ${node.textSelectionStart}-" +
                        "${node.textSelectionEnd} attendu ${plan.cursor}"
                }
            }
            "$UNCERTAIN ($seen)"
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { if (requested) UNCERTAIN else FALLBACK }
        finally {
            if (target === captured) forget()
            inserting.set(false)
        }
    }
    companion object {
        const val OBSERVED = "Insertion observée dans le champ."
        private const val FALLBACK = "Insertion refusée sans rien modifier dans le champ."
        private const val UNCERTAIN = "Insertion demandée mais non confirmée : vérifiez le champ pour éviter un doublon."
        var connected: InsertionService? = null
            private set
    }
}
