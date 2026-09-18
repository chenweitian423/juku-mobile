#!/usr/bin/env bash
#
# 把 GitHub Release 的产物发布到剧库服务器，让
#   https://duanju.sky423.cn:18888/api/mobile/apk?name=juku-mobile.apk
# 给出的就是 GitHub 上那个包。
#
# 背景（为什么需要这个脚本）：
#   该地址读的是服务端容器内 /data/mobile/ 的文件，它**不会**去 GitHub 回源。
#   所以 GitHub 构建出新包后必须显式同步过去，两处才会一致。
#
# 关键安全设计：
#   1) 推送前校验 update.json 声明的 sha256/size 与 APK 实际值一致 —— 避免把
#      GitHub 的 APK 配上服务端旧的 update.json，那样客户端完整性校验必然失败；
#   2) 推送后从公网地址重新下载，核对 sha256 与 update.json 内容 —— 端到端确认；
#   3) 推送前把服务端现有文件备份到宿主机 /tmp（不污染发布目录）。
#
# 用法：
#   ./publish-to-server.sh              # 同步 dist/ 里的最新产物
#   ./publish-to-server.sh --dry-run    # 只做本地校验，不碰服务器
#   SSH_HOST=xxx CONTAINER=yyy ./publish-to-server.sh
#
# 前置：dist/ 里要有 juku-mobile-<版本>.apk 与 update.json（先跑 fetch-artifacts.sh）。
#
# 实现上刻意只用 sed/sha256sum 等基础工具处理 JSON 字段，不依赖 python ——
# Git Bash 里的 Windows python 不认 /c/... 形式的 MSYS 路径，会在取字段时炸掉。

set -euo pipefail

SSH_HOST="${SSH_HOST:-debian13}"
CONTAINER="${CONTAINER:-guoguo-juku-app-1}"
PUBLIC_BASE="${PUBLIC_BASE:-https://duanju.sky423.cn:18888}"
SERVER_DIR="/data/mobile"
SERVER_APK_NAME="juku-mobile.apk"

DRY_RUN=0
while [ $# -gt 0 ]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
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

REAL_SHA=$(sha256_of "$APK" | tr 'a-f' 'A-F')
REAL_SIZE=$(wc -c < "$APK" | tr -d ' ')
DECL_SHA=$(json_field "$UPDATE_JSON" sha256 | tr 'a-f' 'A-F')
DECL_SIZE=$(sed -n 's/.*"size": *\([0-9][0-9]*\).*/\1/p' "$UPDATE_JSON" | head -n1)

echo "  版本       : $VERSION (versionCode $(sed -n 's/.*"versionCode": *\([0-9][0-9]*\).*/\1/p' "$UPDATE_JSON" | head -n1))"
echo "  APK        : $(basename "$APK")"
echo "  APK 实际   : $REAL_SIZE B  sha256=$REAL_SHA"
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
if [ "$FAIL" != "0" ]; then
  echo ""
  echo "拒绝推送：APK 与 update.json 不配对的话，客户端完整性校验必然失败。" >&2
  echo "请重新跑 ./fetch-artifacts.sh 让两者来自同一个 Release。" >&2
  exit 1
fi

if [ "$DRY_RUN" = "1" ]; then
  echo ""
  echo "--dry-run：本地校验通过，未接触服务器。"
  exit 0
fi

echo ""
echo "=== 3. 服务端现状（推送前）==="
ssh -o ConnectTimeout=15 "$SSH_HOST" "
  echo -n '  当前 APK sha256 : '
  docker exec $CONTAINER sha256sum $SERVER_DIR/$SERVER_APK_NAME 2>/dev/null | cut -d' ' -f1 || echo '(读取失败)'
  echo -n '  当前声明 sha256 : '
  docker exec $CONTAINER sh -c 'cat $SERVER_DIR/update.json' 2>/dev/null | sed -n 's/.*\"sha256\": *\"\([^\"]*\)\".*/\1/p' || echo '(读取失败)'
  echo -n '  容器状态        : '
  docker ps --filter name=$CONTAINER --format '{{.Status}}'
"

echo ""
echo "=== 4. 上传到服务器 ==="
REMOTE_TMP="/tmp/juku-publish-$$"
ssh -o ConnectTimeout=15 "$SSH_HOST" "mkdir -p $REMOTE_TMP"
ssh -o ConnectTimeout=120 "$SSH_HOST" "cat > $REMOTE_TMP/app.apk" < "$APK"
ssh -o ConnectTimeout=60  "$SSH_HOST" "cat > $REMOTE_TMP/update.json" < "$UPDATE_JSON"
echo "  已上传到 $SSH_HOST:$REMOTE_TMP"

echo ""
echo "=== 5. 备份现有文件并替换 ==="
ssh -o ConnectTimeout=60 "$SSH_HOST" "
  set -euo pipefail
  BK=$REMOTE_TMP/backup
  mkdir -p \"\$BK\"
  docker cp $CONTAINER:$SERVER_DIR/$SERVER_APK_NAME \"\$BK/apk.bak\" 2>/dev/null || echo '  (无旧 APK 可备份)'
  docker cp $CONTAINER:$SERVER_DIR/update.json \"\$BK/update.json.bak\" 2>/dev/null || echo '  (无旧 update.json 可备份)'
  echo \"  备份位置: \$BK\"

  docker cp $REMOTE_TMP/app.apk $CONTAINER:$SERVER_DIR/$SERVER_APK_NAME
  docker cp $REMOTE_TMP/update.json $CONTAINER:$SERVER_DIR/update.json
  docker exec $CONTAINER chown juku:juku $SERVER_DIR/$SERVER_APK_NAME $SERVER_DIR/update.json
  echo '  替换完成，属主已修正为 juku:juku'
"

echo ""
echo "=== 6. 端到端回验（从公网地址重新下载）==="
VERIFY_DIR="$DIST_DIR/.verify"
rm -rf "$VERIFY_DIR"; mkdir -p "$VERIFY_DIR"
curl -fsS --max-time 180 "$PUBLIC_BASE/api/mobile/apk?name=$SERVER_APK_NAME" -o "$VERIFY_DIR/dl.apk"
curl -fsS --max-time 60  "$PUBLIC_BASE/api/mobile/update" -o "$VERIFY_DIR/dl.json"

DL_SHA=$(sha256_of "$VERIFY_DIR/dl.apk" | tr 'a-f' 'A-F')
DL_SIZE=$(wc -c < "$VERIFY_DIR/dl.apk" | tr -d ' ')
DL_VER=$(json_field "$VERIFY_DIR/dl.json" versionName)
DL_SHA_DECL=$(json_field "$VERIFY_DIR/dl.json" sha256 | tr 'a-f' 'A-F')

echo "  公网下载   : $DL_SIZE B  sha256=$DL_SHA"
echo "  公网接口声明: $DL_VER  sha256=$DL_SHA_DECL"
echo ""
OK=1
if [ "$DL_SHA" = "$REAL_SHA" ]; then
  echo "  ✅ 服务器给出的就是 GitHub 这个包"
else
  echo "  ❌ 与 GitHub 产物不一致"; OK=0
fi
if [ "$DL_SHA_DECL" = "$REAL_SHA" ]; then
  echo "  ✅ 公网 update 接口声明的 sha256 与包一致"
else
  echo "  ❌ update 接口声明与包不一致（客户端校验会失败）"; OK=0
fi

echo ""
echo "=== 7. 清理服务器临时文件 ==="
ssh -o ConnectTimeout=30 "$SSH_HOST" "
  rm -f $REMOTE_TMP/app.apk $REMOTE_TMP/update.json
  echo '  已删除上传临时文件'
  echo '  备份保留在: $REMOTE_TMP/backup'
"
rm -rf "$VERIFY_DIR"

echo ""
if [ "$OK" = "1" ]; then
  echo "✅ 发布完成。手机端现在从 $PUBLIC_BASE/api/mobile/apk 拿到的就是 GitHub 构建的包。"
else
  echo "❌ 回验未通过，请检查上面的差异。" >&2
  exit 1
fi
echo "   回滚：到 GitHub Release 下载目标版本重跑本脚本，或从上面备份路径恢复。"
