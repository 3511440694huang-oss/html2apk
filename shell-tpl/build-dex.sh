#!/usr/bin/env bash
# build-dex.sh —— 在开发期把固定壳 Java 源码编译为 classes.dex
# 用法：bash build-dex.sh <android.jar 路径> <d8 路径>
# 示例：bash build-dex.sh /storage/emulated/0/WORK/02-工具链/android-sdk/platforms/android-36/android.jar /storage/emulated/0/WORK/02-工具链/android-sdk/build-tools/35.0.0/d8
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
ANDROID_JAR="${1:?usage: build-dex.sh <android.jar> <d8>}"
D8="${2:?usage: build-dex.sh <android.jar> <d8>}"
OUT="$HERE/out"

# 允许用环境变量覆盖工具链（设备上没有 java 在 PATH 里）
if [ -z "${JAVA_HOME:-}" ] && [ -x /data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-17-openjdk/bin/javac ]; then
  JAVA_HOME=/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-17-openjdk
fi
JAVAC="${JAVAC:-${JAVA_HOME:+$JAVA_HOME/bin/javac}}"
JAVAC="${JAVAC:-javac}"
export JAVA_HOME="${JAVA_HOME:-}"
if [ -n "$JAVA_HOME" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen/com/html2apk/shell"

echo "[1/4] generating Shim.java from shim.js..."
python3 "$HERE/gen-shim.py" > "$OUT/gen/com/html2apk/shell/Shim.java"
wc -c "$OUT/gen/com/html2apk/shell/Shim.java"

echo "[2/4] javac..."
SOURCES=$(find "$HERE/src" -name '*.java' | sort | tr '\n' ' ')
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
    -Xlint:-options -d "$OUT/classes" $SOURCES "$OUT/gen/com/html2apk/shell/Shim.java"

echo "[3/4] d8..."
CLASSES=$(find "$OUT/classes" -name '*.class' | tr '\n' ' ')
bash "$D8" --release --lib "$ANDROID_JAR" --min-api 24 \
    --output "$OUT" $CLASSES

echo "[4/4] done: $OUT/classes.dex"
ls -la "$OUT/classes.dex"
