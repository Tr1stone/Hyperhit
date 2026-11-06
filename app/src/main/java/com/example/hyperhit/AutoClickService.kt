package com.example.hyperhit

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.example.hyperhit.automation.*
import com.example.hyperhit.overlay.AutomationOverlay

class AutoClickService : AccessibilityService(), DisplayManager.DisplayListener {
    companion object {
        var instance: AutoClickService? = null
            private set
    }
    private val taskHandler = Handler(Looper.getMainLooper())
    private val systemHandler = Handler(Looper.getMainLooper())
    private lateinit var controller: LoopController
    private lateinit var overlay: AutomationOverlay
    private lateinit var store: AutomationStore
    private var mode = Mode.TAP
    private var activePlan: AutomationPlan? = null
    private var screen: ScreenGeometry? = null
    private var registered = false
    private var picking = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stop("屏幕已锁定，请解锁后手动开始")
            cancelPicker()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (instance === this) return
        store = AutomationStore.get(this)
        overlay = AutomationOverlay(this, ::pick, ::runOrResume, { controller.pause() }, ::closeControls, ::openApp)
        controller = LoopController(object : TaskScheduler {
            override fun nowMs() = SystemClock.uptimeMillis()
            override fun post(delayMs: Long, task: () -> Unit) { taskHandler.postDelayed({ task() }, delayMs) }
            override fun clear() { taskHandler.removeCallbacksAndMessages(null) }
        }, object : GestureSink {
            override fun dispatch(action: GestureAction, completion: (Boolean) -> Unit): Boolean {
                if (geometry() != activePlan?.screen || !isUnlocked()) return false
                if (overlay.obscures(action.from)) {
                    message("控制条挡住了动作起点，请将控制条拖开")
                    return false
                }
                val path = Path().apply {
                    moveTo(action.from.x, action.from.y)
                    if (action.from != action.to) lineTo(action.to.x, action.to.y)
                }
                val gesture = GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(path, 0, action.durationMs)
                ).build()
                return try {
                    dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) { completion(true) }
                        override fun onCancelled(gestureDescription: GestureDescription?) { completion(false) }
                    }, systemHandler)
                } catch (_: IllegalStateException) { false }
                catch (_: SecurityException) { false }
            }
        }) { publish() }
        instance = this
        screen = geometry()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        getSystemService(DisplayManager::class.java).registerDisplayListener(this, systemHandler)
        registered = true
        publish()
    }

    @Suppress("DEPRECATION")
    private fun geometry(): ScreenGeometry {
        val display = getSystemService(WindowManager::class.java).defaultDisplay
        val size = Point()
        display.getRealSize(size)
        return ScreenGeometry(size.x, size.y, display.rotation)
    }
    private fun isUnlocked() = getSystemService(PowerManager::class.java).isInteractive &&
        !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun showControls(selectedMode: Mode) {
        if (!::controller.isInitialized) return
        stop()
        cancelPicker()
        mode = selectedMode
        overlay.showControls(mode)
        publish()
    }
    private fun pick() {
        if (controller.hasInFlight || controller.snapshot.phase in listOf(Phase.RUNNING, Phase.PAUSING)) {
            message("请先暂停，等当前动作完成后再选点")
            return
        }
        controller.stop()
        picking = true
        val expected = geometry()
        overlay.showPicker(mode, expected) { result ->
            picking = false
            if (result != null) runCatching {
                require(geometry() == expected) { "屏幕方向改变，请重新选点" }
                if (mode == Mode.TAP) store.addTap(result.first, expected)
                else store.setSwipe(result.first, result.second, expected)
            }.onFailure { message(it.message ?: "位置保存失败") }
            publish()
        }
        publish()
    }
    private fun runOrResume() {
        if (picking) return
        when (controller.snapshot.phase) {
            Phase.RUNNING -> Unit
            Phase.PAUSING -> message("当前手势结束后即暂停")
            else -> {
                if (controller.hasInFlight) { message("请等当前动作完成后重试"); return }
                runCatching {
                    require(isUnlocked()) { "请先解锁屏幕" }
                    val plan = store.plan(mode, geometry())
                    val starts = if (plan is TapPlan) plan.points else listOf((plan as SwipePlan).start)
                    require(starts.none(overlay::obscures)) { "控制条挡住了起点，请拖动顶部手柄移开" }
                    if (controller.snapshot.phase == Phase.PAUSED && activePlan == plan) controller.resume()
                    else { activePlan = plan; controller.start(plan) }
                }.onFailure { message(it.message ?: "无法开始，请检查配置") }
            }
        }
    }
    fun stop(reason: String? = null) {
        if (!::controller.isInitialized) return
        activePlan = null
        controller.stop(reason)
    }
    private fun closeControls() { stop(); cancelPicker(); overlay.close() }
    private fun cancelPicker() {
        if (!::overlay.isInitialized) return
        overlay.cancelPicker()
        picking = false
        publish()
    }
    private fun openApp() {
        controller.pause()
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        ))
    }
    private fun publish() {
        if (!::controller.isInitialized) return
        AutomationRuntime.publish(RuntimeUi(instance === this, mode, controller.snapshot, picking))
        if (::overlay.isInitialized) overlay.update(mode, controller.snapshot, !controller.hasInFlight)
    }
    private fun message(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { if (::controller.isInitialized) controller.pause() }
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) { checkGeometry() }
    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig); checkGeometry() }
    private fun checkGeometry() {
        if (!::controller.isInitialized) return
        val current = geometry()
        if (screen != current) {
            screen = current
            stop("屏幕方向或尺寸改变，请重新选点")
            store.invalidatePositions()
            cancelPicker()
            overlay.reposition()
        }
    }
    private fun cleanUp() {
        stop()
        if (::overlay.isInitialized) overlay.close()
        if (registered) {
            unregisterReceiver(screenReceiver)
            getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
            registered = false
        }
        if (instance === this) instance = null
        picking = false
        systemHandler.removeCallbacksAndMessages(null)
        AutomationRuntime.publish(RuntimeUi())
    }
    override fun onUnbind(intent: Intent?): Boolean { cleanUp(); return super.onUnbind(intent) }
    override fun onDestroy() { cleanUp(); super.onDestroy() }
}
