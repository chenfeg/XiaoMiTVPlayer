package com.tvplayer.universal.subtitle.net

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tvplayer.universal.BuildConfig
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.subtitle.ReleaseInfo
import com.tvplayer.universal.subtitle.SubCandidate
import com.tvplayer.universal.subtitle.SubtitleSource
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * OpenSubtitles 官方 REST 接口（api.opensubtitles.com/api/v1）。
 *
 * 中文条目的处理能力：库里既有全球 75 种语言的字幕，也有一整批中文上传者，
 * 而且是**按文本 + 季/集检索**，不要求先算出文件指纹 —— 正好补上 assrt 之外的另一条路。
 *
 * 鉴权是真门槛，而且是**两个**门槛（官方 best-practices 页写得很明白）：
 *  - key 放在 `Api-Key` 请求头里，不是 `Authorization: Bearer`；带错头的表现是
 *    `403 {"message":"You cannot consume this service"}` —— 跟"没带 key"同一个响应，
 *    真机上我们就是这么把一条自己写错的头误判成"key 被墙"的；
 *  - `User-Agent` 必须是"应用名 + 版本"（`MyApp v1.2.3`），格式不对单独返回
 *    `403 User-Agent header is wrong`。
 * 设置页要填：
 *  - API key（官网 "Add this application to get APIKEY"，免费，一个 key 全局用）；
 *  - 账号用户名/密码（可选）：取字幕正文的 /download 步要按用户配额发放临时链接，
 *    没填就只走能搜到、拿不到正文的那一半能力。
 * key 为空时本源整体跳过，不产生任何网络请求。
 */
class OpenSubtitlesSource(private val prefs: Prefs) : SubtitleSource {

    override val id = "opensub"

    private val base get() = prefs.openSubBaseUrl.trimEnd('/')

    private fun configured() = prefs.openSubApiKey.isNotBlank()

    private fun headers(withUserToken: Boolean = false): Map<String, String> = buildMap {
        // 官方文档的鉴权头是 Api-Key（best-practices 页两个 curl 示例都是 `--header 'Api-Key: <your-key>'`）。
        // 之前只发 `Authorization: Bearer <key>`，网关当作"没带 key"，
        // 于是真机日志里那条 403 "You cannot consume this service" 一直以为是 key 被墙。
        put("Api-Key", prefs.openSubApiKey)
        // UA 格式不对也是一条独立的 403："User-Agent header is wrong; set it to App name with version eg: MyApp v1.2.3"
        put("User-Agent", "TVPlayer v${BuildConfig.VERSION_NAME}")
        put("Accept", "application/json")
        if (withUserToken && userToken.isNotBlank()) put("User-Token", userToken)
    }

    /** /login 拿到的用户令牌，一次搜索会话内复用 */
    @Volatile
    private var userToken = ""

    override suspend fun search(info: ReleaseInfo, hash: String?, size: Long): List<SubCandidate> {
        if (!configured()) return emptyList()
        val merged = LinkedHashMap<String, SubCandidate>()
        for (query in info.queries()) {
            val found = runCatching { searchOnce(query, info, hash) }
                .onFailure { Log.w(TAG, "opensub search '$query' failed", it) }
                .getOrDefault(emptyList())
            for (c in found) {
                val old = merged[c.externalId]
                if (old == null || c.score > old.score) merged[c.externalId] = c
            }
            if (found.any { it.score >= STRONG_HIT }) break
        }
        return merged.values.sortedByDescending { it.score }
    }

    private fun searchOnce(query: String, info: ReleaseInfo, hash: String?): List<SubCandidate> {
        // 参数按官方性能建议字典序发送、值转小写；文档的参数表里没有 limit，
        // 多余的参数会触发一次重定向（他们明确要求"不带默认值、按字母序"以躲开 301）。
        val params = sortedMapOf("query" to query.lowercase(Locale.ROOT))
        // OssHash 算出来的就是文档要求的 16 位十六进制 moviehash，指纹能直接命中同一版源
        hash?.let { params["moviehash"] = it }
        info.season?.let { params["season_number"] = it.toString() }
        info.episode?.let { params["episode_number"] = it.toString() }
        val body = String(Net.getForm("$base/subtitles", params, headers()), StandardCharsets.UTF_8)
        val data = JsonParser.parseString(body).asJsonObject.getAsJsonArray("data")
            ?: return emptyList()
        return data.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val attr = obj.getAsJsonObject("attributes") ?: return@mapNotNull null
            val id = obj.str("id")
            if (id.isBlank()) return@mapNotNull null
            // 片名/季/集在 attributes.feature_details 里；顶层只有 language、download_count 这些。
            // 原来按顶层读，永远读不到，标题全退化成 "OpenSubtitles #id"，集数加分也从来没生效。
            val feat = attr.getAsJsonObject("feature_details") ?: return@mapNotNull null
            val lang = attr.str("language")
            val title = listOf(
                feat.str("movie_name").ifBlank { feat.str("title") },
                feat.int("season_number")?.let { "S%02d".format(it) }.orEmpty(),
                feat.int("episode_number")?.let { "E%02d".format(it) }.orEmpty()
            ).filter { it.isNotBlank() }.joinToString(" ")
            val chinese = lang.startsWith("zh") || lang.startsWith("chi") || lang.startsWith("zho")
            // 正文不在 data[].id 上：/download 要同时带 sub_id 和 files[].file_id，
            // 只发 sub_id 真机返回 406 {"message":"Invalid file_id"}。多碟条目取第一碟。
            val fileId = attr.getAsJsonArray("files")
                ?.mapNotNull { it as? JsonObject }
                ?.mapNotNull { it.long("file_id") }
                ?.firstOrNull()
            // 文档：请求里带 moviehash 时响应多出 moviehash_match，命中的条目必定排在前面。
            // 这是"这条字幕就是为这个文件准备的"的最强信号，比片名相似可靠得多。
            val hashMatch = attr.bool("moviehash_match")
            SubCandidate(
                sourceId = this.id,
                externalId = id,
                title = title.ifBlank { "OpenSubtitles #$id" },
                // /download 给的是明文直链，不存在包
                mayBeZip = false,
                // 响应里没有 format 字段（文件后缀在 files[].file_name），解析本来就看内容
                formatHint = null,
                editor = listOfNotNull(
                    "OpenSubtitles",
                    if (chinese) "中文" else lang.uppercase(Locale.ROOT).ifBlank { null },
                    if (hashMatch) "指纹命中" else null,
                    attr.int("download_count")?.let { "下载 $it 次" }
                ).joinToString(" · "),
                score = 40 +
                    (if (chinese) 25 else -20) +
                    // 下载量只能微调同档次之间的顺序。原来这项不设上限，真机上一条
                    // "2015 - Gabbar Is Back"（别的片子）靠 7.7 万次下载拿到 815 分，
                    // 把片名/集数全不对的条目顶到了第一名。
                    ((attr.int("download_count") ?: 0) / 1000).coerceAtMost(10) +
                    (if (hashMatch) 25 else 0) +
                    (if (info.isSeries && feat.int("episode_number") == info.episode) 20 else 0),
                fileId = fileId
            )
        }
    }

    override suspend fun fetch(candidate: SubCandidate, info: ReleaseInfo): ByteArray {
        val link = downloadLink(candidate)
            ?: throw IllegalStateException("OpenSubtitles 没有给出下载链接")
        return Net.get(link)
    }

    /** sub_id + file_id → 临时直链（有效期很短，取到就立刻下载） */
    private suspend fun downloadLink(candidate: SubCandidate): String? {
        val subId = candidate.externalId
        val fileId = candidate.fileId
            ?: throw IllegalStateException("OpenSubtitles 这条记录没有 file_id，无法取正文（搜索到了但下载不了）")
        if (prefs.openSubUser.isNotBlank() && userToken.isBlank()) login()
        val root = JsonParser.parseString(
            String(
                Net.postJson(
                    "$base/download",
                    JsonObject().apply {
                        addProperty("sub_id", subId.toLong())
                        addProperty("file_id", fileId)
                    }.toString(),
                    headers(withUserToken = true)
                ),
                StandardCharsets.UTF_8
            )
        ).asJsonObject
        return (root.getAsJsonObject("data") ?: root).str("link").takeIf { it.isNotBlank() }
    }

    private suspend fun login() {
        val root = runCatching {
            JsonParser.parseString(
                String(
                    Net.get(
                        "$base/login",
                        // 用户名+密码走 Basic，返回体里给 user token；后面取正文要带它
                        headers() + mapOf(
                            "Authorization" to okhttp3.Credentials.basic(
                                prefs.openSubUser, prefs.openSubPass
                            )
                        )
                    ),
                    StandardCharsets.UTF_8
                )
            ).asJsonObject
        }.getOrElse {
            Log.w(TAG, "opensub login failed", it)
            return
        }
        val token = root.getAsJsonObject("data")?.str("token")
        if (token.isNullOrBlank()) Log.w(TAG, "opensub login 没返回 token：$root" )
        else userToken = token
    }

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive)
            runCatching { get(key).asString }.getOrDefault("") else ""

    private fun JsonObject.int(key: String): Int? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asInt }.getOrNull() else null

    private fun JsonObject.long(key: String): Long? =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asLong }.getOrNull() else null

    private fun JsonObject.bool(key: String): Boolean =
        if (has(key) && !get(key).isJsonNull) runCatching { get(key).asBoolean }.getOrDefault(false) else false

    companion object {
        private const val TAG = "OpenSubSource"

        /** 中文 + 集数命中即算够格，不必再换关键词 */
        private const val STRONG_HIT = 80
    }
}
