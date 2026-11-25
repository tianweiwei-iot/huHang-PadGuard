#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 从 backup-db.sh 的快照回灌数据库（上线前必须真实演练一次）
# ---------------------------------------------------------------------------
# 安全闸门（故意做得很啰嗦）：恢复会覆盖全部业务数据，因此
#   1. 必须显式给出备份文件路径；
#   2. 必须显式 CONFIRM_DB_RESTORE=YES；
#   3. 恢复前自动停 app，退出时（含失败）自动拉起，绝不把服务留在停机状态。
#
# 备份自 2026-11-25 起由 backup-db.sh 带 --clean --if-exists 生成，可直接回灌到同名库。
# 更早的快照不带 DROP 语句，回灌前需手工清空目标库，否则会因对象已存在而失败。
#
# 用法（padguard-server 目录）：
#   CONFIRM_DB_RESTORE=YES bash scripts/restore-db.sh backups/padguard-20261125-030000.sql.gz
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$SERVER_DIR/docker-compose.prod.yml"
DUMP="${1:-}"

[ -n "$DUMP" ] || { echo "用法：CONFIRM_DB_RESTORE=YES bash scripts/restore-db.sh <backups/padguard-*.sql.gz>" >&2; exit 1; }
[ -f "$DUMP" ] || { echo "备份文件不存在：$DUMP" >&2; exit 1; }
[ "${CONFIRM_DB_RESTORE:-}" = "YES" ] || { echo "拒绝执行：请显式设置 CONFIRM_DB_RESTORE=YES 后再运行" >&2; exit 1; }
[ -f "$COMPOSE_FILE" ] || { echo "未找到 $COMPOSE_FILE（应在部署机 padguard-server 目录运行）" >&2; exit 1; }

cd "$SERVER_DIR"
if [ -f .env ]; then
    set -a; . ./.env; set +a
fi
PG_USER_VALUE="${PG_USER:-}"
PG_DB_VALUE="${PG_DB:-padguard}"
[ -n "$PG_USER_VALUE" ] || { echo "PG_USER 未设置（.env 缺失或未填）" >&2; exit 1; }

dc() { docker compose -f "$COMPOSE_FILE" "$@"; }

echo "即将用 $DUMP 覆盖数据库 $PG_DB_VALUE（用户 $PG_USER_VALUE）。停止 app…"
dc stop app
restore_app() {
    echo "恢复结束，重新启动 app…"
    dc start app
}
trap restore_app EXIT

gunzip -c "$DUMP" | dc exec -T postgres \
    psql -v ON_ERROR_STOP=1 -U "$PG_USER_VALUE" -d "$PG_DB_VALUE"
echo "数据库回灌完成。"
