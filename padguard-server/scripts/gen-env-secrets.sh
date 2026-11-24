#!/usr/bin/env bash
# 从 .env.prod.example 生成生产 .env，把所有 CHANGE_ME_* 占位符替换为强随机值。
#
# 用法：
#   bash scripts/gen-env-secrets.sh
#
# 行为：
#   1. 若 .env 已存在则跳过（避免覆盖既有密钥），需重生成请先备份/删除 .env
#   2. 把 CHANGE_ME_openssl_rand_hex_16 -> openssl rand -hex 16
#      CHANGE_ME_openssl_rand_hex_32 -> openssl rand -hex 32
#   3. 微信支付等留空项保持空（= 不启用该渠道），由人工后续填真实资质
#   4. .env 已 gitignore，严禁入库
set -euo pipefail
cd "$(dirname "$0")/.."

SRC=".env.prod.example"
DST=".env"

if [ -f "$DST" ]; then
    echo "已存在 $DST，跳过（如需重生成请先备份或删除现有 .env）" >&2
    exit 0
fi

if [ ! -f "$SRC" ]; then
    echo "找不到 $SRC（请在本脚本所在仓库的 padguard-server 根目录执行）" >&2
    exit 1
fi

HEX16="$(openssl rand -hex 16)"
HEX32="$(openssl rand -hex 32)"

sed -e "s/CHANGE_ME_openssl_rand_hex_16/$HEX16/g" \
    -e "s/CHANGE_ME_openssl_rand_hex_32/$HEX32/g" \
    "$SRC" > "$DST"

echo "已生成 $DST"
echo "  - PG_PASSWORD / MQTT 凭据 / PADGUARD_JWT_SECRET 已替换为强随机值"
echo "  - 微信支付等留空项保持空（= 不启用渠道），上线收费前填真实资质"
echo "  - 请确认 .env 未被提交（已 gitignore）：git status --porcelain .env"
