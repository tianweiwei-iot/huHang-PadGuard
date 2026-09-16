# PadGuard 服务端（桥梁）· 完整实现版

Spring Boot 3 + Kotlin 实现，作为「护航控制端（家长）」与「护航成长（被管控端/孩子）」之间的桥梁。
已实现 **P0 主链路 + P1 全量功能**：绑定、策略（时段/应用/上网/模板）、一键锁屏/模式、截屏/拍照/录音/录屏、信息发布、定位与电子围栏、使用统计、风险告警、临时解锁工单闭环、文件存储与 WebSocket 实时推送。

> 本机无需安装 JDK/Gradle：构建在 `gradle:8.8-jdk17` 容器内完成，运行只需 Docker。  
> 若用 IDEA 打开项目，项目已锁定 JDK 17；如本机默认 JDK 是 25 会提示不兼容，Gradle 会通过 Foojay 自动下载 JDK 17，或你手动在 IDEA 里选 JDK 17。

## 1. 快速启动

```bash
cd padguard-server
docker compose up --build
```

启动后：
- 服务端： http://localhost:8080
- PostgreSQL： localhost:5432 （库 padguard / 用户 padguard）
- EMQX 管理台： http://localhost:18083 （默认 admin / public，首次登录会要求改密）

Flyway 在应用启动时自动执行 `V1__init.sql` 建表。

## 2. P0 主链路演示（curl）

### 2.1 家长登录，拿到 JWT
```bash
curl -s -X POST http://localhost:8080/v1/auth/login/password \
  -H 'Content-Type: application/json' \
  -d '{"phone":"13800000000","password":"admin123"}'
# => { "code":0, "data": { "token":"<PARENT_JWT>", ... } }
```

### 2.2 家长生成 6 位绑定码
```bash
curl -s -X POST http://localhost:8080/v1/devices/bind-code \
  -H "Authorization: Bearer <PARENT_JWT>" \
  -d '{}'
# => { "code":0, "data": { "bindCode":"123456", "expiresAt":... } }
```

### 2.3 孩子端绑定（被管控端契约 §5.1）
```bash
curl -s -X POST http://localhost:8080/api/v1/device/bind \
  -H 'Content-Type: application/json' \
  -d '{
    "bindCode":"123456",
    "deviceSn":"HA1XYK9M2P",
    "fingerprint":"9f2b7c4e1a8d6",
    "model":"Xiaomi Pad 6",
    "brand":"Xiaomi",
    "androidVersion":"13",
    "sdkInt":33,
    "controlMode":"DEVICE_OWNER",
    "appVersion":"1.0.0"
  }'
# => { "code":0, "data": { "deviceId":"<UUID>", "deviceToken":"...", "mqttUsername":"<UUID>", "mqttPassword":"...", "hmacSecret":"...", "expiresAt":... } }
```

### 2.4 孩子端拉策略（被管控端契约 §5.3）
```bash
curl -s "http://localhost:8080/api/v1/device/policy?currentVersion=0" \
  -H "Authorization: Bearer <DEVICE_TOKEN>" \
  -H "X-Device-Id: <DEVICE_ID>"
# => 首次返回全量策略包；再次带 currentVersion=最新 返回 { upToDate:true }
```

### 2.5 家长一键锁屏（下行指令经 MQTT）
```bash
curl -s -X POST http://localhost:8080/v1/policies/<DEVICE_ID>/lock \
  -H "Authorization: Bearer <PARENT_JWT>" \
  -d '{"reason":"已到达就寝时间"}'
# => { "code":0, "data": { "msgId":"cmd_...", "status":"PENDING" } }
```

此时服务端向 EMQX 发布 `pg/v1/default/down/<DEVICE_ID>/command`。
- **真实孩子端**：连接 MQTT 后订阅该 topic，执行并回执 `pg/v1/default/up/<DEVICE_ID>/ack`。
- **无真实孩子端**：在 `application.yml` 设 `padguard.dev.child-simulator: true`（或 `docker compose` 传入环境变量），
  内置模拟器会自动订阅并回执 ack，闭环演示。

### 2.6 家长查询指令执行结果
```bash
curl -s "http://localhost:8080/v1/commands/cmd_<...>" \
  -H "Authorization: Bearer <PARENT_JWT>"
# => status 由 PENDING -> EXECUTED
```

### 2.7 家长查看设备列表（含在线状态）
```bash
curl -s http://localhost:8080/v1/devices -H "Authorization: Bearer <PARENT_JWT>"
```

## 3. 实时推送（WebSocket）
家长端连接：`wss://localhost:8080/ws?token=<PARENT_JWT>`
服务端推送事件：`device.online` / `device.offline` / `alert.new` / `screenshot.ready` / `usage.update`。
（P0 已实现 device.online/offline 与指令结果回显，其余事件为扩展点。）

## 4. 目录结构
```
padguard-server/
├── docker-compose.yml / Dockerfile
├── build.gradle.kts
└── src/main/kotlin/com/padguard/server/
    ├── PadGuardServerApplication.kt
    ├── common/        响应包装 + 错误码 + 异常
    ├── config/        Web/MQTT/WebSocket 配置
    ├── security/      JWT + 父子端鉴权拦截器
    ├── domain/        实体
    ├── repository/    JPA Repository
    ├── service/       Auth/BindCode/Device/Policy/Command/Ingest
    ├── mqtt/          MqttGateway(下发) + ChildUplinkHandler(上行)
    ├── ws/            WebSocket 推送
    └── controller/    parent(/v1) + child(/api/v1)
```

## 5. 对接真实被管控端前的待办（见《前后端契约对齐确认表》）
- 统一 Base URL 前缀（`/v1` vs `/api/v1`）——本脚手架两套都接，已对齐。
- 错误码分流（控制端 1xxx / 被管控端 40xxx）——已实现两套包装。
- 孩子端移除 `123456` Mock，改调真实 `POST /device/bind`（§2.3）。
- 孩子端接入 Paho MQTT，订阅 `pg/v1/default/down/{deviceId}/command`。

## 6. 完整接口一览（已实现的全部端点）

### 6.1 控制端（家长）`/v1` —— Bearer JWT，统一响应 `{code,message,data,timestamp}`
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/v1/auth/login/password` | 密码登录 |
| POST | `/v1/auth/login/sms` | 短信登录 |
| POST | `/v1/auth/sms/send` | 发验证码 |
| POST | `/v1/auth/refresh` | 刷新令牌（Header `Refresh-Token`） |
| POST | `/v1/auth/logout` | 退出 |
| POST | `/v1/devices/bind-code` | 生成 6 位绑定码 |
| GET | `/v1/devices?groupId=&status=` | 设备列表 |
| GET | `/v1/devices/{deviceId}` | 设备详情 |
| POST | `/v1/devices/{deviceId}/unbind` | 解绑 |
| PUT | `/v1/devices/{deviceId}/name` | 改名 |
| PUT | `/v1/devices/{deviceId}/group` | 分配到分组 |
| GET | `/v1/devices/groups?sceneType=` | 分组列表 |
| POST | `/v1/devices/groups` | 创建分组 |
| POST | `/v1/policies/{deviceId}/lock` | 一键锁屏（MQTT 下发） |
| POST | `/v1/policies/{deviceId}/mode` | 切换模式 |
| GET | `/v1/commands/{msgId}` | 查指令结果 |
| GET | `/v1/policies/{deviceId}/time-restrictions` | 时段限制列表 |
| POST | `/v1/policies/{deviceId}/time-restrictions` | 新增时段限制 |
| DELETE | `/v1/policies/time-restrictions/{id}?deviceId=` | 删除时段限制 |
| GET | `/v1/policies/{deviceId}/apps` | 应用管控列表 |
| PUT | `/v1/policies/{deviceId}/apps/blacklist` | 设应用黑名单 |
| PUT | `/v1/policies/{deviceId}/apps/limit` | 设每日时长 |
| GET | `/v1/policies/{deviceId}/web` | 上网策略 |
| PUT | `/v1/policies/{deviceId}/web/urls` | 设网址黑名单 |
| PUT | `/v1/policies/{deviceId}/web/browser` | 禁用浏览器 |
| GET | `/v1/policies/templates?sceneType=` | 策略模板 |
| POST | `/v1/policies/{deviceId}/apply-template` | 应用模板 |
| POST | `/v1/monitor/{deviceId}/screenshot` | 请求截屏 |
| GET | `/v1/monitor/{deviceId}/screenshots?limit=` | 截屏历史 |
| GET | `/v1/monitor/{deviceId}/location` | 实时位置 |
| POST | `/v1/monitor/{deviceId}/photo` | 远程拍照 |
| POST | `/v1/monitor/{deviceId}/record/start` | 开始录音 |
| POST | `/v1/monitor/{deviceId}/record/stop` | 停止录音 |
| POST | `/v1/monitor/{deviceId}/screen-record/start` | 开始录屏 |
| POST | `/v1/monitor/{deviceId}/screen-record/{taskId}/stop` | 停止录屏 |
| GET/PUT | `/v1/monitor/{deviceId}/screen-settings` | 屏幕监控设置 |
| POST | `/v1/messages/{deviceId}/publish` | 发布信息（SHOW_MESSAGE） |
| GET | `/v1/messages/{deviceId}/history` | 发布历史 |
| GET | `/v1/location/{deviceId}/current` | 当前位置 |
| GET/PUT | `/v1/location/{deviceId}/geofence` | 电子围栏 |
| GET | `/v1/location/{deviceId}/track` | 越界轨迹 |
| GET | `/v1/statistics/{deviceId}/usage?period=` | 使用统计 |
| GET | `/v1/statistics/{deviceId}/today` | 今日时长 |
| GET | `/v1/statistics/{deviceId}/web?period=` | 网页统计 |
| GET | `/v1/statistics/{deviceId}/violations?period=` | 违规统计 |
| GET | `/v1/statistics/{deviceId}/export?period=` | 导出报告 |
| GET | `/v1/alerts?deviceId=&status=&page=&size=` | 告警列表 |
| GET | `/v1/alerts/unread-count` | 未读计数 |
| POST | `/v1/alerts/{alertId}/acknowledge` | 确认告警 |
| POST | `/v1/alerts/{alertId}/close` | 关闭告警 |
| GET | `/v1/devices/{deviceId}/unlock-tickets` | 解锁工单列表 |
| POST | `/v1/devices/{deviceId}/unlock-tickets/{ticketId}/approve` | 批准（下发 TEMP_UNLOCK） |
| POST | `/v1/devices/{deviceId}/unlock-tickets/{ticketId}/reject` | 拒绝（下发 SHOW_MESSAGE） |
| GET | `/v1/files/{id}` | 下载/预览上传的文件（截图/报告） |

### 6.2 被管控端（孩子）`/api/v1` —— X-Device-Id + Bearer deviceToken，统一响应 `{code,message,data,serverTime}`
| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/device/bind` | 绑定（消费绑定码，下发令牌/MQTT/HMAC） |
| GET | `/api/v1/device/time` | 时间同步 |
| GET | `/api/v1/device/policy?currentVersion=` | 拉策略（全量+版本比对） |
| POST | `/api/v1/device/logs` | 批量行为日志（含 UNLOCK_REQUEST 转工单，logId 幂等） |
| POST | `/api/v1/device/locations` | 定位轨迹（入库+围栏检测） |
| POST | `/api/v1/device/heartbeat` | 心跳（HTTP 降级） |
| GET | `/api/v1/device/commands?since=` | 指令轮询（MQTT 降级） |
| POST | `/api/v1/device/screenshot` (multipart) | 上报截图 |

### 6.3 MQTT 通道（EMQX）
- 下行：`pg/v1/{tenant}/down/{deviceId}/command|policy|config`，指令带 HMAC-SHA256 签名
- 上行：`pg/v1/{tenant}/up/{deviceId}/ack|heartbeat|event|status`，服务端据此回写状态/建告警/推 WS
- 指令类型：LOCK_SCREEN / SCREENSHOT / REMOTE_PHOTO / RECORD / SCREEN_RECORD / TEMP_UNLOCK / SHOW_MESSAGE / 策略类

### 6.4 WebSocket（`wss://host/ws?token=<JWT>`）推送事件
`device.online` / `device.offline` / `alert.new` / `screenshot.ready` / `command.result` / `usage.update`

## 7. 加速构建（国内网络必看）

构建慢有两类，已分别处理：

### 6.1 Gradle 依赖下载慢 —— 已内置阿里云镜像 + 构建缓存
- `settings.gradle.kts` 已把插件仓库与依赖仓库统一指向 `maven.aliyun.com`，不再直连 Maven Central / Gradle Plugin Portal。
- `gradle.properties` 已开启 `org.gradle.caching=true` + `org.gradle.parallel=true`。
- `Dockerfile` 用 BuildKit 缓存挂载（`--mount=type=cache,target=/home/gradle/.gradle`），**首次构建下载依赖一次后，后续 `docker compose up --build` 直接命中缓存，秒级重建**。
- `build.gradle.kts` 已移除 `repositories` 块，统一由 `settings.gradle.kts` 接管（避免冲突）。

> 仍提示解析失败时可手动清缓存：`docker builder prune` 后重试。

### 6.2 Docker Hub 镜像拉取慢 —— 需在本机配置镜像加速器
`gradle:8.8-jdk17`、`eclipse-temurin:17-jre`、`postgres:16`、`emqx:5.8` 均来自 Docker Hub，国内直连慢。
在 Docker Desktop（或 `/etc/docker/daemon.json`）加入加速器后重启 Docker：

```json
{
  "registry-mirrors": [
    "https://docker.mirrors.ustc.edu.cn",
    "https://hub-mirror.c.163.com",
    "https://mirror.ccs.tencentyun.com"
  ]
}
```
> 阿里云用户可在 https://cr.console.aliyun.com 领取专属加速器地址，速度通常最快，加在列表首位。

### 6.3 预拉取镜像（可选，进一步提速）
```bash
docker pull gradle:8.8-jdk17 eclipse-temurin:17-jre postgres:16 emqx:5.8
```

### 6.4 IDEA 本地开发环境准备（JDK / Gradle 版本问题汇总）

本项目编译目标锁定 **JDK 17**，IDEA 本地开发需要满足两个条件：
1. **Gradle 运行 JVM** 必须是 JDK 17（或 21），因为 Gradle 8.8 不支持在 JDK 25 上运行。
2. **项目编译 toolchain** 也是 JDK 17（已用 `kotlin.jvmToolchain(17)` 锁定）。

#### 6.4.1 必须安装 JDK 17
如果你的本机只有 JDK 25，Gradle 8.8 启动就会报：  
`Incompatible Gradle JVM version. The project's Gradle version 8.8 is incompatible with the Gradle JVM version 25.`  
这是硬性限制，**必须先安装 JDK 17**。推荐下载：
- Eclipse Temurin JDK 17（Windows x64）：https://mirrors.aliyun.com/Adoptium/17/jdk/x64/windows/
- 或 BellSoft Liberica JDK 17：https://bell-sw.com/pages/downloads/

安装后，在 IDEA 里配置：
1. `File → Project Structure → SDKs` → 添加刚装的 JDK 17。
2. `Settings → Build, Execution, Deployment → Build Tools → Gradle` → 把 `Gradle JVM` 选为 JDK 17。
3. 点击 **Reload Gradle Project**。

> 不推荐把 Kotlin 升到 2.1.x、Gradle 升到 9.x 来适配 JDK 25：Spring Boot 3.3 与 Kotlin 1.9.x / Gradle 8.x 是官方验证组合，升级主版本会引入未知兼容性风险。

#### 6.4.2 报错 "JvmVendorSpec does not have member field"
如果 Gradle sync 报 `Class org.gradle.jvm.toolchain.JvmVendorSpec does not have member field ...`，说明 Gradle wrapper 被 IDEA 初始化为 9.3.0。  
本项目已把 `gradle/wrapper/gradle-wrapper.properties` 固定为 **Gradle 8.8**。  
操作：删除项目根目录下的 `.gradle` 文件夹 → 在 IDEA 里 **Reload Gradle Project**，让它重新下载 Gradle 8.8 再 sync。

#### 6.4.3 不想安装 JDK 17 的替代方案
直接用 Docker 构建运行，完全避开本机 JDK 问题：
```bash
cd padguard-server
docker compose up --build
```
Docker 镜像内已自带 JDK 17 + Gradle 8.8，只需你本机有 Docker。

