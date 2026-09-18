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

客户端代码里**不再硬编码版本号**，运行时读包自身的元数据（Android 读 `PackageManager`，
iOS 读 `Info.plist`），所以上面两处就是唯一事实来源 —— 不会出现「改了 gradle 忘了改 Java」
导致缓存清理判据、更新比较、UA 上报全都按旧版本号静默走偏的情况。

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

客户端启动后按 12 小时间隔、以及从后台回到前台（距上次检查超 30 分钟）时取更新信息。

### 更新通道：多级回退（1.3.17 起）

**更新通道刻意不绑在业务服务上。** 曾经只认 `{服务器}/api/mobile/update`，结果上游新版
把这组接口整组下线（连 `publish-mobile-update.sh` 一起删），手机端的检查更新/下载全断，
而且**存量已装版本无法自救**（旧客户端只会调那个接口，发新版客户端它也下载不到）。
现在按顺序尝试，第一个成功的即采用：

| 顺序 | 源 | 地址 | 说明 |
|---|---|---|---|
| ① | 自定义源 | 菜单「更新源地址」里填的地址 | 留空则不参与；可填目录（以 `/` 结尾，会取该目录下的 `update.json`）或完整 `update.json` 地址 |
| ② | 服务器 | `{服务器}/api/mobile/update` | 服务端若恢复该接口就自动用上，无需改客户端 |
| ③ | GitHub Release | `https://github.com/chenweitian423/juku-mobile/releases/latest/download/update.json` | **不需要 API、不需要登录**，CI 每次发版都发这个附件，天生存在 |

- 上次成功的源会被记住并优先尝试，避免每次从失效的源开始等超时。
- 下载地址解析支持三种形态：**绝对地址**直接用；**相对地址**只在「源的基准」下拼
  （`/api/mobile/apk?name=…` 是服务端约定，拿去 GitHub 拼必然 404）；什么都没给时按该源
  的资产命名约定兜底（GitHub 是 `juku-mobile-<版本>.apk`）。
- 401/403 会被明确识别为「该更新源需要登录」——服务端新版是**全局鉴权**，未登录时
  任何路径都返回 401（包括不存在的路径），所以**别用状态码判断接口在不在**，用二进制 grep。
- 「⋯ → 更新源地址」可随时切换/清空；「关于」页会显示当前生效的源与下载直链。

```json
{
  "versionCode": 26,
  "versionName": "1.3.17",
  "sha256": "45D81D9D…",
  "size": 41991,
  "notes": "更新说明",
  "apkUrl": "https://github.com/…/releases/latest/download/juku-mobile-1.3.17.apk",
  "ipaUrl": "https://github.com/…/releases/latest/download/juku-mobile-1.3.17-unsigned.ipa"
}
```

- 返回的 `versionCode` 大于本地版本才提示更新。
- CI 会把 `apkUrl`/`ipaUrl` 写成 **GitHub 绝对地址**；服务端那份被服务端 handler 重新序列化
  时会换成它自己的相对地址，客户端两边都能正确解析。

### 发布新版本的操作顺序

**主要通道是 GitHub Release**（CI 出包即完成发布，客户端走上面的 ③）：

1. 改版本号（`app/build.gradle` + `ios/project.yml`，两端必须一致）
2. 提交推送等 `Build Android APK` 跑完；iOS 另跑 `Build iOS IPA`
3. 完事 —— 客户端下次检查就会看到新版本

**服务端镜像（可选）**：只有当你**希望服务端也提供一份**（例如国内直连更快）时才需要。

```bash
./fetch-artifacts.sh   # 只拉产物到 dist/
./ship.sh              # 额外把产物推到服务端 /data/mobile/ 并做公网回验
```

> ⚠️ **服务端接口一旦不存在（如上游新版删掉了 `/api/mobile/*`），`ship.sh` 推过去也没用** ——
> 文件在盘上但 HTTP 取不到。此时客户端会自动回退到 GitHub 源，无需任何改动。

> ⚠️ **APK 的 sha256 每次构建都会变，且不可复现**：AGP 产出的 zip 条目时间戳随构建时间变化，
> 同源码重复构建（甚至设 `SOURCE_DATE_EPOCH`）也拿不到相同摘要。
> 所以推服务端时必须用**与该 APK 同一次构建产出**的 `update.json`（同一个 Release 里，天然配对），
> 推送脚本的推前配对校验就是拦这个。


两端更新行为不同，这是平台限制而非实现差异：

| | Android | iOS |
|---|---|---|
| 检查更新 | 请求同一接口 | 请求同一接口 |
| 下载 | 应用内下载 + 断点续传 | 优先服务端 IPA，回退 GitHub Releases |
| 安装 | `ACTION_VIEW` 交给系统安装器，支持应用内直接升级 | **不允许**应用自行安装，下载 IPA 后用签名工具导入 |

iOS 的下载地址按**服务端约定**拼（`api/mobile/apk?name=juku-mobile-unsigned.ipa`），
而不是读服务端下发的字段 —— 实测服务端 `api/mobile/update` 会**重新序列化** `update.json`，
只回传它认识的字段（`apkUrl` / `versionCode` / `sha256` …），自定义字段（如 `ipaUrl`）会被丢弃。

签名一致时 Android 可长期走应用内覆盖升级；一旦换钥匙，老用户必须先卸载再装。

### Android 装包前的三道预检

下载完成后并不是直接把包丢给系统安装器，先过三关（任一不过就停下并说明原因）：

| 顺序 | 检查 | 不过时的处理 |
|---|---|---|
| 1 | 文件可读、是合法 APK（`PK` 头）、包名一致 | 提示「更新包有问题：…」 |
| 2 | 实物包的 `versionCode` == 服务端 `update.json` 声明的值 | 提示「更新包与版本信息不一致」，**中止安装** |
| 3 | 新包签名 == 已安装版本签名 | 给出「无法覆盖安装」指引（签名冲突换安装器也绕不过） |

第 2 关专门防服务端「只换了 APK 或只换了 update.json」的错配
（APK 的 sha256 每次构建都变，手工抄必然对不上，见上文警告）。

另外，安装结果的广播会区分三类状态，不能混为一谈：

| 状态 | 含义 | 处理 |
|---|---|---|
| `STATUS_SUCCESS` | 装好了 | 清缓存与「下载」目录副本 |
| `STATUS_FAILURE_ABORTED` / `BLOCKED` | 用户点了取消 / 被设备策略挡下 | 提示「已取消安装」，**就此停下** |
| 其它 `STATUS_FAILURE` | 真失败 | 本地预检 → 换 `ACTION_VIEW` 系统安装器重试 |

第二类必须停下：否则用户刚点完「取消」，安装界面立刻又弹一次，体感像卡死循环。

### iOS 的「更新」

iOS 不允许应用自装，所以应用内只能做到**检查 + 下载 IPA**：
`https://duanju.sky423.cn:18888/api/mobile/apk?name=juku-mobile-unsigned.ipa`

拿到 IPA 后用手机上的签名工具导入安装即可（AltStore / Sideloadly / TrollStore /
手机端签名工具，任选）。做过 AltStore 源（源 JSON 自动生成并发布），
但实际使用下来不如本地签名工具直接，已下线并移除相关代码。

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
