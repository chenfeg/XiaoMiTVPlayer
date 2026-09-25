# ===== ijkplayer 0.8.8（Java 层来自 Maven，native 层为 scripts/build-ijk.sh 自编译）=====
#
# 旧规则把 tv.danmaku.ijk.media.player.** 整包保留（约 70 个类）。实际 JNI 触点
# 已通过 jar 内的 @CalledByNative / @AccessedByNative 注解 + 全量 native 方法清单核实：
# libijkplayer.so 的 JNI_OnLoad 只对 IjkMediaPlayer FindClass + RegisterNatives，
# 并按名查找下列字段与静态方法；其余 misc/listener 类全是 Java 层互调，可正常混淆。
-keep class tv.danmaku.ijk.media.player.IjkMediaPlayer {
    native <methods>;                                   # RegisterNatives 按方法名注册
    static *** postEventFromNative(...);               # native 事件回流入口
    static *** onNativeInvoke(...);                    # native invoke 回调
    static *** onSelectCodec(...);                     # 硬解器选择回调
    long mNativeMediaPlayer;                           # 以下 5 个字段 native 按字段名读写
    long mNativeMediaDataSource;
    long mNativeAndroidIO;
    int mNativeSurfaceTexture;
    int mListenerContext;
}

# FFmpegApi.av_base64_encode 是静态 native 方法，注册与 .so 加载绑定，保留这一个小类
-keep class tv.danmaku.ijk.media.player.ffmpeg.FFmpegApi { *; }

# IAndroidIO / IMediaDataSource 仅在被 setDataSource/setAndroidIOCallback 传入 native 时
# 才被按名调用；本项目一律走 HTTP 桥 + 路径传参，从不使用，R8 可安全移除，故不 keep。

# 自有代码（com.tvplayer.universal.**）没有任何 external 方法、也不被 native 按名反射：
# ijk 监听器都是 SAM lambda，R8 可正常追踪。旧的两条包级 keep 已删除。

# ===== 缺类仅存在于编译期引用，Android 运行时永不触发 =====
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.impl.**

# 以下均为桌面端可选集成（smbj SPNEGO、okhttp Conscrypt/OpenJSSE、mbassy EL）
-dontwarn javax.el.**
-dontwarn org.conscrypt.**
-dontwarn org.ietf.jgss.**
-dontwarn org.openjsse.**
