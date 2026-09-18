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

## 下载产物到本地

```bash
./fetch-artifacts.sh          # 取最新 Release
./fetch-artifacts.sh v1.3.3   # 取指定版本
```

产物落在 `dist/`（已 gitignore），脚本会校验文件大小并打印完整 sha256。

> 刻意不用 `gh release download` —— 它走 `github.com`，在代理拦截该主站的环境下会
> **静默失败**（不报错、退出码 0、目标目录却是空的）。脚本改用 `api.github.com` 的 assets API。

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
- 所以要推送新版本，除了发 APK 还要同步服务端 `update.json`。
  该文件由 CI 随包生成（Release 附件），sha256/size 是机器填的，别手工搬运 —— 见下节。

### 发布新版本的操作顺序

一条命令（拉取 + 配对校验 + 推送 + 公网回验）：

```bash
./ship.sh              # 最新 Release
./ship.sh v1.3.5       # 指定版本
```

它内部就是 `fetch-artifacts.sh` + `publish-to-server.sh`，也可以分步跑。
推送脚本有三道防护：**推前**校验 `update.json` 与 APK 的 sha256/size 配对、
**推后**从公网重新下载逐个核对、**推前**把服务端旧文件备份到宿主机 `/tmp`。

手动等价步骤：
1. 改版本号（`app/build.gradle` + `ios/project.yml`，两端必须一致）
2. 提交推送等 `Build Android APK` 跑完；iOS 另跑 `Build iOS IPA`
3. 产物下载到 `dist/`：APK、IPA、`update.json`
4. `dist/juku-mobile-<版本>.apk` → 服务端 `/data/mobile/juku-mobile.apk`
5. `dist/juku-mobile-<版本>-unsigned.ipa` → `/data/mobile/juku-mobile-unsigned.ipa`
6. `dist/update.json` → `/data/mobile/update.json`

> ⚠️ **APK 的 sha256 每次构建都会变，且不可复现**：AGP 产出的 zip 条目时间戳随构建时间变化，
> 同源码重复构建（甚至设 `SOURCE_DATE_EPOCH`）也拿不到相同摘要。
> 后果是：同一 Release 资产被后续构建 `--clobber` 覆盖后，之前抄下来的 sha256 立刻失效，
> 客户端完整性校验不通过、更新装不上。
> 因此第 5 步务必用**与该 APK 同一次构建产出**的 `update.json` —— 两者在同一个 Release 里，天然配对。

两端更新行为不同，这是平台限制而非实现差异：

| | Android | iOS |
|---|---|---|
| 检查更新 | 请求同一接口 | 请求同一接口 |
| 下载 | 应用内下载 + 断点续传 | 优先服务端 IPA，回退 GitHub Releases |
| 安装 | `ACTION_VIEW` 交给系统安装器，支持应用内直接升级 | **不允许**应用自行安装，走 AltStore 源更新（见下）

iOS 的下载地址按**服务端约定**拼（`api/mobile/apk?name=juku-mobile-unsigned.ipa`），
而不是读服务端下发的字段 —— 实测服务端 `api/mobile/update` 会**重新序列化** `update.json`，
只回传它认识的字段（`apkUrl` / `versionCode` / `sha256` …），自定义字段（如 `ipaUrl`）会被丢弃。

签名一致时 Android 可长期走应用内覆盖升级；一旦换钥匙，老用户必须先卸载再装。

### iOS：用 AltStore 源更新（推荐）

iOS 不允许应用自装，能做到的「在线更新」= **AltStore 源**。发布脚本每次都会把
`juku-altstore.json` 一起推到服务器，源地址固定不变：

```
https://duanju.sky423.cn:18888/api/mobile/apk?name=juku-altstore.json
```

在手机上 **AltStore → Sources → + 粘贴上面的地址**，之后「果果剧库」就会出现在
AltStore 的 Browse 里；有新版本时点一下即可安装（AltStore 用你的 Apple ID 重签）。

源文件里的版本号 / IPA 大小 / 下载地址全部由 `update.json` 与 IPA 实体推导
（见 `publish-to-server.sh` 第 3 步），不要手写。

两点平台限制：
- 免费 Apple ID 签名的应用 **7 天过期**，需要 AltStore 配 AltServer 定期续签（同一 Wi-Fi 自动续）。
- 图标走 `api/mobile/apk?name=juku-icon.png`（就是 App 图标），AltStore 直接取用。

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
