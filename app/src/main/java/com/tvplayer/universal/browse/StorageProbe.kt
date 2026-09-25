package com.tvplayer.universal.browse

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 一次性存储探测：电视上找不到 U 盘时，把真实挂载情况直接显示在屏幕上，
 * 免得只能靠 adb logcat（现场未必有调试通道）。
 */
object StorageProbe {

    private const val TAG = "StorageProbe"

    fun report(context: Context): String = buildString {
        appendLine("Environment: ${Environment.getExternalStorageDirectory()} state=${Environment.getExternalStorageState()}")
        appendLine()

        appendLine("— getExternalFilesDirs —")
        runCatching {
            val dirs = context.getExternalFilesDirs(null).filterNotNull()
            if (dirs.isEmpty()) appendLine("  （空）")
            dirs.forEach { appendLine("  ${it.absolutePath}") }
        }.onFailure { appendLine("  取不到: ${it.message}") }
        appendLine()

        appendLine("— StorageManager.getVolumeList（反射）—")
        volumes(context)
        appendLine()

        val fsTypes = runCatching {
            File("/proc/mounts").readLines().mapNotNull { line ->
                val cols = line.split(' ')
                val at = cols.getOrNull(1) ?: return@mapNotNull null
                at to (cols.getOrNull(2) ?: "")
            }.toMap()
        }.getOrDefault(emptyMap())

        for (base in listOf("/storage", "/mnt", "/mnt/media_rw", "/mnt/usb_storage", "/mnt/extsd")) {
            appendLine("— $base —")
            val children = File(base).listFiles()
            when {
                children == null -> appendLine("  列不出（不存在或无权限）")
                children.isEmpty() -> appendLine("  （空）")
                else -> children.sortedBy { it.name }.forEach { sub ->
                    appendLine(
                        "  %-20s dir=%-5s listable=%-5s fs=%-9s id=%s".format(
                            sub.name, sub.isDirectory, sub.listFiles() != null,
                            fsTypes[sub.absolutePath] ?: "-", identity(sub.absolutePath)
                        )
                    )
                }
            }
            appendLine()
        }

        appendLine("— /proc/mounts 相关行 —")
        runCatching {
            val hits = File("/proc/mounts").readLines().filter { line ->
                listOf("usb", "sdcard", "media", "fuse", "vold", "extsd", "removable")
                    .any { line.contains(it, ignoreCase = true) }
            }
            if (hits.isEmpty()) appendLine("  （无匹配）")
            hits.forEach { appendLine("  $it") }
        }.onFailure { appendLine("  读不到: ${it.message}") }
        appendLine()

        // 直连列不出内容时靠媒体库兜底，所以媒体库里有没有 U 盘的视频决定成败
        appendLine("— MediaStore 视频按挂载点统计 —")
        runCatching {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DATA), null, null, null
            )
        }.getOrNull()?.use { c ->
            val col = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            val counts = linkedMapOf<String, Int>()
            while (c.moveToNext()) {
                val data = c.getString(col) ?: continue
                val volume = data.split('/').take(3).joinToString("/")
                counts[volume] = (counts[volume] ?: 0) + 1
            }
            if (counts.isEmpty()) appendLine("  （媒体库里一条视频都没有）")
            counts.forEach { (k, v) -> appendLine("  $k  → $v 条") }
        }
    }

    private fun StringBuilder.volumes(context: Context) {
        try {
            val manager = context.applicationContext.getSystemService(Context.STORAGE_SERVICE)
                ?: run { appendLine("  服务为 null"); return }
            val method = manager.javaClass.methods.firstOrNull { it.name == "getVolumeList" }
                ?: run { appendLine("  无 getVolumeList"); return }
            val list = method.invoke(manager) as? Array<*> ?: run { appendLine("  返回 null"); return }
            if (list.isEmpty()) { appendLine("  （0 个卷）"); return }
            val volumeClass = method.returnType.componentType
            val getPath = volumeClass.getMethod("getPath")
            val isRemovable = volumeClass.getMethod("isRemovable")
            val isPrimary = volumeClass.getMethod("isPrimary")
            for (volume in list) {
                if (volume == null) continue
                appendLine(
                    "  path=%s removable=%s primary=%s".format(
                        getPath.invoke(volume), isRemovable.invoke(volume), isPrimary.invoke(volume)
                    )
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "volume reflection failed", e)
            appendLine("  反射失败: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun identity(path: String): String? = runCatching {
        val st = android.system.Os.stat(path)
        "${st.st_dev}:${st.st_ino}"
    }.getOrNull()
}
