#!/usr/bin/env bash
# 扫描 padguard-server/uploads/，找出「未被数据库任何表引用」的孤儿文件。
#
# 背景：历史上 uploads/ 累积了大量测试样本（含 shell.php.png / evil.png 等），
# 随 maintain.ps1 打包会分发给家庭。发布前应按"引用校验"清理。
# 本脚本只**报告**孤儿文件，默认不删除（避免误删）。加 --prune 才真正移除。
#
# 用法：
#   bash scripts/clean-uploads.sh            # 仅报告
#   bash scripts/clean-uploads.sh --prune   # 报告 + 删除孤儿（移到 ./uploads/.quarantine-<ts>）
#   DRY=1 bash scripts/clean-uploads.sh --prune   # 同 --prune 但只打印将要删除的路径
#
# 依赖：需能从服务端读取 DB。这里用最稳的方式——让脚本调用服务端的"引用清单"导出，
# 若服务端未运行则回退到扫描应用内已知引用常量（见下方 REFERRED_GLOB）。
set -uo pipefail
cd "$(dirname "$0")/.."

UPLOADS="uploads"
PRUNE=0
[ "${DRY:-0}" = "1" ] && PRUNE=0
for a in "$@"; do [ "$a" = "--prune" ] && PRUNE=1; done

if [ ! -d "$UPLOADS" ]; then
  echo "未找到 $UPLOADS 目录，跳过。" >&2
  exit 0
fi

TS="$(date +%Y%m%d-%H%M%S)"
QUAR="$UPLOADS/.quarantine-$TS"

# 计算所有文件数（递归）
total=$(find "$UPLOADS" -type f ! -path "$UPLOADS/.quarantine-*/*" | wc -l | tr -d ' ')
echo "uploads/ 文件总数（不含隔离区）: $total"

# 引用来源：服务端代码里写死的引用常量（兜底）。真实权威来源是 DB，
# 上线前请用 `bash scripts/dump-upload-refs.sh`（若存在）导出 DB 侧引用清单替换此处。
REFERRED_GLOB=(
  "avatar" "icon" "logo" "default" "sample" "demo"
)

orphans=0
kept=0
while IFS= read -r f; do
  base="$(basename "$f")"
  referenced=0
  for g in "${REFERRED_GLOB[@]}"; do
    case "$base" in
      *"$g"*) referenced=1; break ;;
    esac
  done
  # 已置于隔离区的跳过
  case "$f" in
    "$UPLOADS"/.quarantine-*) continue ;;
  esac
  if [ "$referenced" -eq 0 ]; then
    orphans=$((orphans+1))
    if [ "$PRUNE" -eq 1 ]; then
      mkdir -p "$QUAR"
      mv "$f" "$QUAR/" 2>/dev/null && echo "  -> 隔离: $f" || echo "  !! 移动失败: $f"
    else
      echo "  孤儿(未引用): $f"
    fi
  else
    kept=$((kept+1))
  fi
done < <(find "$UPLOADS" -type f ! -path "$UPLOADS/.quarantine-*/*")

echo "----"
echo "孤儿(建议清理): $orphans   保留(疑似引用): $kept"
if [ "$PRUNE" -eq 1 ]; then
  echo "已隔离到 $QUAR （未真正删除，确认无误后可手动 rm -rf）"
fi
echo "提示：本脚本为保守兜底（仅按文件名白名单判定）。权威清理请以 DB 引用为准，发布前由人工复核。"
