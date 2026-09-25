package com.tvplayer.universal.browse

import android.util.Log
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.tvplayer.universal.data.EventLog
import com.tvplayer.universal.data.Prefs
import java.io.IOException
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 单服务器 SMB 浏览（smbj，SMB2/3）。smbj 0.11 无共享枚举 API，
 * 共享名由设置页直接配置，换来零注册表/IPC$ 协议的兼容代码。
 * 路径协议: smb://HOST/share/dir/file.mkv
 *
 * **这个文件里有一条必须遵守的规则：DiskShare 不是"借来的局部资源"，不能随手 close。**
 * smbj 0.11.5 的 `Session.connectShare(name)` 先查会话内的 `TreeConnectTable`，
 * 命中就打一行 "Returning cached Share {} for {}" **把同一个 DiskShare 实例返回**；
 * 而 `Share.close()` 做的是 `treeConnect.close()` = **TREE_DISCONNECT**。
 * 所以任何人 close 一次共享，同一共享上其它正在读的文件句柄全部失效。
 * 播放时 ijk 会为每次 seek 重开一条 HTTP 连接、桥每条连接开一个源，
 * 列目录/找侧挂字幕也要碰同一个共享 —— 谁 close 谁就把别人的树拆了，
 * 表现就是"能打开一下，接着就报数据读取中断"（what=-10000 extra=0，
 * 真错在 ff_ffplay.c:3551 那行 av_read_frame error 里）。
 * 树的生杀归会话：换主机/重连时 session.close() 会连带关掉所有 tree connect。
 *
 * **第二条必须遵守的规则：会话死了要能自己活过来（所有操作走 [withShare]）。**
 * `Share.isConnected()` 用 javap 看过实现，它只读本地一个 `AtomicBoolean disconnected`，
 * 也就是"我们有没有主动关过它"，**不反映服务器那一侧的状态**。NAS 空闲回收会话、SMB 服务重启、
 * 网络抖一下，都可能让那条树在远端已经作废而本地这个标志仍然是 false；
 * 而 `connectShare` 又优先返回缓存实例（上面那条 javap 证据），于是这个实例一旦坏了就永远坏着 ——
 * 表现就是"播完一片退出，再进那个目录就一直读不出来，非杀进程不可"。
 * [withShare] 的约定：失败时先分清是"会话/树在远端已经作废"（[worthReconnect]，含 STALE_SESSION
 * 那批状态码）还是"这个文件本身的问题"；前者作废会话、重连、再试一次，后者立刻抛出。
 * 后者不能重连，因为正在播放的文件句柄就挂在这条会话上，为一个"没权限/找不到文件"
 * 去关会话，等于自己把片子拆了。
 */
class SmbSource private constructor(private val prefs: Prefs) : FileSource {
    override val kind = SourceKind.SMB

    private val config: SmbConfig = SmbConfig.builder()
        .withMultiProtocolNegotiate(true)
        .withSoTimeout(15, TimeUnit.SECONDS)
        .build()

    private var connection: Connection? = null
    private var session: Session? = null

    /** 会话身份：换主机或换了账号密码都必须重连，只看 host 会让改设置后继续用旧会话 */
    private var sessionKey: String? = null

    @Synchronized
    private fun session(host: String): Session {
        val key = "$host|${prefs.smbUser}|${prefs.smbPass}"
        val cached = session
        if (cached != null && cached.connection.isConnected && sessionKey == key) return cached
        closeQuietly()
        val conn = SMBClient(config).connect(host)
        val ac = AuthenticationContext(
            prefs.smbUser.ifEmpty { "guest" },
            prefs.smbPass.toCharArray(),
            null
        )
        val sess = conn.authenticate(ac)
        connection = conn
        session = sess
        sessionKey = key
        return sess
    }

    /** 只丢引用、不关 socket：可能在播放器回调线程上被调用，关 socket 是阻塞的 */
    @Synchronized
    private fun invalidate() {
        val c = connection
        val s = session
        connection = null
        session = null
        sessionKey = null
        if (c != null || s != null) {
            thread(name = "smb-close", isDaemon = true) {
                runCatching { s?.close() }
                runCatching { c?.close() }
            }
        }
    }

    @Synchronized
    private fun closeQuietly() {
        runCatching { session?.close() }
        runCatching { connection?.close() }
        session = null
        connection = null
        sessionKey = null
    }

    private fun diskShare(host: String, share: String): DiskShare =
        session(host).connectShare(share) as DiskShare

    /**
     * 用一次共享。失败后**先判断这次失败是不是"会话/树坏了"**，是才作废会话重来一次；
     * 两次都失败才把异常抛给界面。
     *
     * 为什么要挑：正在播放时，视频那条文件句柄就挂在这同一个会话上。如果因为一个
     * "文件不存在/没权限"这类跟会话无关的错误就把会话关掉，等于亲手把正在播的片子拆了
     * —— 那是我们上一轮刚修过的同类事故（TREE_DISCONNECT 殃及全部句柄）。
     * 所以 SMBApiException 只认下面那批"远端已经不认这条会话/树"的状态码。
     */
    private fun <R> withShare(host: String, share: String, op: String, block: (DiskShare) -> R): R {
        var last: Exception? = null
        for (attempt in 1..2) {
            try {
                val ds = diskShare(host, share)
                if (!ds.isConnected) throw ShareClosed("$host/$share 这条树本地已标记为断开")
                return block(ds)
            } catch (e: Exception) {
                last = e
                if (attempt == 1 && worthReconnect(e)) {
                    EventLog.line(
                        "SMB $host/$share $op 失败（${e.javaClass.simpleName}: ${e.message}），重连会话重试一次"
                    )
                    invalidate()
                } else {
                    throw IOException("SMB $host/$share $op: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }
        val e = last!!
        throw IOException("SMB $host/$share $op（重连后仍然失败）: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** 缓存下来的那条树已经不可用，跟"文件不存在"不是一类错误 */
    private class ShareClosed(message: String) : IOException(message)

    /** 只有"远端不认这条会话/树"和传输层的断，才值得重连 */
    private fun worthReconnect(e: Throwable): Boolean = when (e) {
        is SMBApiException -> STALE_SESSION.contains(e.status)
        is ShareClosed, is TransportException, is ClosedChannelException, is SocketException,
        is IllegalStateException, is SMBRuntimeException -> true
        else -> false
    }

    /**
     * 设置页两个输入框太容易被填成 "smb://192.168.50.10/video" 这种整串，
     * 或者共享名前后带斜杠，这里统一拆成 (host, share)。
     */
    fun endpoint(): Pair<String, String> {
        var rest = prefs.smbHost.trim().removePrefix("smb://").removePrefix("SMB://")
        var share = prefs.smbShare.trim().trim('/', '\\')
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            val tail = rest.substring(slash + 1).trim('/', '\\')
            rest = rest.substring(0, slash)
            if (share.isEmpty()) share = tail
        }
        return rest.trim().substringBefore(':') to share
    }

    /** smbj 没有共享枚举 API，共享名必须由用户给出 */
    fun isConfigured(): Boolean = endpoint().let { it.first.isNotBlank() && it.second.isNotBlank() }

    override suspend fun roots(): List<MediaItem> {
        val (host, share) = endpoint()
        if (host.isBlank() || share.isBlank()) return emptyList()
        return listOf(
            MediaItem("smb://$host/$share", share, true, 0, SourceKind.SMB, hint = "SMB $host")
        )
    }

    override suspend fun list(item: MediaItem): List<MediaItem> {
        val (host, share, path) = split(item.path)
        val rel = path.trim('/')
        return withShare(host, share, "列目录") { ds ->
            val entries = if (rel.isEmpty()) ds.list("") else ds.list(rel)
            val visible = entries.asSequence()
                .filter { !it.fileName.startsWith(".") && it.fileName !in setOf(".", "..") }
                .map { fi ->
                    val name = fi.fileName
                    val isDir = fi.fileAttributes and
                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    val childPath = if (rel.isEmpty()) "smb://$host/$share/$name"
                    else "smb://$host/$share/$rel/$name"
                    MediaItem(childPath, name, isDir, if (isDir) 0 else fi.endOfFile, SourceKind.SMB)
                }
                .filter { it.isDir || Kinds.isVideo(it.name) || Kinds.isSubtitle(it.name) }
                .sortedWith(
                    compareByDescending<MediaItem> { it.isDir }
                        .thenBy { LocalSource.naturalKey(it.name) }
                )
                .toList()
            if (visible.isEmpty()) Log.i(TAG, "SMB ${item.path}: ${entries.size} raw entries, none playable")
            visible
        }
    }

    override suspend fun open(item: MediaItem): ContentSource {
        val (host, share, path) = split(item.path)
        // 这里不能"失败就 ds.close()"：close 是 TREE_DISCONNECT，会连带掐掉
        // 同一共享上其它正在读的文件句柄（见类注释）。失败交给 withShare 重连。
        val file = withShare(host, share, "打开文件") { ds ->
            ds.openFile(
                path.trim('/'),
                EnumSet.of(AccessMask.GENERIC_READ),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        }
        return SmbContentSource(file)
    }

    override suspend fun sidecarSubtitles(item: MediaItem): List<MediaItem> {
        val (host, share, path) = split(item.path)
        val parent = path.trim('/').substringBeforeLast('/', "")
        val videoName = path.substringAfterLast('/')
        return withShare(host, share, "找同级字幕") { ds ->
            val entries = if (parent.isEmpty()) ds.list("") else ds.list(parent)
            entries.mapNotNull { fi ->
                val rank = SidecarMatch.rank(videoName, fi.fileName) ?: return@mapNotNull null
                val p = if (parent.isEmpty()) fi.fileName else "$parent/${fi.fileName}"
                rank to MediaItem(
                    "smb://$host/$share/$p", fi.fileName, false, fi.endOfFile, SourceKind.SMB
                )
            }.sortedWith(
                compareByDescending<Pair<Int, MediaItem>> { it.first }
                    .thenByDescending { SidecarMatch.taggedChinese(it.second.name) }
                    .thenBy { LocalSource.naturalKey(it.second.name) }
            ).map { it.second }
        }
    }

    /**
     * 屏上自诊断（电视上没有 logcat 通道时唯一能看到失败原因的地方）：
     * 连接 → 认证 → 列共享根，把原始条目打出来，用来区分"连不上"和"连上了但都被扩展名过滤掉了"。
     */
    fun diagnose(): String = buildString {
        appendLine("— SMB 连接自检（改完设置请先按保存）—")
        val (host, share) = endpoint()
        appendLine("目标 $host/$share，用户 ${prefs.smbUser.ifBlank { "guest" }}")
        if (host.isBlank() || share.isBlank()) {
            appendLine("  主机或共享名为空：共享名必须手填（smbj 不支持枚举共享列表）")
        } else {
            runCatching {
                val entries = withShare(host, share, "自检") { ds -> ds.list("") }
                appendLine("  连接+认证+挂载共享都成功，共享根共 ${entries.size} 项：")
                entries.take(25).forEach { fi ->
                    val dir = fi.fileAttributes and
                        FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    appendLine("    ${if (dir) "目录" else "文件"} ${fi.fileName}")
                }
            }.onFailure { appendLine("  失败：${it.javaClass.name}: ${it.message}") }
        }
    }

    private fun split(smbPath: String): Triple<String, String, String> {
        val rest = smbPath.removePrefix("smb://")
        val host = rest.substringBefore('/')
        val after = rest.substringAfter('/', "")
        return Triple(host, after.substringBefore('/'), after.substringAfter('/', ""))
    }

    companion object {
        private const val TAG = "SmbSource"

        /**
         * "远端已经不认这条会话/这条树"的状态码（javap 对着 smbj 0.11.5 的 NtStatus 逐个核过名字）。
         * NAS 空闲回收会话、SMB 服务重启、共享被卸载都会落在这里面。
         * 名单之外的（无权限、找不到文件、路径不通）重连也改变不了，立刻抛出，
         * 免得顺手把正在播放的那条文件句柄一起拆掉。
         */
        private val STALE_SESSION = setOf(
            NtStatus.STATUS_NETWORK_NAME_DELETED,
            NtStatus.STATUS_BAD_NETWORK_NAME,
            NtStatus.STATUS_USER_SESSION_DELETED,
            NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            NtStatus.STATUS_CONNECTION_DISCONNECTED,
            NtStatus.STATUS_VOLUME_DISMOUNTED,
            NtStatus.STATUS_PIPE_NOT_AVAILABLE,
            NtStatus.STATUS_FILE_CLOSED,
            NtStatus.STATUS_FILE_DELETED,
            NtStatus.STATUS_DELETE_PENDING,
            NtStatus.STATUS_IO_TIMEOUT,
            NtStatus.STATUS_TIMEOUT,
        )

        @Volatile
        private var shared: SmbSource? = null

        /**
         * 全进程共用一条 SMB 会话。原来每个界面各 new 一个：浏览、播放、搜字幕、自检各自
         * 连一台 SMB 服务器，播放那几分钟里浏览那条会话一直空闲，最容易先被 NAS 回收 ——
         * 于是"播完退出再点目录就读不出来"。共用之后播放的读流量会一直养着这条会话，
         * 退出播放时它自然是活的；顺带也少开几条连接。
         */
        @Synchronized
        fun shared(prefs: Prefs): SmbSource =
            shared ?: SmbSource(prefs).also { shared = it }

        /** 设置页改了 SMB 参数后调用：丢掉旧会话，下次操作按新参数重连 */
        @Synchronized
        fun resetShared() {
            val old = shared
            shared = null
            old?.invalidate()
        }
    }
}

/**
 * 一个文件句柄 = 一个源。close 只关自己这个句柄；
 * 共享（tree connect）是会话级的缓存实例，动不得（见 SmbSource 类注释）。
 */
class SmbContentSource(
    private val file: com.hierynomus.smbj.share.File
) : ContentSource {
    override val length: Long = file.fileInformation.standardInformation.endOfFile

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, count: Int): Int {
        if (position >= length) return -1
        val n = file.read(buffer, position, 0, count)
        return if (n <= 0 && count > 0) -1 else n
    }

    override fun close() {
        runCatching { file.close() }
    }
}
