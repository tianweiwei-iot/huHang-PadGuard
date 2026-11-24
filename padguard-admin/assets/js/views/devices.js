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
        <div id="d-pager"></div>
      </div>`,
    mount: async (root) => {
      const reload = () => load(root);
      const reloadReset = () => { curPage = 1; load(root); };
      // 全局搜索跳转过来：回填关键字并清空全局态（此前设备页不消费，搜索设备永远落在家长页）。
      // 必须同时重置 curPage：模块级页码跨 mount 存留，翻到第 3 页后全局搜索会以 page=3
      // 请求命中第 1 页的结果 → 显示"没有匹配的设备"。
      if (PG.state && PG.state.search) {
        root.querySelector("#d-kw").value = PG.state.search;
        PG.state.search = "";
        curPage = 1;
      }
      root.querySelector("#d-reset").onclick = () => { root.querySelector("#d-kw").value = ""; root.querySelector("#d-on").value = ""; reloadReset(); };
      root.querySelector("#d-kw").oninput = debounce(reloadReset, 300);
      root.querySelector("#d-on").onchange = reloadReset;
      await load(root);
    },
  });

  let curPage = 1;
  const PAGE_SIZE = 20;
  // 第八轮 P2-9：请求序号防竞态——快速翻页/搜索时，慢的旧响应不得覆盖新结果
  let loadSeq = 0;

  async function load(root) {
    // 搜索框 debounce(300ms) 可能在切页/登出后才到点：本页已换走则节点不存在，
    // 直接放弃，避免发无用请求或对已移除节点写入抛 TypeError（登出后控制台噪声）。
    if (!root.querySelector("#d-kw")) return;
    const seq = ++loadSeq;
    const q = { keyword: root.querySelector("#d-kw").value.trim(), online: root.querySelector("#d-on").value, page: curPage, size: PAGE_SIZE };
    let resp;
    try {
      resp = await PG.api.listDevices(q);
    } catch (e) {
      if (seq !== loadSeq) return;
      PG.ui.toast("err", "加载失败", String((e && e.message) || e));
      return;
    }
    if (seq !== loadSeq) return;
    const list = (resp && resp.items) || [];
    const tb = root.querySelector("#d-body");
    if (!list.length) { tb.innerHTML = ""; root.querySelector("#d-empty").innerHTML = '<div class="empty">没有匹配的设备</div>'; root.querySelector("#d-pager").innerHTML = ""; return; }
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
      <td>${U.fmtMinutes(d.todayUsageMinutes)}</td>
      <td><div class="row-actions">
        <button class="btn btn-xs" data-detail="${U.esc(d.deviceId)}">详情</button>
        ${d.remoteLocked ? PG.guard("devices", "unlock", '<button class="btn btn-xs btn-primary" data-act="unlock" data-id="' + U.esc(d.deviceId) + '">解锁</button>') : PG.guard("devices", "lock", '<button class="btn btn-xs" data-act="lock" data-id="' + U.esc(d.deviceId) + '">锁屏</button>')}
        ${PG.guard("devices", "unbind", '<button class="btn btn-xs btn-danger" data-act="unbind" data-id="' + U.esc(d.deviceId) + '">解绑</button>')}
      </div></td>
    </tr>`).join("");
    tb.querySelectorAll("[data-detail]").forEach(b => b.onclick = () => openDetail(b.dataset.detail));
    tb.querySelectorAll("[data-act='lock']").forEach(b => b.onclick = () => PG.ui.confirm("远程锁屏", "确认向 " + b.dataset.id + " 下发锁屏指令？", async () => {
      try { await PG.api.lockDevice(b.dataset.id); } catch (e) { PG.ui.toast("err", "锁屏失败", (e && e.message) || ""); return; }
      PG.ui.toast("ok", "锁屏指令已下发", b.dataset.id); load(root);
    }, true));
    // 解锁会解除对孩子的管控，危险性不低于锁屏（锁屏反而有二次确认、解锁没有）——对齐为二次确认
    tb.querySelectorAll("[data-act='unlock']").forEach(b => b.onclick = () => PG.ui.confirm("远程解锁", "确认解除 " + b.dataset.id + " 的锁屏？孩子端将立即恢复可用。", async () => {
      try { await PG.api.unlockDevice(b.dataset.id); } catch (e) { PG.ui.toast("err", "解锁失败", (e && e.message) || ""); return; }
      PG.ui.toast("ok", "解锁指令已下发", b.dataset.id); load(root);
    }));
    tb.querySelectorAll("[data-act='unbind']").forEach(b => b.onclick = () => PG.ui.confirm("解绑设备", "确认解绑 " + b.dataset.id + "？解绑后设备归属清除、设备令牌作废，孩子端将失去管控，操作不可恢复。", async () => {
      try { await PG.api.unbindDevice(b.dataset.id); } catch (e) { PG.ui.toast("err", "解绑失败", (e && e.message) || ""); return; }
      PG.ui.toast("ok", "已解绑", b.dataset.id); load(root);
    }, true));
    // 分页器（B3/B4）
    const pagerEl = root.querySelector("#d-pager");
    pagerEl.innerHTML = U.pager(resp.page, resp.totalPages);
    pagerEl.querySelectorAll("[data-pg]").forEach(b => b.onclick = () => { curPage = parseInt(b.dataset.pg, 10); load(root); });
  }

  async function openDetail(id) {
    // 详情按 id 反查：分页拉取（每页 200，最多 20 页 = 4000 台封顶），
    // 第八轮 P2-9 修复：此前只取一页 200，设备超限时静默 return，详情按钮点了毫无反应。
    let d = null;
    try {
      for (let page = 1; page <= 20 && !d; page++) {
        const resp = await PG.api.listDevices({ page, size: 200 });
        const items = (resp && resp.items) || [];
        d = items.find(x => x.deviceId === id) || null;
        if (!items.length || (resp.page || page) >= (resp.totalPages || 1)) break;
      }
    } catch (e) {
      PG.ui.toast("err", "设备详情加载失败", String((e && e.message) || e));
      return;
    }
    if (!d) { PG.ui.toast("warn", "未找到设备", "设备 " + id + " 不在当前设备列表中（可能刚被解绑或删除）。"); return; }
    const dl = PG.ui.drawer({
      // 标题由 drawer() 内部统一 esc()，这里不能再转义一次：
      // 双重转义会把 "小米 & 平板" 显示成 "小米 &amp; 平板"
      title: "设备详情 · " + d.name,
      body: `<div class="stat-line"><span>设备ID</span><span>${U.esc(d.deviceId)}</span></div>
        <div class="stat-line"><span>序列号</span><span>${U.esc(d.deviceSn)}</span></div>
        <div class="stat-line"><span>机型 / 系统</span><span>${U.esc(d.model)} · ${U.esc(d.osVersion)}</span></div>
        <div class="stat-line"><span>管控模式</span><span>${U.esc(d.controlMode)}</span></div>
        <div class="stat-line"><span>绑定家长</span><span>${U.esc(d.parentName || "")} (${U.esc(d.parentPhone)})</span></div>
        <div class="stat-line"><span>在线状态</span><span>${d.online ? "在线" : "离线（" + U.esc(d.lastOnlineAt) + "）"}</span></div>
        <div class="stat-line"><span>远程锁屏</span><span>${d.remoteLocked ? "已锁" : "未锁"}</span></div>
        <div class="stat-line"><span>未成年人模式</span><span>${d.minorMode ? "开启" : "关闭"}</span></div>
        <div class="stat-line"><span>绑定时间</span><span>${U.esc(d.boundAt)}</span></div>
        <div class="stat-line"><span>区域</span><span>${U.esc(d.region || "—")}</span></div>
        <div class="row" style="margin-top:16px;gap:10px">
          ${d.remoteLocked ? PG.guard("devices", "unlock", '<button class="btn btn-primary" id="dl-lock">解锁</button>') : PG.guard("devices", "lock", '<button class="btn btn-primary" id="dl-lock">远程锁屏</button>')}
          <button class="btn" id="dl-parent">查看家长</button>
          ${PG.guard("devices", "unbind", '<button class="btn btn-danger" id="dl-unbind">解绑设备</button>')}
        </div>`,
      width: 460,
    });

    // 这两个按钮此前只渲染、从不绑定事件：抽屉里点「远程锁屏」毫无反应，
    // 而列表页的同类按钮是正常的 —— 同一个操作在两处表现不一致，最容易被当成"功能坏了"。
    // 另外 #dl-lock 只在有 devices:lock / unlock 时渲染，必须用 bind 判空，
    // 否则只读设备角色一开抽屉就抛 TypeError，连带下面 #dl-parent 的绑定一起中断。
    U.bind(dl.el, "#dl-lock", async () => {
      const locked = d.remoteLocked;
      const rootNow = document.getElementById("view");
      try {
        if (locked) await PG.api.unlockDevice(d.deviceId); else await PG.api.lockDevice(d.deviceId);
        PG.ui.toast("ok", locked ? "解锁指令已下发" : "锁屏指令已下发", d.deviceId);
        dl.close();
        load(rootNow);
      } catch (err) {
        PG.ui.toast("err", "操作失败", err.message);
      }
    });
    U.bind(dl.el, "#dl-parent", () => {
      dl.close();
      PG.state.search = d.parentPhone || d.parentName || "";
      PG.router.go("parents");
    });
    const dlUnbind = dl.el.querySelector("#dl-unbind");
    // 与列表页同款：confirm() 内部已统一 esc(text)，这里必须传原始值 ——
    // 先 esc 再传会被二次转义，设备名里的 "&" 在弹窗里显示成 "&amp;"
    if (dlUnbind) dlUnbind.onclick = () => PG.ui.confirm("解绑设备", "确认解绑 " + d.deviceId + "？解绑后设备归属清除、设备令牌作废，孩子端将失去管控，操作不可恢复。", async () => {
      try {
        await PG.api.unbindDevice(d.deviceId);
      } catch (e) {
        PG.ui.toast("err", "解绑失败", (e && e.message) || "");
        return;
      }
      PG.ui.toast("ok", "已解绑", d.deviceId);
      dl.close();
      load(document.getElementById("view"));
    }, true);
  }

  function debounce(fn, ms) { let t; return () => { clearTimeout(t); t = setTimeout(fn, ms); }; }
})(window.PG);
