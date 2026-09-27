package com.tvplayer.universal.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvplayer.universal.R
import com.tvplayer.universal.browse.FileSource
import com.tvplayer.universal.browse.LocalSource
import com.tvplayer.universal.browse.MediaItem
import com.tvplayer.universal.browse.SmbSource
import com.tvplayer.universal.browse.SourceKind
import com.tvplayer.universal.data.EventLog
import com.tvplayer.universal.data.NativeLog
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.databinding.ActivityPlayerBinding
import com.tvplayer.universal.player.LocalHttpBridge
import com.tvplayer.universal.player.PlayerEngine
import com.tvplayer.universal.player.SystemCodecs
import com.tvplayer.universal.player.Clock
import com.tvplayer.universal.player.HandlerScheduler
import com.tvplayer.universal.player.SeekController
import com.tvplayer.universal.subtitle.SubtitleMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.danmaku.ijk.media.player.misc.ITrackInfo
import java.io.File
import java.util.concurrent.TimeUnit

class PlayerActivity : AppCompatActivity() {

    private lateinit var b: ActivityPlayerBinding
    private lateinit var prefs: Prefs
    private lateinit var path: String
    private lateinit var videoItem: MediaItem
    private lateinit var source: FileSource
    private val engine = PlayerEngine()

    /** 字幕编排 + 渲染配置，onCreate 里接线（见 SubtitleController） */
    private lateinit var subtitles: SubtitleController

    /** 字幕搜索面板，onCreate 里接线（见 SubtitlePanelController） */
    private lateinit var panel: SubtitlePanelController

    private var bridge: LocalHttpBridge? = null
    private var playbackUrl: String? = null
    private var surfaceReady = false
    private var started = false
    private var hwFallbackDone = false
    private var prepared = false
    private var nativeLogDumped = false
    private var tickJob: Job? = null

    /** 标记 onPause 是否由手动休眠触发（区别于用户主动退出），onResume 据此恢复 */
    @Volatile private var pausedBySleep = false

    /** 播放存活看门狗：监测"应当播放却长时间没有帧推进"的卡死，触发自动恢复 */
    private var livenessJob: Job? = null
    @Volatile private var recovering = false
    /** 用户主动 seek 期间给看门狗的豁免截止时间（uptimeMillis），防止把 seek 误判成卡死 */
    @Volatile private var seekGraceUntil = 0L
    /** 已因卡死自动恢复过的次数，避免无限重建 */
    @Volatile private var autoRecovered = 0

    private val osdHandler = Handler(Looper.getMainLooper())

    /** OSD + 控制条显隐与自动收起，onCreate 里接线（见 OsdController） */
    private lateinit var osd: OsdController

    /**
     * seek 防抖/合并提交状态机。lazy 是因为要引用 sourceTag() 等播放期才就绪的东西，
     * 实际首次使用一定在 onCreate 接线完成之后。
     */
    private val seek: SeekController by lazy {
        SeekController(
            scheduler = HandlerScheduler(osdHandler),
            clock = Clock { SystemClock.uptimeMillis() },
            // commit 在 Handler 线程执行：只能读 tick 刷新的缓存，不能调可能挂起的 native
            currentPosition = { engine.cachedPosition },
            duration = { engine.cachedDuration },
            seekTo = { engine.seekTo(it) },
            sourceTag = { sourceTag() },
            log = { EventLog.line(it) }
        )
    }

    /**
     * 界面已经在退出：ijk 的回调是排到主线程消息队列里来的，onDestroy 之后还会到；
     * 而 onError 里那条"降级软解重试"会新建一台播放器 —— 在已经销毁的 Surface 上
     * 新建播放器、同时又和后台正在跑的 release 抢同一台，正是 native 空指针跳转的形状。
     * 所以所有回调入口先看这个标记。
     */
    @Volatile
    private var destroyed = false
    private val audioPathCheck = Runnable { reportAudioPath() }

    /**
     * "打开后黑屏、一直没响应"是 prepareAsync 既不成功也不报错的那一类：
     * Java 侧收不到任何回调，光看界面永远分不清"还在读"和"卡死了"。
     * 到点就把已知的源信息 + 原生日志落进运行日志，让下一次回报能直接定位。
     */
    private val prepareWatchdog = Runnable { onPrepareTimeout() }

    private fun onPrepareTimeout() {
        if (prepared) return
        EventLog.line("准备超时 ${sourceTag()}：没收到就绪也没收到错误，卡在打开/探测阶段")
        dumpNativeLog("准备超时")
        showOsdPermanent(getString(R.string.player_still_preparing) + "\n${sourceTag()}")
        osdHandler.postDelayed(prepareWatchdog, PREPARE_TIMEOUT_MS)
    }

    /** 原生日志要 exec 子进程，不能在主线程读；同一轮只抓一次，免得刷屏 */
    private fun dumpNativeLog(reason: String) {
        if (nativeLogDumped) return
        nativeLogDumped = true
        lifecycleScope.launch(Dispatchers.IO) {
            val lines = NativeLog.dumpToEventLog(reason)
            val headline = NativeLog.headline(lines) ?: return@launch
            // 直接把 FFmpeg 说的原因贴在出错这一屏上，省得为了看一行日志再跑一趟设置页
            withContext(Dispatchers.Main) {
                b.osdHint.text = "${b.osdHint.text}\n原生原因：$headline"
            }
        }
    }

    private fun armPrepareWatchdog() {
        prepared = false
        nativeLogDumped = false
        osdHandler.removeCallbacks(prepareWatchdog)
        osdHandler.postDelayed(prepareWatchdog, PREPARE_TIMEOUT_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        // 播放时保持屏幕唤醒：电视默认 30 分钟休眠，休眠会挂起网络（SMB 断流）
        // 导致 ijk 读线程永久阻塞、mediacodec 死持、整机冻结（2026-09-27 事故根因）。
        // FLAG_KEEP_SCREEN_ON 只防自动休眠，用户按电源键仍可正常关屏。
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)

        osd = OsdController(
            osd = b.osd,
            controls = b.controls,
            hintView = b.osdHint,
            handler = osdHandler,
            isPlaying = { engine.isPlayingState },
            onShown = { updatePlayIcon() },
            onHide = {
                // 隐藏时把焦点从控制条上摘掉：焦点留在看不见的按钮上的话，
                // 左右键就成了"移动焦点"，而用户要的是直接快退/快进
                parkFocus()
                barFocused = false
            }
        )

        path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val name = intent.getStringExtra(EXTRA_NAME) ?: path.substringAfterLast('/')
        val kind = if (path.startsWith("smb://")) SourceKind.SMB else SourceKind.LOCAL
        videoItem = MediaItem(
            path, name, false,
            if (kind == SourceKind.SMB) 0 else File(path).length(), kind
        )
        source = if (kind == SourceKind.SMB) SmbSource.shared(prefs) else LocalSource(this)
        engine.audioOpenSles = prefs.audioUseOpenSles

        b.osdTitle.text = name

        // 字幕装配：聚合器在此创建一次，编排与面板共用（指纹缓存也随之复用）
        val matcher = SubtitleMatcher(SubtitleMatcher.defaultSources(prefs))
        subtitles = SubtitleController(
            scope = lifecycleScope,
            context = this,
            view = b.subtitles,
            video = videoItem,
            source = source,
            prefs = prefs,
            matcher = matcher,
            onHint = { showOsd(it) }
        )
        panel = SubtitlePanelController(
            scope = lifecycleScope,
            context = this,
            panel = b.searchPanel,
            videoName = name,
            search = { subtitles.searchReport() },
            fetch = { subtitles.fetchAndApply(it) },
            onToggleVisibility = { visible ->
                showOsd(
                    getString(
                        if (visible) R.string.player_subs_on
                        else R.string.player_subs_off
                    )
                )
            },
            onOpened = { barFocused = false },
            onClosed = {
                parkFocus()
                barFocused = false
            }
        )
        panel.setSubtitleView(b.subtitles)

        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnBack.setOnClickListener { skipBy(-SKIP_MS) }
        b.btnForward.setOnClickListener { skipBy(SKIP_MS) }

        b.surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                if (started && !destroyed) {
                    // 休眠唤醒：surface 重建，但旧引擎的 SMB 连接已死，必须整链重建
                    EventLog.line("surface 重建（休眠唤醒），从 ${engine.cachedPosition / 1000}s 断点恢复")
                    val pos = engine.cachedPosition
                    lifecycleScope.launch { recoverFromStall(pos) }
                } else {
                    maybeStart()
                }
            }

            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {
                surfaceReady = false
            }
        })

        engine.setCallback(object : PlayerEngine.Callback {
            override fun onPrepared(durationMs: Long) {
                if (destroyed) return
                prepared = true
                engine.markPlaying()
                osdHandler.removeCallbacks(prepareWatchdog)
                EventLog.line("已就绪 时长=${durationMs / 1000}s ${engine.videoInfo()}")
                b.osdProgress.max = durationMs.toInt().coerceAtLeast(1)
                showOsd(null)
                startTick()
                startLivenessWatchdog()
                // 卡死恢复的重建：就绪后先跳回断点再播
                val resume = resumeAtMs
                if (resume > 0) {
                    resumeAtMs = -1L
                    engine.seekTo(resume)
                }
                subtitles.autoLoad()
                checkAudioPath()
            }

            override fun onError(what: Int, extra: Int): Boolean {
                if (destroyed) return true
                // ijk 把播放器所有错误都塞进 what=-10000（MEDIA_ERROR_IJK_PLAYER），真错误码在 extra。
                // extra=0 只有一个来源：ff_ffplay.c 里 ffp->error = ic->pb->error 那条分支，
                // 意思是"读数据的线程半路断了"，与解码器无关 —— 换软解只会用同一个源再断一次。
                val ioError = what == IJK_PLAYER_ERROR && extra == 0
                val detail = "what=$what extra=$extra(0x${Integer.toHexString(extra)}) " +
                    "硬解=${engine.hardwareDecode} ${engine.videoInfo()} 源=${sourceTag()}"
                EventLog.line("${if (ioError) "读取中断" else "解码失败"} $detail")
                // 回到循环里再动播放器：ijk 这时还在自己的错误路径上，
                // 在回调栈内 release()+新建第二台播放器是崩溃的高嫌疑点。
                osdHandler.post { retryOrReport(detail, ioError) }
                // 错误码只说明"哪一类"失败，FFmpeg 把"为什么"写在自己的原生日志里，
                // 而电视上没有 logcat 通道，只能在失败当场抓一次。
                dumpNativeLog("错误 $what/$extra")
                return true
            }

            override fun onCompleted() {
                if (destroyed) return
                engine.markStopped()
                EventLog.line("播放结束")
                showOsd(null)
            }
        })

        maybeStart()
    }

    /**
     * 出错后的岔路：读取中断（网络/SMB 断流）也尝试断点自动恢复——换解码器改不了数据源，
     * 但重建连接可以。软解重试只留给真正的解码失败。
     */
    private fun retryOrReport(detail: String, ioError: Boolean) {
        if (destroyed || !surfaceReady) {
            EventLog.line("界面已在退出，取消重试 $detail")
            return
        }
        if (ioError) {
            // SMB 网络断流：位置还在，尝试从断点重建数据源恢复播放
            val pos = engine.cachedPosition
            EventLog.line("读取中断，尝试从 ${pos / 1000}s 断点自动恢复 $detail")
            lifecycleScope.launch { recoverFromStall(pos) }
            return
        }
        val size = engine.videoSize()
        val tooBig = size != null && size.first > SOFT_MAX_WIDTH
        if (engine.hardwareDecode && !hwFallbackDone && surfaceReady && !tooBig) {
            hwFallbackDone = true
            EventLog.line("降级软解重试")
            Toast.makeText(this, R.string.player_hw_fallback, Toast.LENGTH_LONG).show()
            playbackUrl?.let {
                armPrepareWatchdog()
                engine.open(b.surface.holder, it, hw = false)
            }
            return
        }
        val why = when {
            tooBig && size != null ->
                getString(R.string.player_error_soft_unsupported, size.first, size.second)
            hwFallbackDone -> getString(R.string.player_error_soft_failed)
            else -> getString(R.string.player_failed)
        }
        EventLog.line("放弃重试 $detail")
        showOsdPermanent("$why\n$detail")
    }

    /**
     * 出问题时最先要分清的两类源：走本地 HTTP 桥的（SMB / content://，Java 侧按 long 偏移读）
     * 和 FFmpeg 自己 open() 的本地文件。大小和文件系统是 >2GB 文件唯一的分水岭。
     * 只写文件名，不写整条 URL —— 运行日志是持久文件。
     */
    private fun sourceTag(): String {
        val u = playbackUrl ?: return "未取到地址"
        if (u.startsWith("http://127.")) return "HTTP桥(${videoItem.kind})"
        return "文件:${u.substringAfterLast('/')} 大小=${videoItem.size}字节 " +
            "文件系统=${LocalSource.fstypeOf(videoItem.path)}"
    }

    private fun maybeStart() {
        if (started || !surfaceReady || destroyed) return
        started = true
        // 硬解失败时第一个要看的证据：这台电视到底会解什么
        EventLog.line("系统解码器 ${SystemCodecs.report()}")
        // 上一次是怎么死的只有崩溃缓冲知道（native 崩溃 Java 侧完全抓不到），趁本次还没写
        // 自己的崩溃行之前抓一次。exec logcat 是子进程 IO，不能放主线程。
        lifecycleScope.launch(Dispatchers.IO) { NativeLog.dumpCrashBuffer() }
        lifecycleScope.launch {
            val url = runCatching { resolveUrl() }.getOrElse { e ->
                Log.e(TAG, "resolve failed", e)
                EventLog.line("取地址失败 ${e.javaClass.simpleName}: ${e.message}")
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.player_no_url), Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            playbackUrl = url
            EventLog.line("打开 硬解=1 源=${sourceTag()}")
            openEngine(url, hw = true)
        }
    }

    /** 装弹 prepare 看门狗并让引擎在当前 surface 上打开（初次起播与卡死恢复共用） */
    private fun openEngine(url: String, hw: Boolean) {
        armPrepareWatchdog()
        engine.open(b.surface.holder, url, hw = hw)
    }

    private suspend fun resolveUrl(): String = when {
        // SMB 与 content://（USB 直连无权限时的媒体库条目）只能按偏移读，统一走本地 HTTP 桥
        videoItem.kind == SourceKind.SMB || videoItem.path.startsWith("content://") -> {
            val br = LocalHttpBridge("application/octet-stream") {
                kotlinx.coroutines.runBlocking { source.open(videoItem) }
            }
            br.start()
            bridge = br
            br.url
        }
        else -> videoItem.path
    }

    private fun startTick() {
        tickJob?.cancel()
        // 整个循环跑在 IO：snapshot() 的 native 访问即使挂起也只冻这一个协程，不影响主线程按键。
        tickJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val snap = engine.snapshot()
                if (snap != null) {
                    // View 与 SeekController 状态机都切回主线程更新
                    withContext(Dispatchers.Main) {
                        if (destroyed) return@withContext
                        b.subtitles.updatePosition(snap.positionMs)
                        // 字幕永远跟着真实播放走；进度条和时间却在 seek 落定前显示目标位置，
                        // 否则连按快进时数字会跳回去（用户反馈的"显示的时间点回退"）。
                        val shown = seek.onTick(snap.positionMs)
                        if (b.osd.visibility == android.view.View.VISIBLE &&
                            !b.osdProgress.isInTouchMode
                        ) {
                            b.osdProgress.progress = shown.toInt()
                            b.osdTime.text = fmt(shown) + " / " + fmt(snap.durationMs)
                            updatePlayIcon()
                        }
                    }
                }
                delay(200)
            }
        }
    }

    /** 唤出 OSD + 控制条并重置自动收起计时，逻辑在 [OsdController] */
    private fun showOsd(hint: String?) = osd.show(hint)

    /**
     * 播放存活看门狗。独立于 tick 跑在 IO：
     * "应当正在播放、非 seek 豁免期，而快照位置连续 [STALL_LIMIT_MS] 没有推进"
     * 即判定硬解/读管线挂死（不报 onError 的那一类，2026-09-26 黑屏事故），触发恢复。
     *
     * 改进（2026-09-27）：SMB 断流时播放位置可能因音频缓冲微幅推进而"假活"，
     * 但 HTTP 桥字节数已停止增长。现在同时检测位置和数据流动，任一停滞即判定卡死。
     */
    private fun startLivenessWatchdog() {
        livenessJob?.cancel()
        livenessJob = lifecycleScope.launch(Dispatchers.IO) {
            var lastPos = -1L
            var frozenSince = 0L
            var lastBytes = -1L
            while (isActive) {
                delay(1000)
                if (destroyed || recovering || !engine.isPlayingState) {
                    lastPos = -1L; frozenSince = 0L; lastBytes = -1L
                    continue
                }
                if (SystemClock.uptimeMillis() < seekGraceUntil) {
                    lastPos = -1L; frozenSince = 0L; lastBytes = -1L
                    continue
                }
                val pos = engine.snapshot()?.positionMs ?: continue
                val bytes = bridge?.bytesSentTotal ?: -1L
                // 位置推进或数据流动都算活：单一指标有盲区（音频缓冲耗着、或位置在动但数据已断）
                val positionAlive = pos != lastPos
                val dataAlive = bytes != lastBytes && bytes >= 0
                if (positionAlive || dataAlive) {
                    lastPos = pos; lastBytes = bytes; frozenSince = 0L
                } else {
                    val now = SystemClock.uptimeMillis()
                    if (frozenSince == 0L) frozenSince = now
                    if (now - frozenSince >= STALL_LIMIT_MS) {
                        frozenSince = 0L; lastPos = -1L; lastBytes = -1L
                        recoverFromStall(pos)
                    }
                }
            }
        }
    }

    /**
     * 卡死恢复：关数据源 → 限时释放旧引擎 → 从断点重建。
     * release 超时（解码器线程挂死）则不再重建，常驻提示用户重启电视——
     * 那时硬解已被死持，再开播放器也播不了，只能重启 mediaserver。
     */
    private suspend fun recoverFromStall(positionMs: Long) {
        if (recovering || destroyed) return
        recovering = true
        EventLog.line("检测到播放卡死：位置 ${positionMs / 1000}s 连续 ${STALL_LIMIT_MS / 1000}s 无推进")
        osdHandler.post { showOsdPermanent(getString(R.string.player_recovering)) }
        // 先掐数据源：断掉桥的 socket 和 SMB 读，让旧引擎的读线程能醒
        bridge?.close()
        bridge = null
        val released = engine.releaseWithTimeout(RELEASE_TIMEOUT_MS)
        if (!released) {
            EventLog.line("卡死恢复失败：解码器未释放，需重启电视")
            osdHandler.post { showOsdPermanent(getString(R.string.player_need_reboot)) }
            recovering = false
            return
        }
        if (autoRecovered >= MAX_AUTO_RECOVER) {
            EventLog.line("已自动恢复 $autoRecovered 次仍卡死，不再重试")
            osdHandler.post { showOsdPermanent(getString(R.string.player_need_reboot)) }
            recovering = false
            return
        }
        autoRecovered++
        // 重建数据源（SMB 重新挂桥；本地文件直接用路径）
        val newUrl = runCatching { resolveUrl() }.getOrElse {
            EventLog.line("卡死恢复重建数据源失败 ${it.javaClass.simpleName}")
            osdHandler.post { showOsdPermanent(getString(R.string.player_need_reboot)) }
            recovering = false
            return
        }
        resumeAtMs = positionMs
        playbackUrl = newUrl
        EventLog.line("从 ${positionMs / 1000}s 断点自动恢复播放（第 $autoRecovered 次）")
        osdHandler.post { openEngine(newUrl, hw = engine.hardwareDecode) }
        recovering = false
    }

    /** 卡死恢复后在 onPrepared 里 seek 回的断点；-1 = 不 seek */
    @Volatile private var resumeAtMs = -1L

    /**
     * 图标显示"当前是什么状态"，不是"按下去会干什么"：
     * 暂停中显示暂停符（两条竖线），播放中显示播放符。这是用户指定的口径。
     * 状态可能在 Java 侧看不见的地方变（自动暂停、出错、加载完才真正开跑），
     * 所以 tick 里也调它；相同图标不重设，省得每 200ms 重新加载一次 drawable。
     */
    private var playIconRes = 0
    private fun updatePlayIcon() {
        // 用缓存的播放状态，不在主线程问 native isPlaying()
        val res = if (engine.isPlayingState) R.drawable.ic_play else R.drawable.ic_pause
        if (res == playIconRes) return
        playIconRes = res
        b.btnPlay.setImageResource(res)
    }

    private fun togglePlay() {
        val playing = engine.togglePause()
        showOsd(if (playing) null else getString(R.string.player_paused))
    }

    /** 遥控器方向键 / 屏幕按钮的快退快进：状态机在 [SeekController]，这里顺带唤出 OSD */
    private fun skipBy(deltaMs: Long) {
        // seek 提交(防抖400ms)+落定(上限15s)期间位置会短暂停顿，给看门狗豁免，别误判成卡死
        seekGraceUntil = SystemClock.uptimeMillis() + SEEK_GRACE_MS
        seek.skip(deltaMs)
        showOsd(null)
    }

    /**
     * 用户**主动**用下键进控制条的标记。左右键到底是"换焦点"还是"快退快进"只看这一个标记，
     * 不只看 `controls.hasFocus()` —— 真机上框架会自己把焦点停在播放键上（关面板、窗口重新
     * 拿回焦点都会），那样用户没按过下键左右键也不再快进，正是用户反馈的"焦点一直卡在控制按键、
     * 上下键移不开"。两个条件一起用：标记说要换焦点，且焦点确实还在控制条上，才交给系统。
     */
    private var barFocused = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        fun repeatSkip(): Long = if (event.repeatCount > 6) 60_000 else 10_000
        // 控制条收起来之后，任何按键都先把它唤回
        if (!osd.isVisible) showOsd(null)
        val onBar = barFocused && b.controls.hasFocus()
        fun passThrough(): Boolean = super.onKeyDown(keyCode, event)
        // 字幕面板开着的时候，上下/确认必须交给列表自己走焦点：在这儿把它们截走就成了真机
        // 反馈的"按上下焦点跑到播放键上、面板还挂着，换不了字幕"。
        // 但搜索回来的时序会偶发让焦点没送进列表（停在根布局）：先把焦点救回列表再放行，
        // 否则这几个键落在根布局上什么都不发生。面板只靠菜单键（再按一次检索键）关闭。
        if (panel.isOpen &&
            (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            if (!panel.hasFocus) {
                panel.requestListFocus()
                return true
            }
            return passThrough()
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER ->
                if (onBar) return passThrough() else {
                    if (event.repeatCount > 8) cycleSpeed() else togglePlay()
                    return true
                }
            // 焦点不在控制条上：左右键**永远**是快退/快进（用户明确要求保留这条捷径）。
            // 已经按下键走进控制条了：左右改成换焦点，否则那三个按钮永远到不了（真机反馈）。
            KeyEvent.KEYCODE_DPAD_LEFT -> if (onBar) return passThrough() else {
                skipBy(-repeatSkip()); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (onBar) return passThrough() else {
                skipBy(repeatSkip()); return true
            }
            // 下键进控制条（落在中间的播放/暂停上），上键出来
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                barFocused = true
                b.btnPlay.requestFocus(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                barFocused = false
                parkFocus(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (event.repeatCount > 8) {
                    cycleSpeed()
                } else {
                    togglePlay()
                }
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                panel.toggle()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> {
                cycleAudioTrack(); return true
            }
            KeyEvent.KEYCODE_INFO -> {
                // 无声/无声轨时用户能自己把音频链路体检调出来看；audioReport 是 native，放 IO
                showOsd(getString(R.string.player_audio_querying))
                lifecycleScope.launch(Dispatchers.IO) {
                    val report = engine.audioReport()
                    osdHandler.post { showOsdPermanent(report) }
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { skipBy(60_000); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { skipBy(-60_000); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    private val speeds = floatArrayOf(1.0f, 1.25f, 1.5f, 0.75f)
    private var speedPtr = 0

    private fun cycleSpeed() {
        speedPtr = (speedPtr + 1) % speeds.size
        engine.setSpeed(speeds[speedPtr])
        showOsd(getString(R.string.player_speed, speeds[speedPtr].toString()))
    }

    private fun cycleAudioTrack() {
        // tracks/selectTrack 都是 native：整个切换在 IO 做，主线程只显示结果
        lifecycleScope.launch(Dispatchers.IO) {
            val audio = engine.tracks().withIndex()
                .filter { it.value.trackType == ITrackInfo.MEDIA_TRACK_TYPE_AUDIO }
                .map { it.index }
            if (audio.size < 2) {
                osdHandler.post { showOsd(getString(R.string.player_audio_single)) }
                return@launch
            }
            val current = engine.selectedAudioIndex()
            val nextPos = (audio.indexOf(current) + 1).coerceAtLeast(0) % audio.size
            engine.switchAudioTrack(to = audio[nextPos], from = current)
            osdHandler.post { showOsd(getString(R.string.player_audio_track, nextPos + 1, audio.size)) }
        }
    }

    /**
     * 无声问题的现场判定：音轨没选中 / 一条音轨都没有 / 解码没产出数据，
     * 这三种都会在屏幕上留证据；数据到位却仍无声 = 输出后端问题，去设置页换 OpenSL ES。
     * 延时挂在自己的 Handler 上（不是 View 的），退出界面时能一并撤掉。
     */
    private fun checkAudioPath() = osdHandler.postDelayed(audioPathCheck, 3000)

    private fun reportAudioPath() {
        if (destroyed) return
        // 音频体检要读 tracks（native）：放 IO，避免在主线程被挂起
        lifecycleScope.launch(Dispatchers.IO) {
            val report = engine.audioReport()
            EventLog.line("音频体检 $report")
            val silent = engine.selectedAudioIndex() < 0 ||
                engine.player?.audioCachedPackets == 0L
            if (silent) osdHandler.post { showOsdPermanent(report) }
        }
    }

    /** 诊断信息不能被自动隐藏掉 */
    private fun showOsdPermanent(hint: String) = osd.showPermanent(hint)

    /**
     * 把焦点收回到根布局（它自己 focusable、又不画焦点样式）。
     * 不用 clearFocus()：用户反馈"按 ↑ 移出控制条后快退键还是着重色"，而真正把焦点
     * 交给另一个 View 是一次正常的焦点转移，state_focused 一定会翻过来。
     * OSD 隐藏和面板关闭都要用，留在 Activity 作为共享工具。
     */
    private fun parkFocus() {
        if (b.controls.hasFocus() || panel.isOpen) {
            b.root.requestFocus()
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 设计：字幕面板只由"再按一次字幕检索键（菜单键）"关闭，返回键在面板打开时不关闭它
        if (panel.isOpen) return
        super.onBackPressed()
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "00:00:00"
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    override fun onPause() {
        super.onPause()
        pausedBySleep = true
        // 用缓存状态判断，不读 native
        if (engine.isPlayingState) engine.togglePause()
    }

    override fun onResume() {
        super.onResume()
        if (!pausedBySleep || destroyed || !started) return
        pausedBySleep = false
        // 手动休眠唤醒：surface 可能没重建，但 SMB 连接已被系统挂起，
        // 旧引擎的读线程已死，必须整链重建才能恢复画面。
        EventLog.line("休眠唤醒，从 ${engine.cachedPosition / 1000}s 断点恢复")
        val pos = engine.cachedPosition
        lifecycleScope.launch { recoverFromStall(pos) }
    }

    override fun onDestroy() {
        EventLog.line("退出播放 位置=${engine.cachedPosition / 1000}s")
        destroyed = true
        tickJob?.cancel()
        livenessJob?.cancel()
        // 排队的重试/体检/自动隐藏全部撤掉：留在那儿的就是"退出之后还去开第二台播放器"的入口
        osdHandler.removeCallbacksAndMessages(null)
        // 先关数据源、后放播放器：ijk 的 release 要 join 读线程，读线程可能正卡在
        // 本地 HTTP 桥那条 socket 上等字节。桥不先关，这个 join 就回不来。
        bridge?.close()
        engine.release()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_NAME = "name"
        private const val TAG = "PlayerActivity"

        /**
         * 不做软解重试的宽度门槛。设计判断（未在这台机器上实测过 4K 软解）：
         * 4K 参考帧要几百 MB，1.4GHz 四核 + 2GB 即使不崩也解不到实时的零头，
         * 再开一次播放器只会把错误现场搞成"两个源的日志混在一起"。
         */
        private const val SOFT_MAX_WIDTH = 1920

        /** ijk 的 MEDIA_ERROR_IJK_PLAYER：所有播放器错误的统一 what，真错误码在 extra */
        private const val IJK_PLAYER_ERROR = -10000

        /** 超过这个时间还没收到 onPrepared/onError 就把现场记下来（只记录，不杀播放器） */
        private const val PREPARE_TIMEOUT_MS = 20_000L

        /** 存活看门狗："应当播放却连续多久无位置推进"判定为卡死 */
        private const val STALL_LIMIT_MS = 10_000L

        /** 卡死恢复时等待旧引擎 release 的上限；超时说明解码器挂死，只能重启电视 */
        private const val RELEASE_TIMEOUT_MS = 5_000L

        /** 用户 seek 后给看门狗的豁免时长（覆盖提交400ms+落定上限15s） */
        private const val SEEK_GRACE_MS = 16_000L

        /** 一次播放内最多自动恢复次数，超过则提示重启，避免无限重建 */
        private const val MAX_AUTO_RECOVER = 2

        /** 屏幕上的快进/快退按钮一次跳多少 */
        private const val SKIP_MS = 10_000L
    }
}
