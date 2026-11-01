#!/usr/bin/env bash
# 微信支付配置启动自检（对应《微信支付接入操作手册》3.3 启动自检）。
# 用法：在 padguard-server 目录执行  bash scripts/check-wechat-pay.sh [容器名]
# 只读检查，不做任何变更；敏感值只显示"是否已填 + 长度"，绝不回显明文。

set -euo pipefail

SERVICE="${1:-app}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"

if ! docker compose -f "$COMPOSE_FILE" ps "$SERVICE" 2>/dev/null | grep -qi "running"; then
  echo "✗ 容器 [$SERVICE] 未在运行：docker compose -f $COMPOSE_FILE ps"
  exit 1
fi

echo "== 1. 容器内环境变量（值已脱敏）=="
VARS=(ENABLED APP_ID MCH_ID SERIAL_NO API_V3_KEY PRIVATE_KEY_PEM PRIVATE_KEY_PATH NOTIFY_URL REFUND_NOTIFY_URL PLATFORM_CERT_DIR)
get_env() {
  docker compose -f "$COMPOSE_FILE" exec -T "$SERVICE" sh -c 'echo "$'"$1"'"'
}
declare -A val
for v in "${VARS[@]}"; do
  raw="$(get_env "PADGUARD_BILLING_WECHAT_$v" || true)"
  val[$v]="$raw"
  if [ -z "$raw" ]; then
    echo "  PADGUARD_BILLING_WECHAT_$v = <空>"
  elif [ "${#raw}" -le 6 ]; then
    echo "  PADGUARD_BILLING_WECHAT_$v = ${raw:0:2}**（已填,长度${#raw}）"
  else
    echo "  PADGUARD_BILLING_WECHAT_$v = ${raw:0:4}**...**（已填,长度${#raw}）"
  fi
done

echo
echo "== 2. 私钥文件可达性 =="
if [ -n "${val[PRIVATE_KEY_PATH]}" ]; then
  if docker compose -f "$COMPOSE_FILE" exec -T "$SERVICE" sh -c \
      'test -s "$PADGUARD_BILLING_WECHAT_PRIVATE_KEY_PATH"'; then
    echo "  ✓ 私钥文件存在且非空：${val[PRIVATE_KEY_PATH]}"
  else
    echo "  ✗ 私钥文件缺失或为空：${val[PRIVATE_KEY_PATH]}（检查 secrets/ 挂载与文件权限）"
  fi
elif [ -n "${val[PRIVATE_KEY_PEM]}" ]; then
  echo "  · 使用环境变量内联私钥（PRIVATE_KEY_PEM，长度${#val[PRIVATE_KEY_PEM]}）"
else
  echo "  ✗ 未配置私钥（PATH 与 PEM 均为空）—— readyForPay 必为 false"
fi

echo
echo "== 3. 启动日志关键字 =="
LOGS="$(docker compose -f "$COMPOSE_FILE" logs --tail 300 "$SERVICE" 2>&1 || true)"
if echo "$LOGS" | grep -qiE "平台证书.*(成功|拉取|refresh)|WeChatPayVerifier"; then
  echo "$LOGS" | grep -iE "平台证书|WeChatPayVerifier" | tail -5 | sed 's/^/  /'
else
  echo "  · 近 300 行日志未见 WeChatPayVerifier 输出（可能未启用，或日志已滚动）"
fi

echo
echo "== 4. 结论 =="
if [ "${val[ENABLED]}" != "true" ]; then
  echo "→ 微信支付未启用（ENABLED != true）。开发/联调可继续用 mock 渠道；生产收费前需启用。"
elif [ -n "${val[APP_ID]}" ] && [ -n "${val[MCH_ID]}" ] && [ -n "${val[SERIAL_NO]}" ] \
     && [ -n "${val[API_V3_KEY]}" ] && { [ -n "${val[PRIVATE_KEY_PEM]}" ] || [ -n "${val[PRIVATE_KEY_PATH]}" ]; } \
     && [ -n "${val[NOTIFY_URL]}" ]; then
  echo "→ ✓ readyForPay 要素齐全（enabled/appId/mchId/serialNo/apiV3Key/私钥/notifyUrl）。"
  echo "  请再确认：notify-url 与商户平台「支付配置」一致且公网 HTTPS 可达；"
  echo "  平台证书自动拉取成功（见第 3 节日志）；mock-pay-enabled 保持 false。"
else
  echo "→ ✗ 已启用但要素不全（appId/mchId/serialNo/apiV3Key/私钥/notifyUrl 有缺），下单会 fail-closed 拒绝。"
  exit 2
fi
