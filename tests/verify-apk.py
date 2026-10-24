#!/usr/bin/env python3
"""Static verification for HTML2APK shell/test APKs. No adb, no device install.

Usage:
    python3 tests/verify-apk.py <apk> [--expect-package NAME]

Checks (each printed as PASS/FAIL; exit code 1 if any FAIL):
  * aapt2 dump badging: package name + resolved application label
  * assets/ entries exist; assets/ht2a.config entryFile points to a real asset
  * classes.dex present, valid dex magic, contains com/html2apk/shell/MainActivity
  * resources.arsc STORED and its data offset is 4-byte aligned
  * apksigner verify: v2 and v3 signatures both verified
  * lib/arm64-v8a/libaapt2.so present, ELF64 little-endian ET_DYN (aarch64)
  * no libzipalign.so anywhere in the APK

Env overrides: H2A_JDK, H2A_APKSIGNER_JAR, H2A_AAPT2, H2A_AAPT2_LIBS,
H2A_EXEC_DIR (aapt2 setup is delegated to tools/prepare-aapt2.sh).
"""

import argparse
import os
import re
import struct
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
TOOLS = os.path.join(PROJECT, "tools")

DEFAULT_JDK = "/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-17-openjdk"
DEFAULT_APKSIGNER = ("/storage/emulated/0/WORK/02-工具链/android-sdk/"
                     "build-tools/35.0.0/lib/apksigner.jar")


class Result:
    def __init__(self):
        self.passed = 0
        self.failed = 0
        self.warned = 0

    def check(self, name, ok, detail=""):
        tag = "PASS" if ok else "FAIL"
        print("[%s] %s%s" % (tag, name, (" — " + detail) if detail else ""))
        if ok:
            self.passed += 1
        else:
            self.failed += 1
        return ok

    def warn(self, name, detail=""):
        print("[WARN] %s%s" % (name, (" — " + detail) if detail else ""))
        self.warned += 1


def aapt2_env(aapt2):
    env = os.environ.copy()
    libs = []
    libdir = os.path.join(os.path.dirname(aapt2), "lib")
    if os.path.isdir(libdir):
        libs.append(libdir)
    for key, fallback in (("H2A_AAPT2_LIBS",
                           os.path.join(PROJECT, "app/src/main/assets/engine/lib")),):
        value = os.environ.get(key, fallback)
        if value and os.path.isdir(value):
            libs.append(value)
    old = env.get("LD_LIBRARY_PATH")
    if old:
        libs.append(old)
    env["LD_LIBRARY_PATH"] = ":".join(libs)
    return env


def find_aapt2(explicit):
    if explicit:
        return explicit
    helper = os.path.join(TOOLS, "prepare-aapt2.sh")
    if not os.path.isfile(helper):
        return None
    proc = subprocess.run(["bash", helper], capture_output=True, text=True)
    if proc.returncode != 0:
        sys.stderr.write(proc.stdout + proc.stderr)
        return None
    lines = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    return lines[-1] if lines else None


def check_package_label(res, apk, aapt2, expect_package):
    if not aapt2 or not os.path.isfile(aapt2):
        res.check("package name (aapt2 dump badging)", False,
                  "aapt2 unavailable; set H2A_AAPT2 / H2A_EXEC_DIR")
        res.check("manifest label (aapt2 dump badging)", False, "aapt2 unavailable")
        return
    proc = subprocess.run([aapt2, "dump", "badging", apk],
                          capture_output=True, text=True, env=aapt2_env(aapt2))
    out = proc.stdout
    if proc.returncode != 0:
        res.check("package name (aapt2 dump badging)", False,
                  (proc.stderr or out).strip().splitlines()[-1] if (proc.stderr or out).strip() else "aapt2 failed")
        res.check("manifest label (aapt2 dump badging)", False, "aapt2 failed")
        return

    m = re.search(r"package: name='([^']*)'", out)
    pkg = m.group(1) if m else ""
    if expect_package:
        res.check("package name", pkg == expect_package,
                  "got %r, expected %r" % (pkg, expect_package))
    else:
        res.check("package name", bool(pkg), pkg or "missing from badging output")

    labels = re.findall(r"application-label(?:-[^:']+)?:'([^']*)'", out)
    label = labels[0] if labels else ""
    res.check("manifest label", bool(label.strip()), label or "missing application-label")


def check_assets(res, zf):
    names = zf.namelist()
    assets = [n for n in names if n.startswith("assets/") and not n.endswith("/")]
    res.check("assets/ entries", bool(assets), "%d file(s)" % len(assets))

    config_name = "assets/ht2a.config"
    if config_name in names:
        text = zf.read(config_name).decode("utf-8", "replace")
        entry = None
        for line in text.splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if key.strip() == "entryFile":
                entry = value.strip()
                break
        if not entry:
            res.check("ht2a.config entryFile", False, "config present but no entryFile=")
        else:
            target = "assets/" + entry.lstrip("/")
            res.check("ht2a.config entryFile", target in names,
                      "%s -> %s" % (entry, "exists" if target in names else "MISSING"))
    else:
        res.warn("assets/ht2a.config absent", "shell will default to index.html")
        if "assets/index.html" not in names:
            res.warn("assets/index.html absent", "no obvious root entry; check entryFile config")


def check_dex(res, zf):
    if "classes.dex" not in zf.namelist():
        res.check("classes.dex present", False, "missing")
        return
    data = zf.read("classes.dex")
    if len(data) < 0x70:
        res.check("classes.dex valid + shell MainActivity", False,
                  "too short: %d bytes" % len(data))
        return
    magic_ok = (data[:4] == b"dex\n" and data[4:7].isdigit() and data[7] == 0)
    version = data[4:7].decode("ascii", "replace") if magic_ok else "?"
    class_count = struct.unpack_from("<I", data, 0x60)[0] if len(data) >= 0x64 else -1
    has_main = b"com/html2apk/shell/MainActivity" in data
    res.check("classes.dex valid + shell MainActivity", magic_ok and has_main,
              "version=%s class_defs=%d size=%d bytes%s"
              % (version, class_count, len(data),
                 "" if has_main else " (MainActivity string NOT found)"))


def check_arsc(res, apk, zf):
    name = "resources.arsc"
    if name not in zf.namelist():
        res.check("resources.arsc STORED + 4-aligned", False, "missing")
        return
    info = zf.getinfo(name)
    stored = info.compress_type == zipfile.ZIP_STORED
    with open(apk, "rb") as f:
        f.seek(info.header_offset)
        local = f.read(30)
    if len(local) < 30 or local[:4] != b"PK\x03\x04":
        res.check("resources.arsc STORED + 4-aligned", False, "bad local header")
        return
    name_len, extra_len = struct.unpack_from("<HH", local, 26)
    data_off = info.header_offset + 30 + name_len + extra_len
    aligned = data_off % 4 == 0
    res.check("resources.arsc STORED + 4-aligned", stored and aligned,
              "method=%s offset=%d (mod4=%d)"
              % ("STORED" if stored else "compressed", data_off, data_off % 4))


def check_signature(res, apk):
    java = os.environ.get("H2A_JAVA", os.path.join(
        os.environ.get("H2A_JDK", DEFAULT_JDK), "bin", "java"))
    jar = os.environ.get("H2A_APKSIGNER_JAR", DEFAULT_APKSIGNER)
    if not os.path.isfile(java):
        res.check("signature v2/v3", False, "java not found: %s" % java)
        return
    if not os.path.isfile(jar):
        res.check("signature v2/v3", False, "apksigner.jar not found: %s" % jar)
        return
    proc = subprocess.run([java, "-jar", jar, "verify", "--verbose", apk],
                          capture_output=True, text=True)
    out = proc.stdout + proc.stderr
    v2 = re.search(r"Verified using v2 scheme .*: (true|false)", out)
    v3 = re.search(r"Verified using v3 scheme .*: (true|false)", out)
    ok = (proc.returncode == 0 and v2 and v2.group(1) == "true"
          and v3 and v3.group(1) == "true")
    detail = "v2=%s v3=%s" % (v2.group(1) if v2 else "?",
                              v3.group(1) if v3 else "?")
    if not ok and proc.returncode != 0:
        detail += "; apksigner exit=%d (%s)" % (
            proc.returncode, (out.strip().splitlines() or ["?"])[-1])
    res.check("signature v2/v3", bool(ok), detail)


def check_native(res, zf):
    name = "lib/arm64-v8a/libaapt2.so"
    names = zf.namelist()
    if name not in names:
        res.check("libaapt2.so present + ELF ET_DYN", False, "missing " + name)
    else:
        with zf.open(name) as f:
            data = f.read(64)
        elf_ok = (len(data) >= 20 and data[:4] == b"\x7fELF"
                  and data[4] == 2      # ELFCLASS64
                  and data[5] == 1)     # ELFDATA2LSB
        if elf_ok:
            e_type, e_machine = struct.unpack_from("<HH", data, 16)
            detail = "ELF64 LE e_type=%d (ET_DYN=%d) e_machine=%d" % (
                e_type, 3, e_machine)
            ok = e_type == 3 and e_machine == 183  # AArch64
        else:
            detail = "not an ELF64 little-endian file"
            ok = False
        res.check("libaapt2.so present + ELF ET_DYN", ok, detail)

    bad = [n for n in names if n.rsplit("/", 1)[-1] == "libzipalign.so"]
    res.check("no libzipalign.so", not bad,
              "found: " + ", ".join(bad) if bad else "none")


def main():
    parser = argparse.ArgumentParser(description="Static checks for an HTML2APK APK")
    parser.add_argument("apk")
    parser.add_argument("--expect-package", default=None)
    parser.add_argument("--aapt2", default=None, help="runnable aapt2 (skip auto-setup)")
    args = parser.parse_args()

    apk = os.path.abspath(args.apk)
    res = Result()
    print("=== verify-apk: %s ===" % apk)
    if not os.path.isfile(apk):
        print("[FAIL] APK exists — not found: %s" % apk)
        return 1
    print("size: %d bytes" % os.path.getsize(apk))

    try:
        zf = zipfile.ZipFile(apk)
    except Exception as exc:
        print("[FAIL] APK is a readable zip — %s" % exc)
        return 1

    with zf:
        aapt2 = find_aapt2(args.aapt2)
        check_package_label(res, apk, aapt2, args.expect_package)
        check_assets(res, zf)
        check_dex(res, zf)
        check_arsc(res, apk, zf)
        check_signature(res, apk)
        check_native(res, zf)

    print("=== summary: %d passed, %d failed, %d warning(s) ==="
          % (res.passed, res.failed, res.warned))
    if res.failed:
        print("RESULT: FAIL")
        return 1
    print("RESULT: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
