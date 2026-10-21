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
      // 趋势由服务端按真实数据计算；服务端未返回（历史版本 / 数据不足）时为空对象，
      // 卡片降级为显示静态副标题，绝不回落到任何硬编码数组。
      const t = d.trends || {};
      const planColors = ["#6b7693", "#3b82f6", "#14b8a6"];
      const regionColors = ["#3b82f6", "#14b8a6", "#a855f7", "#f59e0b", "#ef4444", "#22c55e", "#60a5fa", "#f472b6", "#fb923c", "#34d399"];
      const body = root.querySelector("#dash-body");
      body.innerHTML = `
      <div class="grid grid-4">
        ${kpi("👨‍👩‍👧", "家长账号", k.parentsTotal, "活跃 " + k.parentsActive, t.parent)}
        ${kpi("📱", "在线设备", k.devicesOnline + "/" + k.devicesTotal, "设备总数 " + k.devicesTotal, t.device)}
        ${kpi("💎", "生效订阅", k.subsActive, "试用 " + k.subsTrial, t.subscription)}
        ${kpi("💰", "本月营收", U.moneyK(k.monthRevenue), "本月已收款", t.revenue)}
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

  /**
   * KPI 卡片。
   *
   * 趋势的两处呈现（变化文案 + 走势线）在**数据缺失时一律不渲染**。
   * 早期这里是四条写死的字面量数组，配一段硬编码的"环比 +12%"，
   * 且方向与箭头自相矛盾（down 配 +12%）。那比没有趋势图更糟 ——
   * 运营会把一条凭空编出来的曲线当成真实信号去做决策。
   * 现在没数据就是没数据：降级显示那条静态副标题（如"活跃 12"），
   * 它是真值，不是替补的假象。
   */
  function kpi(ico, label, val, sub, trend) {
    const hasSeries = trend && Array.isArray(trend.series) && trend.series.length > 0;
    const hasDelta = !!(trend && trend.delta != null && trend.direction);
    const arrow = !hasDelta ? ""
      : trend.direction === "up" ? "▲" : trend.direction === "down" ? "▼" : "▬";
    const deltaText = !hasDelta ? ""
      : trend.unit === "fen"
        ? (trend.delta > 0 ? "+" : trend.delta < 0 ? "-" : "") + U.money(Math.abs(trend.delta))
        : (trend.delta > 0 ? "+" : "") + trend.delta;
    const trendLine = hasDelta
      ? U.esc(trend.label) + " " + deltaText
      : U.esc(sub);
    return `<div class="card kpi">
      <div class="kpi-top"><div class="kpi-ico" style="background:var(--brand-soft);color:var(--brand)">${ico}</div><span>${label}</span></div>
      <div class="kpi-val">${val}</div>
      <div class="kpi-trend ${hasDelta ? trend.direction : ""}">${hasDelta ? arrow + " " : ""}${trendLine}</div>
      ${hasSeries ? '<div class="spark">' + U.sparkline(trend.series) + '</div>' : ""}
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
