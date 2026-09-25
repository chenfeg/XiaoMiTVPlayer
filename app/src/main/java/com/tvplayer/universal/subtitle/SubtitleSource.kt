package com.tvplayer.universal.subtitle

data class SubCandidate(
    val sourceId: String, // "assrt" | "fakesub"
    val externalId: String,
    val title: String,
    /** 下载后可能是 zip，需要解包 */
    val mayBeZip: Boolean,
    val formatHint: String?,
    val editor: String?,
    /** 可直接 GET 的下载链（部分源提供），为 null 时走 source.fetch 二次换取 */
    val downloadUrl: String? = null,
    /** 换取直链时字幕站还要的第二级 ID：OpenSubtitles 的 /download 除 sub_id 外必须带 files[].file_id */
    val fileId: Long? = null,
    /** 合并后的匹配分，越大越优 */
    var score: Int
)

interface SubtitleSource {
    val id: String
    suspend fun search(info: ReleaseInfo, hash: String?, size: Long): List<SubCandidate>
    /** 返回原始字节（可能是 zip/gz 包裹）；info 用于在多文件包里挑出这一集 */
    suspend fun fetch(candidate: SubCandidate, info: ReleaseInfo): ByteArray
}
