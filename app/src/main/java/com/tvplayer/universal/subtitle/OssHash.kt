package com.tvplayer.universal.subtitle

import com.tvplayer.universal.browse.ContentSource
import java.io.RandomAccessFile

/**
 * OpenSubtitles 64 位文件指纹（首尾各 64KB 求和）。
 * 对 mkv/mp4 等按字节流发布、文件名被改得面目全非的片源，指纹匹配远胜文本搜索。
 */
object OssHash {

    suspend fun of(source: ContentSource): String? {
        if (source.length < 65536) return null
        val head = readAt(source, 0, 65536)
        val tailPos = maxOf(0L, source.length - 65536)
        val tail = readAt(source, tailPos, minOf(65536L, source.length - tailPos).toInt())
        return checksum(source.length, head, tail)
    }

    fun ofFile(file: java.io.File): String? {
        if (!file.isFile || file.length() < 65536) return null
        return RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(65536)
            raf.readFully(head)
            val tailPos = maxOf(0L, raf.length() - 65536)
            raf.seek(tailPos)
            val tail = ByteArray(minOf(65536L, raf.length() - tailPos).toInt())
            raf.readFully(tail)
            checksum(raf.length(), head, tail)
        }
    }

    private fun readAt(src: ContentSource, pos: Long, len: Int): ByteArray {
        val buf = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = src.readAt(pos + off, buf, len - off)
            if (n <= 0) break
            off += n
        }
        return if (off == len) buf else buf.copyOf(off)
    }

    /** 参考实现语义：64KB 分块、逐 8 字节小端有符号整数累加，溢出自然回绕 */
    private fun checksum(size: Long, head: ByteArray, tail: ByteArray): String {
        var h = size
        for (chunk in arrayOf(head, tail)) {
            var i = 0
            while (i + 8 <= chunk.size) {
                var w = 0L
                for (j in 0 until 8) {
                    w = w or ((chunk[i + j].toLong() and 0xFF) shl (8 * j))
                }
                h += w
                i += 8
            }
        }
        // 必须是 16 位十六进制：指纹查询按定长比对，高位是 0 时不能省掉那几位，
        // 否则算出来的指纹永远查不中。
        return h.toULong().toString(16).padStart(16, '0')
    }
}
