package com.tvplayer.universal.subtitle

data class Cue(
    val startMs: Long,
    val endMs: Long,
    val text: CharSequence
)

/** 非中文内容检测：CJK 占比。字幕文本若几乎无汉字则触发在线搜索 */
object LangDetect {

    /** 判定线：汉字占所有字母数字字符的比例。双语字幕远超这条，纯英文在 0 附近 */
    const val CHINESE_RATIO = 0.15

    fun chineseRatio(text: CharSequence): Double {
        if (text.isBlank()) return 0.0
        var cjk = 0
        var letters = 0
        for (ch in text) {
            if (ch.isLetter()) letters++
            if (ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF) cjk++
        }
        if (letters == 0) return 0.0
        return cjk.toDouble() / letters
    }

    fun mostlyChinese(text: CharSequence): Boolean = chineseRatio(text) > CHINESE_RATIO
}

/**
 * 取一小段正文用于语言判定。
 * 必须用**解析后的条目文本**而不是文件原文：.ass 的 [Script Info]/[Styles] 和
 * `{\an8\fscx110…}` 这类覆盖标签全是拉丁字母，整文件一锅算下来 CJK 占比能被稀释到
 * 判定线以下 —— 中文 ASS 字幕就是这么被判成"非中文"的。
 */
fun List<Cue>.sampleText(maxCues: Int = 200): String =
    take(maxCues).joinToString(" ") { it.text.toString() }
