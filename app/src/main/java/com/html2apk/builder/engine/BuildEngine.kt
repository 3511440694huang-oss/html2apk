package com.html2apk.builder.engine

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.html2apk.builder.file.FileService
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 构建流水线（全程设备内、离线）：
 * 1. 输入预检 + 复制到 assets（保留子目录，入口用相对路径）
 * 2. 生成 manifest / res / ht2a.config
 * 3. aapt2 compile + link（PIE aapt2 + LD_LIBRARY_PATH）
 * 4. 合并壳 classes.dex
 * 5. 纯 Java zipalign（不再依赖不可执行的 zipalign 二进制）
 * 6. apksig v2/v3 签名
 * 7. 私有目录留一份 + MediaStore 发布到 Download/HTML2APK
 *
 * 任何一步失败都会落盘完整日志（out/build-last.log），并给出可操作的中文提示。
 */
object BuildEngine {

    sealed class Result {
        data class Success(
            val apkPath: String,
            val publicPath: String,
            val publicUri: String?,
            val sizeLabel: String,
            val logPath: String,
            val log: String,
            val warning: String,
            val durationMs: Long
        ) : Result()

        data class Failure(
            val message: String,
            val hint: String,
            val log: String,
            val logPath: String?
        ) : Result()
    }

    private const val TAG = "HTML2APK.Build"
    private const val MAX_LOG = 400_000

    private data class InputStats(
        val files: Int,
        val bytes: Long,
        val entry: String,
        val warnings: List<String>
    )

    private data class PublishResult(val path: String?, val uri: String?, val warning: String)

    fun build(
        ctx: Context,
        engine: EngineManager,
        cfg: BuildConfig2,
        progress: (String, String) -> Unit
    ): Result {
        val started = System.currentTimeMillis()
        val log = StringBuilder()
        val startedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        fun stamp(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        fun line(s: String) {
            log.append('[').append(stamp()).append("] ").append(s).append('\n')
            if (log.length > MAX_LOG) log.setLength(MAX_LOG)
        }
        fun step(id: String, msg: String) {
            progress(id, msg)
            line(msg)
        }
        fun fail(message: String, hint: String): Result.Failure {
            line("FAILED: $message | $hint")
            val path = saveLog(ctx, engine, log, startedAt, cfg)
            progress("error", message)
            return Result.Failure(message, hint, log.toString(), path)
        }

        try {
            line("HTML2APK build start; app=${cfg.appName}; pkg=${cfg.packageName}; entry=${cfg.entryFile}")
            line("device=${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

            step("init", "初始化构建引擎…")
            val status = engine.status(true)
            line("engine ready=${status.ready}; aapt2=${status.aapt2Version}; libs=${status.libCount}; ver=${status.engineVersion}")
            if (!status.ready) {
                return fail(
                    "构建引擎不可用：${status.error.ifBlank { "未知错误" }}",
                    status.hint.ifBlank { "请卸载后重新安装本 App，或把日志发给开发者。" }
                )
            }

            val work = engine.workDir
            work.mkdirs()
            work.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name.startsWith("build_")) {
                    try { f.deleteRecursively() } catch (_: Exception) {}
                }
            }
            val buildDir = File(work, "build_${System.currentTimeMillis()}")
            if (!buildDir.mkdirs()) return fail("无法创建构建目录", "设备存储不可写，请清理空间后重试。")
            val resDir = File(buildDir, "res")
            val assetsDir = File(buildDir, "assets")

            step("input", "读取并复制页面资源…")
            val input = File(cfg.inputDir)
            if (!input.exists()) {
                return fail("找不到已选择的输入文件：${cfg.inputDir}", "请重新选择 HTML 文件或文件夹。")
            }
            val stats = prepareInput(input, assetsDir, cfg.entryFile, log)
                ?: return fail("没有找到可打包的 HTML 入口文件", "请确认所选文件夹里包含 .html/.htm 文件，或重新选择。")
            step("input", "已复制 ${stats.files} 个文件（${formatSize(stats.bytes)}），入口：${stats.entry}")
            stats.warnings.forEach { line("WARN: $it") }
            if (stats.bytes > 0) {
                val need = stats.bytes * 2 + 64L * 1024 * 1024
                val usable = work.usableSpace
                if (usable in 1 until need) {
                    return fail("存储空间不足：需要约 ${formatSize(need)}，可用 ${formatSize(usable)}",
                        "清理设备空间后重试；超大页面建议先压缩 HTML。")
                }
            }

            // 旧版 file:// localStorage 迁移页（壳首次升级时用隐藏 WebView 读取旧数据）
            try {
                File(assetsDir, "__ht2a_migrate.html").writeText(
                    "<!doctype html><html><head><meta charset=\"utf-8\">" +
                        "<title>migrate</title></head><body>html2apk migration</body></html>",
                    Charsets.UTF_8
                )
            } catch (e: Exception) {
                line("WARN: write migration page failed: ${e.message}")
            }

            step("config", "生成清单与资源…")
            ManifestGenerator.generateManifest(engine.manifestTpl, cfg, File(buildDir, "AndroidManifest.xml"))
            ManifestGenerator.generateStringsXml(resDir, cfg.appName)
            val customIcon = try {
                ManifestGenerator.generateIconSet(resDir, cfg.iconPath)
            } catch (t: Throwable) {
                line("WARN: icon generation failed: $t")
                false
            }
            step("icon", if (customIcon) "已应用自定义封面图标" else "使用默认图标")

            val configText = buildString {
                append("entryFile=").append(stats.entry).append('\n')
                append("statusBarColor=").append(cfg.statusBarColor).append('\n')
                append("navBarColor=").append(cfg.navBarColor).append('\n')
                append("backgroundColor=").append(cfg.backgroundColor).append('\n')
                append("orientation=").append(cfg.orientation).append('\n')
                append("debug=").append(if (cfg.debug) "1" else "0").append('\n')
                append("formRestore=").append(if (cfg.formRestore) "1" else "0").append('\n')
            }
            File(assetsDir, "ht2a.config").writeText(configText, Charsets.UTF_8)
            line("ht2a.config:\n$configText")

            step("compile", "编译资源（aapt2 compile）…")
            val resZip = File(buildDir, "res.zip")
            val compileCmd = listOf(
                engine.aapt2.absolutePath, "compile",
                "--dir", resDir.absolutePath,
                "-o", resZip.absolutePath
            )
            logCommand(log, compileCmd)
            val compile = engine.exec(compileCmd, buildDir, 120_000)
            appendOutput(log, compile.output)
            if (compile.exitCode != 0 || !resZip.exists()) {
                return fail("资源编译失败（aapt2 compile 退出码 ${compile.exitCode}）",
                    hintFor(compile.output) + "\n" + tail(compile.output))
            }

            step("link", "链接 APK（aapt2 link）…")
            val unsigned = File(buildDir, "unsigned.apk")
            val linkCmd = listOf(
                engine.aapt2.absolutePath, "link",
                "-o", unsigned.absolutePath,
                "-I", engine.androidJar.absolutePath,
                "-0", "arsc",
                "--manifest", File(buildDir, "AndroidManifest.xml").absolutePath,
                resZip.absolutePath,
                "-A", assetsDir.absolutePath,
                "--min-sdk-version", "24",
                "--target-sdk-version", "33",
                "--version-code", cfg.versionCode.toString(),
                "--version-name", cfg.versionName,
                "--auto-add-overlay"
            )
            logCommand(log, linkCmd)
            val link = engine.exec(linkCmd, buildDir, 240_000)
            appendOutput(log, link.output)
            if (link.exitCode != 0 || !unsigned.exists()) {
                return fail("APK 链接失败（aapt2 link 退出码 ${link.exitCode}）",
                    hintFor(link.output) + "\n" + tail(link.output))
            }

            step("dex", "合并壳代码…")
            val withDex = File(buildDir, "withdex.apk")
            mergeDex(unsigned, engine.templateDex, withDex)
            line("merged classes.dex (${formatSize(engine.templateDex.length())})")

            step("align", "对齐 APK…")
            val aligned = File(buildDir, "aligned.apk")
            ZipAligner.align(withDex, aligned, 4)
            val bad = ZipAligner.verify(aligned, 4)
            if (bad.isNotEmpty()) {
                return fail("APK 对齐校验失败", "以下条目未对齐：${bad.take(5).joinToString("；")}")
            }

            step("sign", "签名（v2/v3）…")
            val signed = File(buildDir, "signed.apk")
            val signError = ApkSignerRunner.sign(ctx, aligned, signed)
            if (signError != null) {
                return fail("签名失败：$signError", "内置签名密钥读取失败。请重新安装本 App 后再试。")
            }

            step("output", "输出 APK…")
            engine.outDir.mkdirs()
            val safeName = fileName(cfg.appName, "app")
            val safeVer = fileName(cfg.versionName, "1.0")
            val finalApk = File(engine.outDir, "$safeName-v$safeVer.apk")
            signed.copyTo(finalApk, overwrite = true)
            val sizeLabel = formatSize(finalApk.length())

            step("publish", "复制到下载目录…")
            val pub = publish(ctx, finalApk)
            if (pub.warning.isNotBlank()) {
                line("WARN: ${pub.warning}")
            } else {
                line("published: ${pub.path}")
            }

            line("BUILD OK: $sizeLabel, ${System.currentTimeMillis() - started}ms")
            val logPath = saveLog(ctx, engine, log, startedAt, cfg)
            progress("done", "构建完成：$sizeLabel")
            return Result.Success(
                apkPath = finalApk.absolutePath,
                publicPath = pub.path ?: finalApk.absolutePath,
                publicUri = pub.uri,
                sizeLabel = sizeLabel,
                logPath = logPath ?: "",
                log = log.toString(),
                warning = pub.warning,
                durationMs = System.currentTimeMillis() - started
            )
        } catch (t: Throwable) {
            Log.e(TAG, "build failed", t)
            line("EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
            val path = saveLog(ctx, engine, log, startedAt, cfg)
            return Result.Failure(
                "构建异常：${t.javaClass.simpleName}: ${t.message ?: "未知错误"}",
                "请复制日志发给开发者。常见原因：文件名含特殊字符、输入文件被删除、存储空间不足。",
                log.toString(),
                path
            )
        }
    }

    /* ------------------------------------------------------------------ */
    /* 输入                                                                */
    /* ------------------------------------------------------------------ */

    private fun prepareInput(input: File, assetsDir: File, requested: String, log: StringBuilder): InputStats? {
        assetsDir.mkdirs()
        val warnings = ArrayList<String>()
        var files = 0
        var bytes = 0L

        if (input.isFile) {
            input.copyTo(File(assetsDir, "index.html"), overwrite = true)
            files = 1
            bytes = input.length()
            log.append("single file: ${input.name} -> assets/index.html\n")
            return InputStats(1, bytes, "index.html", warnings)
        }
        if (!input.isDirectory) {
            return null
        }

        input.walkTopDown()
            .onEnter { dir -> dir == input || !isJunkDir(dir.name) }
            .forEach { f ->
                if (f == input) return@forEach
                val rel = f.relativeTo(input).path.replace(File.separatorChar, '/')
                if (isJunk(rel)) return@forEach
                if (f.isDirectory) {
                    File(assetsDir, rel).mkdirs()
                } else if (f.isFile) {
                    val dest = File(assetsDir, rel)
                    dest.parentFile?.mkdirs()
                    f.copyTo(dest, overwrite = true)
                    files++
                    bytes += f.length()
                }
            }

        val allHtml = assetsDir.walkTopDown()
            .filter { it.isFile && FileService.isHtml(it.name) && !it.name.startsWith("__ht2a_") }
            .map { it.relativeTo(assetsDir).path.replace(File.separatorChar, '/') }
            .sortedWith(compareBy({ p -> p.count { it == '/' } }, { it.lowercase() }))
            .toList()

        if (allHtml.isEmpty()) {
            log.append("no html found under assets\n")
            return null
        }
        val requestedNorm = normalizeEntry(requested)
        val entry = when {
            requestedNorm.isNotBlank() && allHtml.contains(requestedNorm) -> requestedNorm
            allHtml.contains("index.html") -> "index.html"
            allHtml.firstOrNull { it.endsWith("/index.html") } != null ->
                allHtml.first { it.endsWith("/index.html") }
            else -> allHtml.first()
        }
        if (entry != requestedNorm) {
            log.append("entry fallback: requested=$requestedNorm -> $entry\n")
            if (requestedNorm.isNotBlank()) {
                warnings.add("找不到配置的入口 $requestedNorm，已改用 $entry。")
            }
        }
        if (allHtml.size > 1) {
            log.append("html candidates: ${allHtml.take(20).joinToString(", ")}\n")
        }
        return InputStats(files, bytes, entry, warnings)
    }

    private fun normalizeEntry(path: String): String {
        var p = path.trim().replace('\\', '/')
        while (p.startsWith("/")) p = p.substring(1)
        if (p.isEmpty()) return ""
        val parts = p.split("/")
        val out = ArrayList<String>()
        for (part in parts) {
            if (part.isEmpty() || part == ".") continue
            if (part == "..") return ""
            out.add(part)
        }
        return out.joinToString("/")
    }

    private fun isJunk(rel: String): Boolean {
        val name = rel.substringAfterLast('/').lowercase()
        return name == ".ds_store" || name == "thumbs.db" || name == "desktop.ini" || name.startsWith("._")
    }

    private fun isJunkDir(name: String): Boolean {
        val n = name.lowercase()
        return n == ".git" || n == ".svn" || n == ".hg" || n == "__macosx" ||
            n == ".idea" || n == ".vscode"
    }

    /* ------------------------------------------------------------------ */
    /* zip 合并 / 输出                                                     */
    /* ------------------------------------------------------------------ */

    private fun mergeDex(apk: File, dex: File, out: File) {
        ZipFile(apk).use { zin ->
            ZipOutputStream(out.outputStream().buffered(64 * 1024)).use { zout ->
                val entries = zin.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val ne = ZipEntry(e.name)
                    if (e.time >= 0L) ne.time = e.time
                    if (e.name == "resources.arsc") {
                        val data = zin.getInputStream(e).use { it.readBytes() }
                        ne.method = ZipEntry.STORED
                        ne.size = data.size.toLong()
                        ne.compressedSize = data.size.toLong()
                        ne.crc = CRC32().apply { update(data) }.value
                        zout.putNextEntry(ne)
                        zout.write(data)
                    } else if (e.method == ZipEntry.STORED) {
                        // 原样保留 STORED，避免二次压缩已压缩资源（图片/字体等）
                        ne.method = ZipEntry.STORED
                        ne.size = e.size
                        ne.compressedSize = e.compressedSize
                        ne.crc = e.crc
                        zout.putNextEntry(ne)
                        zin.getInputStream(e).use { it.copyTo(zout, 64 * 1024) }
                    } else {
                        ne.method = ZipEntry.DEFLATED
                        zout.putNextEntry(ne)
                        zin.getInputStream(e).use { it.copyTo(zout, 64 * 1024) }
                    }
                    zout.closeEntry()
                }
                val dext = ZipEntry("classes.dex")
                dext.time = System.currentTimeMillis()
                dext.method = ZipEntry.DEFLATED
                zout.putNextEntry(dext)
                dex.inputStream().use { it.copyTo(zout, 64 * 1024) }
                zout.closeEntry()
            }
        }
    }

    private fun publish(ctx: Context, apk: File): PublishResult {
        return try {
            if (Build.VERSION.SDK_INT >= 29) publishQ(ctx, apk) else publishLegacy(ctx, apk)
        } catch (t: Throwable) {
            Log.e(TAG, "publish failed", t)
            PublishResult(null, null, "复制到下载目录失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun publishQ(ctx: Context, apk: File): PublishResult {
        val resolver = ctx.contentResolver
        val name = apk.name
        for (i in 0..19) {
            val candidate = if (i == 0) name else withSuffix(name, i)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, candidate)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HTML2APK")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try {
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: Exception) {
                Log.w(TAG, "media insert failed", e)
                null
            } ?: continue
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    apk.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                } ?: throw IOException("无法打开输出流")
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                return PublishResult("Download/HTML2APK/$candidate", uri.toString(), "")
            } catch (e: Exception) {
                Log.w(TAG, "media write failed", e)
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
        }
        return PublishResult(null, null, "MediaStore 拒绝写入下载目录（APK 仍可从应用内安装）")
    }

    private fun publishLegacy(ctx: Context, apk: File): PublishResult {
        val granted = ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            return PublishResult(null, null, "未授予存储权限，APK 只保存在应用内（可直接安装）")
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "HTML2APK"
        )
        if (!dir.isDirectory && !dir.mkdirs()) {
            return PublishResult(null, null, "无法创建下载目录")
        }
        val dest = File(dir, apk.name)
        apk.copyTo(dest, overwrite = true)
        try {
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.absolutePath), null, null)
        } catch (_: Exception) {}
        return PublishResult(dest.absolutePath, null, "")
    }

    private fun withSuffix(name: String, i: Int): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) "${name.substring(0, dot)} ($i)${name.substring(dot)}" else "$name ($i)"
    }

    private fun fileName(value: String, fallback: String): String {
        val cleaned = value.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.')
        return cleaned.ifEmpty { fallback }.take(60)
    }

    private fun saveLog(ctx: Context, engine: EngineManager, log: StringBuilder, startedAt: String, cfg: BuildConfig2): String? {
        return try {
            engine.outDir.mkdirs()
            val f = File(engine.outDir, "build-last.log")
            val header = "HTML2APK build log\nstarted=$startedAt\napp=${cfg.appName}\npkg=${cfg.packageName}\nentry=${cfg.entryFile}\n\n"
            f.writeText(header + log.toString(), Charsets.UTF_8)
            f.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "save log failed", e)
            null
        }
    }

    private fun logCommand(log: StringBuilder, cmd: List<String>) {
        log.append("$ ").append(cmd.joinToString(" ")).append('\n')
    }

    private fun appendOutput(log: StringBuilder, output: String) {
        if (output.isBlank()) return
        log.append(output.trimEnd()).append('\n')
        if (log.length > MAX_LOG) log.setLength(MAX_LOG)
    }

    private fun tail(s: String): String {
        val t = s.trim()
        return if (t.length <= 1800) t else "…" + t.takeLast(1800)
    }

    private fun hintFor(output: String): String {
        val s = output.lowercase()
        return when {
            s.contains("no space") || s.contains("enospc") ->
                "设备存储空间不足，清理后重试。"
            s.contains("cannot link executable") || (s.contains("library") && s.contains("not found")) ->
                "引擎依赖库缺失，请卸载重装本 App。"
            s.contains("aapt2") && (s.contains("error") || s.contains("failed")) ->
                "aapt2 执行失败，请查看下方输出；若提示 Permission denied，请检查是否使用了应用分身。"
            s.contains("error:") ->
                "资源或文件处理出错，请查看下方 aapt2 输出中的 error: 行。"
            s.contains("androidmanifest") ->
                "AndroidManifest 生成异常（应用名/版本号可能含非法字符）。"
            else ->
                "请复制完整日志发给开发者；可先尝试重新选择文件、清理存储空间。"
        }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1024.0 / 1024.0 / 1024.0)
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
