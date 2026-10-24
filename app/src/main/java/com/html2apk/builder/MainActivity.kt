package com.html2apk.builder

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.html2apk.builder.engine.BuildConfig2
import com.html2apk.builder.engine.BuildEngine
import com.html2apk.builder.engine.EngineManager
import com.html2apk.builder.engine.ManifestGenerator
import com.html2apk.builder.file.FileService
import com.html2apk.builder.install.ApkInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var engineManager: EngineManager
    private var lastApkPath: String? = null
    private var lastPublicPath: String? = null
    private var isBuilding = false
    private var pendingPickCallback: String? = null

    companion object {
        private const val REQ_PICK_HTML = 1001
        private const val REQ_PICK_FOLDER = 1002
        private const val REQ_INSTALL_PERM = 1003
        private const val REQ_PICK_ICON = 1004
        private const val REQ_STORAGE_PERM = 1005
        private const val DEFAULT_CALLBACK = "onPickResult"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        engineManager = EngineManager(this)

        webView = WebView(this)
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = true
        s.allowFileAccessFromFileURLs = true
        s.allowUniversalAccessFromFileURLs = true
        s.setSupportZoom(false)
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                val url = request.url?.toString() ?: return false
                return !(url.startsWith("file:///android_asset/ui/") || url.startsWith("about:"))
            }

            @Deprecated("deprecated in API 24")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return !(url.startsWith("file:///android_asset/ui/") || url.startsWith("about:"))
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(Bridge(), "html2apk")
        setContentView(webView)
        // UI 资产升级后清除 WebView 缓存，避免加载旧版界面
        webView.clearCache(true)
        webView.loadUrl("file:///android_asset/ui/index.html")

        // API 24-28 需要写公共下载目录的权限；29+ 走 MediaStore 不需要
        if (Build.VERSION.SDK_INT <= 28 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                requestPermissions(
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE_PERM
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE_PERM) {
            if (grantResults.isEmpty() ||
                grantResults[0] != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                toastMsg("未授予存储权限：APK 会保存在应用内，仍可直接安装")
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 选择文件 / 文件夹                                                    */
    /* ------------------------------------------------------------------ */

    private fun safeCallback(name: String?): String {
        return if (name != null && name.matches(Regex("^[A-Za-z_$][A-Za-z0-9_$]*$"))) name else DEFAULT_CALLBACK
    }

    private fun handlePick(uri: Uri, kind: String, callback: String?) {
        toastMsg(if (kind == "folder") "正在读取文件夹…" else "正在读取文件…")
        Thread {
            val copy = try {
                if (kind == "folder") FileService.copyTreeToBuildDir(this, uri)
                else FileService.copySingleToBuildDir(this, uri)
            } catch (e: Exception) {
                FileService.CopyResult(null, error = "读取失败: ${e.message}")
            }
            val path = copy.path
            var meta = ""
            if (!path.isNullOrBlank()) {
                val info = try {
                    FileService.describe(this, path, kind)
                } catch (e: Exception) {
                    FileService.PickInfo(path, kind, emptyList(), "index.html", listOf("扫描入口失败: ${e.message}"))
                }
                val warnings = ArrayList(info.warnings)
                if (copy.error.isNotBlank()) warnings.add(copy.error)
                meta = try {
                    val o = JSONObject()
                    o.put("path", path)
                    o.put("kind", kind)
                    o.put("defaultEntry", info.defaultEntry)
                    o.put("htmlFiles", JSONArray(info.htmlFiles))
                    o.put("warnings", JSONArray(warnings))
                    o.toString()
                } catch (e: Exception) {
                    ""
                }
            }
            val err = copy.error
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (!path.isNullOrBlank()) {
                    val cb = safeCallback(callback)
                    evalJs("window.$cb && window.$cb(${JSONObject.quote(path)}, ${JSONObject.quote(meta)})")
                } else {
                    if (err.isNotBlank()) toastMsg(err)
                    val cb = safeCallback(callback)
                    evalJs("window.$cb && window.$cb('')")
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INSTALL_PERM) return
        val cb = pendingPickCallback
        pendingPickCallback = null
        if (resultCode != RESULT_OK || data?.data == null) {
            if (cb != null) evalJs("window.$cb && window.$cb('')")
            return
        }
        val uri = data.data!!
        when (requestCode) {
            REQ_PICK_HTML -> handlePick(uri, "file", cb)
            REQ_PICK_FOLDER -> handlePick(uri, "folder", cb)
            REQ_PICK_ICON -> {
                val iconPath = FileService.copyIconToBuildDir(this, uri)
                val preview = iconPath?.let { ManifestGenerator.makePreviewDataUri(it) }
                if (cb != null) {
                    evalJs("window.$cb && window.$cb(${JSONObject.quote(iconPath ?: "")}, ${JSONObject.quote(preview ?: "")})")
                }
            }
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        try {
            if (::webView.isInitialized) webView.destroy()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    /** JS 桥：window.html2apk.* */
    inner class Bridge {
        @JavascriptInterface
        fun pickHtmlFile(callback: String) {
            runOnUiThread {
                pendingPickCallback = safeCallback(callback)
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(
                        Intent.EXTRA_MIME_TYPES,
                        arrayOf("text/html", "application/xhtml+xml", "text/plain", "application/octet-stream")
                    )
                }
                try {
                    startActivityForResult(intent, REQ_PICK_HTML)
                } catch (e: Exception) {
                    pendingPickCallback = null
                    toastMsg("无法打开文件选择器: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun pickFolder(callback: String) {
            runOnUiThread {
                pendingPickCallback = safeCallback(callback)
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    putExtra("android.content.extra.SHOW_ADVANCED", true)
                }
                try {
                    startActivityForResult(intent, REQ_PICK_FOLDER)
                } catch (e: Exception) {
                    pendingPickCallback = null
                    toastMsg("无法打开文件夹选择器: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun pickIcon(callback: String) {
            runOnUiThread {
                pendingPickCallback = safeCallback(callback)
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*"))
                }
                try {
                    startActivityForResult(intent, REQ_PICK_ICON)
                } catch (e: Exception) {
                    pendingPickCallback = null
                    toastMsg("无法打开图片选择器: ${e.message}")
                }
            }
        }

        @JavascriptInterface
        fun startBuild(configJson: String) {
            if (isBuilding) {
                toastMsg("正在构建中，请稍候")
                return
            }
            val cfg = try {
                BuildConfig2.fromJson(configJson)
            } catch (e: Exception) {
                toastMsg("配置解析失败: ${e.message}")
                return
            }
            if (cfg.appName.isBlank()) {
                toastMsg("请填写应用名称")
                return
            }
            if (cfg.appName.length > 60) {
                toastMsg("应用名称太长（最多 60 字）")
                return
            }
            if (!cfg.packageName.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$"))) {
                toastMsg("包名格式不正确（如 com.example.app）")
                return
            }
            if (cfg.inputDir.isBlank() || !File(cfg.inputDir).exists()) {
                toastMsg("输入文件已失效，请重新选择 HTML/文件夹")
                return
            }
            if (cfg.entryFile.contains("..")) {
                toastMsg("入口路径不合法")
                return
            }
            isBuilding = true
            Thread {
                val result = BuildEngine.build(this@MainActivity, engineManager, cfg) { step, msg ->
                    runOnUiThread {
                        evalJs(
                            "window.onBuildProgress && window.onBuildProgress(" +
                                "${JSONObject.quote(step)}, ${JSONObject.quote(msg)})"
                        )
                    }
                }
                runOnUiThread {
                    isBuilding = false
                    when (result) {
                        is BuildEngine.Result.Success -> {
                            lastApkPath = result.apkPath
                            lastPublicPath = result.publicPath
                            val o = JSONObject()
                            o.put("apkPath", result.apkPath)
                            o.put("sizeLabel", result.sizeLabel)
                            o.put("publicPath", result.publicPath)
                            o.put("publicUri", result.publicUri ?: "")
                            o.put("warning", result.warning)
                            o.put("durationMs", result.durationMs)
                            o.put("logPath", result.logPath)
                            evalJs(
                                "window.onBuildDone && window.onBuildDone(${JSONObject.quote(o.toString())})"
                            )
                        }
                        is BuildEngine.Result.Failure -> {
                            val d = JSONObject()
                            d.put("hint", result.hint)
                            d.put("log", result.log)
                            d.put("logPath", result.logPath ?: "")
                            evalJs(
                                "window.onBuildError && window.onBuildError(" +
                                    "${JSONObject.quote(result.message)}, ${JSONObject.quote(d.toString())})"
                            )
                        }
                    }
                }
            }.start()
        }

        @JavascriptInterface
        fun installApk() {
            val path = lastApkPath ?: run {
                toastMsg("还没有可安装的 APK")
                return
            }
            runOnUiThread {
                if (!File(path).isFile) {
                    toastMsg("APK 文件已不存在，请重新构建")
                    return@runOnUiThread
                }
                if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                    toastMsg("请先允许安装未知应用")
                    try {
                        startActivityForResult(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")),
                            REQ_INSTALL_PERM
                        )
                    } catch (e: Exception) {
                        toastMsg("无法打开安装权限设置")
                    }
                    return@runOnUiThread
                }
                val ok = ApkInstaller.install(this@MainActivity, path)
                if (!ok) toastMsg("安装失败，APK 位于: $path")
            }
        }

        @JavascriptInterface
        fun openApkFolder() {
            runOnUiThread {
                lastPublicPath?.let { toastMsg(it) }
                ApkInstaller.openDownloads(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun getEngineStatus(): String {
            return try {
                val st = engineManager.status()
                val o = JSONObject()
                o.put("ready", st.ready)
                o.put("aapt2Version", st.aapt2Version)
                o.put("libCount", st.libCount)
                o.put("engineVersion", st.engineVersion)
                o.put("error", st.error)
                o.put("hint", st.hint)
                o.put("dir", engineManager.engineDir.absolutePath)
                o.toString()
            } catch (e: Exception) {
                "{\"ready\":false,\"error\":${JSONObject.quote(e.message ?: "引擎检查异常")}}"
            }
        }

        @JavascriptInterface
        fun getDownloadDir(): String {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .absolutePath + "/HTML2APK"
        }

        @JavascriptInterface
        fun copyText(text: String) {
            val s = text
            runOnUiThread {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("HTML2APK", s))
                    Toast.makeText(this@MainActivity, "已复制", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    toastMsg("复制失败")
                }
            }
        }

        @JavascriptInterface
        fun toast(msg: String) {
            toastMsg(msg)
        }
    }

    private fun evalJs(js: String) {
        runOnUiThread {
            try {
                if (::webView.isInitialized) webView.evaluateJavascript(js, null)
            } catch (_: Exception) {
            }
        }
    }

    private fun toastMsg(msg: String) {
        runOnUiThread {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }
}
