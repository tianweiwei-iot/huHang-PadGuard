/* ============================================================
   应用引导
   ============================================================ */
(function (PG) {
  PG.toast = PG.ui.toast; // api.js 依赖
  const $ = (id) => document.getElementById(id);

  // 安全存储封装：部分运行环境（如 file:// opaque origin）禁用 localStorage
  const store = {
    get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch (e) {} },
  };

  // search：顶栏全局搜索的关键字，由目标列表页 mount 时消费后清空，避免跨页残留
  const state = { admin: null, search: "" };
  PG.state = state;

  // 主题
  const savedTheme = store.get("pg-theme") || "dark";
  applyTheme(savedTheme);
  $("theme-btn").onclick = () => { const t = document.body.classList.contains("theme-dark") ? "light" : "dark"; applyTheme(t); };

  function applyTheme(t) {
    // 不用 body.className = ...：那会把 body 上的其它类（collapsed / 全局 loading 等）一并抹掉。
    document.body.classList.toggle("theme-dark", t === "dark");
    document.body.classList.toggle("theme-light", t !== "dark");
    store.set("pg-theme", t);
  }

  // 以本地文件方式打开时给出明确指引：浏览器会拦截对 http 服务端的跨域请求（表现为 Failed to fetch）
  if (location.protocol === "file:") {
    PG.ui.toast("warn", "建议改用服务端地址打开",
      "当前为本地文件方式（file://），接口可能无法访问。请用 http://服务器地址:8090/admin/ 打开本后台。");
  }

  // 折叠菜单
  $("nav-toggle").onclick = () => $("app-shell").classList.toggle("collapsed");

  // 环境切换（Mock / Live）
  $("env-switch").onclick = () => {
    const mock = PG.api.toggleEnv();
    const sw = $("env-switch");
    sw.dataset.on = mock ? "true" : "false";
    sw.querySelector("em").textContent = mock ? "Mock" : "Live";
    PG.ui.toast(mock ? "warn" : "ok", "数据源：" + (mock ? "演示数据（已清空）" : "真实服务端"),
      mock ? "演示数据已下线，开关仅用于调试，界面将显示为空" : "将请求 " + PG.api.config.baseUrl);
  };

  // 全局搜索 → 带关键字跳转到对应列表页（原实现丢弃了用户输入，是伪搜索）
  // 按关键字形态分流：手机号去家长页（家长页支持姓名/手机号），其余（设备ID/SN）去设备页
  // （设备页同时支持设备ID、家长手机号、家长名，是超集）。订单关键字后端无检索参数，不再承诺。
  $("global-search").addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    const kw = $("global-search").value.trim();
    if (!kw) return;
    // 关键字先落到全局状态，由目标页 mount 时回填到自己的搜索框，避免值在跳转中丢失
    PG.state.search = kw;
    PG.router.go(/^1\d{10}$/.test(kw) ? "parents" : "devices");
  });

  // 通知铃 → 审计告警
  $("bell-btn").onclick = () => PG.router.go("audit");

  // 登录
  $("login-form").onsubmit = async (e) => {
    e.preventDefault();
    const username = $("login-username").value.trim();
    const password = $("login-password").value;
    if (!username || !password) {
      PG.ui.toast("err", "请输入账号和密码", "账号与密码均为必填");
      return;
    }
    // ?api= 可把后台指向任意内网 http 主机；地址栏仍是合法域名，页面看起来完全正常，
    // 但登录会把管理员口令与后续 Bearer token 发给那台主机。发凭据前必须显式确认。
    const override = PG.api.config.overrideTarget;
    if (override && !PG.api.config.overrideConfirmed) {
      const ok = window.confirm(
        "安全确认：登录目标被 ?api= 参数覆盖为 " + override + "。\n" +
        "你的账号和密码将发送到该地址，而不是当前页面所在服务器。\n" +
        "请确认这是你信任的服务端；不确定请取消并联系管理员。"
      );
      if (!ok) return;
      PG.api.config.overrideConfirmed = true;
    }
    const btn = e.target.querySelector("button");
    btn.disabled = true; btn.textContent = "登录中…";
    try {
      const r = await PG.api.login(username, password);
      state.admin = r.admin;
      PG.api.config.token = r.token;
      enterApp();
    } catch (err) {
      // 统一走中文错误解析：服务端已给「密码错误 / 管理员不存在 / 账号已停用」等具体原因，
      // 连不上服务端、超时等情况也要给中文，绝不把 fetch 的英文异常原文弹给管理员。
      PG.ui.toast("err", "登录失败", PG.explainError(err));
    } finally { btn.disabled = false; btn.textContent = "登 录"; }
  };

  async function enterApp() {
    $("login-screen").classList.add("hidden");
    $("app-shell").classList.remove("hidden");
    const a = state.admin;
    // displayName 可空（AdminDto.name: String?）：直接渲染会显示字面量 "null"，回落用户名。
    $("user-name").textContent = a.name || a.username;
    $("user-role").textContent = a.roleName;
    $("user-avatar").textContent = (a.name || a.username || "A").charAt(0);
    PG.router.buildSidebar();
    // 落地页必须按权限挑：仪表盘要 server:read，客服/财务等只有 parents/devices/billing
    // 读权限的账号登录后会被直接扔到"没有访问权限"，看起来像账号没开通。
    if (!location.hash) {
      const nav = PG.router.NAV || [];
      let landing = null;
      nav.some(g => g.items.some(it => {
        if (PG.canRead(it.area)) { landing = it.k; return true; }
        return false;
      }));
      if (landing) {
        location.hash = landing;
      } else {
        // 一个可读菜单都没有：如实提示，而不是渲染一个 403 空壳
        PG.ui && PG.ui.toast && PG.ui.toast("err", "当前账号没有可访问的页面", "请联系超级管理员分配读权限");
      }
    } else {
      PG.router.render((location.hash || "#dashboard").slice(1));
    }
    // 未读告警角标必须走真实接口：
    // 去 mock 重构时把 PG.Mock.alerts 清空了，但这里漏改，导致角标恒为 0 —— 未读告警永远不提示。
    await refreshAlertBadge();
  }

  /**
   * 刷新未读告警角标。
   *
   * 取不到数据时不报错也不假装为 0，而是隐藏角标：
   * 显示一个确定的「0」会让管理员以为当前确实没有告警。
   */
  async function refreshAlertBadge() {
    const el = $("bell-count");
    if (!el) return;
    try {
      const list = await PG.api.alerts();
      const open = (list || []).filter(x => x.status !== "closed").length;
      el.textContent = open;
      el.style.display = open ? "block" : "none";
    } catch (err) {
      el.style.display = "none";
      console.warn("[padguard] load alerts failed:", err.message);
    }
  }
  PG.refreshAlertBadge = refreshAlertBadge;

  /**
   * 回到登录页：清理本地会话态并切换视图。
   *
   * 注意这里**不做**服务端吊销 —— 那是 `PG.api.logout()` 的职责。
   * 两者分开的原因：改密码场景下服务端已自行把令牌版本号 +1（旧令牌当场作废），
   * 再调一次 logout 只会拿到 401；退出登录场景才需要显式请求吊销。
   */
  function leaveToLogin() {
    PG.api.config.token = "";
    PG.state.admin = null;
    // 弹层 / 抽屉 / 提示挂在 #app-shell **之外**（见 index.html 的 modal-root），
    // 不会被下面那句 hidden 一起收掉。会话过期（401）时若正开着「家长详情」「重置密码」
    // 这类弹层，里面是真实的家长手机号与设备信息，会继续盖在登录屏上方 ——
    // 既是脏页面，也等于把上一个账号的数据留给了下一个用这台电脑的人。
    const modal = $("modal-root");
    if (modal) modal.innerHTML = "";
    const toast = $("toast-root");
    if (toast) toast.innerHTML = "";
    const view = $("view");
    if (view) view.innerHTML = "";
    // 换账号登录：hash 还停在上个账号的页面时，低权限管理员会直接落到「没有访问权限」，
    // 看起来像账号没开通。清掉 hash，让 enterApp 重新按权限挑落地页。
    // file:// 下部分浏览器禁止 replaceState，退化为直接清空 hash（留个 # 无害）。
    try {
      history.replaceState(null, "", location.pathname + location.search);
    } catch (e) {
      location.hash = "";
    }
    $("app-shell").classList.add("hidden");
    $("login-screen").classList.remove("hidden");
  }

  /**
   * 会话过期统一出口（由 api.js 的 http() 在收到 401 且带令牌时调用）。
   * 已在登录屏时不重复处理——并发请求会同时 401，重复弹提示只会变成一片猩红。
   */
  function onUnauthorized(reason) {
    const login = $("login-screen");
    if (login && !login.classList.contains("hidden")) return;
    leaveToLogin();
    PG.ui.toast("warn", "登录已过期", "请重新登录" + (reason ? "（" + reason + "）" : ""));
  }
  PG.onUnauthorized = onUnauthorized;

  // 用户菜单（修改密码 / 退出登录）
  (function setupUserMenu() {
    const chip = $("user-chip");
    if (!chip) return;
    let menu = null;
    chip.style.cursor = "pointer";
    chip.onclick = (e) => {
      e.stopPropagation();
      if (menu) { menu.remove(); menu = null; return; }
      const rect = chip.getBoundingClientRect();
      menu = document.createElement("div");
      menu.style.cssText =
        "position:fixed;z-index:9999;right:" + (window.innerWidth - rect.right) + "px;top:" + (rect.bottom + 8) + "px;" +
        "background:var(--bg);border:1px solid var(--border-soft);border-radius:10px;" +
        "box-shadow:0 8px 24px rgba(0,0,0,.18);min-width:168px;overflow:hidden";
      menu.innerHTML =
        '<div style="padding:10px 14px;font-weight:600;border-bottom:1px solid var(--border-soft)" id="um-name"></div>' +
        '<button class="btn btn-ghost btn-block" id="um-chgpwd" style="justify-content:flex-start;border-radius:0">修改密码</button>' +
        '<button class="btn btn-ghost btn-block" id="um-logout" style="justify-content:flex-start;border-radius:0;color:var(--danger)">退出登录</button>';
      document.body.appendChild(menu);
      const a = PG.state.admin;
      menu.querySelector("#um-name").textContent = a ? (a.name || a.username) : "";
      menu.querySelector("#um-chgpwd").onclick = () => { menu.remove(); menu = null; openChangePassword(); };
      menu.querySelector("#um-logout").onclick = async () => {
        if (menu) { menu.remove(); menu = null; }
        // 先请服务端吊销令牌，再清本地态。顺序不能反 —— 本地 token 一清，
        // 吊销请求就发不出去了（请求头里没凭据）。
        const r = await PG.api.logout();
        leaveToLogin();
        // 吊销失败也要让人退出去，但必须如实告知：此时令牌只是"本地看不见了"，
        // 在过期前依然可用，管理员得知道这件事，而不是以为已经安全。
        if (r && r.revoked === false && r.reason && r.reason !== "mock") {
          PG.ui.toast("warn", "已退出，但服务端吊销未成功",
            "该令牌将在过期后自动失效：" + r.reason);
        }
      };
      const close = () => { if (menu) { menu.remove(); menu = null; } document.removeEventListener("click", close); };
      setTimeout(() => document.addEventListener("click", close), 0);
    };
  })();

  /** 自助修改当前管理员密码弹层（含显示/隐藏） */
  function openChangePassword() {
    const m = PG.ui.modal({
      title: "修改我的密码", width: 420,
      body: `
        <div class="field"><label>当前密码</label>
          <div style="position:relative"><input type="password" id="cp-cur" autocomplete="current-password">
          <button type="button" class="pw-toggle" data-t="cp-cur" style="position:absolute;right:8px;top:9px;background:none;border:none;cursor:pointer;color:var(--brand)">显示</button></div></div>
        <div class="field"><label>新密码（至少 8 位）</label>
          <div style="position:relative"><input type="password" id="cp-new" autocomplete="new-password">
          <button type="button" class="pw-toggle" data-t="cp-new" style="position:absolute;right:8px;top:9px;background:none;border:none;cursor:pointer;color:var(--brand)">显示</button></div></div>
        <p class="muted" style="font-size:12px">修改后立即生效；为安全起见，修改后请使用新密码重新登录。</p>`,
      foot: '<button class="btn btn-ghost" id="cp-cancel">取消</button><button class="btn btn-primary" id="cp-ok">确认修改</button>',
    });
    m.el.querySelectorAll(".pw-toggle").forEach((b) => (b.onclick = () => {
      const inp = m.el.querySelector("#" + b.dataset.t);
      inp.type = inp.type === "password" ? "text" : "password";
      b.textContent = inp.type === "password" ? "显示" : "隐藏";
    }));
    m.el.querySelector("#cp-cancel").onclick = m.close;
    m.el.querySelector("#cp-ok").onclick = async () => {
      const cur = m.el.querySelector("#cp-cur").value;
      const nw = m.el.querySelector("#cp-new").value;
      if (!cur || !nw) { PG.ui.toast("err", "请填写当前密码和新密码"); return; }
      if (nw.length < 8) { PG.ui.toast("err", "新密码至少 8 位"); return; }
      try {
        await PG.api.changeOwnPassword(cur, nw);
        m.close();
        PG.ui.toast("ok", "密码已修改", "请使用新密码重新登录");
        // 服务端改密时已把令牌版本号 +1，旧令牌当场作废，无需再调 logout。
        setTimeout(leaveToLogin, 900);
      } catch (e) { PG.ui.toast("err", "修改失败", e.message); }
    };
  }

  // 已登录态恢复（演示环境默认需重新登录，保证可审计；此处仅占位）
})(window.PG);
