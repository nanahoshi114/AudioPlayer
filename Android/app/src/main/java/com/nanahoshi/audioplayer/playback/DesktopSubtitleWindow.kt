package com.nanahoshi.audioplayer.playback

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class DesktopSubtitleContent(
    val line: String,
    val settings: DesktopSubtitleSettings,
    val playWhenReady: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
)

internal class DesktopSubtitleWindow(
    service: Context,
    private val preferences: SubtitleDisplayPreferences,
    private val onToggle: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onNext: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    @Suppress("DEPRECATION")
    private val windowType = if (Build.VERSION.SDK_INT >= 26) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        WindowManager.LayoutParams.TYPE_PHONE
    }
    private val context = if (Build.VERSION.SDK_INT >= 30) {
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        service.createDisplayContext(display).createWindowContext(windowType, null)
    } else {
        service
    }
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val controlHeight = dp(48)
    private val params = WindowManager.LayoutParams(
        0, WindowManager.LayoutParams.WRAP_CONTENT, windowType, 0, PixelFormat.TRANSLUCENT,
    ).apply {
        setTitle("桌面字幕")
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }
    private var content: DesktopSubtitleContent? = null
    private var expanded = false
    private var draggedPosition: DesktopSubtitlePosition? = null
    private var attached = false
    private var retryWindowAt = 0L
    private val caption = DesktopSubtitleCaption(context).apply {
        label.setOnClickListener {
            expanded = !expanded
            render()
        }
        label.setOnTouchListener(CaptionDrag())
    }
    private val previous = button(android.R.drawable.ic_media_previous, "上一首", onPrevious)
    private val toggle = button(android.R.drawable.ic_media_pause, "暂停", onToggle)
    private val next = button(android.R.drawable.ic_media_next, "下一首", onNext)
    private val lock = button(android.R.drawable.ic_lock_lock, "锁定桌面字幕") {
        preferences.lockDesktop(true)
    }
    private val close = button(android.R.drawable.ic_menu_close_clear_cancel, "关闭桌面字幕") {
        preferences.enableDesktop(false)
    }
    private val controls = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        for (button in listOf(previous, toggle, next, lock, close)) {
            addView(button, LinearLayout.LayoutParams(0, controlHeight, 1f))
        }
    }
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(0xe61b1d23.toInt())
            cornerRadius = dp(18).toFloat()
        }
        addView(caption, LinearLayout.LayoutParams(-1, -2))
        addView(controls, LinearLayout.LayoutParams(-1, -2))
    }

    fun show(value: DesktopSubtitleContent?, scrollProgress: Float = 0f) {
        if (value == content) {
            if (!attached && value != null) render()
            caption.follow(scrollProgress)
            return
        }
        content = value
        if (value == null || value.settings.locked) {
            expanded = false
            draggedPosition = null
        }
        render()
        caption.follow(scrollProgress)
    }

    fun relayout() = render()

    private fun render() {
        val value = content
        if (value == null || (value.line.isBlank() && !expanded && draggedPosition == null)) {
            removeWindow()
            return
        }
        if (!attached && SystemClock.uptimeMillis() < retryWindowAt) return
        val text = value.line.ifBlank { "桌面字幕" }
        if (!caption.label.text.contentEquals(text)) caption.label.text = text
        controls.visibility = if (expanded) View.VISIBLE else View.GONE
        previous.isEnabled = value.hasPrevious
        next.isEnabled = value.hasNext
        toggle.setImageResource(if (value.playWhenReady) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        toggle.contentDescription = if (value.playWhenReady) "暂停" else "播放"
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (Build.VERSION.SDK_INT >= 30) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        }
        if (value.settings.locked) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        params.alpha = if (value.settings.locked && Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch.coerceAtMost(.8f)
        } else {
            1f
        }
        val safe = safeBounds()
        if (safe.isEmpty) {
            removeWindow()
            return
        }
        params.width = minOf(safe.width(), dp(320))
        val controlsHeight = if (expanded) controlHeight else 0
        val captionHeight = caption.fitHeight(params.width, safe.height(), controlsHeight)
        params.height = (captionHeight + controlsHeight).coerceAtMost(safe.height())
        val position = draggedPosition ?: value.settings.position
        val travel = travelBounds(safe)
        params.x = travel.left + (travel.width() * position.x.coerceIn(0f, 1f)).roundToInt()
        params.y = travel.top + (travel.height() * position.y.coerceIn(0f, 1f)).roundToInt()
        try {
            if (attached) {
                windowManager.updateViewLayout(root, params)
            } else {
                windowManager.addView(root, params)
                attached = true
            }
            retryWindowAt = 0L
        } catch (error: SecurityException) {
            windowUnavailable(error)
        } catch (error: WindowManager.BadTokenException) {
            windowUnavailable(error)
        } catch (error: WindowManager.InvalidDisplayException) {
            windowUnavailable(error)
        } catch (error: IllegalArgumentException) {
            windowUnavailable(error)
        }
    }

    private fun windowUnavailable(error: RuntimeException) {
        if (retryWindowAt == 0L) Log.w("DesktopSubtitleWindow", "Window unavailable: ${error.javaClass.simpleName}")
        retryWindowAt = SystemClock.uptimeMillis() + 1_000
        removeWindow()
        onUnavailable()
    }

    private fun removeWindow() {
        if (!attached) return
        attached = false
        try {
            windowManager.removeViewImmediate(root)
        } catch (_: IllegalArgumentException) {
            // The system may already have detached the window when permission was revoked.
        }
    }

    @Suppress("DEPRECATION")
    private fun safeBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(
                insets.left, insets.top,
                metrics.bounds.width() - insets.right, metrics.bounds.height() - insets.bottom,
            )
        }
        // On older versions the system already places non-layout-in-screen windows inside the app area.
        val size = Point()
        windowManager.defaultDisplay.getSize(size)
        return Rect(0, 0, size.x, size.y)
    }

    private fun travelBounds(safe: Rect) = Rect(
        safe.left, safe.top,
        (safe.right - params.width).coerceAtLeast(safe.left),
        (safe.bottom - params.height).coerceAtLeast(safe.top),
    )

    private fun button(icon: Int, label: String, action: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        imageTintList = ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(0x66ffffff, Color.WHITE),
        )
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, null)
        contentDescription = label
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    private inner class CaptionDrag : View.OnTouchListener {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var originX = 0
        private var originY = 0

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originX = params.x
                    originY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (draggedPosition != null || abs(dx) > slop || abs(dy) > slop) {
                        val travel = travelBounds(safeBounds())
                        draggedPosition = DesktopSubtitlePosition(
                            fraction(originX + dx, travel.left, travel.width()),
                            fraction(originY + dy, travel.top, travel.height()),
                        )
                        render()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val position = draggedPosition
                    draggedPosition = null
                    if (position == null) {
                        view.performClick()
                    } else if (position != preferences.desktop.value.position) {
                        preferences.moveDesktop(position)
                    } else {
                        render()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    draggedPosition = null
                    render()
                }
            }
            return true
        }

        private fun fraction(value: Float, start: Int, span: Int): Float =
            if (span <= 0) .5f else ((value - start) / span).coerceIn(0f, 1f)
    }
}

internal class DesktopSubtitleCaption(context: Context) : ScrollView(context) {
    val label = TextView(context).apply {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        val density = resources.displayMetrics.density
        val horizontal = (12 * density).roundToInt()
        val vertical = (8 * density).roundToInt()
        setPadding(horizontal, vertical, horizontal, vertical)
    }
    private var progress = 0f

    init {
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(label, FrameLayout.LayoutParams(-1, -2))
    }

    fun fitHeight(width: Int, availableHeight: Int, controlsHeight: Int): Int {
        label.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        val limit = minOf(availableHeight / 2, (availableHeight - controlsHeight).coerceAtLeast(0))
        val height = minOf(label.measuredHeight, limit)
        if (layoutParams.height != height) {
            layoutParams = layoutParams.apply { this.height = height }
        }
        return height
    }

    fun follow(scrollProgress: Float) {
        progress = scrollProgress
        val range = (label.height - height).coerceAtLeast(0)
        scrollTo(0, (range * progress).roundToInt())
    }

    // The caption keeps its drag gesture for moving the floating window.
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        follow(progress)
    }
}
