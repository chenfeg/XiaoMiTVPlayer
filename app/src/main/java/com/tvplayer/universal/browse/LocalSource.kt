package com.tvplayer.universal.browse

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 本地存储 + USB。
 *
 * 挂载点在 5.1 电视上命名五花八门（/storage/UsbDriveA、/storage/udisk、/mnt/media_rw/1234-ABCD、
 * 还有 /storage/sdcard0 这类指向本机存储的分身），所以不猜名字，三管齐下找卷：
 *   1. StorageManager.getVolumeList（反射，@hide）里 isRemovable 的条目 —— 权威的 U 盘路径；
 *   2. /storage 与 /mnt 下各挂载父目录里能列出内容的子目录；
 *   3. 上面两者都只给路径不给内容的，要求它确实出现在 /proc/mounts 里，避免残留空目录冒充卷。
 * 去重不能只看 canonicalPath：绑定挂载/sdcardfs 分身不是软链，看穿不了，
 * 所以再叠加两层身份：stat 的 (st_dev, st_ino)，以及顶层条目名指纹（内容一样 = 同一个卷）。
 *
 * 有些 ROM 把 U 盘挂成只有 MediaProvider 可读（应用直连 listFiles 返回 null），
 * 这种情况下浏览退回查系统媒体库，视频以 content:// 交给 ContentSource，播放走本地 HTTP 桥。
 */
class LocalSource(context: Context) : FileSource {
    override val kind = SourceKind.LOCAL

    private val ctx: Context = context.applicationContext

    private fun primaryRoot(): File =
        Environment.getExternalStorageDirectory() // /storage/emulated/0

    /** 挂载父目录下的伪条目，不是可浏览的卷 */
    private val PSEUDO = setOf("self", "emulated", "android_secure", "tmp", "cid_media", "notifysd")

    /**
     * 这些文件系统挂出来的目录不是"能装视频的卷"，不该出现在存储列表里。
     * 电视列表里凭空多出的那条 "iso"，按挂载类型推断极可能是 iso9660（只读光碟镜像挂载），
     * 设置页「存储探测」会打出每个目录的 fstype 以便确认；真要看镜像，.iso 文件本身就在 U 盘里。
     */
    private val PSEUDO_FS = setOf(
        "iso9660", "squashfs", "ramfs", "tmpfs", "devtmpfs", "overlay", "overlayfs",
        "cgroup", "cgroup2", "proc", "sysfs", "pstore", "configfs", "fusectl",
        "debugfs", "tracefs", "9p", "vold", "sdksid"
    )

    private data class Volume(val path: String, val name: String, val kind: SourceKind)

    private fun identity(path: String): String? = runCatching {
        val st = android.system.Os.stat(path)
        "${st.st_dev}:${st.st_ino}"
    }.getOrNull()

    private fun canonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    /** 顶层条目名指纹。空目录返回 null：空的 U 盘和空的本机存储不是一个卷。 */
    private fun contentKey(path: String): String? =
        File(path).listFiles()?.map { it.name }?.sorted()
            ?.takeIf { it.isNotEmpty() }?.joinToString("|")

    /**
     * 挂载点 → 文件系统类型。每次扫描重读：/proc/mounts 很小，
     * 而缓存会让"应用开着才插的 U 盘"被漏掉。
     */
    private fun readMounts(): Map<String, String> = runCatching {
        File("/proc/mounts").readLines().mapNotNull { line ->
            val cols = line.split(' ')
            val at = cols.getOrNull(1) ?: return@mapNotNull null
            canonical(at) to (cols.getOrNull(2) ?: "")
        }.toMap()
    }.getOrDefault(emptyMap())

    /** StorageManager.getVolumeList 是 @hide 的，反射拿；拿不到不影响主路径 */
    private fun removableVolumes(): List<Pair<String, String>> {
        return try {
            val manager = ctx.getSystemService(Context.STORAGE_SERVICE) ?: return emptyList()
            val method = manager.javaClass.methods.firstOrNull { it.name == "getVolumeList" }
                ?: return emptyList()
            val volumes = method.invoke(manager) as? Array<*> ?: return emptyList()
            val volumeClass = method.returnType.componentType
            val getPath = volumeClass.getMethod("getPath")
            val isRemovable = volumeClass.getMethod("isRemovable")
            volumes.filterNotNull()
                .filter { isRemovable.invoke(it) as? Boolean == true }
                .mapNotNull { volume ->
                    val path = getPath.invoke(volume) as? String ?: return@mapNotNull null
                    path to volumeName(path)
                }
        } catch (e: Exception) {
            Log.w(TAG, "getVolumeList failed", e)
            emptyList()
        }
    }

    private fun volumeName(path: String): String {
        val label = File(path).name
        return if (label.isBlank() || label == "/") path else label
    }

    private fun candidateRoots(): List<Volume> {
        val out = LinkedHashMap<String, Volume>() // canonicalPath → 卷
        val seenIds = mutableSetOf<String>()
        val seenContent = mutableSetOf<String>()
        val mounts = readMounts()

        fun add(path: String, name: String, kind: SourceKind) {
            val canon = canonical(path)
            if (out.containsKey(canon)) return
            if ((mounts[canon] ?: "") in PSEUDO_FS) return // 光碟镜像/内核伪文件系统，不是内容卷
            // 已经是某个卷的子目录（有些 ROM 把 U 盘挂在 /storage/emulated/0/udisk）就不再单列
            if (out.keys.any { canon == it || canon.startsWith("$it/") }) return
            val id = identity(canon) ?: return // stat 都不行 = 根本不存在
            if (!seenIds.add(id)) return
            contentKey(canon)?.let { if (!seenContent.add(it)) return } // 同一份文件系统的分身
            out[canon] = Volume(path, name, kind)
        }

        add(primaryRoot().absolutePath, "本机存储", SourceKind.LOCAL)
        for ((path, name) in removableVolumes()) add(path, name, SourceKind.USB)

        for (base in listOf("/storage", "/mnt/media_rw", "/mnt/usb_storage", "/mnt/extsd")) {
            val children = File(base).listFiles() ?: continue
            for (sub in children) {
                if (!sub.isDirectory || sub.name.lowercase() in PSEUDO) continue
                val canon = canonical(sub.absolutePath)
                // 列不出内容的，必须确实是挂载点，否则只是残留空目录
                if (sub.listFiles() == null && canon !in mounts) continue
                add(sub.absolutePath, sub.name, SourceKind.USB)
            }
        }
        return out.values.toList()
    }

    override suspend fun roots(): List<MediaItem> =
        candidateRoots().map { MediaItem(it.path, it.name, true, 0, it.kind, hint = it.path) }

    override suspend fun list(item: MediaItem): List<MediaItem> {
        val files = File(item.path).listFiles()
        if (files != null) {
            return sorted(
                files.asSequence()
                    .filter { it.isDirectory || Kinds.isVideo(it.name) || Kinds.isSubtitle(it.name) }
                    .filter { !it.name.startsWith(".") }
                    .map {
                        MediaItem(
                            it.absolutePath, it.name, it.isDirectory,
                            if (it.isFile) it.length() else 0,
                            item.kind
                        )
                    }.toList()
            )
        }
        return sorted(mediaStoreList(item.path, item.kind))
    }

    /** 直连列不出内容时的兜底：从系统媒体库里按 DATA 前缀还原目录树 */
    private fun mediaStoreList(dir: String, kind: SourceKind): List<MediaItem> {
        val forms = listOf(dir, canonical(dir)).map { it.trimEnd('/') }.distinct()
        val base = dir.trimEnd('/')
        val subDirs = linkedSetOf<String>()
        val videos = mutableListOf<MediaItem>()
        runCatching {
            ctx.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DATA,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE
                ),
                null, null, null
            )
        }.onFailure { Log.w(TAG, "media query failed", it) }
            .getOrNull()?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                while (c.moveToNext()) {
                    val data = c.getString(dataCol) ?: continue
                    val prefix = forms.firstOrNull { data.startsWith("$it/") } ?: continue
                    val rel = data.substring(prefix.length + 1)
                    if (rel.contains('/')) {
                        val seg = rel.substringBefore('/')
                        if (!seg.startsWith(".")) subDirs += seg
                    } else {
                        val name = c.getString(nameCol).orEmpty()
                            .ifBlank { File(data).name }
                        if (!Kinds.isVideo(name)) continue
                        videos += MediaItem(
                            "$CONTENT_VIDEO/${c.getLong(idCol)}",
                            name, false, c.getLong(sizeCol), kind
                        )
                    }
                }
            }
        if (videos.isEmpty() && subDirs.isEmpty()) {
            Log.w(TAG, "no media under $dir (direct listing also blocked)")
        }
        return sorted(
            subDirs.map { MediaItem("$base/$it", it, true, 0, kind) } + videos
        )
    }

    private fun sorted(items: List<MediaItem>): List<MediaItem> = items.sortedWith(
        compareByDescending<MediaItem> { it.isDir }.thenBy { naturalKey(it.name) }
    )

    override suspend fun open(item: MediaItem): ContentSource {
        val path = item.path
        if (path.startsWith("content://")) {
            // 媒体库给的是 content URI，只能按偏移 pread（sdfuse 挂载下这条通常才是通的）
            val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(path), "r")
                ?: throw FileNotFoundException("cannot open $path")
            val size = if (pfd.statSize >= 0) pfd.statSize else runCatching {
                android.system.Os.fstat(pfd.fileDescriptor).st_size
            }.getOrDefault(-1L)
            return PreadContentSource(pfd, size)
        }
        val file = File(path)
        return RandomAccessContentSource(RandomAccessFile(file, "r"), file.length())
    }

    override suspend fun sidecarSubtitles(item: MediaItem): List<MediaItem> {
        if (item.path.startsWith("content://")) return emptyList()
        val base = File(item.path)
        val dir = base.parentFile ?: return emptyList()
        return dir.listFiles()?.mapNotNull { f ->
            if (!f.isFile) return@mapNotNull null
            val rank = SidecarMatch.rank(base.name, f.name) ?: return@mapNotNull null
            val child = MediaItem(f.absolutePath, f.name, false, f.length(), item.kind)
            rank to child
        }?.sortedWith(
            compareByDescending<Pair<Int, MediaItem>> { it.first }
                .thenByDescending { SidecarMatch.taggedChinese(it.second.name) }
                .thenBy { naturalKey(it.second.name) }
        )?.map { it.second } ?: emptyList()
    }

    companion object {
        private const val TAG = "LocalSource"
        private const val CONTENT_VIDEO = "content://media/external/video/media"

        /** 数字按数值排序（EP2 < EP10），遥控器列表可读性关键 */
        fun naturalKey(name: String): String {
            val sb = StringBuilder(name.lowercase())
            return sb.toString().replace(Regex("(\\d+)")) { m ->
                m.groupValues[1].padStart(12, '0')
            }
        }

        /**
         * 这个路径挂在什么文件系统上（按最长挂载点匹配）。
         * 大文件读不动时第一个要看的事实：厂商的 fuse/NTFS 驱动在 32 位设备上
         * 常常处理不了高 32 位偏移，而 ext4/sdcardfs 不会。
         */
        fun fstypeOf(path: String): String = runCatching {
            val target = File(path).canonicalPath
            File("/proc/mounts").readLines().mapNotNull { line ->
                val cols = line.split(' ')
                val mount = cols.getOrNull(1) ?: return@mapNotNull null
                val hit = mount == "/" || target == mount ||
                    target.startsWith(mount.trimEnd('/') + "/")
                if (hit) Pair(mount.length, cols.getOrNull(2) ?: "?") else null
            }.maxByOrNull { it.first }?.second ?: "?"
        }.getOrDefault("?")
    }
}

/** 本地文件随机读取 */
class RandomAccessContentSource(
    private val raf: RandomAccessFile,
    override val length: Long
) : ContentSource {

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, count: Int): Int {
        if (length >= 0 && position >= length) return -1
        raf.seek(position)
        return raf.read(buffer, 0, count).let { if (it <= 0 && count > 0) -1 else it }
    }

    override fun close() {
        try {
            raf.close()
        } catch (e: Exception) {
            Log.w("LocalSource", "close raf", e)
        }
    }
}

/** content:// 的 fd 不能包成 RandomAccessFile，用 pread 做绝对偏移读 */
private class PreadContentSource(
    private val pfd: ParcelFileDescriptor,
    override val length: Long
) : ContentSource {
    private val fd: FileDescriptor = pfd.fileDescriptor

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, count: Int): Int {
        if (length >= 0 && position >= length) return -1
        return try {
            android.system.Os.pread(fd, ByteBuffer.wrap(buffer, 0, count), position)
        } catch (e: Exception) {
            Log.w("LocalSource", "pread failed at $position", e)
            -1
        }
    }

    override fun close() {
        runCatching { pfd.close() }
    }
}
