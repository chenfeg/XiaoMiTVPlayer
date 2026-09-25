#!/usr/bin/env bash
# 自编译 ijkplayer + FFmpeg（只编 armeabi-v7a），把解码器补齐。
# 在 Linux 里跑：Windows 侧用 scripts/ijk-docker-build.sh 包一层 Docker。
#
# ！！为什么必须自编译（2026-09-22 实测，不是猜的）：
#   maven 上 ijkplayer-armv7a:0.8.8 的 libijkffmpeg.so 内嵌配置串是
#   --disable-decoders 后逐个 --enable-decoder 的白名单编译，实际只导出：
#     音频: aac aac_latm mp3* flac                 <- 没有 ac3/eac3/dts/pcm，DTS 片源必无声
#     视频: h264 hevc h263 mpeg4 vp6 vp8 vp9 flv   <- 没有 mpeg2video rv* wmv* vc1
#   复核命令：strings libijkffmpeg.so | grep -oE 'ff_[a-z0-9_]+_(decoder|demuxer)' | sort -u
#   ijk 0.8.8 的 MediaCodec 只用于视频，音频一律走自带 FFmpeg，所以代码侧没有绕法。
#
# 下面这些细节都是对着 k0.8.8 源码核过的，改动前先复核，别凭印象：
#   * 源码 tag 叫 k0.8.8（不是 v0.8.8），Java 层必须同版本，否则 JNI 方法表对不上。
#   * NDK 只能是 r14b：android/contrib/tools/do-detect-env.sh 只认 RELEASE.TXT 的
#     10e 或 source.properties 的 11*~14*，r15 起直接报 "You need the NDKr10e or later" 退出；
#     而且它要 gcc 4.9 + APP_STL := stlport_static（r18 起两样都没了）。
#   * config/module.sh 在仓库里是指向 module-lite.sh 的软链；init-config.sh 只有一句
#     "没有 module.sh 就 cp module-lite.sh"，不接受参数。所以要 rm 掉软链再放自己的文件，
#     否则写 module.sh 等于改 module-lite.sh，且 append 会写串位置。
#   * do-compile-ffmpeg.sh 在 source config/module.sh 之前会先 export COMMON_FF_CFG_FLAGS=
#     清空，所以档位里必须自带这一行（module-default.sh 有）。
#   * compile-ijk.sh 只跑 ndk-build，不会自己编 FFmpeg，必须先跑 android/contrib/compile-ffmpeg.sh。
#   * FFmpeg 源码要 init 到 android/contrib/ffmpeg-armv7a，libyuv/soundtouch 要 init 到
#     ijkmedia/ijkyuv、ijkmedia/ijksoundtouch（ijksoundtouch 是 ijkplayer 的必需静态库）。
#     官方 init-android*.sh 会从 Bilibili/* 拉全量历史；这里改成 --depth 1 --branch 单个
#     ref（libyuv/soundtouch 的 ref 是分支不是 tag），省掉几百 MB。
#
# 可调项（都有默认值）：
#   IJK_REF       ijkplayer 源码 tag，默认 k0.8.8
#   IJK_WORK      构建目录（容器里给 ext4 路径；放 Windows 挂载盘慢一个数量级且丢可执行位）
#   ANDROID_NDK   NDK 路径，必须 r14b（见上）
#   IJK_OUT       给了就把产物 .so 拷过去
set -euo pipefail

IJK_REF="${IJK_REF:-k0.8.8}"
FF_REF="${FF_REF:-ff3.4--ijk0.8.7--20180103--001}"
LIBYUV_REF="${LIBYUV_REF:-ijk-r0.2.1-dev}"
SOUNDTOUCH_REF="${SOUNDTOUCH_REF:-ijk-r0.1.2-dev}"
WORK="${IJK_WORK:-$(pwd)/ijk-work}"
GITHUB="${GITHUB:-https://github.com}"

[ -n "${ANDROID_NDK:-}" ] || { echo "必须先 export ANDROID_NDK（r14b）"; exit 1; }
[ -d "$ANDROID_NDK/toolchains/arm-linux-androideabi-4.9" ] || {
  echo "ANDROID_NDK=$ANDROID_NDK 里没有 gcc 4.9 工具链，ijk k0.8.8 只能用 NDK r14b"; exit 1; }
# do-detect-env.sh 靠 source.properties 里的 Pkg.Revision 认 NDK（只认 11~14），
# 认不出来会在编到一半时才报错，所以在这里先把它挡下来。
grep -q "Pkg.Revision" "$ANDROID_NDK/source.properties" 2>/dev/null || {
  echo "$ANDROID_NDK 没有 source.properties，ijk 的检测脚本会拒绝它"; exit 1; }
grep -q "= *1[1234]\." "$ANDROID_NDK/source.properties" || {
  echo "ijk k0.8.8 的 do-detect-env.sh 只接受 NDK 11~14（这里是 r14b），见文件头注释"; exit 1; }

mkdir -p "$WORK"
cd "$WORK"

# ---------------------------------------------------------------------------
# 1) 源码
clone1() { # clone1 <ref> <url> <dir>
  [ -d "$3" ] && { echo "已存在 $3，跳过 clone"; return; }
  git clone --depth 1 --branch "$1" "$2" "$3"
}
clone1 "$IJK_REF" "$GITHUB/bilibili/ijkplayer.git" ijkplayer
cd ijkplayer

# ---------------------------------------------------------------------------
# 2) FFmpeg 配置档位：直接用 module-default.sh（解码器/封装全开）。
#    先保功能正确，体积是后面的事：scripts/ijk-codec-whitelist.txt 是留给裁体积用的白名单，
#    真要裁就得把 --disable-decoders/--disable-demuxers 两行取消注释再追加白名单，
#    裁完必须用第 5 步的 strings 逐个点名复核，缺一个就是"有画面没声音"这种难查的坑。
rm -f config/module.sh
cp config/module-default.sh config/module.sh
# FFmpeg 3.4 在 arm 上无条件 enable_weak linux_perf（configure:5086），而 NDK r14b 的
# android-9 sysroot 里没有 <linux/perf_event.h>，编到 libavfilter 才炸。关掉它，
# 只是少一个性能计数器打点，不影响解码。
# 注意：module-default.sh 最后一行没有换行符，直接 >> 会把这行拼到那行注释后面被吃掉
# （第一次构建就是这么失败的），所以先补一个换行，再把写完的内容断言一遍。
{ printf '\n'; echo 'export COMMON_FF_CFG_FLAGS="$COMMON_FF_CFG_FLAGS --disable-linux-perf"'; } \
  >> config/module.sh
tail -1 config/module.sh | grep -q '^export COMMON_FF_CFG_FLAGS=.*--disable-linux-perf"$' || {
  echo "!! --disable-linux-perf 没写进 config/module.sh（档位文件被改过？）"; exit 1; }
echo "== 档位尾巴 =="; tail -2 config/module.sh

# ---------------------------------------------------------------------------
# 3) 依赖源码（浅克隆）
clone1 "$FF_REF"       "$GITHUB/Bilibili/FFmpeg.git"    android/contrib/ffmpeg-armv7a
clone1 "$LIBYUV_REF"   "$GITHUB/Bilibili/libyuv.git"    ijkmedia/ijkyuv
clone1 "$SOUNDTOUCH_REF" "$GITHUB/Bilibili/soundtouch.git" ijkmedia/ijksoundtouch

# ---------------------------------------------------------------------------
# 4) 先 FFmpeg 再 ijkplayer（顺序不能反，compile-ijk.sh 只是把编好的 so 链进去）
#    ijk 的检测脚本把并行度写死成空（单核 make 要一个多小时），这里用 GNU make
#    的环境变量补上，不改它的脚本。
export MAKEFLAGS="-j$(nproc)"
export ANDROID_SDK="${ANDROID_SDK:-$ANDROID_NDK}"   # compile-ijk.sh 只检查非空，不用真 SDK
# do-compile-ffmpeg.sh 看到 config.h 就 'reuse configure'，/build 是持久卷，
# 改了档位必须把旧的 config.h 删掉才会重新 configure。
rm -f android/contrib/ffmpeg-armv7a/config.h android/contrib/ffmpeg-armv7a/config_components.h
cd android/contrib
./compile-ffmpeg.sh armv7a
cd ../
./compile-ijk.sh armv7a

# ---------------------------------------------------------------------------
# 5) 验收：导出符号表就是实际编进去的组件，编没编上以这里为准，不看日志。
SO_DIR="$WORK/ijkplayer/android/ijkplayer/ijkplayer-armv7a/src/main/libs/armeabi-v7a"
test -f "$SO_DIR/libijkffmpeg.so" || { echo "没有产物 $SO_DIR/libijkffmpeg.so，构建没走通"; exit 1; }
echo "== 体积 =="; ls -l "$SO_DIR"
# 先一次性收进变量再判断：字符串集合很小，省得每次 grep 都重跑一遍 strings，
# 也避开 grep -q 提前关管道 + pipefail 造成的 SIGPIPE 假失败（这个坑踩过一次，
# 当时 ff_dca_decoder 明明在 so 里却被判成缺失）。
DECODERS=$(strings "$SO_DIR/libijkffmpeg.so" | grep -oE 'ff_[a-z0-9_]+_decoder' | sort -u)
echo "== 已编入的解码器 $(printf '%s\n' "$DECODERS" | wc -l) 个 =="
printf '%s ' $DECODERS; echo
for must in dca ac3 eac3 mpeg2video rv40 vc1 pcm_s16le; do
  [[ "$DECODERS" == *"ff_${must}_decoder"* ]] || {
    echo "!! 关键解码器 ff_${must}_decoder 仍然缺失，这个 so 先别拷进工程"; exit 1; }
done
echo "== 复核通过：DTS/AC3/EAC3/MPEG-2/RV/VC-1/PCM 都在 =="

if [ -n "${IJK_OUT:-}" ]; then
  mkdir -p "$IJK_OUT"
  cp -v "$SO_DIR"/*.so "$IJK_OUT"/
  echo "已拷到 $IJK_OUT"
fi
