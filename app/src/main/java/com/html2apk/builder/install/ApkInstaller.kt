package com.html2apk.builder.install

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/** APK 安装与打开下载目录 */
object ApkInstaller {

    private const val TAG = "HTML2APK.Install"

    fun install(ctx: Context, apkPath: String): Boolean {
        return try {
            val apk = File(apkPath)
            if (!apk.isFile) {
                Log.e(TAG, "apk missing: $apkPath")
                return false
            }
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            false
        }
    }

    /** 打开系统“下载”界面；没有文件管理器时退回分享面板 */
    fun openDownloads(ctx: Context) {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "view downloads failed", e)
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "resource/folder"
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
            } catch (e2: Exception) {
                Log.w(TAG, "open folder fallback failed", e2)
            }
        }
    }
}
