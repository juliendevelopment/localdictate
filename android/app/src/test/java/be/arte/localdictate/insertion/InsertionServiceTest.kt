package be.arte.localdictate.insertion

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAccessibilityService
import org.robolectric.shadows.ShadowAccessibilityNodeInfo
import org.robolectric.shadows.ShadowClipboardManager
import org.robolectric.util.ReflectionHelpers

/** Framework nodes/actions are fixtures; this does not prove clipboard permission or paste on a phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FocusedServiceShadow::class, FocusedNodeShadow::class])
class InsertionServiceTest {
    private lateinit var service: InsertionService
    private lateinit var node: AccessibilityNodeInfo
    private lateinit var clipboard: ClipboardManager
    @Before fun setup() {
        service = Robolectric.buildService(InsertionService::class.java).create().get()
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
        clipboard = service.getSystemService(ClipboardManager::class.java)
        node = AccessibilityNodeInfo.obtain(View(service)).apply {
            packageName = "fixture.notes"
            className = "android.widget.EditText"
            viewIdResourceName = "fixture.notes:id/input"
            text = "abc def"
            isEditable = true
            isFocused = true
            isVisibleToUser = true
            isEnabled = true
            setTextSelection(3, 3)
            addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
        }
        shadowOf(node).setRefreshReturnValue(true)
        FocusedServiceShadow.root = node
        FocusedServiceShadow.queries = 0
        FocusedNodeShadow.focus = node
        clipboard.setPrimaryClip(ClipData.newPlainText("user", "À garder"))
    }
    @After fun cleanup() {
        service.onDestroy()
        FocusedServiceShadow.root = null
        FocusedNodeShadow.focus = null
    }
    @Test fun observedPastePreservesExistingTextAndCannotDispatchTwice() = runBlocking {
        assertTrue(service.snapshot())
        shadowOf(node).setOnPerformActionListener { action, _ ->
            assertEquals(AccessibilityNodeInfo.ACTION_PASTE, action)
            node.text = "abc Bonjour def"
            true
        }
        assertTrue(service.insert(" Bonjour").contains("Insertion observée"))
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_PASTE), shadowOf(node).performedActions)
        service.insert(" Bonjour")
        assertEquals(1, shadowOf(node).performedActions.size)
        assertFalse(clipboard.hasPrimaryClip())
    }
    @Test fun pasteReplacesOnlyTheValidatedOriginalSelection() = runBlocking {
        node.setTextSelection(4, 7)
        assertTrue(service.snapshot())
        shadowOf(node).setOnPerformActionListener { _, _ -> node.text = "abc Hello"; true }
        assertTrue(service.insert("Hello").contains("Insertion observée"))
        assertEquals("abc Hello", node.text.toString())
        assertEquals(listOf(AccessibilityNodeInfo.ACTION_PASTE), shadowOf(node).performedActions)
    }
    @Test
    @Config(shadows = [FocusedServiceShadow::class, FocusedNodeShadow::class, UnreadableClipboardShadow::class])
    fun deniedClipboardReadFailsClosedWithoutDispatchingPaste() = runBlocking {
        assertTrue(service.snapshot())
        assertTrue(service.insert("bonjour").contains("collage annulé"))
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }
    @Test fun changedCursorOrTextNeverWritesClipboardOrDispatchesPaste() = runBlocking {
        assertTrue(service.snapshot())
        node.setTextSelection(0, 0)
        assertTrue(service.insert("bonjour").contains("modifiée"))
        assertTrue(shadowOf(node).performedActions.isEmpty())
        assertEquals("À garder", clipboard.primaryClip!!.getItemAt(0).text.toString())
        node.setTextSelection(3, 3)
        assertTrue(service.snapshot())
        node.text = "changed"
        service.insert("bonjour")
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }
    @Test fun aChangedWindowInvalidatesEvenIfOriginalFieldReturns() = runBlocking {
        assertTrue(service.snapshot())
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        assertTrue(service.insert("bonjour").contains("modifiée"))
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }
    @Test fun unsupportedPasswordOwnAppAndMissingFocusUseRecovery() = runBlocking {
        node.isPassword = true
        assertFalse(service.snapshot())
        node.isPassword = false
        node.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertFalse(service.snapshot())
        node.inputType = 0
        node.packageName = service.packageName
        assertFalse(service.snapshot())
        FocusedNodeShadow.focus = null
        assertFalse(service.snapshot())
        service.insert("bonjour")
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }
    @Test fun eventsDoNotQueryOrHarvestWindows() {
        repeat(20) { service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) }
        assertEquals(0, FocusedServiceShadow.queries)
    }
    @Test fun userClipboardReplacementDuringFailedDispatchIsNeverCleared() = runBlocking {
        assertTrue(service.snapshot())
        shadowOf(node).setOnPerformActionListener { _, _ ->
            clipboard.setPrimaryClip(ClipData.newPlainText("new user copy", "Nouvelle copie"))
            false
        }
        assertTrue(service.insert("bonjour").contains("refuse"))
        assertEquals("Nouvelle copie", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
    @Test fun acceptedActionWithoutObservedTextIsNotClaimedAsInserted() = runBlocking {
        assertTrue(service.snapshot())
        shadowOf(node).setOnPerformActionListener { _, _ -> true }
        assertTrue(service.insert("bonjour").contains("non confirmé"))
        assertEquals(1, shadowOf(node).performedActions.size)
    }
    @Test fun unavailableAccessibilityNeverPastes() = runBlocking {
        assertTrue(service.snapshot())
        service.onUnbind(null)
        assertTrue(service.insert("bonjour").contains("absente"))
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }
}

@Implements(AccessibilityService::class)
class FocusedServiceShadow : ShadowAccessibilityService() {
    @Implementation fun getRootInActiveWindow(): AccessibilityNodeInfo? { queries++; return root }
    companion object { var root: AccessibilityNodeInfo? = null; var queries = 0 }
}
@Implements(AccessibilityNodeInfo::class)
class FocusedNodeShadow : ShadowAccessibilityNodeInfo() {
    @Implementation fun findFocus(focusType: Int): AccessibilityNodeInfo? = focus
    companion object { var focus: AccessibilityNodeInfo? = null }
}

@Implements(ClipboardManager::class)
class UnreadableClipboardShadow : ShadowClipboardManager() {
    @Implementation override fun getPrimaryClip(): ClipData? = null
}
