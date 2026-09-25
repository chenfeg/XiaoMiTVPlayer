package com.tvplayer.universal.subtitle

import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import java.util.Locale

/**
 * SRT/VTT 与 ASS/SSA 的轻量解析。
 * 不集成 libass：省 5MB+ so 与数 MB 运行时内存，代价是放弃 ASS 复杂特效——
 * 在线中文简繁字幕 95% 是 SRT 或仅含颜色/加粗的朴素 ASS，此处足以。
 */
object SubtitleParser {

    /**
     * 格式判定不能只看文件名：在线字幕落盘时统一转成 UTF-8，文件名是 `<sha1>.utf8`
     * （SubtitleCache.put），按扩展名分派就等于把所有在线字幕都当 SRT 读，
     * 而 assrt 的中文条目相当一部分是 ASS —— 时间轴写法不同，SRT 解析器一条都对不上，
     * 于是"下载成功却零条字幕"。所以扩展名不可信时看内容，两边都试一次。
     */
    fun parse(fileName: String, content: String): List<Cue> {
        val cues = when (fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "ass", "ssa" -> AssParser.parse(content)
            "srt", "vtt", "sub" -> SrtParser.parse(content)
            else -> if (looksLikeAss(content)) AssParser.parse(content) else SrtParser.parse(content)
        }
        val retried = when {
            cues.isNotEmpty() -> cues
            looksLikeAss(content) -> AssParser.parse(content)
            else -> SrtParser.parse(content)
        }
        return retried.sortedBy { it.startMs }.map { it.withoutForeignLines() }
    }

    /**
     * 双语字幕只留中文那一行。真机证据：射手那条 Arrival 中文字幕的每条正文都是
     * `好了\NOkay.` 这种"中文行 + 对照英文行"（1089 条里中文占 26%），
     * 原样上屏就是用户报的"字幕是英文的"。
     *
     * 判定单位是**行**：整条里有中文行时，不含汉字的行就是对照翻译；
     * 纯英文字幕没有中文行可留，一个字都不会被删。
     * 只在真要删行时才重建文本 —— 否则 SpannableStringBuilder 上的颜色会丢。
     */
    private fun Cue.withoutForeignLines(): Cue {
        val lines = text.toString().split('\n')
        if (lines.size < 2) return this
        val kept = lines.filter { line -> line.any { it.code in 0x4E00..0x9FFF } }
        if (kept.isEmpty() || kept.size == lines.size) return this
        return copy(text = kept.joinToString("\n").trim())
    }

    private fun looksLikeAss(content: String): Boolean {
        val head = content.take(4096)
        return head.contains("[Script Info]", true) || head.contains("[V4+ Styles]", true) ||
            head.contains("[V4 Styles]", true) ||
            Regex("(?m)^\\s*Dialogue\\s*:").containsMatchIn(head)
    }
}

object SrtParser {
    private val TIME =
        Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    fun parse(content: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        var startMs = -1L
        var endMs = -1L
        val text = StringBuilder()

        fun flush() {
            if (startMs >= 0 && text.isNotBlank()) {
                cues += Cue(startMs, endMs, TagStripper.strip(text.toString()))
            }
            startMs = -1; endMs = -1; text.setLength(0)
        }

        for (raw in content.lineSequence()) {
            val line = raw.trimEnd('\r')
            val m = TIME.find(line)
            if (m != null) {
                flush()
                val g = m.groupValues
                startMs = ts(g[1], g[2], g[3], g[4])
                endMs = ts(g[5], g[6], g[7], g[8])
            } else if (line.isBlank()) {
                flush()
            } else if (startMs >= 0) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(line)
            }
        }
        flush()
        return cues
    }

    private fun ts(h: String, m: String, s: String, ms: String): Long =
        h.toLong() * 3600000 + m.toLong() * 60000 + s.toLong() * 1000 +
            ms.padEnd(3, '0').toLong()
}

/** SRT 内联标签统一剥离（<b>/<i>/<font> 在 TV 场景差异不可见，保文本即可） */
object TagStripper {
    fun strip(text: String): CharSequence = text
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("""</?font[^>]*>""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""</?(?:i|b|u|s|p)\s*>""", RegexOption.IGNORE_CASE), "")
        .trim()
}

object AssParser {
    private val TIME = Regex("""(\d+):(\d{2}):(\d{2})\.(\d{2,3})""")

    fun parse(content: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        var formatCols: List<String> = emptyList()
        var inEvents = false
        var inStyles = false
        val styleColors = HashMap<String, Int>()
        var defaultColor = 0xFFFFFFFF.toInt()

        val lines = content.lineSequence()
        for (raw in lines) {
            val line = raw.trimEnd('\r')
            when {
                line.equals("[Events]", true) -> { inEvents = true; inStyles = false }
                line.equals("[Styles]", true) -> { inStyles = true; inEvents = false }
                line.startsWith("[") -> { inEvents = false; inStyles = false }
                inEvents && line.startsWith("Format:", true) -> {
                    formatCols = line.substringAfter(':').split(',').map { it.trim().lowercase() }
                }
                inEvents && line.startsWith("Dialogue:", true) -> {
                    val values = line.substringAfter(':').split(',', limit = formatCols.size.coerceAtLeast(9))
                    if (formatCols.isEmpty() || values.size < 9) continue
                    val st = timeOf(values.getc(formatCols, "start")) ?: continue
                    val en = timeOf(values.getc(formatCols, "end")) ?: continue
                    val style = values.getc(formatCols, "style")
                    val text = values.getc(formatCols, "text")
                    cues += Cue(st, en, render(text, styleColors[style] ?: defaultColor))
                }
                inStyles && line.startsWith("Style:", true) -> {
                    // Name, Fontname, Fontsize, PrimaryColour, ...
                    val parts = line.substringAfter(':').split(',').map { it.trim() }
                    if (parts.size >= 4) {
                        parseAssColor(parts[3])?.let { styleColors[parts[0]] = it }
                    }
                }
            }
        }
        return cues
    }

    private fun List<String>.getc(cols: List<String>, key: String): String {
        val i = cols.indexOf(key)
        return if (i >= 0 && i < size) this[i] else ""
    }

    private fun timeOf(t: String): Long? =
        TIME.find(t)?.groupValues?.let {
            it[1].toLong() * 3600000 + it[2].toLong() * 60000 + it[3].toLong() * 1000 +
                it[4].padEnd(3, '0').toLong()
        }

    /** &HBBGGRR& / &HAABBGGRR& （ASS 存 BGR，Android 需要 ARGB int） */
    private fun parseAssColor(raw: String): Int? {
        val hex = Regex("&H([0-9a-fA-F]{6,8})&").find(raw)?.groupValues?.get(1) ?: return null
        val bgr = hex.substring(maxOf(0, hex.length - 6))
        val b = bgr.substring(0, 2).toInt(16)
        val g = bgr.substring(2, 4).toInt(16)
        val r = bgr.substring(4, 6).toInt(16)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 内联覆盖标签处理：{\c&H..&} 上色、\b1 加粗、{\an..} 对齐忽略（固定底部） */
    fun render(text: String, defaultColor: Int): CharSequence {
        val overrides = Regex("""\{\\([^}]*)\}""").findAll(text).toList()
        // 绝大多数行既没有覆盖标签、颜色就是默认白：直接给字符串就够，
        // 真机上少一次 Spannable 分配，测试里也不用伪造 android.text
        if (overrides.isEmpty() && defaultColor == 0xFFFFFFFF.toInt()) {
            return text.replace("\\N", "\n").replace("\\n", "\n")
        }
        val out = SpannableStringBuilder()
        var currentColor = defaultColor
        var bold = false
        var lastEnd = 0
        fun applySeg(seg: String) {
            if (seg.isEmpty()) return
            val st = out.length
            out.append(seg.replace("\\N", "\n").replace("\\n", "\n"))
            out.setSpan(
                ForegroundColorSpan(currentColor), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            if (bold) out.setSpan(StyleSpan(Typeface.BOLD), st, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        for (ov in overrides) {
            applySeg(text.substring(lastEnd, ov.range.first))
            val body = ov.groupValues[1]
            Regex("""\c&H([0-9a-fA-F]{6,8})&""").find(body)?.let {
                parseAssColor(it.value)?.let { c -> currentColor = c }
            }
            when {
                Regex("""\b1(\D|$)""").containsMatchIn(body) -> bold = true
                Regex("""\b0(\D|$)""").containsMatchIn(body) -> bold = false
            }
            lastEnd = ov.range.last + 1
        }
        applySeg(text.substring(lastEnd))
        return out
    }
}
