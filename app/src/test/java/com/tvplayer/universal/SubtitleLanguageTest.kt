package com.tvplayer.universal

import com.tvplayer.universal.browse.SidecarMatch
import com.tvplayer.universal.subtitle.LangDetect
import com.tvplayer.universal.subtitle.SubtitleParser
import com.tvplayer.universal.subtitle.Cue
import com.tvplayer.universal.subtitle.sampleText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「这条字幕到底是不是中文」的判定。真机上出过一次事：assrt 把一条标着中文的条目
 * 给了自动加载，日志写"在线中文字幕已加载 1089 条"，屏上却是英文 ——
 * 所以中文只能由**解析后的正文**说了算，字幕站的语言标记不行。
 */
class SubtitleLanguageTest {

    private fun cues(vararg lines: String) =
        lines.mapIndexed { i, t -> Cue(i * 1000L, i * 1000L + 900L, t) }

    @Test
    fun englishSubtitleIsNotChineseEvenWhenLong() {
        val sample = cues(
            "What are they saying?",
            "He's using their written language.",
            "Louise: Their language has no punctuation."
        ).sampleText()
        assertFalse(LangDetect.mostlyChinese(sample))
    }

    @Test
    fun bilingualAndChinesePass() {
        assertTrue(LangDetect.mostlyChinese(cues("他们要说什么？").sampleText()))
        assertTrue(
            LangDetect.mostlyChinese(cues("What are they saying? 他们要说什么？").sampleText())
        )
    }

    @Test
    fun emptyOrSymbolOnlyIsNotChinese() {
        assertFalse(LangDetect.mostlyChinese(cues("[music]", "- -").sampleText()))
        assertFalse(LangDetect.mostlyChinese(""))
    }

    /** 判定线本身：占位用的，别让它被悄悄改掉 */
    @Test
    fun thresholdIsFifteenPercent() {
        assertTrue(LangDetect.CHINESE_RATIO > 0.1 && LangDetect.CHINESE_RATIO < 0.2)
    }

    /**
     * 射手那条真机字幕的形状：每条正文都是"中文行\N对照英文行"。
     * 上屏必须只剩中文，否则用户看到的还是英文（真机反馈）。
     */
    @Test
    fun bilingualAssKeepsOnlyTheChineseLine() {
        val ass = """
            [Script Info]
            ScriptType: v4.00+

            [V4+ Styles]
            Format: Name, Fontname, Fontsize, PrimaryColour
            Style: Default,Arial,16,&Hffffff&

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:01:43.51,0:01:45.15,Default,,0,0,0,,它总在出人意料中运作着\NIt doesn't work like I thought it did.
            Dialogue: 0,0:01:47.25,0:01:49.19,Default,,0,0,0,,好了\NOkay.
        """.trimIndent()
        val cues = SubtitleParser.parse("Arrival.2016.chs&eng.ass", ass)
        assertEquals(2, cues.size)
        assertEquals("它总在出人意料中运作着", cues[0].text.toString())
        assertEquals("好了", cues[1].text.toString())
        // 删掉对照行之后，语言判定更要认得出是中文
        assertTrue(LangDetect.mostlyChinese(cues.sampleText()))
    }

    /** 纯英文字幕没有中文行可留，一个字都不该被删 */
    @Test
    fun pureEnglishSubtitleIsUntouched() {
        val srt = """
            1
            00:00:01,000 --> 00:00:02,000
            Memory is a strange thing.

            2
            00:00:03,000 --> 00:00:04,000
            - Ah... - Oh ...
        """.trimIndent()
        val cues = SubtitleParser.parse("Arrival.eng.srt", srt)
        assertEquals(2, cues.size)
        assertEquals("Memory is a strange thing.", cues[0].text.toString())
        assertEquals("- Ah... - Oh ...", cues[1].text.toString())
    }

    /** assrt 一条记录里摆多个文件时靠文件名认中文（pickFile 用的就是这条规则） */
    @Test
    fun chineseTaggedFileNamesWin() {
        assertTrue(SidecarMatch.taggedChinese("Arrival.2016.CHS.ass"))
        assertTrue(SidecarMatch.taggedChinese("Arrival.2016.cht.srt"))
        assertTrue(SidecarMatch.taggedChinese("降临.2016.中文.srt"))
        assertFalse(SidecarMatch.taggedChinese("Arrival.2016.ENG.ass"))
        assertFalse(SidecarMatch.taggedChinese("Arrival.201.ass"))
        assertFalse(SidecarMatch.taggedChinese("Arrival.2016.1080p BluRay x264-DTS.srt"))
    }
}
