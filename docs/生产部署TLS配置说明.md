# PadGuard 生产部署 TLS 配置说明

> 适用：padguard-server 云部署（公网）/ 局域网私有化部署
> 关联：上线完善计划.md 的 P0-2（全链路传输加密）

## 1. 为什么是"双模"策略

PadGuard 同时支持两种部署形态，**不能一刀切禁止明文**：

- **家庭局域网部署**（服务端跑在家长电脑，孩子端平板在内网）：服务端、孩子端、家长端都在同一信任域内，内网 `http/tcp` 明文是产品固有形态，强制 `usesCleartextTraffic=false` 会让这类核心场景直接连不上。
- **公网/云部署**（阿里云等）：数据跨公网，必须加密，否则 JWT、设备令牌、位置、屏幕帧、操作密码哈希全部明文暴露，可被中间人截获重放。

因此策略是：**客户端支持 https/ssl（默认引导值已改为 https/ssl），公网链路由 Nginx 在 443 终止 TLS、MQTT 启用 8883 TLS；家庭局域网明文保留但仅限内网。**

> 已修复的误伤：早期 `ServerConfigDialog` 会把用户填的 `https://` 静默降级成 `http://`，本次确认代码已保留用户选择的 scheme（见 `ServerConfigDialog.kt:163`）。

## 2. 云部署：启用 TLS（推荐生产形态）

### 2.1 生成证书

```bash
cd padguard-server
# 自签名，仅供测试/内网联调
bash scripts/gen-certs.sh 你的域名
# 生产环境请用 Let's Encrypt(certbot) 或商业证书，覆盖下面两个目录里的同名文件
```

生成后：
- Nginx 用 `nginx/certs/{server.crt, server.key}`
- Mosquitto 用 `mosquitto/certs/{ca.crt, server.crt, server.key}`（自签名时 CA 即自身）

### 2.2 启动编排

```bash
cp .env.prod.example .env   # 填入强密码/JWT/凭据
docker compose -f docker-compose.prod.yml up -d postgres mosquitto nginx
docker compose -f docker-compose.prod.yml up -d --build app
```

### 2.3 端口与安全边界

| 端口 | 暴露 | 说明 |
|---|---|---|
| 80 | 公网 | HTTP，一律 301 重定向到 HTTPS |
| 443 | 公网 | HTTPS 入口，TLS 在此终止，反代到 app:8080 |
| 8883 | 公网 | MQTT over TLS，孩子端设备从公网连 |
| 1883 | **仅内网** | MQTT 明文，仅 compose 内网（app 容器连它），**不映射公网** |
| 8080 | **仅内网** | app 端口，仅 compose 内网，不映射公网 |

`nginx/nginx.conf` 已加 `Strict-Transport-Security`（HSTS）、`X-Content-Type-Options`、`X-Frame-Options` 安全头；`proxy_read_timeout 600s` 与后端一致，避免实时看屏长连接被掐断。

### 2.4 孩子端接入点

公网部署后，孩子端在「扫码绑定」或「高级设置」里填：
- REST：`https://你的域名/api/v1/`
- MQTT：`ssl://你的域名:8883`

（默认引导值已改为 `https://padguard.example.com/...` 与 `ssl://padguard.example.com:8883`，实际由绑定/高级设置覆盖。）

## 3. 家庭局域网部署：保持明文内网

- 服务端用 `home` profile 启动（H2 文件库 + 内置 Mosquitto，`application.yml` 的 `home` profile）。
- 孩子端填 `http://家长电脑内网IP:8090/api/v1/` 与 `tcp://家长电脑内网IP:1883`（或扫码绑定自动获取）。
- 此形态数据不出内网，明文可接受；但**切勿把 8090/1883 端口映射到公网**，否则等同公网明文。

## 4. 风险提示

- **自签名证书**：客户端（系统）不会信任自签名证书，HTTPS/MQTT TLS 会报证书错误。测试期可临时将证书导入设备信任库，但**生产必须换 Let's Encrypt / 商业证书**。
- **家庭部署明文**：仅在"同一内网、可信家庭"前提下安全；若平板连接公网 Wi-Fi 且服务端端口误映射公网，则暴露。请在路由器层面仅开放 443/8883。
- **证书固定（Certificate Pinning）**：当前未启用 `CertificatePinner`。若产品面向高对抗场景（孩子主动 MITM），可在客户端加 pin（pin 后端域名证书），但会绑死证书、增加运维成本，列为 P2 后续项。
