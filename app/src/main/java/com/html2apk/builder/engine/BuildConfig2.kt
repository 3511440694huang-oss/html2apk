package com.html2apk.builder.engine

import org.json.JSONObject

/** 用户构建配置 */
data class BuildConfig2(
    val appName: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Int,
    val entryFile: String,        // assets 内的入口 HTML（相对路径，如 dist/index.html）
    val inputDir: String,         // 用户选择的 HTML/文件夹已拷贝到的构建输入目录
    val statusBarColor: String = "#111111",
    val navBarColor: String = "#111111",
    val backgroundColor: String = "#111111",
    val iconPath: String = "",    // 自定义封面图标路径（空 = 使用默认图标）
    val debug: Boolean = false,   // 产物壳调试模式：页面脚本错误显示红色横幅
    val orientation: String = "auto", // auto | portrait | landscape
    val formRestore: Boolean = true  // 刷新后恢复表单输入
) {
    companion object {
        fun fromJson(json: String): BuildConfig2 {
            val o = JSONObject(json)
            val orientation = o.optString("orientation", "auto").let {
                if (it == "portrait" || it == "landscape") it else "auto"
            }
            return BuildConfig2(
                appName = o.optString("appName", "").trim(),
                packageName = o.optString("packageName", "").trim(),
                versionName = o.optString("versionName", "1.0").trim().ifEmpty { "1.0" },
                versionCode = o.optInt("versionCode", 1).coerceAtLeast(1),
                entryFile = o.optString("entryFile", "index.html").trim().ifEmpty { "index.html" },
                inputDir = o.optString("inputDir", ""),
                statusBarColor = o.optString("statusBarColor", "#111111"),
                navBarColor = o.optString("navBarColor", "#111111"),
                backgroundColor = o.optString("backgroundColor", "#111111"),
                iconPath = o.optString("iconPath", ""),
                debug = when (o.optString("debug", "0").lowercase()) {
                    "1", "true", "yes", "on" -> true
                    else -> false
                },
                orientation = orientation,
                formRestore = o.optBoolean("formRestore", true)
            )
        }
    }
}
