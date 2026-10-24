# HTML2APK v1.2.0 — 手机上把 HTML 打包成 APK 的工具

> 在 Android 设备上**完全离线**地把单个 HTML 文件或整个网页文件夹打包成可安装的 APK。
> 无需电脑、无需 Termux、无需云端构建。

这一版集中修复了用户反馈最多的 5 类问题：

1. **打包后保存 JSON / 文件没反应**（blob、data URL、`showSaveFilePicker`）；
2. **输入数据后刷新就丢**，大项目（11MB HTML、8MB localStorage）尤其明显；
3. **`<input type="file">` 点选文件没反应**；
4. **打包成功但安装后白屏**（ES Module / fetch / 子目录入口 / 渲染崩溃）；
5. **一直构建失败**（旧引擎 aapt2/zipalign 在 Android 10+ 无法执行；Android 11+ 写下载目录失败）。

## 功能

- 📄 **两种输入**：单个 HTML 文件 / 文件夹（含 CSS、JS、图片、子目录，保持相对结构）
- 🎯 **入口文件选择**：文件夹里有多个 HTML 时自动列出，默认根目录 `index.html`，可手动切换
- 🎨 **自定义封面图标**：超大图片自动降采样，不会再把构建拖垮
- 🧱 **自包含构建引擎**：PIE `aapt2`（含运行时依赖库）+ 纯 Kotlin zipalign + apksig 签名库 + 固定壳 `classes.dex`
- 💾 **产物保存**：`Download/HTML2APK/<应用名>-v<版本>.apk`（Android 10+ 走 MediaStore，无需存储权限）
- 🚀 **一键安装**：产物支持直接拉起系统安装器
- 🧪 **自检 APK**：`tests/out/html2apk-selftest.apk` 可逐项验证下列能力

## 产物壳（生成的 App）新增能力

| 能力 | 说明 |
|---|---|
| 下载 / 保存文件 | 拦截 `<a download>`、`blob:`、`data:`；分块（256KB）写入 `Download/HTML2APK`，不受 1MB Binder 限制；`showSaveFilePicker` 也做了 polyfill |
| 大容量 localStorage | `Storage.prototype` 代理到原生落盘存储，突破 WebView 5–10MB 配额；首次启动自动迁移旧数据 |
| 文件选择 | `WebChromeClient.onShowFileChooser` + `parseResult`，支持多选与 accept 类型；`showOpenFilePicker` 也可用 |
| 现代网页 | 页面通过虚拟 HTTPS origin（`https://appassets.androidplatform.net`）加载，ES Module / fetch / IndexedDB / 安全上下文都正常，**不再用 `file://`** |
| 子目录入口 | `ht2a.config` 里的 `entryFile` 是相对路径（如 `dist/index.html`），不再只取文件名 |
| 刷新不丢输入 | 刷新时自动恢复表单值（同一进程内，基于 session 存储；重启 App 清空） |
| 白屏诊断 | 页面加载失败 / 404 / 渲染进程崩溃会显示可复制的错误页；调试模式下脚本错误会显示红色横幅 |
| 状态栏配色 | 根据背景明暗自动选择深/浅色图标（旧版固定深色图标，在深色背景上看不见） |

## 架构

```
┌─ HTML2APK App（com.html2apk.builder）───────────────────┐
│ WebView UI（assets/ui/） ↕ window.html2apk.* JS Bridge  │
│ 原生层：                                                │
│  · FileService    SAF 选择 → work/input + HTML 入口扫描  │
│  · BuildEngine    preflight → aapt2 compile/link →       │
│                   mergeDex → ZipAligner → apksig →      │
│                   MediaStore 发布 → Download/HTML2APK    │
│  · EngineManager  PIE aapt2 + .so 依赖解压/验证           │
└─────────────────────────────────────────────────────────┘
构建产物（每个 APK）：
  assets/ 用户页面 + ht2a.config
  classes.dex  固定壳（MainActivity / AssetServer / Storage /
               FileSaver / Config / Shim）
```

### 关键设计（踩坑记录）

| 问题 | 方案 |
|---|---|
| **Android 10+ 拒绝执行非 PIE ELF**（旧 aapt2/zipalign 报 `unexpected e_type: 2`） | 换用 Termux 构建的 **PIE（ET_DYN）aapt2**；zipalign 彻底移除 |
| aapt2 动态依赖（libprotobuf/absl/libc++_shared/libz/libexpat/libpng/libfmt） | 打包进 `assets/engine/lib/`，首次运行解压到 `filesDir/engine/lib`，子进程用 `LD_LIBRARY_PATH` 加载 |
| Android 15/16 禁止从 app data 执行 ELF | aapt2 放 `jniLibs` → 安装后位于 `applicationInfo.nativeLibraryDir`；`extractNativeLibs=true` |
| Android 11+ 写公共 `Download/` 失败（Scoped Storage） | 构建产物先落 `filesDir/out`（可安装），再用 MediaStore 发布；发布失败不影响安装 |
| targetSdk 30+ 要求 `resources.arsc` 不压缩且 4 字节对齐 | link `-0 arsc`；mergeDex 写 STORED；纯 Kotlin ZipAligner 精确补齐 extra 字段 |
| 旧壳用 `file://` 加载：ES Module / fetch / IndexedDB 均被 CORS/opaque origin 挡住 | 自建 `shouldInterceptRequest` 资源服务器，虚拟 HTTPS origin，字节级注入 shim |
| 旧壳 `ht2a.config` 只写文件名，子目录入口 404 → 白屏 | `entryFile` 写相对路径；构建时扫描候选并由 UI 选择 |
| WebView localStorage 配额约 5–10MB | `Storage.prototype` 方法代理到原生文件存储（每个 key 单独文件 + fsync） |
| blob 下载 `DownloadListener` 拿不到内容 | 注入 JS：拦截 anchor click / `window.open` / `showSaveFilePicker`，分块过桥写 MediaStore |
| 大图标 decode OOM 导致构建失败 | 先读 bounds，按 1024px 降采样；异常回退默认图标 |
| 应用名/版本号含 `&`、引号导致 aapt2 失败 | manifest/strings.xml 分别做 XML / Android string 转义 |
| `pm install -r` 后残留旧引擎资产 | `assets/engine/VERSION` 不一致时全量刷新（当前 v8） |
| 内置 keystore 的 PKCS12 在部分 ROM 不可用 | 打包 PKCS8 DER 私钥 + X.509 DER 证书，运行时直接构建 |

## 目录结构

```
html2apk/
├── app/                           # App 本体（Kotlin，AGP 8.8.2，minSdk 26 / target 34）
│   ├── src/main/
│   │   ├── java/com/html2apk/builder/
│   │   │   ├── MainActivity.kt          # WebView 壳 + JS Bridge
│   │   │   ├── engine/
│   │   │   │   ├── EngineManager.kt     # 引擎资产/PIE aapt2 执行/LD_LIBRARY_PATH
│   │   │   │   ├── BuildEngine.kt       # 构建流水线 + 日志 + MediaStore 发布
│   │   │   │   ├── ZipAligner.kt        # 纯 Java/Kotlin zipalign
│   │   │   │   ├── ManifestGenerator.kt # manifest/res/图标
│   │   │   │   └── ApkSignerRunner.kt   # apksig v2/v3
│   │   │   ├── file/FileService.kt      # SAF 拷贝 + 入口扫描
│   │   │   └── install/ApkInstaller.kt
│   │   ├── assets/
│   │   │   ├── ui/                      # 工业复古控制台 UI
│   │   │   └── engine/                  # android.jar / template.dex / lib/*.so / VERSION
│   │   ├── jniLibs/arm64-v8a/libaapt2.so
│   │   └── res/raw/release_{key,cert}
│   └── libs/apksig-8.5.2.jar
├── shell-tpl/                     # 产物壳（Java 8）
│   ├── src/com/html2apk/shell/
│   │   ├── MainActivity.java      # WebView/下载/文件选择/错误页/全屏视频
│   │   ├── AssetServer.java       # 虚拟 HTTPS 资源服务器 + shim 注入
│   │   ├── Storage.java           # 大容量 localStorage 后端
│   │   ├── FileSaver.java         # 分块写 MediaStore / 旧版下载目录
│   │   ├── Config.java            # ht2a.config
│   │   └── shim.js                # 注入脚本（下载/存储/表单/诊断）
│   ├── gen-shim.py                # shim.js → Shim.java（构建时生成）
│   └── build-dex.sh               # javac + d8
├── tools/                         # 本地构建/测试脚本（无需 adb）
│   ├── build-shell-dex.sh
│   ├── build-test-apk.sh
│   ├── prepare-aapt2.sh
│   └── ZipAlign.java
├── tests/
│   ├── fixtures/                  # save-json / file-input / big-storage / esm / nested / blank
│   ├── fixtures/selftest/         # 汇总自检页（index.html）
│   ├── shim-test.js               # Node 单测：存储/下载/FS API/表单恢复
│   ├── verify-apk.py              # APK 静态校验
│   └── out/html2apk-selftest.apk  # 可安装自检 APK
├── build-tools/collect-engine.sh  # 采集引擎资产（开发期）
└── README.md
```

## 开发期构建

在本机（已装 JDK17 / Gradle / Android SDK）上：

```bash
cd /storage/emulated/0/WORK/01-开发项目/html2apk

# 1) 重新生成产物壳 classes.dex（改了 shell-tpl 后必须）
JAVA_HOME=/data/data/com.dsharnessmobile.shell/files/usr/lib/jvm/java-17-openjdk \
  bash shell-tpl/build-dex.sh \
  /storage/emulated/0/WORK/02-工具链/android-sdk/platforms/android-36/android.jar \
  /storage/emulated/0/WORK/02-工具链/android-sdk/build-tools/35.0.0/d8

# 2) 同步到引擎资产，并把 VERSION +1
cp shell-tpl/out/classes.dex app/src/main/assets/engine/template.dex
printf '8\n' > app/src/main/assets/engine/VERSION

# 3) 构建 App 本体（aapt2 必须是 PIE，且文件名必须以 aapt2 结尾；
#    LD_LIBRARY_PATH 指向 aapt2 依赖库）
JAVA_HOME=... GRADLE_USER_HOME=... LD_LIBRARY_PATH=... \
  bash .../gradle-8.11.1/bin/gradle --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/path/to/aapt2 \
  :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

> 本仓库的开发环境还提供了 `tools/build-shell-dex.sh`（自动生成 Shim.java）和
> `tools/build-test-apk.sh`（不依赖 Gradle/adb，直接产出并签名测试 APK）。

## 测试

```bash
# 产物壳 JS 逻辑单测（Node，无需设备）
node tests/shim-test.js

# 构建一个测试产物 APK（含新壳 + 指定 assets）
bash tools/build-test-apk.sh com.html2apk.selftest \
  tests/fixtures/selftest shell-tpl/test/res tests/out/html2apk-selftest.apk

# 静态校验：包名/入口/arsc 对齐/签名/壳 dex/aapt2 ET_DYN
python3 tests/verify-apk.py tests/out/html2apk-selftest.apk
```

## 使用

1. 安装新的 `app/build/outputs/apk/release/app-release.apk`（或 debug 版）。
2. 选择「HTML 文件」或「文件夹」；多个 HTML 时在「入口文件」里确认入口。
3. 填应用名称（必填）、包名、版本；可选封面图标；高级选项可开「调试模式」。
4. 点「开始构建」→ 实时日志 → 成功后 APK 在 `Download/HTML2APK/`，也可直接安装。
5. 如果构建失败，点「复制日志」把完整日志发给开发者；日志同时保存在
   `files/out/build-last.log`（App 私有目录）和结果面板中。

### 验证历史问题是否修复

安装 `tests/out/html2apk-selftest.apk`，依次点击：

| 页面 | 预期 |
|---|---|
| 保存 JSON | 点按钮后显示已保存，文件出现在 `Download/HTML2APK/` |
| 文件选择 | 点按钮弹出系统文件选择器，选完显示文件名/大小/内容 |
| 8MB 存储 | 写入 8MB → 刷新 → 仍显示 PASS（旧壳会 QuotaExceeded） |
| ES Module | 显示 module + fetch 均 OK（旧壳白屏） |
| 子目录入口 | 显示 nested entry ok（旧壳 404 白屏） |
| 空白页 | 显示“空白页面”诊断横幅，可关闭 |

## 已知限制

- **仅 arm64 设备**：引擎 aapt2 只有 arm64-v8a 版本。
- **localStorage 直接属性访问**：`localStorage.setItem/getItem` 已无配额；但
  `localStorage.foo = '...'` / `localStorage['foo']` 是 WebView 的 exotic 属性，
  无法被 JS 拦截，超过 5MB 时仍可能失败。建议页面改用
  `localStorage.setItem('foo', ...)` 或 `HT2A_WEB.storage.set('foo', ...)`。
- 页面若注册 **Service Worker**，其请求可能绕过资源拦截；当前不保证可用。
- `webkitdirectory`（选择整个目录）WebView 不支持，会退化为普通文件选择。
  `showOpenFilePicker` 单文件上限 32MB（过桥 base64 的内存保护）。
- 默认内置签名密钥仅适合个人分发；自定义密钥导入尚未实现。
- 产物 minSdk 24（Android 7.0+）；构建器 minSdk 26。
- 自检 APK 与测试夹具使用 debug 签名，仅用于验证。

## 相关文档

- `docs/用户反馈修复说明-v1.2.md`：逐条回应用户评论（可直接发给用户）。
- `tools/README.md`：本地测试脚本与手动验收清单。
