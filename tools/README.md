# HTML2APK local build/test harness

On-device (arm64 Android, app sandbox) build and **static** verification tools for
the HTML2APK shell APK. No adb, no Gradle, no network, no root.

| File | Purpose |
| --- | --- |
| `tools/build-shell-dex.sh` | javac every `shell-tpl/src/**/*.java` (+ generated `Shim.java`) → d8 → `shell-tpl/out/classes.dex` |
| `tools/build-test-apk.sh` | manifest → aapt2 compile/link → merge dex (+ engine lib) → 4-byte align → sign → verify → sha256 |
| `tools/prepare-aapt2.sh` | copies the PIE aapt2 + its runtime libs to an app-private exec dir (internal helper) |
| `tools/ZipAlign.java` | pure-Java 4-byte aligner (copied verbatim from the reference implementation) |
| `tests/verify-apk.py` | static PASS/FAIL checks on a built APK |
| `tests/fixtures/*` | self-contained HTML fixtures for the reported complaints |

## Quick start

```bash
cd /storage/emulated/0/WORK/01-开发项目/html2apk

# 1. build the shell dex (must be done after shell-tpl source changes)
bash tools/build-shell-dex.sh

# 2. build one fixture APK (res is shared; pkg / assets / res / out are all args)
bash tools/build-test-apk.sh \
  com.html2apk.fixture.savejson \
  tests/fixtures/save-json \
  shell-tpl/test/res \
  tests/out/com.html2apk.fixture.savejson.apk

# 3. static verification
python3 tests/verify-apk.py tests/out/com.html2apk.fixture.savejson.apk
```

The build prints the APK path, size, sha256 and a `BUILD_OK <path> <sha256>` line.
`verify-apk.py` prints one `[PASS]`/`[FAIL]` line per check and exits non-zero if
anything failed.

> `/storage` does not preserve executable bits on these files (they show `-rw`),
> so always invoke them as `bash tools/...` / `python3 tests/...` — as shown
> below — rather than `./tools/...`.

## Tool paths / environment overrides

All paths have verified defaults; override only when needed.

| Env var | Default |
| --- | --- |
| `H2A_JDK` | `/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-17-openjdk` |
| `H2A_ANDROID_JAR` | `/storage/emulated/0/WORK/02-工具链/android-sdk/platforms/android-36/android.jar` |
| `H2A_D8` | `/storage/emulated/0/WORK/02-工具链/android-sdk/build-tools/35.0.0/d8` |
| `H2A_APKSIGNER_JAR` | `/storage/emulated/0/WORK/02-工具链/android-sdk/build-tools/35.0.0/lib/apksigner.jar` |
| `H2A_KEYSTORE` | `/storage/emulated/0/WORK/01-开发项目/dsh-mobile-apk/keystore/debug.keystore` |
| `H2A_KEYSTORE_PASS` / `H2A_KEY_PASS` | `android` |
| `H2A_KEY_ALIAS` | `androiddebugkey` |
| `H2A_SHELL_SRC` / `H2A_SHELL_OUT` | `shell-tpl/src` / `shell-tpl/out` |
| `H2A_SHELL_GEN_DIR` | `tests/.build/shell-gen` (generated `Shim.java`) |
| `H2A_SHELL_DEX` | `shell-tpl/out/classes.dex` |
| `H2A_MANIFEST_TPL` | `shell-tpl/AndroidManifest.tpl.xml` |
| `H2A_AAPT2` | `app/src/main/jniLibs/arm64-v8a/libaapt2.so` (copied, chmod +x, run from app data) |
| `H2A_AAPT2_LIBS` | `app/src/main/assets/engine/lib` |
| `H2A_EXEC_DIR` | `/data/data/com.dsharnessmobile.shell/files/usr/tmp/h2a-build-tools` |
| `H2A_ENGINE_LIB` | `app/src/main/jniLibs/arm64-v8a/libaapt2.so`; set `none` to omit it |
| `H2A_APK_BUILD_DIR` | `tests/.build/apk-<pkg>` |
| `H2A_TEST_OUT` | `tests/out` |
| `H2A_VERSION_CODE` / `H2A_VERSION_NAME` | `1` / `1.0` |
| `H2A_MIN_SDK` / `H2A_TARGET_SDK` | `24` / `33` |
| `H2A_DEX_MODE` | `auto` (`auto` rebuilds if sources are newer; `always`; `never`) |

Notes:

* `d8` is a bash launcher: `JAVA_HOME` **and** `PATH` must point at JDK17. The
  scripts do this for you.
* `/storage` is noexec for ELF binaries. `prepare-aapt2.sh` copies the PIE
  `libaapt2.so` and its runtime `.so` files into an app-private directory,
  `chmod 755`s them and validates `aapt2 version` before use. The old
  `toolchain/sdk-tools-aarch64` aapt2/zipalign are non-PIE `ET_EXEC` and cannot
  run on Android 10+, so they are not used.
* `zipalign` does not work here. Alignment is done by compiling
  `tools/ZipAlign.java` on first use into `tests/.build/zipalign-classes` and
  running it with JDK17 (4-byte alignment for all STORED entries).
* `dexdump` in the old toolchain is also non-PIE (`unexpected e_type: 2`), so
  `build-shell-dex.sh` parses the dex with a small pure-python reader (magic,
  version, class count, class descriptor list) instead.
* The fixture pipeline packs `lib/arm64-v8a/libaapt2.so` (STORED) so the APK
  matches the builder packaging and passes the native-lib static check. This is
  not needed at runtime by the shell; use `H2A_ENGINE_LIB=none` to omit it
  (the `libaapt2.so` verify check will then fail by design).
* `AndroidManifest.tpl.xml` placeholders are `__PACKAGE__`, `__VERSION_CODE__`,
  `__VERSION_NAME__`. Any other `__[A-Z0-9_]+__` is reported and the build stops
  loudly, so a new placeholder does not silently leak into a manifest.
* If `<res-dir>` does not exist, a minimal res is generated under the build dir:
  `values/strings.xml` (`app_name` = `H2A Test`) + `mipmap/ic_launcher.png`.
* `shell-tpl` may be edited by another agent in parallel. `build-shell-dex.sh`
  compiles **all** `*.java` under `src`; if the product shell embeds
  `shim.js` via `gen-shim.py`, it generates `Shim.java` into
  `tests/.build/shell-gen/` and compiles that too (never writes into
  `shell-tpl/src`). It only swaps in the new `classes.dex` on success, so a
  mid-edit failure keeps the previous good dex. If an auto-rebuild fails,
  `build-test-apk.sh` prints a warning and falls back to the existing dex; run
  `bash tools/build-shell-dex.sh` yourself once the source compiles.

## Fixture APK commands

Package names avoid `-`; the asset directory names keep it.

```bash
build() { bash tools/build-test-apk.sh "$1" "tests/fixtures/$2" shell-tpl/test/res "tests/out/$1.apk"; }

build com.html2apk.fixture.savejson    save-json
build com.html2apk.fixture.file_input  file-input
build com.html2apk.fixture.big_storage big-storage
build com.html2apk.fixture.esm         esm
build com.html2apk.fixture.nested      nested   # has assets/ht2a.config -> entry dist/index.html
build com.html2apk.fixture.blank       blank    # see auto-res note below

# shared generated-res example: pass a path that does not exist
bash tools/build-test-apk.sh com.html2apk.fixture.blank \
  tests/fixtures/blank tests/.build/no-such-res tests/out/com.html2apk.fixture.blank.apk
```

Verify all six:

```bash
for p in savejson file_input big_storage esm nested blank; do
  python3 tests/verify-apk.py "tests/out/com.html2apk.fixture.$p.apk" || exit 1
done
```

`verify-apk.py` checks:

1. `aapt2 dump badging` package name (and optional `--expect-package NAME`);
2. resolved `application-label` is non-empty;
3. at least one `assets/` entry; if `assets/ht2a.config` exists, its
   `entryFile=` target exists inside `assets/`;
4. `classes.dex` exists, has a valid `dex\n03x` magic and contains
   `com/html2apk/shell/MainActivity`;
5. `resources.arsc` is STORED and its data offset is 4-byte aligned;
6. `apksigner verify`: v2 **and** v3 both true;
7. `lib/arm64-v8a/libaapt2.so` exists, is ELF64 little-endian AArch64 ET_DYN;
8. no `libzipalign.so` anywhere in the APK.

## Manual test checklist (after installing)

> **Install limitation:** this sandbox has no adb and no install permission for
> another app. Everything above is **static** verification. To run the pages,
> install the APK from a host with `adb install -r <apk>`, or copy it to the
> device's Download folder and open it with the system file manager (allow
> "install unknown apps" for that file manager). All APKs are debug-signed with
> the repository debug keystore; uninstall an older package if the signature
> differs.

Each fixture page (except `blank`) shows a huge status — `PASS`/`FAIL`, or
`未选择` before a file is chosen — plus details, so it can be checked from a
screenshot.

### save-json — `com.html2apk.fixture.savejson`

* On load: big **PASS**, payload ≈ 200–260 KB, blob URL created, `<a download>`
  auto-triggered. The current shell's injected shim intercepts anchor clicks and
  saves through the native bridge, so the file should land in
  `Download/HTML2APK/`.
* Tap button ①: same path again. Check `Download/HTML2APK/ht2a-save-json.json`
  (or the Download notification) and confirm it is valid JSON.
* Tap button ②: the current shim provides a `showSaveFilePicker` polyfill; pick
  a name/location, confirm the JSON is written. (If the API is genuinely absent
  the page says so and stays PASS.)
* Tap button ③: the current shell exposes
  `HT2A.saveBase64(name, mime, base64)`. The page first calls the 3-argument
  form, then falls back to `(name, base64)`; details show the arity, return
  value or exception. Confirm the saved file.
* `FAIL` + detail means blob/URL.download/bridge threw — screenshot it.

### file-input — `com.html2apk.fixture.file_input`

* Initial state: big **未选择** (not PASS/FAIL) and explanation.
* Tap the picker, select one or more `.json`/`.txt` files: card list shows
  name, byte size, MIME type, lastModified and the first 160 chars; status
  becomes **PASS** with the file count and total bytes.
* Re-open the picker and cancel: remains/returns to **未选择**.
* `FAIL` means at least one `FileReader` read failed; the failing file card and
  log show the exception.
* Note: no file is bundled, so this page is only meaningful interactively.

### big-storage — `com.html2apk.fixture.big_storage`

* First open (nothing stored): **未写入**, details show `big=null`,
  pattern = 8,388,608 chars and its FNV-1a checksum.
* Tap "写入 8 MiB":
  * **PASS** = `localStorage` accepted 8 MiB, read-back length and checksum
    match, `small=ok`. With the current shim (Storage.prototype patched onto a
    native file-backed store) this is the expected result — the old 5 MB quota
    is bypassed.
  * **FAIL** with `QuotaExceededError` (or another DOMException) = the old
    failure path / regression; the exact exception name/message is displayed.
* Tap "重新读取并校验" to re-check persisted data; "清除" returns to **未写入**.
* This intentionally blocks the JS thread briefly while hashing 8 MiB.

### esm — `com.html2apk.fixture.esm`

* **PASS** = `type=module` `app.js` loaded, `fetch('./data.json')` succeeded,
  message/items/elapsed shown. The current shell serves assets from
  `https://appassets.androidplatform.net`, so ESM + fetch should work.
* **FAIL** + detail = module blocked / MIME wrong / fetch blocked. A regression
  to `file://` (opaque origin / unsupported scheme) reproduces the original
  complaint.
* The page has a 6-second watchdog that prints **FAIL** if the module never
  calls back.

### nested — `com.html2apk.fixture.nested`

* The packaged assets are:
  `assets/ht2a.config` (entryFile=dist/index.html), `assets/dist/index.html`,
  `assets/dist/nested.txt` — the entry is **not** at the assets root.
* **PASS** = URL path ends with `/dist/index.html` and `fetch('./nested.txt')`
  returns the expected text. Details print `location.href` and the fetched
  text.
* A **FAIL** on the path means the shell ignored `entryFile` and loaded a root
  `index.html` (there is none), or the config was not read.

### blank — `com.html2apk.fixture.blank`

* Deliberately **no** status UI and an **empty body**: this page throws both a
  synchronous `TypeError` and a delayed uncaught `Error` with message
  `HT2A_INTENTIONAL_UNCAUGHT`.
* Expected: the content stays blank (dark navy). The current shim's blank-page
  detector may add a red "空白页面" diagnostic banner after ~1.8 s (and, in
  debug mode, an error banner); that is expected — it is the shell diagnosing
  the intentional error, not a crash.
* The app must not crash or white-screen the shell; pressing Back / reopening
  the app still works.
* Inspect the errors through the WebView console (`chrome://inspect` from a
  host) or logcat. This fixture intentionally breaks the "big status" rule
  because its purpose is the uncaught-error path.

## Troubleshooting

* `[build-shell-dex] ERROR: javac failed` while another agent edits
  `shell-tpl/src`: wait for the edit to settle, then re-run. If `AssetServer.java`
  references `Shim`, `shell-tpl/gen-shim.py` + `shim.js` must both exist (the
  script generates `Shim.java` from them); otherwise wait for the shell author.
  The previous good `classes.dex` is preserved on failure.
* `aapt2 is not runnable`: check `H2A_AAPT2` and `H2A_AAPT2_LIBS`; the exec dir
  must be app-private (not `/storage`) and writable.
* `apksigner verify failed`: don't edit the APK after signing; re-run the build
  (alignment must happen before signing).
* `resources.arsc ... not 4-byte aligned`: ensure the Java `ZipAlign` step ran
  and that the APK was not re-zipped with a normal zip tool afterwards.
