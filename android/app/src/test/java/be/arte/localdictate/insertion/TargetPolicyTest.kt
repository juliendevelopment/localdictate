package be.arte.localdictate.insertion

import org.junit.Assert.*
import org.junit.Test

class TargetPolicyTest {
    private val own = "be.arte.localdictate"
    private val target = TargetState("notes", 7, "field", "EditText", TargetPolicy.hash("abc def"),
        7, 3, 3, true, false, true)
    @Test fun insertionAtCursorPreservesBothSides() {
        assertEquals("abc Bonjour def", TargetPolicy.insertedText("abc def", 3, 3, " Bonjour"))
        assertTrue(TargetPolicy.unchanged(target, target.copy(), own))
    }
    @Test fun onlyOriginalSelectionMayBeReplaced() {
        assertEquals("abc Hello", TargetPolicy.insertedText("abc def", 4, 7, "Hello"))
        assertFalse(TargetPolicy.unchanged(target, target.copy(end = 7), own))
    }
    @Test fun passwordOwnAppAndUnsupportedTargetsAreRejected() {
        listOf(target.copy(password = true), target.copy(packageName = own), target.copy(editable = false),
            target.copy(visible = false), target.copy(enabled = false), target.copy(focused = false), target.copy(start = -1),
            target.copy(end = 8), target.copy(start = 5, end = 3)).forEach {
            assertFalse(TargetPolicy.eligible(it, own))
        }
    }
    @Test fun changedAppWindowFieldContentOrCursorNeverPassesRevalidation() {
        listOf(target.copy(packageName = "browser"), target.copy(windowId = 8), target.copy(viewId = "other"),
            target.copy(className = "OtherEdit"), target.copy(contentHash = TargetPolicy.hash("modified")),
            target.copy(length = 8), target.copy(start = 4, end = 4)).forEach {
            assertFalse(TargetPolicy.unchanged(target, it, own))
        }
    }
    @Test fun directContextMustMatchTheEditorPackageTypeAndKnownViewId() {
        val context = EditorSurrounding("abc def", 3, 3, 0, false)
        val identity = EditorIdentity("notes", 7, "field", 1, "field")
        assertTrue(DirectContextPolicy.matches(target.copy(inputType = 1), identity, context))
        for (changed in listOf(identity.copy(packageName = "other"), identity.copy(inputType = 2),
            identity.copy(viewId = "other"), identity.copy(inputType = 129))) {
            assertFalse(DirectContextPolicy.matches(target.copy(inputType = 1), changed, context))
        }
    }
}
