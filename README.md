# 果果剧库 手机端

自建剧库服务的跨平台客户端。Android 为原生 Java + WebView 外壳，iOS 为 Swift + WKWebView 外壳，
两端共享同一套网页前端与同一套更新协议。

## 构建产物

到 [Actions](../../actions) 页面选对应 workflow 点 **Run workflow**，构建完成后在
**该次运行的 Artifacts** 里下载，或在 [Releases](../../releases) 下载已发布版本。

| 平台 | Workflow | 产物 | Runner |
|---|---|---|---|
| Android | `Build Android APK` | `juku-mobile-<版本>.apk`（已签名） | ubuntu-latest |
| iOS | `Build iOS IPA (unsigned)` | `juku-mobile-<版本>-unsigned.ipa` | macos-15 |

Android 在推送 `main` 且改动命中 `app/**` 等路径时会自动构建；
iOS **只支持手动触发**（macOS runner 按 Linux 的 10 倍消耗免费额度）。

## 版本号

两端版本号必须一致，改动时同步这两处：

| 位置 | 字段 |
|---|---|
| `app/build.gradle` | `versionCode` / `versionName` |
| `ios/project.yml` | `CURRENT_PROJECT_VERSION` / `MARKETING_VERSION` |

iOS workflow 会在构建前校验两者一致，不一致直接失败。

## 签名

Android 的 release 包由 CI 用仓库密钥签名，密钥通过 Secrets 注入，**不入库**：

| Secret | 含义 |
|---|---|
| `JUKU_KEYSTORE_BASE64` | 钥匙文件的 base64 |
| `JUKU_KEYSTORE_PASSWORD` | 仓库口令 |
| `JUKU_KEY_ALIAS` | 密钥别名 |
| `JUKU_KEY_PASSWORD` | 密钥口令 |

CI 在出包后会断言证书指纹等于 `b23d08d3e4e21b051ecc09e8277b669d8b6090d686ef9b5d47d090e6affb079d`，
不匹配则构建失败 —— 因为签名不一致会让**已安装用户无法覆盖升级**（Android 报
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。换钥匙时这个断言和 README 都要同步更新。

本地构建不配这些环境变量也能跑通，只是产出未签名包：

```bash
# 有工具链时（.tools/ 不入库，需自备 JDK17 + Android SDK）
./gradlew :app:assembleRelease
```

## 在线更新机制

客户端启动后按 12 小时间隔、以及从后台回到前台（距上次检查超 30 分钟）时，
请求 `{服务器}/api/mobile/update`：

```json
{
  "versionCode": 12,
  "versionName": "1.3.3",
  "sha256": "45D81D9D…",
  "size": 41991,
  "notes": "更新说明",
  "apkUrl": "/api/mobile/apk"
}
```

- 服务端返回的 `versionCode` 大于本地 `CURRENT_VERSION_CODE` 才提示更新。
- 所以要推送新版本，除了发 APK 还要同步服务端 `update.json` 的
  `versionCode / versionName / sha256 / size`。

两端更新行为不同，这是平台限制而非实现差异：

| | Android | iOS |
|---|---|---|
| 检查更新 | 请求同一接口 | 请求同一接口 |
| 下载 | 应用内下载 + 断点续传 | 跳转浏览器 / GitHub Releases |
| 安装 | `ACTION_VIEW` 交给系统安装器，支持应用内直接升级 | **不允许**应用自行安装，需用 AltStore / Sideloadly / TrollStore 自签 |

签名一致时 Android 可长期走应用内覆盖升级；一旦换钥匙，老用户必须先卸载再装。

## 目录结构

```
app/                          Android 工程（Java + WebView 外壳）
  src/main/java/com/juku/mobile/
    MainActivity.java         外壳主体：WebView、菜单、更新、播放器沉浸
    ApkFileProvider.java      应用内更新包的 FileProvider
ios/                          iOS 工程（Swift + WKWebView 外壳）
  project.yml                 XcodeGen 描述，唯一事实来源
  JukuMobile/
    AppDelegate.swift         应用入口
    RootViewController.swift  外壳主体，与 MainActivity 行为对齐
    JukuConfig.swift          常量（版本号在此，与 build.gradle 同步）
    ServerStore.swift         服务器地址持久化
    UpdateService.swift       更新检查
.github/workflows/            CI：android-apk.yml / ios-ipa.yml
```

iOS 的 `project.pbxproj` 由 XcodeGen 从 `project.yml` 生成，不入库。
本地需要用 Xcode 时：

```bash
brew install xcodegen
cd ios && xcodegen generate && open JukuMobile.xcodeproj
```

## 服务器地址

首次启动连默认线上地址。内网调试从右上角菜单 **服务器地址** 修改，支持
`http://192.168.x.x:8999/` 这类明文地址（两端都已放开明文与自签证书限制）。
