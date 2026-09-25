package com.tvplayer.universal.player

import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import com.tvplayer.universal.data.EventLog
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.misc.ITrackInfo
import kotlin.concurrent.thread

/**
 * ijkplayer 封装。解码策略（针对 Cortex-A17 + 2GB RAM）：
 *  - 首选 MediaCodec 硬解（mediacodec-all-videos）；
 *  - onError 时关闭硬解整机重建，FFmpeg 软解兜底 —— 4K HEVC 软解必然掉帧，
 *    但 1080p 以下的 RMVB/DivX/XviD 等老格式只有这条路；
 *  - AC3/DTS 这类系统解码器不支持的音频走 ijk 自带的 FFmpeg 软解：native 层是
 *    scripts/build-ijk.sh 自编译的完整版（425 个解码器），不是 Maven 上那个只有
 *    AAC/MP3/FLAC/H264 的白名单版；真机"有画面无声音"仍按 INFO 看音轨编码，
 *    解码器在而音频流没打开时嫌疑落在输出后端（设置页可切 OpenSL ES）。
 */
class PlayerEngine {

    interface Callback {
        fun onPrepared(durationMs: Long)
        /** 返回 true 表示已内部处理（如降级软解） */
        fun onError(what: Int, extra: Int): Boolean
        fun onCompleted()
        fun onInfo(what: Int, extra: Int) {}
    }

    @Volatile
    var player: IjkMediaPlayer? = null
        private set

    var hardwareDecode = true
        private set

    /** 无声时切换音频输出后端：默认走 Java AudioTrack，置真则走 native OpenSL ES */
    var audioOpenSles = false

    private var callback: Callback? = null

    fun setCallback(cb: Callback) {
        callback = cb
    }

    fun open(surface: SurfaceHolder?, path: String, hw: Boolean = hardwareDecode) {
        releaseSync()
        hardwareDecode = hw
        val p = IjkMediaPlayer()
        player = p
        try {
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-all-videos", if (hw) 1 else 0)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", if (hw) 1 else 0)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "framedrop", 5)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "soundtouch", 1)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "start-on-prepared", 1)
            // 快进后"位置又退回去"的另一半原因：默认 seek 落在目标之前的那个关键帧上，
            // 开精确 seek 后内核从关键帧解码到目标时间点再输出，落点才和屏上显示的一致。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "enable-accurate-seek", 1)
            if (audioOpenSles) {
                // 有些盒子/电视的 AudioTrack 建不出来（ijk 只会打一行 native 日志然后哑掉），换 OpenSL ES
                p.setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 1)
            }
            // 探测窗口取 FFmpeg 默认值（probesize/analyzeduration 5MB/5s）的 1.6 倍：
            // mkv/m2ts 头部塞大段字体附件或长 chapter 时，探测提前收尾会漏报音频流。
            // 注意这只影响"流有没有被发现"；发现了解码不了是 so 的编译范围问题。
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 8000000)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 8000000)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzemaxduration", 8000L)
            p.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist", "file,http,https,tcp,tls,crypto")
        } catch (e: Exception) {
            Log.w(TAG, "option set failed (old ijk build?)", e)
            EventLog.line("播放器参数设置失败 ${e.javaClass.simpleName}: ${e.message}")
        }

        p.setOnPreparedListener { mp -> callback?.onPrepared(mp.duration) }
        p.setOnCompletionListener { callback?.onCompleted() }
        p.setOnErrorListener { _, what, extra -> callback?.onError(what, extra) ?: false }
        p.setOnInfoListener { _, what, extra -> callback?.onInfo(what, extra); false }

        if (surface != null) p.setDisplay(surface)
        try {
            p.setDataSource(path)
            p.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "prepare failed", e)
            callback?.onError(-1, 0)
        }
    }

    val duration: Long get() = try { player?.duration ?: 0 } catch (e: Exception) { 0 }
    val currentPosition: Long get() = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    val isPlaying: Boolean get() = player?.isPlaying == true

    fun togglePause(): Boolean {
        val p = player ?: return false
        return if (p.isPlaying) {
            p.pause(); false
        } else {
            p.start(); true
        }
    }

    fun seekTo(ms: Long) {
        runCatching { player?.seekTo(ms.coerceIn(0, duration)) }
    }

    fun setSpeed(speed: Float) {
        try {
            player?.setSpeed(speed)
        } catch (e: Throwable) {
            Log.w(TAG, "setSpeed unsupported", e)
        }
    }

    fun tracks(): List<ITrackInfo> =
        try {
            player?.trackInfo?.toList() ?: emptyList()
        } catch (e: Throwable) {
            emptyList()
        }

    /** 当前选中的音频流索引；-1 = 没选中任何音轨（播放必然无声） */
    fun selectedAudioIndex(): Int = runCatching {
        player?.getSelectedTrack(ITrackInfo.MEDIA_TRACK_TYPE_AUDIO) ?: -1
    }.getOrDefault(-1)

    /** 换音轨：ijk 的 selectTrack 是"追加选中"，必须先把旧的取消，否则两路解码打架 */
    fun switchAudioTrack(to: Int, from: Int) {
        runCatching { if (from >= 0 && from != to) player?.deselectTrack(from) }
        runCatching { player?.selectTrack(to) }
    }

    /** 视频轨的 "编码,宽x高"，取自 IjkTrackInfo.getInfoInline（VIDEO 分支带 resolution） */
    fun videoInfo(): String =
        tracks().firstOrNull { it.trackType == ITrackInfo.MEDIA_TRACK_TYPE_VIDEO }?.infoInline ?: ""

    /** 无法解析出分辨率时返回 null */
    fun videoSize(): Pair<Int, Int>? {
        val m = RES_PATTERN.find(videoInfo()) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        return w to h
    }

    /**
     * 音频链路体检，单行显示在 OSD 上。没有 logcat 通道时，这就是判断
     * "文件没音轨 / 音轨没选中 / 解码没出数据 / 数据到位但仍无声"的依据。
     */
    fun audioReport(): String {
        val p = player ?: return "播放器未就绪"
        val audio = tracks().filter { it.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO }
        val names = audio.joinToString("; ") { it.infoInline ?: "?" }
        val selected = selectedAudioIndex()
        return "音轨 ${audio.size} 条，选中=$selected，" +
            "已缓冲音频=${p.audioCachedDuration}ms/${p.audioCachedPackets}包，" +
            "session=${p.audioSessionId}，输出=${if (audioOpenSles) "OpenSL ES" else "AudioTrack"}" +
            (if (names.isNotEmpty()) " ｜ $names" else "") +
            (if (audio.isNotEmpty() && selected < 0) diagnoseSilence() else "")
    }

    /** 无声原因的二分：内核是自带全量解码器的完整版（见 scripts/build-ijk.sh），
     *  能报出音频轨就说明流认得、解码器在，剩下的嫌疑只有输出后端。 */
    private fun diagnoseSilence(): String {
        val codec = tracks()
            .firstOrNull { it.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO }
            ?.infoInline?.split(',')?.getOrNull(1)?.trim()
            ?: return ""
        return " ｜ 内核有 $codec 解码器但音频流没打开，去设置页勾选 OpenSL ES 再播"
    }

    /** 同步释放。只用于"同一个界面里换一台播放器"（硬解→软解重试）：
     *  那时这台播放器是我们自己刚用过的，join 很快，而且绝不能让新旧两台抢同一个 Surface。 */
    private fun releaseSync() {
        val p = player ?: return
        player = null
        runCatching {
            p.reset()
            p.release()
        }.onFailure { Log.w(TAG, "release", it) }
    }

    /**
     * 退出界面时走这里：把 release 交给后台线程，主线程绝不 join。
     * ijk 的 release 路径是 ijkmp_shutdown → ffp_stop/ffp_wait_stop → stream_close，
     * 那里对读线程和刷帧线程做的是阻塞式 SDL_WaitThread（ff_ffplay.c 里先打一行
     * "wait for read_tid" 再等）。读线程卡在没完没了的阻塞读上时这个 join 永不返回，
     * 放主线程就是整个应用连列表页一起冻住、只能杀进程。
     * 耗时超过 2 秒写进运行日志：这条记录本身就是"卡在哪个 join"的证据。
     */
    fun release() {
        val p = player ?: return
        player = null
        thread(name = "ijk-release", isDaemon = true) {
            val t0 = SystemClock.uptimeMillis()
            runCatching {
                p.reset()
                p.release()
            }.onFailure { Log.w(TAG, "release", it) }
            val cost = SystemClock.uptimeMillis() - t0
            if (cost > 2000) EventLog.line("播放器 release 用了 ${cost}ms：读线程没及时退出")
        }
    }

    companion object {
        private const val TAG = "PlayerEngine"
        private val RES_PATTERN = Regex("""(\d{2,5})\s*x\s*(\d{2,5})""")
    }
}
