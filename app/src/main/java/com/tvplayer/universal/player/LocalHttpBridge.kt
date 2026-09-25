package com.tvplayer.universal.player

import android.util.Log
import com.tvplayer.universal.browse.ContentSource
import com.tvplayer.universal.data.EventLog
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 127.0.0.1 上的最小 Range HTTP 服务：把任意 ContentSource（SMB 等）
 * 伪装成 http URL 交给 ijkplayer，避免为播放而整片下载占用 eMMC。
 * 只支持 GET + 单区间 Range（`bytes=START-END`，不含 `bytes=-N` 后缀区间），
 * 正好覆盖 ffmpeg http 协议的全部需要。
 */
class LocalHttpBridge(
    private val contentType: String,
    private val openSource: () -> ContentSource
) : AutoCloseable {

    private val server = ServerSocket(0, 4)
    private val connections = java.util.Collections.synchronizedList(mutableListOf<Socket>())

    /**
     * 连接处理走固定 4 线程池：ffmpeg 同时只开 1~2 条连接，4 个足够；
     * 不再为每条连接新建线程，长时间播放频繁 seek 时线程数有界。
     */
    private val pool = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "http-bridge-conn").apply { isDaemon = true }
    }
    @Volatile
    private var running = true

    val url: String get() = "http://127.0.0.1:${server.localPort}/stream"

    fun start() {
        thread(name = "http-bridge-accept", isDaemon = true) {
            while (running) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    break
                }
                // 客户端建连后不发完整请求、或暂停太久不读数据时，
                // 读写不能永久阻塞：超时后由 handle 的异常路径收尾，连接和线程都能释放。
                socket.soTimeout = SOCKET_TIMEOUT_MS
                connections += socket
                pool.execute {
                    runCatching { handle(socket) }
                        .onFailure {
                            // 打不开源（SMB 会话掉线等）会从这里出去，原来只写 Log.d，
                            // 电视上没有 logcat 通道就等于没发生
                            if (it is java.net.SocketTimeoutException) {
                                EventLog.line("HTTP桥连接超时（$SOCKET_TIMEOUT_MS 无数据往来）")
                            } else {
                                Log.d(TAG, "conn closed", it)
                                EventLog.line("HTTP桥异常 ${it.javaClass.simpleName}: ${it.message}")
                            }
                        }
                    connections -= socket
                    runCatching { socket.close() }
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedReader(InputStreamReader(socket.getInputStream()))
        val requestLine = input.readLine() ?: return
        var rangeStart = 0L
        var rangeEnd = -1L
        while (true) {
            val header = input.readLine() ?: break
            if (header.isEmpty()) break
            if (header.startsWith("Range:", true)) {
                val spec = header.substringAfter(':').trim()
                    .removePrefix("bytes=").trim()
                val dash = spec.indexOf('-')
                rangeStart = spec.substring(0, dash).toLongOrNull() ?: 0L
                rangeEnd = spec.substring(dash + 1).toLongOrNull() ?: -1L
            }
        }
        if (!requestLine.startsWith("GET")) return

        openSource().use { source ->
            val total = source.length
            val end = if (rangeEnd < 0 || rangeEnd >= total) total - 1 else rangeEnd
            val out = socket.getOutputStream()
            if (rangeStart > 0 || end < total - 1) {
                sendHead(
                    out, "206 Partial Content",
                    listOf(
                        "Content-Range: bytes $rangeStart-$end/$total",
                        "Content-Length: ${end - rangeStart + 1}"
                    )
                )
            } else {
                sendHead(out, "200 OK", listOf("Content-Length: $total"))
            }
            val buffer = ByteArray(64 * 1024)
            val expected = end - rangeStart + 1
            var pos = rangeStart
            var fail: String? = null
            try {
                while (pos <= end && running) {
                    val want = minOf(buffer.size.toLong(), end - pos + 1).toInt()
                    // ContentSource 把"读不动"也报成 -1：这一路以前会静默结束，
                    // 于是 ffmpeg 收到一个比 Content-Length 短的响应，只会向上面
                    // 报"读取中断"（what=-10000 extra=0），根因在这里看不到。
                    val n = source.readAt(pos, buffer, want)
                    if (n <= 0) {
                        fail = "源在 $pos 处返回 $n（总长 $total）"
                        break
                    }
                    out.write(buffer, 0, n)
                    pos += n
                }
                out.flush()
            } catch (e: Exception) {
                fail = "${e.javaClass.simpleName}: ${e.message}"
            }
            // 客户端（ijk 要 seek 时）自己掐断连接是常态，只有我们这侧读不动才算异常；
            // 写异常与读异常都留一行，看已发/应发的比例就能分清是谁先走的。
            if (running && (fail != null || pos <= end)) {
                EventLog.line(
                    "HTTP桥 ${fail ?: "提前结束"} 已发 ${pos - rangeStart}/$expected 字节"
                )
            }
        }
    }

    private fun sendHead(out: OutputStream, status: String, extra: List<String>) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status\r\n")
        sb.append("Content-Type: $contentType\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        // 中断排队和正在处理的连接，配合下面强制关闭 socket，线程池立刻回收
        pool.shutdownNow()
        synchronized(connections) { connections.forEach { runCatching { it.close() } } }
    }

    companion object {
        private const val TAG = "LocalHttpBridge"

        /**
         * Socket 读写超时。30s 无数据往来即断开：
         * 覆盖"建连不发请求"和"暂停后 ffmpeg 长时间不消费"两种卡死，
         * 恢复播放时 ffmpeg 会按 Range 从断点重连。
         */
        private const val SOCKET_TIMEOUT_MS = 30_000
    }
}
