package com.tvplayer.universal

import com.tvplayer.universal.browse.SidecarMatch
import com.tvplayer.universal.subtitle.ReleaseInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发布名解析 + 外挂字幕文件名匹配的回归测试：这两处是纯字符串逻辑，
 * 真机上看不出来、也不该靠电视复测才发现，所以全部在电脑上跑。
 * 命令行：`./gradlew :app:testDebugUnitTest`
 */
class ReleaseNamingTest {

    /** 真机反馈过的那一对：视频带发布组后缀 `.snr`，字幕再追加语言标记 `.sc` */
    private val video = "《冰与火之歌.权力的游戏》S01E01 1080P 爱奇艺 t.snr.mp4"
    private val subSc = "《冰与火之歌.权力的游戏》S01E01 1080P 爱奇艺 t.snr.sc.srt"

    @Test
    fun sidecarRecognisesRealWorldFileName() {
        assertNotNull("带未知发布组后缀的外挂必须认出来", SidecarMatch.rank(video, subSc))
        assertTrue(SidecarMatch.taggedChinese(subSc))
        assertNull("同目录另一部片子的字幕不算", SidecarMatch.rank(video, "别的片子.S02E03.srt"))
    }

    @Test
    fun parseKeepsSeasonAndEpisode() {
        val info = ReleaseInfo.parse(video)
        assertEquals(1, info.season)
        assertEquals(1, info.episode)
    }

    @Test
    fun platformWatermarkStaysOutOfSearchWords() {
        val info = ReleaseInfo.parse(video)
        assertFalse(info.titleWords.any { it.contains("爱奇艺") })
        assertTrue(info.queries().any { "S01E01" in it })
    }

    @Test
    fun titleMatchesCandidateWrittenDifferently() {
        val info = ReleaseInfo.parse(video)
        assertTrue(info.titleMatches("权力的游戏 S01E01"))
        assertTrue(info.titleMatches("冰与火之歌 权力的游戏 全集"))
        assertFalse(info.titleMatches("绝命毒师 S01E01"))
    }

    @Test
    fun englishReleaseNameParsesClean() {
        val en = ReleaseInfo.parse("Zone.Toe.S02E03.1080p.WEB-DL.DDP5.1.H.264-GRP.mkv")
        assertEquals("Zone Toe", en.title)
        assertEquals(2, en.season)
        assertEquals(3, en.episode)
        val alt = ReleaseInfo.parse("Some.Show.1x09.720p.hdtv.x264.mp4")
        assertEquals(1, alt.season)
        assertEquals(9, alt.episode)
        val movie = ReleaseInfo.parse("Big.Movie.2019.1080p.Bluray.mkv")
        assertEquals(2019, movie.year)
        assertNull("年份不能被当成集数", movie.episode)
    }
}
