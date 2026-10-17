/* ============================================================
   路由 + 视图注册表
   ============================================================ */
(function (global) {
  const PG = global.PG || (global.PG = {});
  const views = PG.views;          // 复用 components.js 中初始化的注册表
  const NAV = [
    { title: "总览", items: ["dashboard"] },
    { title: "运营管理", items: ["parents", "devices", "billing"] },
    { title: "平台治理", items: ["rbac", "server", "serverProfile", "audit", "settings"] },
  ];

  function buildSidebar() {
    const nav = document.getElementById("sidebar-nav");
    nav.innerHTML = NAV.map(g => '<div class="nav-group-title">' + g.title + '</div>' + g.items.map(k => {
      const v = views[k]; if (!v) return "";
      return '<div class="nav-item" data-view="' + k + '"><span class="nav-ico">' + v.icon + '</span><span class="nav-label">' + v.title + '</span></div>';
    }).join("")).join("");
    nav.querySelectorAll(".nav-item").forEach(el => el.onclick = () => go(el.dataset.view));
  }

  async function render(key) {
    if (!views[key]) key = "dashboard";
    const v = views[key];
    const root = document.getElementById("view");
    document.getElementById("crumb-mod").textContent = v.title;
    document.querySelectorAll(".nav-item").forEach(el => el.classList.toggle("active", el.dataset.view === key));
    root.innerHTML = typeof v.render === "function" ? v.render() : "";
    await mountSafely(v, root);
    root.scrollTop = 0;
  }

  /**
   * 带兜底地挂载视图。
   *
   * 各 view 的 mount 里 await 的都是真实接口，而 api.js 的契约是「4xx/5xx 与业务错误码如实上抛、绝不降级」。
   * 少了这层捕获，任何一次 403（RBAC 下低权限管理员打开任意页面都会触发）
   * 都会变成一个未捕获的 Promise rejection —— 页面永久停在 render() 渲染出的「加载中…」，
   * 管理员完全不知道自己是被拒了还是系统坏了。
   */
  async function mountSafely(v, root) {
    if (typeof v.mount !== "function") return;
    try {
      await v.mount(root);
    } catch (err) {
      const msg = (err && err.message) || String(err);
      const forbidden = /403|FORBIDDEN|没有权限|无权限/i.test(msg);
      root.innerHTML = '<div class="empty" style="flex-direction:column;gap:12px;padding:40px 20px">' +
        '<div style="font-size:15px;font-weight:600">' + (forbidden ? "没有访问权限" : "加载失败") + '</div>' +
        '<div style="max-width:520px;line-height:1.7">' + escapeHtml(msg) + '</div>' +
        '<button class="btn btn-primary" id="rt-retry">重试</button>' +
        '</div>';
      const btn = root.querySelector("#rt-retry");
      if (btn) btn.onclick = () => render(v.key);
    }
  }

  function escapeHtml(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  }

  function go(key) { if (location.hash !== "#" + key) location.hash = key; else render(key); }
  function refresh() { const k = (location.hash || "#dashboard").slice(1); render(k); }

  window.addEventListener("hashchange", () => render((location.hash || "#dashboard").slice(1)));

  PG.router = { go, refresh, buildSidebar, render, NAV };
})(window);
