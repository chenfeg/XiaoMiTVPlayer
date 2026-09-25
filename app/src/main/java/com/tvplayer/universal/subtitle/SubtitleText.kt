package com.tvplayer.universal.subtitle

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

/**
 * 字幕字节的"变成可读文本"这一步：拆包 + 编码嗅探。
 *
 * 原来这套逻辑只在在线字幕落盘时走（SubtitleCache.put），外挂字幕走的是另一条路：
 * SMB 上的字幕直接 `String(bytes, UTF_8)`，而中文外挂里 GBK 与 UTF-16 极常见 ——
 * 解出来全是乱码，语言判定因此说"不是中文"，于是本地字幕被自己丢掉，
 * 屏幕上一句"未找到匹配的中文字幕"。所以两条路必须共用同一个解码器。
 */
object SubtitleText {

    const val MAX_BYTES = 16L * 1024 * 1024

    /** 先拆 zip/gz（在线字幕包常见），取最长的一条文本 */
    fun decode(raw: ByteArray): String {
        val parts = unzipLike(raw)
        val best = parts.maxByOrNull { it.size } ?: return ""
        return decodeOne(best)
    }

    /** BOM 优先；UTF-8 严格解码失败按 GBK（射手时代字幕大量 GBK） */
    fun decodeOne(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) return String(bytes, 3, bytes.size - 3, StandardCharsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
        }
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, charset("GBK")) }
    }

    private fun unzipLike(raw: ByteArray): List<ByteArray> {
        if (raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
            return listOf(GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() })
        }
        if (raw.size >= 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte()) {
            val out = mutableListOf<ByteArray>()
            ZipInputStream(ByteArrayInputStream(raw)).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    if (!entry.isDirectory &&
                        entry.name.substringAfterLast('.', "").lowercase() in
                        setOf("srt", "ass", "ssa", "sub", "vtt")
                    ) {
                        out += zin.readBytes()
                    }
                }
            }
            return out
        }
        return listOf(raw)
    }
}
