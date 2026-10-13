/* 设备管理 */
(function (PG) {
  const U = PG.ui;
  PG.registerView("devices", {
    title: "设备管理", icon: "📱", group: "运营",
    render: () => `
      <div class="page-head"><div><h2>设备管理</h2><p>查看全量绑定设备、管控模式与在线状态，支持远程锁屏 / 解锁</p></div></div>
      <div class="card">
        <div class="row wrap" style="margin-bottom:14px">
          <div class="search-box" style="flex:1;min-width:220px"><input id="d-kw" placeholder="设备ID / 家长手机号 / 家长名" style="width:100%"></div>
          <select id="d-on" class="btn" style="height:40px;width:140px"><option value="">全部状态</option><option value="1">仅在线</option><option value="0">仅离线</option></select>
          <button class="btn" id="d-reset">重置</button>
        </div>
        <div class="table-wrap"><table class="tbl"><thead><tr>
          <th>设备</th><th>管控模式</th><th>绑定家长</th><th>机型 / 系统</th><th>在线</th><th>远程锁</th><th>未成年人模式</th><th>今日用量</th><th>操作</th>
        </tr></thead><tbody id="d-body"></tbody></table></div>
        <div id="d-empty"></div>
      </div>`,
    mount: async (root) => {
      const reload = () => load(root);
      root.querySelector("#d-reset").onclick = () => { root.querySelector("#d-kw").value = ""; root.querySelector("#d-on").value = ""; reload(); };
      root.querySelector("#d-kw").oninput = debounce(reload, 300);
      root.querySelector("#d-on").onchange = reload;
      await load(root);
    },
  });

  async function load(root) {
    const q = { keyword: root.querySelector("#d-kw").value.trim(), online: root.querySelector("#d-on").value };
    const list = await PG.api.listDevices(q);
    const tb = root.querySelector("#d-body");
    if (!list.length) { tb.innerHTML = ""; root.querySelector("#d-empty").innerHTML = '<div class="empty">没有匹配的设备</div>'; return; }
    root.querySelector("#d-empty").innerHTML = "";
    const modeMap = { DO: ["purple", "设备所有者"], DA: ["blue", "设备管理员"], NONE: ["gray", "无"], };
    tb.innerHTML = list.map(d => `<tr>
      <td><div class="cell-strong">${U.esc(d.name)}</div><div class="cell-sub">${U.esc(d.deviceId)} · ${U.esc(d.deviceSn)}</div></td>
      <td>${U.badge(modeMap[d.controlMode] || { NONE: ["gray", d.controlMode] }, d.controlMode)}</td>
      <td>${U.esc(d.parentName || "")}<div class="cell-sub">${U.esc(d.parentPhone)}</div></td>
      <td class="cell-sub">${U.esc(d.model)}<br>${U.esc(d.osVersion)}</td>
      <td>${d.online ? '<span class="dot green"></span> 在线' : '<span class="dot gray"></span> 离线'}</td>
      <td>${d.remoteLocked ? '<span class="badge red">已锁</span>' : '<span class="badge gray">未锁</span>'}</td>
      <td>${d.minorMode ? '<span class="badge green">开启</span>' : '<span class="badge gray">关闭</span>'}</td>
      <td>${d.todayUsageMinutes ? Math.floor(d.todayUsageMinutes / 60) + 'h' + (d.todayUsageMinutes % 60) + 'm' : '—'}</td>
      <td><div class="row-actions">
        <button class="btn btn-xs" data-detail="${U.esc(d.deviceId)}">详情</button>
        ${d.remoteLocked ? '<button class="btn btn-xs btn-primary" data-act="unlock" data-id="' + U.esc(d.deviceId) + '">解锁</button>' : '<button class="btn btn-xs" data-act="lock" data-id="' + U.esc(d.deviceId) + '">锁屏</button>'}
      </div></td>
    </tr>`).join("");
    tb.querySelectorAll("[data-detail]").forEach(b => b.onclick = () => openDetail(b.dataset.detail));
    tb.querySelectorAll("[data-act='lock']").forEach(b => b.onclick = () => PG.ui.confirm("远程锁屏", "确认向 " + b.dataset.id + " 下发锁屏指令？", async () => { await PG.api.lockDevice(b.dataset.id); PG.ui.toast("ok", "锁屏指令已下发", b.dataset.id); load(root); }, true));
    tb.querySelectorAll("[data-act='unlock']").forEach(b => b.onclick = async () => { await PG.api.unlockDevice(b.dataset.id); PG.ui.toast("ok", "解锁指令已下发", b.dataset.id); load(root); });
  }

  async function openDetail(id) {
    const list = await PG.api.listDevices({});
    const d = list.find(x => x.deviceId === id);
    if (!d) return;
    PG.ui.drawer({
      title: "设备详情 · " + U.esc(d.name),
      body: `<div class="stat-line"><span>设备ID</span><span>${U.esc(d.deviceId)}</span></div>
        <div class="stat-line"><span>序列号</span><span>${U.esc(d.deviceSn)}</span></div>
        <div class="stat-line"><span>机型 / 系统</span><span>${U.esc(d.model)} · ${U.esc(d.osVersion)}</span></div>
        <div class="stat-line"><span>管控模式</span><span>${U.esc(d.controlMode)}</span></div>
        <div class="stat-line"><span>绑定家长</span><span>${U.esc(d.parentName || "")} (${U.esc(d.parentPhone)})</span></div>
        <div class="stat-line"><span>在线状态</span><span>${d.online ? "在线" : "离线（" + U.esc(d.lastOnlineAt) + "）"}</span></div>
        <div class="stat-line"><span>远程锁屏</span><span>${d.remoteLocked ? "已锁" : "未锁"}</span></div>
        <div class="stat-line"><span>未成年人模式</span><span>${d.minorMode ? "开启" : "关闭"}</span></div>
        <div class="stat-line"><span>绑定时间</span><span>${U.esc(d.boundAt)}</span></div>
        <div class="stat-line"><span>区域</span><span>${U.esc(d.region)}</span></div>
        <div class="row" style="margin-top:16px;gap:10px">
          <button class="btn btn-primary" id="dl-lock">${d.remoteLocked ? "解锁" : "远程锁屏"}</button>
          <button class="btn" data-go="parents">查看家长</button>
        </div>`,
      width: 460,
    });
  }

  function debounce(fn, ms) { let t; return () => { clearTimeout(t); t = setTimeout(fn, ms); }; }
})(window.PG);
