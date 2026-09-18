import Foundation

/// 服务端 `api/mobile/update` 的响应模型。
struct MobileUpdate {
    let versionCode: Int
    let versionName: String
    let sha256: String
    let notes: String
    let size: Int64
    /// iOS 侧优先使用服务端返回的 `ipaUrl`；没有则回落 GitHub Releases。
    let downloadURL: URL

    var formattedSize: String {
        guard size > 0 else { return "大小未知" }
        let formatter = ByteCountFormatter()
        formatter.countStyle = .file
        formatter.allowedUnits = [.useKB, .useMB]
        return formatter.string(fromByteCount: size)
    }

    /// 服务端 `size` 描述的是 APK 体积；iOS 包体积不同，文案里不写死。
    var sizeNote: String {
        size > 0 ? "服务端安装包 \(formattedSize)" : "大小未知"
    }
}

enum UpdateCheckError: LocalizedError {
    case notPublished
    case loginRequired
    case badStatus(Int)
    case malformed(String)
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .notPublished:
            return "服务器还没有发布手机端更新"
        case .loginRequired:
            return "服务器要求登录后使用，请在网页里先登录"
        case .badStatus(let code):
            return "服务器返回 \(code)"
        case .malformed(let detail):
            return "更新信息格式异常：\(detail)"
        case .transport(let detail):
            return "网络请求失败：\(detail)"
        }
    }
}

/// 检查更新。只做「查询 + 解析」，不做下载 —— 包体下载交给系统浏览器，
/// 因为 iOS 沙盒不允许应用自行安装 IPA。
enum UpdateService {

    /// 一个更新源：去哪取 manifest、以及相对下载地址怎么解析。
    struct Source {
        /// 稳定标识（记录"上次可用"用）。
        let key: String
        /// 展示名（写进诊断信息）。
        let label: String
        /// update.json 的地址。
        let manifestURL: URL
        /// manifest 里给**相对**下载地址时的解析基准；nil = 这种源不允许相对地址。
        let relativeBase: URL?
        /// 按约定造下载地址时的前缀。
        let assetPrefix: String
        /// 资产名是否带版本号（GitHub 是 `juku-mobile-<版本>.apk`）。
        let versionedAsset: Bool
    }

    /// 组装更新源列表（自定义 → 服务器 → GitHub），上次可用的排最前。
    ///
    /// ★ 更新通道原来只认服务端的 `/api/mobile/update`，而服务端上游新版把这组接口
    /// 整个下线了 —— 业务服务一升级，手机端的更新能力就跟着失效。改成多级之后，
    /// 任何一级挂了都能自动落到下一级，服务端恢复接口也能立即自动用上。
    static func sources(store: ServerStore) -> [Source] {
        var list: [Source] = []

        if let custom = ServerStore.normalizeManifestURL(store.updateSourceOverride),
           let url = URL(string: custom) {
            list.append(Source(key: "custom", label: "自定义源", manifestURL: url,
                               relativeBase: nil, assetPrefix: custom, versionedAsset: false))
        }

        let server = ServerStore.normalize(store.serverURL)
        if let base = URL(string: server),
           let manifest = URL(string: JukuConfig.updateAPIPath, relativeTo: base) {
            list.append(Source(key: "server", label: "服务器", manifestURL: manifest,
                               relativeBase: base,
                               assetPrefix: server + JukuConfig.apkAPIPath + "?name=",
                               versionedAsset: false))
        }

        if let manifest = URL(string: JukuConfig.githubManifestURL) {
            list.append(Source(key: "github", label: "GitHub", manifestURL: manifest,
                               relativeBase: nil,
                               assetPrefix: JukuConfig.githubLatestDownload,
                               versionedAsset: true))
        }

        // 上次成功的源排最前，避免每次都从失效的源开始等超时
        let lastGood = store.lastGoodUpdateSource
        if !lastGood.isEmpty, let index = list.firstIndex(where: { $0.key == lastGood }) {
            let hit = list.remove(at: index)
            list.insert(hit, at: 0)
        }
        return list
    }

    /// 命中结果：更新信息 + 是哪个源给的（**由调用方落盘**）。
    ///
    /// 刻意不在这里写 `ServerStore`：它是 `struct`，而这里的入参是 `let` 常量，
    /// 直接赋值编译不过（`cannot assign to property: 'store' is a 'let' constant`）。
    /// 让 UpdateService 保持无状态、由持有者落盘，语义也更清楚。
    struct FetchResult {
        let update: MobileUpdate
        /// 命中的更新源 key（"custom" / "server" / "github"）。
        let sourceKey: String
    }

    /// 依次尝试各更新源，第一个成功的即采用。
    static func fetch(store: ServerStore) async throws -> FetchResult {
        var lastError: Error = UpdateCheckError.malformed("没有可用的更新源")
        for source in sources(store: store) {
            do {
                let update = try await fetch(source: source)
                return FetchResult(update: update, sourceKey: source.key)
            } catch {
                lastError = error
            }
        }
        throw UpdateCheckError.transport("所有更新源都不可用（最后错误："
            + ((lastError as? LocalizedError)?.errorDescription ?? lastError.localizedDescription) + "）")
    }

    private static func fetch(source: Source) async throws -> MobileUpdate {
        var request = URLRequest(url: source.manifestURL)
        request.timeoutInterval = JukuConfig.readTimeout
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue(JukuConfig.userAgentSuffix, forHTTPHeaderField: "User-Agent")

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: request)
        } catch {
            throw UpdateCheckError.transport(error.localizedDescription)
        }

        guard let http = response as? HTTPURLResponse else {
            throw UpdateCheckError.transport("响应无法识别")
        }
        if http.statusCode == 401 || http.statusCode == 403 {
            // 服务端新版是全局鉴权：未登录时**任何**路径都返回 401（连不存在的也是），
            // 所以 401 只说明"这条路走不通"，不代表接口还在
            throw UpdateCheckError.badStatus(http.statusCode)
        }
        if http.statusCode == 404 {
            throw UpdateCheckError.notPublished
        }
        guard (200..<300).contains(http.statusCode) else {
            throw UpdateCheckError.badStatus(http.statusCode)
        }

        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw UpdateCheckError.malformed("响应不是合法 JSON")
        }

        // 服务端在未登录时会返回 {"code":"login_required", ...}，这不是错误响应码，
        // 但语义上确实取不到更新信息，需要单独提示。
        if let code = json["code"] as? String, code == "login_required" {
            throw UpdateCheckError.loginRequired
        }

        guard let versionCode = (json["versionCode"] as? NSNumber)?.intValue else {
            throw UpdateCheckError.malformed("缺少 versionCode")
        }

        let versionName = json["versionName"] as? String ?? "新版本"
        let sha256 = json["sha256"] as? String ?? ""
        let notes = json["notes"] as? String ?? "本次更新包含功能优化和问题修复。"
        let size = (json["size"] as? NSNumber)?.int64Value ?? 0

        // 下载地址解析，三种形态都要能处理：
        //   ① 绝对地址（服务端补回接口后、或将来 CI 直接写进 update.json 的就是这种）
        //   ② 相对地址 —— 只在**源的基准**下拼（`/api/mobile/apk?name=…` 是服务端约定，
        //      换成 GitHub 去拼就变成 github.com/api/mobile/apk 了，必然 404）
        //   ③ 什么都没给 —— 按该源的资产命名约定兜底
        let downloadURL = try resolveAssetURL(
            source: source,
            raw: json["ipaUrl"] as? String ?? json["apkUrl"] as? String,
            fallbackName: source.versionedAsset
                ? "juku-mobile-\(versionName)-unsigned.ipa"
                : JukuConfig.ipaRemoteName
        )

        return MobileUpdate(
            versionCode: versionCode,
            versionName: versionName,
            sha256: sha256,
            notes: notes,
            size: size,
            downloadURL: downloadURL
        )
    }

    /// 解析下载地址（见 `fetch(source:)` 里的说明）。
    static func resolveAssetURL(source: Source, raw: String?, fallbackName: String) throws -> URL {
        if let value = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !value.isEmpty {
            if value.lowercased().hasPrefix("http://") || value.lowercased().hasPrefix("https://") {
                guard let url = URL(string: value) else {
                    throw UpdateCheckError.malformed("下载地址无法解析：\(value)")
                }
                return url
            }
            if let base = source.relativeBase, let url = URL(string: value, relativeTo: base) {
                return url
            }
        }
        guard let url = URL(string: source.assetPrefix + fallbackName) else {
            throw UpdateCheckError.malformed("无法确定下载地址")
        }
        return url
    }

    /// 当前安装版本是否落后于服务端。
    static func isNewer(_ update: MobileUpdate) -> Bool {
        update.versionCode > JukuConfig.currentVersionCode
    }
}
