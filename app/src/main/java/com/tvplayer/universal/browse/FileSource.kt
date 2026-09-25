package com.tvplayer.universal.browse

interface FileSource {
    val kind: SourceKind

    /** 顶层入口（本机/USB 各分区、SMB 各共享） */
    suspend fun roots(): List<MediaItem>

    /** 目录内容；只返回子目录和视频/字幕文件 */
    suspend fun list(item: MediaItem): List<MediaItem>

    /** 把条目解析成可随机读取的源（播放/哈希用） */
    suspend fun open(item: MediaItem): ContentSource

    /** 同级目录中的外挂字幕文件 */
    suspend fun sidecarSubtitles(item: MediaItem): List<MediaItem>
}

/** 可随机读取的字节源：本地文件、SMB 文件统一抽象，播放器经本地 HTTP 桥接消费 */
interface ContentSource : AutoCloseable {
    val length: Long
    fun readAt(position: Long, buffer: ByteArray, count: Int): Int
}
