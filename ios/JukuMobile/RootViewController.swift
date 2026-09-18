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
        let appearance = UINavigationBarAppearance()
        appearance.configureWithOpaqueBackground()
        appearance.backgroundColor = Self.appBackground
        appearance.titleTextAttributes = [.foregroundColor: UIColor.white]
        appearance.buttonAppearance.normal.titleTextAttributes = [.foregroundColor: Self.brandOrange]

        navigationItem.standardAppearance = appearance
        navigationItem.scrollEdgeAppearance = appearance

        let menuButton = UIBarButtonItem(
            image: UIImage(systemName: "ellipsis.circle"),
            style: .plain,
            target: self,
            action: #selector(presentMenu)
        )
        menuButton.accessibilityLabel = "菜单"
        navigationItem.rightBarButtonItem = menuButton
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
            webView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])

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

    private func showError(_ message: String) {
        errorLabel.text = message + "\n\n点此重试"
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

        navigationController?.setNavigationBarHidden(immersive, animated: true)
        setNeedsStatusBarAppearanceUpdate()
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        .allButUpsideDown
    }

    override var shouldAutorotate: Bool { true }

    override var prefersStatusBarHidden: Bool { immersiveActive }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    // MARK: - 菜单

    @objc private func presentMenu() {
        let sheet = UIAlertController(title: "果果剧库", message: nil, preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: "服务器地址", style: .default) { [weak self] _ in
            self?.presentServerDialog()
        })
        sheet.addAction(UIAlertAction(title: "刷新", style: .default) { [weak self] _ in
            self?.webView.reload()
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

        // iPad 上 actionSheet 必须有锚点，否则崩溃。
        if let popover = sheet.popoverPresentationController {
            popover.barButtonItem = navigationItem.rightBarButtonItem
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
        let message = """
        应用：果果剧库 iOS 版
        版本：\(version) (build \(build))
        服务器：\(store.serverURL)
        标识：\(bundleID)

        iOS 不允许应用自行安装更新包，检查到新版本后会跳转浏览器下载。
        """
        let alert = UIAlertController(title: "关于", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "关闭", style: .cancel))
        alert.addAction(UIAlertAction(title: "检查更新", style: .default) { [weak self] _ in
            self?.checkForUpdate(userInitiated: true)
        })
        present(alert, animated: true)
    }

    // MARK: - 更新

    @objc private func applicationDidBecomeActive() {
        guard store.shouldCheckUpdate(interval: JukuConfig.minForegroundRecheck) else { return }
        checkForUpdate(userInitiated: false)
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
                let update = try await UpdateService.fetch(server: self.store.serverURL)
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

        iOS 需要下载 IPA 后用 AltStore / Sideloadly 等工具自签安装。
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

            请检查手机网络，或从右上角菜单检查服务器地址。
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
