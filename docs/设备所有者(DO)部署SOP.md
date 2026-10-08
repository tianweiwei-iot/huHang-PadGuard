# 设备所有者（Device Owner）部署 SOP

> 适用范围：PadGuard 护航成长（被管控端）首次装机 / 批量部署
> 目标：单台设备 **10 分钟内**完成 DO 部署并通过验收
> 最后实测：联想 TB330FU（HA1Z5FPW，Android 14 / SDK 34）

---

## 0. 先讲清楚两件事

### 0.1 DO 是什么，为什么绕不过去

Device Owner（设备所有者）是 Android 为"企业管理设备"提供的最高权限身份。本项目下列核心能力**全部依赖它**，缺了就不是"功能弱一点"，而是直接不可用：

| 能力 | 依赖的 DO 接口 | 无 DO 时的表现 |
|---|---|---|
| 应用隐藏 / 停用 | `setApplicationHidden` / `setPackagesSuspended` | 只能弹窗求孩子放行 |
| 一键锁屏（LockTask 固定） | `startLockTask` | 无法真正锁定 |
| 禁止恢复出厂 / 安全模式 | `addUserRestriction` | 孩子长按电源键即可绕过 |
| 静默授予运行时权限 | `setPermissionGrantState` | 装机要点满一屏授权弹窗 |

**Android 不允许已安装应用把自己提升为 DO** —— 这是系统安全边界，必须走外部预置流程。所以"装机"这一步本质上就是：**先让设备认这个应用为 DO，再谈管控**。

### 0.2 防砖红线（务必先读）

设 DO 之后，本应用会成为设备的"半主导者"。有两条红线：

1. **必须有 `CATEGORY_HOME` 兜底**。DO 常配合 LockTask（锁定到单一应用）使用；一旦本应用的 HOME 入口失效而系统又无其它可用桌面，设备会停在黑屏/无限重启 —— 这就是"变砖"的真实成因。
   - 部署前先记录默认桌面：
     ```bash
     adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
     ```
   - 目标平板实测默认桌面：`com.zui.launcher/.drawer.NormalLauncher`（`isDefault=true`）。
   - **任何时候都不要把它从策略里排除（kiosk 白名单之外）**，它是唯一的逃生通道。
2. **不要在没有恢复路径的情况下加 `DISALLOW_FACTORY_RESET`**。未成年人模式下该限制会被强制加上（`SystemLockEnforcer`），此时恢复出厂的唯一入口是 adb：
   ```bash
   adb shell dpm remove-active-admin --user current com.padguard.child.debug/com.padguard.child.receiver.PadGuardDeviceAdminReceiver
   ```
   **这条命令要记在部署记录里**，不要等到要救砖时才现查。

---

## 1. 路径选择

| | 路径 A：adb 部署 | 路径 B：二维码部署 |
|---|---|---|
| 适用 | 调试、单台、已开封设备 | 量产、批量、全新设备 |
| 是否需恢复出厂 | **否** | 是（DO 只能在开机向导阶段用二维码设） |
| 前置 | 设备无账号 + 已安装 APK | 一台能放 APK 的 HTTP 服务 |
| 耗时 | 约 5 分钟 | 约 8 分钟（含恢复出厂） |

**绝大多数场景走路径 A。**路径 B 只在批量铺货时才值得准备。

---

## 2. 路径 A：adb 部署（推荐）

### 步骤 1 — 清账号（最容易漏、也最容易失败）

`dpm set-device-owner` 只要设备上存在**任何一个已添加账号**就会失败。平板到手第一件事往往是登录 Google / 厂商账号，所以这一步几乎必做：

设置 → 账号（或 密码与安全 → 账号同步）→ **移除全部账号**

> 失败报错：`Cannot set the device owner because the device has accounts`
> 这条报错不会提示"去删账号"，不看这里的话会原地重试很久。

### 步骤 2 — 安装并授予设备管理员

```bash
adb install -r E:/builds/padguard/app/outputs/apk/debug/app-debug.apk
```

在平板上打开本应用，进入权限引导页点「激活设备管理员」，按系统弹窗确认。
（也可以在引导页直接看下文步骤 3 的命令，界面上会原样展示。）

### 步骤 3 — 设为 Device Owner

```bash
adb shell dpm set-device-owner --user current com.padguard.child.debug/com.padguard.child.receiver.PadGuardDeviceAdminReceiver
```

`--user current` **不可省略**。平板上常有多个用户 profile（厂商"儿童空间"、工作资料等），`dpm` 默认作用于 user 0；部署到非 user 0 时，缺这个参数只会返回一句 `not allowed`，完全看不出是用户参数的问题。

### 步骤 4 — 验收

```bash
adb shell dumpsys device_policy | grep -A 3 "Device Owner"
```

应当看到：

```
Device Owner:
  admin=ComponentInfo{com.padguard.child.debug/com.padguard.child.receiver.PadGuardDeviceAdminReceiver}
  package=com.padguard.child.debug
  isOrganizationOwnedDevice=true
```

看到 `admin=` 有值即成功；**没有 Device Owner 段就说明没设上**，别往下走。

---

## 3. 路径 B：二维码部署（批量）

### 步骤 1 — 计算签名校验值

Android 要求二维码里带 APK 签名证书的 **SHA-256 的 URL-safe Base64（无填充）**。注意不是 hex，两者完全不同：

```bash
apksigner verify --print-certs app-debug.apk
# Signer #1 certificate SHA-256 digest: dd36a984...（这是 hex）
```

把 hex 转成 URL-safe Base64（去掉结尾 `=`）：

```python
import base64
h = "dd36a98482db867cb96e971b9101677795145462f8b9e27331c8155e3a60982d"
print(base64.urlsafe_b64encode(bytes.fromhex(h)).decode().rstrip("="))
# → 3TaphILbhny5bpcbkQFnd5UUVGL4ueJzMcgVXjpgmC0
```

> ⚠️ **这个值绑定构建机器**。debug keystore 每台开发机都不同，换机器打包必须重算；
> 正式发版用 release keystore，校验值要在发布流程里自动生成，不要手写。

### 步骤 2 — 生成二维码 JSON

把 APK 放到一个设备可访问的 HTTP 地址（局域网即可，例如 `http://192.168.1.10:8090/static/app-debug.apk`），然后生成二维码，内容为：

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME":
    "com.padguard.child.debug/com.padguard.child.receiver.PadGuardDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE": "com.padguard.child.debug",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION":
    "http://192.168.1.10:8090/static/app-debug.apk",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM":
    "3TaphILbhny5bpcbkQFnd5UUVGL4ueJzMcgVXjpgmC0",
  "android.app.extra.PROVISIONING_SKIP_ENCRYPTION": true,
  "android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED": true
}
```

`PACKAGE_DOWNLOAD_LOCATION` 与 `SIGNATURE_CHECKSUM` **缺一不可**：向导阶段设备上还没有本应用，系统要现场下载；缺校验值时 Android 会**直接拒绝安装并退回向导首页**，表现为"扫了码没反应"。

（`ProvisioningGuide.dpcQrJson(apkUrl, sha256Base64)` 会生成这段 JSON，界面上展示的是不带下载信息的精简版，仅适用于 APK 已预装的 adb 路径。）

### 步骤 3 — 扫码

1. 设备恢复出厂，进入开机向导；
2. 在欢迎页**连续点击 6~7 次**（部分 ROM 是长按音量键或点四角），进入二维码扫描模式；
3. 扫描上方二维码 → 自动下载 APK → 自动设为 DO。

---

## 4. 排障对照表

| 报错 / 现象 | 原因 | 处理 |
|---|---|---|
| `device has accounts` | 设备上有已添加账号 | 设置 → 账号，全部移除 |
| `already has a device owner` | 已有 DO，无法覆盖 | 先 `dpm remove-active-admin`，或恢复出厂 |
| `not allowed` | 缺 `--user current` / 非 shell 用户 | 补参数后重试 |
| `Unknown admin` | 应用未授予设备管理员 | 先在应用内点「激活设备管理员」 |
| `adb: no devices` | 未开 USB 调试 / 数据线仅充电 | 换线换口，确认授权弹窗已点允许 |
| 扫二维码没反应 | 缺 checksum 或下载地址不可达 | 核对 JSON 两个字段，用平板浏览器先访问 APK 地址验证 |
| DO 设上了但应用仍提示无权限 | 应用进程早于 DO 设置启动，缓存了旧状态 | 重启应用（不是重启设备） |

---

## 5. 部署记录模板

每台设备部署完请填一行，救砖时要用：

```
设备序列号：              型号 / 系统版本：
默认桌面（HOME 兜底）：
DO 组件：com.padguard.child.debug/com.padguard.child.receiver.PadGuardDeviceAdminReceiver
部署方式：adb / 二维码        部署日期：
移除命令（救砖用）：adb shell dpm remove-active-admin --user current com.padguard.child.debug/com.padguard.child.receiver.PadGuardDeviceAdminReceiver
```
