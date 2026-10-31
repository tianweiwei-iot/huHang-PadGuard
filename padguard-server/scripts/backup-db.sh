#!/usr/bin/env bash
# 数据库每日备份（第八轮评估运维批）。
#
# 用法：
#   bash scripts/backup-db.sh                  # 手动执行一次
#   crontab: 10 3 * * *  bash /path/to/padguard-server/scripts/backup-db.sh >> /var/log/padguard-backup.log 2>&1
#
# 行为：
#   1. pg_dump 全库压缩落盘到 backups/padguard-<时间戳>.sql.gz
#   2. 清理超过 KEEP_DAYS（默认 7 天）的旧备份
#   3. 备份文件包含全部家长/设备/订单/订阅数据——这是整套业务唯一的权威存储，
#      uploads 里的媒体文件可另行用 rsync 同步（见文件尾部注释）
#
# 恢复（务必先演练一次再上线！）：
#   docker compose -f docker-compose.prod.yml exec -T postgres \
#     psql -U <PG_USER> -d <PG_DB> < <(gunzip -c backups/padguard-xxx.sql.gz)
#   注意：恢复会覆盖现有数据，先停 app 容器避免写入竞争。
set -euo pipefail
cd "$(dirname "$0")/.."

KEEP_DAYS="${KEEP_DAYS:-7}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p backups

# .env 里的 PG_USER/PG_DB/PG_PASSWORD 供 pg_dump 认证（容器内已配置 trust 内网认证时密码可省）
if [ -f .env ]; then
    set -a; . ./.env; set +a
fi

docker compose -f docker-compose.prod.yml exec -T postgres \
    pg_dump -U "${PG_USER:-padguard}" "${PG_DB:-padguard}" \
    | gzip > "backups/padguard-${STAMP}.sql.gz"

SIZE=$(du -h "backups/padguard-${STAMP}.sql.gz" | cut -f1)
echo "[$(date '+%F %T')] backup ok: backups/padguard-${STAMP}.sql.gz (${SIZE})"

# 清理过期备份（保留 KEEP_DAYS 天）
find backups -name "padguard-*.sql.gz" -type f -mtime "+${KEEP_DAYS}" -delete
echo "[$(date '+%F %T')] pruned backups older than ${KEEP_DAYS} days"

# 提示：uploads/（截图/录屏/录音）不在本备份范围。量大（GB 级/月）建议：
#   rsync -a --delete uploads/ 备份机:/srv/padguard-uploads/
# 媒体可由客户端重新触发生成，优先级低于数据库，按需选择同步周期。
