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

  const state = { admin: null };
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

  // 全局搜索 → 家长账号列表
  $("global-search").addEventListener("keydown", (e) => { if (e.key === "Enter") { PG.router.go("parents"); } });

  // 通知铃 → 审计告警
  $("bell-btn").onclick = () => PG.router.go("audit");

  // 登录
  $("login-form").onsubmit = async (e) => {
    e.preventDefault();
    const username = $("login-username").value.trim() || "admin";
    const password = $("login-password").value || "admin123";
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

  function enterApp() {
    $("login-screen").classList.add("hidden");
    $("app-shell").classList.remove("hidden");
    const a = state.admin;
    $("user-name").textContent = a.name;
    $("user-role").textContent = a.roleName;
    $("user-avatar").textContent = (a.name || "A").charAt(0);
    const alerts = (PG.Mock && Array.isArray(PG.Mock.alerts)) ? PG.Mock.alerts.filter(x => x.status !== "closed").length : 0;
    $("bell-count").textContent = alerts;
    $("bell-count").style.display = alerts ? "block" : "none";
    PG.router.buildSidebar();
    if (!location.hash) location.hash = "dashboard"; else PG.router.render((location.hash || "#dashboard").slice(1));
  }

  // 已登录态恢复（演示环境默认需重新登录，保证可审计；此处仅占位）
})(window.PG);
