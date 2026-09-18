package com.juku.mobile;

import android.Manifest;
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
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
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
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String LOG_TAG = "JukuMobile";
    private static final String PREFS = "juku_mobile";
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_LAST_UPDATE_CHECK = "last_update_check";
    private static final String KEY_UPDATE_TRACE = "update_trace";
    private static final String KEY_WEB_CACHE_VERSION = "web_cache_version";
    private static final String KEY_NOTIFICATION_ASKED = "notification_permission_asked";
    /** 自定义更新源（留空 = 自动：服务器 → GitHub）。 */
    private static final String KEY_UPDATE_SOURCE_OVERRIDE = "update_source_override";
    /** 上次可用的更新源 key —— 下次优先尝试它，避免每次从失效的源开始等超时。 */
    private static final String KEY_LAST_GOOD_SOURCE = "last_good_update_source";
    /** 上次成功解析到的安装包直链 —— 「浏览器下载」和「关于」页用它。 */
    private static final String KEY_LAST_APK_URL = "last_apk_url";
    private static final String INSTALL_STATUS_ACTION = "com.juku.mobile.INSTALL_STATUS";
    private static final String DEFAULT_SERVER_URL = "https://duanju.sky423.cn:18888/";
    private static final String LEGACY_INTERNAL_HOST = "192.168.123.121";
    /**
     * 版本号的**唯一事实来源是 `app/build.gradle`**（versionName / versionCode），
     * 运行时从 PackageManager 读，这里不再硬编码一份。
     *
     * 之前的写法是「gradle 一份 + 本文件一份」，改版本时漏改一处不会报错，
     * 但会静默带偏三处逻辑：网页缓存清理判据（KEY_WEB_CACHE_VERSION）、
     * 更新比较（服务端 versionCode 与本地的比较）、UA 上报 —— 排查起来毫无线索。
     * 下面两个兜底值只在 PackageManager 抛异常（理论上不会）时使用。
     */
    private static final String FALLBACK_VERSION_NAME = "0.0.0";
    private static final int FALLBACK_VERSION_CODE = 1;
    private String resolvedVersionName;
    private int resolvedVersionCode;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int INSTALL_PERMISSION_REQUEST = 1002;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1003;
    private static final long AUTO_UPDATE_INTERVAL_MS = 12L * 60L * 60L * 1000L;
    private static final long MIN_FOREGROUND_RECHECK_MS = 30L * 60L * 1000L;

    /**
     * GitHub Release 的固定下载前缀。
     *
     * `releases/latest/download/<资产名>` 是**不需要 API、不需要登录**的稳定地址，
     * CI 每次发版都会把 APK / IPA / update.json 作为附件发布，所以这个源天生就存在，
     * 不受业务服务端改动影响（这正是本次改造的目的）。
     */
    private static final String GITHUB_LATEST_DOWNLOAD =
            "https://github.com/chenweitian423/juku-mobile/releases/latest/download/";

    /** 浏览器直接打开用的版本列表页（没有已知直链时的兜底）。 */
    private static final String GITHUB_RELEASES_PAGE =
            "https://github.com/chenweitian423/juku-mobile/releases/latest";

    /** 查更新信息用的超时（源可能有好几个，单个源别等太久）。 */
    private static final int UPDATE_CONNECT_TIMEOUT_MS = 8000;
    private static final int UPDATE_READ_TIMEOUT_MS = 12000;

    /** 整页加载看门狗：超过这个时间且进度还几乎没动，就判定卡住并给出可点击的重试页。 */
    private static final long PAGE_LOAD_WATCHDOG_MS = 25_000L;
    private static final int PAGE_LOAD_WATCHDOG_MAX_ROUNDS = 2;

    private static final String NOTIFICATION_CHANNEL_ID = "juku_update";
    private static final int NOTIFICATION_ID_UPDATE = 0x4A55; // "JU"
    private static final int MAX_DOWNLOAD_RETRY = 2;

    /**
     * 安装会话看门狗。
     *
     * `PackageInstaller.commit()` 之后，正常情况系统会立刻回一个广播
     * （`STATUS_PENDING_USER_ACTION` 让我们拉起确认界面，或直接 SUCCESS/FAILURE）。
     * 但实测在部分 ROM + 特定安装来源组合下，**广播压根不来、确认界面也不出现**，
     * 于是界面停在那里什么都不发生 —— 用户看到的就是「点了下载没反应」。
     * 超时后主动换系统安装器兜底，把"静默"变成"有结果"。
     */
    private static final long INSTALL_SESSION_WATCHDOG_MS = 15_000L;
    private static final String KEY_PENDING_INSTALL_CODE = "pending_install_code";

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
    private volatile File pendingInstallFile;
    /**
     * 本次要安装的更新包在**服务端声明的** versionCode。
     * 安装前会拿实物包的 versionCode 跟它比对 —— 服务端历史上出现过「APK 与 update.json
     * 不是同批发布」的情况（APK 的 sha256 每次构建都变，抄写必然对不上），
     * 比对不上就明确中止安装，而不是把错的包装进去。
     */
    private volatile int pendingUpdateVersionCode;
    /** 另存到公共「下载」目录的那份安装包（装成功后清掉，失败则留作手动安装的兜底）。 */
    private Uri publishedApkUri;
    private boolean showingError;
    private boolean updateCheckRunning;
    private boolean autoUpdateChecked;
    private boolean installReceiverRegistered;
    private volatile boolean downloadCancelled;
    /** 安装会话已 commit、但还没收到任何结果广播 —— 看门狗据此决定要不要兜底。 */
    private boolean installSessionAwaitingResult;
    /**
     * 我们是否**成功**把系统安装确认界面拉起来过。
     *
     * 这个标志比"本 Activity 是否在前台"更精确：系统确认界面起来后我们必然被压到后台，
     * 但"被压到后台"也可能是别的原因（通知权限框等）。只有真的拉起过确认界面，
     * 才说明这条路是通的，剩下的是用户在操作 —— 看门狗此时什么都不该做。
     * 反之（提交后既没广播、也没拉起过确认界面）就是系统没搭理我们，必须兜底。
     */
    private boolean installUiLaunched;
    /**
     * 系统给的「安装确认界面」Intent（`STATUS_PENDING_USER_ACTION` 里带的）。
     *
     * 留着它是为了**回前台重试**：如果广播到的时候我们正被别的系统界面挡着
     * （最典型的是紧挨着弹出的通知权限框），这次 startActivity 会被
     * Android 10+ 的「后台启动 Activity」限制**静默丢弃** —— 不报错、界面不出现，
     * 用户看到的就是「点了下载没反应」。等我们回到前台再拉起一次就好了。
     */
    private Intent pendingInstallConfirmation;
    private long lastForegroundCheckAt;
    private boolean notificationChannelReady;
    /** 网页里是否有 video 正在播放 —— 决定要不要给窗口加 FLAG_KEEP_SCREEN_ON。 */
    private boolean playbackActive;
    private boolean webViewPaused;
    private boolean pageLoadSettled = true;
    private int pageLoadWatchdogRounds;
    private ConnectivityManager.NetworkCallback networkCallback;
    private boolean notificationPermissionAsked;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * 整页加载超时看门狗。
     *
     * 服务器不可达时 WebView 可能长时间停在「白屏 + 进度条 0%」，用户既没提示也无从下手
     * （onReceivedError 只在连接明确失败时触发，TCP 挂起不会）。这里在设定时限后检查进度，
     * 若几乎没有推进就切到可点击重试的错误页。
     */
    private final Runnable pageLoadWatchdog = new Runnable() {
        @Override
        public void run() {
            if (pageLoadSettled || showingError || isFinishing() || webView == null) {
                return;
            }
            if (progressBar.getProgress() >= 20 && pageLoadWatchdogRounds < PAGE_LOAD_WATCHDOG_MAX_ROUNDS) {
                // 已经有实质进展，只是慢 —— 再给一轮，不要打扰用户
                pageLoadWatchdogRounds++;
                mainHandler.postDelayed(this, PAGE_LOAD_WATCHDOG_MS);
                return;
            }
            showError("页面加载超时\n\n服务器响应太慢或网络不稳定。");
        }
    };

    private final BroadcastReceiver installResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // ★ 必须先确认广播里真的带状态字段。
            // 原来的写法是 getIntExtra(EXTRA_STATUS, STATUS_FAILURE)：万一收到一个
            // 不带该字段的广播（厂商 ROM 包一层、或我们自己误注册到其它 action），
            // 就会被**当成"安装失败(1)"**并显示"系统未返回具体原因" —— 白白把一次
            // 可能正常的安装判成失败。异常路径绝不能靠默认值来触发。
            if (intent == null || !intent.hasExtra(PackageInstaller.EXTRA_STATUS)) {
                noteUpdateStep("收到不含状态字段的安装结果广播，已忽略");
                return;
            }
            int status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            String detail = message == null || message.trim().isEmpty()
                    ? "系统未返回具体原因" : message.trim();
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                noteUpdateStep("等待系统安装界面确认");
                // ★ 刻意**不**解除看门狗：如果下面这次 startActivity 被系统
                // （Android 10+ 的后台启动限制）静默拦掉，确认界面根本不会出现，
                // 那就又成了"点完没反应"。看门狗会检查"是否真的成功拉起过"，
                // 界面起来了话由 installUiLaunched 挡住，不会误触发。
                pendingInstallConfirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (pendingInstallConfirmation != null) {
                    launchPendingInstallConfirmation();
                } else {
                    noteUpdateStep("系统没有给出安装确认界面，等待看门狗兜底");
                }
                return;
            }
            disarmInstallWatchdog();
            if (status == PackageInstaller.STATUS_SUCCESS) {
                pendingInstallFile = null;
                clearUpdateCache();
                clearPublishedApk();
                finishPendingInstallMarker();
                noteUpdateStep("安装成功");
                Toast.makeText(MainActivity.this, "更新安装完成", Toast.LENGTH_SHORT).show();
                return;
            }
            // ★ 用户主动放弃 / 被设备策略挡下：这是**预期结果，不是故障**。
            // 必须在这里就停下，绝不能走下面的「改用系统安装器」降级路径 ——
            // 否则用户刚点完「取消」，安装界面立刻又弹一次，体感像卡死循环。
            if (isUserAbortedStatus(status)) {
                pendingInstallFile = null;
                noteUpdateStep("安装未完成（用户取消或被策略阻止，status=" + status + "）");
                Toast.makeText(MainActivity.this, "已取消安装", Toast.LENGTH_SHORT).show();
                return;
            }
            noteUpdateStep("安装失败（" + status + "）：" + detail);
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

    /**
     * 安装会话看门狗（见 {@link #INSTALL_SESSION_WATCHDOG_MS} 的说明）。
     *
     * 触发条件：会话已 commit、至今**没收到任何结果广播**、而且**从未成功拉起过
     * 系统安装确认界面**。三条同时成立 = 系统确实没搭理我们，必须换路。
     *
     * 刻意不拿"是否在前台"当判据：被压到后台的原因很多（通知权限框、系统弹窗…），
     * 用它当判据会在"权限框挡着 + 安装请求被吞"这种组合下漏掉真正的故障；
     * 而 {@link #installUiLaunched} 是直接证据，不会误判。
     */
    private final Runnable installSessionWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!installSessionAwaitingResult || installUiLaunched) {
                return;
            }
            File apk = pendingInstallFile;
            installSessionAwaitingResult = false;
            noteUpdateStep("系统 " + (INSTALL_SESSION_WATCHDOG_MS / 1000)
                    + " 秒内没有任何安装响应，改用系统安装器兜底");
            if (apk != null && apk.exists()) {
                Toast.makeText(MainActivity.this,
                        "系统未响应安装请求，正在改用系统安装器",
                        Toast.LENGTH_LONG).show();
                installWithViewer(apk);
            } else {
                Toast.makeText(MainActivity.this,
                        "系统未响应安装请求，请重新检查更新",
                        Toast.LENGTH_LONG).show();
            }
        }
    };

    /** 安装会话已提交，开始等结果；超时由看门狗兜底。 */
    private void armInstallWatchdog() {
        installSessionAwaitingResult = true;
        installUiLaunched = false;
        pendingInstallConfirmation = null;
        mainHandler.removeCallbacks(installSessionWatchdog);
        mainHandler.postDelayed(installSessionWatchdog, INSTALL_SESSION_WATCHDOG_MS);
    }

    /** 收到明确的安装结果（成功/失败/取消）后解除看门狗。 */
    private void disarmInstallWatchdog() {
        installSessionAwaitingResult = false;
        installUiLaunched = false;
        pendingInstallConfirmation = null;
        mainHandler.removeCallbacks(installSessionWatchdog);
    }

    /**
     * 拉起系统安装确认界面。
     *
     * 只有"当时我们确实在前台且有窗口焦点"才算真的成功：窗口没有焦点时
     * （例如通知权限框正压在头上）这次 startActivity 很可能被系统的
     * 「后台启动 Activity」限制**静默丢弃** —— 不抛异常，但界面不会出现。
     * 那种情况不置 installUiLaunched，交给 onResume 重试或看门狗兜底。
     */
    private void launchPendingInstallConfirmation() {
        Intent confirmation = pendingInstallConfirmation;
        if (confirmation == null) {
            return;
        }
        confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(confirmation);
            if (hasWindowFocus()) {
                installUiLaunched = true;
                noteUpdateStep("已拉起系统安装界面");
            } else {
                noteUpdateStep("收到安装确认请求时不在前台，"
                        + "界面可能被系统拦下，回到前台后会重试");
            }
        } catch (Exception error) {
            noteUpdateStep("拉起安装确认界面失败（"
                    + error.getClass().getSimpleName() + "）：" + updateError(error));
        }
    }

    /**
     * 记下"这一次要装到哪个 versionCode"。
     *
     * 走系统安装器（ACTION_VIEW）时我们**拿不到任何回调** —— 装成功了这个进程也已经被替换掉。
     * 所以只能把目标版本落到 SharedPreferences，等下次启动拿实际版本号对比，
     * 才能回答"上次那次安装到底成没成"（这也是「关于」页那条记录的价值所在）。
     */
    private void markPendingInstall(int versionCode) {
        if (versionCode <= 0) {
            return;
        }
        preferences().edit().putInt(KEY_PENDING_INSTALL_CODE, versionCode).apply();
        noteUpdateStep("已记录待安装目标版本 " + versionCode);
    }

    private void finishPendingInstallMarker() {
        preferences().edit().remove(KEY_PENDING_INSTALL_CODE).apply();
    }

    /**
     * 启动时核对"上次那次安装"的结果。
     * 上一次若停在"已拉起系统安装器"，这里就能给出结论：换版本了 = 成了；没变 = 没成。
     */
    private void reportPreviousInstallAttempt() {
        int target = preferences().getInt(KEY_PENDING_INSTALL_CODE, 0);
        if (target <= 0) {
            return;
        }
        int now = currentVersionCode();
        if (now >= target) {
            noteUpdateStep("上次安装已完成，当前 " + currentVersionName() + "(" + now + ")");
        } else {
            noteUpdateStep("上次安装未完成：仍是 " + currentVersionName() + "(" + now
                    + ")，目标 " + target + " —— 若已手动装过请忽略");
        }
        finishPendingInstallMarker();
    }

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

        /**
         * 网页上报是否有 video 正在播放。
         * 播放期间给窗口加 FLAG_KEEP_SCREEN_ON，避免看到一半屏幕自动息屏。
         */
        @JavascriptInterface
        public void setPlaybackState(boolean playing) {
            runOnUiThread(() -> applyPlaybackState(playing));
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
            case "updatesource":
                showUpdateSourceDialog();
                break;
            case "update":
                checkForUpdate(true);
                break;
            case "clearcache":
                clearWebCacheAndReload();
                break;
            case "restart":
                restartClient();
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
        registerNetworkRecovery();
        // 启动时回收「一天前」的残留安装包。★ 这里刻意只删过期的、绝不清空目录：
        // 下载完成 → 拉起系统安装器 → 厂商扫描/用户确认可能耗时较长，期间若本 Activity
        // 被重建（配置变化、安装器切换任务栈等），清空目录会把**正在安装的那个包删掉**，
        // 安装器随后读 provider 得到「update file not found」，用户看到的却又是
        // 「解析软件包时出现问题」。属于磁盘 IO，放到后台线程，不占冷启动主线程时间。
        new Thread(() -> removeExpiredApks(new File(getCacheDir(), "updates"),
                24L * 60L * 60L * 1000L), "juku-cache-cleanup").start();
        // 核对"上次那次安装"的结果（走系统安装器时没有任何回调，只能这样闭环）
        reportPreviousInstallAttempt();
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
            // ★ reload() 在「从没成功加载过任何页面」时是空操作：WebView 没有可重载的 URL，
            // 点了毫无反应（真实用户场景：首次启动服务器不可达 → 错误页 → 网络恢复 →
            // 用户点屏幕却没动静）。这里补一条回落：没有 URL 就直接重新加载配置的服务器。
            if (webView.getUrl() == null && webView.getOriginalUrl() == null) {
                loadConfiguredServer();
            } else {
                webView.reload();
            }
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
        // 只加载 http(s) 页面，file:// 访问没有任何用处，关掉能少一条攻击面。
        // content:// 仍要保持开启 —— 文件选择器（onShowFileChooser）拿到的就是 content URI。
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUserAgentString(settings.getUserAgentString() + " JukuMobile/" + currentVersionName());
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
                // 每次整页加载重新起表，避免上一轮的计时误伤这一轮
                pageLoadSettled = false;
                pageLoadWatchdogRounds = 0;
                mainHandler.removeCallbacks(pageLoadWatchdog);
                mainHandler.postDelayed(pageLoadWatchdog, PAGE_LOAD_WATCHDOG_MS);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                pageLoadSettled = true;
                mainHandler.removeCallbacks(pageLoadWatchdog);
                installShellBridge();
                // 通知权限在这里问（页面已就绪的空闲时机），而不是在更新流程中间 ——
                // 权限框若与"下载完成→拉起安装界面"撞车，安装确认界面会被系统的
                // 「后台启动 Activity」限制静默丢弃（不报错、界面不出现），
                // 用户看到的就是"点了下载没反应"。见 requestNotificationPermissionIfNeeded 的说明。
                requestNotificationPermissionIfNeeded();
                maybeAutoCheckUpdate();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError("无法连接服务器\n\n" + error.getDescription()
                            + "\n\n请检查手机网络是否正常；"
                            + "也可以在浏览器里打开下面的地址确认服务器是否可达。");
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
                showError("HTTPS 证书校验失败\n\n请点右上角「⋯」→「服务器地址」，"
                        + "确认地址与证书是否匹配。");
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
                // 按页面 <input accept="…"> 声明的类型过滤。
                // 原来的写法固定 "*/*"，选图片也得从「最近文件」里翻；
                // 只有一个具体类型时直接 setType，多个类型走 EXTRA_MIME_TYPES。
                String[] acceptTypes = params.getAcceptTypes();
                if (acceptTypes != null && acceptTypes.length > 0) {
                    java.util.List<String> mimeTypes = new java.util.ArrayList<>();
                    for (String type : acceptTypes) {
                        if (type != null && type.contains("/")) {
                            mimeTypes.add(type);
                        }
                    }
                    if (mimeTypes.size() == 1) {
                        intent.setType(mimeTypes.get(0));
                    } else if (mimeTypes.size() > 1) {
                        intent.setType("*/*");
                        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toArray(new String[0]));
                    } else {
                        intent.setType("*/*");
                    }
                } else {
                    intent.setType("*/*");
                }
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
                + "var items=[['server','服务器地址'],['updatesource','更新源地址'],['update','检查更新'],['clearcache','清除网页缓存'],['restart','重启客户端'],['about','关于']];"
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
                // 只在网页自己的移动播放器模式下生效：把原生媒体控件层彻底藏掉。
                // 那一层会盖在 video 上吃掉命中测试（iOS 上尤其明显），
                // 网页本来就用自己的一套控件，这里只是兜住漏网情况。
                + "try{"
                + "if(!document.getElementById('juku-shell-style')){"
                + "var shellStyle=document.createElement('style');"
                + "shellStyle.id='juku-shell-style';"
                + "shellStyle.textContent='.mobile-player video::-webkit-media-controls,'"
                + "+'.mobile-player video::-webkit-media-controls-enclosure,'"
                + "+'.mobile-player video::-webkit-media-controls-panel{display:none !important;}';"
                + "(document.head||document.documentElement).appendChild(shellStyle);"
                + "}}catch(e){}"
                // 播放状态上报：播放期间外壳给窗口加 FLAG_KEEP_SCREEN_ON，
                // 否则看剧时长时间不触摸屏幕会自动息屏。
                // 用 document 上的捕获监听（媒体事件不冒泡，捕获能收到），
                // 这样页面重建 video 元素也不用重新绑定。
                + "if(!window.__jukuPlaybackWatch){"
                + "window.__jukuPlaybackWatch=true;"
                + "var lastPlay=null;"
                + "window.__jukuReportPlayback=function(){"
                + "var playing=false;"
                + "try{"
                + "var vs=document.querySelectorAll('video');"
                + "for(var i=0;i<vs.length;i++){var v=vs[i];"
                + "if(v&&!v.paused&&!v.ended&&!v.seeking&&v.readyState>2){playing=true;break;}}"
                + "}catch(e){}"
                + "if(playing!==lastPlay){lastPlay=playing;"
                + "try{JukuShell.setPlaybackState(playing);}catch(e){}"
                + "try{console.log('[juku] playback='+playing);}catch(e){}}"
                + "};"
                + "var onPlaybackEvent=function(){window.__jukuReportPlayback();};"
                + "var evts=['playing','play','pause','ended','emptied','waiting','seeked'];"
                + "for(var i=0;i<evts.length;i++){"
                + "document.addEventListener(evts[i],onPlaybackEvent,true);}"
                + "window.__jukuReportPlayback();"
                + "}"
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
                // 点击呼出播放器控件的兜底。
                // 网页自己的逻辑是 stage 上 pointerdown/pointerup 成对，且要求
                // 「位移 ≤ 12px 且耗时 ≤ 320ms」，超时就当手势丢掉 —— 手指按住稍久
                // 就会出现「控件隐藏后点屏幕中间呼不出来」。这里做看门狗：
                // 只在「控件确实是隐藏状态、且页面 200ms 内没有自己呼出来」时补一次
                // 合成的快速点击；页面正常工作时不会重复触发。
                + "window.__jukuTapWatch=function(x,y){"
                + "var p=document.getElementById('playerPanel');"
                + "if(!p||!(p.open===true||p.hasAttribute('open'))){return;}"
                + "if(!p.classList.contains('player-controls-hidden')){return;}"
                + "var now=Date.now();"
                + "if(now-(window.__jukuTapAt||0)<450){return;}"
                + "window.__jukuTapAt=now;"
                + "setTimeout(function(){"
                + "var panel=document.getElementById('playerPanel');"
                + "if(!panel||!panel.classList.contains('player-controls-hidden')){return;}"
                + "var stage=panel.querySelector('.playback-stage');"
                + "if(!stage){return;}"
                + "var fire=function(type,buttons){"
                + "var ev;"
                + "try{ev=new PointerEvent(type,{bubbles:true,cancelable:true,composed:true,"
                + "pointerId:1,pointerType:'touch',isPrimary:true,button:0,buttons:buttons,"
                + "clientX:x||0,clientY:y||0});}"
                + "catch(e){ev=new Event(type,{bubbles:true,cancelable:true});"
                + "ev.pointerId=1;ev.isPrimary=true;ev.button=0;ev.clientX=x||0;ev.clientY=y||0;}"
                + "stage.dispatchEvent(ev);};"
                + "fire('pointerdown',1);fire('pointerup',0);"
                + "try{console.log('[juku] player tap fallback');}catch(e){}"
                + "},200);};"
                + "var jukuTouchStart=null;"
                + "document.addEventListener('touchstart',function(e){"
                + "var t=e.changedTouches&&e.changedTouches[0];"
                + "jukuTouchStart=t?{x:t.clientX,y:t.clientY,time:Date.now()}:null;},true);"
                + "document.addEventListener('touchend',function(e){"
                + "var t=e.changedTouches&&e.changedTouches[0];"
                + "var start=jukuTouchStart;jukuTouchStart=null;"
                + "if(!t||!start){return;}"
                + "var el=e.target;"
                + "if(el&&el.closest&&el.closest('button,input,select,a,label')){return;}"
                + "if(Math.hypot(t.clientX-start.x,t.clientY-start.y)>12){return;}"
                + "if(Date.now()-start.time>900){return;}"
                + "window.__jukuTapWatch(t.clientX,t.clientY);},true);"
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

    /**
     * 播放中保持屏幕常亮。
     *
     * 只在状态真的变化时动窗口标志 —— 网页里 video 的 play/pause 事件很密集，
     * 每次都 addFlags 会让窗口频繁请求布局。
     */
    private void applyPlaybackState(boolean playing) {
        if (playbackActive == playing) {
            return;
        }
        playbackActive = playing;
        if (playing && !isFinishing()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * 清除网页缓存并重新加载。
     *
     * 网页改版后残留旧资源、或页面状态错乱时的一键自救入口
     * （相当于浏览器里的「强制刷新」）。刻意**不动 Cookie**，否则会把登录态一起清掉。
     */
    private void clearWebCacheAndReload() {
        if (webView == null) {
            return;
        }
        try {
            webView.clearCache(true);
        } catch (Exception ignored) {
            // 清缓存失败不该阻塞重载
        }
        preferences().edit().putInt(KEY_WEB_CACHE_VERSION, currentVersionCode()).apply();
        showingError = false;
        errorView.setVisibility(View.GONE);
        Toast.makeText(this, "已清除网页缓存，正在重新加载", Toast.LENGTH_SHORT).show();
        if (webView.getUrl() == null) {
            loadConfiguredServer();
        } else {
            webView.reload();
        }
    }

    /**
     * Android 13+ 发通知需要 POST_NOTIFICATIONS 运行时权限。
     * 没这个权限时 update 下载进度通知会被静默丢弃（notify() 不报错，通知栏却什么都没有）。
     *
     * ★ 调用时机很讲究：**在网页加载完成的空闲时机问一次**（见 onPageFinished），
     * 绝不能在更新流程中间问 —— 系统权限框会占住前台，而"下载完成 → 拉起系统安装界面"
     * 恰好发生在同一时刻，那次 startActivity 会被 Android 10+ 的「后台启动 Activity」
     * 限制静默丢弃：不报错、界面不出现，用户体感就是"点了下载没反应"。
     * 拒绝也不影响下载本身，只是通知栏看不到进度。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        // 只问一次：被拒绝后再调用 requestPermissions，系统会直接回调 denied 而不弹窗
        // （Android 的"不再询问"机制），每次都调只会白白多走一轮 IPC 并写脏更新记录。
        if (notificationPermissionAsked
                || preferences().getBoolean(KEY_NOTIFICATION_ASKED, false)) {
            return;
        }
        notificationPermissionAsked = true;
        preferences().edit().putBoolean(KEY_NOTIFICATION_ASKED, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST);
    }

    /** 外壳菜单。正常情况走网页「更多」面板里的注入条目，这里只服务兜底悬浮按钮。 */
    private void showShellMenu() {
        if (isFinishing()) {
            return;
        }
        String[] items = {"服务器地址", "更新源地址", "刷新", "重启客户端", "清除网页缓存", "检查更新", "回到首页", "关于"};
        new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle(R.string.app_name)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            showServerDialog();
                            break;
                        case 1:
                            showUpdateSourceDialog();
                            break;
                        case 2:
                            showingError = false;
                            errorView.setVisibility(View.GONE);
                            if (webView.getUrl() == null && webView.getOriginalUrl() == null) {
                                loadConfiguredServer();
                            } else {
                                webView.reload();
                            }
                            break;
                        case 3:
                            restartClient();
                            break;
                        case 4:
                            clearWebCacheAndReload();
                            break;
                        case 5:
                            checkForUpdate(true);
                            break;
                        case 6:
                            String address = preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
                            webView.loadUrl(normalizeServerUrl(address));
                            break;
                        case 7:
                            showAboutDialog();
                            break;
                        default:
                            break;
                    }
                })
                .show();
    }

    /**
     * 重启客户端（重建 WebView + 回到首页）。
     *
     * 用途：网页前端进入异常状态时（SPA 路由错乱、注入脚本状态卡死、沉浸标志残留），
     * 单纯「刷新页面」往往救不回来，用户的实际做法是去最近任务里划掉再打开。
     * 这里给一个应用内入口，把这类操作收敛成一次点击。
     *
     * 刻意**不杀进程**：`AlarmManager` 定时的「真·冷启动」要依赖精确闹钟权限，
     * 拿不到权限时闹钟会被系统推迟，症状是「点了重启，应用反而消失了」。
     * 重建 WebView（并复位沉浸/常亮/错误页等外壳状态）已经能覆盖全部已知的卡死场景，
     * 而且行为完全可预期。
     */
    private void restartClient() {
        noteUpdateStep("用户触发重启客户端（重建 WebView）");
        try {
            applyImmersiveMode(false);
            applyPlaybackState(false);
            if (webView != null) {
                contentRoot.removeView(webView);
                webView.stopLoading();
                webView.setWebChromeClient(null);
                webView.setWebViewClient(new WebViewClient());
                webView.destroy();
                webView = null;
            }
        } catch (Exception error) {
            Log.w(LOG_TAG, "重启客户端时销毁旧 WebView 失败: " + error);
        }
        showingError = false;
        errorView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        rebuildWebView();
        loadConfiguredServer();
        Toast.makeText(this, "已重启客户端", Toast.LENGTH_SHORT).show();
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
        if (cachedVersion >= currentVersionCode()) {
            return;
        }
        webView.clearCache(true);
        preferences().edit().putInt(KEY_WEB_CACHE_VERSION, currentVersionCode()).apply();
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

    /**
     * 安装成功后清掉「下载」目录里的那份副本。
     * 安装**失败**时刻意保留 —— 那是用户手动安装的兜底路径
     * （界面上也提示了"可到下载文件夹手动点击安装"）。
     */
    private void clearPublishedApk() {
        Uri uri = publishedApkUri;
        publishedApkUri = null;
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().delete(uri, null, null);
        } catch (Exception error) {
            Log.w(LOG_TAG, "清理下载目录副本失败: " + error);
        }
    }

    /**
     * 记录最近一次更新流程的关键步骤（存 SharedPreferences，并在「关于」里展示）。
     * 手机端出问题时，用户直接截「关于」页就能把现场带出来，不必去翻 logcat
     * （iOS 压根没有 logcat，部分 ROM 也不好抓）。
     */
    private void noteUpdateStep(String step) {
        Log.i(LOG_TAG, "update: " + step);
        String stamp = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        String previous = preferences().getString(KEY_UPDATE_TRACE, "");
        StringBuilder builder = new StringBuilder();
        if (!previous.trim().isEmpty()) {
            String[] lines = previous.trim().split("\n");
            // 保留最近 15 行：一次完整的更新流程（点击 → 下载 → 预检 → 安装 → 结果）
            // 就要占十来行，行数留少了会把最关键的开头挤掉。
            int from = Math.max(0, lines.length - 14);
            for (int index = from; index < lines.length; index++) {
                builder.append(lines[index]).append('\n');
            }
        }
        builder.append(stamp).append("  ").append(step);
        preferences().edit().putString(KEY_UPDATE_TRACE, builder.toString()).apply();
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
        String downloadUrl = bestKnownDownloadUrl();
        StringBuilder text = new StringBuilder()
                .append("应用：果果剧库 手机版\n")
                .append("版本：").append(currentVersionName())
                .append("（versionCode ").append(currentVersionCode()).append("）\n")
                .append("服务器：").append(server).append("\n")
                .append("更新源：").append(describeActiveUpdateSource()).append("\n")
                .append("设备：").append(describeDevice()).append("\n")
                // 安装来源放进诊断信息：Android 14+ 的「更新归属」会让非本应用安装的包
                // 在应用内更新时被系统静默压住，这一行能直接看出是不是这个原因。
                .append("安装来源：").append(describeInstallSource()).append("\n");
        if (signature != null) {
            text.append("签名：").append(signature).append("\n");
        }
        text.append("\n点击“检查更新”可立即获取最新版本。")
                .append("\n若自动更新装不上，可复制下面这行到浏览器下载安装：\n")
                .append(downloadUrl)
                .append("\n\n页面卡住不动时，可在“⋯ → 重启客户端”里重建界面。");

        // 最近更新记录：手机端出问题时用户直接把这一页截图即可（不必抓 logcat）
        String trace = preferences().getString(KEY_UPDATE_TRACE, "").trim();
        if (!trace.isEmpty()) {
            text.append("\n\n最近更新记录：\n").append(trace);
        }

        final String diagnostics = text.toString();
        TextView view = new TextView(this);
        view.setText(diagnostics);
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
                .setNeutralButton("复制信息", (dialog, which) -> copyDiagnostics(diagnostics))
                .setNegativeButton("关闭", null)
                .show();
    }

    /** 设备与系统版本：排查"只在某类手机上出问题"时第一时间要看的信息。 */
    private String describeDevice() {
        return Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE
                + "（API " + Build.VERSION.SDK_INT + "）";
    }

    /** 把「关于」页的内容整段复制到剪贴板，用户可一键粘贴发出来。 */
    private void copyDiagnostics(String text) {
        if (copyToClipboard("果果剧库诊断信息", text)) {
            Toast.makeText(this, "已复制，可直接粘贴发送", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "复制失败，请手动选择文本", Toast.LENGTH_SHORT).show();
        }
    }

    /** 写剪贴板。返回是否成功。 */
    private boolean copyToClipboard(String label, String text) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return false;
            }
            manager.setPrimaryClip(ClipData.newPlainText(label, text));
            return true;
        } catch (Exception error) {
            Log.w(LOG_TAG, "写剪贴板失败: " + error);
            return false;
        }
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
                noteUpdateStep("检查更新：本地 " + currentVersionName() + "(" + currentVersionCode()
                        + ") → 服务器 " + update.versionName + "(" + update.versionCode + ")");
                runOnUiThread(() -> {
                    updateCheckRunning = false;
                    preferences().edit().putLong(KEY_LAST_UPDATE_CHECK, System.currentTimeMillis()).apply();
                    if (update.versionCode <= currentVersionCode()) {
                        if (userInitiated) {
                            Toast.makeText(MainActivity.this, "已经是最新版本 " + update.versionName, Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                    showUpdatePrompt(update);
                });
            } catch (Exception error) {
                noteUpdateStep("检查失败：" + updateError(error));
                runOnUiThread(() -> {
                    updateCheckRunning = false;
                    if (userInitiated) {
                        Toast.makeText(MainActivity.this, "检查更新失败：" + updateError(error), Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "juku-update-check").start();
    }

    /**
     * 依次尝试各个更新源，第一个成功的即采用。
     *
     * ★ 为什么要这样：更新通道原来**只认服务端的 `/api/mobile/update`**，而服务端
     * （剧库本体）在上游新版里把这个接口整组下线了 —— 业务服务一升级，手机端的
     * 检查更新/下载就跟着一起失效，而且**存量已装版本无法自救**。
     * 更新通道本来就不该绑在业务服务上，所以改成多级：
     *
     *   ① 自定义源（用户可在「更新源地址」里填自己的静态地址，最高优先）
     *   ② 服务器（`{服务器}/api/mobile/update`；服务端若恢复了这个接口就自动用上）
     *   ③ GitHub Release 附件（CI 每次发版都会发布 update.json，天然存在、无需登录）
     *
     * 上次成功的源会被记住并优先尝试，避免每次都从失效的源开始等超时。
     */
    private MobileUpdate fetchMobileUpdate() throws Exception {
        java.util.List<UpdateSource> sources = updateSources();
        Exception lastError = null;
        for (UpdateSource source : sources) {
            try {
                MobileUpdate update = fetchFromSource(source);
                preferences().edit()
                        .putString(KEY_LAST_GOOD_SOURCE, source.key)
                        .putString(KEY_LAST_APK_URL, update.apkUrl.toString())
                        .apply();
                noteUpdateStep("更新源可用：" + source.label);
                return update;
            } catch (Exception error) {
                lastError = error;
                noteUpdateStep("更新源不可用（" + source.label + "）：" + updateError(error));
            }
        }
        throw new IOException("所有更新源都不可用（最后错误："
                + updateError(lastError) + "）");
    }

    private MobileUpdate fetchFromSource(UpdateSource source) throws Exception {
        URL url = new URL(source.manifestUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(UPDATE_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(UPDATE_READ_TIMEOUT_MS);
        connection.setUseCaches(false);
        // GitHub 的 release 资产会 302 跳到 release-assets.githubusercontent.com，必须跟随
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "JukuMobile/" + currentVersionName());
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = readText(stream);
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException(describeHttpStatus(status));
            }
            JSONObject json = new JSONObject(body);
            MobileUpdate update = new MobileUpdate();
            update.versionCode = json.getInt("versionCode");
            update.versionName = json.optString("versionName", "新版本");
            update.sha256 = json.optString("sha256", "");
            update.notes = json.optString("notes", "本次更新包含功能优化和问题修复。");
            update.size = json.optLong("size", 0L);
            update.apkUrl = resolveAssetUrl(source, json.optString("apkUrl", ""),
                    source.versionedAsset
                            ? "juku-mobile-" + update.versionName + ".apk"
                            : "juku-mobile.apk");
            return update;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 把 manifest 里的下载地址解析成可用的 URL。
     *
     * 三种形态都要能处理：
     *   ① 绝对地址（`http(s)://…`）—— 直接用（服务端补回接口后就是这种；
     *      CI 之后也会往 update.json 里写 GitHub 的绝对地址）
     *   ② 相对地址 —— 只在**源的基准**下拼（服务器源才能拼，因为
     *      `/api/mobile/apk?name=…` 是服务端约定；换成 GitHub 就拼出 404 了）
     *   ③ 什么都没给 —— 按该源的资产命名约定兜底
     */
    private URL resolveAssetUrl(UpdateSource source, String raw, String fallbackName) throws Exception {
        if (raw != null && !raw.trim().isEmpty()) {
            String value = raw.trim();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return new URL(value);
            }
            if (source.relativeBase != null) {
                return new URL(new URL(source.relativeBase), value);
            }
        }
        return new URL(source.assetPrefix + fallbackName);
    }

    private String describeHttpStatus(int status) {
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED || status == HttpURLConnection.HTTP_FORBIDDEN) {
            // 服务端新版是全局鉴权：未登录时**任何**路径都返回 401（连不存在的也是），
            // 所以 401 只说明"这条路走不通"，不代表接口还在
            return "该更新源需要登录（HTTP " + status + "）";
        }
        if (status == HttpURLConnection.HTTP_NOT_FOUND) {
            return "该更新源没有发布信息（HTTP 404）";
        }
        return "HTTP " + status;
    }

    /** 组装更新源列表（自定义 → 服务器 → GitHub），上次可用的排最前。 */
    private java.util.List<UpdateSource> updateSources() {
        java.util.List<UpdateSource> list = new java.util.ArrayList<>();
        String custom = preferences().getString(KEY_UPDATE_SOURCE_OVERRIDE, "").trim();
        if (!custom.isEmpty()) {
            list.add(new UpdateSource("custom", "自定义源", normalizeManifestUrl(custom), null, custom, false));
        }
        String server = normalizeServerUrl(preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        list.add(new UpdateSource("server", "服务器",
                server + "api/mobile/update", server, server + "api/mobile/apk?name=", false));
        list.add(new UpdateSource("github", "GitHub",
                GITHUB_LATEST_DOWNLOAD + "update.json", null, GITHUB_LATEST_DOWNLOAD, true));

        String lastGood = preferences().getString(KEY_LAST_GOOD_SOURCE, "");
        if (!lastGood.isEmpty()) {
            for (int index = 0; index < list.size(); index++) {
                if (lastGood.equals(list.get(index).key)) {
                    list.add(0, list.remove(index));
                    break;
                }
            }
        }
        return list;
    }

    /** 自定义源地址：允许只填目录（以 `/` 结尾）或完整的 update.json 地址。 */
    private String normalizeManifestUrl(String raw) {
        String value = raw.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        if (value.endsWith("/")) {
            value = value + "update.json";
        }
        return value;
    }

    /**
     * 一个更新源。
     *
     * @param key            稳定标识（记录"上次可用"用）
     * @param label          展示名（写进更新记录）
     * @param manifestUrl    去哪取 update.json
     * @param relativeBase   manifest 里给**相对**下载地址时的解析基准；null = 不允许相对
     * @param assetPrefix    按约定造下载地址时的前缀
     * @param versionedAsset 资产名是否带版本号（GitHub 是 `juku-mobile-<版本>.apk`）
     */
    private static final class UpdateSource {
        final String key;
        final String label;
        final String manifestUrl;
        final String relativeBase;
        final String assetPrefix;
        final boolean versionedAsset;

        UpdateSource(String key, String label, String manifestUrl,
                     String relativeBase, String assetPrefix, boolean versionedAsset) {
            this.key = key;
            this.label = label;
            this.manifestUrl = manifestUrl;
            this.relativeBase = relativeBase;
            this.assetPrefix = assetPrefix;
            this.versionedAsset = versionedAsset;
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
        String message = "当前版本：" + currentVersionName() + "\n"
                + "最新版本：" + update.versionName + "（" + size + "）\n\n"
                + update.notes;
        new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle("发现手机版更新")
                .setMessage(message)
                .setPositiveButton("下载并安装", (dialog, which) -> downloadAndInstall(update))
                // 兜底出口：应用内安装在某些 ROM 上会被拦得莫名其妙（签名/来源/扫描都会拦），
                // 浏览器下载是**一定走得通**的那条路（用户手动更新就是这么装的）。
                // 有这个按钮，用户就不会卡在"点了没反应"上。
                .setNeutralButton("浏览器下载", (dialog, which) -> openDownloadInBrowser())
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 「更新源地址」设置。
     *
     * 留空 = 自动：先试服务器（`{服务器}/api/mobile/update`，服务端若恢复该接口就自动生效），
     * 失败则回退 GitHub Release 附件。也可以填自己的静态地址 ——
     * 目录形式（以 `/` 结尾，会取该目录下的 `update.json`）或完整的 update.json 地址。
     * 业务服务端把接口下线后，这是"不改服务端也能继续更新"的那条路。
     */
    private void showUpdateSourceDialog() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(preferences().getString(KEY_UPDATE_SOURCE_OVERRIDE, ""));
        input.setHint("留空 = 自动");
        input.setSelectAllOnFocus(true);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#7C838E"));
        int padding = dp(22);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(padding, dp(8), padding, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle("更新源地址")
                .setMessage("留空 = 自动（服务器 → GitHub）\n\n"
                        + "也可以填自己的静态地址，例如 http://1.2.3.4/mobile/"
                        + "（会自动去取该目录下的 update.json）\n\n"
                        + "当前：" + describeActiveUpdateSource())
                .setView(wrapper)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .setNeutralButton("清空", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String value = input.getText().toString().trim();
                preferences().edit()
                        .putString(KEY_UPDATE_SOURCE_OVERRIDE, value)
                        // 换源后"上次可用"就不再成立了，清掉重新判定
                        .remove(KEY_LAST_GOOD_SOURCE)
                        .apply();
                Toast.makeText(this,
                        value.isEmpty() ? "已恢复自动选择更新源" : "已保存更新源",
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> input.setText(""));
        });
        dialog.show();
    }

    /** 一句话说明当前会走哪个更新源（「更新源地址」对话框与「关于」页共用）。 */
    private String describeActiveUpdateSource() {
        String custom = preferences().getString(KEY_UPDATE_SOURCE_OVERRIDE, "").trim();
        if (!custom.isEmpty()) {
            return "自定义源 " + normalizeManifestUrl(custom);
        }
        String lastGood = preferences().getString(KEY_LAST_GOOD_SOURCE, "");
        if ("github".equals(lastGood)) {
            return "GitHub Release（自动回退）";
        }
        if ("server".equals(lastGood)) {
            return "服务器接口";
        }
        return "自动（服务器 → GitHub）";
    }

    /** 已知可用的安装包直链；没有就返回 GitHub 的版本列表页（浏览器能用）。 */
    private String bestKnownDownloadUrl() {
        String cached = preferences().getString(KEY_LAST_APK_URL, "").trim();
        if (!cached.isEmpty()) {
            return cached;
        }
        return GITHUB_RELEASES_PAGE;
    }

    /** 用系统浏览器打开安装包地址 —— 应用内更新完全走不通时的最后一条路。 */
    private void openDownloadInBrowser() {
        String url = bestKnownDownloadUrl();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            noteUpdateStep("已交给浏览器下载：" + url);
        } catch (ActivityNotFoundException error) {
            copyToClipboard("果果剧库下载地址", url);
            Toast.makeText(this,
                    "没有可用的浏览器，地址已复制，可粘贴到浏览器里打开",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void downloadAndInstall(MobileUpdate update) {
        // ★ 入口先落一条记录：能回答"这次点击到底有没有进到下载流程"。
        // 之前整条链路里最早的记录是"开始下载"，而它前面还有一道
        // `if (downloadDialog != null) return;` —— 一旦踩中，日志里连一行都不会有，
        // 现场完全不可解释（用户反馈就是"点了没反应"）。
        noteUpdateStep("用户点击下载并安装：目标 " + update.versionName
                + "(" + update.versionCode + ")");
        if (downloadDialog != null) {
            // 不静默：说清楚为什么没动
            noteUpdateStep("已有下载在进行中，忽略本次点击");
            Toast.makeText(this, "更新正在下载中，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startDownload(update);
        } catch (Exception error) {
            // 准备阶段（建目录/建对话框）出任何问题都要说出来，不能静默
            noteUpdateStep("启动下载失败（" + error.getClass().getSimpleName() + "）：" + updateError(error));
            closeDownloadDialog();
            Toast.makeText(this,
                    "无法开始下载：" + updateError(error), Toast.LENGTH_LONG).show();
        }
    }

    private void startDownload(MobileUpdate update) {
        downloadCancelled = false;
        File directory = new File(getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) {
            Toast.makeText(this, "无法创建更新缓存目录", Toast.LENGTH_SHORT).show();
            return;
        }
        final File target = new File(directory, "juku-mobile-" + update.versionCode + ".apk");
        // 先把历史残留包清掉，避免旧包被误当成新包安装
        removeOtherApks(directory, target);
        // 记下服务端声明的目标版本，安装前拿实物包比对（见 prepareAndInstall）
        pendingUpdateVersionCode = update.versionCode;
        // ★ 通知权限**不在这里**请求。
        // 在这里请求会让系统权限框与"下载完成→拉起安装界面"撞在一起：
        // 权限框占着前台时，系统的安装确认界面会被「后台启动 Activity」限制静默丢掉，
        // 症状就是"点了下载没反应"。改为在网页加载完成后的空闲时机请求（见 onPageFinished）。
        noteUpdateStep("开始下载：" + target.getName() + "（目标 " + update.size + " B）");
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
        // ★ 必须监听"被关掉"这件事本身，而不是只给「取消」按钮挂 onClick。
        //
        // 之前的写法只在按钮回调里 closeDownloadDialog()，于是**按返回键**（或系统回收、
        // 用户从最近任务划掉又回来等任何非按钮路径）关掉对话框时，downloadDialog 字段
        // 一直是非 null，而 downloadAndInstall() 开头有一句
        //     if (downloadDialog != null) return;
        // ⇒ 之后**每一次**「下载并安装」都会被这句话静默吞掉，
        // 用户看到的就是「点了下载新版本后没反应」。这是本次故障最可能的直接原因。
        downloadDialog.setOnDismissListener(ignored -> {
            downloadDialog = null;
            downloadProgress = null;
            downloadStatus = null;
            // 对话框都没了，还在后台跑的下载就该停掉：否则用户既看不到进度，
            // 又会在某个时刻突然被拉去安装（更莫名其妙）。
            if (!downloadCancelled) {
                downloadCancelled = true;
                if (activeUpdateConnection != null) {
                    activeUpdateConnection.disconnect();
                }
            }
            cancelUpdateNotification();
        });
        downloadDialog.setOnShowListener(ignored -> downloadDialog
                .getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(view -> {
                    noteUpdateStep("用户取消了下载");
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
        try {
            for (int attempt = 0; attempt <= MAX_DOWNLOAD_RETRY; attempt++) {
                if (downloadCancelled) {
                    break;
                }
                try {
                    boolean ok = downloadUpdateOnce(update, target, attempt);
                    if (ok) {
                        runOnUiThread(() -> finishDownloadSuccess(target));
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
        } catch (Throwable fatal) {
            // ★ 只 catch IOException 是不够的：非受检异常（NPE / ClassCastException /
            // IllegalArgumentException 等）会让这个线程**直接死掉**，
            // 既没有 Toast 也不会关掉对话框 —— 界面就永远停在"正在连接服务器…"，
            // 用户看到的就是「点了下载没反应」，而且日志里连一行错误都没有。
            // 这里兜住它，并且明确把它记进更新记录。
            lastError = new IOException("下载过程中出现异常（"
                    + fatal.getClass().getSimpleName() + "）：" + updateError(
                    fatal instanceof Exception ? (Exception) fatal : null));
            Log.w(LOG_TAG, "下载线程异常", fatal);
        }
        final IOException error = lastError;
        noteUpdateStep("下载失败：" + updateError(error));
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
        connection.setRequestProperty("User-Agent", "JukuMobile/" + currentVersionName());
        if (resuming) {
            connection.setRequestProperty("Range", "bytes=" + existing + "-");
        }
        try {
            int status = connection.getResponseCode();
            noteUpdateStep("下载响应 HTTP " + status + (resuming ? "，续传自 " + existing + " B" : ""));
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
                    noteUpdateStep("校验失败：实际 " + actual.substring(0, Math.min(16, actual.length()))
                            + "… 期望 " + update.sha256.substring(0, Math.min(16, update.sha256.length())) + "…");
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

    /**
     * 下载完成 → 安装。
     *
     * ★ 必须用「刚下载并校验通过的那个文件」，不能再去目录里扫。
     * 之前的写法是遍历 cache/updates 取**第一个** .apk，而 listFiles() 的顺序是文件系统决定的：
     * 只要目录里还留着历史残留（例如上一次中断下载留下的 juku-mobile-12.apk），
     * 就会把那个旧包拿去安装 —— 症状是「下载明明成功，安装却报
     * 解析软件包时出现问题 / Failed opening content provider，且报错里的文件名是旧版本号」。
     * 模拟器是干净环境所以复现不出来，用户手机上残留包一多就必然踩中。
     */
    private void finishDownloadSuccess(File apk) {
        if (downloadCancelled) {
            return;
        }
        cancelUpdateNotification();
        closeDownloadDialog();
        if (apk == null || !apk.isFile() || apk.length() <= 0L) {
            noteUpdateStep("下载完成但文件不可用：" + (apk == null ? "(null)" : apk.getName()));
            Toast.makeText(this, "更新文件丢失，请重新下载", Toast.LENGTH_LONG).show();
            return;
        }
        noteUpdateStep("下载完成：" + apk.getName() + " " + apk.length() + " B");
        installApk(apk);
    }

    /**
     * 清掉更新目录里除 keep 之外的安装包。
     * 两个目的：① 不让历史残留包有机会被安装（见 finishDownloadSuccess 的说明）；
     * ② 不长期占存储。
     */
    /**
     * 清理更新缓存目录里「与本次无关」的安装包。
     *
     * ★ 只在**下载之前**调用（keep = 即将下载的目标文件）。
     * 绝不能在启动时把目录清空：下载完成 → 拉起系统安装器 → 扫描/确认可能耗时较长，
     * 期间若本 Activity 被重建（配置变化、厂商安装器切换任务栈等），
     * 启动清理会把**正在安装的那个包删掉**，安装器随后读 provider 得到
     * 「update file not found」，用户看到的却是「解析软件包时出现问题」。
     */
    private void removeOtherApks(File directory, File keep) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".apk")) {
                continue;
            }
            if (keep != null && file.getAbsolutePath().equals(keep.getAbsolutePath())) {
                continue;
            }
            if (file.delete()) {
                Log.i(LOG_TAG, "removed stale update file " + file.getName());
            }
        }
    }

    /**
     * 启动时的缓存回收：只删「一天前的」残留包，不动最近下载的。
     * 这些包只有几十 KB，目的只是别让它们长期堆积，不值得冒误删的风险。
     */
    private void removeExpiredApks(File directory, long maxAgeMs) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        long deadline = System.currentTimeMillis() - maxAgeMs;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".apk")) {
                continue;
            }
            if (file.lastModified() > deadline) {
                continue;
            }
            if (file.delete()) {
                Log.i(LOG_TAG, "recycled expired update file " + file.getName());
            }
        }
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
        AlertDialog dialog = downloadDialog;
        downloadDialog = null;
        downloadProgress = null;
        downloadStatus = null;
        if (dialog != null) {
            // 先摘掉 dismiss 监听再关：否则会走一遍"被关掉"分支，把 downloadCancelled
            // 置真 —— 而下载**成功**后正是在这里关对话框的，那会把一次好结果判成取消。
            dialog.setOnDismissListener(null);
            dialog.dismiss();
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

    /**
     * 安装更新包。
     *
     * ★ 解析 APK、比对签名、写入安装会话都是 IO，**必须离开主线程**：
     * 之前这些都在 UI 线程上做，实测在（较慢的）设备上会长时间阻塞主线程，
     * 系统弹出「应用无响应」对话框 —— 用户体感就是「点了下载并安装之后卡住」。
     */
    private void installApk(File apk) {
        noteUpdateStep("开始安装：" + apk.getName() + " " + apk.length() + " B");
        new Thread(() -> prepareAndInstall(apk), "juku-install").start();
    }

    private void prepareAndInstall(File apk) {
        // 预检 1：包本身是否可解析、包名是否匹配
        String localIssue = precheckApk(apk);
        if (localIssue != null) {
            noteUpdateStep("安装前预检未通过：" + localIssue);
            runOnUiThread(() -> {
                if (!isFinishing()) {
                    Toast.makeText(this, "更新包有问题：" + localIssue, Toast.LENGTH_LONG).show();
                }
            });
            return;
        }
        // 预检 2：实物包的 versionCode 必须等于服务端 version.json 声明的值。
        // 服务端历史上踩过的坑是「APK 与 update.json 不是同批发布」（APK 的 sha256
        // 每次构建都变，手工抄必然对不上）。sha256 校验能拦住大部分情况，但若服务端
        // 那份 update.json 的 sha256 为空就会漏过去，于是把不匹配的包装进手机。
        // 这里做最后一道闸：宁可中止，也不装一个来路不明的版本。
        int expectedCode = pendingUpdateVersionCode;
        if (expectedCode > 0) {
            int actualCode = packageVersionCode(apk);
            noteUpdateStep("更新包 versionCode=" + actualCode + "（服务端声明 " + expectedCode + "）");
            if (actualCode > 0 && actualCode != expectedCode) {
                noteUpdateStep("版本不一致，已中止安装（防止装上错配的包）");
                runOnUiThread(() -> showVersionMismatchGuide(actualCode, expectedCode));
                return;
            }
        }
        // 预检 3：签名是否与已安装版本一致 —— 不一致时系统必然拒绝，提前给出指引
        if (hasSignatureConflict(apk)) {
            noteUpdateStep("签名与已安装版本不一致");
            runOnUiThread(() -> showSignatureConflictGuide(apk));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            runOnUiThread(() -> showInstallPermissionDialog(apk));
            return;
        }
        // 记录"这次要装到哪一版" + 当前的安装来源。
        // 来源很关键：Android 14+ 有「更新归属」限制 —— 若本应用是被别的安装器
        // （微信/文件管理器/浏览器）装上的，我们自己发起的安装会话有可能被系统压着
        // 不弹界面也不回报结果。这条信息能一眼看出是不是这个原因。
        markPendingInstall(expectedCode > 0 ? expectedCode : packageVersionCode(apk));
        noteUpdateStep("安装来源：" + describeInstallSource());
        try {
            installWithPackageInstaller(apk);
        } catch (Exception error) {
            pendingInstallFile = null;
            disarmInstallWatchdog();
            // 带上异常类名：PackageInstaller 在部分 ROM 上抛 SecurityException
            // （「不允许安装」），和一般的 IO 异常处理方式不同，日志里必须能区分开。
            noteUpdateStep("会话安装失败（" + error.getClass().getSimpleName() + "）："
                    + updateError(error) + "，改用系统安装器");
            runOnUiThread(() -> installWithViewer(apk));
        }
    }

    /** 当前这个包是被谁安装/发起的 —— 排查「更新归属」类拦截时第一时间要看。 */
    private String describeInstallSource() {
        PackageManager manager = getPackageManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                InstallSourceInfo info = manager.getInstallSourceInfo(getPackageName());
                return "安装者=" + String.valueOf(info.getInstallingPackageName())
                        + "，发起者=" + String.valueOf(info.getInitiatingPackageName());
            }
            return "安装者=" + String.valueOf(manager.getInstallerPackageName(getPackageName()));
        } catch (Exception error) {
            return "读取失败（" + error.getClass().getSimpleName() + "）";
        }
    }

    /** 「需要安装权限」对话框：去系统设置里允许安装未知应用，回来自动继续。 */
    private void showInstallPermissionDialog(File apk) {
        if (isFinishing()) {
            return;
        }
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
            // 提交之前就把看门狗架起来：commit() 本身也可能一路阻塞不返回，
            // 那时同样不会有任何界面/回调 —— 这正是"点了没反应"的形态之一。
            armInstallWatchdog();
            session.commit(pendingIntent.getIntentSender());
            noteUpdateStep("已提交安装会话（session " + sessionId + "），等待系统响应");
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
    /**
     * 把更新包另存一份到**公共「下载」目录**，返回可直接交给系统安装器的 URI。
     *
     * ★ 为什么不只用自有 provider（这是「Failed opening content provider」的真正症结）：
     *
     * 更新包原本下载在应用私有的 `cache/updates/` 下。该目录**其它 uid 读不到**
     * （`/data/data/<pkg>` 只有本应用和 root 能进）。而安装一条 APK，
     * 读这个包的**不止系统安装器**：厂商 ROM（MIUI/HyperOS 等）在弹出安装界面之前，
     * 会由「安全中心 / 病毒扫描」这类**独立进程**先读一遍安装包
     * （界面上那句「安装包扫描中，请稍候」就是它）。
     *
     * 这类扫描进程通常按**文件路径**（MediaStore 的 `_data` 列）去读文件，
     * 而不是走 `openInputStream()` —— 把私有目录的路径给它，它照样读不到，
     * 于是报出那句含糊的「解析软件包时出现问题。(11) Failed opening content provider」。
     * 即使把 provider 导出（1.3.12 做了）也救不了：问题不在权限，在**文件本身不可达**。
     *
     * 放进公共「下载」目录后：
     * - 任何进程都能按路径读到这个文件；
     * - URI 由系统 media provider 提供，**不依赖我们的进程是否还活着**；
     * - 顺带给了用户一条兜底路径 —— 自动安装失败时，可以直接去「下载」文件夹
     *   手动点一下安装，不必重新下载。
     *
     * Android 10 以下没有 MediaStore.Downloads，沿用自有 provider（老 ROM 没有这类扫描步骤）。
     */
    private Uri publishApkToDownloads(File apk) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }
        ContentResolver resolver = getContentResolver();
        String displayName = apk.getName();
        // 先清掉同名的旧项，避免「下载」里堆一串历史安装包
        try {
            resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{displayName});
        } catch (Exception ignored) {
            // 删不掉不影响新建
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                "application/vnd.android.package-archive");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri item = null;
        try {
            item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (item == null) {
                return null;
            }
            try (OutputStream output = resolver.openOutputStream(item);
                 FileInputStream input = new FileInputStream(apk)) {
                if (output == null) {
                    throw new IOException("无法打开目标文件");
                }
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.flush();
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(item, values, null, null);
            return item;
        } catch (Exception error) {
            Log.w(LOG_TAG, "导出安装包到下载目录失败: " + error);
            if (item != null) {
                try {
                    resolver.delete(item, null, null);
                } catch (Exception ignored) {
                    // 清理失败无所谓，下次同名会被覆盖
                }
            }
            return null;
        }
    }

    private void installWithViewer(File apk) {
        disarmInstallWatchdog();
        noteUpdateStep("改用系统安装器打开：" + apk.getName() + "（" + apk.length() + " B）");
        // 优先用公共「下载」目录里的副本：私有 cache 里的文件厂商扫描进程读不到
        Uri publicUri = publishApkToDownloads(apk);
        Uri uri = publicUri != null ? publicUri : ApkFileProvider.uriForFile(this, apk);
        String mime = "application/vnd.android.package-archive";
        if (publicUri != null) {
            publishedApkUri = publicUri;
            noteUpdateStep("已另存到「下载」文件夹（供扫描进程按路径读取）");
        }

        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(uri, mime);
        viewIntent.setClipData(ClipData.newUri(getContentResolver(), "果果剧库更新", uri));
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(viewIntent);
            noteUpdateStep("已拉起系统安装器（ACTION_VIEW）");
            Toast.makeText(this,
                    "若系统没有自动安装，可到「下载」文件夹手动点击安装包",
                    Toast.LENGTH_LONG).show();
            return;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // 继续尝试 ACTION_INSTALL_PACKAGE 作为兜底
        }

        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setDataAndType(uri, mime);
        installIntent.setClipData(ClipData.newUri(getContentResolver(), "果果剧库更新", uri));
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(installIntent);
            noteUpdateStep("已拉起系统安装器（ACTION_INSTALL_PACKAGE）");
            Toast.makeText(this,
                    "若系统没有自动安装，可到「下载」文件夹手动点击安装包",
                    Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException error) {
            noteUpdateStep("找不到可用的系统安装器：" + updateError(error));
            Toast.makeText(this,
                    "自动安装不可用：安装包已保存到「下载」文件夹，请到文件管理器里点击安装",
                    Toast.LENGTH_LONG).show();
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

    /**
     * 更新包实物版本与服务端版本信息对不上时的说明。
     *
     * 这种情况几乎只有一个成因：服务端只替换了 APK 与 update.json 中的一份
     * （两份文件不是同批发布）。装上会造成版本号混乱、甚至功能异常，所以宁可中止。
     */
    private void showVersionMismatchGuide(int actualCode, int expectedCode) {
        if (isFinishing()) {
            return;
        }
        new AlertDialog.Builder(this, R.style.JukuDialogTheme)
                .setTitle("更新包与版本信息不一致")
                .setMessage("服务器上这份安装包的实际版本号是 " + actualCode
                        + "，但版本信息里写的是 " + expectedCode + "。\n\n"
                        + "通常是因为服务端这次只换了其中一份文件（安装包与 update.json "
                        + "不是同批发布的）。为了不装上错误的版本，已中止安装。\n\n"
                        + "可以稍后重试；若一直如此，请让服务器管理员重新发布一次。")
                .setPositiveButton("知道了", null)
                .show();
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

    /**
     * 安装结果是否属于「用户主动放弃 / 被设备策略拦下」。
     *
     * `STATUS_FAILURE_ABORTED(3)`  = 用户在系统安装界面上点了取消（或安装会话被主动放弃）
     * `STATUS_FAILURE_BLOCKED(4)`  = 被设备策略 / 家长控制 / 未知来源限制挡下
     *
     * 这两类都不是「包有问题」，不该触发降级重试：用户不想装，就别再弹一次安装器。
     */
    private boolean isUserAbortedStatus(int status) {
        return status == PackageInstaller.STATUS_FAILURE_ABORTED
                || status == PackageInstaller.STATUS_FAILURE_BLOCKED;
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

    /**
     * 网络恢复时自动重试。
     *
     * 原来的错误页只有"点一下重试"，用户在地铁/电梯里断了网、出来后有网了，
     * 还得手动点一次才知道能用了。这里监听默认网络的变化，只要**当前正显示错误页**
     * 就自动重新加载（页面正常时什么都不做，不会打断正在播的视频）。
     */
    private void registerNetworkRecovery() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                mainHandler.post(() -> {
                    if (isFinishing() || !showingError || webView == null) {
                        return;
                    }
                    Toast.makeText(MainActivity.this, "网络已恢复，正在重新加载", Toast.LENGTH_SHORT).show();
                    showingError = false;
                    errorView.setVisibility(View.GONE);
                    // 同 errorView 的点击重试：首次加载就失败时 WebView 没有 URL，reload() 是空操作
                    if (webView.getUrl() == null && webView.getOriginalUrl() == null) {
                        loadConfiguredServer();
                    } else {
                        webView.reload();
                    }
                });
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(networkCallback);
            } else {
                manager.registerNetworkCallback(
                        new NetworkRequest.Builder()
                                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                .build(),
                        networkCallback);
            }
        } catch (Exception error) {
            // 注册失败不影响主流程，错误页仍然可以手动点击重试
            networkCallback = null;
        }
    }

    /**
     * 显示错误页。
     *
     * 末尾统一补上「当前服务器地址」—— 排查网络问题时这是首先要确认的信息，
     * 而错误页上原来没有它，用户只能再去菜单里翻。文案也统一在这里收口，
     * 避免各调用点各写一套"点此重试"。
     */
    private void showError(String message) {
        showingError = true;
        progressBar.setVisibility(View.GONE);
        String server = normalizeServerUrl(preferences().getString(KEY_SERVER_URL, DEFAULT_SERVER_URL));
        errorView.setText(message
                + "\n\n当前服务器：\n" + server
                + "\n\n点屏幕任意位置重试"
                + "（网络恢复后也会自动重试）");
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
        boolean wasPaused = webViewPaused;
        webView = new WebView(this);
        contentRoot.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // 新 WebView 是「活的」，先把上一实例遗留的「屏幕常亮」状态复位。
        playbackActive = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        configureWebView();
        // 重建前若正处于暂停（后台），要重新施加暂停：
        // pauseTimers() 是进程级的，和实例状态必须保持一致，否则会出现
        // 「以为是活的、实际定时器已停」这类难查的卡死。
        if (wasPaused) {
            webViewPaused = true;
            webView.onPause();
            webView.pauseTimers();
        } else {
            webViewPaused = false;
            webView.resumeTimers();
        }
    }

    private SharedPreferences preferences() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /**
     * 读取本机实际安装的版本号（与 `app/build.gradle` 天然一致，不需要在两处维护）。
     * 结果缓存一次；进程被杀后重装新版本再启动自然会重新读到新值。
     */
    private synchronized void resolveVersion() {
        if (resolvedVersionName != null && resolvedVersionCode > 0) {
            return;
        }
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            int code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) info.getLongVersionCode()
                    : info.versionCode;
            resolvedVersionCode = code > 0 ? code : FALLBACK_VERSION_CODE;
            String name = info.versionName;
            resolvedVersionName = name == null || name.trim().isEmpty()
                    ? FALLBACK_VERSION_NAME : name.trim();
        } catch (Exception error) {
            Log.w(LOG_TAG, "读取安装版本号失败，使用兜底值: " + error);
            resolvedVersionCode = FALLBACK_VERSION_CODE;
            resolvedVersionName = FALLBACK_VERSION_NAME;
        }
    }

    private String currentVersionName() {
        resolveVersion();
        return resolvedVersionName;
    }

    private int currentVersionCode() {
        resolveVersion();
        return resolvedVersionCode;
    }

    /** 读取指定 APK 文件的 versionCode。返回 0 表示无法解析。 */
    private int packageVersionCode(File apk) {
        try {
            PackageInfo archive = getPackageManager()
                    .getPackageArchiveInfo(apk.getAbsolutePath(), 0);
            if (archive == null) {
                return 0;
            }
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) archive.getLongVersionCode()
                    : archive.versionCode;
        } catch (Exception ignored) {
            return 0;
        }
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
        // 回到前台时补一次「拉起系统安装界面」。
        // 典型场景：下载完成的那一刻正好弹着通知权限框，系统给的确认界面被
        // 「后台启动 Activity」限制静默丢弃了 —— 等我们回到前台再拉起一次就好，
        // 否则用户看到的就是"点了下载，允许完权限，然后什么都没有"。
        if (installSessionAwaitingResult && !installUiLaunched
                && pendingInstallConfirmation != null) {
            noteUpdateStep("回到前台，重试拉起系统安装界面");
            launchPendingInstallConfirmation();
        }
        if (webViewPaused) {
            webViewPaused = false;
            webView.resumeTimers();
            webView.onResume();
        }
        // 回到前台时让网页重报一次播放状态：
        // 后台期间视频被暂停，而「播放中」的事件不会因为恢复而发生，得主动问一次，
        // 否则回到前台继续播放后屏幕会按原来的判断息屏。
        if (webView != null) {
            webView.evaluateJavascript(
                    "try{window.__jukuReportPlayback&&window.__jukuReportPlayback();}catch(e){}", null);
        }
        maybeCheckUpdateOnForeground();
    }

    /**
     * 切到后台时暂停 WebView。
     *
     * 不加这段的话，App 退到后台后页面的 JS 定时器（注入脚本里那个 1.5s 的菜单巡检）、
     * CSS 动画、轮询请求都会照常跑，白白耗电耗流量；视频也会在后台继续解码。
     * 恢复时成对调用 resumeTimers()/onResume()，否则 WebView 会一直卡在暂停态。
     */
    @Override
    protected void onPause() {
        if (webView != null && !webViewPaused) {
            webViewPaused = true;
            webView.onPause();
            webView.pauseTimers();
        }
        // 后台不该继续占着「屏幕常亮」的标记
        applyPlaybackState(false);
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            // 拒绝只是看不到通知栏进度，下载与安装照常进行
            noteUpdateStep(granted ? "已获得通知权限" : "未授予通知权限（通知栏看不到下载进度）");
        }
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
        mainHandler.removeCallbacks(pageLoadWatchdog);
        disarmInstallWatchdog();
        if (networkCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(networkCallback);
            } catch (Exception ignored) {
                // 注销失败无副作用
            }
            networkCallback = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
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
