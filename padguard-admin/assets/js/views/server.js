/* 服务端监控与管理 */
(function (PG) {
  const U = PG.ui;
  PG.registerView("server", {
    title: "服务端监控", icon: "🖥️", group: "系统",
    render: () => `
      <div class="page-head"><div><h2>服务端监控与管理</h2><p>存活探针、实例与资源占用、实时日志；对接 <code>GET /health</code></p></div>
      <div class="page-actions"><button class="btn" id="s-refresh">↻ 刷新</button></div></div>
      <div id="s-body"><div class="empty">加载中…</div></div>`,
    mount: async (root) => {
      root.querySelector("#s-refresh").onclick = () => PG.views.server.mount(root);
      const s = await PG.api.serverMetrics();
      const up = (s.status === "UP");
      const heapPct = Math.round(s.jvmHeapUsedMb / s.jvmHeapMaxMb * 100);
      const uptimeH = Math.round(s.uptimeMs / 3600000);
      root.querySelector("#s-body").innerHTML = `
      <div class="grid grid-4">
        ${kpi("状态", up ? "运行正常" : "异常", '<span class="dot ' + (up ? "green" : "red") + '"></span>', "DB " + s.db, up ? "up" : "down")}
        ${kpi("运行时长", uptimeH + " h", "自 " + s.startedAt, "up", U.sparkline([1,2,2,3,3,4,5]))}
        ${kpi("堆内存", s.jvmHeapUsedMb + "MB", "上限 " + s.jvmHeapMaxMb + "MB", "up", U.sparkline([20,28,26,34,40,38,heapPct]))}
        ${kpi("每分钟请求", s.requestsPerMin, "错误率 " + s.errorRate + "%", "up", U.sparkline([120,180,160,240,210,300,280]))}
      </div>

      <div class="grid grid-2" style="margin-top:16px">
        <div class="card">
          <div class="card-head"><h3>实例状态</h3><span class="sub">${s.instances.length} 个节点</span></div>
          ${s.instances.map(i => `<div class="stat-line"><span><span class="dot ${i.status === "UP" ? "green" : "red"}"></span> ${i.name}</span><span>${i.host} · CPU ${i.cpu}% · 内存 ${i.mem}%</span></div>`).join("")}
          <div class="stat-line"><span>数据库连接</span><span>${s.dbConnections}</span></div>
          <div class="stat-line"><span>活跃线程</span><span>${s.threads}</span></div>
        </div>
        <div class="card">
          <div class="card-head"><h3>资源占用</h3></div>
          <div style="margin-bottom:14px"><div class="between" style="font-size:12px;margin-bottom:6px"><span>JVM 堆内存</span><span>${heapPct}%</span></div><div class="progress"><i style="width:${heapPct}%"></i></div></div>
          <div style="margin-bottom:14px"><div class="between" style="font-size:12px;margin-bottom:6px"><span>错误率</span><span>${s.errorRate}%</span></div><div class="progress"><i style="width:${Math.min(100, s.errorRate * 20)}%"></i></div></div>
          <div><div class="between" style="font-size:12px;margin-bottom:6px"><span>每分钟请求峰值</span><span>${s.requestsPerMin}</span></div><div class="progress"><i style="width:${Math.min(100, s.requestsPerMin / 6)}%"></i></div></div>
          <div class="row" style="margin-top:16px;gap:10px">
            <button class="btn" id="s-restart">重启实例</button>
            <button class="btn btn-ghost" id="s-logs">查看日志</button>
          </div>
        </div>
      </div>

      <div class="card" style="margin-top:16px">
        <div class="card-head"><h3>实时日志</h3><span class="sub">最近 ${s.logs.length} 条</span></div>
        <div style="font-family:monospace;font-size:12.5px;background:var(--bg-2);border-radius:10px;padding:12px 14px;max-height:240px;overflow:auto">
          ${s.logs.map(l => '<div style="padding:3px 0"><span class="muted">' + l.time + '</span> <span style="color:' + ({ INFO: "var(--text-2)", WARN: "var(--warn)", ERROR: "var(--danger)" }[l.level]) + '">[' + l.level + ']</span> ' + U.esc(l.msg) + '</div>').join("")}
        </div>
      </div>`;
      root.querySelector("#s-restart").onclick = () => PG.ui.confirm("重启服务实例", "将向服务端下发重启指令（生产环境请谨慎）。", () => PG.ui.toast("ok", "重启指令已下发", "padguard-server"), true);
      root.querySelector("#s-logs").onclick = () => PG.ui.toast("ok", "日志已展开", "见下方实时日志区");
    },
  });

  function kpi(label, val, trend, dir, spark) {
    return `<div class="card kpi"><div class="kpi-top"><span>${label}</span></div><div class="kpi-val" style="display:flex;align-items:center;gap:8px">${val}</div><div class="kpi-trend ${dir}" style="margin-top:6px">${trend}</div><div class="spark">${spark}</div></div>`;
  }
})(window.PG);
