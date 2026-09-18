#!/usr/bin/env bash
#
# 把 GitHub Release 的产物（APK + IPA + update.json + AltStore 源）发布到剧库服务器，让
#   https://duanju.sky423.cn:18888/api/mobile/apk?name=juku-mobile.apk
#   https://duanju.sky423.cn:18888/api/mobile/apk?name=juku-mobile-unsigned.ipa
# 给出的就是 GitHub 上那次的构建产物。
#
# 背景（为什么需要这个脚本）：
#   该接口只是按文件名读服务端容器内 /data/mobile/ 下的文件，**不会**去 GitHub 回源。
#   所以 GitHub 构建出新包后必须显式同步，两处才会一致。
#
# 关键安全设计：
#   1) 推送前校验 update.json 声明的 sha256/size 与 APK 实际值一致 —— 避免把
#      GitHub 的 APK 配上服务端旧的 update.json，那样客户端完整性校验必然失败；
#   2) 推送后从公网地址重新下载，逐个核对 sha256 —— 端到端确认；
#   3) 推送前把服务端现有文件备份到宿主机 /tmp（不污染发布目录）。
#
# AltStore 源：
#   顺带生成 juku-altstore.json（iOS 端在 AltStore 里添加一次这个地址，之后每次发版
#   都能在 AltStore 里看到更新并一键安装）。版本号/大小/下载地址全部由 update.json
#   与 IPA 实体推导，不手写。
#
# 用法：
#   ./publish-to-server.sh              # 同步 dist/ 里的产物（有 IPA 就一起推）
#   ./publish-to-server.sh --dry-run    # 只做本地校验，不碰服务器
#   SSH_HOST=xxx CONTAINER=yyy ./publish-to-server.sh
#
# 前置：dist/ 里要有 juku-mobile-<版本>.apk 与 update.json（先跑 fetch-artifacts.sh）。
#
# 实现上刻意只用 sed/sha256sum 等基础工具处理 JSON 字段，不依赖 python ——
# Git Bash 里的 Windows python 不认 /c/... 形式的 MSYS 路径。

set -euo pipefail

SSH_HOST="${SSH_HOST:-debian13}"
CONTAINER="${CONTAINER:-guoguo-juku-app-1}"
PUBLIC_BASE="${PUBLIC_BASE:-https://duanju.sky423.cn:18888}"
SERVER_DIR="/data/mobile"
SERVER_APK="juku-mobile.apk"
SERVER_IPA="juku-mobile-unsigned.ipa"
SERVER_SOURCE="juku-altstore.json"
SERVER_ICON="juku-icon.png"

DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,32p' "$0"; exit 0 ;;
    *) echo "未知参数: $1" >&2; exit 2 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_DIR="$SCRIPT_DIR/dist"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    echo "需要 sha256sum 或 shasum" >&2; return 1
  fi
}

# 取 JSON 里的字符串字段值（"key": "value"）。
# 正则只用 BRE 标准语法 —— 不用 GNU 扩展的 \?，否则在 BSD sed 上会失败。
json_field() {
  sed -n "s/.*\"$2\": *\"\([^\"]*\)\".*/\1/p" "$1" | head -n1
}

echo "=== 1. 定位本地产物 ==="
UPDATE_JSON="$DIST_DIR/update.json"
[ -f "$UPDATE_JSON" ] || { echo "缺少 $UPDATE_JSON，先跑 ./fetch-artifacts.sh" >&2; exit 1; }

VERSION=$(json_field "$UPDATE_JSON" versionName)
[ -n "$VERSION" ] || { echo "无法从 update.json 解析 versionName" >&2; exit 1; }

APK="$DIST_DIR/juku-mobile-${VERSION}.apk"
[ -f "$APK" ] || { echo "缺少 $APK" >&2; exit 1; }

IPA="$DIST_DIR/juku-mobile-${VERSION}-unsigned.ipa"
HAS_IPA=0
[ -f "$IPA" ] && HAS_IPA=1

REAL_SHA=$(sha256_of "$APK" | tr 'a-f' 'A-F')
REAL_SIZE=$(wc -c < "$APK" | tr -d ' ')
DECL_SHA=$(json_field "$UPDATE_JSON" sha256 | tr 'a-f' 'A-F')
DECL_SIZE=$(sed -n 's/.*"size": *\([0-9][0-9]*\).*/\1/p' "$UPDATE_JSON" | head -n1)

echo "  版本       : $VERSION (versionCode $(sed -n 's/.*"versionCode": *\([0-9][0-9]*\).*/\1/p' "$UPDATE_JSON" | head -n1))"
echo "  APK        : $(basename "$APK")  $REAL_SIZE B  sha256=$REAL_SHA"
if [ "$HAS_IPA" = "1" ]; then
  echo "  IPA        : $(basename "$IPA")  $(wc -c < "$IPA" | tr -d ' ') B  sha256=$(sha256_of "$IPA" | tr 'a-f' 'A-F')"
else
  echo "  IPA        : (dist 里没有，本次只推 Android)"
fi
echo "  JSON 声明  : $DECL_SIZE B  sha256=$DECL_SHA"

echo ""
echo "=== 2. 本地校验：update.json 必须与实际 APK 配对 ==="
FAIL=0
if [ "$DECL_SHA" = "$REAL_SHA" ]; then
  echo "  ✅ sha256 匹配"
else
  echo "  ❌ sha256 不匹配 —— 这两个文件不是同一次构建的产物"; FAIL=1
fi
if [ "$DECL_SIZE" = "$REAL_SIZE" ]; then
  echo "  ✅ size 匹配"
else
  echo "  ❌ size 不匹配"; FAIL=1
fi
if [ "$HAS_IPA" = "1" ]; then
  echo "  ✅ dist 里有 IPA，将与 APK 一起推送到 $SERVER_DIR/$SERVER_IPA"
  echo "     （iOS 端按约定路径取：api/mobile/apk?name=$SERVER_IPA）"
fi
if [ "$FAIL" != "0" ]; then
  echo ""
  echo "拒绝推送：APK 与 update.json 不配对的话，客户端完整性校验必然失败。" >&2
  echo "请重新跑 ./fetch-artifacts.sh 让两者来自同一个 Release。" >&2
  exit 1
fi

echo ""
echo "=== 3. 生成 AltStore 源 ==="
ALTSTORE_FILE="$DIST_DIR/$SERVER_SOURCE"
ICON_SRC="$SCRIPT_DIR/ios/JukuMobile/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
HAS_SOURCE=0
if [ "$HAS_IPA" != "1" ]; then
  echo "  ⏭  本次没有 IPA，跳过（AltStore 源只有 iOS 包才有意义）"
elif [ ! -f "$ICON_SRC" ]; then
  echo "  ⚠️  找不到图标 $ICON_SRC，跳过 AltStore 源生成" >&2
else
  VCODE=$(sed -n 's/.*"versionCode": *\([0-9][0-9]*\).*/\1/p' "$UPDATE_JSON" | head -n1)
  IPA_SIZE=$(wc -c < "$IPA" | tr -d ' ')
  IPA_SHA=$(sha256_of "$IPA" | tr 'a-f' 'A-F')
  # notes 里可能带引号/换行，JSON 里不能原样塞，先清掉
  NOTES_SAFE=$(json_field "$UPDATE_JSON" notes | tr -d '"\\' | tr '\n\r' '  ')
  [ -n "$NOTES_SAFE" ] || NOTES_SAFE="例行更新"
  PUBLISHED=$(json_field "$UPDATE_JSON" publishedAt)
  PUBLISHED_DATE=$(printf '%s' "$PUBLISHED" | cut -c1-10)
  case "$PUBLISHED_DATE" in
    [0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]) ;;
    *) PUBLISHED_DATE=$(date +%F) ;;
  esac
  MIN_OS=$(sed -n 's/^ *iOS: *"\([0-9][0-9.]*\)".*/\1/p' "$SCRIPT_DIR/ios/project.yml" | head -n1)
  [ -n "$MIN_OS" ] || MIN_OS="15.0"

  IPA_URL="$PUBLIC_BASE/api/mobile/apk?name=$SERVER_IPA"
  ICON_URL="$PUBLIC_BASE/api/mobile/apk?name=$SERVER_ICON"

  cat > "$ALTSTORE_FILE" <<EOF
{
  "name": "果果剧库",
  "subtitle": "自建短剧库 · 手机客户端",
  "description": "果果剧库的 iOS 客户端（未签名 IPA，AltStore 会在安装时用你的 Apple ID 重新签名）。\n\n版本 $VERSION（build $VCODE）。",
  "iconURL": "$ICON_URL",
  "website": "$PUBLIC_BASE/",
  "tintColor": "#F05A28",
  "featuredApps": [
    "com.juku.mobile"
  ],
  "apps": [
    {
      "name": "果果剧库",
      "bundleIdentifier": "com.juku.mobile",
      "developerName": "chenweitian423",
      "subtitle": "自建短剧库 · 手机客户端",
      "localizedDescription": "果果剧库 iOS 客户端。服务器地址可在应用内菜单里配置。",
      "iconURL": "$ICON_URL",
      "tintColor": "#F05A28",
      "category": "entertainment",
      "screenshots": [],
      "versions": [
        {
          "version": "$VERSION",
          "date": "$PUBLISHED_DATE",
          "localizedDescription": "$NOTES_SAFE",
          "downloadURL": "$IPA_URL",
          "size": $IPA_SIZE,
          "minOSVersion": "$MIN_OS"
        }
      ],
      "appPermissions": {
        "entitlements": [],
        "privacy": {}
      }
    }
  ],
  "news": []
}
EOF
  HAS_SOURCE=1
  echo "  已生成 $(basename "$ALTSTORE_FILE")"
  echo "    版本     : $VERSION (build $VCODE)  最低 iOS $MIN_OS"
  echo "    下载地址 : $IPA_URL"
  echo "    IPA 大小 : $IPA_SIZE B   sha256=$IPA_SHA"
  echo "    图标     : $(basename "$ICON_SRC")"
  echo "    源地址   : $PUBLIC_BASE/api/mobile/apk?name=$SERVER_SOURCE"
fi

if [ "$DRY_RUN" = "1" ]; then
  echo ""
  echo "--dry-run：本地校验通过，未接触服务器。"
  exit 0
fi

echo ""
echo "=== 4. 服务端现状（推送前）==="
ssh -o ConnectTimeout=15 "$SSH_HOST" "
  echo -n '  当前 APK sha256 : '
  docker exec $CONTAINER sha256sum $SERVER_DIR/$SERVER_APK 2>/dev/null | cut -d' ' -f1 || echo '(无)'
  echo -n '  当前 IPA sha256 : '
  docker exec $CONTAINER sha256sum $SERVER_DIR/$SERVER_IPA 2>/dev/null | cut -d' ' -f1 || echo '(无)'
  echo -n '  当前声明 sha256 : '
  docker exec $CONTAINER sh -c 'cat $SERVER_DIR/update.json' 2>/dev/null | sed -n 's/.*\"sha256\": *\"\([^\"]*\)\".*/\1/p' || echo '(无)'
  echo -n '  容器状态        : '
  docker ps --filter name=$CONTAINER --format '{{.Status}}'
"

echo ""
echo "=== 5. 上传到服务器 ==="
REMOTE_TMP="/tmp/juku-publish-$$"
ssh -o ConnectTimeout=15 "$SSH_HOST" "mkdir -p $REMOTE_TMP"
ssh -o ConnectTimeout=120 "$SSH_HOST" "cat > $REMOTE_TMP/app.apk" < "$APK"
if [ "$HAS_IPA" = "1" ]; then
  ssh -o ConnectTimeout=180 "$SSH_HOST" "cat > $REMOTE_TMP/app.ipa" < "$IPA"
fi
ssh -o ConnectTimeout=60 "$SSH_HOST" "cat > $REMOTE_TMP/update.json" < "$UPDATE_JSON"
if [ "$HAS_SOURCE" = "1" ]; then
  ssh -o ConnectTimeout=60 "$SSH_HOST" "cat > $REMOTE_TMP/altstore.json" < "$ALTSTORE_FILE"
  ssh -o ConnectTimeout=60 "$SSH_HOST" "cat > $REMOTE_TMP/icon.png" < "$ICON_SRC"
fi
echo "  已上传到 $SSH_HOST:$REMOTE_TMP"

echo ""
echo "=== 6. 备份现有文件并替换 ==="
# 用 heredoc 传给远端 bash，避免多层引号嵌套出错
ssh -o ConnectTimeout=30 "$SSH_HOST" \
  "TMP=$REMOTE_TMP CONTAINER=$CONTAINER DIR=$SERVER_DIR APK=$SERVER_APK IPA=$SERVER_IPA HAS_IPA=$HAS_IPA HAS_SOURCE=$HAS_SOURCE SRC=$SERVER_SOURCE ICON=$SERVER_ICON bash -s" <<'REMOTE'
set -euo pipefail
BK="$TMP/backup"
mkdir -p "$BK"

for f in "$APK" "$IPA" "update.json" "$SRC" "$ICON"; do
  if docker exec "$CONTAINER" test -f "$DIR/$f" 2>/dev/null; then
    docker cp "$CONTAINER:$DIR/$f" "$BK/$f.bak"
    echo "  已备份 $f"
  fi
done

docker cp "$TMP/app.apk" "$CONTAINER:$DIR/$APK"
docker cp "$TMP/update.json" "$CONTAINER:$DIR/update.json"
TARGETS="$DIR/$APK $DIR/update.json"

if [ "$HAS_IPA" = "1" ]; then
  docker cp "$TMP/app.ipa" "$CONTAINER:$DIR/$IPA"
  TARGETS="$TARGETS $DIR/$IPA"
fi
if [ "$HAS_SOURCE" = "1" ]; then
  docker cp "$TMP/altstore.json" "$CONTAINER:$DIR/$SRC"
  docker cp "$TMP/icon.png" "$CONTAINER:$DIR/$ICON"
  TARGETS="$TARGETS $DIR/$SRC $DIR/$ICON"
fi

# docker cp 进去的文件属主会变成 root，必须修回 juku，否则应用可能读不了
docker exec "$CONTAINER" chown juku:juku $TARGETS
docker exec "$CONTAINER" ls -la "$DIR"
REMOTE

echo ""
echo "=== 7. 端到端回验（从公网地址重新下载）==="
VERIFY_DIR="$DIST_DIR/.verify"
rm -rf "$VERIFY_DIR"; mkdir -p "$VERIFY_DIR"

# 注意：必须用 shell 重定向（> file）而不是 curl 的 -o。
# 本机 curl 是 Windows 原生 exe，不认 /c/... 形式的 MSYS 路径，
# 用 -o 会以 exit 23（client returned ERROR on write）失败。
curl -fsS --max-time 180 "$PUBLIC_BASE/api/mobile/apk?name=$SERVER_APK" > "$VERIFY_DIR/dl.apk"
curl -fsS --max-time 60  "$PUBLIC_BASE/api/mobile/update" > "$VERIFY_DIR/dl.json"
if [ "$HAS_IPA" = "1" ]; then
  curl -fsS --max-time 180 "$PUBLIC_BASE/api/mobile/apk?name=$SERVER_IPA" > "$VERIFY_DIR/dl.ipa"
fi
if [ "$HAS_SOURCE" = "1" ]; then
  curl -fsS --max-time 60 "$PUBLIC_BASE/api/mobile/apk?name=$SERVER_SOURCE" > "$VERIFY_DIR/dl-source.json"
  curl -fsS --max-time 60 "$PUBLIC_BASE/api/mobile/apk?name=$SERVER_ICON" > "$VERIFY_DIR/dl-icon.png"
fi

OK=1
DL_SHA=$(sha256_of "$VERIFY_DIR/dl.apk" | tr 'a-f' 'A-F')
echo "  APK: 公网 $DL_SHA"
if [ "$DL_SHA" = "$REAL_SHA" ]; then
  echo "       ✅ 与 GitHub 产物一致"
else
  echo "       ❌ 不一致"; OK=0
fi

if [ "$HAS_IPA" = "1" ]; then
  REAL_IPA_SHA=$(sha256_of "$IPA" | tr 'a-f' 'A-F')
  DL_IPA_SHA=$(sha256_of "$VERIFY_DIR/dl.ipa" | tr 'a-f' 'A-F')
  echo "  IPA: 公网 $DL_IPA_SHA"
  if [ "$DL_IPA_SHA" = "$REAL_IPA_SHA" ]; then
    echo "       ✅ 与 GitHub 产物一致"
  else
    echo "       ❌ 不一致"; OK=0
  fi
fi

DL_SHA_DECL=$(json_field "$VERIFY_DIR/dl.json" sha256 | tr 'a-f' 'A-F')
DL_VER=$(json_field "$VERIFY_DIR/dl.json" versionName)
DL_VCODE=$(sed -n 's/.*"versionCode": *\([0-9][0-9]*\).*/\1/p' "$VERIFY_DIR/dl.json" | head -n1)
echo "  update 接口: $DL_VER (code $DL_VCODE)  声明 sha256=$DL_SHA_DECL"
echo "               （服务端会重新序列化 update.json，只回传它认识的字段 ——"
echo "                 apkName/ipaUrl 这类自定义字段被丢弃属预期，不影响校验）"
if [ "$DL_SHA_DECL" = "$REAL_SHA" ]; then
  echo "       ✅ 声明的 sha256 与包一致（客户端校验能过）"
else
  echo "       ❌ 声明与包不一致（客户端校验会失败）"; OK=0
fi

if [ "$HAS_SOURCE" = "1" ]; then
  DL_SRC_VER=$(json_field "$VERIFY_DIR/dl-source.json" version)
  DL_SRC_SIZE=$(sed -n 's/.*"size": *\([0-9][0-9]*\).*/\1/p' "$VERIFY_DIR/dl-source.json" | head -n1)
  DL_ICON_SIZE=$(wc -c < "$VERIFY_DIR/dl-icon.png" | tr -d ' ')
  LOCAL_IPA_SIZE=$(wc -c < "$IPA" | tr -d ' ')
  echo "  AltStore 源: 公网解析到版本 $DL_SRC_VER  (size=$DL_SRC_SIZE)  图标 $DL_ICON_SIZE B"
  if [ "$DL_SRC_VER" = "$VERSION" ] && [ "$DL_SRC_SIZE" = "$LOCAL_IPA_SIZE" ]; then
    echo "       ✅ 版本与 IPA 大小都对得上"
  else
    echo "       ❌ AltStore 源与实际产物不一致（期望 $VERSION / $LOCAL_IPA_SIZE）"; OK=0
  fi
  if [ "$DL_ICON_SIZE" -gt 1000 ] 2>/dev/null; then
    echo "       ✅ 图标可访问"
  else
    echo "       ❌ 图标不可访问"; OK=0
  fi
fi

echo ""
echo "=== 8. 清理服务器临时文件 ==="
ssh -o ConnectTimeout=30 "$SSH_HOST" "
  rm -f $REMOTE_TMP/app.apk $REMOTE_TMP/app.ipa $REMOTE_TMP/update.json \
        $REMOTE_TMP/altstore.json $REMOTE_TMP/icon.png
  echo '  已删除上传临时文件'
  echo '  备份保留在: $REMOTE_TMP/backup'
"
rm -rf "$VERIFY_DIR"

echo ""
if [ "$OK" = "1" ]; then
  echo "✅ 发布完成。"
  echo "   Android: $PUBLIC_BASE/api/mobile/apk?name=$SERVER_APK"
  [ "$HAS_IPA" = "1" ] && echo "   iOS    : $PUBLIC_BASE/api/mobile/apk?name=$SERVER_IPA"
  if [ "$HAS_SOURCE" = "1" ]; then
    echo "   AltStore 源（在 AltStore 里添加一次）:"
    echo "            $PUBLIC_BASE/api/mobile/apk?name=$SERVER_SOURCE"
  fi
else
  echo "❌ 回验未通过，请检查上面的差异。" >&2
  exit 1
fi
