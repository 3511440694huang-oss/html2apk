#!/usr/bin/env bash
# prepare-aapt2.sh — make the bundled PIE aapt2 runnable in this app sandbox.
#
# The project's old aapt2 under toolchain/sdk-tools-aarch64 is a non-PIE ET_EXEC
# and cannot run on Android 10+. A working PIE aapt2 ships as
# app/src/main/jniLibs/arm64-v8a/libaapt2.so; it needs its runtime .so files.
# /storage is noexec for ELF, so binary + libs are copied to an app-private dir.
#
# Prints the runnable aapt2 path as the LAST LINE of stdout (diagnostics go to
# stderr), so callers can do:  AAPT2=$(bash tools/prepare-aapt2.sh)
#
# Env overrides:
#   H2A_AAPT2       source aapt2 binary   (default: app/src/main/jniLibs/arm64-v8a/libaapt2.so)
#   H2A_AAPT2_LIBS  runtime lib dir       (default: app/src/main/assets/engine/lib)
#   H2A_EXEC_DIR    app-private exec dir  (default: .../files/usr/tmp/h2a-build-tools)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROJECT="${H2A_PROJECT:-$(cd "$HERE/.." && pwd)}"

SRC="${H2A_AAPT2:-$PROJECT/app/src/main/jniLibs/arm64-v8a/libaapt2.so}"
LIB_SRC="${H2A_AAPT2_LIBS:-$PROJECT/app/src/main/assets/engine/lib}"
EXEC="${H2A_EXEC_DIR:-/data/data/com.dsharnessmobile.shell/files/usr/tmp/h2a-build-tools}"

log() { echo "[prepare-aapt2] $*" >&2; }
die() { log "ERROR: $*"; exit 1; }

[ -f "$SRC" ] || die "aapt2 binary not found: $SRC (set H2A_AAPT2)"
[ -d "$LIB_SRC" ] || die "aapt2 runtime lib dir not found: $LIB_SRC (set H2A_AAPT2_LIBS)"
mkdir -p "$EXEC/lib" 2>/dev/null || die "cannot create exec dir $EXEC (set H2A_EXEC_DIR to an app-private writable dir)"
[ -w "$EXEC" ] || die "exec dir not writable: $EXEC"

# aapt2 binary (skip the 3.6 MB copy when unchanged).
if [ ! -f "$EXEC/aapt2" ] || ! cmp -s "$SRC" "$EXEC/aapt2"; then
    cp -f "$SRC" "$EXEC/aapt2" || die "failed to copy aapt2 to $EXEC"
    log "copied aapt2 -> $EXEC/aapt2"
fi
chmod 755 "$EXEC/aapt2"

# Runtime libs (libc++_shared, libprotobuf, absl, ...). Compare before copying
# so repeated builds are cheap. New/changed libs in the project are picked up.
copied=0
for lib in "$LIB_SRC"/*.so*; do
    [ -e "$lib" ] || continue
    name="$(basename "$lib")"
    if [ ! -f "$EXEC/lib/$name" ] || ! cmp -s "$lib" "$EXEC/lib/$name"; then
        cp -f "$lib" "$EXEC/lib/$name" || die "failed to copy $name"
        copied=$((copied + 1))
    fi
done
chmod 644 "$EXEC/lib/"*.so* 2>/dev/null || true
[ "$copied" -gt 0 ] && log "copied $copied runtime lib(s) -> $EXEC/lib"

# Sanity check: an aapt2 that cannot even print its version is useless.
if ! VERSION_OUT=$(LD_LIBRARY_PATH="$EXEC/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
        "$EXEC/aapt2" version 2>&1); then
    log "aapt2 failed to start:"
    log "$VERSION_OUT"
    die "aapt2 is not runnable; check H2A_AAPT2 / H2A_AAPT2_LIBS"
fi
log "$VERSION_OUT"

# Last stdout line = path to use.
echo "$EXEC/aapt2"
