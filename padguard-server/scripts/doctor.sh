#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# 生产部署只读体检：把"配了却静默失效"的物料/凭据缺口在起服前暴露出来
# ---------------------------------------------------------------------------
# 只读取文件与本地环境，不启停容器、不写库、不打印任何密钥明文。
# 典型用法：cd padguard-server && bash scripts/doctor.sh
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(dirname "$SCRIPT_DIR")"
cd "$SERVER_DIR"

ERRORS=0
WARNS=0
ok()   { echo "  [OK]   $1"; }
bad()  { echo "  [FAIL] $1"; ERRORS=$((ERRORS + 1)); }
warn() { echo "  [WARN] $1"; WARNS=$((WARNS + 1)); }

if [ -f .env ]; then
    set -a; . ./.env; set +a
    ok ".env 存在"
else
    bad ".env 不存在（先 cp .env.prod.example .env 并填真实值）"
fi

# 模板占位符：gen-env-secrets.sh 只替换 CHANGE_ME_*，其余留空项合法
if grep -Eq '^(PG_USER|PG_PASSWORD|PADGUARD_JWT_SECRET|PADGUARD_MQTT_PASSWORD)=CHANGE_ME' .env 2>/dev/null; then
    bad ".env 仍有 CHANGE_ME_* 占位符（启动断言会拒绝，或运维填了却进不了容器）"
else
    ok ".env 无 CHANGE_ME_* 占位符"
fi

[ "${PG_USER:-}" = "padguard" ] && bad "PG_USER=padguard 被 ProdConfigAssertion 判弱口令，容器启动即中止"
[ -n "${PADGUARD_JWT_SECRET:-}" ] || bad "PADGUARD_JWT_SECRET 为空，容器档启动即中止"
JWT_SECRET_VALUE="${PADGUARD_JWT_SECRET:-}"
[ -z "$JWT_SECRET_VALUE" ] || [ "${#JWT_SECRET_VALUE}" -ge 32 ] \
    || warn "JWT secret 长度小于 32，建议使用 openssl rand -hex 32"

# 生产 ACL 只认每设备 deviceId；共享账号会让设备 MQTT 静默退化成轮询
DEVICE_USER="$(grep -E '^PADGUARD_MQTT_DEVICE_USERNAME=' .env 2>/dev/null | head -1 | cut -d= -f2- || true)"
if [ -n "${PADGUARD_MQTT_DEVICE_USERNAME:-$DEVICE_USER}" ]; then
    bad "PADGUARD_MQTT_DEVICE_USERNAME 非空：生产 aclfile 无 pgdevice 条目，设备 MQTT 会被 ACL 拒绝"
else
    ok "设备 MQTT 使用每设备凭据（username=deviceId）"
fi

for f in nginx/certs/server.crt nginx/certs/server.key \
         mosquitto/certs/ca.crt mosquitto/certs/server.crt mosquitto/certs/server.key; do
    [ -s "$f" ] && ok "$f 存在且非空" || bad "$f 缺失/为空（公网 HTTPS/MQTT TLS 必需；自签证书仅测试）"
done

if [ -s mosquitto/passwd ] && grep -q '^pgserver:' mosquitto/passwd; then
    ok "mosquitto/passwd 含 pgserver（缺失时先跑 scripts/init-mqtt-passwd.sh）"
else
    bad "mosquitto/passwd 缺失或无 pgserver（先跑 scripts/init-mqtt-passwd.sh）"
fi

if grep -Eq '^[[:space:]]*user[[:space:]]+pgdevice([[:space:]]|$)' mosquitto/aclfile 2>/dev/null; then
    bad "生产 aclfile 出现 pgdevice 共享账号：跨家庭读写全通，必须删除"
else
    ok "生产 ACL 保持每设备隔离"
fi
grep -q 'pattern write pg/v1/+/up/%u/#' mosquitto/aclfile 2>/dev/null \
    && ok "ACL 保留上行 up/%u 规则（删掉会静默切断告警）" \
    || bad "ACL 缺 up/%u 规则"

if command -v docker >/dev/null 2>&1; then
    if docker compose -f docker-compose.prod.yml config --quiet >/dev/null 2>&1; then
        ok "docker-compose.prod.yml 语法与必填变量通过"
    else
        bad "docker compose config 失败（多为 .env 缺必填变量）"
    fi
else
    warn "本机无 docker，跳过 compose 语法检查"
fi

warn "关键状态（在线态/看屏会话/告警去重）为 JVM 本地：生产编排必须保持单副本或会话粘滞"

echo "----------------------------------------"
echo "体检结束：$ERRORS 项 FAIL，$WARNS 项 WARN"
[ "$ERRORS" -eq 0 ] || exit 1
