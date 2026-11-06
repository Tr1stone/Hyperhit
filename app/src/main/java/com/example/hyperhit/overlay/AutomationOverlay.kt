package com.example.hyperhit.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.hyperhit.automation.*

/** Windows exist only while explicitly shown. No screenshot or app content is read. */
class AutomationOverlay(
    private val context: Context,
    private val onPick: () -> Unit,
    private val onRun: () -> Unit,
    private val onPause: () -> Unit,
    private val onClose: () -> Unit,
    private val onOpenApp: () -> Unit
) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var picker: PickerView? = null
    private var pickerCallback: ((Pair<ScreenPoint, ScreenPoint>?) -> Unit)? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var title: TextView? = null
    private var status: TextView? = null
    private var pickButton: Button? = null
    private var toggleButton: Button? = null
    private var pauseButton: Button? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    @SuppressLint("RtlHardcoded") // rawX is measured from the physical left edge in every locale.
    private fun parameters(width: Int, height: Int) = WindowManager.LayoutParams(
        width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    @SuppressLint("ClickableViewAccessibility") // Keep Button's click action; pause also acts on DOWN.
    fun showControls(mode: Mode) {
        if (panel != null) { panel?.visibility = View.VISIBLE; return }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(35, 34, 50)); cornerRadius = dp(18).toFloat()
            }
            elevation = dp(8).toFloat()
        }
        val handle = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 15f
            gravity = Gravity.CENTER; minHeight = dp(40)
            contentDescription = "拖动此处移动悬浮控制"
        }
        title = handle
        status = TextView(context).apply {
            setTextColor(Color.rgb(210, 211, 230)); textSize = 12f
            setPadding(dp(4), 0, dp(4), dp(4))
        }
        root.addView(handle)
        root.addView(status)
        fun row(vararg buttons: Button) {
            val line = LinearLayout(context)
            buttons.forEach { line.addView(it, LinearLayout.LayoutParams(0, dp(48), 1f)) }
            root.addView(line)
        }
        pickButton = button("选点", onPick)
        toggleButton = button("开始", onRun)
        pauseButton = button("暂停", onPause).apply {
            setOnTouchListener { _, event ->
                // Clear the next scheduled gesture before it can cancel this user's touch.
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onPause()
                false
            }
        }
        row(pickButton!!, button("返回 App", onOpenApp).apply {
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onPause()
                false
            }
        })
        row(toggleButton!!, pauseButton!!)
        row(button("关闭悬浮控制", onClose).apply {
            contentDescription = "结束当前任务并关闭悬浮控制"
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onClose()
                false
            }
        })
        val params = parameters(dp(216).coerceAtMost(displaySize().x - dp(16)), WindowManager.LayoutParams.WRAP_CONTENT)
            .apply { x = dp(12); y = dp(80) }
        panelParams = params
        attachDrag(handle, root, params)
        try {
            manager.addView(root, params)
            panel = root
            update(mode, LoopSnapshot(), true)
            reposition()
        } catch (_: RuntimeException) {
            Toast.makeText(context, "无法显示悬浮控制，请检查无障碍服务", Toast.LENGTH_LONG).show()
        }
    }

    private fun button(label: String, click: () -> Unit) = Button(context).apply {
        text = label; textSize = 12f; isAllCaps = false
        setPadding(0, 0, 0, 0); minWidth = 0; minimumWidth = 0
        setOnClickListener { click() }
    }

    fun update(mode: Mode, snapshot: LoopSnapshot, noGestureInFlight: Boolean) {
        title?.text = if (mode == Mode.TAP) "↕ Hyperhit · 连点" else "↕ Hyperhit · 循环滑动"
        val phase = if (snapshot.mode == mode) snapshot.phase else Phase.IDLE
        val phaseText = when (phase) {
            Phase.IDLE -> "待开始"
            Phase.RUNNING -> "运行中"
            Phase.PAUSING -> "当前动作结束后暂停"
            Phase.PAUSED -> "已暂停"
            Phase.COMPLETED -> "本轮任务已完成"
        }
        status?.text = snapshot.problem ?: if (mode == Mode.TAP && snapshot.mode == mode)
            "$phaseText · 已点击 ${snapshot.completed} 次" else phaseText
        pickButton?.text = if (mode == Mode.TAP) "添加点" else "选 A→B"
        pickButton?.isEnabled = noGestureInFlight && phase !in listOf(Phase.RUNNING, Phase.PAUSING)
        toggleButton?.text = when (phase) {
            Phase.RUNNING -> "运行中"
            Phase.PAUSING -> "暂停中"
            Phase.PAUSED -> "继续"
            else -> "开始"
        }
        toggleButton?.isEnabled = noGestureInFlight && phase !in listOf(Phase.RUNNING, Phase.PAUSING)
        pauseButton?.isEnabled = phase == Phase.RUNNING
    }

    fun showPicker(mode: Mode, screen: ScreenGeometry, done: (Pair<ScreenPoint, ScreenPoint>?) -> Unit) {
        cancelPicker()
        panel?.visibility = View.GONE
        pickerCallback = done
        val root = PickerView(context, mode, screen) { finishPicker(it) }
        picker = root
        try {
            manager.addView(root, parameters(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT))
        } catch (_: RuntimeException) {
            picker = null
            panel?.visibility = View.VISIBLE
            pickerCallback = null
            Toast.makeText(context, "无法显示选点窗口", Toast.LENGTH_LONG).show()
            done(null)
        }
    }
    private fun finishPicker(result: Pair<ScreenPoint, ScreenPoint>?) {
        picker?.let { if (it.isAttachedToWindow) manager.removeView(it) }
        picker = null
        panel?.visibility = View.VISIBLE
        val callback = pickerCallback
        pickerCallback = null
        callback?.invoke(result)
    }
    fun cancelPicker() { if (picker != null || pickerCallback != null) finishPicker(null) }
    fun close() {
        cancelPicker()
        panel?.let { if (it.isAttachedToWindow) manager.removeView(it) }
        panel = null; title = null; status = null; pickButton = null; toggleButton = null; pauseButton = null
    }
    fun obscures(point: ScreenPoint): Boolean {
        val view = panel ?: return false
        if (!view.isShown) return false
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return point.x >= location[0] && point.x < location[0] + view.width &&
            point.y >= location[1] && point.y < location[1] + view.height
    }
    @Suppress("DEPRECATION")
    private fun displaySize() = Point().also { manager.defaultDisplay.getRealSize(it) }
    fun reposition() {
        val view = panel ?: return
        view.post {
            if (view !== panel || !view.isAttachedToWindow) return@post
            val params = panelParams ?: return@post
            clamp(view, params)
            manager.updateViewLayout(view, params)
        }
    }
    private fun clamp(view: View, params: WindowManager.LayoutParams) {
        val size = displaySize()
        params.x = params.x.coerceIn(0, (size.x - view.width).coerceAtLeast(0))
        params.y = params.y.coerceIn(dp(28), (size.y - view.height - dp(24)).coerceAtLeast(dp(28)))
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(handle: View, root: View, params: WindowManager.LayoutParams) {
        var rawX = 0f; var rawY = 0f; var initialX = 0; var initialY = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onPause()
                    rawX = event.rawX; rawY = event.rawY; initialX = params.x; initialY = params.y
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - rawX).toInt()
                    params.y = initialY + (event.rawY - rawY).toInt()
                    clamp(root, params)
                    if (root.isAttachedToWindow) manager.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> handle.performClick()
            }
            true
        }
    }
}

@SuppressLint("ClickableViewAccessibility", "RtlHardcoded")
private class PickerView(
    context: Context,
    private val mode: Mode,
    private val screen: ScreenGeometry,
    private val done: (Pair<ScreenPoint, ScreenPoint>?) -> Unit
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = TextView(context)
    private var start: ScreenPoint? = null
    private var current: ScreenPoint? = null
    private fun dp(value: Int) = (value * density).toInt()
    init {
        setWillNotDraw(false)
        setBackgroundColor(0x22000000)
        isClickable = true
        val banner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(35, 34, 50))
        }
        label.apply {
            text = if (mode == Mode.TAP) "按下显示坐标，松开保存点击点\n提示条可上下拖动，避开目标"
                else "从 A 按住拖到 B，松开保存路径\n提示条可上下拖动，避开目标"
            textSize = 15f; setTextColor(Color.WHITE)
        }
        banner.addView(label)
        banner.addView(Button(context).apply { text = "取消选点"; setOnClickListener { done(null) } })
        val bannerParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            topMargin = dp(36); leftMargin = dp(12); rightMargin = dp(12)
        }
        addView(banner, bannerParams)
        var initialY = 0; var downY = 0f
        banner.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { initialY = bannerParams.topMargin; downY = event.rawY }
                MotionEvent.ACTION_MOVE -> {
                    bannerParams.topMargin = (initialY + event.rawY.toInt() - downY.toInt())
                        .coerceIn(0, (height - banner.height).coerceAtLeast(0))
                    banner.layoutParams = bannerParams
                }
                MotionEvent.ACTION_UP -> banner.performClick()
                MotionEvent.ACTION_POINTER_DOWN -> done(null)
            }
            true
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val point = ScreenPoint(event.rawX, event.rawY)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { start = point; current = point }
            MotionEvent.ACTION_MOVE -> current = point
            MotionEvent.ACTION_POINTER_DOWN -> { done(null); return true }
            MotionEvent.ACTION_CANCEL -> { start = null; current = null }
            MotionEvent.ACTION_UP -> {
                val first = start ?: return true
                performClick()
                if (!screen.contains(point) || !screen.contains(first)) {
                    label.text = "请在屏幕内容范围内重新选点"
                } else if (mode == Mode.SWIPE &&
                    (point.x - first.x) * (point.x - first.x) + (point.y - first.y) * (point.y - first.y) < dp(8) * dp(8)) {
                    label.text = "请按住 A 并拖动到 B，再松开"
                } else {
                    done(if (mode == Mode.TAP) point to point else first to point)
                    return true
                }
                start = null; current = null
                invalidate()
                return true
            }
        }
        current?.let { label.text = if (mode == Mode.TAP) it.label()
            else "A：${start?.label()}\n当前位置：${it.label()}" }
        invalidate()
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val point = current ?: return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val x = point.x - location[0]; val y = point.y - location[1]
        paint.color = Color.rgb(255, 186, 65); paint.strokeWidth = 2 * density
        canvas.drawLine(0f, y, width.toFloat(), y, paint)
        canvas.drawLine(x, 0f, x, height.toFloat(), paint)
        paint.style = Paint.Style.STROKE
        canvas.drawCircle(x, y, 12 * density, paint)
        start?.takeIf { mode == Mode.SWIPE }?.let {
            val ax = it.x - location[0]; val ay = it.y - location[1]
            canvas.drawCircle(ax, ay, 8 * density, paint)
            canvas.drawLine(ax, ay, x, y, paint)
        }
        paint.style = Paint.Style.FILL
    }
}
