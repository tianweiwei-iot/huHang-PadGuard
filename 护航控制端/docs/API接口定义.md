# API 接口定义文档

> 版本: v1.0.0  
> 更新日期: 2026-08-29  
> 对应: 平板管控系统-管控端APK功能设计说明书

---

## 1. 通用规范

### 1.1 基础信息
| 项目 | 值 |
|------|-----|
| Base URL | `https://api.padguard.com/v1/` |
| 协议 | HTTPS (生产) / HTTP (Debug) |
| 数据格式 | JSON (UTF-8) |
| 认证方式 | Bearer Token (JWT) |

### 1.2 统一响应格式
```json
{
  "code": 0,           // 0=成功, 非0=错误码
  "message": "success", // 错误时的描述信息
  "data": {},          // 业务数据（成功时有值）
  "timestamp": 1693318400000  // 服务器时间戳(ms)
}
```

### 1.3 错误码
| code | 含义 |
|------|------|
| 0 | 成功 |
| 1001 | 参数错误 |
| 1002 | 未授权（Token无效或过期） |
| 1003 | 权限不足 |
| 2001 | 设备不存在 |
| 2002 | 设备未在线 |
| 2003 | 设备不属于当前用户 |
| 3001 | 策略不存在 |
| 3002 | 策略冲突 |
| 5001 | 服务器内部错误 |

### 1.4 分页参数
| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| page | int | 否 | 0 | 页码(从0开始) |
| size | int | 否 | 20 | 每页条数 |

---

## 2. 认证模块 (`/auth`)

### 2.1 密码登录
```
POST /auth/login/password
Content-Type: application/json

Request:
{
  "phone": "13800138000",     // 手机号
  "password": "encrypted_pwd"  // 密密(RSA加密)
}

Response 200:
{
  "code": 0,
  "data": {
    "user": { "id", "phone", "nickname", "avatar", "role", "sceneType" },
    "token": "eyJhbGci...",
    "refreshToken": "eyJhbGci..."
  }
}
```

### 2.2 短信登录
```
POST /auth/login/sms
Content-Type: application/json

Request:
{
  "phone": "13800138000",
  "smsCode": "123456"        // 6位数字验证码
}

Response: 同 2.1
```

### 2.3 发送验证码
```
POST /auth/sms/send
Content-Type: application/json

Request:
{ "phone": "13800138000" }

Response 200: { "code": 0 }
```

### 2.4 刷新Token
```
POST /auth/refresh
Header: Refresh-Token: <refresh_token>

Response 200:
{
  "code": 0,
  "data": { "token": "new_jwt", "refreshToken": "new_refresh" }
}
```

### 2.5 退出登录
```
POST /auth/logout
Header: Authorization: Bearer <token>

Response 200: { "code": 0 }
```

---

## 3. 设备管理 (`/devices`)

### 3.1 获取设备列表
```
GET /devices?groupId=xxx&status=ONLINE
Header: Authorization: Bearer <token>

Response 200:
{
  "code": 0,
  "data": [
    {
      "id": "dev_001",
      "name": "小明的平板",
      "deviceId": "PAD_XIAOMI_001",
      "model": "Xiaomi Pad 6",
      "osVersion": "14",
      "appVersion": "1.0.0",
      "onlineStatus": "ONLINE",
      "lastOnlineTime": 1693318400000,
      "batteryLevel": 85,
      "controlMode": "NORMAL",
      "groupId": "group_001",
      "groupName": "家庭设备",
      "sceneType": "FAMILY"
    }
  ]
}
```

### 3.2 获取设备详情
```
GET /devices/{deviceId}
Response: 同 3.1 单个设备对象
```

### 3.3 绑定设备
```
POST /devices/bind
Request: { "deviceId": "PAD_xxx", "alias": "我的平板" }
Response 200: { "code": 0, "data": { Device对象 } }
```

### 3.4 解绑设备
```
POST /devices/{deviceId}/unbind
Response 200: { "code": 0 }
```

### 3.5 修改设备别名
```
PUT /devices/{deviceId}/name
Request: { "name": "新名称" }
Response 200: { "code": 0 }
```

### 3.6 获取分组列表
```
GET /devices/groups?sceneType=FAMILY
Response 200:
{
  "code": 0,
  "data": [
    { "id": "group_001", "name": "家庭设备", "sceneType": "FAMILY", "deviceCount": 2 }
  ]
}
```

### 3.7 创建分组
```
POST /devices/groups
Request: { "name": "三年级一班", "sceneType": "SCHOOL" }
Response 200: { "code": 0, "data": { Group对象 } }
```

### 3.8 分配设备到分组
```
PUT /devices/{deviceId}/group
Request: { "groupId": "group_002" }
Response 200: { "code": 0 }
```

---

## 4. 实时监控 (`/monitor`) [P1]

### 4.1 请求实时截屏
```
POST /monitor/{deviceId}/screenshot
Response 200:
{
  "code": 0,
  "data": {
    "deviceId": "dev_001",
    "imageUrl": "https://...png",
    "thumbnailUrl": "https://...thumb.png",
    "capturedAt": 1693318400000,
    "width": 1920,
    "height": 1080
  }
}
```

### 4.2 截屏历史
```
GET /monitor/{deviceId}/screenshots?limit=20
Response 200: { "code": 0, "data": [ Screenshot[] ] }
```

### 4.3 获取设备位置
```
GET /monitor/{deviceId}/location
Response 200:
{
  "code": 0,
  "data": {
    "latitude": 22.5431,
    "longitude": 114.0579,
    "address": "深圳市南山区科技园",
    "accuracy": 10.0,
    "timestamp": 1693318400000
  }
}
```

### 4.4 远程拍照
```
POST /monitor/{deviceId}/photo
Response 200:
{
  "code": 0,
  "data": {
    "url": "https://...jpg",
    "mimeType": "image/jpeg",
    "size": 2048000
  }
}
```

### 4.5 远程录音
```
POST /monitor/{deviceId}/record/start   # 开始
POST /monitor/{deviceId}/record/stop    # 停止
Response 200:
{
  "code": 0,
  "data": {
    "url": "https://...m4a",
    "mimeType": "audio/mp4",
    "size": 1024000,
    "durationSeconds": 60
  }
}
```

---

## 5. 管控策略 (`/policies`)

### 5.1 时段限制
```
GET /policies/{deviceId}/time-restrictions    # 获取列表
PUT /policies/{deviceId}/time-restrictions    # 设置/更新
DELETE /policies/time-restrictions/{id}       # 删除
PUT /policies/{deviceId}/daily-limit          # 设置全局每日限制

TimeRestriction 对象:
{
  "id": "xxx",
  "deviceId": "dev_001",
  "dayOfWeek": 1,           // 1=周一 ... 7=周日
  "startTime": "08:00",
  "endTime": "18:00",
  "maxMinutes": 120,        // 该时段最大分钟数
  "isEnabled": true
}
```

### 5.2 应用管控
```
GET /policies/{deviceId}/apps                    # 已安装应用列表
PUT /policies/{deviceId}/apps/blacklist          # 更新黑名单
PUT /policies/{deviceId}/apps/limit              # 设置单应用时长限制

AppPolicy 对象:
{
  "packageName": "com.tencent.mm",
  "appName": "微信",
  "isBlocked": false,
  "dailyLimitMinutes": 60   // null=不限
}
```

### 5.3 上网管控
```
GET /policies/{deviceId}/web                     # 获取上网策略
PUT /policies/{deviceId}/web/urls               # 更新网址黑名单
PUT /policies/{deviceId}/web/browser            # 禁用/启用浏览器

WebPolicy 对象:
{
  "blockedUrls": ["game.com", "video.com"],
  "browserDisabled": false,
  "smartShutdownEnabled": true,
  "smartShutdownStartTime": "22:00",
  "smartShutdownEndTime": "06:00"
}
```

### 5.4 远程指令
```
POST /policies/{deviceId}/lock      # 一键锁屏
POST /policies/{deviceId}/mode      # 切换模式
# Request: { "mode": "NORMAL" | "LEARNING" | "FOCUS" }
```

### 5.5 策略模板
```
GET /policies/templates?sceneType=FAMILY   # 获取模板列表
POST /policies/{deviceId}/apply-template   # 应用模板
# Request: { "templateId": "tpl_001" }
```

---

## 6. 数据统计 (`/statistics`)

```
GET /statistics/{deviceId}/usage?period=daily|weekly|monthly
GET /statistics/{deviceId}/today
GET /statistics/{deviceId}/web?period=daily
GET /statistics/{deviceId}/violations?period=weekly
GET /statistics/{deviceId}/export?period=monthly   # 导出报告
```

---

## 7. 风险预警 (`/alerts`)

```
GET /alerts?deviceId=xxx&status=ACTIVE&page=0&size=20
GET /alerts/unread-count
POST /alerts/{alertId}/acknowledge    # 确认告警
POST /alerts/{alertId}/close         # 关闭告警
# Request Body (optional): { "reason": "已处理" }

Alert 对象:
{
  "id": "alert_001",
  "deviceId": "dev_001",
  "deviceName": "小明的平板",
  "level": "WARNING",          // INFO | WARNING | CRITICAL
  "title": "使用超时",
  "message": "今日使用时长已超过限制",
  "status": "ACTIVE",          // ACTIVE | ACKNOWLEDGED | RESOLVED | CLOSED
  "category": "TIME_LIMIT_EXCEEDED",
  "triggeredAt": 1693318400000
}
```

---

## 8. WebSocket 推送通道（实时事件）

服务端通过 WebSocket 向管控端推送实时事件：

| 事件类型 | 触发条件 | Payload |
|----------|---------|---------|
| `device.online` | 设备上线 | `{ deviceId, timestamp }` |
| `device.offline` | 设备离线 | `{ deviceId, lastSeen }` |
| `alert.new` | 新告警产生 | `{ alert }` |
| `screenshot.ready` | 截屏完成 | `{ screenshot }` |
| `usage.update` | 使用时长变更 | `{ deviceId, totalMinutes }` |

**连接地址**: `wss://api.padguard.com/ws?token=<jwt_token>`
