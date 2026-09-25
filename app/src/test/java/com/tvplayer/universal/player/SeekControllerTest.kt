package com.tvplayer.universal.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekControllerTest {

    private class FakeClock(var t: Long = 0L) : Clock {
        override fun now() = t
        fun advance(ms: Long) {
            t += ms
        }
    }

    private class FakeScheduler(private val clock: FakeClock) : Scheduler {
        /** action -> 到期时刻；同一个 commitAction 只可能有一条待办 */
        private val pending = LinkedHashMap<Runnable, Long>()

        val pendingCount: Int get() = pending.size

        override fun postDelayed(action: Runnable, delayMs: Long) {
            pending[action] = clock.now() + delayMs
        }

        override fun remove(action: Runnable) {
            pending.remove(action)
        }

        /** 运行所有已到期动作，返回触发条数 */
        fun runDue(): Int {
            val due = pending.entries.filter { it.value <= clock.now() }.map { it.key }
            var count = 0
            for (action in due) {
                if (pending.remove(action) != null) {
                    action.run()
                    count++
                }
            }
            return count
        }
    }

    private class FakePlayer {
        var pos: Long = 0L
        var duration: Long = 600_000L
        val seeks = mutableListOf<Long>()
    }

    private class Harness {
        val clock = FakeClock()
        val scheduler = FakeScheduler(clock)
        val player = FakePlayer()
        val logs = mutableListOf<String>()
        val controller = SeekController(
            scheduler = scheduler,
            clock = clock,
            currentPosition = { player.pos },
            duration = { player.duration },
            seekTo = { player.seeks += it },
            sourceTag = { "测试源" },
            log = { logs += it }
        )
    }

    @Test
    fun `空闲 tick 原样返回真实位置且不调度`() {
        val h = Harness()
        assertEquals(12_345L, h.controller.onTick(12_345L))
        assertEquals(0, h.scheduler.pendingCount)
        assertTrue(h.logs.isEmpty())
    }

    @Test
    fun `单次 skip 防抖期内不提交、tick 显示目标，到点只提交一次`() {
        val h = Harness()
        h.player.pos = 30_000L
        h.controller.skip(10_000L)

        assertEquals(1, h.scheduler.pendingCount)
        assertTrue(h.player.seeks.isEmpty())
        // 落定前显示目标，而不是真实位置（否则连按时数字回跳）
        assertEquals(40_000L, h.controller.onTick(h.player.pos))

        h.clock.advance(399)
        assertEquals(0, h.scheduler.runDue())
        h.clock.advance(1)
        assertEquals(1, h.scheduler.runDue())
        assertEquals(listOf(40_000L), h.player.seeks)
        assertTrue(h.logs.single().startsWith("seek 提交 1 次"))
    }

    @Test
    fun `连按合并为一次 seek，提交最终目标`() {
        val h = Harness()
        h.player.pos = 30_000L
        h.controller.skip(10_000L) // t=0，计划 t=400 提交

        h.clock.advance(300)
        h.controller.skip(10_000L) // 撤掉旧待办，计划 t=700 提交
        assertEquals(1, h.scheduler.pendingCount)
        assertTrue(h.player.seeks.isEmpty())

        h.clock.advance(399) // t=699
        assertEquals(0, h.scheduler.runDue())
        h.clock.advance(1) // t=700
        assertEquals(1, h.scheduler.runDue())
        assertEquals(listOf(50_000L), h.player.seeks)
        assertTrue(h.logs.single().startsWith("seek 提交 2 次"))
    }

    @Test
    fun `报回位置进入落定窗口才交还显示权`() {
        val h = Harness()
        h.player.pos = 30_000L
        h.controller.skip(10_000L)
        h.clock.advance(SeekController.DEBOUNCE_MS)
        h.scheduler.runDue()
        assertEquals(listOf(40_000L), h.player.seeks)

        // 还差 3s（超出 ±2s 窗口）：继续显示目标
        h.player.pos = 37_000L
        assertEquals(40_000L, h.controller.onTick(h.player.pos))
        assertEquals(1, h.logs.size)

        // 进入 ±2s：交还真实位置，写落定日志
        h.player.pos = 38_500L
        assertEquals(38_500L, h.controller.onTick(h.player.pos))
        assertTrue(h.logs.last().startsWith("seek 落定"))

        // 状态已复位：后续 tick 直接返回真实位置，不再写日志
        h.player.pos = 38_600L
        assertEquals(38_600L, h.controller.onTick(h.player.pos))
        assertEquals(2, h.logs.size)
    }

    @Test
    fun `等满落定上限即使位置很远也强制交还真实位置`() {
        val h = Harness()
        h.player.pos = 30_000L
        h.controller.skip(60_000L) // 目标 90_000
        h.clock.advance(SeekController.DEBOUNCE_MS)
        h.scheduler.runDue() // t=400 提交，settleAt=15_400

        // 报回位置停在 35s：t=15_399 仍显示目标
        h.player.pos = 35_000L
        h.clock.advance(14_999) // t=15_399
        assertEquals(90_000L, h.controller.onTick(h.player.pos))

        h.clock.advance(1) // t=15_400，到上限
        assertEquals(35_000L, h.controller.onTick(h.player.pos))
        assertTrue(h.logs.last().startsWith("seek 未落定(15s 上限)"))
    }

    @Test
    fun `目标 clamp 到时长上限和 0`() {
        val h = Harness()
        h.player.duration = 60_000L
        h.player.pos = 50_000L
        h.controller.skip(20_000L)
        h.clock.advance(SeekController.DEBOUNCE_MS)
        h.scheduler.runDue()
        assertEquals(listOf(60_000L), h.player.seeks)

        val h2 = Harness()
        h2.player.pos = 10_000L
        h2.controller.skip(-30_000L)
        h2.clock.advance(SeekController.DEBOUNCE_MS)
        h2.scheduler.runDue()
        assertEquals(listOf(0L), h2.player.seeks)
    }

    @Test
    fun `落定后再次 skip 开启新的一串`() {
        val h = Harness()
        h.player.pos = 30_000L
        h.controller.skip(10_000L)
        h.clock.advance(SeekController.DEBOUNCE_MS)
        h.scheduler.runDue()
        h.player.pos = 40_000L
        assertEquals(40_000L, h.controller.onTick(40_000L)) // 落定复位

        h.controller.skip(10_000L)
        assertEquals(50_000L, h.controller.onTick(40_000L)) // 新目标生效
    }
}
