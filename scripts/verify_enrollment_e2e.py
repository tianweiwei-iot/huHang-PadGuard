# -*- coding: utf-8 -*-
"""
设备接入（Enrollment）链路端到端验证脚本。

覆盖从源头重构后的完整链路：
  1. 家长登录取令牌
  2. 打开接入会话：一次请求同时返回接入码与二维码载荷（同源校验）
  3. 重复打开：幂等复用同一会话（退出去再进来不会作废孩子手里的码）
  4. 刷新接入码：新码生成、旧码立即作废（旧码绑定必须失败）
  5. 孩子端凭新码绑定成功
  6. 家长端查询：会话状态变 BOUND 且 deviceId 与绑定结果一致
  7. 设备列表出现该设备

用法：
    python scripts/verify_enrollment_e2e.py [base_url]
默认 base_url = http://127.0.0.1:8090
"""

import json
import sys
import threading
import time
import urllib.error
import urllib.request

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8090"
PHONE = "19371581808"
PASSWORD = "201325"

results = []


def call(method, path, body=None, token=None, expect_ok=True):
    """发起一次 HTTP 调用，返回 (http_status, payload_dict, raw_text)。"""
    url = BASE + path
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8", "replace")
            return resp.status, json.loads(raw) if raw else {}, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw), raw
        except json.JSONDecodeError:
            return e.code, {}, raw
    except Exception as e:  # 连接失败等
        return -1, {}, str(e)


def check(name, ok, detail=""):
    results.append((name, ok, detail))
    mark = "PASS" if ok else "FAIL"
    print("[%s] %s%s" % (mark, name, ("  -- " + detail) if detail else ""))


def main():
    print("=" * 72)
    print("PadGuard 设备接入链路端到端验证  base=%s" % BASE)
    print("=" * 72)

    # ---------- 1. 家长登录 ----------
    st, body, raw = call("POST", "/v1/auth/login/password",
                         {"phone": PHONE, "password": PASSWORD})
    token = ((body.get("data") or {}).get("token") if isinstance(body, dict) else None)
    check("家长登录返回令牌", st == 200 and bool(token),
          "http=%s token=%s" % (st, (token[:16] + "...") if token else raw[:120]))
    if not token:
        return finish()

    # ---------- 2. 打开接入会话 ----------
    st, body, raw = call("POST", "/v1/devices/enrollments", None, token)
    e1 = body.get("data") or {}
    sid1 = e1.get("sessionId")
    code1 = e1.get("pairingCode")
    qr1 = e1.get("qrPayload")
    check("打开接入会话成功", st == 200 and bool(sid1) and bool(code1),
          "sessionId=%s code=%s" % (sid1, code1))

    # 核心断言：二维码载荷里携带的码 == 界面展示的接入码（同源，杜绝"两个码"）
    same = bool(qr1) and bool(code1) and qr1.endswith(code1)
    check("二维码与接入码同源", same, "qrPayload=%s pairingCode=%s" % (qr1, code1))
    check("会话初始状态为 PENDING", e1.get("state") == "PENDING", "state=%s" % e1.get("state"))
    check("返回有效期与服务端时间", bool(e1.get("expiresAt")) and bool(e1.get("serverNow")),
          "expiresAt=%s serverNow=%s" % (e1.get("expiresAt"), e1.get("serverNow")))

    # ---------- 3. 幂等：重复打开复用同一会话 ----------
    st, body, raw = call("GET", "/v1/devices/enrollments/current", None, token)
    cur = body.get("data") or {}
    check("重复打开复用同一会话（幂等）",
          cur.get("sessionId") == sid1 and cur.get("pairingCode") == code1,
          "code=%s (期望 %s)" % (cur.get("pairingCode"), code1))

    # ---------- 4. 刷新接入码：旧码作废 ----------
    st, body, raw = call("POST", "/v1/devices/enrollments/refresh", None, token)
    e2 = body.get("data") or {}
    code2 = e2.get("pairingCode")
    check("刷新生成新的接入码", st == 200 and bool(code2) and code2 != code1,
          "old=%s new=%s" % (code1, code2))

    # ---------- 5. 旧码已作废：孩子端用它绑定必须失败 ----------
    sn = "E2E-%d" % int(time.time())
    st, body, raw = call("POST", "/api/v1/device/bind",
                         {"bindCode": code1, "deviceSn": sn, "model": "E2E-Device",
                          "brand": "PadGuard", "androidVersion": "13", "sdkInt": 33})
    ok_reject = (st != 200) or (body.get("code") not in (0, None))
    check("作废的旧码无法完成绑定", ok_reject,
          "http=%s code=%s msg=%s" % (st, body.get("code"), body.get("message")))

    # ---------- 6. 孩子端凭新码绑定 ----------
    st, body, raw = call("POST", "/api/v1/device/bind",
                         {"bindCode": code2, "deviceSn": sn, "model": "E2E-Device",
                          "brand": "PadGuard", "androidVersion": "13", "sdkInt": 33})
    bind = body.get("data") or {}
    device_id = bind.get("deviceId")
    check("孩子端凭新接入码绑定成功", st == 200 and bool(device_id) and bool(bind.get("deviceToken")),
          "deviceId=%s" % device_id)

    # ---------- 7. 家长端查询：会话变 BOUND 且绑定结果一致 ----------
    st, body, raw = call("GET", "/v1/devices/enrollments/current", None, token)
    cur = body.get("data") or {}
    check("家长端查询到会话已 BOUND", cur.get("state") == "BOUND",
          "state=%s" % cur.get("state"))
    check("会话记录的 deviceId 与绑定结果一致", cur.get("deviceId") == device_id,
          "session.deviceId=%s bind.deviceId=%s" % (cur.get("deviceId"), device_id))

    # ---------- 8. 设备列表出现新设备 ----------
    st, body, raw = call("GET", "/v1/devices", None, token)
    devices = body.get("data") or []
    hit = [d for d in devices if d.get("id") == device_id]
    check("家长端设备列表出现该设备", bool(hit),
          "列表 %d 台，命中=%s" % (len(devices), bool(hit)))

    # ---------- 9. 取消只针对「进行中」的接入，不得改写已完成的接入 ----------
    st, _, _ = call("DELETE", "/v1/devices/enrollments/current", None, token)
    st2, body2, _ = call("GET", "/v1/devices/enrollments/current", None, token)
    cur2 = body2.get("data") or {}
    check("取消不得改写已完成的接入（BOUND 保持不变）",
          st == 200 and cur2.get("state") == "BOUND" and cur2.get("deviceId") == device_id,
          "state=%s deviceId=%s" % (cur2.get("state"), cur2.get("deviceId")))

    # ---------- 10. 取消进行中的接入 ----------
    call("POST", "/v1/devices/enrollments/refresh", None, token)  # 重新开一个 PENDING 会话
    st, _, _ = call("DELETE", "/v1/devices/enrollments/current", None, token)
    st2, body2, _ = call("GET", "/v1/devices/enrollments/current", None, token)
    cur2 = body2.get("data") or {}
    check("取消进行中的接入后会话变 CANCELLED",
          st == 200 and cur2.get("state") == "CANCELLED",
          "state=%s" % cur2.get("state"))

    # ---------- 11. 并发：同一个接入码不得绑上两台设备 ----------
    st, body, _ = call("POST", "/v1/devices/enrollments/refresh", None, token)
    code3 = (body.get("data") or {}).get("pairingCode")
    barrier = threading.Barrier(2)
    outcomes = [None, None]

    def racer(index, device_sn):
        barrier.wait()  # 尽量让两个孩子端在同一瞬间提交同一个码
        outcomes[index] = call(
            "POST", "/api/v1/device/bind",
            {"bindCode": code3, "deviceSn": device_sn, "model": "E2E-Race",
             "brand": "PadGuard", "androidVersion": "13", "sdkInt": 33})

    threads = [
        threading.Thread(target=racer, args=(0, sn + "-A")),
        threading.Thread(target=racer, args=(1, sn + "-B")),
    ]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    winners = [o for o in outcomes if o and o[0] == 200 and o[1].get("code") in (0, None)]
    losers = [o for o in outcomes if o not in winners]
    check("并发提交同一接入码时只有一台设备绑定成功", len(winners) == 1,
          "成功 %d 台；被拒者=%s" % (
              len(winners),
              [(o[0], o[1].get("code"), o[1].get("message")) for o in losers]))

    return finish()


def finish():
    total = len(results)
    passed = sum(1 for _, ok, _ in results if ok)
    print("-" * 72)
    print("合计 %d 项，通过 %d 项，失败 %d 项" % (total, passed, total - passed))
    if passed != total:
        print("失败项：")
        for name, ok, detail in results:
            if not ok:
                print("  - %s  %s" % (name, detail))
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
