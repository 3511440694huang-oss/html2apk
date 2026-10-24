#!/usr/bin/env bash
# build-shell-dex.sh — compile the fixed HTML2APK shell sources into classes.dex.
#
# Compiles every *.java under $H2A_SHELL_SRC (recursively, so new helper files
# are picked up automatically), plus a generated Shim.java when shell-tpl ships
# gen-shim.py + shim.js, dexes all classes with d8, then validates/prints the
# resulting dex.
#
# Defaults are the verified on-device paths. Override with env vars:
#   H2A_JDK          JDK 17 home                     (default: Termux JDK17)
#   H2A_ANDROID_JAR  android.jar                     (default: android-36)
#   H2A_D8           d8 launcher (bash script)       (default: build-tools 35.0.0)
#   H2A_SHELL_SRC    shell java source root          (default: <project>/shell-tpl/src)
#   H2A_SHELL_OUT    output root                     (default: <project>/shell-tpl/out)
#   H2A_SHELL_GEN_DIR generated Shim.java dir        (default: <project>/tests/.build/shell-gen)
#   H2A_MIN_API      d8 --min-api                    (default: 24)
#   H2A_EXPECT_CLASS descriptor that must exist      (default: Lcom/html2apk/shell/MainActivity;)
#                    set to "" to disable the check.
#
# Note: dexdump from toolchain/sdk-tools-aarch64 is a non-PIE ET_EXEC and is
# rejected by Android 10+ ("unexpected e_type: 2"), so the dex is inspected with
# a small pure-python parser instead (magic/version/class count/class list).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${H2A_PROJECT:-$(cd "$HERE/.." && pwd)}"

JDK="${H2A_JDK:-/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-17-openjdk}"
ANDROID_JAR="${H2A_ANDROID_JAR:-/storage/emulated/0/WORK/02-工具链/android-sdk/platforms/android-36/android.jar}"
D8="${H2A_D8:-/storage/emulated/0/WORK/02-工具链/android-sdk/build-tools/35.0.0/d8}"
SRC="${H2A_SHELL_SRC:-$PROJECT/shell-tpl/src}"
SHELL_ROOT="${H2A_SHELL_DIR:-$PROJECT/shell-tpl}"
OUT="${H2A_SHELL_OUT:-$PROJECT/shell-tpl/out}"
GEN_DIR="${H2A_SHELL_GEN_DIR:-$PROJECT/tests/.build/shell-gen}"
MIN_API="${H2A_MIN_API:-24}"
EXPECT_CLASS="${H2A_EXPECT_CLASS:-Lcom/html2apk/shell/MainActivity;}"

JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"
DEX="$OUT/classes.dex"
TMP_DEX="$OUT/.dex-tmp"
TMP_CLASSES="$OUT/.classes-tmp"

die() { echo "[build-shell-dex] ERROR: $*" >&2; exit 1; }

[ -x "$JAVAC" ] || die "javac not found: $JAVAC (set H2A_JDK)"
[ -x "$JAVA" ] || die "java not found: $JAVA (set H2A_JDK)"
[ -f "$ANDROID_JAR" ] || die "android.jar not found: $ANDROID_JAR (set H2A_ANDROID_JAR)"
[ -f "$D8" ] || die "d8 not found: $D8 (set H2A_D8)"
[ -d "$SRC" ] || die "shell source dir not found: $SRC (set H2A_SHELL_SRC)"

SOURCES=()
while IFS= read -r -d '' f; do
    SOURCES+=("$f")
done < <(find "$SRC" -type f -name '*.java' -print0)
[ "${#SOURCES[@]}" -gt 0 ] || die "no *.java under $SRC"

# The product shell embeds shim.js as generated Java. shell-tpl/build-dex.sh
# generates it into out/gen; we generate it into tests/.build instead (never
# touching shell-tpl/src) so this script works from a clean checkout too.
GEN_SOURCES=()
SHIM_JS="$SHELL_ROOT/src/com/html2apk/shell/shim.js"
GEN_SHIM_PY="$SHELL_ROOT/gen-shim.py"
SRC_SHIM="$(find "$SRC" -type f -name 'Shim.java' -print -quit)"
if [ -n "$SRC_SHIM" ]; then
    echo "[build-shell-dex] using Shim.java from source: ${SRC_SHIM#"$PROJECT"/}"
elif [ -f "$GEN_SHIM_PY" ] && [ -f "$SHIM_JS" ]; then
    command -v python3 >/dev/null 2>&1 || die "python3 required to generate Shim.java"
    mkdir -p "$GEN_DIR" || die "cannot create generated-source dir: $GEN_DIR"
    echo "[build-shell-dex] generating Shim.java from $(basename "$SHIM_JS") ..."
    if ! python3 "$GEN_SHIM_PY" > "$GEN_DIR/Shim.java"; then
        die "Shim.java generation failed ($GEN_SHIM_PY)"
    fi
    [ -s "$GEN_DIR/Shim.java" ] || die "generated Shim.java is empty"
    GEN_SOURCES+=("$GEN_DIR/Shim.java")
elif [ -f "$SHELL_ROOT/out/gen/com/html2apk/shell/Shim.java" ]; then
    GEN_SOURCES+=("$SHELL_ROOT/out/gen/com/html2apk/shell/Shim.java")
fi
if [ "${#GEN_SOURCES[@]}" -gt 0 ]; then
    SOURCES+=("${GEN_SOURCES[@]}")
fi

echo "[build-shell-dex] project: $PROJECT"
echo "[build-shell-dex] sources: ${#SOURCES[@]} file(s)"
for f in "${SOURCES[@]}"; do echo "    ${f#"$PROJECT"/}"; done

# Build into temp dirs and only swap them in on success: a failed compile must
# not destroy the last good classes.dex (a parallel shell edit may be transient).
cleanup() { rm -rf "$TMP_CLASSES" "$TMP_DEX"; }
trap cleanup EXIT
rm -rf "$TMP_CLASSES" "$TMP_DEX"
mkdir -p "$TMP_CLASSES" "$TMP_DEX"

echo "[build-shell-dex] [1/3] javac (source/target 8, bootclasspath android.jar)"
if ! "$JAVAC" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
        -Xlint:-options -d "$TMP_CLASSES" "${SOURCES[@]}"; then
    die "javac failed"
fi

CLASSES=()
while IFS= read -r -d '' f; do
    CLASSES+=("$f")
done < <(find "$TMP_CLASSES" -type f -name '*.class' -print0)
[ "${#CLASSES[@]}" -gt 0 ] || die "javac produced no .class files"

echo "[build-shell-dex] [2/3] d8 (min-api $MIN_API, ${#CLASSES[@]} class files)"
if ! JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" \
        bash "$D8" --release --min-api "$MIN_API" --lib "$ANDROID_JAR" \
        --output "$TMP_DEX" "${CLASSES[@]}"; then
    die "d8 failed"
fi
[ -f "$TMP_DEX/classes.dex" ] || die "d8 did not write classes.dex"
rm -rf "$OUT/classes"
mv "$TMP_CLASSES" "$OUT/classes"
mv -f "$TMP_DEX/classes.dex" "$DEX"
rm -rf "$TMP_DEX"

echo "[build-shell-dex] [3/3] verify dex"
python3 - "$DEX" "$EXPECT_CLASS" <<'PY'
import re
import struct
import sys

path, expect = sys.argv[1], sys.argv[2]
data = open(path, "rb").read()

if not re.match(rb"^dex\n03[5-9]\x00$", data[:8]):
    sys.exit("bad dex magic: %r" % data[:8])

file_size = struct.unpack_from("<I", data, 0x20)[0]
if file_size != len(data):
    sys.exit("dex header file_size=%d but actual=%d" % (file_size, len(data)))

str_ids_size, str_ids_off = struct.unpack_from("<II", data, 0x38)
type_ids_size, type_ids_off = struct.unpack_from("<II", data, 0x40)
class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)
if class_defs_size == 0:
    sys.exit("dex has zero class_defs")
if class_defs_size > 100000 or class_defs_off + class_defs_size * 32 > len(data):
    sys.exit("dex class_defs table out of range")


def uleb128(off):
    value = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        value |= (b & 0x7F) << shift
        if not (b & 0x80):
            return value, off
        shift += 7
        if shift > 35:
            sys.exit("bad uleb128")


def type_descriptor(type_idx):
    if type_idx >= type_ids_size:
        sys.exit("type_idx out of range: %d" % type_idx)
    str_idx = struct.unpack_from("<I", data, type_ids_off + type_idx * 4)[0]
    if str_idx >= str_ids_size:
        sys.exit("string_idx out of range: %d" % str_idx)
    str_off = struct.unpack_from("<I", data, str_ids_off + str_idx * 4)[0]
    length, p = uleb128(str_off)
    return data[p:p + length].decode("utf-8", "replace")


classes = []
for i in range(class_defs_size):
    class_idx = struct.unpack_from("<I", data, class_defs_off + i * 32)[0]
    classes.append(type_descriptor(class_idx))

classes = sorted(set(classes))
version = data[4:7].decode("ascii")
print("    path:    %s" % path)
print("    size:    %d bytes" % len(data))
print("    magic:   dex\\n%s (class_defs=%d)" % (version, class_defs_size))
print("    classes (%d):" % len(classes))
for c in classes:
    print("      %s" % c)

if expect and expect not in classes:
    sys.exit("expected class %s not found in dex" % expect)
PY

SIZE=$(wc -c < "$DEX" | tr -d ' ')
echo "[build-shell-dex] OK: $DEX ($SIZE bytes)"
