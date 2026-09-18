import Foundation

/// 全局常量。数值与 Android 端 `MainActivity.java` 顶部常量一一对应，
/// 改版本号时两边必须同步（另见 `app/build.gradle` 与 `ios/project.yml`）。
enum JukuConfig {

    // MARK: - 版本

    static let currentVersionName = "1.3.3"
    static let currentVersionCode = 12

    // MARK: - 服务器

    /// 默认服务器。线上地址，18888 是 8999 的反代。
    static let defaultServerURL = "https://duanju.sky423.cn:18888/"

    /// 旧内网地址（192.168.123.121:8998 / :8999）会在启动时自动迁移到 `defaultServerURL`。
    static let legacyInternalHost = "192.168.123.121"
    static let legacyInternalPorts: Set<Int> = [8998, 8999]

    /// 网页端可通过 `window.JukuShell` 感知外壳；UA 里也带版本号，便于服务端统计。
    static var userAgentSuffix: String { "JukuMobile/\(currentVersionName)" }

    static let shellHandlerName = "jukuShell"

    // MARK: - 更新节奏

    /// 自动检查更新的最小间隔。
    static let autoUpdateInterval: TimeInterval = 12 * 60 * 60

    /// 从后台回到前台时，距上次检查超过该时长才重新检查。
    static let minForegroundRecheck: TimeInterval = 30 * 60

    /// 网络超时。
    static let connectTimeout: TimeInterval = 10
    static let readTimeout: TimeInterval = 15

    // MARK: - 更新接口路径

    static let updateAPIPath = "api/mobile/update"
    static let apkAPIPath = "api/mobile/apk"

    // MARK: - iOS 更新包来源

    /// iOS 不能像 Android 那样静默安装，也无法从服务端直接取 IPA。
    /// 因此版本比较仍走服务端 `api/mobile/update`（两端同版本发布），
    /// 但下载统一指向 GitHub Releases —— 除非服务端返回了 `ipaUrl` 字段。
    static let releasesURL = "https://github.com/chenweitian423/juku-mobile/releases/latest"
}
