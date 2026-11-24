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
      let s;
      try {
        s = await PG.api.serverMetrics();
      } catch (e) {
        // 探针失败必须给出口：此前异常直接冒泡，页面永久停在"加载中…"
        const body = root.querySelector("#s-body");
        if (!body) return; // 弱网切页：旧视图的回调不得覆盖新页面
        body.innerHTML =
          '<div class="empty">监控数据加载失败：' + U.esc((e && e.message) || "服务端未响应") +
          '<div style="margin-top:12px"><button class="btn" id="s-retry">重试</button></div></div>';
        const rt = root.querySelector("#s-retry");
        if (rt) rt.onclick = () => PG.views.server.mount(root);
        return;
      }
      if (!s || typeof s !== "object") s = {};
      // 指标字段可能缺失或为字符串（老版本服务端 / 探针降级），一律先归一化成有限数字。
      // 此前直接拼进 innerHTML 与 style 宽度：字符串 "80%" 会让进度条失效，
      // 非数字则显示 NaN%；同时这也是 innerHTML 注入面（值进了属性上下文）。
      const num = (v, d = 0) => {
        const n = typeof v === "number" ? v : Number(v);
        return Number.isFinite(n) ? n : d;
      };
      const up = (s.status === "UP");
      const instances = Array.isArray(s.instances) ? s.instances : [];
      const qpmHistory = Array.isArray(s.qpmHistory) ? s.qpmHistory : [];
      const logs = Array.isArray(s.logs) ? s.logs : [];
      const heapMax = num(s.jvmHeapMaxMb);
      const heapUsed = num(s.jvmHeapUsedMb);
      // 第八轮 P2：jvmHeapMaxMb 为 0/缺失时（虚拟机未设上限）除零得 NaN，页面显示 "NaN%"
      const heapPct = heapMax > 0 ? Math.round(heapUsed / heapMax * 100) : 0;
      const uptimeH = Math.round(num(s.uptimeMs) / 3600000);
      const errorRate = num(s.errorRate);
      const qpm = num(s.requestsPerMin);
      const sBody = root.querySelector("#s-body");
      // 弱网切页防线：请求期间用户可能已切到别的视图，目标节点已随旧 DOM 消失，
      // 裸写会 TypeError → mountSafely 把新页面整体替换成"加载失败"。
      if (!sBody) return;
      sBody.innerHTML = `
      <div class="grid grid-4">
        ${kpi("状态", up ? "运行正常" : "异常", '<span class="dot ' + (up ? "green" : "red") + '"></span>', "DB " + U.esc(s.db), up ? "up" : "down")}
        ${kpi("运行时长", uptimeH + " h", "自 " + U.esc(s.startedAt), "up", "")}
        ${kpi("堆内存", heapUsed + "MB", "上限 " + heapMax + "MB", "up", "")}
        ${kpi("每分钟请求", qpm, "错误率 " + errorRate + "% · 平均响应 " + num(s.avgLatencyMs) + "ms", "up", U.sparkline(qpmHistory))}
      </div>

      <div class="grid grid-2" style="margin-top:16px">
        <div class="card">
          <div class="card-head"><h3>实例状态</h3><span class="sub">${instances.length} 个节点</span></div>
          ${instances.length ? instances.map(i => `<div class="stat-line"><span><span class="dot ${i && i.status === "UP" ? "green" : "red"}"></span> ${U.esc(i && i.name)}</span><span>${U.esc(i && i.host)} · 堆内存 ${U.esc(i && i.mem)}</span></div>`).join("") : '<div class="muted">暂无节点信息（单机部署时由本进程自报）</div>'}
          <div class="stat-line"><span>数据库连接</span><span>${U.esc(s.dbConnections)}</span></div>
          <div class="stat-line"><span>活跃线程</span><span>${U.esc(s.threads)}</span></div>
        </div>
        <div class="card">
          <div class="card-head"><h3>资源占用</h3></div>
          <div style="margin-bottom:14px"><div class="between" style="font-size:12px;margin-bottom:6px"><span>JVM 堆内存</span><span>${heapPct}%</span></div><div class="progress"><i style="width:${heapPct}%"></i></div></div>
          <div style="margin-bottom:14px"><div class="between" style="font-size:12px;margin-bottom:6px"><span>错误率</span><span>${errorRate}%</span></div><div class="progress"><i style="width:${Math.min(100, errorRate * 20)}%"></i></div></div>
          <div><div class="between" style="font-size:12px;margin-bottom:6px"><span>每分钟请求峰值</span><span>${qpm}</span></div><div class="progress"><i style="width:${Math.min(100, qpm / 6)}%"></i></div></div>
          <div class="row" style="margin-top:16px;gap:10px">
            <button class="btn btn-ghost" id="s-logs">↻ 刷新日志</button>
            <button class="btn btn-ghost" id="s-sweep">清理孤儿文件</button>
          </div>
          <p class="muted" style="font-size:12px;margin-top:10px">
            本后台不提供「远程重启」：服务进程自重启不可靠，且会让当前在线设备集体掉线。
            需要重启请在服务器执行（如 <code>systemctl restart padguard</code> 或重新运行 <code>deploy/app.jar</code>）。
          </p>
        </div>
      </div>

      <div class="card" style="margin-top:16px">
        <div class="card-head"><h3>实时日志</h3><span class="sub" id="s-logcount">最近 ${logs.length} 条</span></div>
        <div id="s-logbox" style="font-family:monospace;font-size:12.5px;background:var(--bg-2);border-radius:10px;padding:12px 14px;max-height:240px;overflow:auto">
          ${renderLogs(logs)}
        </div>
      </div>`;
      // 拉取服务端进程内环形缓冲的真实日志（WARN/ERROR + 本服务 INFO 业务事件）
      root.querySelector("#s-logs").onclick = async () => {
        // 必须 catch：onclick 里的 async 函数没人 await，异常会变成 unhandledrejection ——
        // 表现就是"点了刷新日志，什么都没发生，也没有任何提示"（服务端 5xx / 网络断时必现）。
        let lines;
        try {
          lines = await PG.api.serverLogs();
        } catch (e) {
          PG.ui.toast("err", "日志刷新失败", (e && e.message) || "");
          return;
        }
        const box = root.querySelector("#s-logbox");
        if (!box) return;
        box.innerHTML = renderLogs(lines);
        root.querySelector("#s-logcount").textContent = "最近 " + (lines ? lines.length : 0) + " 条";
        PG.ui.toast("ok", "日志已刷新", "来源：服务端运行日志缓冲");
      };

      // 磁盘孤儿清理：**先预演再确认**，不提供"一键删除"捷径。
      // 这类文件（盘上有、库里没记录）保留期清理永远扫不到，而打包脚本会把整个
      // uploads/ 复制进发布包 —— 测试样本就是这样随包发到家庭用户手里的。
      // 删除不可逆，所以先用 dryRun 拿数量+样例，运营确认后才真删。
      root.querySelector("#s-sweep").onclick = async (ev) => {
        const btn = ev.currentTarget;
        btn.disabled = true;
        let dry;
        try {
          dry = await PG.api.sweepOrphans(7, true);
        } catch (e) {
          PG.ui.toast("err", "预演失败", (e && e.message) || "");
          return;
        } finally {
          btn.disabled = false;
        }
        // 读库失败时后端一个都没删：必须单独提示，否则"deleted=0"会被读成"没有垃圾"
        if (dry && dry.dbReadFailed) {
          PG.ui.toast("err", "无法清理", "服务端读取文件记录失败，本轮未删除任何文件");
          return;
        }
        if (!dry || !dry.deleted) {
          PG.ui.toast("ok", "没有孤儿文件", "扫描 " + ((dry && dry.scanned) || 0) + " 个文件，全部都有对应记录");
          return;
        }
        const kb = Math.round((dry.bytesFreed || 0) / 1024);
        const sample = dry.samples && dry.samples.length ? "；样例：" + dry.samples.join("、") : "";
        PG.ui.confirm(
          "清理孤儿文件",
          "扫描 " + dry.scanned + " 个文件，其中 " + dry.deleted + " 个在数据库里没有任何记录（约 " + kb + " KB）。" +
            "这些文件既无法被下载、也不会被保留期清理扫到。删除后不可恢复，确认继续？" + sample,
          async () => {
            let r;
            try {
              r = await PG.api.sweepOrphans(7, false);
            } catch (e) {
              PG.ui.toast("err", "清理失败", (e && e.message) || "");
              return;
            }
            PG.ui.toast("ok", "清理完成",
              "删除 " + ((r && r.deleted) || 0) + " 个文件，释放约 " + Math.round(((r && r.bytesFreed) || 0) / 1024) + " KB");
          }
        );
      };
    },
  });

  /** 渲染日志行；缓冲为空时给出明确说明，避免"空白=坏了"的误判 */
  function renderLogs(logs) {
    if (!logs || !logs.length) {
      return '<div class="muted">暂无日志记录（服务刚启动、或近期无 WARN/ERROR 与业务事件）。</div>';
    }
    return logs.map(l => '<div style="padding:3px 0"><span class="muted">' + U.esc(l.time) + '</span> <span style="color:' +
      ({ INFO: "var(--text-2)", WARN: "var(--warn)", ERROR: "var(--danger)" }[l.level] || "var(--text-2)") + '">[' + U.esc(l.level) + ']</span> ' +
      U.esc(l.msg) + '</div>').join("");
  }

  function kpi(label, val, trend, dir, spark) {
    return `<div class="card kpi"><div class="kpi-top"><span>${label}</span></div><div class="kpi-val" style="display:flex;align-items:center;gap:8px">${val}</div><div class="kpi-trend ${dir}" style="margin-top:6px">${trend}</div><div class="spark">${spark}</div></div>`;
  }
})(window.PG);
