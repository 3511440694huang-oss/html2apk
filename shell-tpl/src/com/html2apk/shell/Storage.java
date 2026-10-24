package com.html2apk.shell;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 原生键值存储：为 localStorage/sessionStorage 提供不受 5MB 配额限制的落盘后端。
 *
 * 结构：files/ht2a/storage/<namespace>/<sha256(key)>.dat + keys.json
 * 每个 key 单独一个文件，写入时只重写该 key，避免整库重写。
 * 所有写操作 temp + rename + fsync，确保刷新/杀进程后数据不丢。
 */
final class Storage {

    private static final String TAG = "HTML2APK.Storage";
    private static final long MAX_VALUE = 64L * 1024 * 1024;
    private static final int MAX_KEYS = 20000;

    private final File root;

    Storage(Context ctx) {
        root = new File(ctx.getFilesDir(), "ht2a/storage");
    }

    private static String safeNs(String ns) {
        if (ns == null) return "local";
        ns = ns.trim();
        if (!ns.matches("[a-zA-Z0-9_-]{1,32}")) return "local";
        return ns;
    }

    private File nsDir(String ns) {
        return new File(root, safeNs(ns));
    }

    private static String hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(key.hashCode());
        }
    }

    private File valueFile(String ns, String key) {
        return new File(nsDir(ns), hash(key) + ".dat");
    }

    private File indexFile(String ns) {
        return new File(nsDir(ns), "keys.json");
    }

    synchronized String get(String ns, String key) {
        if (key == null) return null;
        File f = valueFile(ns, key);
        if (!f.isFile()) return null;
        try {
            byte[] data = readFile(f, MAX_VALUE);
            return new String(data, "UTF-8");
        } catch (Exception e) {
            Log.e(TAG, "read failed: " + key, e);
            return null;
        }
    }

    synchronized String set(String ns, String key, String value) {
        if (key == null || key.isEmpty()) return "key 不能为空";
        if (value == null) value = "";
        byte[] data;
        try {
            data = value.getBytes("UTF-8");
        } catch (Exception e) {
            return "编码失败";
        }
        if (data.length > MAX_VALUE) return "单个值超过 64MB 限制";
        File dir = nsDir(ns);
        if (!dir.isDirectory() && !dir.mkdirs()) return "无法创建存储目录";
        long usable = dir.getUsableSpace();
        if (usable > 0 && usable < data.length + 1024L * 1024L) {
            return "存储空间不足";
        }
        try {
            writeAtomic(valueFile(ns, key), data);
        } catch (Exception e) {
            Log.e(TAG, "write failed: " + key, e);
            return "写入失败: " + e.getMessage();
        }
        // 只有新 key 才更新索引，避免每次 setItem 都重写索引
        JSONArray keys = readIndex(ns);
        boolean found = false;
        for (int i = 0; i < keys.length(); i++) {
            if (key.equals(keys.optString(i))) { found = true; break; }
        }
        if (!found) {
            if (keys.length() >= MAX_KEYS) return "key 数量超过限制";
            keys.put(key);
            try {
                writeAtomic(indexFile(ns), keys.toString().getBytes("UTF-8"));
            } catch (Exception e) {
                return "索引写入失败: " + e.getMessage();
            }
        }
        return "";
    }

    synchronized boolean remove(String ns, String key) {
        if (key == null) return false;
        boolean ok = valueFile(ns, key).delete();
        JSONArray keys = readIndex(ns);
        JSONArray next = new JSONArray();
        for (int i = 0; i < keys.length(); i++) {
            String k = keys.optString(i);
            if (!key.equals(k)) next.put(k);
        }
        try {
            writeAtomic(indexFile(ns), next.toString().getBytes("UTF-8"));
        } catch (Exception ignored) {}
        return ok;
    }

    synchronized boolean clear(String ns) {
        File dir = nsDir(ns);
        File[] files = dir.listFiles();
        boolean ok = true;
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.delete()) ok = false;
            }
        }
        return ok;
    }

    synchronized String keysJson(String ns) {
        JSONArray keys = readIndex(ns);
        JSONArray out = new JSONArray();
        for (int i = 0; i < keys.length(); i++) {
            String k = keys.optString(i);
            if (valueFile(ns, k).isFile()) out.put(k);
        }
        return out.toString();
    }

    synchronized long size(String ns) {
        File dir = nsDir(ns);
        long total = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) total += f.length();
            }
        }
        return total;
    }

    synchronized int count(String ns) {
        return readIndex(ns).length();
    }

    synchronized String infoJson() {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("localKeys", keysJson("local"));
            o.put("localBytes", size("local"));
            o.put("sessionBytes", size("session"));
            o.put("dir", root.getAbsolutePath());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private JSONArray readIndex(String ns) {
        File f = indexFile(ns);
        if (!f.isFile()) return new JSONArray();
        try {
            byte[] data = readFile(f, 8L * 1024 * 1024);
            JSONArray arr = new JSONArray(new String(data, "UTF-8"));
            return arr == null ? new JSONArray() : arr;
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static byte[] readFile(File f, long max) throws IOException {
        long len = f.length();
        if (len > max) throw new IOException("file too large: " + len);
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(len, 1 << 20));
            byte[] buf = new byte[64 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > max) throw new IOException("file too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static void writeAtomic(File dest, byte[] data) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdir failed: " + parent);
        }
        File tmp = new File(parent, dest.getName() + ".tmp" + System.nanoTime());
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            try { out.getFD().sync(); } catch (Exception ignored) {}
        } finally {
            try { out.close(); } catch (Exception ignored) {}
        }
        if (!tmp.renameTo(dest)) {
            // rename 失败（跨文件系统等）时退化为覆盖写
            FileOutputStream o2 = new FileOutputStream(dest);
            try {
                o2.write(data);
                o2.flush();
                try { o2.getFD().sync(); } catch (Exception ignored) {}
            } finally {
                try { o2.close(); } catch (Exception ignored) {}
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
