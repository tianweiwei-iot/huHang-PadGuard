# 护航管控 - 家长控制端 (PadGuard Parent)

## 项目概述

**护航管控**是一套平板设备管控系统（MDM）的家长/教师控制端 Android 应用，用于远程管理学生/孩子的平板设备。

### 核心定位
- **双场景支持**：家庭管控 + 校园教学
- **全维度管控**：使用时长、应用限制、上网过滤、远程监控、风险预警
- **实时可控**：截屏、拍照、录音、位置追踪、一键锁屏

## 技术栈

| 层级 | 技术选型 | 说明 |
|------|---------|------|
| 语言 | Kotlin 2.0 | 现代Android开发语言 |
| UI框架 | Jetpack Compose | 声明式UI，Material3设计 |
| 架构 | MVVM + Clean Architecture | Domain/Data/Presentation 三层分离 |
| DI | Hilt | Google推荐依赖注入 |
| 网络 | Retrofit2 + OkHttp4 + Moshi | RESTful API 客户端 |
| 本地存储 | DataStore Preferences | 轻量键值对存储（后续扩展Room） |
| 图片加载 | Coil | Compose图片加载库 |
| 导航 | Navigation Compose | 单Activity多Fragment导航 |
| 最低版本 | Android 8.0 (API 26) | 覆盖95%+设备 |
| 目标版本 | Android 15 (API 35) |

## 项目结构

```
护航控制端/
├── app/                          # 主应用模块
│   ├── src/main/
│   │   ├── java/com/padguard/parent/
│   │   │   ├── PadGuardApplication.kt    # Application入口
│   │   │   ├── di/                       # Hilt DI模块
│   │   │   ├── data/
│   │   │   │   ├── api/                  # Retrofit API接口定义
│   │   │   │   ├── model/                # 数据传输对象(DTO)
│   │   │   │   ├── repository/           # Repository实现
│   │   │   │   └── local/                # 本地数据源(Mock)
│   │   │   ├── domain/
│   │   │   │   ├── model/                # 领域模型
│   │   │   │   ├── repository/           # Repository接口
│   │   │   │   └── usecase/              # 用例(Interactors)
│   │   │   └── presentation/
│   │   │       ├── MainActivity.kt       # 单Activity入口
│   │   │       ├── ui/
│   │   │       │   ├── auth/             # 登录页(P0)
│   │   │       │   ├── home/             # 首页仪表盘
│   │   │       │   ├── device/           # 设备管理
│   │   │       │   ├── monitor/          # 实时监控(P1)
│   │   │       │   ├── control/          # 管控设置
│   │   │       │   ├── statistics/       # 数据统计
│   │   │       │   ├── alert/            # 风险预警
│   │   │       │   ├── profile/          # 个人中心
│   │   │       │   ├── components/       # 可复用组件
│   │   │       │   └── theme/            # 主题定义
│   │   │       └── viewmodel/           # ViewModel层
│   │   └── res/                         # 资源文件
│   └── build.gradle.kts
├── core/                         # 核心基础模块
│   ├── common/                   # 通用工具类
│   ├── network/                  # 网络层(Retrofit封装)
│   ├── database/                 # 数据库(Room)模块
│   └── ui/                       # 通用UI组件/主题
├── docs/                         # 项目文档
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts           # 项目设置
└── gradle.properties             # 全局属性
```

## 开源参考

本项目在架构设计和功能实现上参考了以下开源项目：

| 项目 | 参考内容 | GitHub |
|------|---------|--------|
| **MDMesh** | 整体架构(Kotlin Agent+FastAPI)、Device Owner策略执行、签名更新机制 | [obaidi2005/MDMesh](https://github.com/obaidi2005/MDMesh) |
| **Headwind MDM** | 设备注册(QR码)、Web控制台设计、插件化架构、MQTT推送 | [h-mdm/hmdm-server](https://github.com/h-mdm/hmdm-server) |
| **SecureGuard/A-Bloq** | Kotlin Device Owner实现、VPN防火墙、App挂起、Kiosk Launcher | [sesese1234/SecureGuardMDM](https://github.com/sesese1234/SecureGuardMDM) |
| **TripleU MDM** | 简洁的Device Policy Manager用法、Hosts过滤 | [kosherboynewb/TripleUMDM_Public](https://github.com/kosherboynewb/TripleUMDM_Public) |
| **Kids Shield** | 家长控制UI设计思路、能力检测机制 | [hackthesystm13/kids](https://github.com/hackthesystm13/kids) |

## 功能优先级（对应设计文档）

### P0 一期（当前已实现框架）
- [x] 账号登录（手机号+密码 / 手机号+短信验证码）
- [x] Token 本地缓存与自动刷新
- [x] 首页仪表盘（设备状态、使用时长概览）

### P1 二期（已定义接口，待完善UI）
- [ ] 实时屏幕监控（截图请求、历史查看）
- [ ] 远程拍照 / 录音
- [ ] 设备实时位置
- [ ] 字幕回看功能

### P2 三期（已规划）
- [ ] 子账号管理（教师/孩子账号CRUD）
- [ ] 家庭多成员协作
- [ ] 个人中心完善
- [ ] 消息推送集成

## 快速开始

### 前置要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17+
- Android SDK 35
- 一台 Android 8.0+ 设备或模拟器

### 运行步骤
```bash
# 1. 克隆项目
git clone <repo-url>
cd 护航控制端

# 2. 打开 Android Studio
# File -> Open -> 选择本目录

# 3. 同步 Gradle
# 等待 Gradle Sync 完成（首次需下载依赖，约5-10分钟）

# 4. 连接设备或启动模拟器
adb devices

# 5. 运行
# 点击 Run 'app' 或使用快捷键 Shift+F10
```

### Mock 数据模式
当前阶段使用 `LocalDataSource` 提供 Mock 数据，无需后端即可运行和调试 UI：
- 任意手机号 + 任意密码/验证码即可登录
- 预置了 2 台 Mock 设备（1台在线家庭设备 + 1台离线校园设备）
- 所有 API 调用返回模拟数据

## 模块说明

### core:network
网络层封装，提供：
- `NetworkModule` - Hilt单例，提供Retrofit + OkHttp实例
- 自动附加 Token 拦截器
- 日志拦截器（Debug模式输出完整请求/响应）
- 统一错误处理

### core:database
Room 数据库模块（预留），用于：
- 设备信息本地缓存
- 离线日志存储
- 用户偏好设置持久化

### core:ui
通用UI组件和主题：
- Material3 主题定制（护眼蓝主色调）
- 可复用卡片、列表项、状态指示器
- 通用加载/空状态/错误状态组件

## API 接口规范

所有API遵循 RESTful 规范，详见 [API接口定义.md](./API接口定义.md)

**基础路径**: `/v1/`
**鉴权方式**: Bearer Token (JWT)
**响应格式**: 
```json
{
  "code": 0,
  "message": "success",
  "data": { ... },
  "timestamp": 1693318400000
}
```

## 安全考虑

- 所有通信强制 HTTPS（生产环境）
- Token 使用 JWT，支持 Refresh Token 无感刷新
- 敏感操作（锁屏、擦除数据）需二次确认
- 日志中不记录敏感信息（密码、Token等）
