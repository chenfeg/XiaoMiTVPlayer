package com.tvplayer.universal.data

/**
 * 电视上没有 logcat 通道，而 ijk 把 FFmpeg 的失败原因只写在原生日志里：
 * ff_ffplay.c:3886 `av_log_set_callback(ffp_log_callback_brief)` → 按 `IJKMEDIA` 标签打 logcat。
 * 为什么非要抓它：`what=-10000 extra=0` 这一路的真错误码在 Java 侧是**丢掉**的 ——
 * ff_ffplay.c:3506 用的是 `ffp_notify_msg1`（arg1 恒为 0），而真正的码只出现在
 * `av_read_frame error: <字符串>`（ff_ffplay.c:3551，AV_LOG_ERROR）这一行里。
 *
 * Android 4.1 起非系统应用 exec logcat 只能读到本进程的行，正好够用。
 * 只保留播放器/解码框架相关标签，并丢弃带凭据字样的行 —— 运行日志是持久文件，
 * 而 logcat 里可能出现带用户名/密码的 URL。
 */
object NativeLog {

    private val KEEP = Regex(
        "\\b(IJKMEDIA|IJKPLAYER|ijkplayer|IJKERROR|AVLog|ffplay|FFmpeg|ffmpeg|" +
            "MediaCodec|OMX|ACodec|CCodec|NuPlayer|AudioTrack|SDL)\\b"
    )
    private val DROP = Regex("password|token|passwd|smb://", RegexOption.IGNORE_CASE)

    /** 真正说明"为什么"的行。ijk 每秒还会打一批统计行，靠这个把噪声挤掉 */
    private val HOT = Regex(
        "error|invalid|fail|abort|reset|prematurely|EOF|denied|timeout|no such|" +
            "broken|closed|refused|unsupported|wait for",
        RegexOption.IGNORE_CASE
    )

    /** 崩溃缓冲里必然出现的框架标签（logcat -b crash 也会带上别人的行，靠这个筛） */
    private val CRASH_TAG = Regex(
        "FATAL|AndroidRuntime|\\bDEBUG\\b|tombstone|libc\\s|SIGSEGV|SIGABRT|" +
            "Abort message|beginning of crash",
        RegexOption.IGNORE_CASE
    )

    /** @param scan 往回读多少行 @param maxOut 最多留多少条 */
    fun capture(scan: Int = 1500, maxOut: Int = 12): List<String> = runCatching {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-v", "brief", "-t", "$scan")
        )
        val kept = mutableListOf<String>()
        val reader = proc.inputStream.bufferedReader()
        reader.use { r ->
            r.lineSequence().forEach { line ->
                if (KEEP.containsMatchIn(line) && !DROP.containsMatchIn(line)) kept += line.trim()
            }
        }
        runCatching { proc.destroy() }
        runCatching { proc.waitFor() }
        if (kept.isEmpty()) return listOf("原生日志里没有播放器相关的行（可能没权限读 logcat）")
        // 优先给"说明原因"的行；同类里取最近的 —— 出错的那条永远在最后，
        // 早先那些是这一次播放过程中打出来的统计行。
        val hot = kept.filter { HOT.containsMatchIn(it) }.distinct()
        (if (hot.isNotEmpty()) hot else kept.distinct()).takeLast(maxOut)
    }.getOrElse { listOf("抓原生日志失败 ${it.javaClass.simpleName}: ${it.message}") }

    /** 去掉 logcat 的 "E/IJKMEDIA( 1234): " 前缀，只留正文，方便显示在 OSD 上 */
    fun brief(line: String): String = line.substringAfter("): ", line)

    /**
     * 崩溃专用缓冲（`logcat -b crash`，Android 5.0 起有）：Java 崩溃和 native 崩溃的
     * 收尾行都会进这里，而普通缓冲区会被播放器每秒的统计行挤满 —— 电视上崩一次就退到
     * 桌面、什么都留不下时，这一份是唯一能拿到"崩在哪个 so 的哪一行"的地方。
     * 只在本机应用启动时抓一次（进播放页时），因为崩溃缓冲会一直保持到重启。
     */
    fun captureCrash(): List<String> = runCatching {
        val proc = Runtime.getRuntime().exec(
            arrayOf("logcat", "-d", "-b", "crash", "-v", "brief", "-t", "120")
        )
        val kept = mutableListOf<String>()
        proc.inputStream.bufferedReader().use { r ->
            r.lineSequence().forEach { line ->
                val l = line.trim()
                if (l.isEmpty()) return@forEach
                if (DROP.containsMatchIn(l)) return@forEach
                if (l.contains("com.tvplayer.universal") || CRASH_TAG.containsMatchIn(l) ||
                    KEEP.containsMatchIn(l) || HOT.containsMatchIn(l)
                ) kept += l
            }
        }
        runCatching { proc.waitFor() }
        kept.takeLast(20)
    }.getOrElse { emptyList() }

    /** 有内容才落盘；没有就什么也不写，免得日志里全是"上次没崩" */
    fun dumpCrashBuffer() {
        val lines = captureCrash()
        if (lines.isEmpty()) return
        EventLog.line("崩溃缓冲（上一次运行的残留，若与本次无关请忽略）：")
        lines.forEach { EventLog.line("  $it") }
    }

    /** 最值得给用户看的那一条：最后出现的、带原因字样的行 */
    fun headline(lines: List<String>): String? =
        lines.lastOrNull { HOT.containsMatchIn(it) }?.let { brief(it) }

    /** 抓一次、逐条落进运行日志，并把抓到的行回给调用方（界面要用它显示原因） */
    fun dumpToEventLog(reason: String): List<String> {
        val lines = capture()
        EventLog.line("原生日志（$reason）：")
        lines.forEach { EventLog.line("  $it") }
        return lines
    }
}
