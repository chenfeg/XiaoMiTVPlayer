package com.tvplayer.universal.ui

import android.content.Context
import android.view.View
import com.tvplayer.universal.R
import com.tvplayer.universal.browse.FileSource
import com.tvplayer.universal.browse.MediaItem
import com.tvplayer.universal.data.EventLog
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.subtitle.Cue
import com.tvplayer.universal.subtitle.LangDetect
import com.tvplayer.universal.subtitle.ReleaseInfo
import com.tvplayer.universal.subtitle.SubCandidate
import com.tvplayer.universal.subtitle.SubtitleMatcher
import com.tvplayer.universal.subtitle.SubtitleParser
import com.tvplayer.universal.subtitle.SubtitleText
import com.tvplayer.universal.subtitle.sampleText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 面板选中一条后下载并上屏的结果：成功（面板保留并提示已应用，含条目数）/ 失败（留在面板并给原因） */
sealed interface FetchResult {
    data class Applied(val cueCount: Int) : FetchResult
    data class Failed(val reasonLabel: String) : FetchResult
}

/**
 * 字幕编排（原 PlayerActivity 内的 autoSubtitle/loadSubtitle/readSidecar/showCues/
 * applySubtitleFile 与 KEYCODE_CAPTIONS 处理）。
 *
 * 负责：同级外挂字幕列举读取 → 中文判定上屏 → 在线聚合搜索、逐条下载验正文语言 →
 * 手动选择的下载与解码；并持有 [view] 的渲染配置（字号/延时/可见性）。
 *
 * 所有阻塞工作跑在 [scope] 的 IO 协程里；屏上提示统一经 [onHint]（实现里唤出 OSD），
 * View 操作切回主线程。解析/匹配等纯逻辑在各自的类里已有单测，本类是装配层。
 */
class SubtitleController(
    private val scope: CoroutineScope,
    private val context: Context,
    private val view: SubtitleView,
    private val video: MediaItem,
    private val source: FileSource,
    private val prefs: Prefs,
    private val matcher: SubtitleMatcher,
    /** IO 协程里要在屏上说话时调用（本类负责切主线程）：实现即 showOsd */
    private val onHint: (String) -> Unit
) {
    init {
        // 初始字号/延时：原本散在 onCreate，渲染配置归字幕自己
        view.sizePercent = prefs.subtitleSizePercent
        view.delayMs = prefs.subtitleDelayMs
    }

    /** onPrepared 后触发；整段必须在 IO（列 SMB 目录、读字节、OkHttp 同步调用全是阻塞） */
    fun autoLoad() {
        scope.launch(Dispatchers.IO) {
            runCatching { load() }
                .onFailure {
                    EventLog.line("字幕流程异常 ${it.javaClass.simpleName}: ${it.message}")
                }
        }
    }

    private suspend fun hint(text: String) = withContext(Dispatchers.Main) { onHint(text) }

    private suspend fun showCues(cues: List<Cue>) =
        withContext(Dispatchers.Main) {
            view.sizePercent = prefs.subtitleSizePercent
            view.delayMs = prefs.subtitleDelayMs
            view.visibility = View.VISIBLE
            view.setCues(cues)
        }

    private suspend fun load() {
        val sidecars = runCatching { source.sidecarSubtitles(video) }
            .getOrElse { e ->
                EventLog.line("同级字幕列举失败 ${e.javaClass.simpleName}: ${e.message}")
                emptyList()
            }
        if (sidecars.isEmpty()) {
            EventLog.line("同级没有认得出的外挂字幕")
        } else {
            EventLog.line(
                "同级外挂字幕 ${sidecars.size} 条：" +
                    sidecars.take(4).joinToString(" | ") { it.name }
            )
        }

        // 本地字幕有一条就立刻上屏：中文判定只是"要不要再去在线找"的依据，
        // 不是"配不配显示"的门槛 —— 只有英文外挂时也要能看见字。
        var fallback: Pair<String, List<Cue>>? = null
        for (s in sidecars.take(4)) {
            val cues = runCatching { readSidecar(s) }.getOrElse { e ->
                EventLog.line("字幕 ${s.name} 读取失败 ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            if (cues.isNullOrEmpty()) {
                EventLog.line("字幕 ${s.name} 没解析出条目（格式不支持或文件本身有问题）")
                continue
            }
            if (LangDetect.mostlyChinese(cues.sampleText())) {
                EventLog.line("外挂中文字幕已加载 ${s.name}（${cues.size} 条）")
                showCues(cues)
                hint(context.getString(R.string.player_subtitle_found, s.name))
                return
            }
            if (fallback == null) {
                fallback = s.name to cues
                EventLog.line("字幕 ${s.name} 不是中文（${cues.size} 条），先作为备选显示")
            }
        }
        fallback?.let { (_, cues) -> showCues(cues) }

        if (!prefs.autoSubtitle) return
        hint(context.getString(R.string.player_subtitle_loading))
        val report = runCatching { matcher.ranked(video, source) }
            .getOrElse { e ->
                EventLog.line("在线字幕搜索失败 ${e.javaClass.simpleName}: ${e.message}")
                hint(context.getString(R.string.player_subtitle_error, e.javaClass.simpleName))
                return
            }
        val top = report.ranked.firstOrNull()
        val qualified = report.ranked.filter { it.second >= SubtitleMatcher.MIN_AUTO_SCORE }
        EventLog.line(
            "在线候选 ${report.ranked.size} 条（${report.counts}），" +
                "指纹=${report.fingerprint ?: "没算出来"}，最高分=${top?.second ?: "-"}，" +
                "自动加载门槛=${SubtitleMatcher.MIN_AUTO_SCORE}"
        )
        if (top == null || top.second < SubtitleMatcher.MIN_AUTO_SCORE) {
            // 屏上必须说清"是没答话还是不够格"：只写"未找到匹配的中文字幕"的话，
            // 换源、换文件名、填 token 三种下一步动作分不出来。
            hint(
                context.getString(
                    if (fallback == null) R.string.player_subtitle_none_reason
                    else R.string.player_subtitle_local_only_reason,
                    report.counts, top?.second ?: 0, SubtitleMatcher.MIN_AUTO_SCORE
                )
            )
            return
        }
        // 字幕站标的语言不算数（真机上出现过"标着中文、正文整条是英文"的候选），
        // 所以下一条验一条，验的是解析后的正文；试满几条仍没有中文，就把第一条摆出来并说清。
        var notChinese: Pair<SubCandidate, List<Cue>>? = null
        val info = ReleaseInfo.parse(video.name)
        for ((cand, _) in qualified.take(MAX_ONLINE_TRIES)) {
            val file = runCatching { matcher.resolve(context, cand, info) }
                .getOrElse { e ->
                    EventLog.line("字幕下载失败 ${cand.title}：${e.javaClass.simpleName}: ${e.message}")
                    null
                } ?: continue
            val cues = runCatching {
                SubtitleParser.parse(file.name, SubtitleText.decode(file.readBytes()))
            }.getOrNull()
            if (cues.isNullOrEmpty()) {
                EventLog.line("下载的字幕解析不出条目 ${file.name}")
                continue
            }
            val sample = cues.sampleText()
            val ratio = LangDetect.chineseRatio(sample)
            EventLog.line(
                "在线字幕 ${cand.title}（${cues.size} 条）正文中文占比 " +
                    "${"%.1f%%".format(ratio * 100)}（判定线 ${"%.0f%%".format(LangDetect.CHINESE_RATIO * 100)}）"
            )
            if (ratio > LangDetect.CHINESE_RATIO) {
                EventLog.line("在线中文字幕已加载 ${cand.title}（${cues.size} 条）")
                showCues(cues)
                hint(context.getString(R.string.player_subtitle_found, cand.title))
                return
            }
            if (notChinese == null) notChinese = cand to cues
        }
        val (best, bestCues) = notChinese ?: run {
            // 一条都没能变成条目：每条的失败原因已各自进日志，屏上沿用"没有够格的中文字幕"
            hint(
                context.getString(
                    R.string.player_subtitle_none_reason,
                    report.counts, top.second, SubtitleMatcher.MIN_AUTO_SCORE
                )
            )
            return
        }
        if (fallback != null) {
            // 本地那条是用户自己放的，不拿一条在线英文去顶掉它
            hint(
                context.getString(
                    R.string.player_subtitle_local_only_reason,
                    report.counts, top.second, SubtitleMatcher.MIN_AUTO_SCORE
                )
            )
            return
        }
        showCues(bestCues)
        hint(context.getString(R.string.player_subtitle_not_chinese, best.title))
    }

    /** 读一条外挂字幕：本地和 SMB 都走 ContentSource，字节统一交给 SubtitleText 嗅探编码 */
    private suspend fun readSidecar(item: MediaItem): List<Cue> {
        val bytes = source.open(item).use { src ->
            if (src.length <= 0 || src.length > SubtitleText.MAX_BYTES) {
                EventLog.line("字幕 ${item.name} 大小不合适（${src.length} 字节），跳过")
                return emptyList()
            }
            val out = ByteArray(src.length.toInt())
            var off = 0
            while (off < out.size) {
                val n = src.readAt(off.toLong(), out, out.size - off)
                if (n <= 0) break
                off += n
            }
            if (off < out.size) out.copyOf(off) else out
        }
        return SubtitleParser.parse(item.name, SubtitleText.decode(bytes))
    }

    // ---------- 供面板调用 ----------

    /** 面板搜索：异常已在此记日志，返回 null 表示失败（面板显示"搜索失败"） */
    suspend fun searchReport(): SubtitleMatcher.Report? =
        runCatching { matcher.ranked(video, source) }
            .onFailure {
                EventLog.line("面板搜索异常 ${it.javaClass.simpleName}: ${it.message}")
            }
            .getOrNull()

    /**
     * 面板选中一条：下载 → 解码 → 上屏，全程在调用方的 IO 协程里。
     * 下载抛异常或解析不出条目都算 Failed（面板不关，提示用户换一条）。
     */
    suspend fun fetchAndApply(candidate: SubCandidate): FetchResult {
        val file = try {
            matcher.resolve(context, candidate, ReleaseInfo.parse(video.name))
        } catch (e: Exception) {
            EventLog.line(
                "字幕下载失败 ${candidate.title}：${e.javaClass.simpleName}: ${e.message}"
            )
            return FetchResult.Failed("下载失败：${e.javaClass.simpleName}")
        }
        val cues = runCatching {
            SubtitleParser.parse(file.name, SubtitleText.decode(file.readBytes()))
        }.getOrNull()
        if (cues.isNullOrEmpty()) {
            EventLog.line("字幕解析无条目 ${candidate.title}")
            return FetchResult.Failed("解析无字幕条目，换一个试试")
        }
        withContext(Dispatchers.Main) {
            view.sizePercent = prefs.subtitleSizePercent
            view.delayMs = prefs.subtitleDelayMs
            view.visibility = View.VISIBLE
            view.setCues(cues)
        }
        return FetchResult.Applied(cues.size)
    }

    companion object {
        /** 自动加载最多试几条在线候选（每条都要下载 + 解析 + 验语言） */
        private const val MAX_ONLINE_TRIES = 3
    }
}
