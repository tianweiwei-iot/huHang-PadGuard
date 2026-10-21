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
    document.body.className = "theme-" + t;
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
  $("global-search").addEventListener("keydown", (e) => {
    if (e.key !== "Enter") return;
    const kw = $("global-search").value.trim();
    // 关键字先落到全局状态，由目标页 mount 时回填到自己的搜索框，避免值在跳转中丢失
    PG.state.search = kw;
    PG.router.go("parents");
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
    const btn = e.target.querySelector("button");
    btn.disabled = true; btn.textContent = "登录中…";
    try {
      const r = await PG.api.login(username, password);
      state.admin = r.admin;
      PG.api.config.token = r.token;
      enterApp();
    } catch (err) {
      PG.ui.toast("err", "登录失败", err.message);
    } finally { btn.disabled = false; btn.textContent = "登 录"; }
  };

  async function enterApp() {
    $("login-screen").classList.add("hidden");
    $("app-shell").classList.remove("hidden");
    const a = state.admin;
    $("user-name").textContent = a.name;
    $("user-role").textContent = a.roleName;
    $("user-avatar").textContent = (a.name || "A").charAt(0);
    PG.router.buildSidebar();
    if (!location.hash) location.hash = "dashboard"; else PG.router.render((location.hash || "#dashboard").slice(1));
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
    $("app-shell").classList.add("hidden");
    $("login-screen").classList.remove("hidden");
  }

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
