package com.example.hyperhit.automation

import org.junit.Assert.*
import org.junit.Test
import java.util.PriorityQueue

class LoopControllerTest {
    private val screen = ScreenGeometry(1080, 2400, 0)
    private val a = ScreenPoint(100f, 300f)
    private val b = ScreenPoint(800f, 1800f)

    private class Clock : TaskScheduler {
        data class Job(val due: Long, val order: Int, val task: () -> Unit)
        val jobs = PriorityQueue<Job>(compareBy<Job> { it.due }.thenBy { it.order })
        var time = 0L
        private var order = 0
        override fun nowMs() = time
        override fun post(delayMs: Long, task: () -> Unit) {
            jobs.add(Job(time + delayMs, order++, task))
        }
        override fun clear() = jobs.clear()
        fun next() {
            val job = jobs.remove()
            time = job.due
            job.task()
        }
    }

    private class Sink : GestureSink {
        val actions = mutableListOf<GestureAction>()
        var callback: ((Boolean) -> Unit)? = null
        var accept = true
        override fun dispatch(action: GestureAction, completion: (Boolean) -> Unit): Boolean {
            if (!accept) return false
            check(callback == null) { "Gestures must never overlap" }
            actions.add(action)
            callback = completion
            return true
        }
        fun finish(success: Boolean = true) {
            val pending = checkNotNull(callback)
            callback = null
            pending(success)
        }
    }

    private class Fixture {
        val clock = Clock()
        val sink = Sink()
        var latest = LoopSnapshot()
        val loop = LoopController(clock, sink) { latest = it }
    }

    @Test fun tapsVisitAllPointsForEachRoundThenFinish() {
        val f = Fixture()
        assertTrue(f.loop.start(TapPlan(listOf(a, b), screen, 500, 2)))
        repeat(4) { index ->
            assertEquals(if (index % 2 == 0) a else b, f.sink.actions.last().from)
            f.sink.finish()
            if (index < 3) f.clock.next()
        }
        assertEquals(4, f.sink.actions.size)
        assertEquals(4L, f.latest.completed)
        assertEquals(Phase.COMPLETED, f.latest.phase)
        assertTrue(f.clock.jobs.isEmpty())
    }

    @Test fun tapIntervalIsMeasuredBetweenGestureStarts() {
        val f = Fixture()
        f.loop.start(TapPlan(listOf(a), screen, 500, 2))
        f.clock.time = 60
        f.sink.finish()
        f.clock.next()
        assertEquals(500L, f.clock.time)
    }

    @Test fun swipesKeepRepeatingWithNoRepeatLimit() {
        val f = Fixture()
        f.loop.start(SwipePlan(a, b, screen, 300, 700))
        repeat(1200) {
            assertEquals(a, f.sink.actions.last().from)
            assertEquals(b, f.sink.actions.last().to)
            f.sink.finish()
            f.clock.next()
        }
        assertEquals(1201, f.sink.actions.size)
        assertEquals(Phase.RUNNING, f.latest.phase)
    }

    @Test fun swipeGapIsMeasuredAfterGestureCompletion() {
        val f = Fixture()
        f.loop.start(SwipePlan(a, b, screen, 300, 700))
        f.clock.time = 300
        f.sink.finish()
        f.clock.next()
        assertEquals(1000L, f.clock.time)
    }

    @Test fun pauseDuringSwipeWaitsForCurrentGestureAndDoesNotQueueAnother() {
        val f = Fixture()
        f.loop.start(SwipePlan(a, b, screen, 300, 700))
        f.loop.pause()
        assertEquals(Phase.PAUSING, f.latest.phase)
        f.sink.finish()
        assertEquals(Phase.PAUSED, f.latest.phase)
        assertTrue(f.clock.jobs.isEmpty())
        assertEquals(1, f.sink.actions.size)
    }

    @Test fun pauseDuringGapCancelsScheduledGesture() {
        val f = Fixture()
        f.loop.start(SwipePlan(a, b, screen, 300, 700))
        f.sink.finish()
        f.loop.pause()
        assertEquals(Phase.PAUSED, f.latest.phase)
        assertTrue(f.clock.jobs.isEmpty())
    }

    @Test fun resumeRestartsWholeSwipeFromA() {
        val f = Fixture()
        f.loop.start(SwipePlan(a, b, screen, 300, 700))
        f.loop.pause()
        f.sink.finish()
        assertTrue(f.loop.resume())
        assertEquals(2, f.sink.actions.size)
        assertEquals(a, f.sink.actions.last().from)
        assertEquals(b, f.sink.actions.last().to)
        assertFalse(f.loop.resume())
    }

    @Test fun stopMakesOutstandingCallbackObsolete() {
        val f = Fixture()
        f.loop.start(SwipePlan(a, b, screen, 300, 700))
        f.loop.stop()
        f.sink.finish()
        assertEquals(Phase.IDLE, f.latest.phase)
        assertFalse(f.loop.hasInFlight)
        assertTrue(f.clock.jobs.isEmpty())
    }

    @Test fun cannotStartAnotherModeUntilOldGestureCompletes() {
        val f = Fixture()
        f.loop.start(SwipePlan(a, b, screen, 300, 700))
        f.loop.stop()
        assertFalse(f.loop.start(TapPlan(listOf(a), screen, 500, 2)))
        f.sink.finish()
        assertTrue(f.loop.start(TapPlan(listOf(a), screen, 500, 2)))
        assertEquals(Mode.TAP, f.latest.mode)
    }

    @Test fun systemCancellationPausesInsteadOfRetryingForever() {
        val f = Fixture()
        f.loop.start(SwipePlan(a, b, screen, 300, 700))
        f.sink.finish(false)
        assertEquals(Phase.PAUSED, f.latest.phase)
        assertTrue(f.clock.jobs.isEmpty())
        assertNotNull(f.latest.problem)
    }

    @Test fun rejectedDispatchLeavesNoPendingGesture() {
        val f = Fixture()
        f.sink.accept = false
        assertFalse(f.loop.start(SwipePlan(a, b, screen, 300, 700)))
        assertEquals(Phase.PAUSED, f.latest.phase)
        assertFalse(f.loop.hasInFlight)
        assertTrue(f.clock.jobs.isEmpty())
    }

    @Test fun invalidPointsAndTimesAreRejected() {
        val invalidPlans = listOf<() -> AutomationPlan>(
            { TapPlan(listOf(ScreenPoint(Float.NaN, 1f)), screen, 500, 1) },
            { TapPlan(listOf(ScreenPoint(1080f, 1f)), screen, 500, 1) },
            { TapPlan(emptyList(), screen, 500, 1) },
            { TapPlan(listOf(a), screen, 99, 1) },
            { TapPlan(listOf(a), screen, 500, 0) },
            { SwipePlan(a, a, screen, 300, 700) },
            { SwipePlan(a, b, screen, 0, 700) },
            { SwipePlan(a, b, screen, 300, -1) }
        )
        for (createPlan in invalidPlans) {
            assertThrows(IllegalArgumentException::class.java) { createPlan() }
        }
    }
}
