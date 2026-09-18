#!/usr/bin/env bash
#
# 把 GitHub Release 里的构建产物（APK / IPA）下载到本地 dist/。
#
# 为什么不用 `gh release download`：
#   它内部走 github.com，本机代理对主站返回 502，会**静默失败**
#   —— 不报错、退出码 0、目标目录却是空的，极难察觉。
#   改用 api.github.com 的 assets API 才稳。
#
# 用法：
#   ./fetch-artifacts.sh                # 取最新 Release
#   ./fetch-artifacts.sh v1.3.3         # 取指定 tag
#   ./fetch-artifacts.sh --out <dir>    # 指定下载目录（默认 ./dist）
#   ./fetch-artifacts.sh --repo o/r     # 换仓库（默认本工程）
#
# 产物落在 dist/（已在 .gitignore 中排除）。
# 同名文件会覆盖，保证拿到的是最新构建。

set -euo pipefail

REPO="chenweitian423/juku-mobile"
TAG=""
OUT_DIR=""

while [ $# -gt 0 ]; do
  case "$1" in
    --repo) [ $# -ge 2 ] || { echo "--repo 需要一个参数" >&2; exit 2; }; REPO="$2"; shift 2 ;;
    --out)  [ $# -ge 2 ] || { echo "--out 需要一个参数"  >&2; exit 2; }; OUT_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    -*) echo "未知参数: $1" >&2; exit 2 ;;
    *)  TAG="$1"; shift ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="${OUT_DIR:-$SCRIPT_DIR/dist}"
mkdir -p "$OUT_DIR"

if [ -n "$TAG" ]; then
  API="repos/$REPO/releases/tags/$TAG"
else
  API="repos/$REPO/releases/latest"
fi

echo "仓库: $REPO"
TAG_NAME=$(gh api "$API" --jq '.tag_name')
echo "版本: $TAG_NAME"
echo "输出: $OUT_DIR"
echo ""

# 列出 apk / ipa 资产（id 用于走 API 下载，name 决定落地文件名）
ASSETS=$(gh api "$API" --jq '
  .assets[]
  | select(.name | test("\\.(apk|ipa)$"; "i"))
  | "\(.id)\t\(.name)\t\(.size)"
')

if [ -z "$ASSETS" ]; then
  echo "该 Release 没有 apk / ipa 资产" >&2
  exit 1
fi

printf '%s\n' "$ASSETS" | while IFS=$'\t' read -r id name size; do
  [ -n "$id" ] || continue
  target="$OUT_DIR/$name"

  printf '下载 %-42s' "$name"
  # -H Accept: application/octet-stream 才能拿到二进制本体而不是 JSON
  gh api "repos/$REPO/releases/assets/$id" \
    -H "Accept: application/octet-stream" > "$target"

  actual=$(wc -c < "$target" | tr -d ' ')
  if [ "$actual" != "$size" ]; then
    echo "❌ 大小不符（期望 $size，实际 $actual）"
    exit 1
  fi

  if command -v sha256sum >/dev/null 2>&1; then
    sha=$(sha256sum "$target" | cut -d' ' -f1)
  elif command -v shasum >/dev/null 2>&1; then
    sha=$(shasum -a 256 "$target" | cut -d' ' -f1)
  else
    sha=$(python -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$target")
  fi

  printf ' ✅ %s B  sha256=%s\n' "$actual" "$sha"
done

echo ""
echo "=== 产物清单 ==="
for f in "$OUT_DIR"/*.apk "$OUT_DIR"/*.ipa; do
  [ -f "$f" ] || continue
  if command -v sha256sum >/dev/null 2>&1; then
    s=$(sha256sum "$f" | cut -d' ' -f1 | tr 'a-f' 'A-F')
  else
    s=$(python -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest().upper())" "$f")
  fi
  printf '%8s B  %s\n          sha256=%s\n' "$(wc -c < "$f" | tr -d ' ')" "$(basename "$f")" "$s"
done

echo ""
echo "把 APK 的 size / sha256 填进服务端 update.json 才能推送在线更新。"

# 顺带提示 Windows 原生路径，方便在资源管理器里打开
if command -v cygpath >/dev/null 2>&1; then
  echo ""
  echo "=== Windows 路径 ==="
  for f in "$OUT_DIR"/*.apk "$OUT_DIR"/*.ipa; do
    [ -f "$f" ] || continue
    cygpath -w "$f"
  done
fi
