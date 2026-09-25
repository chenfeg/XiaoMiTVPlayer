package com.tvplayer.universal.player

import android.os.Handler
import java.util.concurrent.TimeUnit

/** 可替换的时钟：生产用 SystemClock.uptimeMillis()，单测用可控的假时钟 */
fun interface Clock {
    fun now(): Long
}

/**
 * 可替换的延时调度：生产用 Handler，单测用手动触发的假调度器。
 * 用 java.lang.Runnable 而不是 Kotlin 函数类型：Handler 可直接收发，
 * JVM 单测也完全不依赖 Android。
 */
interface Scheduler {
    fun postDelayed(action: Runnable, delayMs: Long)
    fun remove(action: Runnable)
}

class HandlerScheduler(private val handler: Handler) : Scheduler {
    override fun postDelayed(action: Runnable, delayMs: Long) {
        handler.postDelayed(action, delayMs)
    }

    override fun remove(action: Runnable) {
        handler.removeCallbacks(action)
    }
}

/**
 * seek 防抖 / 合并提交状态机（原 PlayerActivity 内的 skipBy/commitSeek/settleSeek）。
 *
 * 连按快进**不能每次都真的去 seek**。真机证据（2026-09-24 11:27 那一串）：目标从
 * 00:00:46 一路叠到 00:22:46，每一步都报"落定、偏移 0s、耗时 1~200ms"，可下一次按键
 * 取的基准却是 00:17:48 —— 一串重叠的 seek 里只有头一两个真落地，后面的被内核丢掉了，
 * 松开后报回真实位置，屏上时间就掉回去几分钟。
 *
 * 所以按键期间只累加目标、不动内核，静默 [DEBOUNCE_MS] 之后**只提交一次** seek。
 * 复验（11:39 那一串）：19 次连按合成一次 13 分钟跳转，提交后 183ms 落定，
 * 退出时真实位置 816s 与目标 808s 只差播放量，用户确认不再回退。
 *
 * 落定判据是"播放器报回来的位置追上目标"，但它在 seek 期间偏松：ijk 会把**请求的目标**
 * 原样当当前位置报回来（onSeekComplete 更早之前就废了：24ms 就到、位置还停在跳转前）。
 * 真正兜住回退的是上面那次合并提交 —— 内核拿到的就是最终目标，回声与真位置自然重合。
 * [SETTLE_MAX_MS] 只是位置永远追不上时不让时钟冻死的上限。
 */
class SeekController(
    private val scheduler: Scheduler,
    private val clock: Clock,
    private val currentPosition: () -> Long,
    private val duration: () -> Long,
    private val seekTo: (Long) -> Unit,
    private val sourceTag: () -> String = { "" },
    private val log: (String) -> Unit = {}
) {
    private var targetMs = -1L
    /** 目标是否已经交给内核（false = 还在连按累加中） */
    private var submitted = false
    private var settleAt = 0L
    private var startedAt = 0L
    /** 一串连按里的第几次（连按时只有最后一个目标会被追上，不分开记就看不出目标跑过多远） */
    private var burstCount = 0
    private var burstStartedAt = 0L

    private val commitAction = Runnable { commit() }

    /** 有一次方向键快退/快进：累加目标并续期提交，不立刻动内核 */
    fun skip(deltaMs: Long) {
        val now = clock.now()
        if (targetMs < 0) {
            targetMs = currentPosition()
            burstCount = 1
            burstStartedAt = now
        } else {
            burstCount++
        }
        targetMs = (targetMs + deltaMs).coerceIn(0L, maxOf(0L, duration()))
        // 目标一改就要重新提交；按住不放期间不断续期，免得半路上把显示权交还出去
        submitted = false
        settleAt = now + SETTLE_MAX_MS
        scheduler.remove(commitAction)
        scheduler.postDelayed(commitAction, DEBOUNCE_MS)
    }

    /** 按键静默之后才真的跳：一串连按对内核只发一次 seek */
    private fun commit() {
        val target = targetMs
        if (target < 0 || submitted) return
        val now = clock.now()
        submitted = true
        startedAt = now
        settleAt = now + SETTLE_MAX_MS
        log(
            "seek 提交 $burstCount 次/串长 ${(now - burstStartedAt) / 1000}s" +
                "，目标=${fmt(target)} 提交时位置=${fmt(currentPosition())}"
        )
        seekTo(target)
    }

    /**
     * 每个 tick 调一次：真实位置追上目标（或等满上限）就交还显示权，返回这一拍该显示的时间。
     * 注意字幕永远跟真实播放走，不经过这里。
     */
    fun onTick(actualPositionMs: Long): Long {
        val target = targetMs
        if (target < 0) return actualPositionMs
        val now = clock.now()
        val expired = now >= settleAt
        if (!expired && (!submitted || Math.abs(actualPositionMs - target) > LAND_MS)) return target
        val cost = now - startedAt
        val drift = actualPositionMs - target
        targetMs = -1L
        submitted = false
        startedAt = 0L
        settleAt = 0L
        // 每次落定都记：上一轮把条件设成"慢或有偏移才记"，结果一整串连按快进只字未留，
        // 分不清"目标一路领先、交还显示权时掉一大截"和"根本没跑远"。
        log(
            (if (expired) "seek 未落定(${SETTLE_MAX_MS / 1000}s 上限)" else "seek 落定(${cost}ms)") +
                "，$burstCount 次快进/串长 ${(now - burstStartedAt) / 1000}s" +
                "，目标=${fmt(target)} 实际=${fmt(actualPositionMs)} 偏移=${drift / 1000}s 源=${sourceTag()}"
        )
        return actualPositionMs
    }

    companion object {
        /**
         * seek 之后最多让屏上时间停在目标位置多久。真机上按住右键连按（每次 60s）之后，
         * 5 秒时位置还差着几分钟、但仍在往前赶（10:50:21 实际=00:08:14 → 10:50:31 实际=00:23:30），
         * 所以上限要比那种固定窗口宽；OSD 6 秒自己收起，就算还停在目标上也不会一直挡着画面。
         */
        const val SETTLE_MAX_MS = 15_000L

        /** 真实位置与目标差进入这个范围就算落定 */
        const val LAND_MS = 2_000L

        /** 连按静默这么久才提交一次 seek：重叠的 seek 在真机上会丢 */
        const val DEBOUNCE_MS = 400L

        fun fmt(ms: Long): String {
            if (ms <= 0) return "00:00:00"
            val h = TimeUnit.MILLISECONDS.toHours(ms)
            val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
            val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
            return "%02d:%02d:%02d".format(h, m, s)
        }
    }
}
