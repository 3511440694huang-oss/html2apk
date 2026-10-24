#!/usr/bin/env bash
# collect-engine.sh —— 采集构建引擎资产到 App 工程（开发期脚本）
#
# 重要（2026-10 修复）：
#   1. 旧版打包的 aapt2/zipalign 是 ET_EXEC（非 PIE），Android 10+ 直接拒绝执行
#      （"unexpected e_type: 2"），这是“构建引擎不可用/一直失败”的根因。
#      现在只使用 Termux 构建的 PIE aapt2（ET_DYN），并打包它的 .so 依赖。
#   2. zipalign 已移除：对齐改由纯 Kotlin 的 ZipAligner 完成。
#
# 用法：
#   AAPT2_BIN=/path/to/aapt2 TERMUX_LIB_DIR=/path/to/lib bash collect-engine.sh
# 缺省从 /data/data/com.termux/files/usr 取（Termux 安装的 aapt2 包）。
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HERE/../app/src/main"
ENGINE="$APP/assets/engine"
JNI="$APP/jniLibs/arm64-v8a"
SHELL_TPL="$HERE/../shell-tpl"
ANDROID_JAR="${ANDROID_JAR:-/storage/emulated/0/WORK/02-工具链/android-sdk/platforms/android-36/android.jar}"
APKSIG="${APKSIG:-/storage/emulated/0/WORK/02-工具链/toolchain/apksig-m1/apksig-8.5.2.jar}"
D8="${D8:-/storage/emulated/0/WORK/02-工具链/android-sdk/build-tools/35.0.0/d8}"
TERMUX_PREFIX="${TERMUX_PREFIX:-/data/data/com.termux/files/usr}"
AAPT2_BIN="${AAPT2_BIN:-$TERMUX_PREFIX/bin/aapt2}"
TERMUX_LIB_DIR="${TERMUX_LIB_DIR:-$TERMUX_PREFIX/lib}"

mkdir -p "$ENGINE/lib" "$JNI" "$APP/../libs"

if [ -f "$AAPT2_BIN" ]; then
  cp "$AAPT2_BIN" "$JNI/libaapt2.so"
  chmod 755 "$JNI/libaapt2.so"
  echo "==> aapt2 (jniLibs/libaapt2.so) from $AAPT2_BIN"
else
  echo "!! 找不到 $AAPT2_BIN，保留已有 libaapt2.so" >&2
fi

if [ -d "$TERMUX_LIB_DIR" ]; then
  echo "==> 复制 aapt2 运行时依赖 .so"
  for f in "$TERMUX_LIB_DIR"/libfmt.so "$TERMUX_LIB_DIR"/libz.so.1 \
           "$TERMUX_LIB_DIR"/libpng16.so "$TERMUX_LIB_DIR"/libexpat.so.1 \
           "$TERMUX_LIB_DIR"/libprotobuf.so "$TERMUX_LIB_DIR"/libc++_shared.so \
           "$TERMUX_LIB_DIR"/libabsl_*.so "$TERMUX_LIB_DIR"/libutf8_validity.so; do
    [ -f "$f" ] && cp "$f" "$ENGINE/lib/"
  done
fi

echo "==> android.jar (assets)"
cp "$ANDROID_JAR" "$ENGINE/android.jar"
echo "==> apksig (libs)"
[ -f "$APKSIG" ] && cp "$APKSIG" "$APP/../libs/apksig-8.5.2.jar"

echo "==> 编译壳模板 classes.dex"
if ! bash "$SHELL_TPL/build-dex.sh" "$ANDROID_JAR" "$D8"; then
  echo "!! build-dex 失败，使用已有 dex" >&2
fi
cp "$SHELL_TPL/out/classes.dex" "$ENGINE/template.dex"

echo "==> manifest 模板"
cp "$SHELL_TPL/AndroidManifest.tpl.xml" "$ENGINE/AndroidManifest.tpl.xml"

echo "==> 校验 aapt2 是 PIE（ET_DYN）"
python3 - "$JNI/libaapt2.so" <<'PY'
import struct, sys
p = sys.argv[1]
d = open(p, 'rb').read(20)
t = struct.unpack_from('<H', d, 16)[0]
print('   e_type =', t, '(3 = ET_DYN/PIE OK)' if t == 3 else '(不是 PIE，Android 10+ 无法执行！)')
sys.exit(0 if t == 3 else 1)
PY

echo "==> 引擎资产版本：$(cat "$ENGINE/VERSION" 2>/dev/null || echo '?')"
echo "    如果引擎/壳代码有变化，请把 app/src/main/assets/engine/VERSION 加 1，"
echo "    否则已安装用户升级后不会刷新 assets。"
echo "DONE."
ls -lh "$JNI" | tail -3
ls -lh "$ENGINE" | tail -5
