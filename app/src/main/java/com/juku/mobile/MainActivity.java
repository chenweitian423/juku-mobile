package com.juku.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "JukuMobile";
    private static final String PREFS = "juku_mobile";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    private static final String KEY_WEB_CACHE_VERSION = "web_cache_version";
    private static final String INSTALL_STATUS_ACTION = "com.juku.mobile.INSTALL_STATUS";
    private static final String DEFAULT_SERVER_URL = "https://duanju.sky423.cn:18888/";
    private static final String LEGACY_INTERNAL_HOST = "192.168.123.121";
    private static final String CURRENT_VERSION_NAME = "1.3.7";
    private static final int CURRENT_VERSION_CODE = 16;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int INSTALL_PERMISSION_REQUEST = 1002;
    private static final long AUTO_UPDATE_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final long MIN_FOREGROUND_RECHECK_MS = 30L * 60L * 1000L;

    private static final String NOTIFICATION_CHANNEL_ID = "juku_update";    private static final int NOTIFICATION_ID_UPDATE = 0x4A55; // "JU"
    private static final int MAX_DOWNLOAD_RETRY = 2;

    private FrameLayout contentRoot;
    private TextView fallbackMenuButton;
    private boolean immersiveNow;
    private WebView webView;
    private ProgressBar progressBar;
    private TextView errorView;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private ValueCallback<Uri[]> filePathCallback;
    private AlertDialog downloadDialog;
    private ProgressBar downloadProgress;
    private TextView downloadStatus;
    private HttpURLConnection activeUpdateConnection;
    private File pendingInstallFile;
    private boolean showingError;
    private boolean updateCheckRunning;
    private boolean autoUpdateChecked;
    private boolean installReceiverRegistered;
    private volatile boolean downloadCancelled;
    private long lastForegroundCheckAt;
    private boolean notificationChannelReady;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver installResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirmation != null) {
                    confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirmation);
                }
                return;
            }
            if (status == PackageInstaller.STATUS_SUCCESS) {
                pendingInstallFile = null;
                clearUpdateCache();
                Toast.makeText(MainActivity.this, "更新安装完成", Toast.LENGTH_SHORT).show();
                return;
            }
            String detail = message == null || message.trim().isEmpty()
                    ? "系统未返回具体原因" : message.trim();
            File failedApk = pendingInstallFile;
            pendingInstallFile = null;
            // 签名冲突类失败无法通过换安装器绕过，直接给出可执行指引，不再降级掩盖原因
            if (isSignatureConflict(detail)) {
                showSignatureConflictGuide(failedApk);
                return;
            }
            if (failedApk != null && failedApk.exists()) {
                // 先做一次本地预检：把「包本身有问题」和「安装器有问题」区分开
                String localIssue = precheckApk(failedApk);
                if (localIssue != null) {
                    Toast.makeText(MainActivity.this,
                            "更新包有问题：" + localIssue, Toast.LENGTH_LONG).show();
                    return;
                }
                Toast.makeText(MainActivity.this,
                        "系统安装失败（" + detail + "），正在改用浏览器安装方式",
                        Toast.LENGTH_LONG).show();
                installWithViewer(failedApk);
                return;
            }
            Toast.makeText(MainActivity.this,
                    "安装失败：" + detail, Toast.LENGTH_LONG).show();
        }
    };

    private final class ShellBridge {
        @JavascriptInterface
        public void setPlayerState(boolean active, boolean controlsHidden) {
            runOnUiThread(() -> syncImmersiveMode(active, controlsHidden));
        }

        /** 网页「更多」面板里注入的外壳条目被点击时调用：server / update / about。 */
        @JavascriptInterface
        public void nativeAction(String action) {
            runOnUiThread(() -> handleNativeAction(action));
        }

        /**
         * 网页上报「菜单入口是否可用」：
         * inpage   —— 网页顶部的「更多」按钮可见，外壳条目已挂在它的面板里，隐藏兜底按钮；
         * floating —— 网页没有这个入口（未登录时头部动作区被隐藏，或网页改版），
         *             显示兜底悬浮按钮，保证「服务器地址」这类唯一入口永远可达。
         */
        @JavascriptInterface
        public void setMenuEntry(String mode) {
            runOnUiThread(() -> {
                if (fallbackMenuButton != null) {
                    fallbackMenuButton.setVisibility("inpage".equals(mode) ? View.GONE : View.VISIBLE);
                }
            });
        }
    }

    /** 处理网页里注入条目的动作。 */
    private void handleNativeAction(String action) {
        if (action == null) {
            return;
        }
        switch (action) {
            case "server":
                showServerDialog();
                break;
            case "update":
                checkForUpdate(true);
                break;
            case "about":
                showAboutDialog();
                break;
            default:
                break;
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureWindow();
        buildContentView();
        registerInstallReceiver();
        configureWebView();
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            loadConfiguredServer();
        }
    }

    private void configureWindow() {
        getWindow().setStatusBarColor(Color.rgb(15, 17, 21));
        getWindow().setNavigationBarColor(Color.rgb(15, 17, 21));
    }

    private void buildContentView() {
        contentRoot = new FrameLayout(this);
        contentRoot.setBackgroundColor(Color.rgb(15, 17, 21));

        // 顶部安全区由外壳补：网页只处理了底部 safe-area（app.css 里 5 处
        // env(safe-area-inset-bottom)），顶部没有任何处理。
        // Android 15 起 targetSdk 35 走 edge-to-edge，内容默认绘制到状态栏下面，
        // 网页顶栏会被状态栏压掉半行且拉不出来。这里按实际 inset 下移内容。
        // 沉浸（全屏播放）时不留白，否则播放器会被顶出一条黑边。
        contentRoot.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
            } else {
                top = insets.getSystemWindowInsetTop();
            }
            if (immersiveNow) {
                top = 0;
            }
            if (view.getPaddingTop() != top) {
                view.setPadding(0, top, 0, 0);
            }
            return insets;
        });

        webView = new WebView(this);
        contentRoot.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3));
        progressParams.gravity = Gravity.TOP;
        contentRoot.addView(progressBar, progressParams);

        errorView = new TextView(this);
        errorView.setTextColor(Color.WHITE);
        errorView.setTextSize(16);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(dp(28), dp(28), dp(28), dp(28));
        errorView.setBackgroundColor(Color.rgb(15, 17, 21));
        errorView.setClickable(true);
        errorView.setFocusable(true);
        errorView.setOnClickListener(view -> {
            showingError = false;
            errorView.setVisibility(View.GONE);
            webView.reload();
        });
        errorView.setVisibility(View.GONE);
        contentRoot.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(contentRoot);
        installFallbackMenuButton();
    }

    /**
     * 兜底菜单入口：只有网页里没有可用的「更多」按钮时才显示
     * （未登录时网页会隐藏头部动作区，或网页改版），保证「服务器地址」等入口永远可达。
     */
    private void installFallbackMenuButton() {
        // 用文字「⋯」自绘，不用系统图标 —— android.R.drawable.ic_menu_more
        // 在各版本上长得像「下载」，容易被误认。
        TextView button = new TextView(this);
        button.setText("⋯");
        button.setTextColor(Color.WHITE);
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.argb(205, 20, 24, 31));
        background.setStroke(dp(1), Color.argb(80, 255, 255, 255));
        button.setBackground(background);
        button.setContentDescription("更多设置");
        button.setVisibility(View.GONE);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(38), dp(38));
        params.gravity = Gravity.TOP | Gravity.END;
        params.topMargin = dp(10);
        params.rightMargin = dp(10);
        button.setOnClickListener(view -> showShellMenu());
        fallbackMenuButton = button;
        contentRoot.addView(button, params);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(false);
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " JukuMobile/" + CURRENT_VERSION_NAME);
        clearWebCacheAfterUpgrade();
        webView.addJavascriptInterface(new ShellBridge(), "JukuShell");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrl(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                showingError = false;
                errorView.setVisibility(View.GONE);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                installShellBridge();
                maybeAutoCheckUpdate();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("无法连接服务器\n\n" + error.getDescription()
                            + "\n\n请检查手机网络，或从右上角菜单检查服务器地址。\n点此重试");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
                showError("HTTPS 证书校验失败，请在菜单中检查服务器地址。");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 无论是崩溃还是被系统回收，都重建 WebView 并跳回首页，避免白屏
                return handleRenderProcessGone();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
            }

            /**
             * 只把外壳自己打的日志（以 [juku] 开头）转到 logcat，
             * 便于定位「菜单条目注入是否成功」这类网页侧问题：
             *   adb logcat -s JukuMobile
             */
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null && message.message() != null
                        && message.message().startsWith("[juku]")) {
                    Log.i(LOG_TAG, "js " + message.message());
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException ignored) {
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                contentRoot.addView(customView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
                applyImmersiveMode(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                if (!URLUtil.isNetworkUrl(url)) {
                    Toast.makeText(MainActivity.this, "这个下载地址无法直接保存", Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimeType);
                    request.addRequestHeader("User-Agent", userAgent);
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null) {
                        request.addRequestHeader("Cookie", cookie);
                    }
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            URLUtil.guessFileName(url, contentDisposition, mimeType));
                    DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, "已开始下载", Toast.LENGTH_SHORT).show();
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "下载失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private boolean handleUrl(Uri uri) {
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this, "没有可以打开这个链接的应用", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private void loadConfiguredServer() {
        String address = normalizeServerUrl(preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        if (isLegacyInternalServer(address)) {
            address = DEFAULT_SERVER_URL;
        }
        preferences().edit().putString(KEY_SERVER_URL, address).apply();
        webView.loadUrl(address);
    }

    private void installShellBridge() {
        String script = "(function(){"
                // 菜单条目注入：直接挂进网页自己的「更多」面板（#morePanel），
                // 不再额外加一个 header 按钮 —— 那样会出现两个「⋯」，而且原生
                // AlertDialog 在部分 ROM 上是浅色，弹出来很突兀。
                // 放在 __jukuShellBridgeInstalled 短路之前：SPA 切换、登录状态变化都会
                // 重建头部 DOM，所以要靠定时器持续校正。
                + "var injectMenu=function(){"
                + "var panel=document.getElementById('morePanel');"
                + "if(!panel){return false;}"
                + "if(panel.querySelector('[data-juku-action]')){return true;}"
                + "var anchor=panel.querySelector('#libraryMenuUpdatedAt');"
                + "var label=document.createElement('span');"
                + "label.className='small';"
                + "label.textContent='客户端';"
                + "if(anchor){panel.insertBefore(label,anchor);}else{panel.appendChild(label);}"
                + "var items=[['server','服务器地址'],['update','检查更新'],['about','关于']];"
                + "for(var i=0;i<items.length;i++){"
                + "var key=items[i][0],text=items[i][1];"
                + "var b=document.createElement('button');"
                + "b.type='button';"
                + "b.setAttribute('data-juku-action',key);"
                + "b.textContent=text;"
                + "b.addEventListener('click',function(e){"
                + "e.preventDefault();e.stopPropagation();"
                + "var d=document.getElementById('headerMenu');if(d){d.open=false;}"
                + "try{JukuShell.nativeAction(this.getAttribute('data-juku-action'));}catch(err){}});"
                + "if(anchor){panel.insertBefore(b,anchor);}else{panel.appendChild(b);}}"
                + "return true;};"
                + "var lastMode='';"
                + "var reportMenu=function(){"
                + "var ok=false;"
                + "try{ok=injectMenu();}catch(e){ok=false;}"
                + "var visible=false;"
                + "try{var s=document.getElementById('moreButton');"
                + "visible=!!(s&&s.offsetParent!==null&&getComputedStyle(s).display!=='none');}catch(e){}"
                // 必须「条目真的注入了」且「网页入口可见」才算页内菜单可用；
                // 任一不成立都退回原生兜底按钮，避免完全够不到服务器地址。
                + "var mode=(ok&&visible)?'inpage':'floating';"
                + "if(mode!==lastMode){lastMode=mode;"
                + "try{console.log('[juku] menu mode='+mode+' items='+document.querySelectorAll('#morePanel [data-juku-action]').length);}catch(e){}"
                + "try{JukuShell.setMenuEntry(mode);}catch(e){}}"
                + "};"
                + "setInterval(reportMenu,1500);"
                + "reportMenu();"
                // 自动旋转默认关闭：网页的「自动旋转」读的是设备方向传感器
                // （player-orientation.js 里 localStorage 的 juku.playback.autoRotate，默认 true），
                // 跟系统「方向锁定」无关，所以手机横过来播放器就会自己转。
                // 只在用户从未设置过时改成 false，之后他仍可在播放器里自己勾回来。
                + "try{"
                + "if(localStorage.getItem('juku.playback.autoRotate')===null){"
                + "localStorage.setItem('juku.playback.autoRotate','false');"
                + "var cb=document.getElementById('mobileAutoRotate');"
                + "if(cb&&cb.checked){cb.checked=false;cb.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "}}catch(e){}"
                + "if(window.__jukuShellBridgeInstalled){return;}"
                + "window.__jukuShellBridgeInstalled=true;"
                + "var sync=function(){"
                + "var p=document.getElementById('playerPanel');"
                + "var active=!!(p&&(p.open===true||p.hasAttribute('open'))&&p.classList.contains('mobile-player'));"
                + "var hidden=active&&p.classList.contains('player-controls-hidden');"
                + "if(window.JukuShell&&JukuShell.setPlayerState){JukuShell.setPlayerState(active,hidden);}"
                + "};"
                + "var bind=function(){"
                + "var p=document.getElementById('playerPanel');"
                + "if(!p){setTimeout(bind,200);return;}"
                + "if(p.__jukuShellObserver){sync();return;}"
                + "var observer=new MutationObserver(sync);"
                + "observer.observe(p,{attributes:true,attributeFilter:['class','open']});"
                + "p.__jukuShellObserver=observer;"
                + "sync();"
                + "};"
                + "bind();"
                + "})()";
        webView.evaluateJavascript(script, null);
    }

    /**
     * 播放器状态同步。已经没有原生 ActionBar 可显示了，这里只负责沉浸（隐藏状态栏）。
     *
     * 注意：**不做任何系统方向旋转**。网页的横竖屏是它自己用 CSS 旋转实现的
     * （见服务端 /assets/player-orientation.js），外壳插手会与页面旋转叠加，
     * 导致「一进播放器就被强制横屏」以及页面命中区域错位。
     */
    private void syncImmersiveMode(boolean playerActive, boolean controlsHidden) {
        applyImmersiveMode(playerActive && controlsHidden);
    }

    /** 外壳菜单。正常情况走网页「更多」面板里的注入条目，这里只服务兜底悬浮按钮。 */
    private void showShellMenu() {
        if (isFinishing()) {
            return;
        }
        String[] items = {"服务器地址", "刷新", "检查更新", "回到首页", "关于"};
        new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle(R.string.app_name)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showServerDialog();
                            break;
                        case 1:
                            showingError = false;
                            errorView.setVisibility(View.GONE);
                            webView.reload();
                            break;
                        case 2:
                            checkForUpdate(true);
                            break;
                        case 3:
                            String address = preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
                            webView.loadUrl(normalizeServerUrl(address));
                            break;
                        case 4:
                            showAboutDialog();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    private boolean isLegacyInternalServer(String address) {
        try {
            Uri uri = Uri.parse(address);
            int port = uri.getPort();
            return LEGACY_INTERNAL_HOST.equals(uri.getHost()) && (port == 8998 || port == 8999);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void clearWebCacheAfterUpgrade() {
        int cachedVersion = preferences().getInt(KEY_WEB_CACHE_VERSION, 0);
        if (cachedVersion >= CURRENT_VERSION_CODE) {
            return;
        }
        webView.clearCache(true);
        preferences().edit().putInt(KEY_WEB_CACHE_VERSION, CURRENT_VERSION_CODE).apply();
    }

    /** 清理更新缓存目录中的历史安装包，避免长期占用存储。 */
    private void clearUpdateCache() {
        File directory = new File(getCacheDir(), "updates");
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    private String normalizeServerUrl(String raw) {
        String address = raw == null ? "" : raw.trim();
        if (address.isEmpty()) {
            address = DEFAULT_SERVER_URL;
        }
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            address = "http://" + address;
        }
        if (!address.endsWith("/")) {
            address += "/";
        }
        return address;
    }

    private void showServerDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        input.setSelectAllOnFocus(true);
        // 主题已是深色，但输入框文字/光标要显式指定，否则个别 ROM 上会出现白底白字。
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#7C838E"));
        int padding = dp(22);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(padding, dp(8), padding, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle("服务器地址")
                .setMessage("例如 http://192.168.123.121:8999/")
                .setView(wrapper)
                .setPositiveButton("保存并进入", null)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", null)
                .create();

        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String address = normalizeServerUrl(input.getText().toString());
                preferences().edit().putString(KEY_SERVER_URL, address).apply();
                webView.loadUrl(address);
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                input.setText(DEFAULT_SERVER_URL);
                input.setSelection(input.length());
            });
        });
        dialog.show();
    }

    /** 关于面板：展示版本、服务器与构建信息，便于确认当前安装的版本。 */
    private void showAboutDialog() {
        String server = normalizeServerUrl(preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        String signature = describeSignature();
        StringBuilder text = new StringBuilder()
                .append("应用：果果剧库 手机版\n")
                .append("版本：").append(CURRENT_VERSION_NAME)
                .append("（versionCode ").append(CURRENT_VERSION_CODE).append("）\n")
                .append("服务器：").append(server).append("\n");
        if (signature != null) {
            text.append("签名：").append(signature).append("\n");
        }
        text.append("\n点击“检查更新”可立即获取最新版本。");

        TextView view = new TextView(this);
        view.setText(text.toString());
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setLineSpacing(dp(4), 1.0f);
        view.setPadding(dp(22), dp(15), dp(22), dp(4));
        view.setMovementMethod(LinkMovementMethod.getInstance());
        view.setTextIsSelectable(true);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);

        new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle("关于")
                .setView(scroll)
                .setPositiveButton("检查更新", (dialog, which) -> checkForUpdate(true))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 读取当前安装包的签名摘要（SHA-256 前 16 位），用于确认签名身份是否一致。 */
    private String describeSignature() {
        try {
            PackageManager manager = getPackageManager();
            PackageInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info = manager.getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                if (info.signingInfo == null) {
                    return null;
                }
                android.content.pm.Signature[] signatures = info.signingInfo.hasMultipleSigners()
                        ? info.signingInfo.getApkContentsSigners()
                        : info.signingInfo.getSigningCertificateHistory();
                if (signatures == null || signatures.length == 0) {
                    return null;
                }
                return shortHash(signatures[0].toByteArray());
            }
            info = manager.getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            if (info.signatures == null || info.signatures.length == 0) {
                return null;
            }
            return shortHash(info.signatures[0].toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String shortHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String full = hex(digest.digest(data));
            return full.substring(0, 16) + "…";
        } catch (Exception ignored) {
            return null;
        }
    }

    private void maybeAutoCheckUpdate() {
        if (autoUpdateChecked) {
            return;
        }
        autoUpdateChecked = true;
        long lastCheck = preferences().getLong(KEY_LAST_UPDATE_CHECK, 0L);
        if (System.currentTimeMillis() - lastCheck >= AUTO_UPDATE_INTERVAL_MS) {
            checkForUpdate(false);
        }
    }

    /** 前台恢复时节流复查：距上次检查超过 30 分钟则重新检查一次。 */
    private void maybeCheckUpdateOnForeground() {
        long now = System.currentTimeMillis();
        if (now - lastForegroundCheckAt < 60_000L) {
            return;
        }
        lastForegroundCheckAt = now;
        long lastCheck = preferences().getLong(KEY_LAST_UPDATE_CHECK, 0L);
        if (now - lastCheck >= MIN_FOREGROUND_RECHECK_MS) {
            checkForUpdate(false);
        }
    }

    private void checkForUpdate(boolean userInitiated) {
        if (updateCheckRunning || downloadDialog != null) {
            if (userInitiated) {
                Toast.makeText(this, "正在检查或下载更新", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        updateCheckRunning = true;
        if (userInitiated) {
            Toast.makeText(this, "正在检查更新", Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            try {
                MobileUpdate update = fetchMobileUpdate();
                runOnUiThread(() -> {
                    updateCheckRunning = false;
                    preferences().edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
                    if (update.versionCode <= CURRENT_VERSION_CODE) {
                        if (userInitiated) {
                            Toast.makeText(MainActivity.this, "已经是最新版本 " + update.versionName, Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    showUpdatePrompt(update);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    updateCheckRunning = false;
                    if (userInitiated) {
                        Toast.makeText(MainActivity.this, "检查更新失败：" + updateError(error), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "juku-update-check").start();
    }

    private MobileUpdate fetchMobileUpdate() throws Exception {
        String server = normalizeServerUrl(preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        URL url = new URL(new URL(server), "api/mobile/update");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "JukuMobile/" + CURRENT_VERSION_NAME);
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readText(stream);
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException(status == HttpURLConnection.HTTP_NOT_FOUND
                        ? "服务器还没有发布手机端更新"
                        : "服务器返回 " + status);
            }
            JSONObject json = new JSONObject(body);
            MobileUpdate update = new MobileUpdate();
            update.versionCode = json.getInt("versionCode");
            update.versionName = json.optString("versionName", "新版本");
            update.sha256 = json.optString("sha256", "");
            update.notes = json.optString("notes", "本次更新包含功能优化和问题修复。");
            update.size = json.optLong("size", 0L);
            String apkUrl = json.optString("apkUrl", "/api/mobile/apk");
            update.apkUrl = new URL(new URL(server), apkUrl);
            return update;
        } finally {
            connection.disconnect();
        }
    }

    private String readText(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line).append('\n');
            }
        }
        return body.toString();
    }

    private void showUpdatePrompt(MobileUpdate update) {
        if (isFinishing()) {
            return;
        }
        String size = update.size > 0
                ? String.format(Locale.CHINA, "%.2f MB", update.size / 1024.0 / 1024.0)
                : "大小未知";
        String message = "当前版本：" + CURRENT_VERSION_NAME + "\n"
                + "最新版本：" + update.versionName + "（" + size + "）\n\n"
                + update.notes;
        new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle("发现手机版更新")
                .setMessage(message)
                .setPositiveButton("下载并安装", (dialog, which) -> downloadAndInstall(update))
                .setNegativeButton("稍后", null)
                .show();
    }

    private void downloadAndInstall(MobileUpdate update) {
        if (downloadDialog != null) {
            return;
        }
        downloadCancelled = false;
        File directory = new File(getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "无法创建更新缓存目录", Toast.LENGTH_SHORT).show();
            return;
        }
        final File target = new File(directory, "juku-mobile-" + update.versionCode + ".apk");
        downloadCancelled = false;

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(22);
        layout.setPadding(padding, dp(8), padding, 0);
        downloadStatus = new TextView(this);
        downloadStatus.setText("正在连接服务器…");
        downloadProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        downloadProgress.setMax(100);
        layout.addView(downloadStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(14);
        layout.addView(downloadProgress, progressParams);

        downloadDialog = new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle("正在下载更新")
                .setView(layout)
                .setNegativeButton("取消", null)
                .create();
        downloadDialog.setCanceledOnTouchOutside(false);
        downloadDialog.setOnShowListener(ignored -> downloadDialog
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(view -> {
                    downloadCancelled = true;
                    if (activeUpdateConnection != null) {
                        activeUpdateConnection.disconnect();
                    }
                    cancelUpdateNotification();
                    closeDownloadDialog();
                }));
        downloadDialog.show();

        new Thread(() -> downloadUpdate(update, target), "juku-update-download").start();
    }

    private void downloadUpdate(MobileUpdate update, File target) {
        IOException lastError = null;
        for (int attempt = 0; attempt <= MAX_DOWNLOAD_RETRY; attempt++) {
            if (downloadCancelled) {
                break;
            }
            try {
                boolean ok = downloadUpdateOnce(update, target, attempt);
                if (ok) {
                    runOnUiThread(this::finishDownloadSuccess);
                    return;
                }
                if (downloadCancelled) {
                    break;
                }
                lastError = new IOException("下载未完成");
            } catch (IOException error) {
                lastError = error;
                if (downloadCancelled) {
                    break;
                }
            }
            // 断点续传：保留已下载部分，重试时带 Range 继续
            if (attempt < MAX_DOWNLOAD_RETRY) {
                final int nextAttempt = attempt + 1;
                runOnUiThread(() -> {
                    if (downloadStatus != null) {
                        downloadStatus.setText("网络中断，正在重试（" + nextAttempt + "/" + MAX_DOWNLOAD_RETRY + "）…");
                    }
                });
            }
        }
        final IOException error = lastError;
        runOnUiThread(() -> {
            if (downloadCancelled) {
                cancelUpdateNotification();
                closeDownloadDialog();
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                return;
            }
            cancelUpdateNotification();
            closeDownloadDialog();
            if (!isFinishing()) {
                Toast.makeText(MainActivity.this,
                        "更新下载失败：" + updateError(error), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 单次下载。返回 true 表示已完整下载并通过校验。
     * 支持断点续传：若 target 已存在部分内容，则带 Range 头从已有长度继续。
     */
    private boolean downloadUpdateOnce(MobileUpdate update, File target, int attempt) throws IOException {
        long existing = target.exists() ? target.length() : 0L;
        FileOutputStream output = null;
        boolean resuming = existing > 0L && attempt > 0;
        activeUpdateConnection = (HttpURLConnection) update.apkUrl.openConnection();
        HttpURLConnection connection = activeUpdateConnection;
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "JukuMobile/" + CURRENT_VERSION_NAME);
        if (resuming) {
            connection.setRequestProperty("Range", "bytes=" + existing + "-");
        }
        try {
            int status = connection.getResponseCode();
            long total;
            long received;
            if (status == HttpURLConnection.HTTP_PARTIAL && resuming) {
                String range = connection.getHeaderField("Content-Range");
                long fullTotal = parseContentRangeTotal(range);
                total = fullTotal > 0 ? fullTotal : update.size;
                received = existing;
                output = new FileOutputStream(target, true); // 追加模式
            } else if (status == HttpURLConnection.HTTP_OK) {
                total = connection.getContentLengthLong();
                if (total <= 0) {
                    total = update.size;
                }
                received = 0L;
                // 服务端不支持续传或首次下载：重写文件
                if (target.exists() && !target.delete()) {
                    throw new IOException("无法写入更新文件");
                }
                output = new FileOutputStream(target, false);
            } else if (status == 416 && resuming) {
                // 已下载部分比服务端文件还大，丢弃重来
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                throw new IOException("本地缓存异常，已重置");
            } else {
                throw new IOException("安装包下载失败，服务器返回 " + status);
            }

            final long totalForUi = total;
            final long startForUi = received;
            runOnUiThread(() -> {
                if (downloadProgress != null) {
                    downloadProgress.setIndeterminate(totalForUi <= 0);
                }
                showUpdateNotification(startForUi, totalForUi, false);
            });

            long lastNotify = 0L;
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (downloadCancelled) {
                        throw new IOException("下载已取消");
                    }
                    output.write(buffer, 0, count);
                    received += count;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify >= 200L) {
                        lastNotify = now;
                        final long current = received;
                        runOnUiThread(() -> {
                            updateDownloadProgress(current, totalForUi);
                            showUpdateNotification(current, totalForUi, false);
                        });
                    }
                }
                output.flush();
            } finally {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
                output = null;
            }

            if (downloadCancelled) {
                throw new IOException("下载已取消");
            }

            // 完整性校验：无论续传与否，都对落盘文件重算 sha256
            if (!update.sha256.isEmpty()) {
                String actual = sha256Of(target);
                if (!actual.equalsIgnoreCase(update.sha256)) {
                    //noinspection ResultOfMethodCallIgnored
                    target.delete();
                    if (attempt < MAX_DOWNLOAD_RETRY) {
                        return false; // 让外层重试
                    }
                    throw new IOException("安装包校验失败，请重新下载");
                }
            }
            return true;
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException ignored) {
                }
            }
            connection.disconnect();
            activeUpdateConnection = null;
        }
    }

    private void finishDownloadSuccess() {
        if (downloadCancelled) {
            return;
        }
        cancelUpdateNotification();
        closeDownloadDialog();
        File directory = new File(getCacheDir(), "updates");
        File apk = null;
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".apk")) {
                    apk = file;
                    break;
                }
            }
        }
        if (apk == null) {
            Toast.makeText(this, "更新文件丢失，请重新下载", Toast.LENGTH_LONG).show();
            return;
        }
        installApk(apk);
    }

    private long parseContentRangeTotal(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        int slash = contentRange.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= contentRange.length()) {
            return -1L;
        }
        try {
            return Long.parseLong(contentRange.substring(slash + 1).trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private String sha256Of(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, count);
                }
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException("设备不支持 SHA-256 校验");
        }
    }

    private void updateDownloadProgress(long received, long total) {
        if (downloadDialog == null || !downloadDialog.isShowing()) {
            return;
        }
        if (downloadProgress == null || downloadStatus == null) {
            return;
        }
        if (total > 0) {
            int percent = (int) Math.min(100, received * 100 / total);
            downloadProgress.setIndeterminate(false);
            downloadProgress.setProgress(percent);
            downloadStatus.setText(String.format(Locale.CHINA,
                    "正在下载：%d%%（%.2f / %.2f MB）",
                    percent,
                    received / 1024.0 / 1024.0,
                    total / 1024.0 / 1024.0));
        } else {
            downloadStatus.setText(String.format(Locale.CHINA,
                    "正在下载：%.2f MB", received / 1024.0 / 1024.0));
        }
    }

    private void closeDownloadDialog() {
        if (downloadDialog != null) {
            downloadDialog.dismiss();
            downloadDialog = null;
            downloadProgress = null;
            downloadStatus = null;
        }
    }

    // ==== 通知栏下载进度（切后台也能看到进度）====

    private void ensureNotificationChannel() {
        if (notificationChannelReady || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "应用更新下载", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示手机版更新包的下载进度");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        notificationChannelReady = true;
    }

    private void showUpdateNotification(long received, long total, boolean finished) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        ensureNotificationChannel();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        int percent = total > 0 ? (int) Math.min(100, received * 100 / total) : 0;
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent, pendingFlags);

        Notification.Builder builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(finished ? "更新下载完成" : "正在下载更新")
                .setContentText(total > 0
                        ? String.format(Locale.CHINA, "%d%%  %.2f / %.2f MB",
                        percent, received / 1024.0 / 1024.0, total / 1024.0 / 1024.0)
                        : String.format(Locale.CHINA, "%.2f MB", received / 1024.0 / 1024.0))
                .setContentIntent(contentIntent)
                .setOngoing(!finished)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent, total <= 0);
        manager.notify(NOTIFICATION_ID_UPDATE, builder.build());
    }

    private void cancelUpdateNotification() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID_UPDATE);
        }
    }

    private void installApk(File apk) {
        // 预检 1：包本身是否可解析、包名是否匹配
        String localIssue = precheckApk(apk);
        if (localIssue != null) {
            Toast.makeText(this, "更新包有问题：" + localIssue, Toast.LENGTH_LONG).show();
            return;
        }
        // 预检 2：签名是否与已安装版本一致 —— 不一致时系统必然拒绝，提前给出指引
        if (hasSignatureConflict(apk)) {
            showSignatureConflictGuide(apk);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstallFile = apk;
            new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                    .setTitle("需要安装权限")
                    .setMessage("请允许“果果剧库”安装应用，返回后会自动继续安装。")
                    .setPositiveButton("去开启", (dialog, which) -> {
                        Intent settings = new Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + getPackageName()));
                        try {
                            startActivityForResult(settings, INSTALL_PERMISSION_REQUEST);
                        } catch (ActivityNotFoundException error) {
                            Toast.makeText(this, "无法打开安装权限设置页", Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", (dialog, which) -> pendingInstallFile = null)
                    .show();
            return;
        }
        try {
            installWithPackageInstaller(apk);
        } catch (Exception error) {
            pendingInstallFile = null;
            installWithViewer(apk);
        }
    }

    /** 比对更新包与已安装应用的签名证书是否一致。 */
    private boolean hasSignatureConflict(File apk) {
        try {
            PackageManager manager = getPackageManager();
            PackageInfo installed;
            PackageInfo archive;
            byte[] installedCert;
            byte[] archiveCert;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                installed = manager.getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNING_CERTIFICATES);
                installedCert = firstSignerBytes(installed);
                archiveCert = firstSignerBytes(archive);
            } else {
                installed = manager.getPackageInfo(getPackageName(),
                        PackageManager.GET_SIGNATURES);
                archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(),
                        PackageManager.GET_SIGNATURES);
                installedCert = legacyFirstSignature(installed);
                archiveCert = legacyFirstSignature(archive);
            }
            if (installedCert == null || archiveCert == null) {
                return false; // 拿不到就交给系统判断
            }
            return !MessageDigest.isEqual(installedCert, archiveCert);
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private byte[] legacyFirstSignature(PackageInfo info) {
        if (info == null || info.signatures == null || info.signatures.length == 0) {
            return null;
        }
        return info.signatures[0].toByteArray();
    }

    private byte[] firstSignerBytes(PackageInfo info) {
        if (info == null || info.signingInfo == null) {
            return null;
        }
        android.content.pm.Signature[] signatures = info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners()
                : info.signingInfo.getSigningCertificateHistory();
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        return signatures[0].toByteArray();
    }

    private void installWithPackageInstaller(File apk) throws IOException {
        PackageInfo archive = getPackageManager()
                .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null || !getPackageName().equals(archive.packageName)) {
            throw new IOException("更新包格式无效");
        }
        PackageInstaller packageInstaller = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(getPackageName());
        params.setSize(apk.length());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        }
        int sessionId = packageInstaller.createSession(params);
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);
        try {
            try (FileInputStream input = new FileInputStream(apk);
                 java.io.OutputStream output = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buffer = new byte[32 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                session.fsync(output);
            }
            Intent callback = new Intent(INSTALL_STATUS_ACTION).setPackage(getPackageName());
            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pendingIntentFlags |= PendingIntent.FLAG_MUTABLE;
            } else {
                pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    this,
                    sessionId,
                    callback,
                    pendingIntentFlags);
            pendingInstallFile = apk;
            session.commit(pendingIntent.getIntentSender());
        } finally {
            session.close();
        }
    }

    /**
     * 降级安装路径。
     *
     * 注意：这里刻意**不再使用 ACTION_INSTALL_PACKAGE**。该 action 在 Android 8+
     * 已被标记为 deprecated，部分厂商 ROM 的系统安装器在处理它时，会先向
     * ContentProvider 请求包元数据（query），一旦 provider 返回不符合预期，
     * 就抛出“解析软件包时出现问题 / Failed opening content provider”，
     * 而真正的原因（例如签名冲突）被掩盖。
     *
     * 改为 ACTION_VIEW + setDataAndType，让系统以「打开 APK 文件」的标准链路
     * 触发安装器，兼容性明显更好；若系统只有一个安装器入口，行为一致。
     */
    private void installWithViewer(File apk) {
        Uri uri = ApkFileProvider.uriForFile(this, apk);
        String mime = "application/vnd.android.package-archive";

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(uri, mime);
        viewIntent.setClipData(ClipData.newUri(getContentResolver(), "果果剧库更新", uri));
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(viewIntent);
            return;
        } catch (ActivityNotFoundException ignored) {
            // 继续尝试 ACTION_INSTALL_PACKAGE 作为兜底
        }

        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setDataAndType(uri, mime);
        installIntent.setClipData(ClipData.newUri(getContentResolver(), "果果剧库更新", uri));
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(installIntent);
        } catch (ActivityNotFoundException ignored) {
            Toast.makeText(this,
                    "没有找到系统安装器，请到浏览器打开下载页手动安装", Toast.LENGTH_LONG).show();
        }
    }

    /** 安装前本地预检：确认文件可读、是合法 APK、包名一致。返回 null 表示无问题。 */
    private String precheckApk(File apk) {
        if (!apk.isFile() || apk.length() <= 0L) {
            return "更新文件不存在或为空";
        }
        if (!apk.canRead()) {
            return "更新文件不可读（权限异常）";
        }
        try (FileInputStream input = new FileInputStream(apk)) {
            byte[] header = new byte[4];
            if (input.read(header) < 4
                    || header[0] != 'P' || header[1] != 'K') {
                return "文件不是有效的安装包";
            }
        } catch (IOException error) {
            return "更新文件读取失败";
        }
        PackageInfo archive = getPackageManager()
                .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
        if (archive == null) {
            return "系统无法解析安装包";
        }
        if (!getPackageName().equals(archive.packageName)) {
            return "安装包包名不匹配";
        }
        return null;
    }

    /** 签名冲突时给出明确、可执行的指引（而不是一句“安装失败”）。 */
    private void showSignatureConflictGuide(File apk) {
        String signature = describeSignature();
        String downloaded = apk == null ? null : describeApkSignature(apk);
        StringBuilder text = new StringBuilder()
                .append("新版本与手机上已安装版本的签名不一致，系统不允许覆盖安装。\n\n");
        if (signature != null) {
            text.append("已安装版本签名：").append(signature).append("\n");
        }
        if (downloaded != null) {
            text.append("更新包签名：").append(downloaded).append("\n\n");
        } else {
            text.append("\n");
        }
        text.append("解决办法（任选其一）：\n")
                .append("1. 卸载当前 App 后重新安装新版本（会清空本地设置）\n")
                .append("2. 到浏览器打开下载页下载最新安装包手动安装\n")
                .append("3. 确认新版本是用同一签名证书打包后重新发布");
        new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle("无法覆盖安装")
                .setMessage(text.toString())
                .setPositiveButton("知道了", null)
                .show();
    }

    /** 读取指定 APK 文件的签名摘要（SHA-256 前 16 位）。 */
    private String describeApkSignature(File apk) {
        try {
            PackageInfo info = getPackageManager().getPackageArchiveInfo(
                    apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
            if (info == null || info.signatures == null || info.signatures.length == 0) {
                return null;
            }
            return shortHash(info.signatures[0].toByteArray());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 判断安装失败原因是否属于签名/包冲突（这类问题无法通过换安装器绕过）。 */
    private boolean isSignatureConflict(String detail) {
        if (detail == null) {
            return false;
        }
        String lower = detail.toLowerCase(Locale.ROOT);
        return lower.contains("signature")
                || lower.contains("signatures do not match")
                || lower.contains("installation failed due to")
                || lower.contains("update incompatible")
                || detail.contains("签名")
                || detail.contains("不兼容")
                || lower.contains("inconsistent");
    }

    private void registerInstallReceiver() {
        if (installReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(INSTALL_STATUS_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(installResultReceiver, filter);
        }
        installReceiverRegistered = true;
    }

    private String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private String updateError(Exception error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? "网络连接异常" : message;
    }

    private static final class MobileUpdate {
        int versionCode;
        String versionName;
        String sha256;
        String notes;
        long size;
        URL apkUrl;
    }

    private void showError(String message) {
        showingError = true;
        progressBar.setVisibility(View.GONE);
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private void hideCustomView() {
        if (customView == null) {
            return;
        }
        contentRoot.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        applyImmersiveMode(false);
    }

    private void applyImmersiveMode(boolean enabled) {
        immersiveNow = enabled;
        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            // 关键：必须把 LAYOUT_FULLSCREEN / LAYOUT_HIDE_NAVIGATION 一并清掉。
            // 只 clearFlags 而不重置 systemUiVisibility 的话，窗口会一直认为「内容要绘制到
            // 状态栏下面」：退出全屏回到竖屏浏览时，网页顶栏（.app-header）会被状态栏压住
            // 半行，而且因为已经在滚动顶部、怎么上滑都拉不出来 —— 只能重启进程才恢复。
            // 这是用户反馈「竖屏浏览时顶部卡死上不去，重启后正常」的直接原因。
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            configureWindow();
        }
        if (contentRoot != null) {
            contentRoot.requestApplyInsets();
        }
    }

    /** WebView 渲染进程崩溃时重建，避免整页白屏且无法操作。 */
    private boolean handleRenderProcessGone() {
        final String address = normalizeServerUrl(
                preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        mainHandler.post(() -> {
            Toast.makeText(MainActivity.this, "页面渲染进程异常，正在恢复…", Toast.LENGTH_SHORT).show();
            if (webView != null) {
                contentRoot.removeView(webView);
                webView.destroy();
            }
            rebuildWebView();
            if (webView != null) {
                webView.loadUrl(address);
            }
        });
        return true;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void rebuildWebView() {
        webView = new WebView(this);
        contentRoot.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        configureWebView();
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // 外壳菜单不再走 ActionBar（已改用 NoActionBar 主题 + 网页头部注入按钮），
    // 菜单项的响应逻辑统一在 showShellMenu() 里。

    @Override
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }
        moveTaskToBack(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        maybeCheckUpdateOnForeground();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == INSTALL_PERMISSION_REQUEST) {
            File apk = pendingInstallFile;
            pendingInstallFile = null;
            boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || getPackageManager().canRequestPackageInstalls();
            if (apk != null && apk.exists() && granted) {
                installApk(apk);
            } else if (apk != null && !granted) {
                Toast.makeText(this, "未获得安装权限，请重新检查更新", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != FILE_CHOOSER_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (filePathCallback == null) {
            return;
        }
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                result = new Uri[count];
                for (int index = 0; index < count; index++) {
                    result[index] = data.getClipData().getItemAt(index).getUri();
                }
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
    }

    @Override
    protected void onDestroy() {
        downloadCancelled = true;
        if (activeUpdateConnection != null) {
            activeUpdateConnection.disconnect();
            activeUpdateConnection = null;
        }
        cancelUpdateNotification();
        closeDownloadDialog();
        if (installReceiverRegistered) {
            unregisterReceiver(installResultReceiver);
            installReceiverRegistered = false;
        }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
        }
        super.onDestroy();
    }
}
