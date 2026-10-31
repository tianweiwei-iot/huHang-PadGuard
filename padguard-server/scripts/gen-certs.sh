#!/usr/bin/env bash
# 生成自签名 TLS 证书，仅供测试/内网 TLS 联调使用。
# 生产环境请替换为 Let's Encrypt（certbot）或商业证书，否则客户端会报证书不受信任。
#
# 用法：
#   bash scripts/gen-certs.sh 你的域名
#   （不传域名则默认 padguard.example.com）
#
# 生成后：
#   · Nginx 用  ./nginx/certs/{server.crt,server.key}
#   · Mosquitto 用 ./mosquitto/certs/{ca.crt,server.crt,server.key}（自签名时 CA 即自身）
set -euo pipefail

DOMAIN="${1:-padguard.example.com}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NGINX_DIR="$ROOT/nginx/certs"
MOSQ_DIR="$ROOT/mosquitto/certs"
mkdir -p "$NGINX_DIR" "$MOSQ_DIR"

openssl req -x509 -newkey rsa:2048 -nodes \
  -keyout "$NGINX_DIR/server.key" -out "$NGINX_DIR/server.crt" \
  -days 365 -subj "/CN=$DOMAIN"

# 自签名场景下，Mosquitto 的 CA 就是这张证书本身
cp "$NGINX_DIR/server.crt" "$MOSQ_DIR/ca.crt"
cp "$NGINX_DIR/server.crt" "$MOSQ_DIR/server.crt"
cp "$NGINX_DIR/server.key" "$MOSQ_DIR/server.key"

echo "已生成证书："
echo "  Nginx:  $NGINX_DIR/server.crt / server.key"
echo "  Mosquitto: $MOSQ_DIR/ca.crt / server.crt / server.key"
echo "生产环境请改用 Let's Encrypt / 商业证书，避免客户端证书不受信任。"
