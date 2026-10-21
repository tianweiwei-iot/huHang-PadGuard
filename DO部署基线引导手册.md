# PadGuard 孩子端 Device Owner（DO）部署基线引导手册

> 文档定位：L0 部署基线 / 开发计划 P0-2「DO 部署产品化」落地交付物之一
> 配套脚本：`scripts/do-provisioning.ps1`（Windows 一键 `dpm set-device-owner`）
> 适用对象：实施人员、家长（自助部署）、校园批量部署负责人
> 最后更新：2026-10-21

---

## 一、为什么必须做 Device Owner（DO）

PadGuard 的管控深度来自 **Android Device Owner + Kiosk** 技术路线（对标 MDM / 教育专用设备管控，而非普通家长 App）。

在「非 DO」状态下，孩子端只是普通设备管理员（Device Admin）或仅靠无障碍服务运行，存在**可被用户绕过的物理逃逸面**：

| 能力 | 非 DO（普通应用/设备管理员） | DO（设备所有者） |
|---|---|---|
| 卸载拦截 | 需家长授权码，仍可能被「设置→应用」强行卸载 | **系统级 `setUninstallBlocked(true)`，无需授权码即硬拦** |
| 「停用设备管理员」入口 | 设置中可见，可手动解除 | **设置中不再出现该入口** |
| Kiosk 锁屏 | 无法消除桌面这一逃逸目标 | 可从物理上锁死桌面，仅留管控界面 |
| 防恢复出厂 / 防多用户 / 禁 USB 调试 | 弱 | 系统级强管控 |
| 防卸载的「数学成立性」 | 不成立（总有路径绕过） | **唯一成立路径** |

**结论**：「不可卸载」在数学上唯一成立的路径就是 DO。未做 DO 部署，前面所有锁屏/应用管控的加固都存在一个可被「卸载 App」一击瓦解的缺口。因此 DO 部署是上线前的**强制基线**，不是可选项。

---

## 二、激活前准备（前置条件，务必确认）

DO 只能在设备**接近出厂态**时设置一次，失败最常见原因就是前置态不干净：

1. **未设置锁屏密码**：DO 设置前请勿设置任何屏幕锁密码；设置完成后再按需设置。
2. **未登录非出厂账号**：已登录 Google / 厂商账号会阻止 DO，需先退出或移除。
3. **未安装孩子端 APK**：DO 必须作用于「已安装且非系统核心」的包，请先安装孩子端。
4. **USB 调试已开启**：方式一（adb）需要；方式二/三（NFC/QR）不需要。
5. **仅一台目标设备**：批量场景请用方式二/三，单机调试一次只连一台。

> 注意：DO 一旦设置，**只能经「恢复出厂设置」彻底移除**。下面的「撤销」仅用于调试期临时解除，生产机请直接恢复出厂。

---

## 三、方式一：adb `dpm set-device-owner`（单机最可靠，推荐首推）

使用配套脚本 `scripts/do-provisioning.ps1`（Windows PowerShell）。

### 3.1 默认参数（Debug 构建）

- 包名 `Package`：`com.padguard.child.debug`
- 接收器 `Receiver`：`com.padguard.child.debug.receiver.PadGuardDeviceAdminReceiver`
  （即 `AndroidManifest.xml` 中 `android:name=".receiver.PadGuardDeviceAdminReceiver"`，按 namespace `com.padguard.child` + debug flavor `.debug` 解析得到）

> Release 构建包名与接收器路径会不同，请按实际 flavor 调整 `-Package` / `-Receiver` 参数。

### 3.2 执行

```powershell
# 管理员身份打开 PowerShell，cd 到仓库根目录
.\scripts\do-provisioning.ps1
# 或显式指定（参数与默认一致，便于复制）
.\scripts\do-provisioning.ps1 -Package com.padguard.child.debug -Receiver com.padguard.child.debug.receiver.PadGuardDeviceAdminReceiver
```

脚本会自动完成：探测 adb → 校验单设备连接 → 校验已安装 → 提示前置态 → 执行 `dpm set-device-owner` → `dpm list-owners` 验证。

### 3.3 调试期撤销（非生产）

```powershell
.\scripts\do-provisioning.ps1 -Revert
```

仅解除设备管理员态，用于调试；**生产机请改用恢复出厂设置**彻底移除 DO。

---

## 四、方式二：NFC Provisioning（批量出厂推荐）

适合厂商预装 / 校园批量。通过 NFC 标签触发设备开机向导中的「设备所有者配置」。

要点：

- 在一台「已清空 / 恢复出厂」的设备开机向导界面，将预写入 provisioning 数据的 NFC 标签贴近设备背部 NFC 区域。
- NFC 数据需包含：`EXTRA_PROVISIONING_DEVICE_ADMIN_PACKAGE_NAME = com.padguard.child.debug`、对应的 `DEVICE_ADMIN_COMPONENT_NAME`、以及服务端绑定所需的 `EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE`（如绑定码 / 服务器地址）。
- 触发后系统自动下载并设为 DO，进入 PadGuard 绑定流程。

> 实施侧需用一个独立工具（或现成 NFC provisioning APP）把上述 extras 烧录到标签。本仓库暂不内置烧录工具，标签内容由部署负责人按服务端下发参数填写。

---

## 五、方式三：QR Provisioning（批量出厂推荐，无需 NFC 硬件）

适合无 NFC 模组的设备。设备开机向导中选择「扫码配置」，扫描含 provisioning JSON 的二维码。

二维码内容（示例，字段以服务端实际下发为准）：

```json
{
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.padguard.child.debug/com.padguard.child.debug.receiver.PadGuardDeviceAdminReceiver",
  "android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION": "https://<server>/apk/padguard-child.apk",
  "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": {
    "serverUrl": "https://<server>",
    "bindCode": "<由服务端下发的绑定码>"
  }
}
```

流程：开机向导 → 连 Wi-Fi → 选择「扫码 / QR 配置」→ 扫描上述二维码 → 系统自动下载 APK 并设为 DO → 进入绑定。

---

## 六、验证（三种方式任一）

1. **adb 验证**：`adb shell dpm list-owners`，输出应包含 `com.padguard.child.debug`。
2. **设置验证**：系统「设置 → 安全 → 设备管理员」中，PadGuard 不再提供「停用」入口；或「设置」中已无卸载该应用的正常路径。
3. **功能验证**：打开孩子端完成绑定；尝试在设置中卸载，应被系统拒绝。

---

## 七、撤销与回退

| 场景 | 操作 |
|---|---|
| 调试期临时解除 | `.\scripts\do-provisioning.ps1 -Revert`（仅移除设备管理员态） |
| 生产机彻底移除 DO | **恢复出厂设置**（系统级唯一路径） |
| 误设到错误设备 | 先 `-Revert` 解调试态，再对正确设备重新部署；生产环境必须恢复出厂 |

---

## 八、安全与隐私（强制约束）

1. **DO 权限极大**，仅应在用户明确知情并同意下激活；激活流程必须在孩子端 UI 中向家长明示「将获得系统级不可卸载管控」。
2. 部署脚本与文档**不收集、不上传**任何设备隐私数据；`-Revert` 仅调用系统 API。
3. 绑定码 / 服务器地址等敏感参数通过服务端安全下发，不以明文硬编码在二维码/标签中可被随意读取的位置（部署负责人须按服务端下发值填写）。
4. 激活后请参照《隐私政策》履行未成年人个人信息保护义务（数据最小化、加密存储、家长可导出/删除）。

---

## 九、常见问题排查

| 现象 | 原因 | 对策 |
|---|---|---|
| `dpm set-device-owner` 报错 | 已设锁屏密码 | 设置中移除密码后重试 |
| 同上 | 已登录非出厂账号 | 退出/移除该账号后重试 |
| 同上 | 已是普通设备管理员 | 先 `remove-active-admin` 再试 |
| 找不到 adb | SDK 未装 / 未加 PATH | 安装 Android 平台工具，或用 `-Adb` 指定路径 |
| 多台设备冲突 | 连了多台 | 只保留一台目标平板 |
| 设置成功但 list-owners 无本包 | 偶发时序 | 重新执行一次并手动 `dpm list-owners` 确认 |

---

## 十、与开发计划的对应关系

- 本手册 + `scripts/do-provisioning.ps1` 覆盖 **P0-2「DO 部署产品化」的「文档 + 命令行一键」部分**。
- 计划中还提到「孩子端 `ui/profile/PermissionGuideScreen.kt` 引导页」——**应用内可视化引导页尚未开发**，当前以本手册 + 脚本作为临时交付，后续可在孩子端增加图形化 DO 引导流程以进一步降低家长上手成本。
