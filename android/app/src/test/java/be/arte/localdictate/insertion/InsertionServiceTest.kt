package be.arte.localdictate.insertion

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
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
import org.robolectric.shadows.ShadowClipboardManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real service and framework nodes, fixture direct editor. No claim of phone InputConnection success. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [FocusedServiceShadow::class, ClipboardSpyShadow::class])
class InsertionServiceTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var service: InsertionService
    private lateinit var node: AccessibilityNodeInfo
    private lateinit var clipboard: ClipboardManager
    private lateinit var editor: FixtureEditor
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        service = Robolectric.buildService(InsertionService::class.java).create().get()
        ReflectionHelpers.callInstanceMethod<Void>(service, "onServiceConnected")
        clipboard = service.getSystemService(ClipboardManager::class.java)
        node = AccessibilityNodeInfo.obtain(View(service)).apply {
            packageName = "fixture.notes"
            className = "android.widget.EditText"
            viewIdResourceName = "fixture.notes:id/input"
            text = "abc def"
            isEditable = true; isFocused = true; isVisibleToUser = true; isEnabled = true
            setTextSelection(3, 3)
        }
        setWindow(7)
        shadowOf(node).setRefreshReturnValue(true)
        FocusedServiceShadow.focus = node
        FocusedServiceShadow.queries = 0
        editor = FixtureEditor()
        service.directEditor = DirectEditor { editor }
        clipboard.setPrimaryClip(ClipData.newPlainText("user", "À garder"))
        ClipboardSpyShadow.calls.clear()
        ClipboardSpyShadow.denyRead = false
    }
    @After fun cleanup() {
        service.onDestroy()
        FocusedServiceShadow.focus = null
        ClipboardSpyShadow.denyRead = false
        Dispatchers.resetMain()
    }
    private fun setWindow(id: Int) {
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).setId(id)
        shadowOf(node).setAccessibilityWindowInfo(window)
    }
    private inner class FixtureEditor : DirectEditorSession {
        override var identity = EditorIdentity("fixture.notes", 123, "input", 1)
        var current = true
        var commitAllowed = true
        var context: EditorSurrounding? = EditorSurrounding("abc def", 3, 3, 0, false)
        var reads = 0
        var commits = 0
        var readEffect: (() -> Unit)? = null
        var commitEffect: ((String) -> Unit)? = null
        override fun isCurrent() = current
        override fun canCommit(start: Int, end: Int) = current && commitAllowed
        override fun surrounding(): EditorSurrounding? { reads++; readEffect?.invoke(); return context }
        override fun commit(text: String) {
            commits++
            val action = commitEffect
            if (action != null) action(text) else observe(text)
        }
        fun observe(text: String) {
            val start = node.textSelectionStart
            node.text = TargetPolicy.insertedText(node.text.toString(), start, node.textSelectionEnd, text)
            node.setTextSelection(start + text.length, start + text.length)
        }
    }
    private fun noClipboardOrNodeAction() {
        assertTrue("Automatic path touched clipboard: ${ClipboardSpyShadow.calls}", ClipboardSpyShadow.calls.isEmpty())
        assertTrue(shadowOf(node).performedActions.isEmpty())
    }
    @Test fun directCommitPreservesPrefixSuffixAndExactCollapsedCursorWithoutPasteAction() = runTest(dispatcher) {
        assertFalse(node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_PASTE })
        assertTrue(service.snapshot())
        assertTrue(service.insert(" Bonjour").contains("Insertion observée"))
        assertEquals("abc Bonjour def", node.text.toString())
        assertEquals(11, node.textSelectionStart)
        assertEquals(11, node.textSelectionEnd)
        assertEquals(1, editor.commits)
        noClipboardOrNodeAction()
        assertEquals("À garder", clipboard.primaryClip!!.getItemAt(0).text.toString())
    }
    @Test fun selectionReplacementUsesUtf16AndPreservesUnselectedText() = runTest(dispatcher) {
        node.text = "😀 abc fin"
        node.setTextSelection(3, 6)
        editor.context = EditorSurrounding(node.text.toString(), 3, 6, 0, false)
        assertTrue(service.snapshot())
        assertTrue(service.insert("Hello🌍").contains("Insertion observée"))
        assertEquals("😀 Hello🌍 fin", node.text.toString())
        assertEquals(10, node.textSelectionEnd)
        noClipboardOrNodeAction()
    }
    @Test fun unsupportedDirectEditorDoesNotFallBackToSupportedPaste() = runTest(dispatcher) {
        node.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_PASTE)
        service.directEditor = DirectEditor { null }
        assertFalse(service.snapshot())
        assertTrue(service.insert("bonjour").contains("Copier"))
        noClipboardOrNodeAction()
    }
    @Test fun unreadableClipboardDoesNotPreventDirectCommitAndIsNeverAccessed() = runTest(dispatcher) {
        ClipboardSpyShadow.denyRead = true
        assertTrue(service.snapshot())
        assertTrue(service.insert(" Bonjour").contains("Insertion observée"))
        noClipboardOrNodeAction()
    }
    @Test fun changedTextBlocks() = runTest(dispatcher) {
        assertTrue(service.snapshot()); node.text = "changed"
        assertTrue(service.insert("Bonjour").contains("modifié")); assertEquals(0, editor.commits)
        noClipboardOrNodeAction()
    }
    @Test fun changedSelectionBlocks() = runTest(dispatcher) {
        assertTrue(service.snapshot()); node.setTextSelection(0, 0)
        service.insert("Bonjour"); assertEquals(0, editor.commits); noClipboardOrNodeAction()
    }
    @Test fun reboundSameIdSameContentEditorBlocks() = runTest(dispatcher) {
        assertTrue(service.snapshot())
        editor.current = false // Native adapter separately tests reference identity on equal EditorInfo objects.
        service.directEditor = DirectEditor { FixtureEditor() }
        service.insert("Bonjour"); assertEquals(0, editor.commits); noClipboardOrNodeAction()
    }
    @Test fun replacedFocusedNodeWithSameValuesIsNotTheOriginalTarget() = runTest(dispatcher) {
        assertTrue(service.snapshot())
        FocusedServiceShadow.focus = AccessibilityNodeInfo.obtain(View(service)).apply {
            packageName = node.packageName; text = node.text; className = node.className
            viewIdResourceName = node.viewIdResourceName
            isEditable = true; isFocused = true; isVisibleToUser = true; isEnabled = true
            setTextSelection(3, 3)
        }
        service.insert("Bonjour"); assertEquals(0, editor.commits)
    }
    @Test fun windowDriftBlocks() = runTest(dispatcher) {
        assertTrue(service.snapshot()); setWindow(8)
        service.insert("Bonjour"); assertEquals(0, editor.commits)
    }
    @Test fun passwordVisibilityEnabledAndEditableDriftBlock() = runTest(dispatcher) {
        for (drift in listOf<() -> Unit>({ node.isPassword = true }, { node.isVisibleToUser = false },
            { node.isEnabled = false }, { node.isEditable = false }, { node.isFocused = false },
            { node.inputType = 129 }, { node.packageName = "other.app" })) {
            node.isPassword = false; node.isVisibleToUser = true; node.isEnabled = true
            node.isEditable = true; node.isFocused = true; node.inputType = 1; node.packageName = "fixture.notes"
            assertTrue(service.snapshot()); drift()
            service.insert("Bonjour"); assertEquals(0, editor.commits)
        }
        noClipboardOrNodeAction()
    }
    @Test fun passwordOwnAppAndEditorPasswordCannotBeSnapshotted() {
        node.isPassword = true; assertFalse(service.snapshot())
        node.isPassword = false; node.inputType = 225; assertFalse(service.snapshot())
        node.inputType = 1; node.packageName = service.packageName; assertFalse(service.snapshot())
        node.packageName = "fixture.notes"; editor.identity = editor.identity.copy(inputType = 129)
        assertFalse(service.snapshot())
    }
    @Test fun unavailableTruncatedOffsetSelectionAndTextMismatchAreRejected() = runTest(dispatcher) {
        val good = editor.context!!
        for (context in listOf(null, good.copy(text = "abc"), good.copy(offset = 1), good.copy(start = 2),
            good.copy(end = 4), good.copy(text = "xyz def"), good.copy(composing = true))) {
            editor.context = context
            assertTrue(service.snapshot())
            assertTrue(service.insert("Bonjour").contains("insertion annulée"))
        }
        assertEquals(0, editor.commits); noClipboardOrNodeAction()
    }
    @Test fun oversizedFieldIsNotReadAndNeverCommitted() {
        node.text = "a".repeat(32_769)
        assertFalse(service.snapshot()); assertEquals(0, editor.reads)
    }
    @Test fun contextReadDriftIsRevalidatedAfterOffMainRead() = runTest(dispatcher) {
        assertTrue(service.snapshot())
        editor.readEffect = { node.setTextSelection(0, 0) }
        service.insert("Bonjour"); assertEquals(0, editor.commits)
    }
    @Test fun composingRangeAppearingAfterContextReadBlocksBeforeSingleCommit() = runTest(dispatcher) {
        assertTrue(service.snapshot()); editor.readEffect = { editor.commitAllowed = false }
        service.insert("Bonjour"); assertEquals(0, editor.commits)
        noClipboardOrNodeAction()
    }
    @Test fun exceptionAfterVoidCommitIsUncertainAndNeverRetried() = runTest(dispatcher) {
        assertTrue(service.snapshot()); editor.commitEffect = { error("Fixture remote disconnect") }
        assertTrue(service.insert("Bonjour").contains("non confirmée"))
        service.insert("Bonjour"); assertEquals(1, editor.commits)
        noClipboardOrNodeAction()
    }
    @Test fun editorRebindDuringContextReadBlocks() = runTest(dispatcher) {
        assertTrue(service.snapshot()); editor.readEffect = { editor.current = false }
        service.insert("Bonjour"); assertEquals(0, editor.commits)
    }
    @Test fun acceptedButUnobservedOrWrongCursorCommitRemainsUncertain() = runTest(dispatcher) {
        assertTrue(service.snapshot()); editor.commitEffect = {}
        assertTrue(service.insert("Bonjour").contains("non confirmée"))
        node.text = "abc def"; node.setTextSelection(3, 3)
        assertTrue(service.snapshot())
        editor.commitEffect = { editor.observe(it); node.setTextSelection(0, 0) }
        assertTrue(service.insert("Bonjour").contains("non confirmée"))
        assertEquals(2, editor.commits); noClipboardOrNodeAction()
    }
    @Test fun windowChangeDuringVerificationCannotBeSuppressedByDispatching() = runTest(dispatcher) {
        assertTrue(service.snapshot())
        editor.commitEffect = {
            editor.observe(it)
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED))
        }
        assertTrue(service.insert("Bonjour").contains("non confirmée"))
        assertEquals(1, editor.commits)
    }
    @Test fun focusChangeDuringVerificationCannotBeSuppressedByDispatching() = runTest(dispatcher) {
        assertTrue(service.snapshot())
        editor.commitEffect = {
            editor.observe(it)
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_FOCUSED))
        }
        assertTrue(service.insert("Bonjour").contains("non confirmée"))
    }
    @Test fun doubleInvocationDuringReadOrVerificationCommitsOnceAndNeverRetries() = runTest(dispatcher) {
        assertTrue(service.snapshot())
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        editor.readEffect = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) }
        val first = async { service.insert("Bonjour") }
        assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
        assertTrue(service.insert("Bonjour").contains("déjà en cours"))
        release.countDown()
        while (editor.commits == 0) { runCurrent(); delay(10) }
        assertTrue(service.insert("Bonjour").contains("déjà en cours"))
        assertTrue(first.await().contains("Insertion observée"))
        service.insert("Bonjour")
        assertEquals(1, editor.commits); noClipboardOrNodeAction()
    }
    @Test fun eventsAloneNeverQueryOrHarvestWindows() {
        repeat(20) { service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) }
        assertEquals(0, FocusedServiceShadow.queries)
    }
    @Test fun missingAccessibilityKeepsTextWithoutCommit() = runTest(dispatcher) {
        assertTrue(service.snapshot()); service.onUnbind(null)
        assertTrue(service.insert("Bonjour").contains("Copier")); assertEquals(0, editor.commits)
    }
    @Test fun windowChangeBeforeCommitPermanentlyInvalidatesTarget() = runTest(dispatcher) {
        assertTrue(service.snapshot())
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED))
        service.insert("Bonjour"); assertEquals(0, editor.commits)
    }
}

@Implements(AccessibilityService::class)
class FocusedServiceShadow : ShadowAccessibilityService() {
    @Implementation fun findFocus(focusType: Int): AccessibilityNodeInfo? { queries++; return focus }
    @Implementation fun getRootInActiveWindow(): AccessibilityNodeInfo? = error("Active root may be the overlay; must not be queried")
    companion object { var focus: AccessibilityNodeInfo? = null; var queries = 0 }
}
@Implements(ClipboardManager::class)
class ClipboardSpyShadow : ShadowClipboardManager() {
    @Implementation override fun getPrimaryClip(): ClipData? { calls += "read"; return if (denyRead) null else super.getPrimaryClip() }
    @Implementation override fun setPrimaryClip(clip: ClipData) { calls += "write"; super.setPrimaryClip(clip) }
    @Implementation override fun clearPrimaryClip() { calls += "clear"; super.clearPrimaryClip() }
    companion object { val calls = mutableListOf<String>(); var denyRead = false }
}
