package com.tvplayer.universal.subtitle.net

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tvplayer.universal.browse.SidecarMatch
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.subtitle.ReleaseInfo
import com.tvplayer.universal.subtitle.SubCandidate
import com.tvplayer.universal.subtitle.SubtitleSource
import java.nio.charset.StandardCharsets

/**
 * 射手网 AssSub 开放接口（api.assrt.net/v1），单 token 鉴权。
 * token 在 https://secure.assrt.net 控制台获取，编译时从 local.properties 注入 BuildConfig.ASSRT_TOKEN。
 *
 * 实测响应（2026-09 核对）：
 *   search → {status:0, sub:{result:"succeed", subs:[{id, videoname, native_name,
 *              subtype, vote_score, lang:{desc, langlist:{langchs,langcht,langdou,langeng}},
 *              release_site, upload_time}]}}
 *   detail → {status:0, sub:{subs:[{filename, url, filelist:[{f, s, url}]}]}}
 * filelist 是接口替我们解包后逐条给出的直链（含 http://，故需要 network_security_config 放行），
 * 优先用它——整包 url 往往是 .rar，客户端解不了。
 */
class AssrtSource(private val prefs: Prefs) : SubtitleSource {
    override val id = "assrt"

    private val base get() = prefs.assrtBaseUrl.trimEnd('/')

    private fun configured() = prefs.assrtToken.isNotBlank()

    override suspend fun search(info: ReleaseInfo, hash: String?, size: Long): List<SubCandidate> {
        if (!configured()) return emptyList()
        val merged = LinkedHashMap<String, SubCandidate>()
        for (query in info.queries()) {
            val found = runCatching { searchOnce(query, info) }
                .onFailure { Log.w(TAG, "assrt search '$query' failed", it) }
                .getOrDefault(emptyList())
            for (c in found) {
                val old = merged[c.externalId]
                if (old == null || c.score > old.score) merged[c.externalId] = c
            }
            // 已经有片名+集数都对得上的中文条目就不必再问下一轮
            if (found.any { it.score >= STRONG_HIT }) break
        }
        return merged.values.sortedByDescending { it.score }
    }

    private fun searchOnce(query: String, info: ReleaseInfo): List<SubCandidate> {
        val root = getJson("$base/sub/search", searchParams(query))
        if (!isSucceed(root)) return emptyList()
        return subsArray(root).mapNotNull { el ->
            if (el !is JsonObject) return@mapNotNull null
            val videoName = el.firstString("videoname")
            val nativeName = el.firstString("native_name")
            val title = nativeName.ifBlank { videoName }
            if (title.isBlank()) return@mapNotNull null
            val archiveName = el.firstString("filename", "videoname")
            val names = "$nativeName $videoName"
            val chinese = isChinese(el)
            var score = 40 + el.firstInt("vote_score") / 10
            if (chinese) score += 15 else score -= 25
            if (matchesEpisode(names, info)) score += 20
            if (matchesTitle(names, info)) score += 15
            SubCandidate(
                sourceId = id,
                externalId = el.firstString("id"),
                title = title,
                mayBeZip = isArchive(archiveName),
                formatHint = subtypeOf(el.firstString("subtype")),
                editor = listOf(el.firstString("release_site"), el.firstString("upload_time"))
                    .filter { it.isNotBlank() }.joinToString(" "),
                score = score
            )
        }
    }

    private fun searchParams(query: String) = mapOf(
        "token" to prefs.assrtToken,
        "q" to query,
        "cnt" to "20"
    )

    override suspend fun fetch(candidate: SubCandidate, info: ReleaseInfo): ByteArray {
        val root = getJson(
            "$base/sub/detail",
            mapOf("token" to prefs.assrtToken, "id" to candidate.externalId)
        )
        if (!isSucceed(root)) throw IllegalStateException("assrt detail rejected")
        val detail = subsArray(root).firstOrNull() as? JsonObject
            ?: throw IllegalStateException("assrt detail empty")
        val direct = pickFile(detail.get("filelist"), info, candidate.formatHint)
        val url = direct ?: detail.firstString("url").takeIf { !isArchive(it) }
            ?: throw IllegalStateException("assrt has no unpacked file, only archive")
        return Net.get(url)
    }

    /** filelist 里挑最贴合这一集的那条，返回直链 */
    private fun pickFile(
        element: JsonElement?,
        info: ReleaseInfo,
        formatHint: String?
    ): String? {
        val files = element as? JsonArray ?: return null
        data class Entry(val name: String, val url: String)
        val entries = files.mapNotNull {
            if (it !is JsonObject) return@mapNotNull null
            Entry(it.firstString("f"), it.firstString("url"))
        }.filter { it.url.isNotBlank() }
        if (entries.isEmpty()) return null
        // 挑了哪条要能事后对账：屏上只显示字幕标题，显示不出包里的文件名
        fun choose(entry: Entry): String {
            Log.i(TAG, "assrt 包内 ${entries.size} 个文件：${entries.joinToString(" | ") { it.name }} → 选 ${entry.name}")
            return entry.url
        }
        if (entries.size == 1) return choose(entries[0])

        val wanted = episodeTokens(info)
        for (token in wanted) {
            entries.firstOrNull { Regex(token).containsMatchIn(it.name) }?.let { return choose(it) }
        }
        // 集数对不上（电影本来就没集数）时按语言标记挑：一条记录里常同时摆着
        // chs/cht/eng 三个文件，按列表顺序取第一条会取到英文那个
        entries.firstOrNull { SidecarMatch.taggedChinese(it.name) }?.let { return choose(it) }
        val fmt = formatHint?.lowercase()
        return choose(entries.firstOrNull { fmt != null && it.name.lowercase().endsWith(".$fmt") }
            ?: entries.first())
    }

    /** 从强到弱的集数匹配式；数字都要求前后不是数字，避免 1080p 里的 "01" 误命中 */
    private fun episodeTokens(info: ReleaseInfo): List<String> {
        val s = info.season
        val e = info.episode ?: return emptyList()
        val pad = "%02d".format(e)
        val bare = e.toString()
        return buildList {
            if (s != null) {
                add("(?i)(^|[^a-z0-9])s%02d[. _-]?e?%s".format(s, pad))
                add("(?i)(^|[^0-9])%dx%s".format(s, pad))
            }
            add("(?i)(^|[^a-z0-9])e?$pad([^0-9]|$)")
            if (bare != pad) add("(?i)(^|[^0-9])$bare([^0-9]|$)")
        }
    }

    private fun matchesEpisode(names: String, info: ReleaseInfo): Boolean {
        val cleaned = names.replace('/', '.')
        return episodeTokens(info).any { Regex(it).containsMatchIn(cleaned) }
    }

    /** 片名比对：assrt 条目里常是 "Breaking Bad/绝命毒师" 这种多语言并列，命中任一实义词即可 */
    private fun matchesTitle(names: String, info: ReleaseInfo): Boolean =
        info.titleMatches(names)

    private fun getJson(url: String, params: Map<String, String>): JsonObject {
        val body = String(Net.getForm(url, params), StandardCharsets.UTF_8)
        return JsonParser.parseString(body).asJsonObject
    }

    private fun isSucceed(root: JsonObject): Boolean {
        if ((root.get("status")?.asInt ?: -1) != 0) {
            Log.w(TAG, "assrt status != 0: ${root.firstString("errmsg", "msg", "error")}")
            return false
        }
        val result = (root.get("sub") as? JsonObject)?.firstString("result") ?: ""
        if (result.isNotBlank() && result != "succeed") {
            Log.w(TAG, "assrt result=$result")
            return false
        }
        return true
    }

    private fun subsArray(root: JsonObject): JsonArray =
        (root.get("sub") as? JsonObject)?.get("subs") as? JsonArray ?: JsonArray()

    /** lang.langlist 里出现任一中文标记即算中文字幕（langdou=双语同样含中文） */
    private fun isChinese(entry: JsonObject): Boolean {
        val langlist = (entry.get("lang") as? JsonObject)?.get("langlist") as? JsonObject
        if (langlist != null) {
            for (key in listOf("langchs", "langcht", "langdou", "langzho", "langchi")) {
                if (langlist.get(key)?.asBoolean == true) return true
            }
        }
        val desc = (entry.get("lang") as? JsonObject)?.firstString("desc").orEmpty()
        return desc.any { it.code in 0x4E00..0x9FFF }
    }

    private fun subtypeOf(subtype: String): String? = when {
        subtype.contains("srt", true) || subtype.contains("subrip", true) -> "srt"
        subtype.contains("ass", true) -> "ass"
        subtype.contains("ssa", true) -> "ssa"
        subtype.contains("vtt", true) -> "vtt"
        else -> null
    }

    private fun isArchive(name: String): Boolean {
        val lower = name.lowercase().substringBefore('?')
        return listOf(".zip", ".rar", ".7z", ".gz", ".tar").any { lower.endsWith(it) }
    }

    private fun JsonObject.firstString(vararg keys: String): String {
        for (k in keys) {
            val el = get(k) ?: continue
            if (el.isJsonNull || !el.isJsonPrimitive) continue
            return runCatching { el.asString }.getOrDefault("")
        }
        return ""
    }

    private fun JsonObject.firstInt(vararg keys: String): Int {
        for (k in keys) {
            val el = get(k) ?: continue
            if (el.isJsonNull || !el.isJsonPrimitive) continue
            return runCatching { el.asInt }.getOrDefault(0)
        }
        return 0
    }

    companion object {
        private const val TAG = "AssrtSource"

        /** 中文 + 集数命中 + 片名命中 = 90；≥75 视为足够可靠，停止换关键词 */
        private const val STRONG_HIT = 75
    }
}
