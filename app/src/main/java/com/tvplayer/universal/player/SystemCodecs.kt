package com.tvplayer.universal.player

import android.media.MediaCodecList

/**
 * 这台电视系统自己会解什么。硬解失败时先看这张表：
 * 表里没有的编码，ijk 传 MediaCodec 一定失败，只能靠自带 FFmpeg 软解，
 * 而软解又受 CPU 上限约束（见 PlayerActivity.SOFT_MAX_WIDTH）。
 */
object SystemCodecs {

    /** 形如 "video/hevc=OMX.MSK.HEVC,OMX.google.hevc | video/avc=…"；MediaCodecList 挂了返回 "?" */
    fun report(): String = runCatching {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val byMime = LinkedHashMap<String, MutableSet<String>>()
        for (info in list.codecInfos) {
            // 公开 SDK 里没有 isDecoder()，只能反向判：电视上的编码器寥寥无几
            if (info.isEncoder) continue
            for (mime in info.supportedTypes) {
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                byMime.getOrPut(mime) { LinkedHashSet() }.add(
                    info.name.removeSuffix(".Decoder").removePrefix("OMX.")
                )
            }
        }
        byMime.entries.joinToString(" ｜ ") { (mime, names) ->
            "$mime=${names.joinToString(",")}"
        }
    }.getOrDefault("MediaCodecList 不可用")
}
