package com.html2apk.builder.file

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** SAF 文件/文件夹选择后的拷贝服务与入口扫描 */
object FileService {

    private const val TAG = "HTML2APK.File"
    private const val MAX_SCAN = 512 * 1024

    data class CopyResult(
        val path: String?,
        val files: Int = 0,
        val bytes: Long = 0,
        val error: String = ""
    )

    data class PickInfo(
        val path: String,
        val kind: String,
        val htmlFiles: List<String>,
        val defaultEntry: String,
        val warnings: List<String>
    ) {
        fun toJson(): String {
            val o = JSONObject()
            o.put("path", path)
            o.put("kind", kind)
            o.put("defaultEntry", defaultEntry)
            val arr = JSONArray()
            htmlFiles.forEach { arr.put(it) }
            o.put("htmlFiles", arr)
            val w = JSONArray()
            warnings.forEach { w.put(it) }
            o.put("warnings", w)
            return o.toString()
        }
    }

    /** 拷贝单个文件到构建输入目录 */
    fun copySingleToBuildDir(ctx: Context, uri: Uri): CopyResult {
        return try {
            val inputDir = inputDir(ctx)
            val name = safeName(queryDisplayName(ctx, uri) ?: "index.html")
            val dest = File(inputDir, name)
            var bytes = 0L
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output ->
                    bytes = input.copyTo(output, 64 * 1024)
                }
            } ?: return CopyResult(null, error = "无法读取所选文件（内容提供者返回空流）")
            Log.i(TAG, "copied single -> $dest ($bytes bytes)")
            CopyResult(dest.absolutePath, 1, bytes)
        } catch (e: Exception) {
            Log.e(TAG, "copy single failed", e)
            CopyResult(null, error = "复制文件失败: ${e.message}")
        }
    }

    /** 拷贝整个文档树（文件夹）到构建输入目录 */
    fun copyTreeToBuildDir(ctx: Context, treeUri: Uri): CopyResult {
        return try {
            val inputDir = inputDir(ctx)
            var files = 0
            var bytes = 0L
            val skipped = ArrayList<String>()
            copyTreeRecursive(ctx, treeUri, inputDir, skipped) { n, b ->
                files += n
                bytes += b
            }
            if (skipped.isNotEmpty()) {
                Log.w(TAG, "skipped ${skipped.size} entries: ${skipped.take(5)}")
            }
            if (files == 0) {
                return CopyResult(null, 0, 0, "所选文件夹是空的，或没有读取权限")
            }
            Log.i(TAG, "copied tree -> $inputDir ($files files, $bytes bytes)")
            CopyResult(inputDir.absolutePath, files, bytes)
        } catch (e: Exception) {
            Log.e(TAG, "copy tree failed", e)
            CopyResult(null, error = "复制文件夹失败: ${e.message}")
        }
    }

    private fun inputDir(ctx: Context): File {
        val inputDir = File(ctx.filesDir, "work/input")
        inputDir.mkdirs()
        inputDir.listFiles()?.forEach { it.deleteRecursively() }
        return inputDir
    }

    /**
     * 递归拷贝。返回 [文件数, 字节数]，通过 [accumulate] 累加。
     * 单个文件失败只跳过并在 skipped 里记录，不中断整个文件夹（更接近用户预期）。
     */
    private fun copyTreeRecursive(
        ctx: Context,
        uri: Uri,
        destDir: File,
        skipped: MutableList<String>,
        accumulate: (Int, Long) -> Unit
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            uri, DocumentsContract.getTreeDocumentId(uri)
        )
        val cursor = ctx.contentResolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            ),
            null, null, null
        ) ?: throw IOException("无法读取文件夹内容")

        cursor.use {
            val docs = ArrayList<Triple<String, String, String>>()
            while (it.moveToNext()) {
                val docId = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2) ?: ""
                docs.add(Triple(docId, name, mime))
            }
            docs.sortBy { it.second.lowercase() }
            for ((docId, rawName, mime) in docs) {
                val name = safeName(rawName)
                if (name.isEmpty() || isJunk(name)) continue
                val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val subDir = File(destDir, name)
                    if (!subDir.isDirectory && !subDir.mkdirs()) {
                        skipped.add("$rawName (无法创建目录)")
                        continue
                    }
                    try {
                        copyTreeRecursive(ctx, docUri, subDir, skipped, accumulate)
                    } catch (e: Exception) {
                        skipped.add("$rawName (${e.message})")
                    }
                } else {
                    try {
                        var bytes = 0L
                        ctx.contentResolver.openInputStream(docUri)?.use { input ->
                            File(destDir, name).outputStream().use { output ->
                                bytes = input.copyTo(output, 64 * 1024)
                            }
                        } ?: throw IOException("空数据流")
                        accumulate(1, bytes)
                    } catch (e: Exception) {
                        skipped.add("$rawName (${e.message})")
                    }
                }
            }
        }
    }

    /** 拷贝封面图标到构建目录（固定名 icon.png，覆盖旧图） */
    fun copyIconToBuildDir(ctx: Context, uri: Uri): String? {
        return try {
            val workDir = File(ctx.filesDir, "work")
            workDir.mkdirs()
            val dest = File(workDir, "icon.png")
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: return null
            Log.i(TAG, "copied icon -> $dest")
            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "copy icon failed", e)
            null
        }
    }

    /** 扫描输入目录，给出入口候选与提示 */
    fun describe(ctx: Context, path: String, kind: String): PickInfo {
        val warnings = ArrayList<String>()
        if (kind == "file") {
            warnings.add("单文件模式只会打包这个 HTML；它引用的本地 CSS/JS/图片不会被包含。需要完整资源请选择文件夹。")
            val f = File(path)
            if (!f.name.lowercase().endsWith(".html") && !f.name.lowercase().endsWith(".htm")) {
                warnings.add("所选文件扩展名不是 .html/.htm，入口会被当作 HTML 处理。")
            }
            localRefs(f)?.let {
                if (it.isNotEmpty()) {
                    warnings.add("表单文件里发现本地资源引用（${it.take(3).joinToString("、")}${if (it.size > 3) " 等" else ""}），单文件模式不会打包它们。")
                }
            }
            return PickInfo(path, kind, listOf("index.html"), "index.html", warnings)
        }

        val root = File(path)
        val htmlFiles = ArrayList<String>()
        try {
            root.walkTopDown()
                .onEnter { dir -> dir == root || !isJunkDir(dir.name) }
                .forEach { f ->
                    if (f.isFile && isHtml(f.name) && !f.name.startsWith("__ht2a_")) {
                        val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                        if (!isJunk(rel)) htmlFiles.add(rel)
                    }
                }
        } catch (e: Exception) {
            warnings.add("扫描 HTML 文件失败: ${e.message}")
        }
        htmlFiles.sortWith(compareBy({ it.count { c -> c == '/' } }, { it.lowercase() }))

        if (htmlFiles.isEmpty()) {
            warnings.add("所选文件夹里没有找到 .html/.htm 文件。")
            return PickInfo(path, kind, emptyList(), "", warnings)
        }

        val defaultEntry = pickDefaultEntry(htmlFiles)
        if (htmlFiles.size > 1) {
            warnings.add("检测到 ${htmlFiles.size} 个 HTML 文件，默认入口为 $defaultEntry；可在下方“入口文件”里切换。")
        }
        if (defaultEntry != "index.html" && htmlFiles.contains("index.html")) {
            warnings.add("根目录有 index.html，但默认入口选择了 $defaultEntry（请确认）。")
        }
        return PickInfo(path, kind, htmlFiles, defaultEntry, warnings)
    }

    private fun pickDefaultEntry(files: List<String>): String {
        files.firstOrNull { it == "index.html" }?.let { return it }
        files.firstOrNull { it == "index.htm" }?.let { return it }
        files.firstOrNull { it.endsWith("/index.html") }?.let { return it }
        files.firstOrNull { it.endsWith("/index.htm") }?.let { return it }
        return files.first()
    }

    private fun localRefs(file: File): List<String>? {
        if (!file.isFile) return null
        return try {
            val bytes = file.inputStream().use { it.readBytesLimited(MAX_SCAN) }
            val text = String(bytes, Charsets.UTF_8)
            val re = Regex("""(?:src|href)\s*=\s*["'](?!(?:https?:)?//|data:|#|javascript:|mailto:|tel:)([^"']+)["']""", RegexOption.IGNORE_CASE)
            re.findAll(text).map { it.groupValues[1] }.filter { it.isNotBlank() }.take(10).toList()
        } catch (e: Exception) {
            null
        }
    }

    private fun java.io.InputStream.readBytesLimited(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        var n: Int
        while (true) {
            n = read(buf)
            if (n <= 0) break
            total += n
            if (total > max) {
                val room = max - (total - n)
                if (room > 0) out.write(buf, 0, room)
                break
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun queryDisplayName(ctx: Context, uri: Uri): String? {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun isHtml(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".html") || n.endsWith(".htm")
    }

    fun safeName(name: String): String {
        val sb = StringBuilder()
        for (ch in name) {
            if (ch == '/' || ch == '\\' || ch.code < 0x20 || ch == '\u0000') sb.append('_') else sb.append(ch)
        }
        return sb.toString().trim().trimEnd('.')
    }

    private fun isJunk(name: String): Boolean {
        val n = name.lowercase()
        return n == ".ds_store" || n == "thumbs.db" || n == "desktop.ini" || n.startsWith("._")
    }

    private fun isJunkDir(name: String): Boolean {
        val n = name.lowercase()
        return n == ".git" || n == ".svn" || n == ".hg" || n == "__macosx" || n == ".idea" || n == ".vscode"
    }
}
