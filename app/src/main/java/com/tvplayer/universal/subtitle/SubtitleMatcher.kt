package com.tvplayer.universal.subtitle

import android.content.Context
import android.util.Log
import com.tvplayer.universal.browse.ContentSource
import com.tvplayer.universal.browse.FileSource
import com.tvplayer.universal.browse.MediaItem
import com.tvplayer.universal.browse.SourceKind
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.subtitle.net.AssrtSource
import com.tvplayer.universal.subtitle.net.FakeSubSource
import com.tvplayer.universal.subtitle.net.OpenSubtitlesSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * 多源聚合：AssSub（中文人工字幕质量最高）+ OpenSubtitles（全球库，按片名/季集检索）+
 * FakeSub（文件指纹，改名/无片名时唯一可靠，需要自建实例）。
 * 结果合并打分排序；下载统一走缓存，命中缓存则零网络。
 *
 * 搜索是"每个源各跑一遍再并起来"，所以把每个源各自返回了几条也带出来 ——
 * 电视上"没找到中文字幕"要能当场分清是**一个源都没答话**（网络/配置）还是
 * **答了但都不够格**（片名解析/分数线），这两种的下一步动作完全不同。
 */
class SubtitleMatcher(private val sources: List<SubtitleSource>) {

    /**
     * 视频指纹按 path 缓存（实例级）：一次 ranked() 要 open 源读首尾 128KB，
     * 面板每次打开重搜都会重算；matcher 在播放页内共享后，整个播放期只算一次，
     * SMB 下尤其值钱（省一次远程 open + 往返）。失败结果也缓存，播放期内不重试。
     */
    private val fingerprintCache = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, Long>>()

    /** 一次聚合搜索的结果：排序后的候选 + 各源返回条数 + 算出的文件指纹 */
    data class Report(
        val ranked: List<Pair<SubCandidate, Int>>,
        val perSource: List<Pair<String, Int>>,
        val counts: String,
        val fingerprint: String?
    )

    suspend fun candidates(video: MediaItem, source: FileSource): List<SubCandidate> =
        ranked(video, source).ranked.map { it.first }

    /** 候选 + 最终分（含本地加分）。自动模式要用这个分，人工搜索列表也按它排 */
    suspend fun ranked(video: MediaItem, source: FileSource): Report {
        val info = ReleaseInfo.parse(video.name)
        val (hash, size) = fingerprintOf(video, source)

        return coroutineScope {
            val perSource = sources.map { src ->
                async {
                    val found = runCatching { src.search(info, hash, size) }
                        .onFailure { Log.w(TAG, "source ${src.id} failed", it) }
                        .getOrDefault(emptyList())
                    src.id to found
                }
            }.awaitAll()
            val ranked = perSource.flatMap { it.second }
                .distinctBy { it.sourceId + it.externalId }
                .map { it to scoreOf(it, info) }
                .sortedByDescending { it.second }
            Report(
                ranked = ranked,
                perSource = perSource.map { it.first to it.second.size },
                counts = perSource.joinToString(" ") { "${it.first}=${it.second.size}" },
                fingerprint = hash
            )
        }
    }

    private fun scoreOf(c: SubCandidate, info: ReleaseInfo): Int {
        var s = c.score
        val title = c.title.lowercase()
        // 用片名里的实义词比对，不是整串：整串里混着平台名和水印词，
        // 而字幕站条目的写法几乎不可能一模一样。
        if (info.titleMatches(title)) s += 15
        if (info.isSeries && Regex("(?i)s?%02d[. _-]?e?%02d".format(info.season ?: 0, info.episode ?: 0))
                .containsMatchIn(title)
        ) s += 20
        if (title.contains("chs") || title.contains("简")) s += 5
        return s
    }

    /**
     * 自动模式取第一名。门槛必须打在**最终分**上：
     * 原来这里读的是各源给的原始分，本地那 15/20 分加分只参与了排序、没参与门槛，
     * 于是"片名和集数都对得上、只是上传者没刷票"的字幕会被判成不合格。
     */
    suspend fun best(video: MediaItem, source: FileSource): SubCandidate? =
        ranked(video, source).ranked.firstOrNull { it.second >= MIN_AUTO_SCORE }?.first

    suspend fun resolve(
        context: Context,
        candidate: SubCandidate,
        info: ReleaseInfo
    ): File {
        val tag = listOfNotNull(info.season?.let { "S%02d".format(it) }, info.episode?.let { "E%02d".format(it) })
            .joinToString("")
        SubtitleCache.get(context, candidate, tag)?.let { return it }
        val src = sources.first { it.id == candidate.sourceId }
        val raw = src.fetch(candidate, info)
        return SubtitleCache.put(context, candidate, raw, tag)
    }

    /** 取指纹：先查缓存，未命中才 open 源计算；并发重复计算无害，保留先到的结果 */
    private suspend fun fingerprintOf(
        video: MediaItem,
        source: FileSource
    ): Pair<String?, Long> {
        fingerprintCache[video.path]?.let { return it }
        val fp = runCatching { fileFingerprint(video, source) }
            .getOrElse {
                Log.w(TAG, "fingerprint failed", it)
                null to 0L
            }
        return fingerprintCache.putIfAbsent(video.path, fp) ?: fp
    }

    private suspend fun fileFingerprint(video: MediaItem, source: FileSource): Pair<String?, Long> {
        if (video.kind == SourceKind.SMB && !SMB_HASH_ENABLED) return null to 0L
        val opened = source.open(video)
        return try {
            if (opened.length < HASH_MIN_BYTES) return null to opened.length
            OssHash.of(opened) to opened.length
        } finally {
            runCatching { opened.close() }
        }
    }

    companion object {
        private const val TAG = "SubtitleMatcher"
        private const val HASH_MIN_BYTES = 4L * 1024 * 1024

        /**
         * 自动加载的分数线。各源的"中文 + 集数命中"都能到 75~90，
         * 45 分左右是"语言对得上但没有任何一部对得上"的那一类，不能自动上屏。
         */
        const val MIN_AUTO_SCORE = 45

        /** SMB 上读 128KB 指纹很便宜，保留开关以备慢网络 */
        const val SMB_HASH_ENABLED = true

        /** 三个源都在这，缺配置的源自己返回空表 */
        fun defaultSources(prefs: Prefs) = listOf(
            AssrtSource(prefs),
            OpenSubtitlesSource(prefs),
            FakeSubSource(prefs)
        )
    }
}
