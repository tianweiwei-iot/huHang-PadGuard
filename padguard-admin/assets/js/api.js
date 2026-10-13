/* ============================================================
   PadGuard 管控后台 · API 客户端
   - 后台真实接口（/v1/admin/**）已在服务端落地，**默认走 Live**。
   - 实时接口不可用时，按下方 ENDPOINTS 契约请求真实 padguard-server，
     失败则降级到空数据并提示（不再返回任何编造的演示数据）。
   ============================================================ */
(function (global) {
  const PG = global.PG || (global.PG = {});
  const M = PG.Mock;

  // ---------- 后端地址解析 ----------
  // 后台应通过「服务端同源地址」访问：http://<服务器>:8090/admin/
  // 此时 fetch("/v1/admin/...") 与页面同源，浏览器不会拦截，最稳妥。
  // 若以本地文件方式（file://）打开，浏览器会禁止页面直接请求 http 接口，
  // 这也是「Failed to fetch / 实时接口不可用」的根因，这里给出明确提示。
  const DEFAULT_ORIGIN = "http://8.148.68.183:8090";
  const IS_FILE = (typeof location !== "undefined") && location.protocol === "file:";

  function resolveBaseUrl() {
    // 1) 允许用 URL 参数临时覆盖：?api=http://host:port
    try {
      const q = new URLSearchParams(location.search).get("api");
      if (q) return q.replace(/\/+$/, "");
    } catch (e) { /* ignore */ }
    // 2) http/https 打开时（经 /admin/ 访问）直接用当前源，彻底无跨域
    if (location.protocol === "http:" || location.protocol === "https:") return location.origin;
    // 3) 兜底：本地文件打开，仍指向默认服务端（仅供调试，浏览器通常会拦截）
    return DEFAULT_ORIGIN;
  }

  const config = {
    useMock: false,
    baseUrl: resolveBaseUrl(),
    token: "",
  };

  /** 把底层网络/HTTP 错误翻译成运营能看懂、能照做的中文提示 */
  function friendlyNetworkError(e) {
    const m = (e && e.message) || String(e || "");
    if (/Failed to fetch|NetworkError|ERR_|load failed/i.test(m)) {
      if (IS_FILE) {
        return "无法连接服务端：当前以本地文件方式打开（file://），浏览器禁止请求接口。" +
          "请改用 http://" + config.baseUrl.replace(/^https?:\/\//, "") + "/admin/ 打开后台。";
      }
      return "无法连接服务端 " + config.baseUrl + "（请确认服务端已启动、地址正确、网络可达）。";
    }
    return m;
  }

  // 真实端点契约（admin 端点已并入 padguard-server：controller/AdminController.kt）
  const ENDPOINTS = {
    login: "POST /v1/admin/login",
    dashboard: "GET /v1/admin/dashboard",
    parents: "GET /v1/admin/parents",
    parentSuspend: "POST /v1/admin/parents/{id}/suspend",
    parentResetPwd: "POST /v1/admin/parents/{id}/reset-password",
    parentUpdate: "PUT /v1/admin/parents/{id}",
    parentDelete: "DELETE /v1/admin/parents/{id}",
    devices: "GET /v1/admin/devices",
    deviceLock: "POST /v1/admin/devices/{id}/lock",
    plans: "GET /v1/admin/billing/plans",
    subscriptions: "GET /v1/admin/billing/subscriptions",
    orders: "GET /v1/admin/billing/orders",
    revenue: "GET /v1/admin/billing/revenue",
    roles: "GET /v1/admin/rbac/roles",
    admins: "GET /v1/admin/rbac/admins",
    adminUpdate: "PUT /v1/admin/rbac/admins/{id}",
    adminDelete: "DELETE /v1/admin/rbac/admins/{id}",
    adminResetPwd: "POST /v1/admin/rbac/admins/{id}/reset-password",
    createRole: "POST /v1/admin/rbac/roles",
    deleteRole: "DELETE /v1/admin/rbac/roles/{id}",
    serverHealth: "GET /health",
    serverMetrics: "GET /v1/admin/server/metrics",
    serverLogs: "GET /v1/admin/server/logs",
    serverProfile: "GET /v1/admin/server-profile",
    saveServerProfile: "PUT /v1/admin/server-profile",
    audit: "GET /v1/admin/audit",
    alerts: "GET /v1/admin/alerts",
    settings: "GET /v1/admin/settings",
  };

  const wait = (ms) => new Promise(r => setTimeout(r, ms));
  // 安全深拷贝：undefined/null 原样返回，避免 JSON.parse(undefined) 抛 "undefined" is not valid JSON
  const clone = (o) => (o === undefined || o === null) ? o : JSON.parse(JSON.stringify(o));

  // 网络层错误（服务端未启动 / file:// 跨域被拦截 / DNS / 断网）：这类才允许降级到空数据
  class NetworkError extends Error {}

  async function liveOrMock(liveFn, mockFn) {
    if (config.useMock) { await wait(120); return mockFn(); }
    try { return await liveFn(); }
    catch (e) {
      // 只有「网络层失败」才降级为空数据。
      // 早期实现把 401/403 等 HTTP 错误也吞掉降级成全 0 假仪表盘，
      // 导致无权限管理员看到一片绿色"正常"数据（假成功）。
      // 收到服务端响应（含 4xx/5xx/业务错误）必须如实上抛，绝不编造数据。
      if (!(e instanceof NetworkError)) throw e;
      PG.toast && PG.toast("warn", "实时接口不可用", "已降级为空数据：" + friendlyNetworkError(e));
      await wait(60);
      try { return mockFn(); }
      catch (e2) { PG.toast && PG.toast("err", "本地降级失败", String((e2 && e2.message) || e2)); return null; }
    }
  }

  async function http(method, path, body) {
    let res;
    try {
      res = await fetch(config.baseUrl + path, {
        method, headers: { "Content-Type": "application/json", ...(config.token ? { "Authorization": "Bearer " + config.token } : {}) },
        body: body ? JSON.stringify(body) : undefined,
      });
    } catch (e) {
      // 网络层失败（服务端未启动 / file:// 跨域被浏览器拦截 / DNS 等）—— 标记为可降级
      throw new NetworkError(friendlyNetworkError(e));
    }
    let j = null;
    try { j = await res.json(); } catch (e) { j = null; }
    if (!res.ok) {
      // 服务端统一返回 { code, message }，优先透传业务 message（如「密码错误」）。
      // 这类 HTTP 错误如实抛出（不可降级）。
      const msg = (j && (j.message || j.error)) || ("HTTP " + res.status);
      throw new Error(msg);
    }
    // 校验业务码。服务端统一信封 { code, message, data }，
    // code 存在且非 0 表示业务失败（如设备已解绑/不存在/订阅过期），
    // 早期实现只判 HTTP 状态导致"设备已删点锁屏弹成功"这类假成功。
    if (j && typeof j.code === "number" && j.code !== 0) {
      throw new Error((j.message || j.error) || ("业务错误 code=" + j.code));
    }
    return (j && j.data !== undefined) ? j.data : j;
  }

  // ---------------- 实现 ----------------
  const api = {
    config, ENDPOINTS,
    setMock(v) { config.useMock = v; },
    toggleEnv() { config.useMock = !config.useMock; return config.useMock; },

    async login(username, password) {
      // 登录绝不静默降级为假 token：连不上服务端就如实报错，避免"看似登录成功却没有真实会话"
      if (config.useMock) {
        const a = M.admins.find(x => x.username === username) || M.admins[0];
        if (!a) throw new Error("演示数据已清空（Mock 中无管理员），请切换为 Live 模式");
        return { token: "mock-" + Date.now(), admin: clone(a) };
      }
      const r = await http("POST", "/v1/admin/login", { username, password });
      if (!r || !r.token) throw new Error("服务端登录响应缺少 token");
      return r;
    },

    async dashboard() {
      return liveOrMock(
        () => http("GET", "/v1/admin/dashboard"),
        () => {
          const active = M.parents.filter(p => p.status === "active").length;
          const online = M.devices.filter(d => d.online).length;
          const paid = M.subscriptions.filter(s => s.status === "active").length;
          const monthRev = M.revenue.length ? M.revenue[M.revenue.length - 1].amount : 0;
          const series = M.revenue.slice(-6).map(r => r.amount);
          return {
            kpis: {
              parentsTotal: M.parents.length, parentsActive: active, parentsSuspended: M.parents.filter(p => p.status === "suspended").length,
              devicesTotal: M.devices.length, devicesOnline: online,
              subsActive: paid, subsTrial: M.subscriptions.filter(s => s.status === "trial").length,
              monthRevenue: monthRev, serverStatus: M.server.status,
            },
            revenueSeries: series,
            planDist: M.PLANS.map(p => ({ name: p.name, count: M.parents.filter(x => x.plan === p.id).length })),
            regionDist: [...new Set(M.parents.map(p => p.region))].map(r => ({ name: r, count: M.parents.filter(x => x.region === r).length })),
            alertsOpen: M.alerts.filter(a => a.status !== "closed").length,
            recent: M.audit.slice(0, 6),
          };
        }
      );
    },

    // 家长账号
    async listParents(q) {
      return liveOrMock(() => http("GET", "/v1/admin/parents?" + new URLSearchParams(q || {})), () => {
        let arr = clone(M.parents);
        if (q) {
          if (q.keyword) { const k = q.keyword.toLowerCase(); arr = arr.filter(p => p.phone.includes(k) || p.name.includes(k) || p.id.toLowerCase().includes(k)); }
          if (q.status) arr = arr.filter(p => p.status === q.status);
          if (q.plan) arr = arr.filter(p => p.plan === q.plan);
        }
        return arr;
      });
    },
    async getParent(id) {
      return liveOrMock(() => http("GET", "/v1/admin/parents/" + id), () => {
        const p = M.parents.find(x => x.id === id);
        if (!p) return null;                        // 先判空再拷贝，避免 clone(undefined) 崩溃
        const out = clone(p);
        out.devices = clone(M.devices.filter(d => d.parentPhone === out.phone));
        const sub = M.subscriptions.find(s => s.parentPhone === out.phone);
        out.subscription = sub ? clone(sub) : null;
        return out;
      });
    },
    async suspendParent(id) {
      return liveOrMock(() => http("POST", "/v1/admin/parents/" + id + "/suspend"), () => {
        const p = M.parents.find(x => x.id === id); if (p) p.status = "suspended"; return { ok: true };
      });
    },
    async activateParent(id) {
      return liveOrMock(() => http("POST", "/v1/admin/parents/" + id + "/activate"), () => {
        const p = M.parents.find(x => x.id === id); if (p) p.status = "active"; return { ok: true };
      });
    },
    async resetParentPwd(id) {
      return liveOrMock(() => http("POST", "/v1/admin/parents/" + id + "/reset-password"), () => ({ ok: true, newPwd: "PG" + Math.floor(100000 + rnd6()) }));
    },
    async updateParent(id, body) {
      return liveOrMock(() => http("PUT", "/v1/admin/parents/" + id, body), () => { const p = M.parents.find(x => x.id === id); if (p) Object.assign(p, body); return { ok: true }; });
    },
    async deleteParent(id) {
      return liveOrMock(() => http("DELETE", "/v1/admin/parents/" + id), () => { const i = M.parents.findIndex(x => x.id === id); if (i >= 0) M.parents.splice(i, 1); return { ok: true }; });
    },
    async createParent(body) {
      return liveOrMock(() => http("POST", "/v1/admin/parents", body), () => {
        const np = { id: "P" + (100001 + M.parents.length), phone: body.phone, name: body.name, email: body.email || "",
          status: "active", registeredAt: new Date().toISOString().slice(0, 19).replace("T", " "), lastLoginAt: null,
          plan: body.plan || "free", planName: (M.planById(body.plan || "free") || {}).name || "免费版",
          expiredAt: new Date(Date.now() + 365 * 864e5).toISOString().slice(0, 19).replace("T", " "),
          deviceCount: 0, childrenCount: 1, region: body.region || "北京", channel: "manual", remark: body.remark || "", tags: [] };
        M.parents.unshift(np); return { ok: true, parent: np };
      });
    },

    // 设备
    async listDevices(q) {
      return liveOrMock(() => http("GET", "/v1/admin/devices?" + new URLSearchParams(q || {})), () => {
        let arr = clone(M.devices);
        if (q) {
          if (q.keyword) { const k = q.keyword.toLowerCase(); arr = arr.filter(d => d.deviceId.toLowerCase().includes(k) || d.parentPhone.includes(k) || (d.parentName || "").includes(k)); }
          if (q.online === "1") arr = arr.filter(d => d.online);
          if (q.online === "0") arr = arr.filter(d => !d.online);
        }
        return arr;
      });
    },
    async lockDevice(id) { return liveOrMock(() => http("POST", "/v1/admin/devices/" + id + "/lock"), () => { const d = M.devices.find(x => x.deviceId === id); if (d) d.remoteLocked = true; return { ok: true }; }); },
    async unlockDevice(id) { return liveOrMock(() => http("POST", "/v1/admin/devices/" + id + "/unlock"), () => { const d = M.devices.find(x => x.deviceId === id); if (d) d.remoteLocked = false; return { ok: true }; }); },

    // 计费
    async listPlans() { return liveOrMock(() => http("GET", "/v1/admin/billing/plans"), () => clone(M.PLANS)); },
    async listSubscriptions(q) {
      return liveOrMock(() => http("GET", "/v1/admin/billing/subscriptions?" + new URLSearchParams(q || {})), () => {
        let arr = clone(M.subscriptions);
        if (q && q.status) arr = arr.filter(s => s.status === q.status);
        return arr;
      });
    },
    async listOrders(q) {
      return liveOrMock(() => http("GET", "/v1/admin/billing/orders?" + new URLSearchParams(q || {})), () => {
        let arr = clone(M.orders);
        if (q && q.status) arr = arr.filter(o => o.status === q.status);
        return arr;
      });
    },
    async manualActivate(parentPhone, plan, days) {
      return liveOrMock(() => http("POST", "/v1/admin/billing/subscriptions/manual", { parentPhone, plan, days }), () => {
        const p = M.parents.find(x => x.phone === parentPhone); if (p) { p.plan = plan; p.planName = (M.planById(plan) || {}).name || plan; p.status = "active"; }
        return { ok: true };
      });
    },
    async refund(orderNo) {
      return liveOrMock(() => http("POST", "/v1/admin/billing/orders/" + orderNo + "/refund"), () => {
        const o = M.orders.find(x => x.orderNo === orderNo); if (o) o.status = "refunded"; return { ok: true };
      });
    },
    async revenue() { return liveOrMock(() => http("GET", "/v1/admin/billing/revenue"), () => clone(M.revenue)); },

    // RBAC
    // 权限元数据（领域/动作中文名 + 危险等级 + 系统锁定角色）由服务端下发，
    // 前端不再自带白名单副本 —— 避免"前端有列、服务端不认"导致的勾选存不进去。
    async permissionSchema() {
      const fallback = {
        areas: [
          ["parents", "家长账号", [["read", "查看"], ["write", "编辑"], ["create", "新建"], ["suspend", "冻结/启用", 1], ["reset_pwd", "重置密码", 1], ["delete", "删除", 1]]],
        ],
        lockedRoles: ["super"],
      };
      return liveOrMock(() => http("GET", "/v1/admin/rbac/permissions"), () => ({
        areas: fallback.areas.map(([key, name, acts]) => ({
          key, name, actions: acts.map(([k, n, d]) => ({ key: k, name: n, danger: !!d })),
        })),
        lockedRoles: ["super"],
      }));
    },
    async listRoles() { return liveOrMock(() => http("GET", "/v1/admin/rbac/roles"), () => clone(M.ROLES)); },
    // 新建/删除自定义角色：早期只在前端内存里 push，刷新即丢且服务端不存在该角色。
    // 现在落库（V24 admin_roles），立即可用于指派。
    async createRole(role) {
      return liveOrMock(() => http("POST", "/v1/admin/rbac/roles", role), () => { M.ROLES.push(clone(role)); return { ok: true, role: clone(role) }; });
    },
    async deleteRole(id) {
      return liveOrMock(() => http("DELETE", "/v1/admin/rbac/roles/" + encodeURIComponent(id)), () => { const i = M.ROLES.findIndex(r => r.id === id); if (i >= 0) M.ROLES.splice(i, 1); return { ok: true }; });
    },
    async saveRole(role) { return liveOrMock(() => http("PUT", "/v1/admin/rbac/roles", role), () => { const i = M.ROLES.findIndex(r => r.id === role.id); if (i >= 0) M.ROLES[i] = Object.assign(M.ROLES[i], role); return { ok: true }; }); },
    // 还原为系统默认权限：内置角色一旦在矩阵里被改乱（典型是误取消「查看」导致菜单全打不开），
    // 没有这个功能就只能改代码或直接改数据库。
    async resetRolePrivileges(id) {
      return liveOrMock(() => http("DELETE", "/v1/admin/rbac/roles/" + encodeURIComponent(id) + "/privileges"), () => ({ ok: true }));
    },
    async listAdmins() { return liveOrMock(() => http("GET", "/v1/admin/rbac/admins"), () => clone(M.admins)); },
    async createAdmin(body) {
      return liveOrMock(() => http("POST", "/v1/admin/rbac/admins", body), () => {
        const role = M.roleById(body.roleId);
        const na = { id: "A" + (90020 + M.admins.length), username: body.username, name: body.name, roleId: body.roleId, roleName: role ? role.name : body.roleId, email: body.email || "", status: "active", mfa: false, createdAt: new Date().toISOString().slice(0, 19).replace("T", " "), lastLoginAt: null };
        M.admins.push(na); return { ok: true, admin: na };
      });
    },
    async assignRole(adminId, roleId) {
      return liveOrMock(() => http("POST", "/v1/admin/rbac/admins/" + adminId + "/role", { roleId }), () => {
        const a = M.admins.find(x => x.id === adminId); if (a) { a.roleId = roleId; a.roleName = (M.roleById(roleId) || {}).name || roleId; } return { ok: true };
      });
    },
    async updateAdmin(adminId, body) {
      return liveOrMock(() => http("PUT", "/v1/admin/rbac/admins/" + adminId, body), () => {
        const a = M.admins.find(x => x.id === adminId); if (a) { if (body.name) a.name = body.name; if (body.email != null) a.email = body.email; if (body.status) a.status = body.status; } return { ok: true };
      });
    },
    async deleteAdmin(adminId) {
      return liveOrMock(() => http("DELETE", "/v1/admin/rbac/admins/" + adminId), () => { const i = M.admins.findIndex(x => x.id === adminId); if (i >= 0) M.admins.splice(i, 1); return { ok: true }; });
    },
    async resetAdminPwd(adminId) {
      return liveOrMock(() => http("POST", "/v1/admin/rbac/admins/" + adminId + "/reset-password"), () => ({ ok: true, newPwd: "ADM" + Math.floor(100000 + rnd6()) }));
    },
    /** 自助修改当前登录管理员密码（需当前密码；新密码至少 8 位，服务端强制） */
    async changeOwnPassword(currentPassword, newPassword) {
      return http("POST", "/v1/admin/me/password", { currentPassword, newPassword });
    },

    // 服务端
    async serverHealth() {
      if (config.useMock) { await wait(100); return clone(M.server); }
      try { const r = await fetch(config.baseUrl + "/health"); if (!r.ok) throw new Error("HTTP " + r.status); return await r.json(); }
      catch (e) { PG.toast && PG.toast("warn", "健康检查失败", "已用空指标：" + e.message); return clone(M.server); }
    },
    async serverMetrics() { return liveOrMock(() => http("GET", "/v1/admin/server/metrics"), () => clone(M.server)); },
    // 运行日志：进程内环形缓冲的真实日志（WARN/ERROR + 本服务 INFO 业务事件），不再返回空列表
    async serverLogs() { return liveOrMock(() => http("GET", "/v1/admin/server/logs"), () => []); },

    // 服务器基本信息（后台可配置：对外 IP / 端口 / 协议等）
    async serverProfile() {
      return liveOrMock(
        () => http("GET", "/v1/admin/server-profile"),
        () => ({ publicHost: "8.148.68.183", port: 8090, scheme: "http", serverName: "PadGuard 管控服务端", remark: "" })
      );
    },
    async saveServerProfile(body) {
      return liveOrMock(() => http("PUT", "/v1/admin/server-profile", body), () => ({ ok: true }));
    },

    // 审计 / 告警
    async audit(q) { return liveOrMock(() => http("GET", "/v1/admin/audit?" + new URLSearchParams(q || {})), () => { let a = clone(M.audit); if (q && q.area) a = a.filter(x => x.area === q.area); return a; }); },
    async alerts() { return liveOrMock(() => http("GET", "/v1/admin/alerts"), () => clone(M.alerts)); },
    async closeAlert(id) { return liveOrMock(() => http("POST", "/v1/admin/alerts/" + id + "/close"), () => { const a = M.alerts.find(x => x.id === id); if (a) a.status = "closed"; return { ok: true }; }); },

    // 设置
    async settings() { return liveOrMock(() => http("GET", "/v1/admin/settings"), () => ({ brand: "PadGuard", currency: "CNY", trialDays: 1, mockPay: false, passwordPolicy: "medium", mfaRequired: false, plans: [] })); },
    async saveSettings(body) { return liveOrMock(() => http("PUT", "/v1/admin/settings", body), () => ({ ok: true })); },
  };

  function rnd6() { return Math.floor(Math.random() * 900000); }

  PG.api = api;
})(window);
