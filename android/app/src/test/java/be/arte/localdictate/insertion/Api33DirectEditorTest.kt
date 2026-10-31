package be.arte.localdictate.insertion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.text.SpannableString
import android.text.Spanned
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAccessibilityService

/** Exercises the production API33 adapter with shadows of exact SDK signatures, not a remote editor. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [InputServiceShadow::class, InputMethodShadow::class, InputConnectionShadow::class])
class Api33DirectEditorTest {
    private lateinit var service: InsertionService
    @Before fun setup() {
        service = Robolectric.buildService(InsertionService::class.java).create().get()
        InputServiceShadow.method = GuardedInputMethod(service)
        InputMethodShadow.info = info()
        (InputServiceShadow.method as GuardedInputMethod).apply {
            onStartInput(InputMethodShadow.info!!, false)
            onUpdateSelection(3, 3, 3, 3, -1, -1)
        }
        InputMethodShadow.started = true
        InputMethodShadow.available = true
        InputConnectionShadow.context = SurroundingText("abc def", 3, 3, 0)
        InputConnectionShadow.reads.clear()
        InputConnectionShadow.commits.clear()
    }
    private fun info() = EditorInfo().apply {
        packageName = "fixture.notes"; fieldId = 123; fieldName = "input"; inputType = 1
    }
    @Test fun actualAdapterCallsBoundedStyledReadAndExactCommitSignature() {
        val session = Api33DirectEditor.acquire(service)!!
        assertTrue(session.isCurrent())
        assertEquals(EditorSurrounding("abc def", 3, 3, 0, false), session.surrounding())
        assertEquals(listOf(Triple(32768, 32768, InputConnection.GET_TEXT_WITH_STYLES)), InputConnectionShadow.reads)
        session.commit("Bonjour")
        assertEquals(listOf(Triple("Bonjour", 1, null)), InputConnectionShadow.commits)
    }
    @Test fun freshConnectionWrappersAreAllowedButEqualValueEditorRebindIsRejected() {
        val first = InputServiceShadow.method!!.currentInputConnection
        val second = InputServiceShadow.method!!.currentInputConnection
        assertNotSame(first, second)
        val session = Api33DirectEditor.acquire(service)!!
        assertTrue(session.isCurrent())
        InputMethodShadow.info = info() // Same IDs/text do not imply the same bound editor.
        assertFalse(session.isCurrent())
    }
    @Test fun changedEditorMetadataOrInputMethodOrFinishedConnectionInvalidates() {
        var session = Api33DirectEditor.acquire(service)!!
        InputMethodShadow.info!!.inputType = 129
        assertFalse(session.isCurrent())
        InputMethodShadow.info = info(); session = Api33DirectEditor.acquire(service)!!
        InputMethodShadow.started = false; assertFalse(session.isCurrent())
        InputMethodShadow.started = true; InputMethodShadow.available = false; assertFalse(session.isCurrent())
        InputMethodShadow.available = true
        InputServiceShadow.method = GuardedInputMethod(service); assertFalse(session.isCurrent())
    }
    @Test fun missingInputMethodConnectionOrEditorAndPasswordNeverAcquire() {
        InputServiceShadow.method = null; assertNull(Api33DirectEditor.acquire(service))
        InputServiceShadow.method = GuardedInputMethod(service)
        InputMethodShadow.available = false; assertNull(Api33DirectEditor.acquire(service))
        InputMethodShadow.available = true; InputMethodShadow.started = false; assertNull(Api33DirectEditor.acquire(service))
        InputMethodShadow.started = true; InputMethodShadow.info = null; assertNull(Api33DirectEditor.acquire(service))
        InputMethodShadow.info = info().apply { inputType = 129 }; assertNull(Api33DirectEditor.acquire(service))
    }
    @Test fun styledComposingSpanAndFrameworkComposingMarkerAreDetectedWithoutEdits() {
        val session = Api33DirectEditor.acquire(service)!!
        val text = SpannableString("abc def")
        text.setSpan(android.text.style.UnderlineSpan(), 0, 3, Spanned.SPAN_COMPOSING or Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        InputConnectionShadow.context = SurroundingText(text, 3, 3, 0)
        assertTrue(session.surrounding()!!.composing) // A marker without a reported position is refused.
        val marked = SpannableString("abc def")
        BaseInputConnection.setComposingSpans(marked)
        (InputServiceShadow.method as GuardedInputMethod).onUpdateSelection(3, 3, 3, 3, 0, 3)
        InputConnectionShadow.context = SurroundingText(marked, 3, 3, 0)
        val placed = session.surrounding()!!
        assertFalse(placed.composing) // Reported by the keyboard: its position is known and kept intact.
        assertEquals(0, placed.composingStart); assertEquals(3, placed.composingEnd)
        assertTrue(InputConnectionShadow.commits.isEmpty())
    }
    @Test fun lateCompositionBlocksTheCommitAndARestartedInputStartsWithoutOne() {
        val session = Api33DirectEditor.acquire(service)!!
        val method = InputServiceShadow.method as GuardedInputMethod
        val read = session.surrounding()!!
        assertTrue(session.canCommit(read))
        method.onUpdateSelection(3, 3, 3, 3, 0, 3) // Composition appears after the read.
        assertFalse(session.canCommit(read))
        val composed = session.surrounding()!!
        assertEquals(0, composed.composingStart); assertTrue(session.canCommit(composed))
        method.onStartInput(InputMethodShadow.info!!, true)
        assertFalse(session.isCurrent()) // Same EditorInfo reference still invalidated by generation.
        val rebound = Api33DirectEditor.acquire(service)!!
        val fresh = rebound.surrounding()!!
        assertFalse(fresh.composing); assertEquals(-1, fresh.composingStart)
        assertTrue(rebound.canCommit(fresh))
        method.onUpdateSelection(3, 3, 3, 3, 0, 3)
        assertFalse(rebound.canCommit(fresh))
        assertTrue(InputConnectionShadow.commits.isEmpty())
    }
    @Test fun nullAndTruncatedContextAreNotInventedOrRepaired() {
        val session = Api33DirectEditor.acquire(service)!!
        InputConnectionShadow.context = null; assertNull(session.surrounding())
        InputConnectionShadow.context = SurroundingText("def", 0, 0, 4)
        assertEquals(EditorSurrounding("def", 0, 0, 4, false), session.surrounding())
        assertTrue(InputConnectionShadow.commits.isEmpty())
    }
    @Test fun passiveCallbacksNeverEditAndTrackComposingRangeAndRebindGeneration() {
        val method = GuardedInputMethod(service)
        assertFalse(method.observed.known)
        method.onStartInput(info(), false)
        method.onUpdateSelection(0, 0, 1, 1, 0, 1)
        assertEquals(0, method.observed.composingStart)
        assertEquals(1, method.observed.composingEnd)
        val generation = method.observed.generation
        method.onStartInput(info(), true)
        assertTrue(method.observed.generation > generation)
        assertFalse(method.observed.known)
        method.onFinishInput()
        assertTrue(InputConnectionShadow.commits.isEmpty())
    }
}

@Implements(AccessibilityService::class)
class InputServiceShadow : ShadowAccessibilityService() {
    @Implementation fun getInputMethod(): InputMethod? = method
    companion object { var method: InputMethod? = null }
}
@Implements(InputMethod::class, minSdk = 33)
class InputMethodShadow {
    @Implementation fun getCurrentInputStarted() = started
    @Implementation fun getCurrentInputEditorInfo(): EditorInfo? = info
    @Implementation fun getCurrentInputConnection(): InputMethod.AccessibilityInputConnection? =
        if (available) Shadow.newInstanceOf(InputMethod.AccessibilityInputConnection::class.java) else null
    companion object { var started = false; var available = false; var info: EditorInfo? = null }
}
@Implements(InputMethod.AccessibilityInputConnection::class, minSdk = 33)
class InputConnectionShadow {
    @Implementation fun getSurroundingText(before: Int, after: Int, flags: Int): SurroundingText? {
        reads += Triple(before, after, flags)
        return context
    }
    @Implementation fun commitText(text: CharSequence, newCursorPosition: Int, attributes: TextAttribute?) {
        commits += Triple(text.toString(), newCursorPosition, attributes)
    }
    companion object {
        var context: SurroundingText? = null
        val reads = mutableListOf<Triple<Int, Int, Int>>()
        val commits = mutableListOf<Triple<String, Int, TextAttribute?>>()
    }
}
