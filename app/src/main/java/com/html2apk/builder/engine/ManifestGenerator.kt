package com.html2apk.builder.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/** 生成 AndroidManifest.xml 与 res 资源 */
object ManifestGenerator {

    /** 生成 AndroidManifest.xml（文本形式，供 aapt2 link 使用） */
    fun generateManifest(tpl: File, cfg: BuildConfig2, dest: File): File {
        var content = tpl.readText(Charsets.UTF_8)
        content = content
            .replace("__PACKAGE__", xmlAttr(cfg.packageName))
            .replace("__VERSION_CODE__", cfg.versionCode.toString())
            .replace("__VERSION_NAME__", xmlAttr(cfg.versionName))
        val left = Regex("__[A-Z_]+__").find(content)
        if (left != null) {
            throw IllegalStateException("Manifest 模板存在未替换的占位符: ${left.value}")
        }
        dest.parentFile?.mkdirs()
        dest.writeText(content, Charsets.UTF_8)
        return dest
    }

    /** 生成 res/values/strings.xml（app_name） */
    fun generateStringsXml(resDir: File, appName: String): File {
        val valuesDir = File(resDir, "values")
        valuesDir.mkdirs()
        val f = File(valuesDir, "strings.xml")
        // 先按 Android string 资源规则转义（反斜杠/单引号/双引号），再做 XML 转义
        var escaped = appName
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\"", "\\\"")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\r", " ")
            .replace("\n", " ")
        // 以 @ / ? 开头的字符串会被 aapt2 当作资源引用，转义成字面量
        if (escaped.startsWith("@") || escaped.startsWith("?")) {
            escaped = "\\" + escaped
        }
        f.writeText(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<resources>\n" +
                "    <string name=\"app_name\">$escaped</string>\n" +
                "</resources>\n",
            Charsets.UTF_8
        )
        return f
    }

    /**
     * 生成启动图标（多密度 mipmap PNG，供 @mipmap/ic_launcher 引用）。
     *
     * @param iconPath 用户自定义封面图片路径；为空/解码失败时使用默认程序化图标
     * @return true = 使用了自定义图片，false = 默认图标
     */
    fun generateIconSet(resDir: File, iconPath: String?): Boolean {
        var src: Bitmap? = null
        var square: Bitmap? = null
        var custom = false
        try {
            src = if (iconPath.isNullOrBlank()) null else decodeSampled(iconPath, 1024)
            square = src?.let { if (it.width == it.height) it else centerCrop(it) }
            custom = square != null

            // 标准 launcher 密度：mdpi48 / hdpi72 / xhdpi96 / xxhdpi144 / xxxhdpi192
            val densities = linkedMapOf(
                "mdpi" to 48, "hdpi" to 72, "xhdpi" to 96,
                "xxhdpi" to 144, "xxxhdpi" to 192
            )
            for ((density, size) in densities) {
                val target = File(File(resDir, "mipmap-$density"), "ic_launcher.png")
                target.parentFile?.mkdirs()
                val bmp = if (square != null) Bitmap.createScaledBitmap(square, size, size, true)
                else drawDefaultIcon(size)
                target.writeBytes(toPng(bmp))
                if (bmp !== square) bmp.recycle()
            }
            // 无密度兜底（部分旧 launcher 直接查 mipmap/）
            val base = File(File(resDir, "mipmap"), "ic_launcher.png")
            base.parentFile?.mkdirs()
            val bmpBase = if (square != null) Bitmap.createScaledBitmap(square, 48, 48, true)
            else drawDefaultIcon(48)
            base.writeBytes(toPng(bmpBase))
            if (bmpBase !== square) bmpBase.recycle()
        } catch (t: Throwable) {
            // 超大/损坏图片不能拖垮整个构建：回退默认图标
            custom = false
            try {
                if (square == null) {
                    for ((density, size) in linkedMapOf("mdpi" to 48, "hdpi" to 72, "xhdpi" to 96, "xxhdpi" to 144, "xxxhdpi" to 192)) {
                        val target = File(File(resDir, "mipmap-$density"), "ic_launcher.png")
                        target.parentFile?.mkdirs()
                        target.writeBytes(toPng(drawDefaultIcon(size)))
                    }
                }
            } catch (_: Throwable) {
            }
        } finally {
            try {
                if (square != null && square !== src) square.recycle()
                src?.recycle()
            } catch (_: Throwable) {
            }
        }
        return custom
    }

    /**
     * 生成封面预览 data URI（128px PNG base64），供 WebView UI 显示。
     * 返回 null 表示无法生成（图标文件缺失或无法解码）。
     */
    fun makePreviewDataUri(iconPath: String): String? {
        if (iconPath.isBlank()) return null
        var src: Bitmap? = null
        var square: Bitmap? = null
        var thumb: Bitmap? = null
        return try {
            src = decodeSampled(iconPath, 512) ?: return null
            square = if (src.width == src.height) src else centerCrop(src)
            thumb = Bitmap.createScaledBitmap(square, 128, 128, true)
            val bos = ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.PNG, 100, bos)
            "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            null
        } finally {
            try {
                if (thumb != null && thumb !== square && thumb !== src) thumb.recycle()
                if (square != null && square !== src) square.recycle()
                src?.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun decodeSampled(path: String, maxSide: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > maxSide || bounds.outHeight / sample > maxSide) {
                sample *= 2
            }
            val opts = BitmapFactory.Options()
            opts.inSampleSize = sample
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888
            BitmapFactory.decodeFile(path, opts)
        } catch (t: Throwable) {
            null
        }
    }

    /** 中心裁剪为正方形 */
    private fun centerCrop(src: Bitmap): Bitmap {
        val side = minOf(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        return Bitmap.createBitmap(src, x, y, side, side)
    }

    /** 程序化绘制默认图标（深蓝底 + 三横线，与旧矢量外观一致） */
    private fun drawDefaultIcon(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply { color = 0xFF0B1020.toInt(); style = Paint.Style.FILL }
        val fg = Paint().apply { color = 0xFF4C8DFF.toInt(); style = Paint.Style.FILL }
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bg)
        val s = size / 108f
        // 对应旧 vector：三条横线（第 3 条较短）
        c.drawRect(20f * s, 26f * s, 88f * s, 35f * s, fg)
        c.drawRect(20f * s, 49f * s, 88f * s, 58f * s, fg)
        c.drawRect(20f * s, 72f * s, 60f * s, 81f * s, fg)
        return bmp
    }

    private fun toPng(bmp: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        return bos.toByteArray()
    }

    private fun xmlAttr(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
    }
