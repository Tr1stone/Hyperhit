package com.example.hyperhit

import android.content.Context
import com.example.hyperhit.automation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class TapConfiguration(val points: List<ScreenPoint> = emptyList(), val screen: ScreenGeometry? = null,
    val intervalMs: Long = 500, val rounds: Int = 10)
data class SwipeConfiguration(val start: ScreenPoint? = null, val end: ScreenPoint? = null,
    val screen: ScreenGeometry? = null, val durationMs: Long = 500, val gapMs: Long = 500)
data class ToolConfiguration(val tap: TapConfiguration = TapConfiguration(),
    val swipe: SwipeConfiguration = SwipeConfiguration())
data class RuntimeUi(val connected: Boolean = false, val mode: Mode = Mode.TAP,
    val loop: LoopSnapshot = LoopSnapshot(), val picking: Boolean = false)
object AutomationRuntime {
    private val mutable = MutableStateFlow(RuntimeUi())
    val state = mutable.asStateFlow()
    internal fun publish(value: RuntimeUi) { mutable.value = value }
}

/** Retains the application context, never an Activity or its picker callback. */
class AutomationStore private constructor(context: Context) {
    companion object {
        @Volatile private var instance: AutomationStore? = null
        fun get(context: Context): AutomationStore = instance ?: synchronized(this) {
            instance ?: AutomationStore(context.applicationContext).also { instance = it }
        }
    }
    private val preferences = context.getSharedPreferences("automation_settings", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val state = mutable.asStateFlow()

    fun saveTapSettings(intervalMs: Long, rounds: Int) {
        require(intervalMs in 100L..60_000L) { "点击间隔应为 100～60000 毫秒" }
        require(rounds in 1..10_000) { "连点轮数应为 1～10000" }
        save(mutable.value.copy(tap = mutable.value.tap.copy(intervalMs = intervalMs, rounds = rounds)))
    }
    fun saveSwipeSettings(durationMs: Long, gapMs: Long) {
        require(durationMs in 100L..10_000L) { "滑动时长应为 100～10000 毫秒" }
        require(gapMs in 100L..60_000L) { "滑动间隔应为 100～60000 毫秒" }
        save(mutable.value.copy(swipe = mutable.value.swipe.copy(durationMs = durationMs, gapMs = gapMs)))
    }
    fun addTap(point: ScreenPoint, screen: ScreenGeometry) {
        require(screen.contains(point)) { "位置超出屏幕，请重新选点" }
        val current = mutable.value.tap
        val previous = if (current.screen == screen) current.points else emptyList()
        require(previous.size < 20) { "最多保存 20 个点击点，请先移除旧位置" }
        save(mutable.value.copy(tap = current.copy(points = previous + point, screen = screen)))
    }
    fun setSwipe(start: ScreenPoint, end: ScreenPoint, screen: ScreenGeometry) {
        val current = mutable.value.swipe
        SwipePlan(start, end, screen, current.durationMs, current.gapMs)
        save(mutable.value.copy(swipe = current.copy(start = start, end = end, screen = screen)))
    }
    fun removeLastTap() {
        save(mutable.value.copy(tap = mutable.value.tap.copy(points = mutable.value.tap.points.dropLast(1))))
    }
    fun invalidatePositions() {
        val current = mutable.value
        save(current.copy(
            tap = current.tap.copy(points = emptyList(), screen = null),
            swipe = current.swipe.copy(start = null, end = null, screen = null)
        ))
    }
    fun clear(mode: Mode) {
        val current = mutable.value
        save(if (mode == Mode.TAP) current.copy(tap = current.tap.copy(points = emptyList(), screen = null))
        else current.copy(swipe = current.swipe.copy(start = null, end = null, screen = null)))
    }
    fun plan(mode: Mode, screen: ScreenGeometry): AutomationPlan {
        val current = mutable.value
        return if (mode == Mode.TAP) {
            val tap = current.tap
            require(tap.points.isNotEmpty()) { "请先在悬浮控制中添加点击位置" }
            require(tap.screen == screen) { "屏幕尺寸或方向改变，请重新选点" }
            TapPlan(tap.points.toList(), screen, tap.intervalMs, tap.rounds)
        } else {
            val swipe = current.swipe
            val start = requireNotNull(swipe.start) { "请先在悬浮控制中选择 A→B 路径" }
            val end = requireNotNull(swipe.end) { "请重新选择滑动终点" }
            require(swipe.screen == screen) { "屏幕尺寸或方向改变，请重新选择路径" }
            SwipePlan(start, end, screen, swipe.durationMs, swipe.gapMs)
        }
    }
    private fun pointJson(p: ScreenPoint) = JSONArray().put(p.x.toDouble()).put(p.y.toDouble())
    private fun screenJson(s: ScreenGeometry?) = s?.let { JSONArray().put(it.width).put(it.height).put(it.rotation) }
    private fun save(value: ToolConfiguration) {
        val tap = value.tap
        val swipe = value.swipe
        val json = JSONObject().put("tapInterval", tap.intervalMs).put("tapRounds", tap.rounds)
            .put("points", JSONArray().apply { tap.points.forEach { put(pointJson(it)) } })
            .put("tapScreen", screenJson(tap.screen)).put("swipeDuration", swipe.durationMs)
            .put("swipeGap", swipe.gapMs).put("swipeScreen", screenJson(swipe.screen))
        swipe.start?.let { json.put("start", pointJson(it)) }
        swipe.end?.let { json.put("end", pointJson(it)) }
        preferences.edit().putString("configuration", json.toString()).apply()
        mutable.value = value
    }
    private fun load(): ToolConfiguration = runCatching {
        val json = JSONObject(preferences.getString("configuration", "{}") ?: "{}")
        fun point(a: JSONArray) = ScreenPoint(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
        fun screen(key: String) = json.optJSONArray(key)?.let { ScreenGeometry(it.getInt(0), it.getInt(1), it.getInt(2)) }
        val tapScreen = screen("tapScreen")
        val points = json.optJSONArray("points")?.let { list ->
            (0 until minOf(list.length(), 20)).map { point(list.getJSONArray(it)) }
        }.orEmpty().filter { tapScreen?.contains(it) == true }
        val swipeScreen = screen("swipeScreen")
        val start = json.optJSONArray("start")?.let(::point)?.takeIf { swipeScreen?.contains(it) == true }
        val end = json.optJSONArray("end")?.let(::point)?.takeIf { swipeScreen?.contains(it) == true }
        ToolConfiguration(
            TapConfiguration(points, tapScreen, json.optLong("tapInterval", 500).coerceIn(100, 60_000),
                json.optInt("tapRounds", 10).coerceIn(1, 10_000)),
            SwipeConfiguration(start, end, swipeScreen, json.optLong("swipeDuration", 500).coerceIn(100, 10_000),
                json.optLong("swipeGap", 500).coerceIn(100, 60_000))
        )
    }.getOrDefault(ToolConfiguration())
}
