package com.example.hyperhit.automation

interface TaskScheduler {
    fun nowMs(): Long
    fun post(delayMs: Long, task: () -> Unit)
    fun clear()
}
interface GestureSink {
    fun dispatch(action: GestureAction, completion: (Boolean) -> Unit): Boolean
}
/** Called on one thread. A gesture is completed before another can be dispatched. */
class LoopController(
    private val scheduler: TaskScheduler,
    private val sink: GestureSink,
    private val onChange: (LoopSnapshot) -> Unit
) {
    var hasInFlight = false
        private set
    var snapshot = LoopSnapshot()
        private set
    private var generation = 0L
    private var plan: AutomationPlan? = null

    fun start(newPlan: AutomationPlan): Boolean {
        if (hasInFlight) return false
        stop()
        plan = newPlan
        publish(LoopSnapshot(newPlan.mode, Phase.RUNNING))
        return dispatchNext()
    }

    fun pause() {
        if (snapshot.phase != Phase.RUNNING) return
        scheduler.clear()
        publish(snapshot.copy(phase = if (hasInFlight) Phase.PAUSING else Phase.PAUSED))
    }

    fun resume(): Boolean {
        if (snapshot.phase != Phase.PAUSED || hasInFlight || plan == null) return false
        publish(snapshot.copy(phase = Phase.RUNNING, problem = null))
        return dispatchNext()
    }

    fun stop(reason: String? = null) {
        generation++
        scheduler.clear()
        plan = null
        publish(snapshot.copy(phase = Phase.IDLE, problem = reason))
    }

    private fun dispatchNext(): Boolean {
        val currentPlan = plan ?: return false
        if (snapshot.phase != Phase.RUNNING || hasInFlight) return false
        val activeGeneration = generation
        val startedAt = scheduler.nowMs()
        hasInFlight = true
        val accepted = sink.dispatch(currentPlan.actionAt(snapshot.completed)) { success ->
            hasInFlight = false
            if (activeGeneration != generation) {
                publish(snapshot)
                return@dispatch
            }
            if (!success) {
                publish(snapshot.copy(phase = Phase.PAUSED, problem = "手势被系统打断，已暂停"))
                return@dispatch
            }
            val completed = snapshot.completed + 1
            when {
                currentPlan.isFinished(completed) ->
                    publish(snapshot.copy(phase = Phase.COMPLETED, completed = completed))
                snapshot.phase == Phase.PAUSING ->
                    publish(snapshot.copy(phase = Phase.PAUSED, completed = completed))
                else -> {
                    publish(snapshot.copy(completed = completed))
                    scheduler.post(currentPlan.nextDelay(scheduler.nowMs() - startedAt)) {
                        if (activeGeneration == generation) dispatchNext()
                    }
                }
            }
        }
        if (!accepted) {
            hasInFlight = false
            if (activeGeneration == generation) {
                publish(snapshot.copy(phase = Phase.PAUSED, problem = "系统未接受手势，请检查位置和服务状态"))
            }
        }
        return accepted
    }

    private fun publish(value: LoopSnapshot) {
        snapshot = value
        onChange(value)
    }
}
