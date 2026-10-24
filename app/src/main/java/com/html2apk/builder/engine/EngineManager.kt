package com.html2apk.builder.engine

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 引擎资产管理：
 * - 可执行文件（aapt2）打包在 APK 的 jniLibs（lib/arm64-v8a/libaapt2.so），
 *   运行时从 applicationInfo.nativeLibraryDir 定位并 exec。
 *   注意：Android 10+ 拒绝执行非 PIE（ET_EXEC）ELF，Android 15/16 还禁止从
 *   app data 目录 exec，因此必须使用 PIE 二进制 + native lib 目录。
 * - aapt2 的动态依赖（libc++_shared、libprotobuf、libabsl_* 等）放在
 *   assets/engine/lib/，首次运行时解压到 filesDir/engine/lib，并通过
 *   LD_LIBRARY_PATH 提供给 aapt2。
 * - 数据资产（android.jar、template.dex、manifest 模板）同样解压到私有目录。
 */
class EngineManager(private val ctx: Context) {

    val engineDir: File = File(ctx.filesDir, "engine")
    val libDir: File = File(engineDir, "lib")
    val workDir: File = File(ctx.filesDir, "work")
    val outDir: File = File(ctx.filesDir, "out")

    /** native lib 目录（/data/app/.../lib/<abi>，系统安装时解压，不可写） */
    private val nativeLibDir: File = File(ctx.applicationInfo.nativeLibraryDir)

    val aapt2: File get() = File(nativeLibDir, "libaapt2.so")
    val androidJar: File get() = File(engineDir, "android.jar")
    val templateDex: File get() = File(engineDir, "template.dex")
    val manifestTpl: File get() = File(engineDir, "AndroidManifest.tpl.xml")

    data class Status(
        val ready: Boolean,
        val aapt2Version: String = "",
        val error: String = "",
        val hint: String = "",
        val libCount: Int = 0,
        val engineVersion: String = ""
    )

    data class ExecResult(val exitCode: Int, val output: String, val timedOut: Boolean = false)

    private val lock = Any()

    @Volatile
    private var cached: Status? = null

    @Volatile
    private var cachedAt = 0L

    /** 资产版本号（与 assets/engine/VERSION 一致；引擎资产更新时递增） */
    private val assetVersion: String =
        try {
            ctx.assets.open("engine/VERSION").use { it.bufferedReader().readText().trim() }
        } catch (e: Exception) {
            "0"
        }

    fun status(refresh: Boolean = false): Status {
        synchronized(lock) {
            val c = cached
            if (!refresh && c != null) {
                // 成功状态一直复用；失败状态缓存 4 秒，避免 UI 轮询反复启动 aapt2
                if (c.ready || System.currentTimeMillis() - cachedAt < 4000L) return c
            }
            val s = computeStatus()
            cached = s
            cachedAt = System.currentTimeMillis()
            return s
        }
    }

    fun ensureEngine(): Boolean = status().ready

    private fun computeStatus(): Status {
        try {
            val abis = Build.SUPPORTED_ABIS
            if (abis.none { it == "arm64-v8a" }) {
                return Status(
                    false,
                    error = "当前设备 CPU 架构为 ${abis.joinToString("/")}",
                    hint = "构建引擎目前只提供 arm64-v8a 版本（绝大多数 2018 年后的手机）。" +
                        "x86 模拟器/32 位设备暂不支持。",
                    engineVersion = assetVersion
                )
            }
            if (!aapt2.exists() || !aapt2.canExecute()) {
                return Status(
                    false,
                    error = "引擎可执行文件缺失：${aapt2.absolutePath}",
                    hint = "APK 安装时没有释放 native 库。请卸载后重新安装本 App；" +
                        "若使用应用商店的“拆分 APK/App Bundle”，请安装完整版。",
                    engineVersion = assetVersion
                )
            }

            val versionFile = File(engineDir, "VERSION")
            val needRefresh = !versionFile.exists() ||
                versionFile.readText().trim() != assetVersion ||
                (libDir.listFiles()?.size ?: 0) < 20 ||
                !androidJar.exists() || !templateDex.exists() || !manifestTpl.exists()
            if (needRefresh) {
                engineDir.deleteRecursively()
                engineDir.mkdirs()
                extractAsset("engine/android.jar", androidJar)
                extractAsset("engine/template.dex", templateDex)
                extractAsset("engine/AndroidManifest.tpl.xml", manifestTpl)
                val libs = ctx.assets.list("engine/lib") ?: emptyArray()
                libDir.mkdirs()
                libs.forEach { name ->
                    extractAsset("engine/lib/$name", File(libDir, name))
                }
                versionFile.writeText(assetVersion)
                Log.i(TAG, "engine assets refreshed: version=$assetVersion libs=${libs.size}")
            }

            val exec = exec(listOf(aapt2.absolutePath, "version"), ctx.cacheDir, 15_000)
            val text = exec.output.trim()
            val ok = exec.exitCode == 0 && (text.contains("Android Asset Packaging Tool") || text.contains("aapt"))
            if (!ok) {
                Log.e(TAG, "aapt2 version failed exit=${exec.exitCode}: $text")
                return Status(
                    false,
                    error = "aapt2 启动失败（exit=${exec.exitCode}）",
                    hint = hintFor(text.ifBlank { "无输出" }),
                    libCount = libDir.listFiles()?.size ?: 0,
                    engineVersion = assetVersion
                )
            }
            val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
            Log.i(TAG, "engine ready: $firstLine, libs=${libDir.listFiles()?.size}")
            return Status(
                true,
                aapt2Version = firstLine,
                libCount = libDir.listFiles()?.size ?: 0,
                engineVersion = assetVersion
            )
        } catch (e: Throwable) {
            Log.e(TAG, "engine init failed", e)
            return Status(
                false,
                error = "引擎初始化异常：${e.message}",
                hint = hintFor(e.toString()),
                libCount = libDir.listFiles()?.size ?: 0,
                engineVersion = assetVersion
            )
        }
    }

    private fun hintFor(output: String): String {
        val s = output.lowercase()
        return when {
            s.contains("unexpected e_type") || s.contains("exec format error") ->
                "引擎二进制格式不被当前 Android 接受（旧版非 PIE 二进制）。请升级本 App 到最新版本。"
            s.contains("cannot link executable") || (s.contains("library") && s.contains("not found")) ->
                "引擎依赖库缺失或不匹配。请卸载后重新安装本 App（不要只覆盖安装旧的测试版）。"
            s.contains("permission denied") ->
                "系统拒绝执行引擎。请确认没有使用“应用分身/平行空间”，并在系统设置中允许本 App 运行。"
            s.contains("no space") ->
                "设备存储空间不足，请清理后重试。"
            else ->
                "请把这段错误日志复制给开发者；可先尝试卸载重装本 App。"
        }
    }

    private fun extractAsset(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        ctx.assets.open(assetPath).use { input ->
            dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
        }
    }

    /** 给子进程准备 LD_LIBRARY_PATH（aapt2 的 .so 依赖在 libDir） */
    fun childEnv(): Map<String, String> {
        val env = HashMap<String, String>()
        env.putAll(System.getenv())
        val old = env["LD_LIBRARY_PATH"]
        val path = if (old.isNullOrBlank()) {
            nativeLibDir.absolutePath + ":" + libDir.absolutePath
        } else {
            nativeLibDir.absolutePath + ":" + libDir.absolutePath + ":" + old
        }
        env["LD_LIBRARY_PATH"] = path
        env["TMPDIR"] = ctx.cacheDir.absolutePath
        env["HOME"] = ctx.filesDir.absolutePath
        return env
    }

    /** 执行外部命令；并发读输出，带超时，避免 aapt2 卡死拖住构建 */
    fun exec(cmd: List<String>, cwd: File, timeoutMs: Long = 180_000): ExecResult {
        var process: Process? = null
        try {
            val pb = ProcessBuilder(cmd)
            pb.directory(cwd)
            pb.redirectErrorStream(true)
            pb.environment().putAll(childEnv())
            val p = pb.start()
            process = p
            val output = StringBuilder()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) {
                                if (output.length < 200_000) {
                                    output.append(line).append('\n')
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }
            reader.isDaemon = true
            reader.start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                try { p.destroy() } catch (_: Exception) {}
                try { p.waitFor(2, TimeUnit.SECONDS) } catch (_: Exception) {}
                reader.join(1000)
                return ExecResult(-1, synchronized(output) { output.toString() } + "\n[超时] 命令执行超过 ${timeoutMs / 1000}s", true)
            }
            reader.join(2000)
            return ExecResult(p.exitValue(), synchronized(output) { output.toString() })
        } catch (e: Throwable) {
            try { process?.destroy() } catch (_: Exception) {}
            Log.e(TAG, "exec failed: ${cmd.firstOrNull()}", e)
            return ExecResult(-1, e.toString())
        }
    }

    companion object {
        private const val TAG = "HTML2APK.Engine"
    }
}
