package be.arte.localdictate.session

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Color
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** A non-focusable overlay. Drag does not trigger capture. Close is always explicit. */
class FloatingControl(context: Context, onTap: () -> Unit, onClose: () -> Unit) : AutoCloseable {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val controls = LinearLayout(context)
    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        maxLines = 3
        maxWidth = (240 * context.resources.displayMetrics.density).toInt()
    }
    private val toggle = Button(context).apply { contentDescription = "Démarrer ou arrêter la dictée" }
    private val close = Button(context).apply { text = "×"; contentDescription = "Arrêter et désarmer la bulle" }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 16; y = 220
    }
    init {
        root.setBackgroundColor(Color.rgb(30, 40, 48))
        controls.addView(toggle)
        controls.addView(close)
        root.addView(controls)
        root.addView(status)
        close.setOnClickListener { onClose() }
        toggle.setOnClickListener { onTap() }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        toggle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                    if (moved) {
                        val bounds = context.resources.displayMetrics
                        params.x = (startX + dx.toInt()).coerceIn(0, (bounds.widthPixels - root.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (bounds.heightPixels - root.height - 80).coerceAtLeast(0))
                        runCatching { manager.updateViewLayout(root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick(); true }
                else -> true
            }
        }
        manager.addView(root, params)
    }
    fun render(state: DictationState) {
        val phase = state.phase
        status.text = state.error ?: if (state.transcript.isNotEmpty() && phase == Phase.ARMED)
            "Texte conservé • ouvrir pour vérifier" else ""
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        toggle.text = when (phase) {
            Phase.LOADING -> "Chargement…"
            Phase.ARMED -> if (state.transcript.isNotEmpty() || state.error != null) "Ouvrir le texte" else "● Dicter"
            Phase.RECORDING -> "■ Arrêter"
            Phase.PROCESSING -> "Traitement…"
            Phase.OFF -> "Désarmée"
        }
        toggle.isEnabled = phase == Phase.ARMED || phase == Phase.RECORDING
    }
    override fun close() { runCatching { manager.removeView(root) } }
}
