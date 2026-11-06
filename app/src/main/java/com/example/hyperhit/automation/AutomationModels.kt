package com.example.hyperhit.automation

data class ScreenPoint(val x: Float, val y: Float) {
    fun label() = "X=${x.toInt()}，Y=${y.toInt()}"
}

data class ScreenGeometry(val width: Int, val height: Int, val rotation: Int) {
    init {
        require(width > 0 && height > 0 && rotation in 0..3) { "屏幕尺寸无效" }
    }

    fun contains(point: ScreenPoint) = point.x.isFinite() && point.y.isFinite() &&
        point.x >= 0 && point.y >= 0 && point.x < width && point.y < height
}

enum class Mode { TAP, SWIPE }
enum class Phase { IDLE, RUNNING, PAUSING, PAUSED, COMPLETED }
data class GestureAction(val from: ScreenPoint, val to: ScreenPoint, val durationMs: Long)

sealed interface AutomationPlan {
    val screen: ScreenGeometry
    val mode: Mode
    fun actionAt(index: Long): GestureAction
    fun isFinished(completed: Long): Boolean
    fun nextDelay(elapsedMs: Long): Long
}

data class TapPlan(
    val points: List<ScreenPoint>,
    override val screen: ScreenGeometry,
    val intervalMs: Long,
    val rounds: Int
) : AutomationPlan {
    override val mode = Mode.TAP
    init {
        require(points.size in 1..20 && points.all(screen::contains)) { "请重新选择有效的点击位置" }
        require(intervalMs in 100L..60_000L) { "点击间隔应为 100～60000 毫秒" }
        require(rounds in 1..10_000) { "连点轮数应为 1～10000" }
    }
    override fun actionAt(index: Long): GestureAction {
        val point = points[(index % points.size).toInt()]
        return GestureAction(point, point, 50)
    }
    override fun isFinished(completed: Long) = completed >= points.size.toLong() * rounds
    override fun nextDelay(elapsedMs: Long) = (intervalMs - elapsedMs).coerceAtLeast(0)
}

data class SwipePlan(
    val start: ScreenPoint,
    val end: ScreenPoint,
    override val screen: ScreenGeometry,
    val durationMs: Long,
    val gapMs: Long
) : AutomationPlan {
    override val mode = Mode.SWIPE
    init {
        require(screen.contains(start) && screen.contains(end)) { "请重新选择有效的滑动路径" }
        val dx = end.x - start.x
        val dy = end.y - start.y
        require(dx * dx + dy * dy >= 4f) { "请从 A 拖动到不同的 B 位置" }
        require(durationMs in 100L..10_000L) { "滑动时长应为 100～10000 毫秒" }
        require(gapMs in 100L..60_000L) { "滑动间隔应为 100～60000 毫秒" }
    }
    override fun actionAt(index: Long) = GestureAction(start, end, durationMs)
    override fun isFinished(completed: Long) = false
    override fun nextDelay(elapsedMs: Long) = gapMs
}

data class LoopSnapshot(
    val mode: Mode? = null,
    val phase: Phase = Phase.IDLE,
    val completed: Long = 0,
    val problem: String? = null
)
