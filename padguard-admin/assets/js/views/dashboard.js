/* 仪表盘 */
(function (PG) {
  const U = PG.ui;
  PG.registerView("dashboard", {
    title: "仪表盘", icon: "📊", group: "运营",
    render: () => '<div class="page-head"><div><h2>运营总览</h2><p>实时掌握家长账号、设备、订阅与服务端健康</p></div><div class="page-actions"><button class="btn" id="dash-refresh">↻ 刷新</button></div></div><div id="dash-body"><div class="empty">加载中…</div></div>',
    mount: async (root) => {
      root.querySelector("#dash-refresh").onclick = () => PG.views.dashboard.mount(root);
      const d = await PG.api.dashboard();
      const k = d.kpis;
      const planColors = ["#6b7693", "#3b82f6", "#14b8a6"];
      const regionColors = ["#3b82f6", "#14b8a6", "#a855f7", "#f59e0b", "#ef4444", "#22c55e", "#60a5fa", "#f472b6", "#fb923c", "#34d399"];
      const body = root.querySelector("#dash-body");
      body.innerHTML = `
      <div class="grid grid-4">
        ${kpi("👨‍👩‍👧", "家长账号", k.parentsTotal, "活跃 " + k.parentsActive, "up", U.sparkline([3,5,4,6,7,9,8]))}
        ${kpi("📱", "在线设备", k.devicesOnline + "/" + k.devicesTotal, "设备总数", "up", U.sparkline([20,24,22,30,28,34,33]))}
        ${kpi("💎", "生效订阅", k.subsActive, "试用 " + k.subsTrial, "up", U.sparkline([10,12,14,15,18,20,22]))}
        ${kpi("💰", "本月营收", U.moneyK(k.monthRevenue), "环比 +12%", "down", U.sparkline([28,30,26,34,38,42,46]))}
      </div>

      <div class="grid grid-3" style="margin-top:16px">
        <div class="card">
          <div class="card-head"><h3>近 6 月营收趋势</h3><span class="sub">单位：元</span></div>
          ${U.line(d.revenueSeries, { h: 200 })}
        </div>
        <div class="card">
          <div class="card-head"><h3>套餐分布</h3></div>
          <div class="between" style="gap:14px">
            <div style="flex:none">${U.donut(d.planDist.map((p, i) => ({ value: p.count, color: planColors[i] })), { center: k.parentsTotal + "", centerSub: "总账号", size: 160 })}</div>
            <div class="legend" style="flex-direction:column;align-items:flex-start">
              ${d.planDist.map((p, i) => '<div><i style="background:' + planColors[i] + '"></i>' + U.esc(p.name) + ' · ' + p.count + '</div>').join("")}
            </div>
          </div>
        </div>
        <div class="card">
          <div class="card-head"><h3>区域分布</h3><span class="sub">Top 5</span></div>
          ${U.bar(d.regionDist.slice(0, 5).map((r, i) => ({ label: r.name, value: r.count, color: regionColors[i] })), { h: 200 })}
        </div>
      </div>

      <div class="grid grid-2" style="margin-top:16px">
        <div class="card">
          <div class="card-head"><h3>待处理风险告警</h3><span class="badge red">${d.alertsOpen} 项</span></div>
          <div class="timeline">
            ${await alertsMini()}
          </div>
        </div>
        <div class="card">
          <div class="card-head"><h3>最近管理操作</h3><a class="sub" style="cursor:pointer;color:var(--brand)" data-go="audit">查看全部 →</a></div>
          <div class="timeline">
            ${d.recent.map(a => '<div class="tl-item"><div class="tl-time">' + U.esc(a.time) + ' · ' + U.esc(a.admin) + '</div><div class="tl-text">' + U.esc(a.actionName) + ' <span class="cell-sub">(' + U.esc(a.target) + ')</span></div></div>').join("")}
          </div>
        </div>
      </div>`;
      body.querySelectorAll("[data-go]").forEach(b => b.onclick = () => PG.router.go(b.dataset.go));
    },
  });

  function kpi(ico, label, val, trend, dir, spark) {
    return `<div class="card kpi">
      <div class="kpi-top"><div class="kpi-ico" style="background:var(--brand-soft);color:var(--brand)">${ico}</div><span>${label}</span></div>
      <div class="kpi-val">${val}</div>
      <div class="kpi-trend ${dir}">${dir === "up" ? "▲" : "▼"} ${trend}</div>
      <div class="spark">${spark}</div>
    </div>`;
  }

  async function alertsMini() {
    const list = await PG.api.alerts();
    return list.filter(a => a.status !== "closed").slice(0, 5).map(a => {
      const c = { high: "red", mid: "amber", low: "gray" }[a.level] || "gray";
      return '<div class="tl-item"><div class="tl-time">' + U.esc(a.time) + ' · <span class="badge ' + c + '">' + ({ high: "高", mid: "中", low: "低" }[a.level]) + '</span> ' + U.esc(a.type) + '</div><div class="tl-text">' + U.esc(a.message) + '</div></div>';
    }).join("") || '<div class="empty">暂无告警</div>';
  }
})(window.PG);
