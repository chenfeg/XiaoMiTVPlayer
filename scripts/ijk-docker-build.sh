#!/usr/bin/env bash
# 在 Docker 里自编译 ijkplayer 完整版内核（armeabi-v7a），产物直接落到
# app/src/main/jniLibs/armeabi-v7a/。Windows 侧只跑这一个脚本即可。
#
#   bash scripts/ijk-docker-build.sh
#
# 为什么要 Docker：ijk 0.8.x 的 FFmpeg 编译脚本只认带 gcc 4.9 的 NDK r14b
# （检测逻辑见 build-ijk.sh 头部），只能在 Linux 工具链下跑；构建目录放容器自己的
# ext4 卷里，放 Windows 挂载盘会慢一个数量级且丢掉可执行位。
# NDK zip 缓存在同名 docker volume 里，重跑不再下载。
set -euo pipefail
# Git Bash 会把 /d/... 这类参数改写成 Windows 路径，Docker 就不认了
export MSYS_NO_PATHCONV=1

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE=tvplayer-ijk:1
CACHE_VOL=tvplayer-ijk-cache
BUILD_VOL=tvplayer-ijk-build
NDK_URL="${NDK_URL:-https://dl.google.com/android/repository/android-ndk-r14b-linux-x86_64.zip}"
NDK_DIR="${NDK_DIR:-android-ndk-r14b}"
OUT_DIR="$REPO/app/src/main/jniLibs/armeabi-v7a"

docker info >/dev/null 2>&1 || { echo "Docker 没在运行，先启动 Docker Desktop"; exit 1; }

# buildx 的 context 路径不做 msys 转换，必须喂 Windows 路径（-v 挂载参数则相反）
WIN=$(cygpath -w "$REPO/scripts")
docker build -f "$WIN\\Dockerfile.ijk" -t "$IMAGE" "$WIN"
docker volume create "$CACHE_VOL" >/dev/null
# /build 也用卷：源码 clone 和 FFmpeg 中间产物留下来，重跑不用从下载重来
docker volume create "$BUILD_VOL" >/dev/null
mkdir -p "$OUT_DIR"

docker run --rm \
  -v "$CACHE_VOL":/cache \
  -v "$BUILD_VOL":/build \
  -v "$OUT_DIR":/out \
  -v "$REPO/scripts":/work/scripts:ro \
  -e NDK_URL="$NDK_URL" \
  -e NDK_DIR="$NDK_DIR" \
  "$IMAGE" bash -euc '
    mkdir -p /build/ndk
    # 缓存文件名必须带版本，否则换 NDK 时会拿旧包解压出对不上的目录
    if [ ! -d "/build/ndk/$NDK_DIR" ]; then
      if [ ! -f "/cache/$NDK_DIR.zip" ]; then
        curl -fL --retry 3 -o "/cache/$NDK_DIR.zip.part" "$NDK_URL"
        mv "/cache/$NDK_DIR.zip.part" "/cache/$NDK_DIR.zip"
      fi
      unzip -q "/cache/$NDK_DIR.zip" -d /build/ndk
    fi
    export ANDROID_NDK="/build/ndk/$NDK_DIR"
    # compile-ijk.sh 只做 ndk-build，但会检查 SDK 变量非空，这里不需要真 SDK
    cd /build
    IJK_WORK=/build/work IJK_OUT=/out bash /work/scripts/build-ijk.sh
  '

echo
echo "== 工程里的内核文件 =="
ls -l "$OUT_DIR"
echo "接着：删掉 app/build.gradle.kts 的 ijkplayer-armv7a 依赖，更新 PlayerEngine.KERNEL_AUDIO_CODECS，再 assembleDebug。"
