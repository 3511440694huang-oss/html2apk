package com.html2apk.shell;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Vector;

/**
 * 把 APK 的 assets 通过一个虚拟 HTTPS origin 提供出来。
 *
 * 为什么不用 file://：
 *  - file:// 下 ES Module / fetch / IndexedDB 都被 CORS 或 opaque origin 挡住，
 *    这正是「打包后白屏」最常见的原因；
 *  - file:// 的 localStorage origin 不稳定，且无法被安全地扩展。
 *
 * 虚拟 origin（https://appassets.androidplatform.net）是 secure context，
 * 且所有请求在本进程内由 shouldInterceptRequest 截获，不产生任何网络流量。
 * 入口 HTML 在字节层面注入 shim（不改编码、不重排内容）。
 */
final class AssetServer {

    static final String HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + HOST;
    private static final String TAG = "HTML2APK.Asset";
    private static final String SHIM_PATH = "__ht2a/shim.js";
    private static final int HEAD_SCAN = 64 * 1024;

    private final Context ctx;
    private final AssetManager assets;
    private final String entry;
    private final byte[] shimTag;
    private final byte[] shimJs;

    AssetServer(Context ctx, String entry) {
        this.ctx = ctx;
        this.assets = ctx.getAssets();
        this.entry = Config.normalizeEntry(entry);
        this.shimTag = ("<script src=\"/" + SHIM_PATH + "\" data-ht2a=\"1\"></script>")
                .getBytes();
        String js = Shim.get();
        this.shimJs = js == null ? new byte[0] : js.getBytes();
    }

    WebResourceResponse handle(WebResourceRequest request) {
        if (request == null) return null;
        Uri uri;
        try {
            uri = request.getUrl();
        } catch (Exception e) {
            return null;
        }
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) ||
                !HOST.equalsIgnoreCase(uri.getHost())) {
            return null;
        }
        String path;
        try {
            // getEncodedPath 只解码一次，避免资源名里含 % 时被二次解码
            path = uri.getEncodedPath();
            if (path == null) path = uri.getPath();
            if (path == null) path = "/";
            path = Uri.decode(path);
        } catch (Exception e) {
            path = "/";
        }
        path = normalize(path);
        if (path.isEmpty()) path = entry;
        if (path.endsWith("/")) path = path + "index.html";

        if (SHIM_PATH.equals(path)) {
            Map<String, String> h = new HashMap<>();
            h.put("Cache-Control", "no-cache, no-store, must-revalidate");
            h.put("Content-Type", "text/javascript; charset=utf-8");
            return new WebResourceResponse("text/javascript", "UTF-8", 200, "OK", h,
                    new ByteArrayInputStream(shimJs));
        }

        InputStream raw = null;
        try {
            raw = assets.open(path, AssetManager.ACCESS_STREAMING);
        } catch (Exception e) {
            // 目录链接常写成 href="sub"（没有结尾 /），补一次 index.html
            if (!path.endsWith(".html") && !path.endsWith(".htm")) {
                try {
                    raw = assets.open(path + "/index.html", AssetManager.ACCESS_STREAMING);
                    path = path + "/index.html";
                } catch (Exception ignored) {
                    raw = null;
                }
            }
            if (raw == null) return notFound(path);
        }

        String mime = mimeFor(path);
        boolean isHtml = "text/html".equals(mime);

        try {
            if (isHtml) {
                return injectShim(raw, path);
            }
            // Range support for media (only works for uncompressed assets)
            String range = null;
            try {
                Map<String, String> reqHeaders = request.getRequestHeaders();
                if (reqHeaders != null) range = reqHeaders.get("Range");
            } catch (Exception ignored) {}
            if (range != null && Build.VERSION.SDK_INT >= 21) {
                WebResourceResponse ranged = serveRange(path, range, mime);
                if (ranged != null) {
                    try { raw.close(); } catch (Exception ignored) {}
                    return ranged;
                }
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            headers.put("Accept-Ranges", "bytes");
            return new WebResourceResponse(mime, null, 200, "OK", headers, raw);
        } catch (Exception e) {
            try { raw.close(); } catch (Exception ignored) {}
            Log.e(TAG, "serve failed: " + path, e);
            return notFound(path);
        }
    }

    private WebResourceResponse injectShim(InputStream in, String path) throws IOException {
        BufferedInputStream bin = new BufferedInputStream(in, HEAD_SCAN);
        byte[] head = new byte[HEAD_SCAN];
        int len = 0;
        while (len < head.length) {
            int n = bin.read(head, len, head.length - len);
            if (n < 0) break;
            len += n;
        }
        int pos = insertionPoint(head, len);
        Vector<InputStream> parts = new Vector<>();
        if (pos > 0) parts.add(new ByteArrayInputStream(head, 0, pos));
        parts.add(new ByteArrayInputStream(shimTag));
        if (len > pos) parts.add(new ByteArrayInputStream(head, pos, len - pos));
        parts.add(bin);
        InputStream seq = new SequenceInputStream(parts.elements());
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        headers.put("X-HT2A-Shim", "1");
        return new WebResourceResponse("text/html", null, 200, "OK", headers, seq);
    }

    /** 在 <head> 之后插入；没有 head 时退到 doctype/html 之后，绝不插到 doctype 前面（避免怪异模式） */
    private static int insertionPoint(byte[] b, int len) {
        int start = 0;
        if (len >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        int head = indexOfTag(b, len, start, "<head");
        if (head >= 0) {
            int end = indexOf(b, len, head, (byte) '>');
            if (end > 0) return end + 1;
        }
        int doc = indexOfTag(b, len, start, "<!doctype");
        if (doc >= 0) {
            int end = indexOf(b, len, doc, (byte) '>');
            if (end > 0) return end + 1;
        }
        int html = indexOfTag(b, len, start, "<html");
        if (html >= 0) {
            int end = indexOf(b, len, html, (byte) '>');
            if (end > 0) return end + 1;
        }
        return start;
    }

    private static int indexOfTag(byte[] b, int len, int from, String tag) {
        int i = indexOfIgnoreCase(b, len, from, tag);
        if (i < 0) return -1;
        int after = i + tag.length();
        if (after >= len) return i;
        byte c = b[after];
        if (c == '>' || c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '/') {
            return i;
        }
        return -1;
    }

    private static int indexOfIgnoreCase(byte[] b, int len, int from, String s) {
        int n = s.length();
        for (int i = Math.max(0, from); i + n <= len; i++) {
            boolean ok = true;
            for (int j = 0; j < n; j++) {
                int a = b[i + j] & 0xFF;
                int c = s.charAt(j);
                if (a >= 'A' && a <= 'Z') a += 32;
                if (c >= 'A' && c <= 'Z') c += 32;
                if (a != c) { ok = false; break; }
            }
            if (ok) return i;
        }
        return -1;
    }

    private static int indexOf(byte[] b, int len, int from, byte ch) {
        for (int i = Math.max(0, from); i < len; i++) {
            if (b[i] == ch) return i;
        }
        return -1;
    }

    private WebResourceResponse serveRange(String path, String range, String mime) {
        AssetFileDescriptor afd = null;
        FileInputStream fis = null;
        try {
            afd = assets.openFd(path);
            long total = afd.getLength();
            if (total <= 0) {
                afd.close();
                return null;
            }
            long start = 0, end = total - 1;
            String r = range.trim();
            int eq = r.indexOf('=');
            if (eq >= 0) r = r.substring(eq + 1);
            int dash = r.indexOf('-');
            if (dash < 0) { afd.close(); return null; }
            String a = r.substring(0, dash).trim();
            String b = r.substring(dash + 1).trim();
            if (a.isEmpty()) {
                long suffix = Long.parseLong(b);
                start = Math.max(0, total - suffix);
            } else {
                start = Long.parseLong(a);
                if (!b.isEmpty()) end = Long.parseLong(b);
            }
            if (start < 0 || start >= total) {
                afd.close();
                Map<String, String> h = new HashMap<>();
                h.put("Content-Range", "bytes */" + total);
                return new WebResourceResponse(mime, null, 416, "Range Not Satisfiable", h,
                        new ByteArrayInputStream(new byte[0]));
            }
            if (end >= total) end = total - 1;
            if (end < start) end = total - 1;
            long length = end - start + 1;

            fis = new FileInputStream(afd.getFileDescriptor());
            fis.getChannel().position(afd.getStartOffset() + start);
            Map<String, String> h = new HashMap<>();
            h.put("Content-Range", "bytes " + start + "-" + end + "/" + total);
            h.put("Content-Length", Long.toString(length));
            h.put("Accept-Ranges", "bytes");
            h.put("Cache-Control", "no-cache");
            InputStream limited = new LimitedStream(fis, length, afd);
            fis = null;
            afd = null;
            return new WebResourceResponse(mime, null, 206, "Partial Content", h, limited);
        } catch (Exception e) {
            Log.w(TAG, "range failed: " + path + " " + e);
            try { if (fis != null) fis.close(); } catch (Exception ignored) {}
            try { if (afd != null) afd.close(); } catch (Exception ignored) {}
            return null;
        }
    }

    private WebResourceResponse notFound(String path) {
        String body = "<!doctype html><meta charset=utf-8>" +
                "<body style=\"font:14px monospace;padding:24px;background:#111;color:#eee\">" +
                "<h3>404 资源不存在</h3><p>" + escape(path) + "</p>" +
                "<p>请确认文件已打包进 APK 的 assets/ 目录，且入口路径与 ht2a.config 一致。</p></body>";
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        return new WebResourceResponse("text/html", "UTF-8", 404, "Not Found", h,
                new ByteArrayInputStream(body.getBytes()));
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String normalize(String path) {
        if (path == null) return "";
        while (path.startsWith("/")) path = path.substring(1);
        String[] parts = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) return "";
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    static String mimeFor(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase(Locale.US) : "";
        if (ext.equals("html") || ext.equals("htm")) return "text/html";
        if (ext.equals("js") || ext.equals("mjs") || ext.equals("cjs")) return "text/javascript";
        if (ext.equals("css")) return "text/css";
        if (ext.equals("json") || ext.equals("map")) return "application/json";
        if (ext.equals("wasm")) return "application/wasm";
        if (ext.equals("xml")) return "application/xml";
        if (ext.equals("txt") || ext.equals("log")) return "text/plain";
        if (ext.equals("csv")) return "text/csv";
        if (ext.equals("svg")) return "image/svg+xml";
        if (ext.equals("png")) return "image/png";
        if (ext.equals("jpg") || ext.equals("jpeg")) return "image/jpeg";
        if (ext.equals("gif")) return "image/gif";
        if (ext.equals("webp")) return "image/webp";
        if (ext.equals("bmp")) return "image/bmp";
        if (ext.equals("ico")) return "image/x-icon";
        if (ext.equals("woff")) return "font/woff";
        if (ext.equals("woff2")) return "font/woff2";
        if (ext.equals("ttf")) return "font/ttf";
        if (ext.equals("otf")) return "font/otf";
        if (ext.equals("eot")) return "application/vnd.ms-fontobject";
        if (ext.equals("mp4") || ext.equals("m4v")) return "video/mp4";
        if (ext.equals("webm")) return "video/webm";
        if (ext.equals("ogv")) return "video/ogg";
        if (ext.equals("mp3")) return "audio/mpeg";
        if (ext.equals("m4a") || ext.equals("aac")) return "audio/mp4";
        if (ext.equals("ogg") || ext.equals("oga")) return "audio/ogg";
        if (ext.equals("wav")) return "audio/wav";
        if (ext.equals("flac")) return "audio/flac";
        if (ext.equals("pdf")) return "application/pdf";
        if (ext.equals("zip")) return "application/zip";
        if (ext.equals("wasm")) return "application/wasm";
        String guess = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return guess == null ? "application/octet-stream" : guess;
    }

    /** 限定读取长度并在 close 时同时关闭 FileInputStream 与 AssetFileDescriptor */
    private static final class LimitedStream extends InputStream {
        private final FileInputStream in;
        private final AssetFileDescriptor afd;
        private long left;

        LimitedStream(FileInputStream in, long length, AssetFileDescriptor afd) {
            this.in = in;
            this.afd = afd;
            this.left = length;
        }

        @Override public int read() throws IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }

        @Override public int available() throws IOException {
            return (int) Math.min(Integer.MAX_VALUE, left);
        }

        @Override public void close() throws IOException {
            try { in.close(); } catch (Exception ignored) {}
            try { if (afd != null) afd.close(); } catch (Exception ignored) {}
        }
    }
}
