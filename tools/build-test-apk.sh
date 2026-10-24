#!/usr/bin/env bash
# build-test-apk.sh — build + align + sign a shell test APK entirely on-device.
#
# Usage:
#   tools/build-test-apk.sh <pkg> [assets-dir] [res-dir] [out.apk]
#
#   <pkg>         applicationId / manifest package (required)
#   [assets-dir]  directory packed as assets/ (default: shell-tpl/test/assets)
#   [res-dir]     resource dir. If it does not exist, a minimal res (strings +
#                 PNG icon) is generated under the build dir. Default: shell-tpl/test/res
#   [out.apk]     output path (default: tests/out/<pkg>.apk)
#
# Pipeline: manifest from shell-tpl/AndroidManifest.tpl.xml -> aapt2 compile/link
#   -> merge shell-tpl/out/classes.dex (+ optional engine libaapt2.so)
#   -> 4-byte align with the pure-Java ZipAlign -> apksigner v2/v3
#   -> verify signature + resources.arsc STORED/4-aligned -> sha256.
#
# Env overrides (all optional):
#   H2A_PROJECT H2A_SHELL_DIR H2A_MANIFEST_TPL H2A_SHELL_DEX
#   H2A_JDK H2A_ANDROID_JAR H2A_D8 H2A_APKSIGNER_JAR
#   H2A_KEYSTORE H2A_KEYSTORE_PASS H2A_KEY_ALIAS H2A_KEY_PASS
#   H2A_AAPT2 H2A_AAPT2_LIBS H2A_EXEC_DIR
#   H2A_ENGINE_LIB (default: app/src/main/jniLibs/arm64-v8a/libaapt2.so; "none" = skip)
#   H2A_ZIPALIGN_SRC H2A_ZIPALIGN_CLASSES
#   H2A_APK_BUILD_DIR H2A_TEST_OUT
#   H2A_VERSION_CODE H2A_VERSION_NAME H2A_MIN_SDK H2A_TARGET_SDK
#   H2A_DEX_MODE=auto|always|never  (default auto: rebuild if any source is newer)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${H2A_PROJECT:-$(cd "$HERE/.." && pwd)}"

usage() {
    sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
    exit "${1:-2}"
}

PKG="${1:-}"
[ -n "$PKG" ] || usage 2
case "$PKG" in -h|--help) usage 0 ;; esac

SHELL_DIR="${H2A_SHELL_DIR:-$PROJECT/shell-tpl}"
ASSETS_IN="${2:-$SHELL_DIR/test/assets}"
RES_IN="${3:-$SHELL_DIR/test/res}"
OUT="${4:-${H2A_TEST_OUT:-$PROJECT/tests/out}/$PKG.apk}"

MANIFEST_TPL="${H2A_MANIFEST_TPL:-$SHELL_DIR/AndroidManifest.tpl.xml}"
SRC_DIR="${H2A_SHELL_SRC:-$SHELL_DIR/src}"
DEX="${H2A_SHELL_DEX:-$SHELL_DIR/out/classes.dex}"

JDK="${H2A_JDK:-/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-17-openjdk}"
ANDROID_JAR="${H2A_ANDROID_JAR:-/storage/emulated/0/WORK/02-工具链/android-sdk/platforms/android-36/android.jar}"
D8="${H2A_D8:-/storage/emulated/0/WORK/02-工具链/android-sdk/build-tools/35.0.0/d8}"
APKSIGNER_JAR="${H2A_APKSIGNER_JAR:-/storage/emulated/0/WORK/02-工具链/android-sdk/build-tools/35.0.0/lib/apksigner.jar}"
KEYSTORE="${H2A_KEYSTORE:-/storage/emulated/0/WORK/01-开发项目/dsh-mobile-apk/keystore/debug.keystore}"
KEYSTORE_PASS="${H2A_KEYSTORE_PASS:-android}"
KEY_ALIAS="${H2A_KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${H2A_KEY_PASS:-android}"

EXEC_DIR="${H2A_EXEC_DIR:-/data/data/com.dsharnessmobile.shell/files/usr/tmp/h2a-build-tools}"
ZIPALIGN_SRC="${H2A_ZIPALIGN_SRC:-$HERE/ZipAlign.java}"
ZIPALIGN_CLASSES="${H2A_ZIPALIGN_CLASSES:-$PROJECT/tests/.build/zipalign-classes}"
BUILD="${H2A_APK_BUILD_DIR:-$PROJECT/tests/.build/apk-$PKG}"

ENGINE_LIB="${H2A_ENGINE_LIB:-$PROJECT/app/src/main/jniLibs/arm64-v8a/libaapt2.so}"
VERSION_CODE="${H2A_VERSION_CODE:-1}"
VERSION_NAME="${H2A_VERSION_NAME:-1.0}"
MIN_SDK="${H2A_MIN_SDK:-24}"
TARGET_SDK="${H2A_TARGET_SDK:-33}"
die() { echo "[build-test-apk] ERROR: $*" >&2; exit 1; }
step() { echo; echo "[build-test-apk] == $* =="; }

DEX_MODE="${H2A_DEX_MODE:-auto}"
case "$DEX_MODE" in
    auto|always|never) ;;
    *) die "H2A_DEX_MODE must be auto|always|never, got: $DEX_MODE" ;;
esac
case "$BUILD" in
    ""|"/") die "refusing unsafe H2A_APK_BUILD_DIR: '$BUILD'" ;;
esac

JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"

[ -x "$JAVAC" ] || die "javac not found: $JAVAC (set H2A_JDK)"
[ -x "$JAVA" ] || die "java not found: $JAVA (set H2A_JDK)"
[ -f "$ANDROID_JAR" ] || die "android.jar not found: $ANDROID_JAR"
[ -f "$APKSIGNER_JAR" ] || die "apksigner jar not found: $APKSIGNER_JAR"
[ -f "$KEYSTORE" ] || die "keystore not found: $KEYSTORE"
[ -f "$MANIFEST_TPL" ] || die "manifest template not found: $MANIFEST_TPL"
[ -d "$ASSETS_IN" ] || die "assets dir not found: $ASSETS_IN"

if ! [[ "$PKG" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$ ]]; then
    die "invalid package name: $PKG"
fi
[[ "$VERSION_CODE" =~ ^[0-9]+$ ]] || die "H2A_VERSION_CODE must be an integer"
[[ "$VERSION_NAME" =~ ^[A-Za-z0-9._+-]{1,64}$ ]] || die "H2A_VERSION_NAME has unsupported characters"

ASSETS="$(cd "$ASSETS_IN" && pwd)"
OUT_DIR="$(dirname "$OUT")"
mkdir -p "$OUT_DIR"
OUT="$(cd "$OUT_DIR" && pwd)/$(basename "$OUT")"
mkdir -p "$(dirname "$OUT")"

echo "[build-test-apk] project: $PROJECT"
echo "[build-test-apk] pkg:     $PKG"
echo "[build-test-apk] assets:  $ASSETS"
echo "[build-test-apk] output:  $OUT"

step "1/9 prerequisites"
[ -d "$SRC_DIR" ] || die "shell source dir not found: $SRC_DIR"
if [ -n "${ENGINE_LIB:-}" ] && [ "$ENGINE_LIB" != "none" ] && [ ! -f "$ENGINE_LIB" ]; then
    die "engine lib not found: $ENGINE_LIB (set H2A_ENGINE_LIB=none to build without it)"
fi
if [ "$ENGINE_LIB" = "none" ]; then ENGINE_LIB=""; fi
echo "  engine lib: ${ENGINE_LIB:-<none>}"

step "2/9 shell dex"
rebuild=0
if [ "$DEX_MODE" = "always" ]; then
    rebuild=1
elif [ "$DEX_MODE" = "auto" ]; then
    if [ ! -f "$DEX" ]; then
        rebuild=1
    else
        changed="$(find "$SRC_DIR" -type f \( -name '*.java' -o -name '*.js' \) \
            -newer "$DEX" -print -quit 2>/dev/null || true)"
        if [ -n "$changed" ]; then
            echo "  source newer than dex -> rebuilding"
            rebuild=1
        elif [ -f "$SHELL_DIR/gen-shim.py" ] && [ "$SHELL_DIR/gen-shim.py" -nt "$DEX" ]; then
            echo "  gen-shim.py newer than dex -> rebuilding"
            rebuild=1
        fi
    fi
fi
if [ "$rebuild" -eq 1 ]; then
    if ! H2A_JDK="$JDK" H2A_ANDROID_JAR="$ANDROID_JAR" H2A_D8="$D8" \
            H2A_SHELL_DIR="$SHELL_DIR" H2A_SHELL_SRC="$SRC_DIR" \
            H2A_SHELL_OUT="$(dirname "$DEX")" \
            bash "$HERE/build-shell-dex.sh"; then
        if [ -f "$DEX" ]; then
            echo "  WARNING: dex rebuild failed (shell sources may be mid-edit);" >&2
            echo "           falling back to existing $DEX" >&2
        else
            die "dex rebuild failed and no fallback dex exists: $DEX"
        fi
    fi
fi
[ -f "$DEX" ] || die "dex missing: $DEX (run tools/build-shell-dex.sh first, or set H2A_SHELL_DEX)"
echo "  using: $DEX ($(wc -c < "$DEX" | tr -d ' ') bytes)"

step "3/9 resources"
if [ -d "$RES_IN" ]; then
    RES="$(cd "$RES_IN" && pwd)"
    RES_NOTE="provided"
else
    RES="$BUILD/res-auto"
    RES_NOTE="generated (missing: $RES_IN)"
fi
echo "  res: $RES  [$RES_NOTE]"

step "4/9 manifest"
rm -rf "$BUILD"
mkdir -p "$BUILD"
# Freeze the dex: the shell dir may be rebuilt by a parallel agent while aapt2
# runs, and we want the exact dex we printed in step 2.
DEX_FROZEN="$BUILD/input-classes.dex"
cp "$DEX" "$DEX_FROZEN" || die "failed to freeze dex: $DEX"
python3 - "$MANIFEST_TPL" "$BUILD/AndroidManifest.xml" "$PKG" "$VERSION_CODE" "$VERSION_NAME" <<'PY'
import re
import sys
from xml.sax.saxutils import escape

tpl, out, pkg, version_code, version_name = sys.argv[1:6]
text = open(tpl, encoding="utf-8").read()

values = {
    "__PACKAGE__": pkg,
    "__VERSION_CODE__": version_code,
    "__VERSION_NAME__": version_name,
}
found = set(re.findall(r"__[A-Z0-9_]+__", text))
unknown = sorted(found - set(values))
if unknown:
    sys.stderr.write(
        "unknown manifest placeholder(s): %s\n"
        "Add them to tools/build-test-apk.sh (known: %s)\n"
        % (", ".join(unknown), ", ".join(sorted(values)))
    )
    sys.exit(2)

for key, value in values.items():
    text = text.replace(key, escape(value, {'"': "&quot;"}))
open(out, "w", encoding="utf-8").write(text)
print("  wrote %s (replaced %d placeholder occurrence(s))"
      % (out, sum(1 for _ in re.finditer(r"__[A-Z0-9_]+__", open(tpl, encoding="utf-8").read()))))
PY

if [ "$RES" = "$BUILD/res-auto" ]; then
    python3 - "$RES" <<'PY'
import os
import struct
import sys
import zlib

res = sys.argv[1]
os.makedirs(os.path.join(res, "values"), exist_ok=True)
os.makedirs(os.path.join(res, "mipmap"), exist_ok=True)

with open(os.path.join(res, "values", "strings.xml"), "w", encoding="utf-8") as f:
    f.write('<?xml version="1.0" encoding="utf-8"?>\n'
            '<resources>\n    <string name="app_name">H2A Test</string>\n</resources>\n')

# Minimal 48x48 RGBA PNG: dark navy with a blue square. No external deps.
w = h = 48
rows = bytearray()
for y in range(h):
    rows.append(0)  # filter type 0
    for x in range(w):
        if 8 <= x < 40 and 8 <= y < 40:
            r, g, b, a = 0x4C, 0x8D, 0xFF, 0xFF
        else:
            r, g, b, a = 0x0B, 0x10, 0x20, 0xFF
        rows += bytes((r, g, b, a))


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


png = (b"\x89PNG\r\n\x1a\n"
       + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
       + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
       + chunk(b"IEND", b""))
with open(os.path.join(res, "mipmap", "ic_launcher.png"), "wb") as f:
    f.write(png)
print("  generated minimal res: %s" % res)
PY
fi

step "5/9 aapt2 compile + link"
AAPT2="$(bash "$HERE/prepare-aapt2.sh")" || die "failed to prepare aapt2"
[ -x "$AAPT2" ] || die "aapt2 not executable: $AAPT2"
AAPT2_LD="$EXEC_DIR/lib"
[ -d "$AAPT2_LD" ] || AAPT2_LD="$(dirname "$AAPT2")/lib"
run_aapt2() {
    LD_LIBRARY_PATH="$AAPT2_LD${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" "$AAPT2" "$@"
}
rm -f "$BUILD/res.zip" "$BUILD/unsigned.apk"
run_aapt2 compile --dir "$RES" -o "$BUILD/res.zip"
run_aapt2 link -o "$BUILD/unsigned.apk" -I "$ANDROID_JAR" -0 arsc \
    --manifest "$BUILD/AndroidManifest.xml" "$BUILD/res.zip" \
    -A "$ASSETS" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --auto-add-overlay
[ -f "$BUILD/unsigned.apk" ] || die "aapt2 link produced no APK"

step "6/9 merge classes.dex (+ engine lib)"
python3 - "$BUILD/unsigned.apk" "$BUILD/withdex.apk" "$DEX_FROZEN" "${ENGINE_LIB:-}" <<'PY'
import sys
import zipfile

src, dst, dex, engine_lib = sys.argv[1:5]
DEX_TIME = (2020, 1, 1, 0, 0, 0)


def add(zout, name, data, stored):
    info = zipfile.ZipInfo(name, date_time=DEX_TIME)
    info.compress_type = zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    zout.writestr(info, data)


with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
    for info in zin.infolist():
        data = zin.read(info.filename)
        ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
        ni.external_attr = info.external_attr
        ni.create_system = info.create_system
        ni.comment = info.comment
        # resources.arsc and native libs must be STORED (arsc also 4-aligned).
        if info.filename == "resources.arsc" or info.filename.endswith(".so"):
            ni.compress_type = zipfile.ZIP_STORED
        else:
            ni.compress_type = zipfile.ZIP_DEFLATED
        zout.writestr(ni, data)

    with open(dex, "rb") as f:
        add(zout, "classes.dex", f.read(), stored=False)

    if engine_lib:
        with open(engine_lib, "rb") as f:
            add(zout, "lib/arm64-v8a/libaapt2.so", f.read(), stored=True)
        print("  merged classes.dex + lib/arm64-v8a/libaapt2.so")
    else:
        print("  merged classes.dex (no engine lib)")

with zipfile.ZipFile(dst) as z:
    arsc = z.getinfo("resources.arsc")
    if arsc.compress_type != zipfile.ZIP_STORED:
        sys.exit("resources.arsc is not STORED after merge")
    if "classes.dex" not in z.namelist():
        sys.exit("classes.dex missing after merge")
    print("  withdex.apk entries: %d" % len(z.infolist()))
PY

step "7/9 4-byte align (Java ZipAlign)"
if [ ! -f "$ZIPALIGN_CLASSES/ZipAlign.class" ] || [ "$ZIPALIGN_SRC" -nt "$ZIPALIGN_CLASSES/ZipAlign.class" ]; then
    rm -rf "$ZIPALIGN_CLASSES"
    mkdir -p "$ZIPALIGN_CLASSES"
    echo "  compiling $ZIPALIGN_SRC -> $ZIPALIGN_CLASSES"
    "$JAVAC" -d "$ZIPALIGN_CLASSES" "$ZIPALIGN_SRC" || die "failed to compile ZipAlign.java"
fi
rm -f "$BUILD/aligned.apk"
"$JAVA" -cp "$ZIPALIGN_CLASSES" ZipAlign "$BUILD/withdex.apk" "$BUILD/aligned.apk" 4

step "8/9 sign v2/v3 (apksigner)"
rm -f "$BUILD/signed.apk"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEYSTORE_PASS" --key-pass "pass:$KEY_PASS" \
    --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true \
    --out "$BUILD/signed.apk" "$BUILD/aligned.apk"
[ -f "$BUILD/signed.apk" ] || die "apksigner produced no APK"

step "9/9 verify + publish"
VERIFY_LOG="$BUILD/apksigner-verify.txt"
if ! "$JAVA" -jar "$APKSIGNER_JAR" verify --verbose --print-certs \
        "$BUILD/signed.apk" >"$VERIFY_LOG" 2>&1; then
    cat "$VERIFY_LOG" >&2
    die "apksigner verify failed"
fi
grep -E "Verified using (v2|v3) scheme|Number of signers|Signer #1 certificate DN" "$VERIFY_LOG" | sed 's/^/  /' || true
grep -Eq "Verified using v2 scheme .*: true" "$VERIFY_LOG" || die "APK is not v2-signed"
grep -Eq "Verified using v3 scheme .*: true" "$VERIFY_LOG" || die "APK is not v3-signed"

python3 - "$BUILD/signed.apk" "$OUT" <<'PY'
import hashlib
import os
import shutil
import struct
import sys
import zipfile

apk, out = sys.argv[1:3]
with open(apk, "rb") as f:
    blob = f.read()

with zipfile.ZipFile(apk) as z:
    info = z.getinfo("resources.arsc")
    if info.compress_type != zipfile.ZIP_STORED:
        sys.exit("resources.arsc is not STORED")
    with open(apk, "rb") as f:
        f.seek(info.header_offset)
        local = f.read(30)
    if local[:4] != b"PK\x03\x04":
        sys.exit("bad local header for resources.arsc")
    name_len, extra_len = struct.unpack_from("<HH", local, 26)
    data_off = info.header_offset + 30 + name_len + extra_len
    if data_off % 4 != 0:
        sys.exit("resources.arsc data offset %d is not 4-byte aligned" % data_off)
    if "classes.dex" not in z.namelist():
        sys.exit("classes.dex missing from signed APK")

shutil.copyfile(apk, out)
digest = hashlib.sha256(open(out, "rb").read()).hexdigest()
size = os.path.getsize(out)
print("  resources.arsc: STORED, data offset %d (mod 4 = 0)" % data_off)
print("  APK:    %s" % out)
print("  size:   %d bytes" % size)
print("  sha256: %s" % digest)
print("BUILD_OK %s %s" % (out, digest))
PY

echo
echo "[build-test-apk] DONE: $OUT"
