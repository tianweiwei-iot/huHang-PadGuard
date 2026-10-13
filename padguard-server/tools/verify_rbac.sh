#!/usr/bin/env bash
# RBAC 角色与权限矩阵 —— 端到端验证脚本
#
# 验证目标（对应本轮修复的四项核心缺陷）：
#   1. 权限元数据由服务端下发（前端不再硬编码）
#   2. 内置角色（非 super）可被编辑 —— 早期 isSystem 一刀切导致矩阵全灰
#   3. read 类权限真正参与服务端校验 —— 早期全系统无任何一处校验 read
#   4. 保存前置校验（有写无读被拒） + 系统锁定角色不可编辑 + 还原默认
#
# 用法：bash tools/verify_rbac.sh
set -uo pipefail
BASE="${BASE:-http://127.0.0.1:8090}"
OUT="$(cd "$(dirname "$0")" && pwd)/out"
mkdir -p "$OUT"
PASS=0; FAIL=0

py() { python -c "$1" 2>/dev/null; }

TOKEN=$(curl -s -X POST "$BASE/v1/admin/login" -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | py "import sys,json;print(json.load(sys.stdin)['data']['token'])")
if [ -z "${TOKEN:-}" ]; then echo "无法登录 admin，脚本终止"; exit 1; fi
AUTH=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

check() { # check <名称> <期望正则> <实际>
  if echo "$3" | grep -qE "$2"; then echo "  [PASS] $1"; PASS=$((PASS+1));
  else echo "  [FAIL] $1  （期望匹配 /$2/，实际：$3）"; FAIL=$((FAIL+1)); fi
}

echo "=== 1. 权限元数据端点 ==="
SCHEMA=$(curl -s "${AUTH[@]}" "$BASE/v1/admin/rbac/permissions")
check "端点可用且含 areas" '"areas"' "$SCHEMA"
check "下发系统锁定角色 super" '"lockedRoles"' "$SCHEMA"
echo "$SCHEMA" > "$OUT/schema.json"
printf '  领域清单：'
echo "$SCHEMA" | python -c "
import sys,json
d=json.load(sys.stdin)['data']
print('、'.join(a['name']+'('+str(len(a['actions']))+'动作)' for a in d['areas']))
print('  锁定角色：', d['lockedRoles'])
"

echo
echo "=== 2. 内置角色可编辑（早期 isSystem 一刀切全灰）==="
# 给「客服」加 parents:write（内置角色 cs，非系统锁定）
ROLES=$(curl -s "${AUTH[@]}" "$BASE/v1/admin/rbac/roles")
echo "$ROLES" > "$OUT/roles.json"
CS_BEFORE=$(echo "$ROLES" | python -c "
import sys,json
r=[x for x in json.load(sys.stdin)['data'] if x['id']=='cs'][0]
print(','.join(sorted(r['privileges'].get('parents',[]))))
")
echo "  客服 parents 权限（调整前）：$CS_BEFORE"
NEW=$(echo "$ROLES" | python -c "
import sys,json
r=[x for x in json.load(sys.stdin)['data'] if x['id']=='cs'][0]
p=r['privileges']; p['parents']=sorted(set(p.get('parents',[])+['write']))
print(json.dumps({'id':'cs','privileges':p}))
")
RES=$(curl -s -X PUT "${AUTH[@]}" "$BASE/v1/admin/rbac/roles" -d "$NEW")
check "内置角色保存成功" '"ok"[[:space:]]*:[[:space:]]*true' "$RES"
ROLES2=$(curl -s "${AUTH[@]}" "$BASE/v1/admin/rbac/roles")
CS_AFTER=$(echo "$ROLES2" | python -c "
import sys,json
r=[x for x in json.load(sys.stdin)['data'] if x['id']=='cs'][0]
print(','.join(sorted(r['privileges'].get('parents',[]))))
")
echo "  客服 parents 权限（调整后）：$CS_AFTER"
check "write 已写入并生效" 'write' "$CS_AFTER"
check "角色标记为已调整 overridden" "[Tt]rue" "$(echo "$ROLES2" | python -c "
import sys,json
print([x['overridden'] for x in json.load(sys.stdin)['data'] if x['id']=='cs'][0])
")"

echo
echo "=== 3. 系统锁定角色不可编辑 ==="
SUPER=$(echo "$ROLES" | python -c "
import sys,json
r=[x for x in json.load(sys.stdin)['data'] if x['id']=='super'][0]
print(json.dumps({'id':'super','privileges':{'parents':['read']}}))
")
CODE=$(curl -s -o "$OUT/super.txt" -w '%{http_code}' -X PUT "${AUTH[@]}" "$BASE/v1/admin/rbac/roles" -d "$SUPER")
check "编辑 super 被拒（HTTP 403）" '403' "$CODE"
echo "  服务端返回：$(head -c 160 "$OUT/super.txt")"

echo
echo "=== 4. 有写无读的非法组合被前置拦截 ==="
BAD='{"id":"finance","privileges":{"parents":["write"]}}'
CODE2=$(curl -s -o "$OUT/bad.txt" -w '%{http_code}' -X PUT "${AUTH[@]}" "$BASE/v1/admin/rbac/roles" -d "$BAD")
echo "  HTTP $CODE2  $(head -c 200 "$OUT/bad.txt")"
if [ "$CODE2" != "200" ]; then echo "  [PASS] 非法组合被拒绝"; PASS=$((PASS+1)); else echo "  [FAIL] 非法组合竟然被接受"; FAIL=$((FAIL+1)); fi

echo
echo "=== 5. read 权限真正参与服务端校验（决定性证据）==="
# 建一个只给 billing:read、完全不给 parents 的角色，并指派给新管理员
curl -s -X POST "${AUTH[@]}" "$BASE/v1/admin/rbac/roles" -d \
  '{"id":"role_e2e_probe","name":"E2E只读探针","description":"验证read闸门","privileges":{"billing":["read"]}}' > /dev/null
TAIL=$((RANDOM))
CRE=$(curl -s -X POST "${AUTH[@]}" "$BASE/v1/admin/rbac/admins" -d \
  "{\"username\":\"probe$TAIL\",\"name\":\"探针\",\"roleId\":\"role_e2e_probe\"}")
echo "$CRE" > "$OUT/create_admin.json"
echo "  新建管理员：$(head -c 200 "$OUT/create_admin.json")"
PW=$(echo "$CRE" | python -c "import sys,json;d=json.load(sys.stdin).get('data') or {};print(d.get('password') or '')" 2>/dev/null)
PT=""
if [ -n "$PW" ]; then
  PT=$(curl -s -X POST "$BASE/v1/admin/login" -H "Content-Type: application/json" \
    -d "{\"username\":\"probe$TAIL\",\"password\":\"$PW\"}" | python -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
fi
if [ -z "${PT:-}" ]; then echo "  [FAIL] 探针管理员登录失败，无法验证 read 闸门"; FAIL=$((FAIL+1));
else
  C1=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $PT" "$BASE/v1/admin/parents")
  check "无 parents:read → 家长列表被拒（403）" '403' "$C1"
  C2=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $PT" "$BASE/v1/admin/billing/orders")
  check "有 billing:read → 订单列表放行（200）" '200' "$C2"
  C3=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $PT" "$BASE/v1/admin/settings")
  check "无 settings:read → 系统设置被拒（403）" '403' "$C3"
  C4=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $PT" "$BASE/v1/admin/dashboard")
  check "仪表盘对所有已登录管理员开放（200）" '200' "$C4"
  # 清理：删除探针管理员与角色
  AID=$(curl -s "${AUTH[@]}" "$BASE/v1/admin/rbac/admins" | python -c "
import sys,json
print([x['id'] for x in json.load(sys.stdin)['data'] if x['username']=='probe$TAIL'][0])
" 2>/dev/null)
  [ -n "${AID:-}" ] && curl -s -X DELETE "${AUTH[@]}" "$BASE/v1/admin/rbac/admins/$AID" > /dev/null
fi

echo
echo "=== 6. 还原默认权限 ==="
# cs 已被改过（overridden=true），还原后应回到只有 read
RESET=$(curl -s -X DELETE "${AUTH[@]}" "$BASE/v1/admin/rbac/roles/cs/privileges")
check "还原接口成功" '"ok"[[:space:]]*:[[:space:]]*true' "$RESET"
CS_RESET=$(curl -s "${AUTH[@]}" "$BASE/v1/admin/rbac/roles" | python -c "
import sys,json
r=[x for x in json.load(sys.stdin)['data'] if x['id']=='cs'][0]
print(','.join(sorted(r['privileges'].get('parents',[]))), '| overridden=', r['overridden'])
")
echo "  客服 parents 权限（还原后）：$CS_RESET"
check "write 已被清除" '^read' "$CS_RESET"
check "overridden 标记复位" 'overridden= False' "$CS_RESET"

# 清理临时角色
curl -s -X DELETE "${AUTH[@]}" "$BASE/v1/admin/rbac/roles/role_e2e_probe" > /dev/null

echo
echo "=== 7. 角色成员计数 ==="
curl -s "${AUTH[@]}" "$BASE/v1/admin/rbac/roles" | python -c "
import sys,json
for r in json.load(sys.stdin)['data']:
    print('  %-14s 成员=%-3d 权限项=%-3d %s' % (r['id'], r['memberCount'], sum(len(v) for v in r['privileges'].values()), '(已调整)' if r['overridden'] else ''))
"

echo
echo "======================================"
echo " 通过 $PASS 项，失败 $FAIL 项"
echo "======================================"
exit $((FAIL > 0 ? 1 : 0))
