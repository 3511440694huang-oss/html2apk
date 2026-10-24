package com.html2apk.shell;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 产物壳 MainActivity。
 *
 * 设计要点：
 *  - 页面通过虚拟 https origin 加载（见 AssetServer），修复 file:// 下
 *    ES Module / fetch / IndexedDB / localStorage origin 导致的白屏；
 *  - 注入 shim（下载、大容量存储、文件选择、错误诊断）；
 *  - 所有页面写文件/读存储的桥调用都做 origin 校验，防止外部网页调用。
 */
@SuppressLint("SetJavaScriptEnabled")
public class MainActivity extends Activity {

    private static final String TAG = "HTML2APK.Shell";
    static final String VERSION = "1.2.0";
    private static final int REQ_FILE_CHOOSER = 2001;
    private static final int REQ_PICK_FILES = 2002;
    private static final int REQ_STORAGE_PERM = 2003;
    private static final int MAX_PICK_BYTES = 32 * 1024 * 1024;

    private Config cfg;
    private Storage storage;
    private FileSaver saver;
    private AssetServer assetServer;
    private WebView webView;
    private FrameLayout root;
    private ProgressBar progress;
    private View loadingView;
    private TextView loadingLabel;
    private View errorView;
    private FrameLayout customContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private WebView legacyWebView;
    private boolean migrationStarted;
    private static final String MIGRATE_URL = "file:///android_asset/__ht2a_migrate.html";
    private static final String PREFS = "ht2a_shell";
    private static final String KEY_MIGRATED = "file_storage_migrated_v1";
    private ValueCallback<Uri[]> fileChooserCallback;
    private volatile String pendingPickId;
    private volatile boolean pendingPickMultiple;
    private volatile String lastError = "";
    /** 当前主文档 URL（缓存，供 JavaBridge 线程判断 origin，避免跨线程调用 WebView） */
    private volatile String trustedUrl = "";
    private int insetTop, insetBottom, insetLeft, insetRight;
    private final ArrayDeque<String> consoleLog = new ArrayDeque<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int bgColor = 0xFF111111;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = Config.load(this);
        storage = new Storage(this);
        // session 命名空间只用于表单恢复：每次进程启动清空，刷新页面仍保留
        try { storage.clear("session"); } catch (Exception ignored) {}
        saver = new FileSaver(this);
        assetServer = new AssetServer(this, cfg.entryFile);
        bgColor = parseColor(cfg.backgroundColor, 0xFF111111);

        applyWindow();
        buildRoot();
        if (shouldMigrateLegacyStorage()) {
            startLegacyMigration();
        } else {
            try {
                createWebView();
            } catch (Throwable t) {
                showError("无法创建 WebView", stackTrace(t));
            }
        }

        if (Build.VERSION.SDK_INT <= 28 &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            try {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQ_STORAGE_PERM);
            } catch (Exception ignored) {}
        }
    }

    /* ------------------------------------------------------------------ */
    /* 窗口 / 根布局                                                       */
    /* ------------------------------------------------------------------ */

    private void applyWindow() {
        Window w = getWindow();
        try {
            w.setBackgroundDrawable(new ColorDrawable(bgColor));
        } catch (Exception ignored) {}
        int status = parseColor(cfg.statusBarColor, bgColor);
        int nav = parseColor(cfg.navBarColor, bgColor);
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                w.setStatusBarColor(status);
                w.setNavigationBarColor(nav);
            } catch (Exception ignored) {}
        }
        if (cfg.keepScreenOn) {
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        if (cfg.fullscreen) {
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    w.setDecorFitsSystemWindows(false);
                    WindowInsetsController c = w.getInsetsController();
                    if (c != null) {
                        c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                        c.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                } catch (Exception ignored) {}
            } else {
                w.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } else {
            applySystemBarIcons(status, nav);
        }
        String o = cfg.orientation;
        if ("portrait".equals(o)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if ("landscape".equals(o)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    /** 根据状态栏/导航栏颜色决定图标明暗（深色背景 → 浅色图标） */
    private void applySystemBarIcons(int status, int nav) {
        boolean lightStatus = luminance(status) > 0.6;
        boolean lightNav = luminance(nav) > 0.6;
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                WindowInsetsController c = w.getInsetsController();
                if (c != null) {
                    int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    int appearance = 0;
                    if (lightStatus) appearance |= WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                    if (lightNav) appearance |= WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(appearance, mask);
                }
            } catch (Exception ignored) {}
        } else {
            int flags = w.getDecorView().getSystemUiVisibility();
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            if (lightStatus) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (lightNav) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            w.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private void buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(bgColor);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(progress, plp);

        LinearLayout loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(bgColor);
        ProgressBar spinner = new ProgressBar(this);
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView label = new TextView(this);
        label.setText("正在加载页面…");
        loadingLabel = label;
        label.setTextColor(0xFF9AA4B2);
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        loading.addView(label, lp);
        loadingView = loading;
        root.addView(loading, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                captureInsets(insets);
                return insets;
            }
        });
        setContentView(root);
        try { root.requestApplyInsets(); } catch (Exception ignored) {}
    }

    /* ------------------------------------------------------------------ */
    /* 旧版 file:// localStorage 迁移                                       */
    /* ------------------------------------------------------------------ */

    /**
     * 1.2.0 把页面从 file:// 换成虚拟 https origin，旧 APK（file:// 时代）的
     * localStorage 属于另一个 origin，直接读不到。首次升级时用一个隐藏 WebView
     * 打开 file:// 迁移页，逐 key 读出并写入新的原生存储，避免老用户“数据消失”。
     */
    private boolean shouldMigrateLegacyStorage() {
        try {
            if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_MIGRATED, false)) {
                return false;
            }
            if (!"[]".equals(storage.keysJson("local"))) {
                // 新存储已有数据（例如 1.2 beta 用户），不需要再迁
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_MIGRATED, true).apply();
                return false;
            }
            // 迁移页必须存在；否则直接跳过
            java.io.InputStream in = getAssets().open("__ht2a_migrate.html");
            in.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void startLegacyMigration() {
        migrationStarted = true;
        try {
            if (loadingLabel != null) loadingLabel.setText("正在迁移旧版数据…");
            WebView wv = new WebView(this);
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setAllowFileAccess(true);
            s.setAllowContentAccess(true);
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(true);
            wv.setBackgroundColor(bgColor);
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    readLegacyKeys(view);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                                            WebResourceError error) {
                    if (request != null && !request.isForMainFrame()) return;
                    finishLegacyMigration();
                }
            });
            legacyWebView = wv;
            root.addView(wv, new FrameLayout.LayoutParams(1, 1));
            wv.loadUrl(MIGRATE_URL);
            // 迁移最多等 15 秒，避免旧数据损坏时一直卡在加载页
            main.postDelayed(new Runnable() {
                @Override public void run() { finishLegacyMigration(); }
            }, 15000);
        } catch (Throwable t) {
            Log.w(TAG, "legacy migration start failed", t);
            finishLegacyMigration();
        }
    }

    private void readLegacyKeys(final WebView view) {
        if (!migrationStarted) return;
        try {
            view.evaluateJavascript("JSON.stringify(Object.keys(localStorage))", new ValueCallback<String>() {
                @Override public void onReceiveValue(String value) {
                    JSONArray keys;
                    try {
                        keys = new JSONArray(value == null || "null".equals(value) ? "[]" : value);
                    } catch (Exception e) {
                        keys = new JSONArray();
                    }
                    if (keys.length() == 0) {
                        finishLegacyMigration();
                        return;
                    }
                    migrateKeyAt(view, keys, 0);
                }
            });
        } catch (Throwable t) {
            finishLegacyMigration();
        }
    }

    private void migrateKeyAt(final WebView view, final JSONArray keys, final int index) {
        if (!migrationStarted) return;
        if (index >= keys.length()) {
            finishLegacyMigration();
            return;
        }
        final String key = keys.optString(index);
        if (key == null || key.isEmpty()) {
            migrateKeyAt(view, keys, index + 1);
            return;
        }
        try {
            view.evaluateJavascript("localStorage.getItem(" + JSONObject.quote(key) + ")",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            String v = decodeJsString(value);
                            if (v != null) {
                                try { storage.set("local", key, v); } catch (Exception ignored) {}
                            }
                            migrateKeyAt(view, keys, index + 1);
                        }
                    });
        } catch (Throwable t) {
            migrateKeyAt(view, keys, index + 1);
        }
    }

    /** evaluateJavascript 返回的是 JSON 字面量；把字符串解码，null 保持 null */
    private static String decodeJsString(String value) {
        if (value == null || "null".equals(value)) return null;
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            try {
                return new JSONArray("[" + value + "]").optString(0);
            } catch (Exception e) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private void finishLegacyMigration() {
        if (!migrationStarted) return;
        migrationStarted = false;
        if (isFinishing() || isDestroyed()) return;
        if (loadingLabel != null) loadingLabel.setText("正在加载页面…");
        try {
            if (legacyWebView != null) {
                root.removeView(legacyWebView);
                legacyWebView.destroy();
                legacyWebView = null;
            }
        } catch (Exception ignored) {}
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_MIGRATED, true).apply();
        } catch (Exception ignored) {}
        if (webView == null) {
            try {
                createWebView();
            } catch (Throwable t) {
                showError("无法创建 WebView", stackTrace(t));
            }
        }
    }

    private void createWebView() {
        WebView wv = new WebView(this);
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadsImagesAutomatically(true);
        s.setBlockNetworkImage(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setTextZoom(cfg.textZoom);
        try {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        } catch (Exception ignored) {}
        try {
            String ua = s.getUserAgentString();
            if (ua != null && !ua.contains("HTML2APK/")) {
                s.setUserAgentString(ua + cfg.userAgentSuffix);
            }
        } catch (Exception ignored) {}
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(wv, true);
        } catch (Exception ignored) {}

        wv.setBackgroundColor(bgColor);
        wv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        wv.setWebViewClient(new Client());
        wv.setWebChromeClient(new Chrome());
        wv.setDownloadListener(new Downloader());
        Bridge bridge = new Bridge();
        wv.addJavascriptInterface(bridge, "HT2A");
        wv.addJavascriptInterface(bridge, "HTML2APK");

        webView = wv;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(wv, 0, lp);
        if (cfg.debug) {
            try { WebView.setWebContentsDebuggingEnabled(true); } catch (Exception ignored) {}
        }
        trustedUrl = "";
        wv.loadUrl(AssetServer.ORIGIN + "/" + Uri.encode(cfg.entryFile, "/"));
    }

    private void reloadPage() {
        if (webView == null) {
            try {
                createWebView();
            } catch (Throwable t) {
                showError("无法创建 WebView", stackTrace(t));
            }
            return;
        }
        hideError();
        webView.reload();
    }

    private void recreateWebView() {
        if (webView != null) {
            try {
                root.removeView(webView);
                webView.destroy();
            } catch (Exception ignored) {}
            webView = null;
        }
        hideError();
        try {
            createWebView();
        } catch (Throwable t) {
            showError("页面进程崩溃后无法恢复", stackTrace(t));
        }
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public void onBackPressed() {
        if (migrationStarted) {
            finishLegacyMigration();
            return;
        }
        if (customContainer != null) {
            hideCustomView();
            return;
        }
        if (errorView != null && errorView.getVisibility() == View.VISIBLE) {
            hideError();
            reloadPage();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            try { webView.onPause(); } catch (Exception ignored) {}
        }
        try { CookieManager.getInstance().flush(); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            try { webView.onResume(); } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (legacyWebView != null) {
                root.removeView(legacyWebView);
                legacyWebView.destroy();
                legacyWebView = null;
            }
        } catch (Exception ignored) {}
        try {
            if (webView != null) {
                root.removeView(webView);
                webView.destroy();
                webView = null;
            }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    /* ------------------------------------------------------------------ */
    /* WebViewClient                                                       */
    /* ------------------------------------------------------------------ */

    private final class Client extends WebViewClient {
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            try {
                return assetServer.handle(request);
            } catch (Throwable t) {
                Log.e(TAG, "intercept failed", t);
                return null;
            }
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            try {
                return handleUrl(request.getUrl().toString());
            } catch (Exception e) {
                return false;
            }
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            trustedUrl = url == null ? "" : url;
            hideError();
            if (loadingView != null) loadingView.setVisibility(View.VISIBLE);
            if (progress != null) {
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            trustedUrl = url == null ? "" : url;
            if (progress != null) {
                progress.setProgress(100);
                progress.setVisibility(View.GONE);
            }
            main.postDelayed(new Runnable() {
                @Override public void run() {
                    if (loadingView != null) loadingView.setVisibility(View.GONE);
                }
            }, 120);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request == null || !request.isForMainFrame()) return;
            String desc = "";
            int code = 0;
            try {
                desc = String.valueOf(error.getDescription());
                code = error.getErrorCode();
            } catch (Exception ignored) {}
            showError("页面加载失败", "错误码: " + code + "\n" + desc + "\n\nURL: " + request.getUrl()
                    + "\n入口: " + cfg.entryFile + "\n\n提示：请确认入口文件已打包进 APK，"
                    + "且页面依赖的资源（JS/CSS/图片）在同一个文件夹里。");
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            if (request == null || !request.isForMainFrame()) return;
            int status = 0;
            try { status = errorResponse.getStatusCode(); } catch (Exception ignored) {}
            if (status >= 400) {
                showError("页面返回 HTTP " + status,
                        "URL: " + request.getUrl() + "\n入口: " + cfg.entryFile
                                + "\n\n通常表示入口路径不存在，或资源没有一起打包。");
            }
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            boolean crashed = false;
            try { crashed = detail.didCrash(); } catch (Exception ignored) {}
            try {
                root.removeView(view);
                view.destroy();
            } catch (Exception ignored) {}
            webView = null;
            showError("页面渲染进程已结束", (crashed ? "WebView 渲染进程崩溃。\n" : "系统回收了渲染进程。\n")
                    + "常见原因：页面占用内存过大（超大 HTML / 图片），或页面脚本死循环。\n"
                    + "点击「重新加载」会重新创建页面；若反复出现，请压缩 HTML 或减少首屏数据量。");
            return true;
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                       android.net.http.SslError error) {
            // 保持安全默认：取消证书有问题的请求
            try { handler.cancel(); } catch (Exception ignored) {}
            toast("已拦截证书不安全的连接");
        }
    }

    private boolean handleUrl(String url) {
        if (url == null) return false;
        if (url.startsWith(AssetServer.ORIGIN)) return false;
        if (url.startsWith("about:") || url.startsWith("data:") || url.startsWith("javascript:")) {
            return false;
        }
        if (url.startsWith("blob:")) {
            // 顶层导航到 blob：交给 shim 保存，避免 WebView 显示空白
            evaluateJs("window.__ht2aSaveBlobUrl && window.__ht2aSaveBlobUrl("
                    + JSONObject.quote(url) + ")");
            return true;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return false; // 站内/站外网页都留在 WebView（桥调用有 origin 校验）
        }
        if (url.startsWith("file://")) {
            return false;
        }
        if (url.startsWith("intent:")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                startActivity(intent);
                return true;
            } catch (Exception e) {
                try {
                    Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null) {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fallback)));
                    }
                } catch (Exception ignored) {}
                return true;
            }
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            toast("无法打开链接: " + url);
        }
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* WebChromeClient                                                     */
    /* ------------------------------------------------------------------ */

    private final class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (progress == null) return;
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                         FileChooserParams params) {
            if (fileChooserCallback != null) {
                try { fileChooserCallback.onReceiveValue(null); } catch (Exception ignored) {}
            }
            fileChooserCallback = callback;
            Intent intent;
            try {
                intent = params.createIntent();
            } catch (Exception e) {
                fileChooserCallback = null;
                try { callback.onReceiveValue(null); } catch (Exception ignored) {}
                return false;
            }
            try {
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(intent, REQ_FILE_CHOOSER);
                return true;
            } catch (ActivityNotFoundException e) {
                fileChooserCallback = null;
                try { callback.onReceiveValue(null); } catch (Exception ignored) {}
                toast("没有可用的文件选择器");
                return false;
            } catch (Exception e) {
                fileChooserCallback = null;
                try { callback.onReceiveValue(null); } catch (Exception ignored) {}
                toast("无法打开文件选择器: " + e.getMessage());
                return false;
            }
        }

        @Override
        public boolean onJsAlert(WebView view, String url, String message,
                                 final android.webkit.JsResult result) {
            try {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(hostOf(url))
                        .setMessage(message == null ? "" : message)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) { result.confirm(); }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            @Override public void onCancel(android.content.DialogInterface d) { result.cancel(); }
                        })
                        .show();
            } catch (Exception e) {
                result.confirm();
            }
            return true;
        }

        @Override
        public boolean onJsConfirm(WebView view, String url, String message,
                                   final android.webkit.JsResult result) {
            try {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(hostOf(url))
                        .setMessage(message == null ? "" : message)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) { result.confirm(); }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) { result.cancel(); }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            @Override public void onCancel(android.content.DialogInterface d) { result.cancel(); }
                        })
                        .show();
            } catch (Exception e) {
                result.cancel();
            }
            return true;
        }

        @Override
        public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                  final android.webkit.JsPromptResult result) {
            try {
                final EditText input = new EditText(MainActivity.this);
                input.setText(defaultValue == null ? "" : defaultValue);
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(hostOf(url))
                        .setMessage(message == null ? "" : message)
                        .setView(input)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm(input.getText().toString());
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) { result.cancel(); }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            @Override public void onCancel(android.content.DialogInterface d) { result.cancel(); }
                        })
                        .show();
            } catch (Exception e) {
                result.cancel();
            }
            return true;
        }

        @Override
        public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
            if (msg == null) return true;
            String line = msg.message() + " (" + msg.sourceId() + ":" + msg.lineNumber() + ")";
            Log.d(TAG, "console: " + line);
            if (consoleLog.size() >= 200) consoleLog.removeFirst();
            consoleLog.addLast(msg.messageLevel() + ": " + line);
            return true;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customContainer != null) {
                try { callback.onCustomViewHidden(); } catch (Exception ignored) {}
                return;
            }
            try {
                customContainer = new FrameLayout(MainActivity.this);
                customContainer.setBackgroundColor(Color.BLACK);
                customContainer.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                root.addView(customContainer, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                if (webView != null) webView.setVisibility(View.GONE);
                customViewCallback = callback;
                if (Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController c = getWindow().getInsetsController();
                    if (c != null) {
                        c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                        c.setSystemBarsBehavior(
                                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    }
                } else {
                    getWindow().getDecorView().setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
                }
            } catch (Exception e) {
                Log.e(TAG, "show custom view failed", e);
            }
        }

        @Override
        public void onHideCustomView() {
            hideCustomView();
        }

        @Override
        public void onPermissionRequest(final PermissionRequest request) {
            try { request.deny(); } catch (Exception ignored) {}
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin,
                                                       android.webkit.GeolocationPermissions.Callback callback) {
            try { callback.invoke(origin, false, false); } catch (Exception ignored) {}
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture,
                                      android.os.Message resultMsg) {
            return false;
        }
    }

    private void hideCustomView() {
        if (customContainer == null) return;
        try {
            root.removeView(customContainer);
        } catch (Exception ignored) {}
        customContainer = null;
        if (webView != null) webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            try { customViewCallback.onCustomViewHidden(); } catch (Exception ignored) {}
            customViewCallback = null;
        }
        applyWindow();
    }

    /* ------------------------------------------------------------------ */
    /* 下载                                                                */
    /* ------------------------------------------------------------------ */

    private final class Downloader implements DownloadListener {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                    String mimeType, long contentLength) {
            if (url == null) return;
            if (url.startsWith("blob:") || url.startsWith("data:")
                    || url.startsWith(AssetServer.ORIGIN)) {
                // 虚拟 origin 的 URL 只有页面内的 shim 能读到，不能交给 DownloadManager
                evaluateJs("window.__ht2aSaveBlobUrl && window.__ht2aSaveBlobUrl("
                        + JSONObject.quote(url) + ")");
                toast("正在保存文件…");
                return;
            }
            if (url.startsWith("file:")) {
                try {
                    File f = new File(Uri.parse(url).getPath());
                    byte[] data = readFile(f, MAX_PICK_BYTES);
                    String json = saver.saveBytes(
                            FileSaver.fileNameFromDisposition(contentDisposition, url, mimeType),
                            mimeType, data);
                    Log.i(TAG, "file save: " + json);
                } catch (Exception e) {
                    toast("保存失败: " + e.getMessage());
                }
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return;
            }
            try {
                String name = FileSaver.fileNameFromDisposition(contentDisposition, url, mimeType);
                DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
                req.setMimeType(mimeType);
                if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
                try {
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) req.addRequestHeader("Cookie", cookie);
                } catch (Exception ignored) {}
                req.setTitle(name);
                req.setDescription("HTML2APK 下载");
                req.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                req.setAllowedOverMetered(true);
                req.setAllowedOverRoaming(false);
                req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                        FileSaver.SUB_DIR + "/" + name);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm == null) throw new IllegalStateException("DownloadManager 不可用");
                dm.enqueue(req);
                toast("已开始下载: " + name);
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                toast("下载失败，已尝试用浏览器打开");
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception ignored) {}
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Activity result                                                     */
    /* ------------------------------------------------------------------ */

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILE_CHOOSER) {
            if (fileChooserCallback != null) {
                Uri[] result = null;
                if (resultCode == RESULT_OK) {
                    try {
                        result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                    } catch (Exception ignored) {}
                }
                try { fileChooserCallback.onReceiveValue(result); } catch (Exception ignored) {}
                fileChooserCallback = null;
            }
            return;
        }
        if (requestCode == REQ_PICK_FILES) {
            final String id = pendingPickId;
            pendingPickId = null;
            if (resultCode != RESULT_OK || data == null) {
                jsPickResult(id, "{\"canceled\":true}");
                return;
            }
            final List<Uri> uris = new ArrayList<>();
            try {
                ClipData clip = data.getClipData();
                if (clip != null) {
                    for (int i = 0; i < clip.getItemCount(); i++) {
                        Uri u = clip.getItemAt(i).getUri();
                        if (u != null) uris.add(u);
                    }
                }
                if (uris.isEmpty() && data.getData() != null) {
                    uris.add(data.getData());
                }
            } catch (Exception ignored) {}
            if (uris.isEmpty()) {
                jsPickResult(id, "{\"canceled\":true}");
                return;
            }
            new Thread(new Runnable() {
                @Override public void run() {
                    final String json = readPickPayload(uris);
                    main.post(new Runnable() {
                        @Override public void run() { jsPickResult(id, json); }
                    });
                }
            }, "ht2a-pick").start();
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private String readPickPayload(List<Uri> uris) {
        try {
            JSONArray files = new JSONArray();
            for (Uri u : uris) {
                JSONObject o = new JSONObject();
                try {
                    String name = FileSaver.displayName(this, u);
                    String mime = getContentResolver().getType(u);
                    if (mime == null) mime = "application/octet-stream";
                    InputStream in = getContentResolver().openInputStream(u);
                    if (in == null) throw new Exception("无法读取文件");
                    byte[] data;
                    try {
                        data = readAll(in, MAX_PICK_BYTES);
                    } finally {
                        try { in.close(); } catch (Exception ignored) {}
                    }
                    o.put("name", name);
                    o.put("mime", mime);
                    o.put("size", data.length);
                    o.put("base64", Base64.encodeToString(data, Base64.NO_WRAP));
                } catch (Exception e) {
                    o.put("error", e.getMessage() == null ? "读取失败" : e.getMessage());
                }
                files.put(o);
            }
            JSONObject out = new JSONObject();
            out.put("files", files);
            return out.toString();
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        }
    }

    private void jsPickResult(String id, String json) {
        if (id == null || webView == null) return;
        evaluateJs("window.__ht2aPickResult && window.__ht2aPickResult("
                + JSONObject.quote(id) + "," + JSONObject.quote(json) + ")");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE_PERM) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                toast("未授予存储权限：文件将保存到应用专属目录");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* JS Bridge                                                           */
    /* ------------------------------------------------------------------ */

    private boolean trusted() {
        String u = trustedUrl;
        return u != null && u.startsWith(AssetServer.ORIGIN + "/");
    }

    private void captureInsets(WindowInsets insets) {
        if (insets == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars());
                insetTop = i.top;
                insetBottom = i.bottom;
                insetLeft = i.left;
                insetRight = i.right;
            } else {
                insetTop = insets.getSystemWindowInsetTop();
                insetBottom = insets.getSystemWindowInsetBottom();
                insetLeft = insets.getSystemWindowInsetLeft();
                insetRight = insets.getSystemWindowInsetRight();
            }
        } catch (Exception ignored) {}
    }

    private final class Bridge {

        @JavascriptInterface
        public String getInfo() {
            JSONObject o = new JSONObject();
            try {
                o.put("version", VERSION);
                o.put("package", getPackageName());
                o.put("appName", appLabel());
                o.put("entry", cfg.entryFile);
                o.put("origin", AssetServer.ORIGIN);
                o.put("debug", cfg.debug);
                o.put("formRestore", cfg.formRestore);
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("device", Build.MANUFACTURER + " " + Build.MODEL);
                o.put("downloadDir", "Download/" + FileSaver.SUB_DIR);
                o.put("webview", WebSettings.getDefaultUserAgent(MainActivity.this));
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String storageGet(String ns, String key) {
            if (!trusted()) return null;
            return storage.get(ns, key);
        }

        @JavascriptInterface
        public String storageSet(String ns, String key, String value) {
            if (!trusted()) return "非受信任页面";
            return storage.set(ns, key, value);
        }

        @JavascriptInterface
        public boolean storageRemove(String ns, String key) {
            if (!trusted()) return false;
            return storage.remove(ns, key);
        }

        @JavascriptInterface
        public boolean storageClear(String ns) {
            if (!trusted()) return false;
            return storage.clear(ns);
        }

        @JavascriptInterface
        public String storageKeys(String ns) {
            if (!trusted()) return "[]";
            return storage.keysJson(ns);
        }

        @JavascriptInterface
        public String storageInfo() {
            if (!trusted()) return "{}";
            return storage.infoJson();
        }

        @JavascriptInterface
        public String saveBegin(String name, String mime) {
            if (!trusted()) return "ERR:非受信任页面";
            return saver.begin(name, mime);
        }

        @JavascriptInterface
        public String saveChunk(String token, String base64) {
            if (!trusted()) return "非受信任页面";
            return saver.write(token, base64);
        }

        @JavascriptInterface
        public String saveEnd(String token) {
            if (!trusted()) return "{\"ok\":false,\"error\":\"非受信任页面\"}";
            return saver.end(token);
        }

        @JavascriptInterface
        public void saveAbort(String token) {
            if (!trusted()) return;
            saver.abort(token);
        }

        @JavascriptInterface
        public String saveBase64(String name, String mime, String base64) {
            if (!trusted()) return "{\"ok\":false,\"error\":\"非受信任页面\"}";
            try {
                byte[] data = Base64.decode(base64 == null ? "" : base64, Base64.DEFAULT);
                return saver.saveBytes(name, mime, data);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
            }
        }

        @JavascriptInterface
        public void pickFiles(String mimes, boolean multiple, String callbackId) {
            if (!trusted()) return;
            if (callbackId == null) return;
            pendingPickId = callbackId;
            pendingPickMultiple = multiple;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        String[] parts = null;
                        if (mimes != null && mimes.contains(",")) {
                            parts = mimes.split(",");
                        }
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.setType("*/*");
                        if (parts != null) {
                            intent.putExtra(Intent.EXTRA_MIME_TYPES, parts);
                        }
                        if (pendingPickMultiple) {
                            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        }
                        startActivityForResult(intent, REQ_PICK_FILES);
                    } catch (Exception e) {
                        String id = pendingPickId;
                        pendingPickId = null;
                        jsPickResult(id, "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}");
                    }
                }
            });
        }

        @JavascriptInterface
        public void toast(String msg) {
            if (!trusted()) return;
            final String m = msg == null ? "" : msg;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    Toast.makeText(MainActivity.this, m, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void copy(String text) {
            if (!trusted()) return;
            copyText(text);
        }

        @JavascriptInterface
        public void log(String level, String msg) {
            if (!trusted()) return;
            Log.println(level != null && level.equalsIgnoreCase("error") ? Log.ERROR : Log.INFO,
                    TAG, "page: " + msg);
        }

        @JavascriptInterface
        public void reload() {
            if (!trusted()) return;
            runOnUiThread(new Runnable() {
                @Override public void run() { reloadPage(); }
            });
        }

        @JavascriptInterface
        public void exitApp() {
            if (!trusted()) return;
            runOnUiThread(new Runnable() {
                @Override public void run() { finish(); }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (!trusted() || url == null) return;
            try {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setKeepScreenOn(final boolean on) {
            if (!trusted()) return;
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }
            });
        }

        @JavascriptInterface
        public String getInsets() {
            if (!trusted()) return "{}";
            JSONObject o = new JSONObject();
            try {
                o.put("top", insetTop);
                o.put("bottom", insetBottom);
                o.put("left", insetLeft);
                o.put("right", insetRight);
                o.put("density", getResources().getDisplayMetrics().density);
            } catch (Exception ignored) {}
            return o.toString();
        }

        @JavascriptInterface
        public String getLastError() {
            if (!trusted()) return "";
            return lastError;
        }

        @JavascriptInterface
        public void requestStoragePermission() {
            if (!trusted()) return;
            if (Build.VERSION.SDK_INT <= 28) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        try {
                            requestPermissions(new String[]{
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
                        } catch (Exception ignored) {}
                    }
                });
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 错误页 / 工具函数                                                    */
    /* ------------------------------------------------------------------ */

    private void showError(String title, String detail) {
        lastError = title + "\n" + detail;
        try {
            if (errorView != null) {
                root.removeView(errorView);
                errorView = null;
            }
            ScrollView scroll = new ScrollView(this);
            scroll.setBackgroundColor(0xF0101319);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(28), dp(20), dp(24));

            TextView h = new TextView(this);
            h.setText(title == null ? "出错了" : title);
            h.setTextColor(0xFFFFB4A2);
            h.setTextSize(18);
            h.setGravity(Gravity.START);
            box.addView(h);

            TextView body = new TextView(this);
            body.setText((detail == null ? "" : detail) + "\n\n"
                    + "设备: " + Build.MANUFACTURER + " " + Build.MODEL
                    + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                    + "\n入口: " + cfg.entryFile
                    + "\n壳版本: " + VERSION);
            body.setTextColor(0xFFD6DEEA);
            body.setTextSize(12);
            body.setTypeface(android.graphics.Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(14);
            box.addView(body, blp);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(22);
            Button retry = new Button(this);
            retry.setText("重新加载");
            retry.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { reloadPage(); }
            });
            row.addView(retry, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button copy = new Button(this);
            copy.setText("复制错误");
            copy.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { copyText(diagnostics()); }
            });
            row.addView(copy, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            Button exit = new Button(this);
            exit.setText("退出");
            exit.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { finish(); }
            });
            row.addView(exit, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            box.addView(row, rlp);

            TextView hint = new TextView(this);
            hint.setText("提示：长按上方文字可复制；把这段内容发给打包工具开发者可快速定位问题。");
            hint.setTextColor(0xFF8A94A6);
            hint.setTextSize(11);
            LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.topMargin = dp(16);
            box.addView(hint, hlp);

            scroll.addView(box);
            errorView = scroll;
            root.addView(scroll, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            errorView.bringToFront();
            if (progress != null) progress.setVisibility(View.GONE);
            if (loadingView != null) loadingView.setVisibility(View.GONE);
        } catch (Exception e) {
            Log.e(TAG, "showError failed", e);
        }
    }

    private void hideError() {
        if (errorView != null) {
            try { root.removeView(errorView); } catch (Exception ignored) {}
            errorView = null;
        }
    }

    private String diagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("HTML2APK 诊断\n");
        sb.append("壳版本: ").append(VERSION).append('\n');
        sb.append("包名: ").append(getPackageName()).append('\n');
        sb.append("入口: ").append(cfg.entryFile).append('\n');
        sb.append("URL: ").append(webView == null ? "-" : String.valueOf(webView.getUrl())).append('\n');
        sb.append("设备: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("WebView: ").append(WebSettings.getDefaultUserAgent(this)).append('\n');
        sb.append("存储: ").append(storage.infoJson()).append('\n');
        sb.append("\n--- 最后错误 ---\n").append(lastError).append('\n');
        if (!consoleLog.isEmpty()) {
            sb.append("\n--- 控制台（最近） ---\n");
            for (String s : consoleLog) sb.append(s).append('\n');
        }
        sb.append("\n时间: ").append(new java.util.Date().toString()).append('\n');
        return sb.toString();
    }

    private String appLabel() {
        try {
            return String.valueOf(getApplicationInfo().loadLabel(getPackageManager()));
        } catch (Exception e) {
            return getPackageName();
        }
    }

    private void copyText(String text) {
        final String s = text == null ? "" : text;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("HTML2APK", s));
                        Toast.makeText(MainActivity.this, "已复制", Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void evaluateJs(String js) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    if (webView != null) webView.evaluateJavascript(js, null);
                } catch (Exception ignored) {}
            }
        });
    }

    private void toast(final String msg) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            }
        });
    }

    private static String hostOf(String url) {
        try {
            Uri u = Uri.parse(url);
            String h = u.getHost();
            return h == null ? "页面" : h;
        } catch (Exception e) {
            return "页面";
        }
    }

    private static int parseColor(String color, int def) {
        try {
            return Color.parseColor(color);
        } catch (Exception e) {
            return def;
        }
    }

    private static double luminance(int color) {
        double r = Color.red(color) / 255.0;
        double g = Color.green(color) / 255.0;
        double b = Color.blue(color) / 255.0;
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static byte[] readFile(File f, int max) throws Exception {
        java.io.FileInputStream in = new java.io.FileInputStream(f);
        try {
            return readAll(in, max);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readAll(InputStream in, int max) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new Exception("文件超过 " + (max / 1024 / 1024) + "MB 限制");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
