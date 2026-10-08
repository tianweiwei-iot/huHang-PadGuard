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
    if (v.mount) await v.mount(root);
    root.scrollTop = 0;
  }

  function go(key) { if (location.hash !== "#" + key) location.hash = key; else render(key); }
  function refresh() { const k = (location.hash || "#dashboard").slice(1); render(k); }

  window.addEventListener("hashchange", () => render((location.hash || "#dashboard").slice(1)));

  PG.router = { go, refresh, buildSidebar, render, NAV };
})(window);
