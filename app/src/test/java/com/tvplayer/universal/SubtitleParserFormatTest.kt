package com.tvplayer.universal

import com.tvplayer.universal.subtitle.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 钉住真机上暴露的那个问题：在线字幕落盘后文件名是 `<sha1>.utf8`（SubtitleCache 统一转码），
 * 解析器按扩展名分派，`.utf8` 落到 SRT 分支，于是 ASS 内容解析出 0 条，
 * 运行日志只留下一句"下载的字幕解析不出条目"。
 */
class SubtitleParserFormatTest {

    private val ass = """
        [Script Info]
        Title: Arrival
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour
        Style: Default,Arial,20

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:12.50,0:00:15.20,Default,,0,0,0,,我们来了
        Dialogue: 0,0:01:02.00,0:01:05.00,Default,,0,0,0,,他们在听
    """.trimIndent()

    private val srt = """
        1
        00:00:12,500 --> 00:00:15,200
        我们来了

        2
        00:01:02,000 --> 00:01:05,000
        他们在听
    """.trimIndent()

    private val vtt = "WEBVTT\n\n00:00:12.500 --> 00:00:15.200\n我们来了\n"

    @Test
    fun `缓存文件名的 utf8 后缀不能决定字幕格式`() {
        for (name in listOf("35a831ec.utf8", "35a831ec", "35a831ec.bin")) {
            assertEquals("$name 应认出 ASS 的两条对白", 2, SubtitleParser.parse(name, ass).size)
            assertEquals("$name 应认出 SRT 的两条对白", 2, SubtitleParser.parse(name, srt).size)
        }
    }

    @Test
    fun `显式扩展名照旧生效`() {
        assertEquals(2, SubtitleParser.parse("a.ass", ass).size)
        assertEquals(2, SubtitleParser.parse("a.srt", srt).size)
        assertEquals(1, SubtitleParser.parse("a.vtt", vtt).size)
    }

    @Test
    fun `解析出的时间轴与文本正确`() {
        val cues = SubtitleParser.parse("x.utf8", ass)
        assertEquals(12_500L, cues[0].startMs)
        assertEquals("我们来了", cues[0].text.toString())
        val srtCues = SubtitleParser.parse("x.utf8", srt)
        assertEquals(62_000L, srtCues[1].startMs)
        assertTrue(srtCues[1].endMs > srtCues[1].startMs)
    }
}
