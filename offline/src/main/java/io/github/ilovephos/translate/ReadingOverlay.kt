package io.github.ilovephos.translate

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.yomu.pipeline.typesetting.TypesetBubble
import kotlin.math.roundToInt
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** Main-thread-only floating controls and touch-through English lettering. */
class ReadingOverlay(
    private val context: Context,
    private val isJapanese: Boolean,
    private val onTranslate: () -> Unit,
    private val onToggleOcr: () -> String,
    private val onMovement: () -> Unit,
    private val onVisibilityChanged: () -> Unit,
    private val onStop: () -> Unit
) {
    private val window = context.getSystemService(WindowManager::class.java)
    private val status = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        text = "Loading…"
        setPadding(dp(8), dp(2), dp(8), dp(4))
        maxLines = 2
        minHeight = dp(36)
    }
    private val controls = object : LinearLayout(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { onMovement(); return true }
            return super.dispatchTouchEvent(event)
        }
    }
    private val controlParams = parameters(dp(350), -2).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(40)
        flags = flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
    }
    private val letterView = LettersView(context)
    private var lettersAdded = false
    private var controlsAdded = false
    var hasLetters = false
        private set
    var originalHeld = false
        private set

    init {
        controls.apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 39, 59)); cornerRadius = dp(14).toFloat()
            }
        }
        val row = LinearLayout(context)
        row.addView(TextView(context).apply {
            text = "⠿"; textSize = 24f; setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(8), dp(8))
            var startX = 0f; var startY = 0f; var originalX = 0; var originalY = 0
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX; startY = event.rawY
                        originalX = controlParams.x; originalY = controlParams.y
                        onMovement()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val bounds = window.currentWindowMetrics.bounds
                        controlParams.x = (originalX + event.rawX - startX).roundToInt().coerceIn(0, (bounds.width() - controls.width).coerceAtLeast(0))
                        controlParams.y = (originalY + event.rawY - startY).roundToInt().coerceIn(0, (bounds.height() - controls.height).coerceAtLeast(0))
                        window.updateViewLayout(controls, controlParams)
                    }
                    MotionEvent.ACTION_UP -> performClick()
                }
                true
            }
        })
        row.addView(button("Translate") { onTranslate() })
        if (isJapanese) {
            val ocrBtn = button("AR") {}
            ocrBtn.contentDescription = "Toggle OCR engine: AR (SOTA Accuracy) vs NAR (Fast)"
            ocrBtn.setOnClickListener {
                ocrBtn.text = onToggleOcr()
            }
            row.addView(ocrBtn)
        }
        row.addView(button("Original") {}.apply {
            contentDescription = "Hold to reveal original Japanese or Chinese"
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { originalHeld = true; letterView.visibility = View.INVISIBLE; onVisibilityChanged() }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        originalHeld = false
                        letterView.visibility = View.VISIBLE
                        onVisibilityChanged()
                        if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    }
                }
                true
            }
        })
        row.addView(button("×") { onStop() }.apply { contentDescription = "Stop reading" })
        controls.addView(row)
        controls.addView(status)
    }

    fun showControls() {
        if (!controlsAdded) { window.addView(controls, controlParams); controlsAdded = true }
    }

    fun message(value: String) { status.text = value }

    fun controlBounds(): RectF {
        val location = IntArray(2)
        controls.getLocationOnScreen(location)
        return RectF(location[0].toFloat(), location[1].toFloat(),
            (location[0] + controls.width).toFloat(), (location[1] + controls.height).toFloat())
    }

    /** Mirror the actual letter window's draw transform, including any system-applied window offset. */
    fun translationBounds(): List<SampleRegion> {
        if (!lettersAdded || !hasLetters) return emptyList()
        val location = IntArray(2)
        letterView.getLocationOnScreen(location)
        val scaleX = letterView.width.toFloat() / letterView.sourceWidth
        val scaleY = letterView.height.toFloat() / letterView.sourceHeight
        val padding = dp(2).toFloat()
        return letterView.bubbles.map { bubble ->
            val box = bubble.boundingBox
            SampleRegion(location[0] + box[0] * scaleX - padding, location[1] + box[1] * scaleY - padding,
                location[0] + box[2] * scaleX + padding, location[1] + box[3] * scaleY + padding)
        }
    }

    fun show(bubbles: List<TypesetBubble>, width: Int, height: Int) {
        letterView.bubbles = bubbles
        letterView.sourceWidth = width
        letterView.sourceHeight = height
        letterView.visibility = if (originalHeld) View.INVISIBLE else View.VISIBLE
        if (!lettersAdded) {
            val params = parameters(-1, -1).apply {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                gravity = Gravity.TOP or Gravity.START
                // Android permits touch-through for untrusted overlay windows only within its opacity limit.
                alpha = 0.78f
            }
            window.addView(letterView, params)
            lettersAdded = true
            if (controlsAdded) { window.removeView(controls); window.addView(controls, controlParams) }
        }
        hasLetters = bubbles.isNotEmpty()
        letterView.invalidate()
    }

    fun clear() {
        hasLetters = false
        letterView.bubbles = emptyList()
        if (lettersAdded) { window.removeView(letterView); lettersAdded = false }
    }

    /** Forces a new composited frame inside the ignored controls, even for a static reader. */
    suspend fun pulseAndWait() {
        controls.alpha = if (controls.alpha == 1f) 0.99f else 1f
        controls.invalidate()
        awaitDraw()
    }

    suspend fun awaitDraw() {
        repeat(2) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val choreographer = Choreographer.getInstance()
                val callback = Choreographer.FrameCallback { if (continuation.isActive) continuation.resume(Unit) }
                continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
                choreographer.postFrameCallback(callback)
            }
        }
    }

    fun close() {
        clear()
        if (controlsAdded) { window.removeView(controls); controlsAdded = false }
    }

    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 12f
        minWidth = dp(48); minimumWidth = dp(48); minHeight = dp(48)
        setPadding(dp(8), 0, dp(8), 0)
        setOnClickListener { action() }
    }

    private fun parameters(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()
}

private class LettersView(context: Context) : View(context) {
    var bubbles: List<TypesetBubble> = emptyList()
    var sourceWidth = 1
    var sourceHeight = 1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val save = canvas.save()
        canvas.scale(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
        // Pass 1: draw all bubble backgrounds
        for (bubble in bubbles) {
            val box = bubble.boundingBox
            paint.color = bubble.backgroundColor
            canvas.drawRoundRect(box[0], box[1], box[2], box[3], 8f, 8f, paint)
        }
        // Pass 2: draw all English text on top
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        for (bubble in bubbles) {
            val box = bubble.boundingBox
            paint.color = bubble.textColor
            paint.textSize = bubble.fontSize
            val cx = (box[0] + box[2]) / 2f
            val total = bubble.textLines.size * bubble.lineHeight
            var baseline = (box[1] + box[3] - total) / 2f + (bubble.lineHeight - paint.fontMetrics.ascent - paint.fontMetrics.descent) / 2f
            for (line in bubble.textLines) {
                canvas.drawText(line, cx, baseline, paint)
                baseline += bubble.lineHeight
            }
        }
        canvas.restoreToCount(save)
    }
}
