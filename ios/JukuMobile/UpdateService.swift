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

    static func fetch(server: String) async throws -> MobileUpdate {
        let base = ServerStore.normalize(server)
        guard let baseURL = URL(string: base),
              let url = URL(string: JukuConfig.updateAPIPath, relativeTo: baseURL) else {
            throw UpdateCheckError.malformed("服务器地址无法解析：\(base)")
        }

        var request = URLRequest(url: url)
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

        // 下载地址优先级：服务端 ipaUrl > 服务端 apkUrl > GitHub Releases
        let downloadURL: URL
        if let ipa = json["ipaUrl"] as? String, let resolved = URL(string: ipa, relativeTo: baseURL) {
            downloadURL = resolved
        } else if let apk = json["apkUrl"] as? String,
                  let resolved = URL(string: apk, relativeTo: baseURL) {
            downloadURL = resolved
        } else if let resolved = URL(string: JukuConfig.releasesURL) {
            downloadURL = resolved
        } else {
            throw UpdateCheckError.malformed("无法确定下载地址")
        }

        return MobileUpdate(
            versionCode: versionCode,
            versionName: versionName,
            sha256: sha256,
            notes: notes,
            size: size,
            downloadURL: downloadURL
        )
    }

    /// 当前安装版本是否落后于服务端。
    static func isNewer(_ update: MobileUpdate) -> Bool {
        update.versionCode > JukuConfig.currentVersionCode
    }
}
