package com.html2apk.shell;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文件保存：把页面产生的 Blob/data 分块写入公共 下载/HTML2APK 目录。
 *
 * - API 29+：MediaStore.Downloads + IS_PENDING，无需任何存储权限；
 * - API 24-28：Download/HTML2APK + WRITE_EXTERNAL_STORAGE（未授权时回退到应用目录）。
 * 分块写入避免一次性把大文件塞进 Java 堆。
 */
final class FileSaver {

    private static final String TAG = "HTML2APK.Save";
    static final String SUB_DIR = "HTML2APK";
    private static final long MAX_TOTAL = 1024L * 1024 * 1024; // 1GB 安全上限

    private static final class Session {
        String name;
        String mime;
        Uri uri;
        File file;
        OutputStream out;
        long written;
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Session> sessions = new HashMap<>();
    private final AtomicLong seq = new AtomicLong(System.currentTimeMillis());

    FileSaver(Context ctx) {
        this.ctx = ctx;
    }

    synchronized String begin(String name, String mime) {
        try {
            name = sanitizeName(name, mime);
            mime = normalizeMime(mime, name);
            String token = "s" + seq.incrementAndGet();
            Session s = new Session();
            s.name = name;
            s.mime = mime;

            if (Build.VERSION.SDK_INT >= 29) {
                Uri uri = null;
                OutputStream out = null;
                String finalName = name;
                for (int i = 0; i < 60; i++) {
                    String candidate = i == 0 ? name : withSuffix(name, i);
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, candidate);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR);
                    v.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    try {
                        uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    } catch (Exception e) {
                        Log.w(TAG, "insert " + candidate + " failed", e);
                        uri = null;
                    }
                    if (uri == null) continue;
                    out = ctx.getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        finalName = candidate;
                        break;
                    }
                    try { ctx.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {}
                    uri = null;
                }
                if (uri == null || out == null) {
                    return "ERR:无法在下载目录创建文件（MediaStore 拒绝了请求）";
                }
                s.name = finalName;
                s.uri = uri;
                s.out = out;
            } else {
                File dir = downloadDirLegacy();
                if (dir == null) {
                    return "ERR:没有可写的存储目录";
                }
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    return "ERR:无法创建目录 " + dir;
                }
                File f = uniqueFile(dir, name);
                s.file = f;
                s.out = new FileOutputStream(f);
            }
            sessions.put(token, s);
            return token;
        } catch (Exception e) {
            Log.e(TAG, "begin failed", e);
            return "ERR:创建文件失败: " + e.getMessage();
        }
    }

    private static String withSuffix(String name, int i) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        return base + " (" + i + ")" + ext;
    }

    synchronized String write(String token, String base64) {
        Session s = sessions.get(token);
        if (s == null) return "保存会话已失效";
        if (base64 == null || base64.isEmpty()) return "";
        try {
            byte[] data = Base64.decode(base64, Base64.DEFAULT);
            if (s.written + data.length > MAX_TOTAL) {
                return "文件超过 1GB 上限";
            }
            s.out.write(data);
            s.written += data.length;
            return "";
        } catch (Exception e) {
            Log.e(TAG, "write failed", e);
            abort(token);
            return "写入失败: " + e.getMessage();
        }
    }

    synchronized String end(String token) {
        Session s = sessions.remove(token);
        if (s == null) return errorJson("保存会话已失效");
        try {
            if (s.out != null) {
                try { s.out.flush(); } catch (Exception ignored) {}
                try {
                    if (s.out instanceof FileOutputStream) {
                        ((FileOutputStream) s.out).getFD().sync();
                    }
                } catch (Exception ignored) {}
                try { s.out.close(); } catch (Exception ignored) {}
                s.out = null;
            }
            if (s.uri != null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.IS_PENDING, 0);
                ctx.getContentResolver().update(s.uri, v, null, null);
                String path = Environment.DIRECTORY_DOWNLOADS + "/" + SUB_DIR + "/" + s.name;
                toast("已保存到 " + path);
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("path", path);
                o.put("uri", s.uri.toString());
                o.put("bytes", s.written);
                return o.toString();
            }
            if (s.file != null) {
                MediaScannerConnection.scanFile(ctx,
                        new String[]{s.file.getAbsolutePath()}, null, null);
                toast("已保存到 " + s.file.getAbsolutePath());
                JSONObject o = new JSONObject();
                o.put("ok", true);
                o.put("path", s.file.getAbsolutePath());
                o.put("uri", Uri.fromFile(s.file).toString());
                o.put("bytes", s.written);
                return o.toString();
            }
            return errorJson("没有写入目标");
        } catch (Exception e) {
            Log.e(TAG, "end failed", e);
            cleanup(s);
            return errorJson("保存失败: " + e.getMessage());
        }
    }

    synchronized void abort(String token) {
        Session s = sessions.remove(token);
        if (s != null) cleanup(s);
    }

    private void cleanup(Session s) {
        try { if (s.out != null) s.out.close(); } catch (Exception ignored) {}
        try {
            if (s.uri != null) ctx.getContentResolver().delete(s.uri, null, null);
        } catch (Exception ignored) {}
        try {
            if (s.file != null) s.file.delete();
        } catch (Exception ignored) {}
    }

    /** 一次性保存字节（data: URL / 小文件） */
    synchronized String saveBytes(String name, String mime, byte[] data) {
        String token = begin(name, mime);
        if (token.startsWith("ERR:")) return errorJson(token.substring(4));
        String err = write(token, Base64.encodeToString(data, Base64.NO_WRAP));
        if (err != null && !err.isEmpty()) {
            abort(token);
            return errorJson(err);
        }
        return end(token);
    }

    private void toast(final String msg) {
        main.post(new Runnable() {
            @Override public void run() {
                try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
            }
        });
    }

    private static String errorJson(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", false);
            o.put("error", msg == null ? "未知错误" : msg);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private File downloadDirLegacy() {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), SUB_DIR);
            // 没有权限时 getExternalStoragePublicDirectory 仍可返回路径，但 mkdir 会失败
            if (dir.isDirectory() || dir.mkdirs()) return dir;
        } catch (Exception ignored) {}
        File fallback = ctx.getExternalFilesDir(SUB_DIR);
        if (fallback != null && (fallback.isDirectory() || fallback.mkdirs())) return fallback;
        return new File(ctx.getFilesDir(), "downloads");
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 500; i++) {
            f = new File(dir, base + " (" + i + ")" + ext);
            if (!f.exists()) return f;
        }
        return new File(dir, System.currentTimeMillis() + "-" + name);
    }

    static String sanitizeName(String name, String mime) {
        if (name == null) name = "";
        name = name.replace('\\', '_').replace('/', '_');
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch == ':' || ch == '*' || ch == '?' || ch == '"' ||
                    ch == '<' || ch == '>' || ch == '|') {
                sb.append('_');
            } else {
                sb.append(ch);
            }
        }
        name = sb.toString().trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "download";
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 12 ? name.substring(dot) : "";
            name = name.substring(0, 120 - ext.length()) + ext;
        }
        if (name.lastIndexOf('.') <= 0) name += extensionFor(mime);
        return name;
    }

    static String extensionFor(String mime) {
        if (mime == null) return ".bin";
        mime = mime.toLowerCase();
        if (mime.contains("json")) return ".json";
        if (mime.contains("csv")) return ".csv";
        if (mime.contains("html")) return ".html";
        if (mime.contains("xml")) return ".xml";
        if (mime.contains("javascript")) return ".js";
        if (mime.contains("css")) return ".css";
        if (mime.contains("plain") || mime.startsWith("text/")) return ".txt";
        if (mime.contains("zip")) return ".zip";
        if (mime.contains("pdf")) return ".pdf";
        if (mime.contains("png")) return ".png";
        if (mime.contains("jpeg") || mime.contains("jpg")) return ".jpg";
        if (mime.contains("gif")) return ".gif";
        if (mime.contains("webp")) return ".webp";
        return ".bin";
    }

    static String normalizeMime(String mime, String name) {
        if (mime == null || mime.trim().isEmpty()) {
            String lower = name == null ? "" : name.toLowerCase();
            if (lower.endsWith(".json")) return "application/json";
            if (lower.endsWith(".csv")) return "text/csv";
            if (lower.endsWith(".txt")) return "text/plain";
            if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
            return "application/octet-stream";
        }
        return mime.trim();
    }

    /** 从 Content-Disposition / URL 推断下载文件名 */
    static String fileNameFromDisposition(String cd, String url, String mime) {
        String name = null;
        if (cd != null) {
            try {
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("filename\\*=UTF-8''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(cd);
                if (m.find()) {
                    name = java.net.URLDecoder.decode(m.group(1).trim(), "UTF-8");
                }
                if (name == null) {
                    m = java.util.regex.Pattern
                            .compile("filename=\"?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE)
                            .matcher(cd);
                    if (m.find()) name = m.group(1).trim();
                }
            } catch (Exception ignored) {}
        }
        if (name == null || name.isEmpty()) {
            try {
                Uri u = Uri.parse(url);
                String last = u.getLastPathSegment();
                if (last != null && !last.isEmpty()) name = last;
            } catch (Exception ignored) {}
        }
        if (name == null || name.isEmpty()) name = "download";
        try { name = java.net.URLDecoder.decode(name, "UTF-8"); } catch (Exception ignored) {}
        return sanitizeName(name, mime);
    }

    /** 查询显示名，仅用于读取用户选择文件的元数据 */
    static String displayName(Context ctx, Uri uri) {
        String name = null;
        try {
            android.database.Cursor c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) name = c.getString(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {}
        if (name == null) {
            String last = uri.getLastPathSegment();
            name = last == null ? "file" : last;
        }
        return name;
    }
}
