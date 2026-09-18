#!/usr/bin/env bash
#
# 一条龙：从 GitHub Release 拉取产物 → 校验配对 → 推送到剧库服务器 → 公网回验。
# 等价于 ./fetch-artifacts.sh 然后 ./publish-to-server.sh。
#
# 用法：
#   ./ship.sh              # 最新 Release
#   ./ship.sh v1.3.5       # 指定版本
#
# 也可以分步跑（排查时更清楚）：
#   bash fetch-artifacts.sh [tag]     # 只下载到 dist/
#   bash publish-to-server.sh         # 只推送（自带配对校验与端到端回验）

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TAG="${1:-}"

# 用 bash 显式调用子脚本：Windows 上 NTFS 不保留可执行位，
# 直接按路径执行会失败（git index 里的 100755 只对 Linux 检出有意义）。
if [ -n "$TAG" ]; then
  bash "$SCRIPT_DIR/fetch-artifacts.sh" "$TAG"
else
  bash "$SCRIPT_DIR/fetch-artifacts.sh"
fi

echo ""
bash "$SCRIPT_DIR/publish-to-server.sh"
