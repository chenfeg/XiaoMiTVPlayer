package com.tvplayer.universal.subtitle.net

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.subtitle.ReleaseInfo
import com.tvplayer.universal.subtitle.SubCandidate
import com.tvplayer.universal.subtitle.SubtitleSource
import java.nio.charset.StandardCharsets

/**
 * FakeSub（开源的 OpenSubtitles 指纹匹配服务）。
 * 按文件指纹精确匹配，改名/无干净片名时唯一可靠。
 * 公共实例已确认不存在（NXDOMAIN），所以默认关闭：只有设置页填了自建地址才发请求。
 */
class FakeSubSource(private val prefs: Prefs) : SubtitleSource {
    override val id = "fakesub"

    override suspend fun search(info: ReleaseInfo, hash: String?, size: Long): List<SubCandidate> {
        // 公共实例域名已不存在（见 Prefs.fakeSubBaseUrl），留空就是没自建实例，别白跑一趟
        if (prefs.fakeSubBaseUrl.isBlank() || hash == null || size <= 0) return emptyList()
        return runCatching {
            val url = prefs.fakeSubBaseUrl.trimEnd('/') +
                "/subtitles?filelength=$size&hash=$hash"
            val root = JsonParser.parseString(
                String(Net.get(url), StandardCharsets.UTF_8)
            ).asJsonObject
            val data = root.getAsJsonArray("data") ?: return@runCatching emptyList()
            data.mapNotNull { el ->
                val attr = el.asJsonObject.getAsJsonObject("attributes") ?: return@mapNotNull null
                val lang = attr.str("lang").lowercase()
                val download = attr.str("download").ifBlank { attr.str("url") }
                if (download.isBlank()) return@mapNotNull null
                val fmt = attr.str("format")
                val name = buildString {
                    append(if (lang.isBlank()) "字幕" else lang.uppercase())
                    append('-').append(size / 1048576).append("MB片源")
                    if (fmt.isNotBlank()) append('.').append(fmt)
                }
                SubCandidate(
                    sourceId = id,
                    externalId = el.asJsonObject.str("id"),
                    title = name,
                    // 下载直链已是明文 srt/ass，不解 zip（format=zip 时除外）
                    mayBeZip = fmt == "zip",
                    formatHint = fmt.ifBlank { null },
                    editor = "FakeSub",
                    downloadUrl = download,
                    score = 55 +
                        (if (lang.startsWith("zh") || lang.startsWith("chi") || lang.startsWith("zho")) 25 else -10) +
                        (if (fmt in setOf("srt", "ass", "ssa")) 5 else 0)
                )
            }
        }.onFailure { Log.w(TAG, "fakesub search failed", it) }.getOrDefault(emptyList())
    }

    override suspend fun fetch(candidate: SubCandidate, info: ReleaseInfo): ByteArray {
        val url = candidate.downloadUrl ?: throw IllegalStateException("no download url")
        return Net.get(url)
    }

    private fun JsonObject.str(key: String) =
        if (has(key) && !get(key).isJsonNull) get(key).asString else ""

    companion object {
        private const val TAG = "FakeSubSource"
    }
}
