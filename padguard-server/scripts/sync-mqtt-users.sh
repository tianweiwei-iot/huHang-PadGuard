#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 每设备 MQTT 凭据同步：devices 表 -> mosquitto/passwd
# ---------------------------------------------------------------------------
# 为什么需要：服务端绑定时给每台设备下发 username=deviceId + 随机密码
# (mk_<uuid>)，但 mosquitto 的 password_file 是部署时手工建的（只有 pgserver
# 等初始用户）。没有本脚本，新绑定的设备在 broker 侧根本不存在 → 连接被拒，
# MQTT 静默退化为 HTTP 轮询（功能不缺、少了实时推送，且日志难察觉）。
#
# 做什么：
#   1. 从 postgres 容器读 (id, mqtt_password)；
#   2. 用一次性 mosquitto 容器在【宿主侧】更新 ./mosquitto/passwd
#      （该文件在 compose 里是 ro 挂载，宿主可写、容器只读，语义不变）；
#   3. 向 mosquitto 容器发 SIGHUP 热加载（mosquitto 2.x 支持，无需重启服务）。
#
# 可选：
#   --purge   顺带删除 passwd 里已不在 devices 表中的设备用户（保留 pgserver
#             等非设备用户；解绑只置 user_id=null 不删行，故通常无需 purge）。
#
# 用法（部署机、padguard-server 目录下）：
#   ./scripts/sync-mqtt-users.sh            # 增量同步（幂等，可放 cron）
#   ./scripts/sync-mqtt-users.sh --purge    # 同步 + 清理已删设备
#
# 前提：docker-compose.prod.yml 已 up（postgres + mosquitto），
#       ./mosquitto/passwd 已按部署文档初始化（含 pgserver）。
#
# 注意：单租户家庭自建包（注入共享 pgdevice 凭据）**不要**用本脚本——
#       那种形态所有设备共用一个 broker 用户，无需按设备开通。
# ---------------------------------------------------------------------------
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$SERVER_DIR/docker-compose.prod.yml"
PASSWD_FILE="$SERVER_DIR/mosquitto/passwd"
MOSQUITTO_IMAGE="eclipse-mosquitto:2"   # 与 compose 保持同版本，避免哈希算法差异
KEEP_USERS="pgserver"                    # 非设备用户，purge 时保留
[ -f "$COMPOSE_FILE" ] || { echo "未找到 $COMPOSE_FILE（应在部署机 padguard-server 目录运行）" >&2; exit 1; }
[ -f "$PASSWD_FILE" ] || { echo "未找到 $PASSWD_FILE，请先按部署文档初始化（mosquitto_passwd -c ... pgserver）" >&2; exit 1; }

dc() { docker compose -f "$COMPOSE_FILE" "$@"; }

# --- 1) 读库：每设备 (deviceId, mqttPassword) ---
# -At 去表头/只打行，-F' ' 空格分隔；id 是 uuid、密码是 mk_<hex>，均不含空格。
ROWS="$(dc exec -T postgres psql -U "${PG_USER:-padguard}" -d "${PG_DB:-padguard}" \
    -At -F' ' -c "SELECT id, mqtt_password FROM devices WHERE mqtt_password <> ''")"
[ -n "$ROWS" ] || { echo "devices 表没有可同步的每设备凭据（0 行），结束。"; exit 0; }

COUNT=0
while IFS=' ' read -r DEV_ID DEV_PWD; do
    [ -n "$DEV_ID" ] && [ -n "$DEV_PWD" ] || continue
    # 宿主侧更新 passwd（-b 批处理非交互；已存在则更新，幂等）
    docker run --rm -v "$SERVER_DIR/mosquitto":/work "$MOSQUITTO_IMAGE" \
        mosquitto_passwd -b /work/passwd "$DEV_ID" "$DEV_PWD"
    COUNT=$((COUNT + 1))
done <<< "$ROWS"
echo "已同步 $COUNT 个设备用户 -> $PASSWD_FILE"

# --- 2) 可选：purge 已删设备 ---
if [ "${1:-}" = "--purge" ]; then
    # passwd 每行 "user:hash"，取用户名与库内集合做差
    LIB_USERS="$(dc exec -T postgres psql -U "${PG_USER:-padguard}" -d "${PG_DB:-padguard}" \
        -At -c "SELECT id FROM devices")"
    PURGED=0
    while IFS= read -r USER; do
        [ -n "$USER" ] || continue
        case " $KEEP_USERS " in *" $USER "*) continue ;; esac
        if ! grep -Fxq "$USER" <<< "$LIB_USERS"; then
            docker run --rm -v "$SERVER_DIR/mosquitto":/work "$MOSQUITTO_IMAGE" \
                mosquitto_passwd -D /work/passwd "$USER"
            PURGED=$((PURGED + 1))
            echo "已移除已删设备用户: $USER"
        fi
    done < <(cut -d: -f1 "$PASSWD_FILE")
    echo "purge 完成，移除 $PURGED 个"
fi

# --- 3) 热加载 ---
dc kill -s HUP mosquitto >/dev/null && echo "mosquitto 已 SIGHUP 热加载（passwd + acl 生效）"
