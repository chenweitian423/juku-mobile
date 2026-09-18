import UIKit
import WebKit

/// 外壳主控制器：承载 WKWebView，并提供菜单、进度、错误页、更新提示、
/// 播放器沉浸模式、文件上传/下载等原生能力。
///
/// 与 Android 端 `MainActivity` 的行为一一对应：
/// | Android                             | iOS                                        |
/// |-------------------------------------|--------------------------------------------|
/// | `addJavascriptInterface(JukuShell)`  | `WKScriptMessageHandler("jukuShell")`      |
/// | `ActionBar` 显隐                     | `setNavigationBarHidden`                   |
/// | `onShowCustomView` 全屏              | 由页面自身 CSS 旋转，外壳不旋转系统方向      |
/// | `DownloadManager`                   | `WKDownload`                               |
/// | `onShowFileChooser`                 | 无需实现，WKWebView 自动弹系统选择器        |
final class RootViewController: UIViewController {

    // MARK: - 依赖

    private var store = ServerStore()

    // MARK: - 视图

    private var webView: WKWebView!
    private var progressBar: UIProgressView!
    private var errorContainer: UIView!
    private var errorLabel: UILabel!

    // MARK: - 状态

    private var progressObservation: NSKeyValueObservation?
    private var updateTask: Task<Void, Never>?
    private var updateCheckRunning = false
    private var didReportMainFrameError = false
    private var immersiveActive = false
    private var toastAlert: UIAlertController?
    /// 网页里是否有 video 正在播放 —— 决定要不要禁用息屏。
    private var playbackActive = false
    private var webViewTopSafeConstraint: NSLayoutConstraint?
    private var webViewTopFullConstraint: NSLayoutConstraint?
    /// 兜底菜单入口：默认隐藏，只在注入网页头部失败时显示。
    private var fallbackMenuButton: UIButton?

    private static let brandOrange = UIColor(red: 0.94, green: 0.35, blue: 0.16, alpha: 1)
    private static let appBackground = UIColor(red: 0.059, green: 0.067, blue: 0.082, alpha: 1)

    // MARK: - 生命周期

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = Self.appBackground

        title = "果果剧库"
        configureNavigationItem()
        configureWebView()
        configureOverlays()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification,
            object: nil
        )
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applicationWillResignActive),
            name: UIApplication.willResignActiveNotification,
            object: nil
        )

        migrateWebCacheIfNeeded()
        loadConfiguredServer()
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
        progressObservation?.invalidate()
        updateTask?.cancel()
        webView?.configuration.userContentController
            .removeScriptMessageHandler(forName: JukuConfig.shellHandlerName)
    }

    // MARK: - 界面搭建

    private func configureNavigationItem() {
        // 导航栏常驻隐藏：网页自己的 `.app-header` 已经有品牌名与菜单，
        // 原生导航栏再显示一遍标题会重复占掉一整行，把内容压下去。
        // 外壳菜单改由注入到网页头部的按钮触发（见 injectShellBridge）。
        navigationController?.setNavigationBarHidden(true, animated: false)
    }

    private func configureWebView() {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.websiteDataStore = .default()
        // 在默认 UA 后追加 " JukuMobile/1.3.3"，等价 Android 端 setUserAgentString 拼接。
        configuration.applicationNameForUserAgent = JukuConfig.userAgentSuffix
        configuration.userContentController.add(
            WeakMessageHandler(target: self),
            name: JukuConfig.shellHandlerName
        )

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
        view.addSubview(webView)
        self.webView = webView

        // 顶边贴 safeArea：导航栏可见时内容从其下方开始（与 Android ActionBar 布局一致）；
        // 进入播放器沉浸模式后导航栏与状态栏同时隐藏，安全区归零，播放器自然铺满全屏。
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        // 顶部约束二选一：常态贴安全区（内容不被刘海/状态栏遮挡），
        // 播放器沉浸时改成贴屏幕顶（真正铺满，见 applyPlayerState）。
        webViewTopSafeConstraint = webView.topAnchor.constraint(
            equalTo: view.safeAreaLayoutGuide.topAnchor
        )
        webViewTopFullConstraint = webView.topAnchor.constraint(equalTo: view.topAnchor)
        webViewTopSafeConstraint?.isActive = true

        // 点击兜底：iOS 上视频层会吃掉命中测试，网页的 stage 收不到 pointer 事件，
        // 表现就是播放器控件自动隐藏后，点屏幕中间呼不出来。
        // 这里挂一个「不取消触摸」的点击手势（cancelsTouchesInView = false，
        // 不影响网页自己的任何手势），把判断交给网页里的 __jukuTapWatch：
        // 它只会在「控件确实处于隐藏状态且页面没响应」时补一次合成的快速点击。
        let playerTapFallback = UITapGestureRecognizer(
            target: self,
            action: #selector(handlePlayerTapFallback)
        )
        playerTapFallback.cancelsTouchesInView = false
        playerTapFallback.requiresExclusiveTouchType = false
        webView.addGestureRecognizer(playerTapFallback)

        progressObservation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] webView, _ in
            let progress = Float(webView.estimatedProgress)
            self?.progressBar.setProgress(progress, animated: true)
            self?.progressBar.isHidden = progress >= 1.0
        }
    }

    private func configureOverlays() {
        let progressBar = UIProgressView(progressViewStyle: .bar)
        progressBar.translatesAutoresizingMaskIntoConstraints = false
        progressBar.progressTintColor = Self.brandOrange
        progressBar.trackTintColor = .clear
        progressBar.isHidden = true
        view.addSubview(progressBar)
        self.progressBar = progressBar

        // 兜底菜单入口：默认隐藏，只有注入网页头部失败时才显示，保证菜单永远可达
        let menuButton = UIButton(type: .system)
        menuButton.translatesAutoresizingMaskIntoConstraints = false
        menuButton.setImage(UIImage(systemName: "ellipsis"), for: .normal)
        menuButton.tintColor = .white
        menuButton.backgroundColor = UIColor(white: 0.06, alpha: 0.72)
        menuButton.layer.cornerRadius = 18
        menuButton.isHidden = true
        menuButton.accessibilityLabel = "更多设置"
        menuButton.addTarget(self, action: #selector(fallbackMenuTapped), for: .touchUpInside)
        view.addSubview(menuButton)
        self.fallbackMenuButton = menuButton

        NSLayoutConstraint.activate([
            menuButton.widthAnchor.constraint(equalToConstant: 36),
            menuButton.heightAnchor.constraint(equalToConstant: 36),
            menuButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -6),
            menuButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 6)
        ])

        let errorContainer = UIView()
        errorContainer.translatesAutoresizingMaskIntoConstraints = false
        errorContainer.backgroundColor = Self.appBackground
        errorContainer.isHidden = true
        view.addSubview(errorContainer)
        self.errorContainer = errorContainer

        let errorLabel = UILabel()
        errorLabel.translatesAutoresizingMaskIntoConstraints = false
        errorLabel.numberOfLines = 0
        errorLabel.textAlignment = .center
        errorLabel.textColor = UIColor(white: 0.85, alpha: 1)
        errorLabel.font = .systemFont(ofSize: 16)
        errorContainer.addSubview(errorLabel)
        self.errorLabel = errorLabel

        errorContainer.addGestureRecognizer(
            UITapGestureRecognizer(target: self, action: #selector(retryLoad))
        )

        NSLayoutConstraint.activate([
            progressBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            progressBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            progressBar.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),

            errorContainer.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            errorContainer.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            errorContainer.topAnchor.constraint(equalTo: view.topAnchor),
            errorContainer.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            errorLabel.centerXAnchor.constraint(equalTo: errorContainer.centerXAnchor),
            errorLabel.centerYAnchor.constraint(equalTo: errorContainer.centerYAnchor),
            errorLabel.leadingAnchor.constraint(equalTo: errorContainer.leadingAnchor, constant: 32),
            errorLabel.trailingAnchor.constraint(equalTo: errorContainer.trailingAnchor, constant: -32)
        ])
    }

    // MARK: - 加载

    private func loadConfiguredServer() {
        let address = store.serverURL
        guard let url = URL(string: address) else {
            showError("服务器地址无法解析：\n\(address)\n\n请从右上角菜单修改服务器地址。")
            return
        }
        didReportMainFrameError = false
        errorContainer.isHidden = true
        webView.load(URLRequest(url: url))
    }

    /// 升级后清一次网页缓存，避免旧前端资源残留（对应 Android `clearWebCacheAfterUpgrade`）。
    private func migrateWebCacheIfNeeded() {
        guard store.webCacheVersion < JukuConfig.currentVersionCode else { return }
        // 必须显式传 completionHandler：
        // iOS 15 起 WKWebsiteDataStore 也有 removeData(ofTypes:modifiedSince:) 的
        // async 重载，不写 completionHandler 会被解析成 async 调用，
        // 在非 async 函数里直接报 "'async' call in a function that does not support concurrency"。
        // 且该参数在新 SDK 里是非可选的 @MainActor @Sendable 闭包，传 nil 会报类型不兼容，
        // 所以给一个空闭包。
        WKWebsiteDataStore.default().removeData(
            ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(),
            modifiedSince: .distantPast,
            completionHandler: {}
        )
        store.webCacheVersion = JukuConfig.currentVersionCode
    }

    /// 错误页统一在末尾补上「当前服务器地址」——排查网络问题时这是首先要确认的信息，
    /// 原来错误页上没有它，用户只能再去菜单里翻（对应 Android 的 showError）。
    private func showError(_ message: String) {
        errorLabel.text = message
            + "\n\n当前服务器：\n\(store.serverURL)"
            + "\n\n点屏幕任意位置重试"
        errorContainer.isHidden = false
    }

    @objc private func retryLoad() {
        loadConfiguredServer()
    }

    // MARK: - 播放器沉浸模式

    /// 网页通过 `window.JukuShell.setPlayerState(active, hidden, landscape)` 上报播放器状态。
    ///
    /// ⚠️ 这里**刻意不做任何系统级旋转**。网页的横竖屏是它自己用 CSS 旋转实现的
    /// （`player-rotated` class + `--player-rotation`，由页面里的横屏按钮或方向感应驱动，
    /// 见 `/assets/player-orientation.js`）。外壳一旦插入 `requestGeometryUpdate`，
    /// 系统旋转会与页面自身的 CSS 旋转叠加，症状是：
    ///   1. 一进播放器就被强制横屏（用户没点横屏按钮）；
    ///   2. 页面按自己的尺寸变量算布局，系统旋转后视口尺寸变了，
    ///      播放区 `stage` 的渲染区域与命中区域错位 → 控件自动隐藏后点屏幕中央呼不出来。
    ///
    /// 沉浸条件取「播放中 **且** 已横屏 **且** 控件已隐藏」：
    /// 竖屏时导航栏常驻，这样即使控件一时没呼出来，用户转回竖屏也能拿到菜单。
    private func applyPlayerState(active: Bool, controlsHidden: Bool, landscape: Bool) {
        let immersive = active && landscape && controlsHidden
        guard immersive != immersiveActive else { return }
        immersiveActive = immersive
        // 导航栏本来就是常驻隐藏的（见 configureNavigationItem），这里只切换状态栏
        setNeedsStatusBarAppearanceUpdate()
        // 沉浸时让 WebView 铺到屏幕最上沿：否则在刘海机型上安全区仍有 47~59pt，
        // 播放器顶部会留一条黑边、而且那条区域点不动。
        // 网页侧会用 env(safe-area-inset-top) 自己避让刘海（WebView 覆盖到刘海后该值为真实值）。
        webViewTopSafeConstraint?.isActive = !immersive
        webViewTopFullConstraint?.isActive = immersive
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        .allButUpsideDown
    }

    override var shouldAutorotate: Bool { true }

    override var prefersStatusBarHidden: Bool { immersiveActive }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    // MARK: - 菜单

    @objc private func fallbackMenuTapped() {
        presentShellMenu()
    }

    /// 点击兜底：交给网页里的 __jukuTapWatch 判断是否需要补一次点击。
    /// 页面正常（控件已经呼出 / 不在播放器里）时它什么都不做，所以不会重复触发。
    @objc private func handlePlayerTapFallback() {
        webView.evaluateJavaScript(
            "window.__jukuTapWatch && window.__jukuTapWatch(0, 0)",
            completionHandler: nil
        )
    }

    /// 处理网页「更多」面板里注入条目的动作（server / updatesource / update / clearcache / restart / about）。
    private func handleNativeAction(_ action: String) {
        switch action {
        case "server":
            presentServerDialog()
        case "updatesource":
            presentUpdateSourceDialog()
        case "update":
            checkForUpdate(userInitiated: true)
        case "clearcache":
            clearWebCacheAndReload()
        case "restart":
            restartClient()
        case "about":
            presentAbout()
        default:
            break
        }
    }

    /// 「更新源地址」设置：留空 = 自动（服务器 → GitHub）。
    private func presentUpdateSourceDialog() {
        let alert = UIAlertController(
            title: "更新源地址",
            message: "留空 = 自动（服务器 → GitHub）\n\n"
                + "也可以填自己的静态地址，例如 http://1.2.3.4/mobile/"
                + "（会自动去取该目录下的 update.json）\n\n"
                + "当前：\(describeActiveUpdateSource())",
            preferredStyle: .alert
        )
        alert.addTextField { [weak self] field in
            field.placeholder = "留空 = 自动"
            field.text = self?.store.updateSourceOverride
            field.keyboardType = .URL
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "保存", style: .default) { [weak self, weak alert] _ in
            guard let self else { return }
            self.store.updateSourceOverride = alert?.textFields?.first?.text ?? ""
            // 换源后"上次可用"就不再成立，清掉重新判定
            self.store.lastGoodUpdateSource = ""
            self.presentToast("已保存更新源")
        })
        alert.addAction(UIAlertAction(title: "清空", style: .destructive) { [weak self] _ in
            guard let self else { return }
            self.store.updateSourceOverride = ""
            self.store.lastGoodUpdateSource = ""
            self.presentToast("已恢复自动选择更新源")
        })
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        present(alert, animated: true)
    }

    /// 一句话说明当前会走哪个更新源。
    private func describeActiveUpdateSource() -> String {
        if !store.updateSourceOverride.isEmpty {
            return "自定义源 \(ServerStore.normalizeManifestURL(store.updateSourceOverride) ?? "")"
        }
        switch store.lastGoodUpdateSource {
        case "github": return "GitHub Release（自动回退）"
        case "server": return "服务器接口"
        default: return "自动（服务器 → GitHub）"
        }
    }

    /// 已知可用的安装包直链；没有就返回 GitHub 的版本列表页。
    private func bestKnownDownloadURL() -> String {
        if !store.lastAPKURL.isEmpty {
            return store.lastAPKURL
        }
        return JukuConfig.githubLatestDownload
            .replacingOccurrences(of: "/releases/latest/download/", with: "/releases/latest")
    }

    /// 重启客户端。
    ///
    /// iOS 不允许应用自杀后自拉起（会被系统判为崩溃），所以这里做的是等价的
    /// **重建 Web 层**：停掉当前加载 → 清掉网页资源缓存（不动 Cookie，保住登录态）
    /// → 重新载入首页，并把播放器沉浸 / 常亮等外壳状态复位。
    /// 网页前端卡死（路由错乱、注入脚本状态异常）时，这比单纯「刷新」更彻底。
    private func restartClient() {
        applyPlayerState(active: false, controlsHidden: false, landscape: false)
        applyPlaybackState(false)
        webView.stopLoading()
        // 只清资源缓存，保留 Cookie / LocalStorage，否则会把登录态一起清掉
        let types = WKWebsiteDataStore.allWebsiteDataTypes().subtracting([
            WKWebsiteDataTypeCookies,
            WKWebsiteDataTypeLocalStorage,
            WKWebsiteDataTypeOfflineWebApplicationCache
        ])
        WKWebsiteDataStore.default().removeData(
            ofTypes: types,
            modifiedSince: .distantPast
        ) { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.errorContainer.isHidden = true
                self.loadConfiguredServer()
                self.presentToast("已重启客户端")
            }
        }
    }

    /// 清除网页缓存并重新加载（浏览器里的「强制刷新」）。
    ///
    /// 只清 URL 缓存与 WebStorage 里的版本标记，**不动 Cookie** —— 否则会把登录态一起清掉。
    private func clearWebCacheAndReload() {
        let store = WKWebsiteDataStore.default()
        let types = WKWebsiteDataStore.allWebsiteDataTypes().subtracting([
            WKWebsiteDataTypeCookies,
            WKWebsiteDataTypeLocalStorage
        ])
        store.removeData(ofTypes: types, modifiedSince: .distantPast) { [weak self] in
            DispatchQueue.main.async {
                self?.presentToast("已清除网页缓存，正在重新加载")
                if let self, self.webView.url == nil {
                    self.loadConfiguredServer()
                } else {
                    self?.webView.reload()
                }
            }
        }
    }

    /// 播放中禁止息屏。状态没变时不动，避免频繁改 UIApplication 状态。
    private func applyPlaybackState(_ playing: Bool) {
        guard playbackActive != playing else { return }
        playbackActive = playing
        UIApplication.shared.isIdleTimerDisabled = playing
    }

    /// 外壳菜单。正常走网页「更多」面板里的注入条目，这里只服务兜底悬浮按钮。
    private func presentShellMenu() {
        let sheet = UIAlertController(title: "果果剧库", message: nil, preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: "服务器地址", style: .default) { [weak self] _ in
            self?.presentServerDialog()
        })
        sheet.addAction(UIAlertAction(title: "更新源地址", style: .default) { [weak self] _ in
            self?.presentUpdateSourceDialog()
        })
        sheet.addAction(UIAlertAction(title: "刷新", style: .default) { [weak self] _ in
            self?.webView.reload()
        })
        sheet.addAction(UIAlertAction(title: "重启客户端", style: .default) { [weak self] _ in
            self?.restartClient()
        })
        sheet.addAction(UIAlertAction(title: "清除网页缓存", style: .default) { [weak self] _ in
            self?.clearWebCacheAndReload()
        })
        sheet.addAction(UIAlertAction(title: "检查更新", style: .default) { [weak self] _ in
            self?.checkForUpdate(userInitiated: true)
        })
        sheet.addAction(UIAlertAction(title: "回到首页", style: .default) { [weak self] _ in
            self?.loadConfiguredServer()
        })
        sheet.addAction(UIAlertAction(title: "关于", style: .default) { [weak self] _ in
            self?.presentAbout()
        })
        sheet.addAction(UIAlertAction(title: "取消", style: .cancel))

        // 导航栏已常驻隐藏，iPad 上不再有 barButtonItem 可作锚点，
        // 改用视图中心（actionSheet 无锚点会崩）。
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = view
            popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        present(sheet, animated: true)
    }

    private func presentServerDialog() {
        let alert = UIAlertController(
            title: "服务器地址",
            message: "例如 https://duanju.sky423.cn:18888/",
            preferredStyle: .alert
        )
        alert.addTextField { [weak self] field in
            field.placeholder = JukuConfig.defaultServerURL
            field.text = self?.store.serverURL
            field.keyboardType = .URL
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            field.clearButtonMode = .whileEditing
        }
        alert.addAction(UIAlertAction(title: "保存并进入", style: .default) { [weak self, weak alert] _ in
            guard let self, let input = alert?.textFields?.first?.text else { return }
            self.store.serverURL = input
            self.loadConfiguredServer()
        })
        alert.addAction(UIAlertAction(title: "恢复默认", style: .destructive) { [weak self] _ in
            guard let self else { return }
            self.store.serverURL = JukuConfig.defaultServerURL
            self.loadConfiguredServer()
        })
        alert.addAction(UIAlertAction(title: "取消", style: .cancel))
        present(alert, animated: true)
    }

    private func presentAbout() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String
            ?? JukuConfig.currentVersionName
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String
            ?? String(JukuConfig.currentVersionCode)
        let bundleID = Bundle.main.bundleIdentifier ?? "com.juku.mobile"
        let system = UIDevice.current.systemName + " " + UIDevice.current.systemVersion
        let message = """
        应用：果果剧库 iOS 版
        版本：\(version) (build \(build))
        服务器：\(store.serverURL)
        更新源：\(describeActiveUpdateSource())
        设备：\(UIDevice.current.model) / \(system)
        标识：\(bundleID)

        iOS 不允许应用自行安装更新包，检查到新版本后会跳转浏览器下载。
        也可直接用浏览器打开下面这个地址：
        \(bestKnownDownloadURL())

        页面卡住不动时，可在「⋯ → 重启客户端」里重建网页层。
        """
        let alert = UIAlertController(title: "关于", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "关闭", style: .cancel))
        alert.addAction(UIAlertAction(title: "复制信息", style: .default) { [weak self] _ in
            // 整段复制，用户可一键粘贴发出来（对应 Android 的「复制信息」）
            UIPasteboard.general.string = message
            self?.presentToast("已复制，可直接粘贴发送")
        })
        alert.addAction(UIAlertAction(title: "检查更新", style: .default) { [weak self] _ in
            self?.checkForUpdate(userInitiated: true)
        })
        present(alert, animated: true)
    }

    // MARK: - 更新

    @objc private func applicationDidBecomeActive() {
        // 回到前台时让网页重报一次播放状态：后台期间视频被暂停，
        // 「播放中」的事件不会因为恢复而发生，得主动问一次，
        // 否则继续播放后屏幕会按旧判断息屏。
        webView.evaluateJavaScript(
            "window.__jukuReportPlayback && window.__jukuReportPlayback()",
            completionHandler: nil
        )
        guard store.shouldCheckUpdate(interval: JukuConfig.minForegroundRecheck) else { return }
        checkForUpdate(userInitiated: false)
    }

    /// 退到后台时解除息屏锁 —— 后台不该继续持有这个全局状态。
    @objc private func applicationWillResignActive() {
        applyPlaybackState(false)
    }

    /// 页面加载完成后按 12 小时节流自动检查（对应 Android `maybeAutoCheckUpdate`）。
    private func maybeAutoCheckUpdate() {
        guard store.shouldCheckUpdate(interval: JukuConfig.autoUpdateInterval) else { return }
        checkForUpdate(userInitiated: false)
    }

    private func checkForUpdate(userInitiated: Bool) {
        guard !updateCheckRunning else {
            if userInitiated { presentToast("正在检查或下载更新") }
            return
        }
        updateCheckRunning = true
        if userInitiated { presentToast("正在检查更新") }

        updateTask?.cancel()
        updateTask = Task { [weak self] in
            guard let self else { return }
            do {
                let update = try await UpdateService.fetch(store: self.store)
                await MainActor.run {
                    self.updateCheckRunning = false
                    self.store.lastUpdateCheck = Date()
                    if !UpdateService.isNewer(update) {
                        if userInitiated {
                            self.presentToast("已经是最新版本 \(update.versionName)")
                        }
                        return
                    }
                    self.presentUpdatePrompt(update)
                }
            } catch {
                await MainActor.run {
                    self.updateCheckRunning = false
                    guard userInitiated else { return }
                    let detail = (error as? LocalizedError)?.errorDescription
                        ?? error.localizedDescription
                    self.presentToast("检查更新失败：\(detail)")
                }
            }
        }
    }

    private func presentUpdatePrompt(_ update: MobileUpdate) {
        let message = """
        当前版本：\(JukuConfig.currentVersionName)
        最新版本：\(update.versionName)（\(update.sizeNote)）

        \(update.notes)

        iOS 需要下载 IPA 后用你自己的签名工具导入安装（AltStore / Sideloadly / TrollStore 等）。
        """
        let alert = UIAlertController(title: "发现手机版更新", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "去下载", style: .default) { _ in
            UIApplication.shared.open(update.downloadURL)
        })
        alert.addAction(UIAlertAction(title: "稍后", style: .cancel))
        present(alert, animated: true)
    }

    /// 轻提示。已有弹窗在展示时不打断，避免 "presenting on a view not in window" 报错。
    private func presentToast(_ text: String) {
        guard view.window != nil, presentedViewController == nil else { return }
        let alert = UIAlertController(title: nil, message: text, preferredStyle: .alert)
        toastAlert = alert
        present(alert, animated: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.6) { [weak self] in
            alert.dismiss(animated: true)
            if self?.toastAlert === alert { self?.toastAlert = nil }
        }
    }

    // MARK: - 外部链接

    /// 非 http/https 交给系统处理。返回 true 表示已接管，调用方不应再加载。
    @discardableResult
    private func handleExternalURL(_ url: URL) -> Bool {
        let scheme = url.scheme?.lowercased() ?? ""
        if scheme.isEmpty || scheme == "http" || scheme == "https" {
            return false
        }
        UIApplication.shared.open(url, options: [:]) { [weak self] success in
            if !success { self?.presentToast("没有可以打开这个链接的应用") }
        }
        return true
    }
}

// MARK: - WKScriptMessageHandler

extension RootViewController: WKScriptMessageHandler {

    func userContentController(_ controller: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        guard message.name == JukuConfig.shellHandlerName,
              let body = message.body as? [String: Any],
              let type = body["type"] as? String else {
            return
        }

        switch type {
        case "playerState":
            let active = (body["active"] as? NSNumber)?.boolValue ?? false
            let hidden = (body["hidden"] as? NSNumber)?.boolValue ?? false
            let landscape = (body["landscape"] as? NSNumber)?.boolValue ?? false
            applyPlayerState(active: active, controlsHidden: hidden, landscape: landscape)
        case "menu":
            if let action = body["action"] as? String {
                handleNativeAction(action)
            }
        case "menuEntry":
            // inpage：网页「更多」面板里已挂上外壳条目，隐藏兜底按钮；
            // floating：网页没有可用入口（未登录时头部动作区被隐藏，或网页改版），显示兜底按钮。
            let mode = body["mode"] as? String ?? "floating"
            fallbackMenuButton?.isHidden = (mode == "inpage")
        case "playback":
            // 播放中禁止息屏，否则看剧时长时间不触摸会自动黑屏。
            applyPlaybackState((body["playing"] as? NSNumber)?.boolValue ?? false)
        case "ready":
            injectShellBridge()
        default:
            break
        }
    }
}

// MARK: - WKNavigationDelegate

extension RootViewController: WKNavigationDelegate {

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let url = navigationAction.request.url, handleExternalURL(url) {
            decisionHandler(.cancel)
            return
        }
        if navigationAction.shouldPerformDownload {
            decisionHandler(.download)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView,
                 decidePolicyFor navigationResponse: WKNavigationResponse,
                 decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        // 无法直接渲染的类型（如 .apk / .zip）走下载流程。
        if !navigationResponse.canShowMIMEType {
            decisionHandler(.download)
            return
        }
        decisionHandler(.allow)
    }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        didReportMainFrameError = false
        errorContainer.isHidden = true
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        progressBar.isHidden = true
        if let pageTitle = webView.title, !pageTitle.isEmpty {
            title = pageTitle
        }
        injectShellBridge()
        maybeAutoCheckUpdate()
    }

    func webView(_ webView: WKWebView,
                 didFailProvisionalNavigation navigation: WKNavigation!,
                 withError error: Error) {
        reportMainFrameError(error)
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
        reportMainFrameError(error)
    }

    private func reportMainFrameError(_ error: Error) {
        let nsError = error as NSError
        // 取消类错误（用户点了别的链接等）不算故障。
        if nsError.domain == NSURLErrorDomain, nsError.code == NSURLErrorCancelled { return }
        guard !didReportMainFrameError else { return }
        didReportMainFrameError = true

        let detail: String
        if nsError.domain == NSURLErrorDomain,
           nsError.code == URLError.Code.serverCertificateUntrusted.rawValue {
            detail = "HTTPS 证书校验失败，请在菜单中检查服务器地址。"
        } else {
            detail = """
            无法连接服务器

            \(error.localizedDescription)

            请检查手机网络是否正常；也可以在浏览器里打开下面的地址确认服务器是否可达。
            """
        }
        showError(detail)
    }

    /// 网页里的 `target="_blank"`：不新开窗口，直接在当前视图加载。
    func webView(_ webView: WKWebView,
                 createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        guard navigationAction.targetFrame == nil, let url = navigationAction.request.url else {
            return nil
        }
        if !handleExternalURL(url) {
            webView.load(navigationAction.request)
        }
        return nil
    }

    // MARK: WKDownload 接管

    func webView(_ webView: WKWebView,
                 navigationAction: WKNavigationAction,
                 didBecome download: WKDownload) {
        download.delegate = self
    }

    func webView(_ webView: WKWebView,
                 navigationResponse: WKNavigationResponse,
                 didBecome download: WKDownload) {
        download.delegate = self
    }
}

// MARK: - WKDownloadDelegate

extension RootViewController: WKDownloadDelegate {

    func download(_ download: WKDownload,
                  decideDestinationUsing response: URLResponse,
                  suggestedFilename: String,
                  completionHandler: @escaping (URL?) -> Void) {
        let fileManager = FileManager.default
        guard let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first else {
            completionHandler(nil)
            return
        }
        let directory = documents.appendingPathComponent("Downloads", isDirectory: true)
        try? fileManager.createDirectory(at: directory, withIntermediateDirectories: true)

        // 同名时追加序号，避免覆盖已有文件。
        var target = directory.appendingPathComponent(suggestedFilename)
        let base = target.deletingPathExtension().lastPathComponent
        let ext = target.pathExtension
        var index = 1
        while fileManager.fileExists(atPath: target.path) {
            let name = ext.isEmpty ? "\(base)-\(index)" : "\(base)-\(index).\(ext)"
            target = directory.appendingPathComponent(name)
            index += 1
        }
        completionHandler(target)
    }

    func downloadDidFinish(_ download: WKDownload) {
        presentToast("下载完成，已保存到「文件 → 我的 iPhone → JukuMobile」")
    }

    func download(_ download: WKDownload, didFailWithError error: Error, resumeData: Data?) {
        let nsError = error as NSError
        if nsError.domain == NSURLErrorDomain, nsError.code == NSURLErrorCancelled { return }
        presentToast("下载失败：\(error.localizedDescription)")
    }
}

// MARK: - WKUIDelegate

extension RootViewController: WKUIDelegate {

    // 注意：这里刻意**不实现** `webView(_:runOpenPanelWith:initiatedByFrame:completionHandler:)`。
    // 该 delegate 在 iOS 上要 18.4+ 才可用（此前是 macOS 专属 API），
    // 在 iOS 15 部署目标下直接报
    //   "'WKOpenPanelParameters' is only available in iOS 18.4 or newer"。
    // iOS 上 WKWebView 会自动为 <input type="file"> 弹出系统文件/照片选择器，
    // 无需外壳介入，删掉是正确做法而非功能缺失。

    func webView(_ webView: WKWebView,
                 requestMediaCapturePermissionFor origin: WKSecurityOrigin,
                 initiatedByFrame frame: WKFrameInfo,
                 type: WKMediaCaptureType,
                 decisionHandler: @escaping (WKPermissionDecision) -> Void) {
        decisionHandler(.grant)
    }
}

// MARK: - 脚本注入

extension RootViewController {

    /// 安装 `window.JukuShell` 并监听播放器面板状态。
    ///
    /// 与 Android 的关键差异：Android 的 `JukuShell` 是原生对象，页面刷新后依然存在；
    /// iOS 侧是注入的 JS 对象，**每次页面加载都要重新赋值**，
    /// 因此「赋值」必须与「安装 observer」分开 —— 后者同一页面只需执行一次。
    ///
    /// 状态来源全部取自页面已有的 class，不自造判断：
    /// - `mobile-player`        → 处于移动端播放器形态
    /// - `player-controls-hidden` → 页面控件已自动隐藏
    /// - `player-landscape`     → 页面已切到横屏（这**不是**系统方向，是页面 CSS 旋转的结果）
    /// 另监听页面在横竖屏切换时派发的 `jukuorientationchange`（见 player-orientation.js），
    /// 避免仅依赖 class 变化而漏掉状态同步。
    func injectShellBridge() {
        let script = """
        (function(){
          window.JukuShell = window.JukuShell || {};
          window.JukuShell.platform = 'ios';
          window.JukuShell.setPlayerState = function(active, hidden, landscape){
            try {
              window.webkit.messageHandlers.\(JukuConfig.shellHandlerName).postMessage({
                type: 'playerState',
                active: !!active,
                hidden: !!hidden,
                landscape: !!landscape
              });
            } catch (e) {}
          };
          window.JukuShell.nativeAction = function(action){
            try {
              window.webkit.messageHandlers.\(JukuConfig.shellHandlerName).postMessage({
                type: 'menu',
                action: String(action)
              });
            } catch (e) {}
          };
          window.JukuShell.setMenuEntry = function(mode){
            try {
              window.webkit.messageHandlers.\(JukuConfig.shellHandlerName).postMessage({
                type: 'menuEntry',
                mode: String(mode)
              });
            } catch (e) {}
          };
          window.JukuShell.setPlaybackState = function(playing){
            try {
              window.webkit.messageHandlers.\(JukuConfig.shellHandlerName).postMessage({
                type: 'playback',
                playing: !!playing
              });
            } catch (e) {}
          };
          // 播放状态上报：播放期间禁止息屏（isIdleTimerDisabled）。
          // 用 document 上的捕获监听（媒体事件不冒泡，捕获能收到），
          // 这样页面重建 video 元素也不用重新绑定。
          if (!window.__jukuPlaybackWatch) {
            window.__jukuPlaybackWatch = true;
            var jukuLastPlay = null;
            window.__jukuReportPlayback = function(){
              var playing = false;
              try {
                var vs = document.querySelectorAll('video');
                for (var i = 0; i < vs.length; i++) {
                  var v = vs[i];
                  if (v && !v.paused && !v.ended && !v.seeking && v.readyState > 2) { playing = true; break; }
                }
              } catch (e) {}
              if (playing !== jukuLastPlay) {
                jukuLastPlay = playing;
                window.JukuShell.setPlaybackState(playing);
                try { console.log('[juku] playback=' + playing); } catch (e) {}
              }
            };
            var jukuOnPlaybackEvent = function(){ window.__jukuReportPlayback(); };
            var jukuPlaybackEvents = ['playing','play','pause','ended','emptied','waiting','seeked'];
            for (var i = 0; i < jukuPlaybackEvents.length; i++) {
              document.addEventListener(jukuPlaybackEvents[i], jukuOnPlaybackEvent, true);
            }
            window.__jukuReportPlayback();
          }
          // 菜单条目注入：直接挂进网页自己的「更多」面板（#morePanel），
          // 不再额外加一个 header 按钮 —— 那样会出现两个「⋯」。
          // 这段必须放在 __jukuShellBridgeInstalled 短路之前 —— SPA 会重建头部 DOM，
          // 登录状态变化也会隐藏/显示入口，所以靠定时器持续校正。
          var jukuInjectMenu = function(){
            var panel = document.getElementById('morePanel');
            if (!panel) { return false; }
            if (panel.querySelector('[data-juku-action]')) { return true; }
            var anchor = panel.querySelector('#libraryMenuUpdatedAt');
            var label = document.createElement('span');
            label.className = 'small';
            label.textContent = '客户端';
            if (anchor) { panel.insertBefore(label, anchor); } else { panel.appendChild(label); }
            var items = [['server', '服务器地址'], ['updatesource', '更新源地址'], ['update', '检查更新'], ['clearcache', '清除网页缓存'], ['restart', '重启客户端'], ['about', '关于']];
            for (var i = 0; i < items.length; i++) {
              var key = items[i][0], text = items[i][1];
              var btn = document.createElement('button');
              btn.type = 'button';
              btn.setAttribute('data-juku-action', key);
              btn.textContent = text;
              btn.addEventListener('click', function(e){
                e.preventDefault(); e.stopPropagation();
                var details = document.getElementById('headerMenu');
                if (details) { details.open = false; }
                window.JukuShell.nativeAction(this.getAttribute('data-juku-action'));
              });
              if (anchor) { panel.insertBefore(btn, anchor); } else { panel.appendChild(btn); }
            }
            return true;
          };
          var jukuLastMode = '';
          var jukuReportMenu = function(){
            var ok = false;
            try { ok = jukuInjectMenu(); } catch (e) { ok = false; }
            var visible = false;
            try {
              var s = document.getElementById('moreButton');
              visible = !!(s && s.offsetParent !== null && getComputedStyle(s).display !== 'none');
            } catch (e) {}
            var mode = (ok && visible) ? 'inpage' : 'floating';
            if (mode !== jukuLastMode) {
              jukuLastMode = mode;
              try {
                console.log('[juku] menu mode=' + mode + ' items=' +
                  document.querySelectorAll('#morePanel [data-juku-action]').length);
              } catch (e) {}
              window.JukuShell.setMenuEntry(mode);
            }
          };
          setInterval(jukuReportMenu, 1500);
          jukuReportMenu();
          // 自动旋转默认关闭：网页的「自动旋转」读的是设备方向传感器
          // （player-orientation.js 里 localStorage 的 juku.playback.autoRotate，默认 true），
          // 跟系统「方向锁定」无关，所以手机横过来播放器就会自己转。
          // 只在用户从未设置过时改成 false，之后他仍可在播放器里自己勾回来。
          try {
            if (localStorage.getItem('juku.playback.autoRotate') === null) {
              localStorage.setItem('juku.playback.autoRotate', 'false');
              var autoBox = document.getElementById('mobileAutoRotate');
              if (autoBox && autoBox.checked) {
                autoBox.checked = false;
                autoBox.dispatchEvent(new Event('change', { bubbles: true }));
              }
            }
          } catch (e) {}
          // 只在网页自己的移动播放器模式下生效：把 WebKit 的原生媒体控件彻底藏掉。
          // 那一层会盖在 video 上吃掉命中测试 —— 网页的 stage 收不到点击，
          // 于是「控件隐藏后点屏幕中间呼不出来」。网页本来就用自己的一套控件
          // （update() 里会 video.controls = !mode.matches），这里只是兜住漏网情况。
          try {
            if (!document.getElementById('juku-shell-style')) {
              var shellStyle = document.createElement('style');
              shellStyle.id = 'juku-shell-style';
              shellStyle.textContent = '.mobile-player video::-webkit-media-controls,' +
                '.mobile-player video::-webkit-media-controls-enclosure,' +
                '.mobile-player video::-webkit-media-controls-panel{display:none !important;}';
              (document.head || document.documentElement).appendChild(shellStyle);
            }
          } catch (e) {}
          if (window.__jukuShellBridgeInstalled) { return; }
          window.__jukuShellBridgeInstalled = true;
          var sync = function(){
            var p = document.getElementById('playerPanel');
            var active = !!(p && (p.open === true || p.hasAttribute('open')) && p.classList.contains('mobile-player'));
            var hidden = active && p.classList.contains('player-controls-hidden');
            var landscape = active && p.classList.contains('player-landscape');
            window.JukuShell.setPlayerState(active, hidden, landscape);
          };
          var bind = function(){
            var p = document.getElementById('playerPanel');
            if (!p) { setTimeout(bind, 200); return; }
            if (p.__jukuShellObserver) { sync(); return; }
            var observer = new MutationObserver(sync);
            observer.observe(p, { attributes: true, attributeFilter: ['class', 'open'] });
            p.addEventListener('jukuorientationchange', sync);
            p.__jukuShellObserver = observer;
            sync();
          };
          bind();

          // ------------------------------------------------------------------
          // 点击呼出播放器控件的兜底
          //
          // 网页自己的逻辑：stage 上 pointerdown/pointerup 成对，且要求
          // 「位移 ≤ 12px 且耗时 ≤ 320ms」，否则直接丢弃。两个已知失效场景：
          //   1) iOS 上视频层会吃掉命中测试，stage 的 pointer 事件收不到；
          //   2) 手指按住稍久（> 320ms）就被当成手势丢掉。
          // 这里做一层看门狗：确认「控件确实是隐藏状态、且页面在 200ms 内没有
          // 自己呼出来」时，才补一次合成的快速点击。页面正常工作时不会重复触发。
          // ------------------------------------------------------------------
          window.__jukuTapWatch = function(x, y){
            var p = document.getElementById('playerPanel');
            if (!p || !(p.open === true || p.hasAttribute('open'))) { return; }
            if (!p.classList.contains('player-controls-hidden')) { return; }
            var now = Date.now();
            if (now - (window.__jukuTapAt || 0) < 450) { return; }
            window.__jukuTapAt = now;
            setTimeout(function(){
              var panel = document.getElementById('playerPanel');
              if (!panel || !panel.classList.contains('player-controls-hidden')) { return; }
              var stage = panel.querySelector('.playback-stage');
              if (!stage) { return; }
              var fire = function(type, buttons){
                var event;
                try {
                  event = new PointerEvent(type, {
                    bubbles: true, cancelable: true, composed: true,
                    pointerId: 1, pointerType: 'touch', isPrimary: true,
                    button: 0, buttons: buttons, clientX: x || 0, clientY: y || 0
                  });
                } catch (e) {
                  event = new Event(type, { bubbles: true, cancelable: true });
                  event.pointerId = 1; event.isPrimary = true;
                  event.button = 0; event.clientX = x || 0; event.clientY = y || 0;
                }
                stage.dispatchEvent(event);
              };
              fire('pointerdown', 1);
              fire('pointerup', 0);
              try { console.log('[juku] player tap fallback'); } catch (e) {}
            }, 200);
          };
          var jukuTouchStart = null;
          document.addEventListener('touchstart', function(e){
            var t = e.changedTouches && e.changedTouches[0];
            jukuTouchStart = t ? { x: t.clientX, y: t.clientY, time: Date.now() } : null;
          }, true);
          document.addEventListener('touchend', function(e){
            var t = e.changedTouches && e.changedTouches[0];
            var start = jukuTouchStart;
            jukuTouchStart = null;
            if (!t || !start) { return; }
            var el = e.target;
            if (el && el.closest && el.closest('button,input,select,a,label')) { return; }
            // 滑动（切集/快进）和长按不算点击
            if (Math.hypot(t.clientX - start.x, t.clientY - start.y) > 12) { return; }
            if (Date.now() - start.time > 900) { return; }
            window.__jukuTapWatch(t.clientX, t.clientY);
          }, true);
        })();
        """
        webView.evaluateJavaScript(script, completionHandler: nil)
    }
}

// MARK: - 弱引用消息处理器

/// `WKUserContentController` 会强引用 handler，直接注册 self 会造成循环引用。
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {

    private weak var target: WKScriptMessageHandler?

    init(target: WKScriptMessageHandler) {
        self.target = target
    }

    func userContentController(_ controller: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}
