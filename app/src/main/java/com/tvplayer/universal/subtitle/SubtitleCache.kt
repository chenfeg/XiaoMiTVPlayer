package com.tvplayer.universal.subtitle

import android.content.Context
import android.util.Log
import com.tvplayer.universal.data.Prefs
import java.io.File
import java.security.MessageDigest

/**
 * 字幕文本落盘缓存（cacheDir/subs）。
 * 统一转码为 UTF-8 明文再存，播放侧零解码成本；LRU 上限保护 8GB eMMC。
 */
object SubtitleCache {
    private const val TAG = "SubtitleCache"

    private fun dir(context: Context): File =
        File(context.cacheDir, "subs").apply { mkdirs() }

    /**
     * tag 区分同一字幕包里的不同集：assrt 一条记录常含整季文件，
     * 只按 id 缓存会让 S01E02 命中 S01E01 的字幕。
     * KEY_SALT 是"挑文件/解包规则变了"的开关：改了 pickFile 就加一段，
     * 否则旧缓存里那条不合适应被换掉的文件会一直命中、改动看不出效果。
     */
    private const val KEY_SALT = "v2"

    fun keyOf(candidate: SubCandidate, tag: String): String =
        sha1("$KEY_SALT:${candidate.sourceId}:${candidate.externalId}:$tag")

    fun get(context: Context, candidate: SubCandidate, tag: String): File? {
        val hit = dir(context).listFiles { f ->
            f.isFile && f.name.startsWith(keyOf(candidate, tag))
        }?.firstOrNull()
        if (hit != null) hit.setLastModified(System.currentTimeMillis())
        return hit
    }

    /** 解包（zip/gz）+ 编码嗅探 + 归一 UTF-8 落盘 */
    fun put(context: Context, candidate: SubCandidate, raw: ByteArray, tag: String): File {
        val text = SubtitleText.decode(raw)
        if (text.isBlank()) throw IllegalStateException("字幕包内未找到可用文本")
        val file = File(dir(context), keyOf(candidate, tag) + ".utf8")
        file.writeText(text)
        file.setLastModified(System.currentTimeMillis())
        purgeToFit(context, Prefs(context).subtitleCacheLimitMb)
        return file
    }

    fun purgeToFit(context: Context, limitMb: Long) {
        runCatching {
            val files = dir(context).listFiles()?.toList() ?: return
            var total = files.sumOf { it.length() }
            val budget = limitMb * 1024 * 1024
            if (total <= budget) return
            files.sortedBy { it.lastModified() }.forEach {
                if (total <= budget) return@forEach
                total -= it.length()
                it.delete()
            }
        }.onFailure { Log.w(TAG, "purge failed", it) }
    }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") {
            "%02x".format(it)
        }
}
