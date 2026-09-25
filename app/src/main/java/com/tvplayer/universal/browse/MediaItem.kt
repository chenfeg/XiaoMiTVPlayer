package com.tvplayer.universal.browse

enum class SourceKind { LOCAL, USB, SMB }

data class MediaItem(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val kind: SourceKind,
    /** 顶层卷/共享的补充说明（实际挂载路径），列表右侧灰色小字；排查"这是哪个盘"必备 */
    val hint: String = ""
) {
    val displayName: String
        get() = if (kind == SourceKind.SMB) path.substringAfterLast('/') else name
}

object Kinds {
    val VIDEO_EXT = setOf(
        "mp4", "mkv", "mov", "avi", "wmv", "flv", "rm", "rmvb", "ts", "m2ts", "mpg",
        "mpeg", "vob", "3gp", "webm", "m4v", "asf", "strm", "iso", "mpls", "divx"
    )
    val SUBTITLE_EXT = setOf("srt", "ass", "ssa", "sub", "vtt")

    fun isVideo(name: String) = name.substringAfterLast('.', "").lowercase() in VIDEO_EXT
    fun isSubtitle(name: String) = name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXT
}

/**
 * 外挂字幕的文件名匹配。
 *
 * 为什么不能要求"片名一字不差"：真机反馈里字幕就叫
 * `《冰与火之歌.权力的游戏》S01E01 ... 爱奇艺 1080P t.snr.sc.srt`，
 * 视频是同一串去掉 `.sc`。字幕库/射手的惯例是**在视频名后面追加语言标记**
 * （.sc/.tc/.chs/.cht/.zh…），而压制组的命名又在片名后面跟一串分辨率/编码标记。
 * 所以两头都要容错。原来"尾巴里出现任何一个不认识的词就不算这部片子的字幕"太严：
 * 真机上视频名带 `t.snr` 这种发布组后缀（不在任何词表里），字幕却常常只写到
 * `...权力的游戏.S01E01.srt` 为止 —— 于是本地那条明明对得上的字幕被我们自己判成
 * "另一部片子"，一条都不读，屏幕上一句"未找到匹配的中文字幕"。
 * 现在按可信度分档，认不出的尾巴降权而不是判死刑：
 *
 * - 字幕名 = 视频名 + 语言/属性标记 → 90（带中文标记）/ 60
 * - 视频名 = 字幕名 + 分辨率/编码标记 → 50
 * - 字幕名 = 视频名 + 看不懂的尾巴 → 40
 * - 视频名 = 字幕名 + 看不懂的尾巴 → 30
 * - 两头连前缀都接不上（同目录另一部片子）→ 不算
 */
object SidecarMatch {

    /** 越大越优先；null = 不是这个视频的外挂字幕 */
    fun rank(videoName: String, subtitleName: String): Int? {
        if (!Kinds.isSubtitle(subtitleName)) return null
        val v = stem(videoName)
        val s = stem(subtitleName)
        if (v.isEmpty() || s.isEmpty()) return null
        if (v == s) return 100
        tailOf(v, s)?.let { words ->
            return if (words.any { CHINESE_TAG.matches(it) }) 90 else 60
        }
        tailOf(s, v)?.let { words ->
            // 视频名比字幕名长：尾巴全是分辨率/编码词才算（认不出的词交给 prefixOnly 兜底）
            return if (words.all { JUNK_TAG.matches(it) }) 50 else if (prefixOnly(s, v)) 30 else null
        }
        return when {
            prefixOnly(v, s) -> 40
            prefixOnly(s, v) -> 30
            else -> null
        }
    }

    /** short 是 long 的整段前缀且接在分隔符上：词表认不出尾巴时的兜底依据 */
    private fun prefixOnly(short: String, long: String): Boolean =
        long.startsWith(short) && short.length >= 6 && long[short.length].isSeparator()

    /** 字幕是不是标了中文（用于并列时挑哪个先加载） */
    fun taggedChinese(subtitleName: String): Boolean =
        stem(subtitleName).split(SEP).any { CHINESE_TAG.matches(it) }

    /** longer 以 shorter 开头时，返回尾巴上的各个词；接不上返回 null */
    private fun tailOf(shorter: String, longer: String): List<String>? {
        if (!longer.startsWith(shorter)) return null
        val rest = longer.substring(shorter.length)
        if (rest.isEmpty() || !rest[0].isSeparator()) return null
        val words = rest.split(SEP).filter { it.isNotBlank() }
        return words.takeIf { it.isNotEmpty() && it.all { w -> KNOWN_TAG.matches(w) } }
    }

    private fun stem(name: String): String =
        name.substringBeforeLast('.').lowercase().trim()

    private fun Char.isSeparator(): Boolean =
        this == '.' || this == '_' || this == '-' || this == ' ' || this == '[' || this == '('

    private val SEP = Regex("[ ._\\-\\[\\]()]+")

    private val CHINESE_TAG = Regex(
        "zh|zho|chi|chs|cht|cn|tc|sc|zhtw|zhcn|zh-tw|zh-cn|chinese|中文|简体|繁体|双语|国配|普通话|大字"
    )

    /** 分辨率/编码/容器一类的装饰词：出现在"字幕名比视频名短"的那段尾巴里 */
    private val JUNK_TAG = Regex(
        "480p|720p|1080p|2160p|4k|8k|x264|x265|h264|h265|hevc|avc|av1|vp9|" +
            "web-?dl|web-?rip|bluray|bdrip|dvdrip|hdtv|hdcam|remux|proper|repack|" +
            "aac|ac3|eac3|dd5\\.1|ddp5\\.1|dts|dts-?hd|truehd|atmos|flac|mp3|mp2|" +
            "hdr10\\+?|hdr|sdr|10-?bit|8-?bit|mp4|mkv|avi|mov|ts|internal|embed|" +
            CHINESE_TAG.pattern
    )

    /** 尾巴上允许出现的词：语言标记、常见属性、装饰词，出现不认识的词就不算这部片子的字幕 */
    private val KNOWN_TAG = Regex(
        "en|eng|ja|jap|ko|kr|fra|fre|de|deu|es|spa|ru|it|pt|sdh|forced|default|full|" +
            "hardsub|subs?|subtitles?|v2|v3|fixed|" + JUNK_TAG.pattern
    )
}
