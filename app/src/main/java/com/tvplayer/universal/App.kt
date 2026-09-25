package com.tvplayer.universal

import android.app.Application
import com.tvplayer.universal.data.EventLog
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.subtitle.SubtitleCache

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        context = this
        EventLog.init(this)
        // 8GB eMMC 很紧张：启动时把字幕缓存压回配额内
        SubtitleCache.purgeToFit(this, Prefs(this).subtitleCacheLimitMb)
    }

    companion object {
        /** 给拿不到 Activity 的底层模块（字幕 TLS 锚点要读 assets）用 */
        lateinit var context: Application
            private set
    }
}
