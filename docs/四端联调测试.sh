#!/usr/bin/env bash
# =============================================================================
# PadGuard 四端联调测试脚本
#   服务端 / 家长端 / 孩子端 / 运营管理后台
#
# 用法：
#   1. 先双击  padguard-server/start-server.bat  启动服务端并保持窗口打开
#   2. 等约 8 秒（日志出现 "Tomcat started on port 8090"）
#   3. 在 Git Bash 里执行：  bash docs/四端联调测试.sh
#
# 注意：
#   - 所有 curl 都带 --noproxy '*'：本机环境有 http_proxy，
#     不加会让访问 127.0.0.1 也走代理并返回 502，看起来像服务端挂了。
#   - 期望值写在每步的注释里，输出与期望不符即为不通过。
# =============================================================================

BASE="http://127.0.0.1:8090/v1"
PHONE="${PHONE:-13900000001}"     # 换一个未注册过的手机号再跑一次
PWD="${PWD:-test123456}"
ADMIN_USER="${ADMIN_USER:-admin}" # 运营后台账号，按实际部署填写
ADMIN_PWD="${ADMIN_PWD:-请填写}"  # 运营后台密码

step() { echo; echo "========== $1 =========="; }

# -----------------------------------------------------------------------------
step "0. 服务端存活（期望 401：无 token 被拒即说明服务在跑）"
curl -s -o /dev/null -w "  状态码=%{http_code}\n" --noproxy '*' \
  -X POST "$BASE/policies/probe/unlock" --max-time 8

# -----------------------------------------------------------------------------
step "1. 家长注册（手机号 + 密码）  期望 0/成功，返回 token"
REG=$(curl -s --noproxy '*' -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PWD\",\"nickname\":\"联调家长\"}" --max-time 10)
echo "$REG"
TOKEN=$(echo "$REG" | grep -oE '"token":"[^"]+"' | head -1 | cut -d'"' -f4)
echo "  取到 token 长度=${#TOKEN}"

if [ -z "$TOKEN" ]; then echo "  !! 注册未取到 token，后续步骤会失败"; fi

# -----------------------------------------------------------------------------
step "2. 注册即送免费试用  期望 status=TRIAL、plan=TRIAL、剩余约 1 天"
curl -s --noproxy '*' "$BASE/billing/subscription" \
  -H "Authorization: Bearer $TOKEN" --max-time 10
echo

# -----------------------------------------------------------------------------
step "3. 可购买套餐  期望 月卡/季卡/年卡 三档，金额单位为分"
curl -s --noproxy '*' "$BASE/billing/plans" \
  -H "Authorization: Bearer $TOKEN" --max-time 10
echo

# -----------------------------------------------------------------------------
step "4. 支付渠道  期望 支付宝/微信/云闪付/银行卡"
curl -s --noproxy '*' "$BASE/billing/channels" \
  -H "Authorization: Bearer $TOKEN" --max-time 10
echo

# -----------------------------------------------------------------------------
step "5. 生成设备绑定码（家长端）  期望返回 6 位码"
CODE=$(curl -s --noproxy '*' -X POST "$BASE/devices/bind-code" \
  -H "Authorization: Bearer $TOKEN" --max-time 10)
echo "$CODE"
# 孩子端拿到这 6 位码后，在孩子端 App 输入即完成绑定。
# 绑定受订阅闸门控制：试用期内可绑（trial-devices 默认 3 台），过期后会被拒绝。

# -----------------------------------------------------------------------------
step "6. 重复注册  期望被拒：手机号已注册"
curl -s --noproxy '*' -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$PHONE\",\"password\":\"$PWD\"}" --max-time 10
echo

step "7. 弱密码注册  期望被拒（密码至少 6 位）"
curl -s --noproxy '*' -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13900000009","password":"123"}' --max-time 10
echo

step "8. 非法手机号注册  期望被拒（手机号格式不正确）"
curl -s --noproxy '*' -X POST "$BASE/auth/register" \
  -H 'Content-Type: application/json' \
  -d '{"phone":"12345","password":"test123456"}' --max-time 10
echo

# -----------------------------------------------------------------------------
step "9. 下单（月卡）  期望返回 orderNo"
ORDER=$(curl -s --noproxy '*' -X POST "$BASE/billing/orders" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"plan":"MONTHLY","channel":"WECHAT"}' --max-time 10)
echo "$ORDER"
ORDERNO=$(echo "$ORDER" | grep -oE '"orderNo":"[^"]+"' | head -1 | cut -d'"' -f4)

# -----------------------------------------------------------------------------
step "10. 模拟支付  期望支付后订阅转 ACTIVE"
if [ -n "$ORDERNO" ]; then
  curl -s --noproxy '*' -X POST "$BASE/billing/orders/$ORDERNO/mock-pay" \
    -H "Authorization: Bearer $TOKEN" --max-time 10
  echo
else
  echo "  !! 未取到 orderNo，跳过"
fi

step "11. 支付后订阅状态  期望 status=ACTIVE"
curl -s --noproxy '*' "$BASE/billing/subscription" \
  -H "Authorization: Bearer $TOKEN" --max-time 10
echo

# -----------------------------------------------------------------------------
step "12. 运营管理后台登录"
echo "  （请把脚本顶部的 ADMIN_USER / ADMIN_PWD 改成实际部署的后台账号）"
curl -s --noproxy '*' -X POST "$BASE/admin/login" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PWD\"}" --max-time 10
echo

# -----------------------------------------------------------------------------
step "13. 试用期过期验证（人工）"
cat <<'TIP'
  自动脚本不会真的等 1 天，用下面任一方式验证"过期后需付费"：
  a) 后台「系统设置」把试用天数改成 0 或 1 分钟后，重新注册一个新手机号观察；
  b) 直接用 H2 控制台把该账号 subscription 行的 expired_at 改成过去时间；
  c) 等待真实到期。
  然后重复步骤 2 查订阅状态，期望 status=EXPIRED；
  此时家长端调用业务接口应被订阅闸门拦截（提示需续费），
  且「我的 → 订阅」页应显示"订阅已到期，续费后恢复使用"。
TIP

echo
echo "========== 联调脚本执行完毕 =========="
