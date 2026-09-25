package com.tvplayer.universal.subtitle

/**
 * 极简片名解析器：从发布名提取 标题/年份/季集。
 * 例：Zone.Toe.S02E03.1080p.WEB-DL.DDP5.1.H.264-GRP.mkv
 *  → title="Zone Toe" season=2 episode=3
 */
data class ReleaseInfo(
    val title: String,
    val year: Int?,
    val season: Int?,
    val episode: Int?
) {
    val isSeries get() = season != null || episode != null

    /**
     * 片名里的各个词。用来和候选字幕的标题比对，也用来挑一个"最能代表这部片子"的词
     * 去字幕站检索（整串里往往混着平台名和水印词，检索会一条都不返回）。
     */
    val titleWords: List<String>
        get() = title.split(' ')
            .map { it.trim() }
            .filter { it.length >= 3 && it.lowercase() !in STOP_WORDS }

    /** 命中片名里任何一个实义词即算同一部片子 */
    fun titleMatches(other: String): Boolean {
        val lower = other.lowercase()
        return titleWords.any { lower.contains(it.lowercase()) }
    }

    /** 片名里最像"正式名字"的那个词（最长的实义词） */
    val mainTitleWord: String? get() = titleWords.maxByOrNull { it.length }

    /**
     * 依次尝试的查询词。实测 assrt 单查 "S01E01" 会命中一堆别的剧，
     * 所以有片名时必须带片名，纯集数只作无片名时的兜底。
     *
     * 中文发布名里几乎必然混着平台名（"冰与火之歌 权力的游戏 S01E01 1080P 爱奇艺"），
     * 整串丢给字幕站检索会一条都不返回 —— 所以除了整串，还要拿"最长的那个词"再查一遍。
     */
    fun queries(): List<String> {
        val out = mutableListOf<String>()
        val s = season
        val e = episode
        val epCode = if (s != null && e != null) "S%02dE%02d".format(s, e) else null
        val t = title
        if (t.isNotBlank()) {
            if (epCode != null) out += "$t $epCode"
            val head = titleWords.maxByOrNull { it.length }
            if (head != null && head != t) {
                if (epCode != null) out += "$head $epCode"
                out += head
            }
            if (e != null) out += if (s != null) "$t season $s episode $e" else "$t episode $e"
            if (s != null) out += "$t S%02d".format(s)
            if (year != null) out += "$t $year"
            out += t
        } else {
            if (epCode != null) out += epCode
            if (e != null) out += "E%02d".format(e)
        }
        return out.distinct()
    }

    companion object {
        private val BRACKET = Regex("[\\[({][^\\])}]*[\\])}]")
        private val EP = Regex("""(?i)(?:s(\d{1,2})[\s._-]?e(p)?(\d{1,3}))|(?<!\d)(\d{1,2})x(\d{2,3})(?:x\d{2,3})?(?!\d)|(?:episode|ep|cap)\.?\s*(\d{1,3})""")
        private val YEAR = Regex("""(?<![1-2]\d{3})(19\d{2}|20[0-4]\d)(?![\d])""")
        private val JUNK = Regex(
            """(?i)\b(1080p|2160p|720p|480p|4k|8k|x264|x265|h\.?264|h\.?265|hevc|avc|h\.?265|""" +
                """web-?rip|web-?dl|bluray|bdrip|dvdrip|hdtv|hdts|atsc|dv|hdtv|""" +
                """aac|ac3|ddp?5\.1|dts(?:-hd)?|truehd|atmos|flac|mp[23]|""" +
                """proper|repack|extended|unrated|remastered|complete|multi|dual|chs|cht|chi|zho|""" +
                """10-bit|10bit|8bit|hdr10\+?|hdr|sdr|vs|only)\b"""
        )

        /** 汉字没有词边界，\b 对整串中文不起作用，水印词只能单列一张表整词删掉 */
        private val CJK_JUNK = Regex(
            "爱奇艺|优酷|腾讯视频|腾讯|西瓜视频|哔哩哔哩|bilibili|樱花动漫|粉丝团|" +
                "字幕组|双语字幕|中文字幕|国配|国语|粤语|特效字幕|简体|繁体|高清|蓝光|" +
                "超清|标清|完结|全\\d+集"
        )

        /** 切词后剩下的连接词/介词：拿去比对片名没用，还会让 "of" 这种短词到处命中 */
        private val STOP_WORDS = setOf(
            "the", "and", "for", "from", "with", "that", "this", "season", "series",
            "episode", "part", "vol", "of", "le", "la", "les", "der", "die", "das"
        )

        /** 场景发布名的特征：分辨率/来源/编码标记。有这个尾巴才敢删 "-组名" */
        private val SCENE = Regex(
            """(?i)\b(720p|1080p|2160p|4k|8k|480p|bluray|bdrip|dvdrip|hdtv|web-?dl|web-?rip|""" +
                """x26[45]|h\.?26[45]|hevc|avc|remux|proper|repack)\b"""
        )

        /** 结尾的 "-组名"（H.264-GRP 里的 GRP）：字母数字，2~12 位 */
        private val GROUP = Regex("""-[A-Za-z0-9]{2,12}$""")

        fun parse(fileName: String): ReleaseInfo {
            var name = fileName.substringBeforeLast('.')
            name = BRACKET.replace(name, " ")
            var season: Int? = null
            var episode: Int? = null
            EP.find(name)?.groups?.let { g ->
                season = g[1]?.value?.toIntOrNull() ?: g[4]?.value?.toIntOrNull()
                episode = g[3]?.value?.toIntOrNull() ?: g[5]?.value?.toIntOrNull()
                    ?: g[6]?.value?.toIntOrNull()
            }
            val year = YEAR.find(name)?.value?.toIntOrNull()
            val scene = SCENE.containsMatchIn(name) || season != null || episode != null
            name = EP.replace(name, " ")
            if (year != null) name = name.replace(year.toString(), " ")
            name = JUNK.replace(name, " ")
            name = CJK_JUNK.replace(name, " ")
            // 压制组后缀（"...-GRP"）只在确属场景发布名时去掉 —— 那类名字尾巴上的
            // "-组名"跟片子无关；普通片子名里带连字符的不能误删。
            if (scene) name = GROUP.replace(name, " ")
            name = name.replace("[ ._~\\-《》]".toRegex(), " ").trim().replace(Regex("\\s+"), " ")
            return ReleaseInfo(name, year, season, episode)
        }
    }
}
