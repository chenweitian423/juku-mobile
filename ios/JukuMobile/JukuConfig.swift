import Foundation

/// 全局常量。版本号**唯一事实来源是 `ios/project.yml`**（MARKETING_VERSION /
/// CURRENT_PROJECT_VERSION，两者又由 CI 校验与 `app/build.gradle` 一致），
/// 这里运行时从 `Info.plist` 读，不再硬编码第二份。
enum JukuConfig {

    // MARK: - 版本

    /// 打包后的 `CFBundleShortVersionString`；取不到时返回兜底值（理论上不会发生）。
    static var currentVersionName: String {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
              !value.trimmingCharacters(in: .whitespaces).isEmpty else {
            return fallbackVersionName
        }
        return value
    }

    /// 打包后的 `CFBundleVersion`。
    static var currentVersionCode: Int {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String,
              let value = Int(raw), value > 0 else {
            return fallbackVersionCode
        }
        return value
    }

    private static let fallbackVersionName = "0.0.0"
    private static let fallbackVersionCode = 1

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

    /// 服务端发布目录里 IPA 的固定文件名（与 APK 同目录、共用同一个下载接口，只是文件名不同）。
    ///
    /// 为什么要在客户端硬编码这个名字：实测服务端 `api/mobile/update` 是**它自己重新序列化**的
    /// —— 它读 `/data/mobile/update.json` 取 `versionCode/versionName/sha256/size/notes`，
    /// 但**只回传它认识的字段**（`apkUrl` 等），自定义字段（如 `ipaUrl`）会被丢弃。
    /// 所以 iOS 侧必须自己按约定拼出 IPA 地址，不能依赖服务端下发。
    static let ipaRemoteName = "juku-mobile-unsigned.ipa"

    // MARK: - iOS 更新包来源

    /// iOS 不能像 Android 那样静默安装，也无法从服务端直接取 IPA。
    /// 因此版本比较仍走服务端 `api/mobile/update`（两端同版本发布），
    /// 但下载统一指向 GitHub Releases —— 除非服务端返回了 `ipaUrl` 字段。
    static let releasesURL = "https://github.com/chenweitian423/juku-mobile/releases/latest"
}
