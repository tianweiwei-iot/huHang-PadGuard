/* 审计日志与风险告警 */
(function (PG) {
  const U = PG.ui;
  const AREA_CN = { parents: "家长账号", devices: "设备管理", billing: "计费", rbac: "权限", server: "服务端", settings: "设置" };
  PG.registerView("audit", {
    title: "审计日志", icon: "📝", group: "系统",
    render: () => `
      <div class="page-head"><div><h2>审计日志与风险告警</h2><p>全量管理操作留痕与安全风险事件，满足合规审计要求</p></div></div>
      <div class="card">
        <div class="row" style="gap:8px;margin-bottom:14px;border-bottom:1px solid var(--border-soft);padding-bottom:10px">
          <button class="btn btn-xs btn-primary tab" data-tab="log">管理审计</button>
          <button class="btn btn-xs tab" data-tab="alert">风险告警</button>
        </div>
        <div id="a-tab"></div>
      </div>`,
    mount: async (root) => {
      const tabs = root.querySelectorAll(".tab");
      const show = (t) => { tabs.forEach(b => b.classList.toggle("btn-primary", b.dataset.tab === t)); t === "log" ? renderLog(root) : renderAlert(root); };
      tabs.forEach(b => b.onclick = () => show(b.dataset.tab));
      show("log");
    },
  });

  async function renderLog(root) {
    const list = await PG.api.audit({});
    root.querySelector("#a-tab").innerHTML = `<div class="row wrap" style="margin-bottom:12px;gap:8px">
      <select id="al-area" class="btn" style="height:36px"><option value="">全部领域</option>${Object.keys(AREA_CN).map(a => '<option value="' + a + '">' + AREA_CN[a] + '</option>').join("")}</select></div>
      <div class="table-wrap"><table class="tbl"><thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>领域</th><th>对象</th><th>来源IP</th><th>结果</th></tr></thead><tbody id="al-body"></tbody></table></div>`;
    const draw = (area) => { const f = area ? list.filter(l => l.area === area) : list; root.querySelector("#al-body").innerHTML = f.map(l => `<tr><td class="cell-sub">${l.time}</td><td>${U.esc(l.admin)}</td><td>${l.actionName}</td><td>${AREA_CN[l.area] || l.area}</td><td class="cell-sub">${l.target}</td><td class="cell-sub">${l.ip}</td><td>${l.result === "success" ? '<span class="badge green">成功</span>' : '<span class="badge red">失败</span>'}</td></tr>`).join("") || '<tr><td colspan="7" class="empty">暂无记录</td></tr>'; };
    draw(""); root.querySelector("#al-area").onchange = (e) => draw(e.target.value);
  }

  async function renderAlert(root) {
    const list = await PG.api.alerts();
    root.querySelector("#a-tab").innerHTML = `<div class="timeline" id="al-list"></div>`;
    const draw = () => {
      const el = root.querySelector("#al-list");
      const open = list.filter(a => a.status !== "closed");
      el.innerHTML = list.map(a => {
        const c = { high: "red", mid: "amber", low: "gray" }[a.level] || "gray";
        const lv = { high: "高", mid: "中", low: "低" }[a.level];
        return `<div class="tl-item">
          <div class="tl-time">${a.time} · <span class="badge ${c}">${lv}危</span> ${a.type} · ${a.status === "closed" ? "已处理" : "待处理"}</div>
          <div class="tl-text">${U.esc(a.message)} <span class="cell-sub">（家长 ${a.relatedParent}）</span></div>
          ${a.status !== "closed" ? '<div style="margin-top:6px"><button class="btn btn-xs" data-close="' + a.id + '">标记为已处理</button></div>' : ""}
        </div>`;
      }).join("");
      el.querySelectorAll("[data-close]").forEach(b => b.onclick = async () => { await PG.api.closeAlert(b.dataset.close); PG.ui.toast("ok", "已处理", b.dataset.close); renderAlert(root); });
    };
    draw();
  }
})(window.PG);
