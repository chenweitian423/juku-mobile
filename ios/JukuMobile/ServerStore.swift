import Foundation

/// 服务器地址与检查节奏的持久化。
/// 语义对齐 Android 端 `MainActivity` 里的 SharedPreferences 逻辑（含旧内网地址迁移）。
struct ServerStore {

    private enum Key {
        static let serverURL = "server_url"
        static let lastUpdateCheck = "last_update_check"
        static let webCacheVersion = "web_cache_version"
    }

    private let defaults: UserDefaults

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - 服务器地址

    /// 读数即做迁移：落在旧内网地址上时改写到线上默认地址，并落盘。
    var serverURL: String {
        get {
            let raw = defaults.string(forKey: Key.serverURL) ?? JukuConfig.defaultServerURL
            var address = ServerStore.normalize(raw)
            if ServerStore.isLegacyInternal(address) {
                address = ServerStore.normalize(JukuConfig.defaultServerURL)
                defaults.set(address, forKey: Key.serverURL)
            }
            return address
        }
        set {
            defaults.set(ServerStore.normalize(newValue), forKey: Key.serverURL)
        }
    }

    /// 恢复默认地址。
    mutating func resetServerURL() {
        serverURL = JukuConfig.defaultServerURL
    }

    // MARK: - 更新节奏

    var lastUpdateCheck: Date? {
        get { defaults.object(forKey: Key.lastUpdateCheck) as? Date }
        set { defaults.set(newValue, forKey: Key.lastUpdateCheck) }
    }

    var webCacheVersion: Int {
        get { defaults.integer(forKey: Key.webCacheVersion) }
        set { defaults.set(newValue, forKey: Key.webCacheVersion) }
    }

    /// 距上次检查是否已超过 `interval`。从未检查过返回 true。
    func shouldCheckUpdate(interval: TimeInterval) -> Bool {
        guard let last = lastUpdateCheck else { return true }
        return Date().timeIntervalSince(last) >= interval
    }

    // MARK: - 规范化

    /// 补齐 scheme 与结尾斜杠。空值回落默认地址。
    static func normalize(_ raw: String?) -> String {
        var address = (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if address.isEmpty {
            address = JukuConfig.defaultServerURL
        }
        let lower = address.lowercased()
        if !lower.hasPrefix("http://") && !lower.hasPrefix("https://") {
            address = "http://" + address
        }
        if !address.hasSuffix("/") {
            address += "/"
        }
        return address
    }

    /// 是否为需要被迁移掉的旧内网地址。
    static func isLegacyInternal(_ address: String) -> Bool {
        guard let components = URLComponents(string: address),
              let host = components.host,
              host == JukuConfig.legacyInternalHost else {
            return false
        }
        // 没写端口时按 scheme 默认端口处理，视作非旧内网地址。
        guard let port = components.port else { return false }
        return JukuConfig.legacyInternalPorts.contains(port)
    }
}
