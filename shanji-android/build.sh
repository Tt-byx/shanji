#!/usr/bin/env bash
# 闪记 Android 一键构建（Docker）
# 用法: bash build.sh
set -e
cd "$(dirname "$0")"
WIN_DIR=$(cygpath -w "$(pwd)")

echo "== [1/2] 构建环境镜像（首次约 5-15 分钟，含 SDK 下载）=="
docker build -t shanji-builder:1.0 .

echo "== [2/2] 编译 APK =="
# gradle 缓存挂 volume，二次构建不重新下载依赖
MSYS_NO_PATHCONV=1 docker run --rm \
  -v "${WIN_DIR}:/work" \
  -v shanji_gradle_cache:/root/.gradle \
  shanji-builder:1.0 \
  sh -c "cd /work && gradle --no-daemon assembleRelease && mkdir -p apk && cp app/build/outputs/apk/release/app-release.apk apk/ShanJi-v1.11-release.apk && echo APK_BUILT"

echo "== 产物 =="
ls -lh apk/
