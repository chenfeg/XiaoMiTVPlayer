package com.tvplayer.universal.data

import android.content.Context
import android.content.SharedPreferences
import com.tvplayer.universal.BuildConfig

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("tvplayer", Context.MODE_PRIVATE)

    /** assrt v1 接口只需这一个 token（控制台里叫 API Token） */
    var assrtToken: String
        get() = sp.getString("assrt_token", BuildConfig.ASSRT_TOKEN) ?: BuildConfig.ASSRT_TOKEN
        set(v) = sp.edit().putString("assrt_token", v).apply()

    /** 接口地址；默认公共 api.assrt.net，自建/换域名时可改 */
    var assrtBaseUrl: String
        get() = sp.getString("assrt_base", DEFAULT_ASSRT_BASE) ?: DEFAULT_ASSRT_BASE
        set(v) = sp.edit().putString("assrt_base", v).apply()

    /**
     * FakeSub 是按文件指纹匹配的兜底源。默认值曾是公共实例 fake-sub.ifsol.dev，
     * 2026-09-23 在电视和家里路由器上各查一次都是 NXDOMAIN（域名已经不存在了），
     * 所以默认改成留空=不启用，自建实例的人在设置页填回来。
     */
    var fakeSubBaseUrl: String
        get() = sp.getString("fakesub_base", "") ?: ""
        set(v) = sp.edit().putString("fakesub_base", v).apply()

    var openSubBaseUrl: String
        get() = sp.getString("opensub_base", DEFAULT_OPENSUB_BASE) ?: DEFAULT_OPENSUB_BASE
        set(v) = sp.edit().putString("opensub_base", v).apply()

    /** OpenSubtitles 官网给应用申请的 API key；留空 = 这个源完全不发请求 */
    var openSubApiKey: String
        get() = sp.getString("opensub_key", BuildConfig.OPENSUB_KEY) ?: BuildConfig.OPENSUB_KEY
        set(v) = sp.edit().putString("opensub_key", v).apply()

    /** 下载字幕正文要按用户配额发临时直链，所以账号是第二个可选项 */
    var openSubUser: String
        get() = sp.getString("opensub_user", "") ?: ""
        set(v) = sp.edit().putString("opensub_user", v).apply()

    var openSubPass: String
        get() = sp.getString("opensub_pass", "") ?: ""
        set(v) = sp.edit().putString("opensub_pass", v).apply()

    var autoSubtitle: Boolean
        get() = sp.getBoolean("auto_subtitle", true)
        set(v) = sp.edit().putBoolean("auto_subtitle", v).apply()

    /** 音频输出后端：false=Java AudioTrack（ijk 默认），true=native OpenSL ES。电视无声时切换 */
    var audioUseOpenSles: Boolean
        get() = sp.getBoolean("audio_opensles", false)
        set(v) = sp.edit().putBoolean("audio_opensles", v).apply()

    /** 字幕高度占屏高的百分比，遥控器设置里按 1080p 实测调 */
    var subtitleSizePercent: Float
        get() = sp.getFloat("subtitle_size_pct", 4.5f)
        set(v) = sp.edit().putFloat("subtitle_size_pct", v).apply()

    var subtitleDelayMs: Int
        get() = sp.getInt("subtitle_delay_ms", 0)
        set(v) = sp.edit().putInt("subtitle_delay_ms", v).apply()

    var smbHost: String
        get() = sp.getString("smb_host", BuildConfig.SMB_HOST) ?: BuildConfig.SMB_HOST
        set(v) = sp.edit().putString("smb_host", v).apply()

    var smbShare: String
        get() = sp.getString("smb_share", BuildConfig.SMB_SHARE) ?: BuildConfig.SMB_SHARE
        set(v) = sp.edit().putString("smb_share", v).apply()

    var smbUser: String
        get() = sp.getString("smb_user", BuildConfig.SMB_USER) ?: BuildConfig.SMB_USER
        set(v) = sp.edit().putString("smb_user", v).apply()

    var smbPass: String
        get() = sp.getString("smb_pass", BuildConfig.SMB_PASS) ?: BuildConfig.SMB_PASS
        set(v) = sp.edit().putString("smb_pass", v).apply()

    /** 字幕缓存总量上限（MB），保护 eMMC */
    var subtitleCacheLimitMb: Long
        get() = sp.getLong("subtitle_cache_limit", 256L)
        set(v) = sp.edit().putLong("subtitle_cache_limit", v).apply()

    companion object {
        const val DEFAULT_OPENSUB_BASE = "https://api.opensubtitles.com/api/v1"
        const val DEFAULT_ASSRT_BASE = "https://api.assrt.net/v1"
    }
}
