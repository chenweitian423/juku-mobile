#!/usr/bin/env bash
#
# 把 GitHub Release 里的构建产物（APK / IPA / update.json）下载到本地 dist/。
#
# 为什么不用 `gh release download`：
#   它内部走 github.com，本机代理对主站返回 502，会**静默失败**
#   —— 不报错、退出码 0、目标目录却是空的，极难察觉。
#
# 为什么还要回退机制：
#   assets API 虽然走 api.github.com，但会 302 跳转到
#   release-assets.githubusercontent.com，该域名可能 TLS 握手超时。
#   因此：先重试 API，再回退 gh-proxy（第三方加速，本机实测可用）。
#
# 用法：
#   ./fetch-artifacts.sh                # 取最新 Release
#   ./fetch-artifacts.sh v1.3.3         # 取指定 tag
#   ./fetch-artifacts.sh --out <dir>    # 指定下载目录（默认 ./dist）
#   ./fetch-artifacts.sh --repo o/r     # 换仓库（默认本工程）
#
# 产物落在 dist/（已在 .gitignore 中排除）。同名文件会覆盖，保证拿到最新构建。

set -euo pipefail

REPO="chenweitian423/juku-mobile"
TAG=""
OUT_DIR=""
RETRY=3
GH_PROXY="${GH_PROXY:-https://gh-proxy.com}"

while [ $# -gt 0 ]; do
  case "$1" in
    --repo)  [ $# -ge 2 ] || { echo "--repo 需要一个参数"  >&2; exit 2; }; REPO="$2"; shift 2 ;;
    --out)   [ $# -ge 2 ] || { echo "--out 需要一个参数"   >&2; exit 2; }; OUT_DIR="$2"; shift 2 ;;
    --retry) [ $# -ge 2 ] || { echo "--retry 需要一个参数" >&2; exit 2; }; RETRY="$2"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
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

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d' ' -f1
  else
    python -c "import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],'rb').read()).hexdigest())" "$1"
  fi
}

# 下载单个资产：先重试 assets API，再用 gh-proxy 走标准下载 URL。
# 每次都校验字节数，避免把半截文件当成成功。
download_asset() {
  local id="$1" name="$2" size="$3" out="$4" tag="$5"
  local attempt got

  for attempt in $(seq 1 "$RETRY"); do
    if gh api "repos/$REPO/releases/assets/$id" \
         -H "Accept: application/octet-stream" > "$out" 2>/dev/null; then
      got=$(wc -c < "$out" | tr -d ' ')
      [ "$got" = "$size" ] && return 0
    fi
    sleep 2
  done

  echo ""
  printf '    api 通道失败，回退 %s\n' "$GH_PROXY"
  local url="https://github.com/$REPO/releases/download/$tag/$name"
  for attempt in $(seq 1 "$RETRY"); do
    # 用 shell 重定向而不是 curl 的 -o：本机 curl 是 Windows 原生 exe，
    # 不认 /c/... 形式的 MSYS 路径，用 -o 会 exit 23。
    if curl -fsSL --max-time 300 "$GH_PROXY/$url" > "$out" 2>/dev/null; then
      got=$(wc -c < "$out" | tr -d ' ')
      [ "$got" = "$size" ] && return 0
    fi
    sleep 2
  done

  echo "    ❌ 两种通道都失败: $name" >&2
  return 1
}

echo "仓库: $REPO"
TAG_NAME=$(gh api "$API" --jq '.tag_name')
echo "版本: $TAG_NAME"
echo "输出: $OUT_DIR"
echo ""

ASSETS=$(gh api "$API" --jq '
  .assets[]
  | select(.name | test("\\.(apk|ipa|json)$"; "i"))
  | "\(.id)\t\(.name)\t\(.size)"
')

if [ -z "$ASSETS" ]; then
  echo "该 Release 没有 apk / ipa / json 资产" >&2
  exit 1
fi

# 用 herestring 而非管道：管道会让 while 落到子 shell，
# 内部的 exit 1 无法让脚本失败（set -e 感知不到）。
FAIL=0
while IFS=$'\t' read -r id name size; do
  [ -n "$id" ] || continue
  target="$OUT_DIR/$name"

  printf '下载 %-44s' "$name"
  if download_asset "$id" "$name" "$size" "$target" "$TAG_NAME"; then
    printf ' ✅ %s B\n' "$size"
  else
    FAIL=1
  fi
done <<< "$ASSETS"

echo ""
echo "=== 产物清单 ==="
for f in "$OUT_DIR"/*.apk "$OUT_DIR"/*.ipa "$OUT_DIR"/*.json; do
  [ -f "$f" ] || continue
  printf '%8s B  %s\n          sha256=%s\n' \
    "$(wc -c < "$f" | tr -d ' ')" \
    "$(basename "$f")" \
    "$(sha256_of "$f" | tr 'a-f' 'A-F')"
done

if command -v cygpath >/dev/null 2>&1; then
  echo ""
  echo "=== Windows 路径 ==="
  for f in "$OUT_DIR"/*.apk "$OUT_DIR"/*.ipa "$OUT_DIR"/*.json; do
    [ -f "$f" ] || continue
    cygpath -w "$f"
  done
fi

if [ "$FAIL" != "0" ]; then
  echo ""
  echo "⚠️  有资产未能下载，重跑本脚本即可（会覆盖已下好的文件）。" >&2
  exit 1
fi
