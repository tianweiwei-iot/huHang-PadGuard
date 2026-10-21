/* 审计日志与风险告警 */
(function (PG) {
  const U = PG.ui;
  /**
   * 领域中文名兜底表 —— **只在服务端元数据取不到时使用**。
   *
   * 正常路径由 `PG.api.auditAreas()` 从服务端权限定义下拉，前后端只有一处真相。
   * 这份兜底存在的唯一理由：接口偶发失败时，页面至少还能显示一个可读的下拉，
   * 而不是整页白屏。因此它必须**包含 audit**（这正是原硬编码表漏掉的那一项），
   * 且对未登记的领域 key 直接回显原始 key（见 [areaName]），不假装认识它。
   */
  const AREA_CN = { parents: "家长账号", devices: "设备管理", billing: "订阅计费", rbac: "角色权限", server: "服务端", audit: "审计告警", settings: "系统设置" };
  const areaName = (k) => AREA_CN[k] || k;
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
    // 领域下拉取自服务端权限定义（前后端单一事实源）。
    // 取不到时回落到本地兜底表，保证页面还能用 —— 兜底表已覆盖全部 7 个领域（含 audit）。
    let opts;
    try {
      const areas = await PG.api.auditAreas();
      opts = (areas && areas.length)
        ? areas.map((a) => ({ key: a.key, name: a.name }))
        : Object.keys(AREA_CN).map((k) => ({ key: k, name: AREA_CN[k] }));
    } catch (e) {
      opts = Object.keys(AREA_CN).map((k) => ({ key: k, name: AREA_CN[k] }));
    }
    const nameOf = (k) => { const hit = opts.find((o) => o.key === k); return hit ? hit.name : k; };

    // 结构先落地，数据异步填充：领域下拉切换时**重新请求服务端**，
    // 而不是"拉一次全量再本地筛"—— 后者只能筛到"最近 N 条里恰好属于该领域的记录"，
    // 与服务端先过滤后截断的语义不一致，会让"这个领域最近没动静"变成假象。
    root.querySelector("#a-tab").innerHTML = `<div class="row wrap" style="margin-bottom:12px;gap:8px">
      <select id="al-area" class="btn" style="height:36px"><option value="">全部领域</option>${opts.map((o) => '<option value="' + U.esc(o.key) + '">' + U.esc(o.name) + '</option>').join("")}</select></div>
      <div id="al-note" class="cell-sub" style="margin-bottom:8px"></div>
      <div class="table-wrap"><table class="tbl"><thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>领域</th><th>对象</th><th>来源IP</th><th>结果</th></tr></thead><tbody id="al-body"><tr><td colspan="7" class="empty">加载中…</td></tr></tbody></table></div>`;

    const draw = async (area) => {
      const body = root.querySelector("#al-body");
      const note = root.querySelector("#al-note");
      let page;
      try {
        page = await PG.api.audit(area ? { area } : {});
      } catch (e) {
        note.textContent = "";
        body.innerHTML = '<tr><td colspan="7" class="empty">加载失败：' + U.esc(e && e.message ? e.message : e) + '</td></tr>';
        return;
      }
      const items = (page && page.items) || [];
      // 截断必须显式告知，否则运营会把"最近 N 条"读成"全部历史"。
      note.textContent = page && page.truncated
        ? `仅展示最近 ${page.maxRows} 条（该筛选条件下共 ${page.total} 条），更早记录需通过导出/检索查看`
        : "";
      body.innerHTML = items.map(l => `<tr><td class="cell-sub">${U.esc(l.time)}</td><td>${U.esc(l.admin)}</td><td>${U.esc(l.actionName)}</td><td>${U.esc(nameOf(l.area))}</td><td class="cell-sub">${U.esc(l.target)}</td><td class="cell-sub">${U.esc(l.ip)}</td><td>${l.result === "success" ? '<span class="badge green">成功</span>' : '<span class="badge red">失败</span>'}</td></tr>`).join("") || '<tr><td colspan="7" class="empty">暂无记录</td></tr>';
    };
    await draw("");
    root.querySelector("#al-area").onchange = (e) => draw(e.target.value);
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
          <div class="tl-time">${U.esc(a.time)} · <span class="badge ${c}">${lv}危</span> ${U.esc(a.type)} · ${a.status === "closed" ? "已处理" : "待处理"}</div>
          <div class="tl-text">${U.esc(a.message)} <span class="cell-sub">（家长 ${U.esc(a.relatedParent)}）</span></div>
          ${a.status !== "closed" ? '<div style="margin-top:6px"><button class="btn btn-xs" data-close="' + U.esc(a.id) + '">标记为已处理</button></div>' : ""}
        </div>`;
      }).join("");
      el.querySelectorAll("[data-close]").forEach(b => b.onclick = async () => { await PG.api.closeAlert(b.dataset.close); PG.ui.toast("ok", "已处理", b.dataset.close); renderAlert(root); });
    };
    draw();
  }
})(window.PG);
