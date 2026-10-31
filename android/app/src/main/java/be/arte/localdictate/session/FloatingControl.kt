package be.arte.localdictate.session

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Non-focusable overlay, modelled on common dictation bubbles: a small square docked at the screen edge
 * at rest, a [✕ | live level | ✓] pill while dictating. Dragging it onto the ✕ target at the bottom hides it
 * (the service stays armed with the microphone closed); [show] brings it back. Drag never starts a capture.
 */
class FloatingControl(
    private val context: Context,
    private val onStart: () -> Unit,
    private val onConfirm: () -> Unit,
    private val onCancel: () -> Unit,
    private val onOpen: () -> Unit,
) : AutoCloseable {
    private enum class Mode { IDLE, LOADING, RECORDING, PROCESSING, ATTENTION }

    private val manager = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()
    private fun screenWidth() = context.resources.displayMetrics.widthPixels
    private fun screenHeight() = context.resources.displayMetrics.heightPixels

    private val wave = WaveView(context)
    private val label = TextView(context).apply {
        setTextColor(INK); textSize = 22f; gravity = Gravity.CENTER; visibility = View.GONE
    }
    private val center = FrameLayout(context).apply {
        addView(wave, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }
    private val cancel = roundButton("✕", LAVENDER, INK, "Annuler la dictée, sans insertion")
    private val confirm = roundButton("✓", PURPLE, Color.WHITE, "Terminer et insérer au curseur")
    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(cancel, LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON)).apply { marginEnd = dp(8) })
        addView(center, LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON)))
        addView(confirm, LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON)).apply { marginStart = dp(8) })
    }
    private val status = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = 13f; maxLines = 3
        maxWidth = dp(260)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.argb(220, 30, 26, 36)) }
        visibility = View.GONE
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        addView(row)
        addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) })
    }
    private val params = overlayParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenWidth() - dp(BUTTON) - dp(MARGIN)
        y = (screenHeight() * 0.55f).toInt()
    }
    private val target = TextView(context).apply {
        text = "✕"; textSize = 26f; gravity = Gravity.CENTER; setTextColor(Color.WHITE)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(200, 30, 26, 36)) }
        contentDescription = "Masquer la bulle"
    }
    private val targetParams = overlayParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).apply {
        width = dp(TARGET); height = dp(TARGET)
        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        y = dp(72)
    }
    private var mode: Mode? = null
    private var dockRight = true
    private var shown = false
    private var targetShown = false
    private var shownStatus = ""
    private val hideStatus = Runnable { status.visibility = View.GONE }
    private val pendingLevel = AtomicBoolean(false)
    @Volatile private var lastLevel = 0f

    init {
        cancel.setOnClickListener { if (mode == Mode.RECORDING) onCancel() }
        confirm.setOnClickListener { if (mode == Mode.RECORDING) onConfirm() }
        installDrag()
        show()
    }

    /** Called from the capture thread: only the latest level is drawn, at most once per frame. */
    fun level(value: Float) {
        lastLevel = value
        if (pendingLevel.compareAndSet(false, true)) main.post {
            pendingLevel.set(false)
            if (mode == Mode.RECORDING) wave.push(lastLevel)
        }
    }

    fun show() {
        if (shown) return
        shown = runCatching { manager.addView(root, params) }.isSuccess
        if (shown) root.post { dock() }
    }

    private fun hide() {
        if (!shown) return
        shown = false
        runCatching { manager.removeView(root) }
    }

    fun render(state: DictationState) {
        val next = when (state.phase) {
            Phase.LOADING -> Mode.LOADING
            Phase.RECORDING -> Mode.RECORDING
            Phase.PROCESSING -> Mode.PROCESSING
            Phase.ARMED -> if (state.transcript.isNotEmpty() || state.error != null) Mode.ATTENTION else Mode.IDLE
            Phase.OFF -> Mode.LOADING
        }
        if (next != mode) {
            mode = next
            val pill = mode == Mode.RECORDING || mode == Mode.PROCESSING
            cancel.visibility = if (mode == Mode.RECORDING) View.VISIBLE else View.GONE
            confirm.visibility = if (mode == Mode.RECORDING) View.VISIBLE else View.GONE
            center.layoutParams = (center.layoutParams as LinearLayout.LayoutParams).apply { width = dp(if (pill) PILL else BUTTON) }
            center.background = GradientDrawable().apply {
                cornerRadius = dp(if (pill) BUTTON / 2 else 18).toFloat()
                setColor(if (mode == Mode.ATTENTION) AMBER else LAVENDER)
            }
            center.alpha = if (mode == Mode.LOADING) 0.55f else 1f
            label.visibility = if (mode == Mode.ATTENTION) View.VISIBLE else View.GONE
            label.text = "!"
            wave.visibility = if (mode == Mode.ATTENTION) View.GONE else View.VISIBLE
            wave.style = when (mode) {
                Mode.RECORDING -> WaveView.Style.LIVE
                Mode.PROCESSING, Mode.LOADING -> WaveView.Style.DOTS
                else -> WaveView.Style.ICON
            }
            wave.reset()
            center.contentDescription = when (mode) {
                Mode.IDLE -> "Démarrer la dictée"
                Mode.RECORDING -> "Terminer et insérer au curseur"
                Mode.ATTENTION -> "Ouvrir LocalDictate pour voir le texte conservé"
                else -> "Traitement en cours"
            }
            root.post { dock() }
        }
        if (mode == Mode.RECORDING && !state.micLive) wave.style = WaveView.Style.DOTS
        else if (mode == Mode.RECORDING) wave.style = WaveView.Style.LIVE
        val message = state.error ?: state.notice
            ?: if (mode == Mode.ATTENTION) "Texte conservé • touchez pour ouvrir" else ""
        if (message != shownStatus) {
            shownStatus = message
            main.removeCallbacks(hideStatus)
            status.text = message
            status.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
            // Short outcomes fade; errors and kept text stay until the state changes.
            if (message.isNotEmpty() && state.error == null && mode != Mode.ATTENTION) main.postDelayed(hideStatus, 5_000)
        }
    }

    private fun tap() = when (mode) {
        Mode.IDLE -> onStart()
        Mode.RECORDING -> onConfirm()
        Mode.ATTENTION -> onOpen()
        else -> Unit
    }

    @SuppressLint("ClickableViewAccessibility") // A tap without drag calls performClick() below.
    private fun installDrag() {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        center.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (!dragging && abs(dx) + abs(dy) > ViewConfiguration.get(context).scaledTouchSlop) {
                        dragging = true
                        if (dismissible()) showTarget(true)
                    }
                    if (dragging) {
                        params.x = (startX + dx.toInt()).coerceIn(0, (screenWidth() - root.width).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (screenHeight() - root.height).coerceAtLeast(0))
                        runCatching { manager.updateViewLayout(root, params) }
                        if (targetShown) target.scaleX = if (overTarget()) 1.25f else 1f
                        target.scaleY = target.scaleX
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) view.performClick()
                    else {
                        val dismiss = targetShown && overTarget()
                        showTarget(false)
                        if (dismiss) hide() else {
                            dockRight = params.x + root.width / 2 > screenWidth() / 2
                            dock()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> showTarget(false)
            }
            true
        }
        center.setOnClickListener { tap() }
    }

    /** Hiding is allowed only when no dictation is running. */
    private fun dismissible() = mode == Mode.IDLE || mode == Mode.ATTENTION || mode == Mode.LOADING

    private fun overTarget(): Boolean {
        val bubbleX = params.x + root.width / 2f
        val bubbleY = params.y + row.height / 2f
        val targetX = screenWidth() / 2f
        val targetY = screenHeight() - dp(72) - dp(TARGET) / 2f
        return hypot(bubbleX - targetX, bubbleY - targetY) < dp(TARGET)
    }

    private fun showTarget(visible: Boolean) {
        if (visible == targetShown) return
        targetShown = visible
        target.scaleX = 1f; target.scaleY = 1f
        if (visible) runCatching { manager.addView(target, targetParams) }.onFailure { targetShown = false }
        else runCatching { manager.removeView(target) }
    }

    private fun dock() {
        if (!shown) return
        root.gravity = if (dockRight) Gravity.END else Gravity.START
        params.x = if (dockRight) (screenWidth() - root.width - dp(MARGIN)).coerceAtLeast(0) else dp(MARGIN)
        params.y = params.y.coerceIn(0, (screenHeight() - root.height).coerceAtLeast(0))
        runCatching { manager.updateViewLayout(root, params) }
    }

    private fun roundButton(text: String, fill: Int, ink: Int, description: String) = TextView(context).apply {
        this.text = text; textSize = 22f; gravity = Gravity.CENTER; setTextColor(ink)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        contentDescription = description
        visibility = View.GONE
    }

    private fun overlayParams(flags: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT)

    override fun close() {
        main.removeCallbacksAndMessages(null)
        showTarget(false)
        hide()
    }

    private companion object {
        const val BUTTON = 56
        const val PILL = 150
        const val TARGET = 64
        const val MARGIN = 8
        val LAVENDER = Color.argb(240, 201, 187, 214)
        val AMBER = Color.argb(240, 240, 200, 120)
        val PURPLE = Color.rgb(110, 75, 168)
        val INK = Color.rgb(43, 37, 51)
    }
}

/** Bars drawn in code: a fixed waveform icon, waiting dots, or the live microphone level. */
internal class WaveView(context: Context) : View(context) {
    enum class Style { ICON, DOTS, LIVE }
    var style = Style.ICON
        set(value) { if (field != value) { field = value; invalidate() } }
    private val levels = FloatArray(11)
    private val silent = FloatArray(levels.size)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(43, 37, 51) }
    private val bar = RectF()

    fun reset() { levels.fill(0f); invalidate() }

    fun push(level: Float) {
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        // Speech RMS is small; a square root spreads quiet and loud voices over the bar height.
        levels[levels.size - 1] = kotlin.math.sqrt((level * 6f).coerceIn(0f, 1f))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val heights = when (style) {
            Style.ICON -> ICON
            Style.DOTS -> silent
            Style.LIVE -> levels
        }
        val barWidth = 3f * density
        val gap = if (style == Style.ICON) 3f * density else 4f * density
        // As many of the latest values as fit inside the rounded shape.
        val fits = ((width - 16f * density + gap) / (barWidth + gap)).toInt().coerceAtLeast(1)
        val count = minOf(heights.size, fits)
        val total = count * barWidth + (count - 1) * gap
        var x = (width - total) / 2f
        val middle = height / 2f
        val maxHalf = height * 0.32f
        for (index in heights.size - count until heights.size) {
            val value = heights[index]
            val half = maxOf(barWidth / 2f, value * maxHalf)
            bar.set(x, middle - half, x + barWidth, middle + half)
            canvas.drawRoundRect(bar, barWidth / 2f, barWidth / 2f, paint)
            x += barWidth + gap
        }
    }

    private companion object {
        val ICON = floatArrayOf(0.35f, 0.7f, 1f, 0.55f, 0.85f)
    }
}
