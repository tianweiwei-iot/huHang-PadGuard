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
        ${kpi("每分钟请求", s.requestsPerMin, "错误率 " + s.errorRate + "% · 平均响应 " + (s.avgLatencyMs || 0) + "ms", "up", U.sparkline(s.qpmHistory || []))}
      </div>

      <div class="grid grid-2" style="margin-top:16px">
        <div class="card">
          <div class="card-head"><h3>实例状态</h3><span class="sub">${s.instances.length} 个节点</span></div>
          ${s.instances.map(i => `<div class="stat-line"><span><span class="dot ${i.status === "UP" ? "green" : "red"}"></span> ${i.name}</span><span>${i.host} · 堆内存 ${i.mem}</span></div>`).join("")}
          <div class="stat-line"><span>数据库连接</span><span>${s.dbConnections}</span></div>
          <div class="stat-line"><span>活跃线程</span><span>${s.threads}</span></div>
        </div>
        <div class="card">
          <div class="card-head"><h3>资源占用</h3></div>
          <div style="margin-bottom:14px"><div class="between" style="font-size:12px;margin-bottom:6px"><span>JVM 堆内存</span><span>${heapPct}%</span></div><div class="progress"><i style="width:${heapPct}%"></i></div></div>
          <div style="margin-bottom:14px"><div class="between" style="font-size:12px;margin-bottom:6px"><span>错误率</span><span>${s.errorRate}%</span></div><div class="progress"><i style="width:${Math.min(100, s.errorRate * 20)}%"></i></div></div>
          <div><div class="between" style="font-size:12px;margin-bottom:6px"><span>每分钟请求峰值</span><span>${s.requestsPerMin}</span></div><div class="progress"><i style="width:${Math.min(100, s.requestsPerMin / 6)}%"></i></div></div>
          <div class="row" style="margin-top:16px;gap:10px">
            <button class="btn btn-ghost" id="s-logs">↻ 刷新日志</button>
          </div>
          <p class="muted" style="font-size:12px;margin-top:10px">
            本后台不提供「远程重启」：服务进程自重启不可靠，且会让当前在线设备集体掉线。
            需要重启请在服务器执行（如 <code>systemctl restart padguard</code> 或重新运行 <code>deploy/app.jar</code>）。
          </p>
        </div>
      </div>

      <div class="card" style="margin-top:16px">
        <div class="card-head"><h3>实时日志</h3><span class="sub" id="s-logcount">最近 ${s.logs.length} 条</span></div>
        <div id="s-logbox" style="font-family:monospace;font-size:12.5px;background:var(--bg-2);border-radius:10px;padding:12px 14px;max-height:240px;overflow:auto">
          ${renderLogs(s.logs)}
        </div>
      </div>`;
      // 拉取服务端进程内环形缓冲的真实日志（WARN/ERROR + 本服务 INFO 业务事件）
      root.querySelector("#s-logs").onclick = async () => {
        const lines = await PG.api.serverLogs();
        const box = root.querySelector("#s-logbox");
        if (!box) return;
        box.innerHTML = renderLogs(lines);
        root.querySelector("#s-logcount").textContent = "最近 " + (lines ? lines.length : 0) + " 条";
        PG.ui.toast("ok", "日志已刷新", "来源：服务端运行日志缓冲");
      };
    },
  });

  /** 渲染日志行；缓冲为空时给出明确说明，避免"空白=坏了"的误判 */
  function renderLogs(logs) {
    if (!logs || !logs.length) {
      return '<div class="muted">暂无日志记录（服务刚启动、或近期无 WARN/ERROR 与业务事件）。</div>';
    }
    return logs.map(l => '<div style="padding:3px 0"><span class="muted">' + l.time + '</span> <span style="color:' +
      ({ INFO: "var(--text-2)", WARN: "var(--warn)", ERROR: "var(--danger)" }[l.level] || "var(--text-2)") + '">[' + l.level + ']</span> ' +
      U.esc(l.msg) + '</div>').join("");
  }

  function kpi(label, val, trend, dir, spark) {
    return `<div class="card kpi"><div class="kpi-top"><span>${label}</span></div><div class="kpi-val" style="display:flex;align-items:center;gap:8px">${val}</div><div class="kpi-trend ${dir}" style="margin-top:6px">${trend}</div><div class="spark">${spark}</div></div>`;
  }
})(window.PG);
