#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 初始化 mosquitto/passwd（生产 broker 的 pgserver 服务端账号）
# ---------------------------------------------------------------------------
# 为什么要单独一步：docker-compose.prod.yml 把 ./mosquitto/passwd 只读挂载进容器，
# 但仓库不提供该文件（已 gitignore）。漏了这步，mosquitto 启动即拒绝所有认证，
# 服务端与设备全部连不上，且日志里只有 "Connection refused: not authorised"。
#
# 设备用户不要在本脚本里建：绑定后由 sync-mqtt-users.sh 从 devices 表同步，
# ACL 只认 username=deviceId，共享 pgdevice 账号在生产形态下是禁止的。
#
# 用法（padguard-server 目录）：
#   bash scripts/init-mqtt-passwd.sh                 # 首次初始化，文件已存在则拒绝覆盖
#   FORCE=1 bash scripts/init-mqtt-passwd.sh         # 明确轮换 pgserver 口令时才覆盖
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(dirname "$SCRIPT_DIR")"
PASSWD_FILE="$SERVER_DIR/mosquitto/passwd"
MOSQUITTO_IMAGE="eclipse-mosquitto:2"   # 与 compose 同版本，避免哈希算法差异

cd "$SERVER_DIR"
if [ -f .env ]; then
    set -a; . ./.env; set +a
fi

PWD_VALUE="${PADGUARD_MQTT_PASSWORD:-}"
[ -n "$PWD_VALUE" ] || { echo "PADGUARD_MQTT_PASSWORD 未设置（先在 .env 填 pgserver 口令）" >&2; exit 1; }

if [ -e "$PASSWD_FILE" ] && [ "${FORCE:-}" != "1" ]; then
    echo "已存在 $PASSWD_FILE；为避免抹掉每设备用户，默认不覆盖。确需轮换请 FORCE=1 重跑，随后执行 sync-mqtt-users.sh。" >&2
    exit 1
fi

mkdir -p "$(dirname "$PASSWD_FILE")"
docker run --rm -v "$SERVER_DIR/mosquitto":/work "$MOSQUITTO_IMAGE" \
    mosquitto_passwd -c -b /work/passwd pgserver "$PWD_VALUE"
chmod 600 "$PASSWD_FILE"
echo "已初始化 $PASSWD_FILE（仅 pgserver）。下一步：启动 compose 后执行 scripts/sync-mqtt-users.sh 同步每设备账号。"
