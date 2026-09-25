package com.tvplayer.universal.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.tvplayer.universal.BuildConfig
import com.tvplayer.universal.R
import com.tvplayer.universal.browse.FileSource
import com.tvplayer.universal.browse.LocalSource
import com.tvplayer.universal.browse.MediaItem
import com.tvplayer.universal.browse.Kinds
import com.tvplayer.universal.browse.SmbSource
import com.tvplayer.universal.browse.SourceKind
import com.tvplayer.universal.data.EventLog
import com.tvplayer.universal.data.Prefs
import com.tvplayer.universal.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var local: LocalSource
    private var smb: SmbSource? = null

    private val adapter by lazy { FileAdapter { open(it) } }
    private val stack = ArrayDeque<Pair<MediaItem?, FileSource>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)
        local = LocalSource(this)
        if (BuildConfig.ENABLE_SMB) smb = SmbSource.shared(prefs)

        b.list.adapter = adapter
        requestStoragePermission()
        loadTopLevel()
    }

    /**
     * targetSdk 28 ⇒ 5.1 上 READ_EXTERNAL_STORAGE 也是运行时权限。
     * 已授权时这句不会弹窗（本机存储能列出来就说明已授权）；没授权就必须要一次，
     * 否则 U 盘那条路径连 stat 都过不去。
     */
    private fun requestStoragePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQUEST_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) loadTopLevel()
    }

    private fun sourceOf(item: MediaItem): FileSource =
        if (item.kind == SourceKind.SMB) smb!! else local

    private fun loadTopLevel() {
        stack.clear()
        b.breadcrumb.setText(R.string.app_name)
        lifecycleScope.launch {
            val items = mutableListOf<MediaItem>()
            items += withContext(Dispatchers.IO) {
                runCatching { local.roots() }.getOrDefault(emptyList())
            }
            val smb = this@MainActivity.smb
            if (smb != null) {
                if (smb.isConfigured()) {
                    // 只配了一个共享，就直接摆到顶层，省掉"点进去还有一层空列表"
                    items += withContext(Dispatchers.IO) { smb.roots() }
                } else if (prefs.smbHost.isNotBlank()) {
                    Toast.makeText(
                        this@MainActivity, R.string.smb_need_share, Toast.LENGTH_LONG
                    ).show()
                }
            }
            if (items.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.source_none, Toast.LENGTH_LONG).show()
            }
            adapter.items = items
            focusFirst()
        }
    }

    private fun loadDir(item: MediaItem) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { sourceOf(item).list(item) }
            }
            val entries = result.getOrElse { e ->
                // 失败就说失败，不能顺手报"这个目录里没有视频"：后者会把人引去翻 NAS 的
                // 共享设置，而真正的原因在会话/网络/权限那一侧。整条异常同时落进运行日志。
                val why = "${e.javaClass.simpleName}: ${e.message}"
                EventLog.line("列目录失败 $why")
                Toast.makeText(
                    this@MainActivity, getString(R.string.browse_error, why), Toast.LENGTH_LONG
                ).show()
                // 列表清空：留着上一个目录的条目会让人以为"这就是这个目录的内容"
                adapter.items = emptyList()
                b.breadcrumb.text = breadcrumb()
                return@launch
            }
            if (entries.isEmpty()) {
                Toast.makeText(this@MainActivity, R.string.browse_empty_dir, Toast.LENGTH_LONG).show()
            }
            adapter.items = entries
            b.breadcrumb.text = breadcrumb()
            focusFirst()
        }
    }

    private fun breadcrumb(): String =
        stack.mapNotNull { it.first?.name }.ifEmpty { listOf(getString(R.string.app_name)) }
            .joinToString(" / ")

    private fun open(item: MediaItem) {
        when {
            item.isDir -> {
                stack.addLast(item to sourceOf(item))
                loadDir(item)
            }
            Kinds.isVideo(item.name) -> {
                startActivity(
                    Intent(this, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_PATH, item.path)
                        putExtra(PlayerActivity.EXTRA_NAME, item.name)
                    }
                )
            }
            else -> Toast.makeText(this, item.name, Toast.LENGTH_SHORT).show()
        }
    }

    private fun focusFirst() {
        b.list.post {
            // ViewHolder 可能还没绑定完，退回直接取第一个子 View
            val child = b.list.findViewHolderForAdapterPosition(0)?.itemView ?: b.list.getChildAt(0)
            (child ?: b.list).requestFocus()
        }
    }

    /**
     * U 盘拔了列表还挂着那条，是因为旧版只在 onResume 重扫一次 —— 人一直停在列表页时
     * 根本没有 onResume。存储插拔系统都会发广播，跟着广播重扫才是同步的。
     */
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            EventLog.line("存储变化 $action ${intent.data?.path ?: ""}")
            if (stack.isEmpty()) {
                loadTopLevel()
                return
            }
            // 正在逛的目录属于刚拔掉的那个盘：留在原地只会看到媒体库缓存出来的假条目
            if (action in REMOVAL_ACTIONS && goneLocalDir(stack.lastOrNull()?.first)) {
                stack.clear()
                loadTopLevel()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(
            storageReceiver, IntentFilter().apply {
                (MOUNT_ACTIONS + REMOVAL_ACTIONS).forEach { addAction(it) }
                // 真机验过的坑（2026-09-24）：只 addAction 的过滤器被排进 dumpsys 的
                // "Non-Data Actions" 索引里，而存储广播一律带 dat=file://，于是永远匹配不上 ——
                // 同一个 action 去掉 data 能收到、加上 file:// 收不到。厂商自己的接收者写了 Scheme: "file"。
                addDataScheme("file")
            }
        )
        refreshIfStale()
    }

    override fun onPause() {
        super.onPause()
        runCatching { unregisterReceiver(storageReceiver) }
    }

    /**
     * 停在顶层时每次回到前台都重扫（插了 U 盘没切过界面也要看得见）；
     * 停在某个目录里时，只有这个目录已经不存在了才退回顶层，不打扰正常浏览位置。
     */
    private fun refreshIfStale() {
        if (stack.isEmpty()) {
            if (adapter.items.isNotEmpty()) loadTopLevel()
            return
        }
        if (goneLocalDir(stack.lastOrNull()?.first)) {
            stack.clear()
            loadTopLevel()
        }
    }

    /**
     * 当前目录是否已经不存在了。U 盘卷的 kind 是 USB、本机存储才是 LOCAL，
     * 旧代码只判 LOCAL，所以在 U 盘子目录里拔盘不会退回顶层。
     */
    private fun goneLocalDir(top: MediaItem?): Boolean =
        top != null && top.kind != SourceKind.SMB && !File(top.path).exists()

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Back navigates up the folder stack")
    override fun onBackPressed() {
        if (stack.isNotEmpty()) {
            stack.removeLast()
            val parent = stack.lastOrNull()
            if (parent == null) loadTopLevel() else loadDir(parent.first!!)
        } else {
            super.onBackPressed()
        }
    }

    companion object {
        private const val REQUEST_STORAGE = 1
        private val MOUNT_ACTIONS = setOf(Intent.ACTION_MEDIA_MOUNTED)
        private val REMOVAL_ACTIONS = setOf(
            Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT, Intent.ACTION_MEDIA_BAD_REMOVAL
        )
    }
}
