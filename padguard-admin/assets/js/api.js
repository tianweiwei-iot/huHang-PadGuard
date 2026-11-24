/* ============================================================
   PadGuard 管控后台 · API 客户端
   - 后台真实接口（/v1/admin/**）已在服务端落地，**默认走 Live**。
   - 网络层不可用时降级为空数据并如实提示（不再返回任何编造的演示数据）；
     收到服务端响应的 4xx/5xx 一律上抛，绝不降级成假数据。
   ============================================================ */
(function (global) {
  const PG = global.PG || (global.PG = {});
  const M = PG.Mock;

  // ---------- 后端地址解析 ----------
  // 后台应通过「服务端同源地址」访问：http(s)://<服务器>:8090/admin/
  // 此时 fetch("/v1/admin/...") 与页面同源，浏览器不会拦截，最稳妥。
  // 安全策略（第八轮评估 P0-1）：
  // 1) 不再内置任何硬编码生产地址——公网 IP 明文 HTTP 等于把管理员凭据裸奔，
  //    且仓库里带生产 IP 会在代码泄漏时直接暴露攻击面；
  // 2) ?api= 覆盖参数加白名单：同源、内网/本机 http 源
  //    （192.168/10/172.16-31/localhost），以及 file:// 打开时的 https 源
  //    ——防止诱导链接把后台指向恶意服务器窃取凭据。
  const IS_FILE = (typeof location !== "undefined") && location.protocol === "file:";

  /**
   * 内网/本机主机名判定。
   * 必须用 URL 解析后的 hostname 做**完整匹配**：旧实现对整条 URL 用前缀正则
   * /^(http:\/\/(localhost|127\.|10\.|192\.168\.)...)/ 测试，
   * `http://192.168.evil.com`、`http://10.evil.com`、`http://localhost.evil.com`
   * 都会命中并被当成内网放行 —— 而 resolveBaseUrl() 随后把管理员账号口令 POST 到该域名，
   * 注释里承诺的「防止诱导链接把后台指向恶意服务器窃取凭据」等于没做。
   */
  function isPrivateHost(hostname) {
    const h = String(hostname || "").toLowerCase().replace(/^\[|\]$/g, "");
    if (h === "localhost" || h === "::1") return true;
    // IPv6 唯一本地 fc00::/7、链路本地 fe80::/10（旧实现的 `http://[` 前缀放行了任意 IPv6）
    if (/^f[cd][0-9a-f]{2}:/.test(h) || /^fe[89ab][0-9a-f]:/.test(h)) return true;
    const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
    if (!m) return false;
    const parts = m.slice(1).map(Number);
    if (parts.some(n => n > 255)) return false;
    const a = parts[0], b = parts[1];
    return a === 127 || a === 10 || (a === 192 && b === 168) || (a === 172 && b >= 16 && b <= 31);
  }

  function allowedOverride(url) {
    try {
      const u = new URL(url);
      // 同源：无风险（就是当前后台自己）。
      if (u.origin === location.origin) return true;
      // 内网/本机 http：家庭与内网部署的常见形态。
      if (u.protocol === "http:" && isPrivateHost(u.hostname)) return true;
      // file:// 打开（无同源可比）时才允许 https：否则任意 https 都放行，
      // 一条 http://真实后台/?api=https://attacker.example 的钓鱼链接就能把
      // 管理员口令 POST 到攻击者服务器（注释声称的"白名单防诱导"实际只挡住了 http）。
      if (IS_FILE && u.protocol === "https:") return true;
    } catch (e) { /* 非法 URL 一律拒绝 */ }
    return false;
  }

  function resolveBaseUrl() {
    // 1) 允许用 URL 参数临时覆盖：?api=https://host（https 任意；http 仅限内网/本机）
    try {
      const q = new URLSearchParams(location.search).get("api");
      if (q) {
        if (allowedOverride(q)) return q.replace(/\/+$/, "");
        console && console.warn && console.warn("[PadGuard] ?api= 已拒绝非白名单地址（仅允许 https 或内网 http）");
      }
    } catch (e) { /* ignore */ }
    // 2) http/https 打开时（经 /admin/ 访问）直接用当前源，彻底无跨域
    if (location.protocol === "http:" || location.protocol === "https:") return location.origin;
    // 3) 兜底：本地文件打开时不预设任何公网地址，由部署者显式以 ?api= 指定（白名单校验同上）
    return "";
  }

  const config = {
    useMock: false,
    baseUrl: resolveBaseUrl(),
    token: "",
    /**
     * `?api=` 覆盖后的跨源目标（null = 同源/未覆盖，无需确认）。
     *
     * 钓鱼链可把地址栏保持在合法后台，却让登录表单把管理员口令与后续 Bearer token
     * POST 到攻击者控制的**内网 http** 主机（allowedOverride 对私网 http 是放行的）。
     * 这里记录目标 origin，由登录入口强制显式确认后才发凭据。
     */
    overrideTarget: (() => {
      try {
        const q = new URLSearchParams(location.search).get("api");
        if (!q || !allowedOverride(q)) return null;
        const u = new URL(q);
        if (!IS_FILE && u.origin === location.origin) return null;
        return u.origin;
      } catch (e) { return null; }
    })(),
    overrideConfirmed: false,
  };

  /** 单次请求超时（毫秒）。见 http() 注释。 */
  const REQUEST_TIMEOUT_MS = 20000;

  /** 服务端没给具体原因时，按状态码补一句中文（绝不让 "HTTP 401" 这类英文上屏）。 */
  function httpStatusHint(status) {
    if (status === 400) return "请求参数有误，请检查后重试";
    if (status === 401) return "登录未通过，请检查账号和密码后重试";
    if (status === 403) return "没有权限执行该操作";
    if (status === 404) return "请求的服务端接口不存在，请检查服务器地址是否正确";
    if (status === 429) return "操作过于频繁，请稍后再试";
    if (status >= 500) return "服务端内部错误，请稍后重试或联系管理员";
    return "服务端返回错误（状态码 " + status + "），请稍后重试";
  }

  /** 把底层网络/HTTP 错误翻译成运营能看懂、能照做的中文提示 */
  function friendlyNetworkError(e) {
    const m = (e && e.message) || String(e || "");
    if (/Failed to fetch|NetworkError|ERR_|load failed|timeout|timed out|aborted/i.test(m)) {
      if (IS_FILE) {
        return "无法连接服务端：当前以本地文件方式打开（file://），浏览器禁止请求接口。" +
          "请改用 http://" + config.baseUrl.replace(/^https?:\/\//, "") + "/admin/ 打开后台。";
      }
      return "无法连接服务端 " + config.baseUrl + "（请确认服务端已启动、地址正确、网络可达）。";
    }
    // 服务端/前端自己抛的中文原因（密码错误、管理员不存在、账号已停用…）原样透传；
    // 其余英文原文一律收敛成中文，避免 fetch 的英文异常直接弹给管理员。
    if (/[\u4e00-\u9fa5]/.test(m)) return m;
    return "无法连接服务端 " + config.baseUrl + "（请确认服务端已启动、地址正确、网络可达）。";
  }

  /**
   * 面向界面的统一错误文案出口：保证中文，且尽量保留服务端给的具体原因。
   * 登录失败时服务端已返回「密码错误」「管理员不存在」「管理员账号已停用」等中文；
   * 连不上服务端 / 超时 / 英文兜底则在这里补成中文。
   */
  function explainError(e) {
    const m = (e && e.message) || String(e || "");
    if (/[\u4e00-\u9fa5]/.test(m)) return m;
    if (/Failed to fetch|NetworkError|ERR_|load failed|timeout|timed out|aborted|refused|resolve/i.test(m)) {
      return friendlyNetworkError(e);
    }
    if (/^HTTP\s+\d+/i.test(m)) {
      const status = parseInt((m.match(/\d+/) || [0])[0], 10);
      return httpStatusHint(status);
    }
    // 英文原文留日志供排障，界面只给中文兜底。
    if (m && console && console.warn) console.warn("[PadGuard] 未翻译的错误原文：", m);
    return "操作失败，请稍后重试或联系管理员";
  }
  PG.explainError = explainError;

  // 端点契约：各方法自带的 `http(method, path)` 调用即为唯一事实源，
  // admin 端点已并入 padguard-server（controller/AdminController.kt）。
  //
  // 这里原先维护过一张 `ENDPOINTS` 常量表，但它**从未被任何代码读取** ——
  // 每个方法仍然各写各的字面量路径。结果就是：新增端点时改了方法、忘了改表，
  // 表里 10 多条记录长期过期（缺 audit/areas、logout、rbac 多数端点）。
  // 一张没人读的"契约表"比没有更危险：它会让人误以为端点清单就这些。故直接删除。

  const wait = (ms) => new Promise(r => setTimeout(r, ms));
  // 安全深拷贝：undefined/null 原样返回，避免 JSON.parse(undefined) 抛 "undefined" is not valid JSON
  const clone = (o) => (o === undefined || o === null) ? o : JSON.parse(JSON.stringify(o));

  // 网络层错误（服务端未启动 / file:// 跨域被拦截 / DNS / 断网）：这类才允许降级到空数据
  class NetworkError extends Error {}

  /**
   * 构造查询串：**剔除值为 undefined/null/空串的键**。
   *
   * 直接 `new URLSearchParams({status: undefined})` 会把 undefined ToString 成字面量
   * "status=undefined" 发给服务端 —— 服务端 `isNullOrBlank()` 挡不住它，按字符串过滤
   * 必然命中 0 行（表现为"全部状态"下订阅/订单页永远为空，且 Mock 路径完全正常、
   * 只有 Live 才炸）。收口在这一处，杜绝复发。
   */
  function qs(params) {
    const p = Object.assign({}, params || {});
    Object.keys(p).forEach(k => {
      if (p[k] === undefined || p[k] === null || p[k] === "") delete p[k];
    });
    return new URLSearchParams(p).toString();
  }

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

  /**
   * 写操作的唯一出口：**绝不在网络失败时降级**。
   *
   * liveOrMock 的降级语义只适用于只读看板（网络断了给空数据 + 明确 toast）。
   * 写操作（冻结/删除家长、退款、改角色、重置密码、解绑设备…）的 mockFn 一律返回
   * { ok: true }；若照抄 liveOrMock，断网/超时会被当成"操作成功"，管理员据此回复家长
   * "已处理"，而服务端什么都没发生。这类假成功比直接报错危险得多。
   */
  async function liveWrite(liveFn, mockFn) {
    if (config.useMock) { await wait(120); return mockFn(); }
    return liveFn();
  }

  async function http(method, path, body) {
    // 统一超时：fetch 本身没有超时，服务端线程池打满/反代挂起时请求会永远挂着，
    // 各页面就永久停在"加载中…"，运营无法区分"卡死"和"慢"。
    const ctrl = typeof AbortController !== "undefined" ? new AbortController() : null;
    const timer = ctrl ? setTimeout(() => { try { ctrl.abort(); } catch (e) {} }, REQUEST_TIMEOUT_MS) : null;
    // 捕获"发这条请求时"的令牌代次：401 返回时 config.token 可能已被另一个会话替换
    // （旧会话在途请求 → 用户登出 → 立刻用新账号登录 → 旧请求才返回 401）。
    // 若不做这道比对，旧会话的迟到 401 会清空新账号的 token、把人踢回登录页。
    const sentToken = config.token;
    let res;
    try {
      res = await fetch(config.baseUrl + path, {
        method, headers: { "Content-Type": "application/json", ...(sentToken ? { "Authorization": "Bearer " + sentToken } : {}) },
        body: body ? JSON.stringify(body) : undefined,
        signal: ctrl ? ctrl.signal : undefined,
      });
    } catch (e) {
      // 网络层失败（服务端未启动 / file:// 跨域被浏览器拦截 / DNS / 超时）—— 标记为可降级
      if (e && (e.name === "AbortError" || /aborted/i.test(String(e.message)))) {
        throw new NetworkError("请求超时（" + Math.round(REQUEST_TIMEOUT_MS / 1000) + "s 无响应）：" + path);
      }
      throw new NetworkError(friendlyNetworkError(e));
    } finally {
      if (timer) clearTimeout(timer);
    }
    let j = null;
    try { j = await res.json(); } catch (e) { j = null; }
    if (!res.ok) {
      // 服务端统一返回 { code, message }，优先透传业务 message（如「密码错误」）。
      // 这类 HTTP 错误如实抛出（不可降级）；服务端没给原因时补中文状态码提示，不用英文 "HTTP xxx"。
      const msg = (j && (j.message || j.error)) || httpStatusHint(res.status);
      // 401 = 令牌过期/被吊销。此前被当普通错误抛给各调用方：管理员困在坏页面，
      // 每个动作弹一次 "HTTP 401"，永远回不到登录屏（唯一的出路是手动刷新）。
      // 这里做唯一出口：清本地令牌 + 通知外壳回登录。只对"带着令牌发出去的请求"生效，
      // 避免登录接口本身密码错误（同为 401）时反而把人踢来踢去。
      // 只有"发请求时的令牌"仍是当前令牌，才判定本会话过期。令牌被替换说明这是旧会话的
      // 迟到响应，既不能清空新 token，也不能把新会话踢回登录页（见上方 sentToken 注释）。
      if (res.status === 401 && config.token && config.token === sentToken) {
        config.token = "";
        if (typeof PG !== "undefined" && typeof PG.onUnauthorized === "function") {
          try { PG.onUnauthorized(msg); } catch (e) { /* 兜底：外壳异常不影响错误上抛 */ }
        }
      }
      throw new Error(msg);
    }
    // 校验业务码。服务端统一信封 { code, message, data }，
    // code 存在且非 0 表示业务失败（如设备已解绑/不存在/订阅过期），
    // 早期实现只判 HTTP 状态导致"设备已删点锁屏弹成功"这类假成功。
    if (j && typeof j.code === "number" && j.code !== 0) {
      throw new Error((j.message || j.error) || ("操作未被服务端接受，请稍后重试（错误码 " + j.code + "）"));
    }
    return (j && j.data !== undefined) ? j.data : j;
  }

  // ---------------- 实现 ----------------
  const api = {
    config,
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

    /**
     * 退出登录：请求服务端吊销当前令牌（admins.token_version +1）。
     *
     * 只清浏览器本地 token 是不够的 —— JWT 过期前始终有效，被复制走的令牌仍能操作后台。
     *
     * 容错策略（刻意宽松）：本方法**不抛错**，只返回吊销是否成功。
     * 退出是"让用户离开"的操作，任何原因（网络断开、令牌其实早已过期返回 401）
     * 都不该把人卡在退出这一步。调用方拿到 revoked=false 时如实提示即可 ——
     * 本地态照清，只是令牌要等到自然过期才失效，这比"退出按钮点了没反应"安全得多。
     */
    async logout() {
      if (config.useMock) return { revoked: false, reason: "mock" };
      try {
        const r = await http("POST", "/v1/admin/logout", {});
        return { revoked: !!(r && r.revoked), reason: null };
      } catch (e) {
        // 401 表示令牌已被吊销或已过期 —— 目标状态本就是"未登录"，按成功处理
        const m = String((e && e.message) || e);
        if (/重新登录|失效|HTTP 401/i.test(m)) return { revoked: true, reason: "already_invalid" };
        return { revoked: false, reason: m };
      }
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

    // 家长账号（B3/B4：支持 page/size 分页，返回 PageResult 信封）
    async listParents(q) {
      const opt = q || {};
      const params = Object.assign({ page: 1, size: 20 }, opt);
      return liveOrMock(
        () => http("GET", "/v1/admin/parents?" + qs(params)),
        () => {
          let arr = clone(M.parents);
          if (q) {
            if (q.keyword) { const k = q.keyword.toLowerCase(); arr = arr.filter(p => p.phone.includes(k) || p.name.includes(k) || p.id.toLowerCase().includes(k)); }
            if (q.status) arr = arr.filter(p => p.status === q.status);
            if (q.plan) arr = arr.filter(p => p.plan === q.plan);
          }
          const size = params.size, page = params.page;
          const total = arr.length;
          const totalPages = total === 0 ? 1 : Math.ceil(total / size);
          const from = (page - 1) * size;
          return { items: arr.slice(from, from + size), page, size, total, totalPages };
        }
      );
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
      return liveWrite(() => http("POST", "/v1/admin/parents/" + id + "/suspend"), () => {
        const p = M.parents.find(x => x.id === id); if (p) p.status = "suspended"; return { ok: true };
      });
    },
    async activateParent(id) {
      return liveWrite(() => http("POST", "/v1/admin/parents/" + id + "/activate"), () => {
        const p = M.parents.find(x => x.id === id); if (p) p.status = "active"; return { ok: true };
      });
    },
    async resetParentPwd(id) {
      return liveWrite(() => http("POST", "/v1/admin/parents/" + id + "/reset-password"), () => ({ ok: true, newPwd: "PG" + Math.floor(100000 + rnd6()) }));
    },
    async updateParent(id, body) {
      return liveWrite(() => http("PUT", "/v1/admin/parents/" + id, body), () => { const p = M.parents.find(x => x.id === id); if (p) Object.assign(p, body); return { ok: true }; });
    },
    async deleteParent(id) {
      return liveWrite(() => http("DELETE", "/v1/admin/parents/" + id), () => { const i = M.parents.findIndex(x => x.id === id); if (i >= 0) M.parents.splice(i, 1); return { ok: true }; });
    },
    /**
     * 注销家长账号（不可逆）：服务端会逐字比对 confirmPhone，仅超级管理员可执行。
     * 与普通删除分开：注销会解绑设备、脱敏订单归属，并在同一事务里清理所有关联表。
     */
    async eraseParent(id, confirmPhone) {
      return liveWrite(() => http("POST", "/v1/admin/parents/" + id + "/erase", { confirmPhone }), () => ({ ok: true }));
    },
    async createParent(body) {
      return liveWrite(() => http("POST", "/v1/admin/parents", body), () => {
        const np = { id: "P" + (100001 + M.parents.length), phone: body.phone, name: body.name, email: body.email || "",
          status: "active", registeredAt: new Date().toISOString().slice(0, 19).replace("T", " "), lastLoginAt: null,
          plan: body.plan || "free", planName: (M.planById(body.plan || "free") || {}).name || "免费版",
          expiredAt: new Date(Date.now() + 365 * 864e5).toISOString().slice(0, 19).replace("T", " "),
          deviceCount: 0, childrenCount: 1, region: body.region || "北京", channel: "manual", remark: body.remark || "", tags: [] };
        M.parents.unshift(np); return { ok: true, parent: np };
      });
    },

    // 设备（B3/B4：支持 page/size 分页，返回 PageResult 信封）
    async listDevices(q) {
      const opt = q || {};
      const params = Object.assign({ page: 1, size: 20 }, opt);
      return liveOrMock(
        () => http("GET", "/v1/admin/devices?" + qs(params)),
        () => {
          let arr = clone(M.devices);
          if (q) {
            if (q.keyword) { const k = q.keyword.toLowerCase(); arr = arr.filter(d => d.deviceId.toLowerCase().includes(k) || d.parentPhone.includes(k) || (d.parentName || "").includes(k)); }
            if (q.online === "1") arr = arr.filter(d => d.online);
            if (q.online === "0") arr = arr.filter(d => !d.online);
          }
          const size = params.size, page = params.page;
          const total = arr.length;
          const totalPages = total === 0 ? 1 : Math.ceil(total / size);
          const from = (page - 1) * size;
          return { items: arr.slice(from, from + size), page, size, total, totalPages };
        }
      );
    },
    async lockDevice(id) { return liveWrite(() => http("POST", "/v1/admin/devices/" + id + "/lock"), () => { const d = M.devices.find(x => x.deviceId === id); if (d) d.remoteLocked = true; return { ok: true }; }); },
    async unlockDevice(id) { return liveWrite(() => http("POST", "/v1/admin/devices/" + id + "/unlock"), () => { const d = M.devices.find(x => x.deviceId === id); if (d) d.remoteLocked = false; return { ok: true }; }); },
    // 设备解绑（B7）：清除归属 + 作废设备令牌，孩子端失去管控。危险操作，前端按 devices:unbind 隐藏。
    async unbindDevice(id) { return liveWrite(() => http("POST", "/v1/admin/devices/" + id + "/unbind"), () => { const d = M.devices.find(x => x.deviceId === id); if (d) { d.userId = null; d.parentName = ""; d.parentPhone = ""; } return { ok: true }; }); },

    // 计费
    async listPlans() { return liveOrMock(() => http("GET", "/v1/admin/billing/plans"), () => clone(M.PLANS)); },
    async listSubscriptions(q) {
      const opt = q || {};
      const params = Object.assign({ page: 1, size: 20 }, opt);
      return liveOrMock(
        () => http("GET", "/v1/admin/billing/subscriptions?" + qs(params)),
        () => {
          let arr = clone(M.subscriptions);
          if (q && q.status) arr = arr.filter(s => s.status === q.status);
          const size = params.size, page = params.page;
          const total = arr.length;
          const totalPages = total === 0 ? 1 : Math.ceil(total / size);
          const from = (page - 1) * size;
          return { items: arr.slice(from, from + size), page, size, total, totalPages };
        }
      );
    },
    async listOrders(q) {
      const opt = q || {};
      const params = Object.assign({ page: 1, size: 20 }, opt);
      return liveOrMock(
        () => http("GET", "/v1/admin/billing/orders?" + qs(params)),
        () => {
          let arr = clone(M.orders);
          if (q && q.status) arr = arr.filter(o => o.status === q.status);
          const size = params.size, page = params.page;
          const total = arr.length;
          const totalPages = total === 0 ? 1 : Math.ceil(total / size);
          const from = (page - 1) * size;
          return { items: arr.slice(from, from + size), page, size, total, totalPages };
        }
      );
    },
    async manualActivate(parentPhone, plan, days) {
      return liveWrite(() => http("POST", "/v1/admin/billing/subscriptions/manual", { parentPhone, plan, days }), () => {
        const p = M.parents.find(x => x.phone === parentPhone); if (p) { p.plan = plan; p.planName = (M.planById(plan) || {}).name || plan; p.status = "active"; }
        return { ok: true };
      });
    },
    async refund(orderNo) {
      return liveWrite(() => http("POST", "/v1/admin/billing/orders/" + orderNo + "/refund"), () => {
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
      return liveWrite(() => http("POST", "/v1/admin/rbac/roles", role), () => { M.ROLES.push(clone(role)); return { ok: true, role: clone(role) }; });
    },
    async deleteRole(id) {
      return liveWrite(() => http("DELETE", "/v1/admin/rbac/roles/" + encodeURIComponent(id)), () => { const i = M.ROLES.findIndex(r => r.id === id); if (i >= 0) M.ROLES.splice(i, 1); return { ok: true }; });
    },
    async saveRole(role) { return liveWrite(() => http("PUT", "/v1/admin/rbac/roles", role), () => { const i = M.ROLES.findIndex(r => r.id === role.id); if (i >= 0) M.ROLES[i] = Object.assign(M.ROLES[i], role); return { ok: true }; }); },
    // 还原为系统默认权限：内置角色一旦在矩阵里被改乱（典型是误取消「查看」导致菜单全打不开），
    // 没有这个功能就只能改代码或直接改数据库。
    async resetRolePrivileges(id) {
      return liveWrite(() => http("DELETE", "/v1/admin/rbac/roles/" + encodeURIComponent(id) + "/privileges"), () => ({ ok: true }));
    },
    async listAdmins() { return liveOrMock(() => http("GET", "/v1/admin/rbac/admins"), () => clone(M.admins)); },
    async createAdmin(body) {
      return liveWrite(() => http("POST", "/v1/admin/rbac/admins", body), () => {
        const role = M.roleById(body.roleId);
        const na = { id: "A" + (90020 + M.admins.length), username: body.username, name: body.name, roleId: body.roleId, roleName: role ? role.name : body.roleId, email: body.email || "", status: "active", mfa: false, createdAt: new Date().toISOString().slice(0, 19).replace("T", " "), lastLoginAt: null };
        M.admins.push(na); return { ok: true, admin: na };
      });
    },
    async assignRole(adminId, roleId) {
      return liveWrite(() => http("POST", "/v1/admin/rbac/admins/" + adminId + "/role", { roleId }), () => {
        const a = M.admins.find(x => x.id === adminId); if (a) { a.roleId = roleId; a.roleName = (M.roleById(roleId) || {}).name || roleId; } return { ok: true };
      });
    },
    async updateAdmin(adminId, body) {
      return liveWrite(() => http("PUT", "/v1/admin/rbac/admins/" + adminId, body), () => {
        const a = M.admins.find(x => x.id === adminId); if (a) { if (body.name) a.name = body.name; if (body.email != null) a.email = body.email; if (body.status) a.status = body.status; } return { ok: true };
      });
    },
    async deleteAdmin(adminId) {
      return liveWrite(() => http("DELETE", "/v1/admin/rbac/admins/" + adminId), () => { const i = M.admins.findIndex(x => x.id === adminId); if (i >= 0) M.admins.splice(i, 1); return { ok: true }; });
    },
    async resetAdminPwd(adminId) {
      return liveWrite(() => http("POST", "/v1/admin/rbac/admins/" + adminId + "/reset-password"), () => ({ ok: true, newPwd: "ADM" + Math.floor(100000 + rnd6()) }));
    },
    /** 自助修改当前登录管理员密码（需当前密码；新密码至少 8 位，服务端强制） */
    async changeOwnPassword(currentPassword, newPassword) {
      return http("POST", "/v1/admin/me/password", { currentPassword, newPassword });
    },

    // 服务端
    // U-09 已清理：serverHealth() 曾是零调用点的死代码，且用裸 fetch 绕过统一出口
    // （不带 token、不走 401 统一登出）；健康检查实际由 serverMetrics() 走统一 http 出口。
    async serverMetrics() { return liveOrMock(() => http("GET", "/v1/admin/server/metrics"), () => clone(M.server)); },
    // 运行日志：进程内环形缓冲的真实日志（WARN/ERROR + 本服务 INFO 业务事件），不再返回空列表
    async serverLogs() { return liveOrMock(() => http("GET", "/v1/admin/server/logs"), () => []); },

    /**
     * 清理 uploads/ 里的磁盘孤儿文件（盘上有、库里没有任何记录）。
     *
     * 默认 dryRun=true —— 后端也是同样的默认值，前端不做"直接删"的快捷方式：
     * 这个动作不可逆，必须先看到"有多少、都是些什么"再决定。
     */
    async sweepOrphans(graceDays, dryRun) {
      // dryRun=false 会真删文件：断网时降级成 { deleted: 0 } 会让运维以为"清理成功、没有孤儿"，
      // 因此同样走 liveWrite，失败如实上抛。
      return liveWrite(
        () => http("POST", "/v1/admin/server/storage/sweep-orphans?" + qs({ graceDays, dryRun })),
        () => ({ dryRun: dryRun !== false, graceDays: graceDays || 7, scanned: 0, deleted: 0,
                  bytesFreed: 0, samples: [], truncated: false, dbReadFailed: false })
      );
    },

    // 服务器基本信息（后台可配置：对外 IP / 端口 / 协议等）
    async serverProfile() {
      return liveOrMock(
        () => http("GET", "/v1/admin/server-profile"),
        () => ({ publicHost: "", port: 8090, scheme: "http", serverName: "PadGuard 管控服务端", remark: "" })
      );
    },
    async saveServerProfile(body) {
      return liveWrite(() => http("PUT", "/v1/admin/server-profile", body), () => ({ ok: true }));
    },

    // 审计 / 告警
    /**
     * 审计领域下拉项 [{key,name}]，取自服务端权限定义（含前端此前漏掉的 `audit` 领域）。
     *
     * 不使用 `liveOrMock`：这是**渲染下拉所需的元数据**，不是业务数据。
     * 取不到时上抛让调用方显示"— 无法加载筛选 —"，而不是拿一份残缺的本地副本糊过去
     * （那正是"下拉少一项"这个缺陷的成因）。
     */
    async auditAreas() { return http("GET", "/v1/admin/audit/areas"); },

    // 审计列表：真实接口返回分页对象 {items,truncated,maxRows,total}。
    // 降级路径**必须**返回同构对象，否则调用方拿到的数据结构会随网络状况漂移
    // （在线是对象、离线是数组），页面会被迫写两套分支 —— 这类形状不一致是线上事故的高发区。
    async audit(q) {
      return liveOrMock(
        () => http("GET", "/v1/admin/audit?" + qs(q)),
        () => {
          let a = clone(M.audit);
          if (q && q.area) a = a.filter((x) => x.area === q.area);
          return { items: a, truncated: false, maxRows: a.length, total: a.length };
        }
      );
    },
    async alerts() { return liveOrMock(() => http("GET", "/v1/admin/alerts"), () => clone(M.alerts)); },
    async closeAlert(id) { return liveWrite(() => http("POST", "/v1/admin/alerts/" + id + "/close"), () => { const a = M.alerts.find(x => x.id === id); if (a) a.status = "closed"; return { ok: true }; }); },

    // 设置
    async settings() { return liveOrMock(() => http("GET", "/v1/admin/settings"), () => ({ brand: "PadGuard", currency: "CNY", trialDays: 1, mockPay: false, passwordPolicy: "medium", mfaRequired: false, plans: [] })); },
    async saveSettings(body) { return liveWrite(() => http("PUT", "/v1/admin/settings", body), () => ({ ok: true })); },
  };

  function rnd6() { return Math.floor(Math.random() * 900000); }

  PG.api = api;
})(window);
