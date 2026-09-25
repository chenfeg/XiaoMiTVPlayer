package com.tvplayer.universal.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tvplayer.universal.BuildConfig
import com.tvplayer.universal.R
import com.tvplayer.universal.browse.SmbSource
import com.tvplayer.universal.browse.StorageProbe
import com.tvplayer.universal.data.EventLog
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.databinding.ActivitySettingsBinding
import com.tvplayer.universal.subtitle.SubtitleCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.assrtToken.setText(prefs.assrtToken)
        b.assrtBase.setText(prefs.assrtBaseUrl)
        b.fakesubBase.setText(prefs.fakeSubBaseUrl)
        b.opensubKey.setText(prefs.openSubApiKey)
        b.opensubUser.setText(prefs.openSubUser)
        b.opensubPass.setText(prefs.openSubPass)
        b.opensubBase.setText(prefs.openSubBaseUrl)
        b.autoSubtitle.isChecked = prefs.autoSubtitle
        b.audioOpensles.isChecked = prefs.audioUseOpenSles
        b.subtitleSize.setText(prefs.subtitleSizePercent.toString())
        b.subtitleDelay.setText(prefs.subtitleDelayMs.toString())
        b.smbHost.setText(prefs.smbHost)
        b.smbShare.setText(prefs.smbShare)
        b.smbUser.setText(prefs.smbUser)
        b.smbPass.setText(prefs.smbPass)
        b.smbGroup.visibility = if (BuildConfig.ENABLE_SMB) View.VISIBLE else View.GONE

        b.btnSave.setOnClickListener { save() }
        b.btnClearCache.setOnClickListener {
            SubtitleCache.clear(this)
            Toast.makeText(this, R.string.settings_cache_cleared, Toast.LENGTH_SHORT).show()
        }
        b.btnProbe.setOnClickListener {
            b.btnProbe.isEnabled = false
            b.probeText.setText(R.string.settings_probe_running)
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    val smbPart = if (BuildConfig.ENABLE_SMB) "\n" + SmbSource.shared(prefs).diagnose() else ""
                    StorageProbe.report(this@SettingsActivity) + smbPart
                }
                b.probeText.text = text
                b.btnProbe.isEnabled = true
            }
        }
        b.btnBeep.setOnClickListener { beepSelfTest() }
        // 最新的一条在最上面：遥控器翻页比现场复现一次崩溃便宜
        b.btnLog.setOnClickListener { b.probeText.text = EventLog.newestFirst(80) }
    }

    /**
     * 用系统提示音（不经 ijkplayer）验证电视的声音通路：
     * 听得见 → 无声是播放器/编码问题；听不见 → 是电视音量或数字输出设置，与本应用无关。
     */
    private fun beepSelfTest() {
        Toast.makeText(this, R.string.settings_beep_hint, Toast.LENGTH_LONG).show()
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 100) }.getOrNull()
            ?: run { Toast.makeText(this, "本机不支持提示音", Toast.LENGTH_SHORT).show(); return }
        fun ping(code: Int, delayMs: Long) = b.root.postDelayed(
            { runCatching { tone.startTone(code, 350) } }, delayMs
        )
        ping(ToneGenerator.TONE_PROP_BEEP, 400)
        ping(ToneGenerator.TONE_PROP_BEEP, 1200)
        b.root.postDelayed({
            runCatching { tone.startTone(ToneGenerator.TONE_PROP_ACK, 500) }
            b.root.postDelayed({ runCatching { tone.release() } }, 600)
        }, 2000)
    }

    private fun save() {
        // 电视上输入法难用：留空视为不修改，避免误清空后字幕源失效
        b.assrtToken.text.toString().trim().takeIf { it.isNotBlank() }?.let { prefs.assrtToken = it }
        b.assrtBase.text.toString().trim().takeIf { it.isNotBlank() }?.let { prefs.assrtBaseUrl = it }
        b.fakesubBase.text.toString().trim().takeIf { it.isNotBlank() }?.let {
            prefs.fakeSubBaseUrl = it
        }
        b.opensubKey.text.toString().trim().takeIf { it.isNotBlank() }?.let {
            prefs.openSubApiKey = it
        }
        b.opensubUser.text.toString().trim().takeIf { it.isNotBlank() }?.let {
            prefs.openSubUser = it
        }
        b.opensubPass.text.toString().takeIf { it.isNotEmpty() }?.let { prefs.openSubPass = it }
        b.opensubBase.text.toString().trim().takeIf { it.isNotBlank() }?.let {
            prefs.openSubBaseUrl = it
        }
        prefs.autoSubtitle = b.autoSubtitle.isChecked
        prefs.audioUseOpenSles = b.audioOpensles.isChecked
        b.subtitleSize.text.toString().toFloatOrNull()?.let {
            prefs.subtitleSizePercent =
                it.coerceIn(SubtitleView.MIN_SIZE_PERCENT, SubtitleView.MAX_SIZE_PERCENT)
        }
        b.subtitleDelay.text.toString().toIntOrNull()?.let { prefs.subtitleDelayMs = it }
        prefs.smbHost = b.smbHost.text.toString().trim()
        prefs.smbShare = b.smbShare.text.toString().trim()
        prefs.smbUser = b.smbUser.text.toString().trim()
        prefs.smbPass = b.smbPass.text.toString()
        // SMB 参数改过就要丢掉旧会话：现在全应用共用一条，不丢的话换了地址还在用老的连
        SmbSource.resetShared()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }
}
