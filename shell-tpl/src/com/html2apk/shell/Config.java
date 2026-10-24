package com.html2apk.shell;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 可选配置：assets/ht2a.config（key = value 每行，支持 # 注释）。
 * 缺省使用默认值。构建时由 HTML2APK 生成该文件。
 * 解析绝不抛异常：配置有问题时回退默认值，保证壳能启动。
 */
public class Config {
    public String entryFile = "index.html";
    public String statusBarColor = "#111111";
    public String navBarColor = "#111111";
    public String backgroundColor = "#111111";
    public String orientation = "auto";
    public boolean debug = false;
    public boolean fullscreen = false;
    public boolean keepScreenOn = false;
    public boolean formRestore = true;
    public int textZoom = 100;
    public String userAgentSuffix = " HTML2APK/1.2";

    public static Config load(Context ctx) {
        Config c = new Config();
        Map<String, String> kv = new HashMap<>();
        BufferedReader br = null;
        try {
            br = new BufferedReader(new InputStreamReader(ctx.getAssets().open("ht2a.config"), "UTF-8"));
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) {
                    first = false;
                    if (line.length() > 0 && line.charAt(0) == '\uFEFF') {
                        line = line.substring(1);
                    }
                }
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                    continue;
                }
                int idx = line.indexOf('=');
                if (idx < 0) {
                    continue;
                }
                String k = line.substring(0, idx).trim();
                String v = line.substring(idx + 1).trim();
                if (!k.isEmpty()) {
                    kv.put(k, v);
                }
            }
        } catch (Exception ignored) {
            // 无配置文件则全部默认
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ignored) {}
            }
        }

        c.entryFile = normalizeEntry(get(kv, "entryFile", c.entryFile));
        c.statusBarColor = color(get(kv, "statusBarColor", c.statusBarColor), c.statusBarColor);
        c.navBarColor = color(get(kv, "navBarColor", c.navBarColor), c.navBarColor);
        c.backgroundColor = color(get(kv, "backgroundColor", c.backgroundColor), c.backgroundColor);
        c.orientation = normalizeOrientation(get(kv, "orientation", c.orientation));
        c.debug = bool(get(kv, "debug", "0"));
        c.fullscreen = bool(get(kv, "fullscreen", "0"));
        c.keepScreenOn = bool(get(kv, "keepScreenOn", "0"));
        c.formRestore = bool(get(kv, "formRestore", "1"));
        c.textZoom = clampInt(get(kv, "textZoom", "100"), 50, 200, 100);
        String ua = get(kv, "userAgentSuffix", c.userAgentSuffix);
        c.userAgentSuffix = ua.length() > 120 ? ua.substring(0, 120) : ua;
        return c;
    }

    private static String get(Map<String, String> kv, String key, String def) {
        String v = kv.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    private static boolean bool(String v) {
        if (v == null) return false;
        v = v.trim().toLowerCase(Locale.US);
        return v.equals("1") || v.equals("true") || v.equals("yes") || v.equals("on");
    }

    private static int clampInt(String v, int min, int max, int def) {
        try {
            int n = Integer.parseInt(v.trim());
            return n < min ? min : (n > max ? max : n);
        } catch (Exception e) {
            return def;
        }
    }

    private static String normalizeOrientation(String v) {
        if (v == null) return "auto";
        v = v.trim().toLowerCase(Locale.US);
        if (v.equals("portrait") || v.equals("landscape")) return v;
        return "auto";
    }

    /** 只接受 #RRGGBB / #AARRGGBB，解析失败回退默认色 */
    private static String color(String v, String def) {
        if (v == null) return def;
        v = v.trim();
        if (!v.startsWith("#")) v = "#" + v;
        if (!v.matches("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")) return def;
        return v;
    }

    /** 入口必须是 assets 内的相对路径，禁止 .. 穿越与 URL 片段 */
    public static String normalizeEntry(String v) {
        if (v == null) return "index.html";
        v = v.trim().replace('\\', '/');
        int q = v.indexOf('?');
        if (q >= 0) v = v.substring(0, q);
        int h = v.indexOf('#');
        if (h >= 0) v = v.substring(0, h);
        while (v.startsWith("/")) v = v.substring(1);
        if (v.isEmpty()) return "index.html";
        String[] parts = v.split("/");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) continue;
            if (p.equals("..")) return "index.html";
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.length() == 0 ? "index.html" : sb.toString();
    }
}
