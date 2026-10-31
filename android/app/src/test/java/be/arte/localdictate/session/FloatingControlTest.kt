package be.arte.localdictate.session

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.WindowManager
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowWindowManagerImpl
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Real overlay views under Robolectric: every state renders and the bubble can be hidden and shown again. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FloatingControlTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val windows: List<View> get() =
        Shadow.extract<ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java)).views

    private fun snapshot(view: View, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(maxOf(1, view.measuredWidth), maxOf(1, view.measuredHeight), Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.rgb(28, 22, 20)); view.draw(this) }
        System.getenv("BUBBLE_SNAPSHOTS")?.let { dir ->
            File(dir).mkdirs()
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun everyStateRendersAndTheButtonsMatchTheState() {
        var started = 0; var confirmed = 0; var cancelled = 0
        val bubble = FloatingControl(context, { started++ }, { confirmed++ }, { cancelled++ }, {})
        val root = windows.single()
        val states = listOf(
            "loading" to DictationState(phase = Phase.LOADING),
            "idle" to DictationState(phase = Phase.ARMED),
            "waiting-mic" to DictationState(phase = Phase.RECORDING),
            "recording" to DictationState(phase = Phase.RECORDING, micLive = true),
            "processing" to DictationState(phase = Phase.PROCESSING),
            "not-inserted" to DictationState(phase = Phase.ARMED, notice = "Non inséré • texte copié, collez-le"),
            "kept-text" to DictationState(phase = Phase.ARMED, transcript = "texte"),
        )
        for ((name, state) in states) {
            bubble.render(state)
            if (name == "recording") repeat(11) { bubble.level(0.02f * it); ShadowLooper.idleMainLooper() }
            ShadowLooper.idleMainLooper()
            snapshot(root, name)
        }
        bubble.render(DictationState(phase = Phase.RECORDING, micLive = true))
        val buttons = mutableListOf<View>()
        fun collect(view: View) {
            if (view.contentDescription != null) buttons += view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
        }
        collect(root)
        buttons.first { it.contentDescription.startsWith("Annuler") }.performClick()
        buttons.first { it.contentDescription == "Terminer et insérer au curseur" && it is android.widget.TextView }.performClick()
        assertEquals(1, cancelled); assertEquals(1, confirmed); assertEquals(0, started)
        bubble.close()
        assertTrue(windows.isEmpty())
        bubble.show()
        assertEquals(1, windows.size)
        bubble.close()
    }
}
