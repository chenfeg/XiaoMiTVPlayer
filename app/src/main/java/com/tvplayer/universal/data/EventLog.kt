package com.tvplayer.universal.data

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 电视上拿不到 logcat，所以把播放流程逐条写进文件，设置页「运行日志」倒序显示。
 * 最后一条记录尤其重要：ijk 的 native 崩溃 Java 侧抓不到，只能靠"崩在上一步哪件事"定位。
 * 只写诊断必需的内容，不写 SMB 账号密码、字幕站 token、带文件名的完整 URL。
 */
object EventLog {

    private const val TAG = "EventLog"
    private const val MAX_BYTES = 64L * 1024
    // 带日期：64KB 环形缓冲跨天复用后，单凭时分秒分不清记录是哪一天的（2026-09-26 黑屏事故日志因此无法辨认）
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    private var file: File? = null
    private var handlerInstalled = false

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "event-log.txt")
        line("启动 ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE} API${Build.VERSION.SDK_INT}")
        if (handlerInstalled) return
        handlerInstalled = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            line("Java 未捕获异常 ${thread.name}: ${Log.getStackTraceString(e)}")
            previous?.uncaughtException(thread, e)
        }
    }

    /**
     * 被主线程 / IO / HTTP桥 / SMB 等多个线程并发调用：
     * SimpleDateFormat 非线程安全，writeText 截断与 appendText 也必须互斥，
     * 否则会出现行交错、时间戳错乱、截断丢行。所有文件读写统一走同一把锁。
     */
    @Synchronized
    fun line(text: String) {
        Log.i(TAG, text)
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText("")
            f.appendText("${stamp.format(Date())} $text\n")
        }
    }

    /** 最新的排最前：遥控器翻页比手指累 */
    @Synchronized
    fun newestFirst(lines: Int): String {
        val f = file ?: return "日志未初始化"
        if (!f.exists()) return "（暂无记录）"
        val body = runCatching { f.readText() }.getOrDefault("")
        return body.trimEnd().lines().asReversed().take(lines).joinToString("\n")
    }

    @Synchronized
    fun clear() {
        runCatching { file?.writeText("") }
    }
}
