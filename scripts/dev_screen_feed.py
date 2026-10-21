#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
本地开发用「实时看屏」链路验证工具（不依赖任何服务端代码改动）。

它做两件事，借此端到端验证「孩子端推帧 -> 服务端中转 -> 家长拉流」全链路：

  1. 模拟一个“孩子端”：在本地服务端绑定一台合成设备，并按帧把测试画面
     推到服务端  POST /api/v1/device/stream/up  （走真实的孩子端鉴权头）。
  2. 模拟一个“家长端”：从服务端真实出口  GET /v1/stream/{deviceId}/live
     拉取中转后的帧，转成本地 MJPEG 流，浏览器打开即可看到实时画面。

运行（默认连本机 8080、家长 13800000000/admin123）：
    python dev_screen_feed.py
然后浏览器打开： http://localhost:9000/

参数：
    --server   服务端地址，默认 http://localhost:8080
    --phone    家长手机号，默认 13800000000
    --password 家长密码，默认 admin123
    --fps      推帧频率，默认 4
    --port     本地 MJPEG 端口，默认 9000
    --frames   推送帧数上限（0=无限），默认 0
"""
import argparse
import base64
import struct
import sys
import threading
import time
import urllib.request
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:
    sys.exit("缺少 Pillow，请先：pip install pillow")

# ====================== 实时帧共享（viewer -> MJPEG server） ======================
_latest_frame = None
_latest_id = 0
_frame_lock = threading.Lock()
_frame_cond = threading.Condition(_frame_lock)


def publish_frame(jpeg: bytes):
    global _latest_frame, _latest_id
    with _frame_lock:
        _latest_frame = jpeg
        _latest_id += 1
        _frame_cond.notify_all()


# ====================== HTTP 小工具 ======================
def http_json(url, method="GET", token=None, device_id=None, data=None, raw=False, timeout=30):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if device_id:
        headers["X-Device-Id"] = device_id
    if raw and data is not None:
        headers["Content-Type"] = "application/octet-stream"
    elif data is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
    except urllib.error.HTTPError as e:
        raise RuntimeError("HTTP %s %s -> %s: %s" % (method, url, e.code, e.read().decode("utf-8", "replace")))
    if raw:
        return body
    return body.decode("utf-8", "replace")


import json
def jget(d, *keys, default=None):
    for k in keys:
        if not isinstance(d, dict):
            return default
        d = d.get(k, default)
    return d


# ====================== 测试画面生成 ======================
def make_test_pattern(idx, w=480, h=320):
    img = Image.new("RGB", (w, h), (idx * 7 % 256, (idx * 13 + 60) % 256, (idx * 23 + 120) % 256))
    draw = ImageDraw.Draw(img)
    # 简易栅格，肉眼可辨“在动”
    for x in range(0, w, 40):
        draw.line([(x, 0), (x, h)], fill=(255, 255, 255), width=1)
    for y in range(0, h, 40):
        draw.line([(0, y), (w, y)], fill=(255, 255, 255), width=1)
    try:
        font = ImageFont.truetype("arial.ttf", 28)
    except Exception:
        font = ImageFont.load_default()
    ts = time.strftime("%H:%M:%S")
    draw.text((16, 16), "PADGUARD LIVE TEST", fill=(255, 255, 0), font=font)
    draw.text((16, 56), "frame=%d" % idx, fill=(0, 255, 255), font=font)
    draw.text((16, 96), ts, fill=(255, 255, 255), font=font)
    buf = BytesIO()
    img.save(buf, format="JPEG", quality=80)
    return buf.getvalue()


# ====================== 模拟孩子端：推流 ======================
def feeder(server, device_id, device_token, fps, max_frames):
    interval = 1.0 / max(fps, 1)
    idx = 0
    url = server.rstrip("/") + "/api/v1/device/stream/up"
    while max_frames == 0 or idx < max_frames:
        jpeg = make_test_pattern(idx)
        payload = struct.pack(">I", len(jpeg)) + jpeg  # 4 字节大端长度 + JPEG
        try:
            http_json(url, method="POST", device_id=device_id, token=device_token, data=payload, raw=True, timeout=10)
        except Exception as e:
            print("[feeder] push error: %s" % e, flush=True)
        idx += 1
        time.sleep(interval)


# ====================== 模拟家长端：从服务端真实出口拉流 ======================
def viewer(server, parent_token, device_id):
    url = server.rstrip("/") + "/v1/stream/%s/live" % device_id
    while True:
        # 注意：/live 是无限流（4 字节长度 + JPEG 反复），必须增量 read，绝不能一次性 read()
        try:
            import http.client
            resp = urllib.request.urlopen(
                urllib.request.Request(url, headers={"Authorization": "Bearer " + parent_token}),
                timeout=120,
            )
            while True:
                header = resp.read(4)
                if len(header) < 4:
                    break
                length = struct.unpack(">I", header)[0]
                if length == 0:
                    continue  # 心跳帧
                frame = resp.read(length)
                if len(frame) < length:
                    break
                publish_frame(frame)
        except Exception as e:
            print("[viewer] stream ended: %s (1s 后重试)" % e, flush=True)
            time.sleep(1)


# ====================== 本地 MJPEG 服务（让家长“看到”画面） ======================
class MJPEGHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/":
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        boundary = b"\r\n--frame\r\n"
        last_id = -1
        try:
            while True:
                with _frame_lock:
                    _frame_cond.wait_for(lambda: _latest_id != last_id, timeout=1.0)
                    frame = _latest_frame
                    cur_id = _latest_id
                if frame is None:
                    continue
                last_id = cur_id
                self.wfile.write(boundary)
                self.send_header("Content-Type", "image/jpeg")
                self.send_header("Content-Length", str(len(frame)))
                self.end_headers()
                self.wfile.write(frame)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, *args):
        pass


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--server", default="http://localhost:8080")
    ap.add_argument("--phone", default="13800000000")
    ap.add_argument("--password", default="admin123")
    ap.add_argument("--fps", type=int, default=4)
    ap.add_argument("--port", type=int, default=9000)
    ap.add_argument("--frames", type=int, default=0)
    args = ap.parse_args()

    # 1) 家长登录
    login_url = args.server.rstrip("/") + "/v1/auth/login/password"
    login_body = json.dumps({"phone": args.phone, "password": args.password}).encode()
    login_resp = json.loads(http_json(login_url, method="POST", data=login_body))
    parent_token = jget(login_resp, "data", "token")
    if not parent_token:
        sys.exit("家长登录失败：%s" % login_resp)
    print("[ok] 家长登录成功", flush=True)

    # 2) 开一次设备接入会话，拿到 6 位接入码（pairingCode）
    code_url = args.server.rstrip("/") + "/v1/devices/enrollments"
    code_resp = json.loads(http_json(code_url, method="POST", token=parent_token, data=b"{}"))
    bind_code = jget(code_resp, "data", "pairingCode")
    if not bind_code:
        sys.exit("开启接入会话失败：%s" % code_resp)
    print("[ok] 接入码(pairingCode)=%s" % bind_code, flush=True)

    # 3) 绑定合成设备
    bind_url = args.server.rstrip("/") + "/api/v1/device/bind"
    bind_payload = json.dumps({
        "bindCode": bind_code,
        "deviceSn": "DEV-SYNTH-%d" % int(time.time()),
        "fingerprint": "synth-%d" % int(time.time()),
        "model": "LocalTestTablet",
        "brand": "PadGuard",
        "androidVersion": "13",
        "sdkInt": 33,
        "controlMode": "DEVICE_OWNER",
        "appVersion": "1.0.0",
    }).encode()
    bind_resp = json.loads(http_json(bind_url, method="POST", data=bind_payload))
    device_id = jget(bind_resp, "data", "deviceId")
    device_token = jget(bind_resp, "data", "deviceToken")
    if not device_id or not device_token:
        sys.exit("绑定设备失败：%s" % bind_resp)
    print("[ok] 合成设备已绑定 deviceId=%s" % device_id, flush=True)

    # 4) 起推流（模拟孩子端）与拉流（模拟家长端）
    threading.Thread(target=feeder, args=(args.server, device_id, device_token, args.fps, args.frames), daemon=True).start()
    threading.Thread(target=viewer, args=(args.server, parent_token, device_id), daemon=True).start()

    print("=" * 60, flush=True)
    print("实时看屏链路已接通（合成帧 -> 服务端中转 -> 本地 MJPEG）", flush=True)
    print("浏览器打开： http://localhost:%d/  即可看到实时画面" % args.port, flush=True)
    print("也可在家长端 App 中直接观看 deviceId=%s" % device_id, flush=True)
    print("Ctrl+C 停止", flush=True)
    print("=" * 60, flush=True)

    server_obj = ThreadingHTTPServer(("127.0.0.1", args.port), MJPEGHandler)
    try:
        server_obj.serve_forever()
    except KeyboardInterrupt:
        print("\n[stop] 已停止", flush=True)
        server_obj.shutdown()


if __name__ == "__main__":
    main()
