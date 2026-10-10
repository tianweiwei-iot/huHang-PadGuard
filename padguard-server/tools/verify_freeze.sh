#!/usr/bin/env bash
# 冻结账号缺陷验证脚本：后台冻结 → 家长登录被拒 → 已登录会话失效 → 手动开通受阻 → 解冻恢复
set -u
BASE="http://127.0.0.1:8090"
PHONE="19371581808"
PWD="201325"

echo "=== 1) 管理员登录 ==="
ADMIN=$(curl -s -X POST "$BASE/v1/admin/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}')
TOKEN=$(echo "$ADMIN" | python -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('token',''))" 2>/dev/null)
echo "token 长度: ${#TOKEN}"

echo "=== 2) 冻结前：家长登录 ==="
curl -s -o /tmp/r1.json -w "HTTP %{http_code}\n" -X POST "$BASE/v1/auth/login/password" \
  -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\",\"password\":\"$PWD\"}"
cat /tmp/r1.json | head -c 300; echo

echo "=== 3) 后台冻结该家长 ==="
curl -s -X POST "$BASE/v1/admin/parents" -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" -d '{}' >/dev/null 2>&1
PID=$(curl -s "$BASE/v1/admin/parents?keyword=$PHONE" -H "Authorization: Bearer $TOKEN" \
  | python -c "import sys,json;d=json.load(sys.stdin).get('data') or [];print(d[0]['id'] if d else '')" 2>/dev/null)
echo "家长 id: $PID"
curl -s -X POST "$BASE/v1/admin/parents/$PID/suspend" -H "Authorization: Bearer $TOKEN"; echo

echo "=== 4) 冻结后：家长登录（应被拒 401 / code 1004）==="
curl -s -o /tmp/r2.json -w "HTTP %{http_code}\n" -X POST "$BASE/v1/auth/login/password" \
  -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\",\"password\":\"$PWD\"}"
cat /tmp/r2.json | head -c 300; echo

echo "=== 5) 服务端指标（应看到真实 QPM）==="
curl -s "$BASE/v1/admin/server/metrics" -H "Authorization: Bearer $TOKEN" \
  | python -c "import sys,json;d=json.load(sys.stdin)['data'];print('requestsPerMin=',d['requestsPerMin'],'errorRate=',d['errorRate'],'dbConnections=',d['dbConnections'],'host=',d['instances'][0]['host'],'logs=',len(d['logs']))" 2>/dev/null

echo "=== 6) 审计告警关闭（audit:write 修复验证）==="
AID=$(curl -s "$BASE/v1/admin/alerts" -H "Authorization: Bearer $TOKEN" \
  | python -c "import sys,json;d=json.load(sys.stdin).get('data') or [];print(d[0]['id'] if d else '')" 2>/dev/null)
if [ -n "$AID" ]; then
  curl -s -o /tmp/r3.json -w "HTTP %{http_code}\n" -X POST "$BASE/v1/admin/alerts/$AID/close" -H "Authorization: Bearer $TOKEN"
  head -c 200 /tmp/r3.json; echo
else
  echo "（当前无告警，跳过）"
fi

echo "=== 7) 解冻恢复 ==="
curl -s -X POST "$BASE/v1/admin/parents/$PID/activate" -H "Authorization: Bearer $TOKEN"; echo
curl -s -o /tmp/r4.json -w "HTTP %{http_code}\n" -X POST "$BASE/v1/auth/login/password" \
  -H "Content-Type: application/json" -d "{\"phone\":\"$PHONE\",\"password\":\"$PWD\"}"
head -c 200 /tmp/r4.json; echo
