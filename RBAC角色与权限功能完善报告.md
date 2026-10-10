# 角色与权限（RBAC）功能完善报告

> 目标：让权限矩阵**真正可设置、真正生效**，而不是一份只能看的配置表。
> 验证环境：padguard-server 8090 实例（H2），14 项接口断言 + 全流程渲染冒烟测试全部通过。

---

## 一、结论先行

原实现的 RBAC **只有外壳**：5 个角色、7 个领域、27 个动作都列了出来，但存在四类致命缺陷，导致"看得到、改不动、配了也不生效"。已全部修复并实测。

| # | 缺陷 | 表现 | 性质 |
|---|---|---|---|
| 1 | 内置角色矩阵全部置灰 | 运营/客服/财务/审计四个最常用角色只能看不能改 | 功能被前端白白锁死 |
| 2 | `monitor` 是幽灵领域 | 矩阵有整列，服务端从未登记，勾选后静默丢弃 | 配了不生效 |
| 3 | `read` 类权限从不校验 | 全系统 16 个鉴权点里 0 个 read，"只读角色"不存在 | 越权风险 + 配置无效 |
| 4 | 点击角色卡片明细变空白 | 容器重建后仍向已丢弃的旧节点写内容 | DOM 孤儿 bug |

**顺带发现并修复**：服务端拒绝时返回 **HTTP 200**，前端据此弹出"保存成功"，而实际一个字节都没写入 —— 这是"改了半天没效果"最隐蔽的来源。

---

## 二、逐项根因与修复

### 1. 内置角色不可编辑 —— 「不可设置」的直接原因

**根因**：前端 `rbac.js` 用 `role.isSystem` 判定是否禁用复选框：

```js
role.isSystem ? '<span class="badge gray">系统角色不可编辑</span>' : ...
(locked ? "disabled" : "")
```

而 `AdminRoles.ROLES` 里 five 个角色全是 `isSystem = true`，于是四张矩阵全部变成只读展示。
服务端 `saveRolePrivileges()` 其实**本来就支持**保存内置角色（仅拒绝 super）—— 是前端把有用的能力拦死了。

**修复**：引入 `LOCKED_ROLES` 明确区分两个概念：

- `isSystem` = 系统**内置**（随版本发布，不可删除）
- `LOCKED_ROLES` = 系统**锁定**（权限不可编辑，目前仅 `super`）

理由：super 是系统最后的破窗账号，其全量权限由代码固定。一旦允许收窄，超管会自锁、后台再无人能恢复。除它之外，内置角色的**可调整性正是 RBAC 的意义**。

该名单通过权限元数据下发前端，不再是前端硬编码，将来调整无需改两处。

### 2. `monitor` 幽灵领域 —— 「配了不生效」

**根因**：前端自带的权限清单里有一整个 `monitor` 领域（查看画面/截屏/锁屏/解锁），
服务端 `AdminRoles.AREAS` 从未登记。`saveRolePrivileges` 里的 `sanitize()` 会**静默丢弃未知领域**，
于是勾选存不进去、刷新后恢复原样，且没有任何报错。

**取证**：全仓检索显示 `/v1/monitor/**` 全部属于**家长端**（家长查看自己绑定的设备），
后台 `padguard-admin` 根本没有 monitor 视图；设备在运营侧的管控已由 `devices` 的
`lock / unlock` 覆盖。

**决策**：**移除该领域**，而不是补端点凑数。
保留一个无端点支撑的权限项，等于把"配了不生效"换个形式重演。

**根治手段**：新增 `GET /v1/admin/rbac/permissions`，把领域/动作的中文名、危险等级、锁定角色
统一下发；前端矩阵改为完全由服务端数据驱动，**不再自带白名单副本**。
今后新增权限只需改服务端一处，前端矩阵自动出现对应行列。

### 3. `read` 权限从不校验 —— 最严重的一条

**取证**：全仓 `adminPerm.require(...)` 共 16 处，其中 **`read` 出现 0 次**。

后果有两层，都很严重：

- **越权**：cs / finance / audit 在服务端毫无区别。任何能进后台的管理员都能拉取全部数据，
  包括营收报表、家长手机号、审计日志。"只读角色"名存实亡。
- **配置无效**：矩阵里给角色勾/不勾「查看」，服务端行为完全一样。

**修复**：新增统一读闸门 `AdminService.requireRead(adminId, area)`，
15 个列表/详情方法在首行调用：

| 领域 | 受保护接口 |
|---|---|
| parents | 家长列表、家长详情 |
| devices | 设备列表 |
| billing | 套餐、订阅、订单、营收 |
| rbac | 权限元数据、角色列表、管理员列表 |
| server | 运行指标、运行日志 |
| audit | 审计日志、风险告警 |
| settings | 系统设置 |

**两处关键细节**：

- 仪表盘内部的 `audit(null)` 必须改为 `if (adminPerm.can(adminId,"audit","read")) audit(adminId,null)`
  —— 否则首页会把这道闸门从旁路掏空，没有审计权限的人照样在首页看到操作流。
- `saveSettings` 保存后回读走 `settingsInternal()`（不带闸门）。那里已通过更严格的
  `settings:write`，不应在回读环节被 read 二次卡住。

**明确取舍**：`/dashboard` 自身**不加**闸门。它是所有管理员的默认落地页，
若要求某领域 read 才能打开，会造成"进得去后台却看不到任何东西"的空首页。

### 4. 点击角色卡片明细变空白

**根因**（DOM 孤儿）：

```js
cards.querySelectorAll("[data-role]").forEach(c => c.onclick = () => {
  renderRoles(root);        // 内部 el.innerHTML = '...<div id="r-detail"></div>' 重建容器
  drawDetail(detail, ...);  // detail 指向已被替换掉的旧节点 → 内容写进了空气
});
```

`renderRoles` 重建了包含 `#r-detail` 的容器，而外部闭包仍持有替换前的旧节点引用。
现象是点击任意角色卡片，明细区永远空白。

**修复**：壳 HTML 一次性建立 `#r-roles` 与 `#r-detail` 两个**平级独立容器**，
后续各自局部 `innerHTML` 重绘，互不干扰。

### 5. 顺带修复：PARAM_ERROR 返回 HTTP 200

服务端校验其实**已经生效**（返回了 `{"code":1001,"message":"配置了写权限但未配置「查看」权限…"}`），
但 HTTP 状态码是 **200**。前端 `api.js` 的 `http()` 用 `res.ok` 判定成败：

```js
if (!res.ok) throw new Error(msg);
return (j && j.data !== undefined) ? j.data : j;   // 200 → 认为成功
```

于是 `saveRole` 返回 `data: null` 却被视为成功，页面弹出 **"权限已保存并生效"**，
而事实上一项权限都没存。这正是"改了半天没反应"最隐蔽的来源。

**修复**：`GlobalExceptionHandler.handleBiz` 增加
`ParentErr.PARAM_ERROR -> HttpStatus.BAD_REQUEST`。

已确认前端全仓**没有任何**读取业务 `code` 的代码（`grep '\.code'` 无结果），
改动是纯修复、无副作用。

---

## 三、新增能力

| 能力 | 端点 | 解决的问题 |
|---|---|---|
| 权限元数据下发 | `GET /v1/admin/rbac/permissions` | 前后端白名单漂移的根治手段 |
| 还原默认权限 | `DELETE /v1/admin/rbac/roles/{id}/privileges` | 内置角色被改乱后可自助回滚（此前只能改代码/改库） |
| 角色成员计数 | `listRoles()` 增加 `memberCount` | 删除前即可预判"还有 N 名管理员"，而非等服务端拒绝 |
| 已调整标记 | `listRoles()` 增加 `overridden` | 一眼看出哪些角色偏离了系统默认 |
| 保存前置校验 | `requireReadWhenHavingWrites` | 拦截"有写无读"的矛盾配置 |

**"有写无读"为什么要在保存时拦**：写接口（如 `parents:suspend`）不校验 `parents:read`，
若只勾写不勾读，运营会陷入"列表打不开、但直接调接口又能成功"的状态。
把矛盾挡在配置阶段，而不是让用户运行阶段困惑。

**前端矩阵同时做了易用性重构**：

- 布局改为「领域为行、动作为列」，且每个领域只渲染其合法动作 —— 原来 13 行 × 8 列
  的有效格子不到 30%，大量空洞
- 行级「全选 / 清空」、全局「一键只读 / 全选」
- 未保存修改标记 + 切换角色时提示放弃
- 高危动作（删除/退款/解绑/冻结/重置密码）在表头加粗标注
- 新增「权限总览」Tab：角色 × 领域热力对比，悬停查看具体已授予动作
- 默认选中第一个**可编辑**角色（不再一进页面就看到 super 的满屏灰色）
- 新建角色支持设置可读标识 + 从「全领域只读」或「复制现有角色」起步

---

## 四、验证证据

### 4.1 接口端到端（`tools/verify_rbac.sh`，14/14 通过）

**决定性证据 —— read 闸门真正生效**：
新建仅授予 `billing:read` 的角色并指派给管理员，以该管理员身份访问：

| 请求 | 期望 | 实际 |
|---|---|---|
| `GET /v1/admin/parents` | 403 | **403** |
| `GET /v1/admin/billing/orders` | 200 | **200** |
| `GET /v1/admin/settings` | 403 | **403** |
| `GET /v1/admin/dashboard` | 200 | **200** |

其余：内置角色 cs 权限由 `read` 成功改为 `read,write`；编辑 super 返回 403；
"有写无读"返回 **400** 并给出明确中文提示；还原默认后 `read,write` 回退为 `read`、
`overridden` 标记复位。

### 4.2 前端渲染冒烟（`tools/smoke_rbac.js`）

以最小 DOM 沙箱加载 `rbac.js`，**打真实服务端**跑完整条渲染链路：

```
[OK] mount() 渲染通过（默认=矩阵页）
[OK] 渲染出 5 张角色卡片
--- 逐个角色切换明细，统计不可编辑(disabled)的复选框数 ---
    super      复选框=26   disabled=26  → 系统锁定
    ops        复选框=26   disabled=0   → 可编辑
    cs         复选框=26   disabled=0   → 可编辑
    finance    复选框=26   disabled=0   → 可编辑
    audit      复选框=26   disabled=0   → 可编辑
[OK] 4 个角色可编辑、1 个系统锁定（预期仅 super）
[OK] Tab matrix / overview / admins 渲染通过
```

逐个点击卡片验证明细切换，直接覆盖了旧版「点了之后明细变空白」的回归场景。

---

## 五、改动文件清单

**服务端（padguard-server）**

| 文件 | 改动 |
|---|---|
| `domain/AdminModels.kt` | 新增 `ActionDef/AreaDef` 元数据、`LOCKED_ROLES`；移除 monitor 领域 |
| `security/AdminPermissionService.kt` | `permissionSchema()`、`resetRolePrivileges()`、`memberCount`、`overridden`、`requireReadWhenHavingWrites` |
| `service/AdminService.kt` | `requireRead()` 闸门，15 个读方法的签名与内部调用；`permissionSchema`、`resetRolePrivileges` |
| `controller/AdminController.kt` | 新增 2 个端点，15 处调用同步 |
| `common/Core.kt` | `PARAM_ERROR -> 400` |

**后台前端（padguard-admin）**

| 文件 | 改动 |
|---|---|
| `views/rbac.js` | 整体重写（矩阵布局、放开编辑、容器拆分、总览页、dirty 提示） |
| `api.js` | 新增 `permissionSchema()`、`resetRolePrivileges()` |

---

## 六、需要关注的影响

1. **现有管理员的访问范围会按配置收紧**。此前任何管理员都能看所有数据；
   现在严格按矩阵执行。上线后建议逐一复核各管理员角色：若某角色未授予某领域 `read`，
   该管理员将**无法打开对应页面**（返回 403）。这是修复的预期行为，但需提前核对业务预期。
2. **`dashboard` 不做领域级闸门**（所有管理员的默认落地页），
   但其内部的"最近操作"区块已按 `audit:read` 裁剪。
3. **`monitor` 领域已移除**。若确需在后台查看设备实时画面，应作为新功能立项：
   新增后台端点 + 视图 + RBAC 领域三件套，而不是先在矩阵里挂一列空权限。

---

## 七、快速验证命令

```bash
# 接口层：14 项断言
bash padguard-server/tools/verify_rbac.sh

# 前端层：完整渲染链路冒烟（需服务端已在 8090 运行）
node padguard-server/tools/smoke_rbac.js
```
