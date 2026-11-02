/* 家长账号管理 */
(function (PG) {
  const U = PG.ui;
  PG.registerView("parents", {
    title: "家长账号", icon: "👨‍👩‍👧", group: "运营",
    render: () => `
      <div class="page-head"><div><h2>家长账号管理</h2><p>开通、授权、冻结与重置家长账号；绑定设备与订阅状态一目了然</p></div>
      <div class="page-actions">${PG.guard("parents", "create", '<button class="btn btn-primary" id="p-create">＋ 新建账号</button>')}</div></div>
      <div class="card">
        <div class="row wrap" style="margin-bottom:14px">
          <div class="search-box" style="flex:1;min-width:220px"><input id="p-kw" placeholder="手机号 / 姓名 / 账号ID" style="width:100%"></div>
          <select id="p-status" class="btn" style="height:40px;width:130px"><option value="">全部状态</option><option value="active">正常</option><option value="suspended">已冻结</option><option value="pending">待激活</option></select>
          <select id="p-plan" class="btn" style="height:40px;width:150px"><option value="">全部套餐</option><option value="free">免费版</option><option value="monthly">月度/季度会员</option><option value="yearly">年度会员</option><option value="permanent">永久会员</option></select>
          <button class="btn" id="p-reset">重置</button>
        </div>
        <div class="table-wrap"><table class="tbl"><thead><tr>
          <th>账号ID</th><th>家长</th><th>手机号</th><th>状态</th><th>套餐</th><th>设备/孩子</th><th>区域</th><th>注册时间</th><th>最近登录</th><th>操作</th>
        </tr></thead><tbody id="p-body"></tbody></table></div>
        <div id="p-empty"></div>
        <div id="p-pager"></div>
      </div>`,
    mount: async (root) => {
      // 「新建家长」只在有 parents:create 时渲染；客服/财务/审计打开本页时该节点为 null，
      // 判空后仍然能正常看列表（此前会整页「加载失败」）。
      U.bind(root, "#p-create", openCreate);
      // 筛选条件变化（或顶栏搜索回填）应回到第 1 页，否则可能在已无数据的页码上停留
      const reloadReset = () => { curPage = 1; load(root); };
      // 顶栏全局搜索跳转过来时回填关键字（消费后立即清空，避免下次进页面误命中）
      if (PG.state && PG.state.search) {
        root.querySelector("#p-kw").value = PG.state.search;
        PG.state.search = "";
        curPage = 1;
      }
      root.querySelector("#p-reset").onclick = () => { root.querySelector("#p-kw").value = ""; root.querySelector("#p-status").value = ""; root.querySelector("#p-plan").value = ""; reloadReset(); };
      root.querySelector("#p-kw").oninput = debounce(reloadReset, 300);
      root.querySelector("#p-status").onchange = reloadReset;
      root.querySelector("#p-plan").onchange = reloadReset;
      await load(root);
    },
  });

  let curPage = 1;
  const PAGE_SIZE = 20;
  // 第八轮 P2-9：请求序号防竞态——快速翻页/输入搜索时，慢的旧响应不得覆盖新结果
  let loadSeq = 0;

  async function load(root) {
    const seq = ++loadSeq;
    const q = { keyword: root.querySelector("#p-kw").value.trim(), status: root.querySelector("#p-status").value, plan: root.querySelector("#p-plan").value, page: curPage, size: PAGE_SIZE };
    let resp;
    try {
      resp = await PG.api.listParents(q);
    } catch (e) {
      if (seq !== loadSeq) return; // 已有更新的请求，旧失败忽略
      PG.ui.toast("err", "加载失败", String((e && e.message) || e));
      return;
    }
    if (seq !== loadSeq) return; // 旧响应到达时已有新请求在途，丢弃
    const list = (resp && resp.items) || [];
    const tb = root.querySelector("#p-body");
    if (!list.length) { tb.innerHTML = ""; root.querySelector("#p-empty").innerHTML = '<div class="empty">没有匹配的家长账号</div>'; root.querySelector("#p-pager").innerHTML = ""; return; }
    root.querySelector("#p-empty").innerHTML = "";
    tb.innerHTML = list.map(p => `<tr>
      <td class="cell-strong">${U.esc(p.id)}</td>
      <td><div class="cell-strong">${U.esc(p.name)}</div>${(p.tags || []).map(t => '<span class="badge blue" style="margin-left:4px">' + U.esc(t) + '</span>').join("")}</td>
      <td>${U.esc(p.phone)}</td>
      <td>${U.badge(U.PARENT_STATUS, p.status)}</td>
      <td>${U.esc(p.planName)}</td>
      <td>${p.deviceCount} / ${p.childrenCount}</td>
      <td class="muted">${U.esc(p.region)}</td>
      <td class="cell-sub">${U.esc(p.registeredAt)}</td>
      <td class="cell-sub">${U.esc(p.lastLoginAt || "—")}</td>
      <td><div class="row-actions">
        <button class="btn btn-xs" data-detail="${U.esc(p.id)}">详情</button>
        ${p.status === "suspended" ? PG.guard("parents", "write", '<button class="btn btn-xs btn-primary" data-act="activate" data-id="' + U.esc(p.id) + '">激活</button>') : PG.guard("parents", "suspend", '<button class="btn btn-xs" data-act="suspend" data-id="' + U.esc(p.id) + '">冻结</button>')}
        ${PG.guard("parents", "write", '<button class="btn btn-xs" data-act="edit" data-id="' + U.esc(p.id) + '">编辑</button>')}
        ${PG.guard("parents", "reset_pwd", '<button class="btn btn-xs" data-act="reset" data-id="' + U.esc(p.id) + '">重置密码</button>')}
        ${PG.guard("parents", "delete", '<button class="btn btn-xs btn-danger" data-act="del" data-id="' + U.esc(p.id) + '">删除</button>')}
      </div></td>
    </tr>`).join("");
    tb.querySelectorAll("[data-detail]").forEach(b => b.onclick = () => openDetail(b.dataset.detail));
    tb.querySelectorAll("[data-act='suspend']").forEach(b => b.onclick = () => PG.ui.confirm("冻结账号", "确认冻结 " + b.dataset.id + "？冻结后该家长无法登录、其下设备策略暂停下发。", async () => { await PG.api.suspendParent(b.dataset.id); PG.ui.toast("ok", "已冻结", b.dataset.id); load(root); }, true));
    tb.querySelectorAll("[data-act='activate']").forEach(b => b.onclick = async () => {
      try {
        await PG.api.activateParent(b.dataset.id);
      } catch (e) {
        PG.ui.toast("err", "激活失败", (e && e.message) || "");
        return;
      }
      PG.ui.toast("ok", "已激活", b.dataset.id);
      load(root);
    });
    tb.querySelectorAll("[data-act='edit']").forEach(b => b.onclick = () => openEdit(b.dataset.id));
    tb.querySelectorAll("[data-act='reset']").forEach(b => b.onclick = () => resetPwd(b.dataset.id));
    tb.querySelectorAll("[data-act='del']").forEach(b => b.onclick = () => delParent(b.dataset.id));
    // 分页器（B3/B4）：渲染上/下一页与页码，点击翻页
    const pagerEl = root.querySelector("#p-pager");
    pagerEl.innerHTML = U.pager(resp.page, resp.totalPages);
    pagerEl.querySelectorAll("[data-pg]").forEach(b => b.onclick = () => { curPage = parseInt(b.dataset.pg, 10); load(root); });
  }

  async function resetPwd(id) {
    // 展示用手机号取自服务端（此前直接拼 id，弹窗里是一串 UUID）
    const p = await PG.api.getParent(id).catch(() => null);
    const phone = (p && p.phone) || id;
    // 注意：后端目前没有"强制下次登录修改"的实现，文案必须如实，不得承诺不存在的约束
    // 注意：confirm 内部会对 text 做一次 esc，这里传原始值——双重转义会把引号显示成 &#39;
    PG.ui.confirm("重置密码", "将为家长 " + phone + " 生成随机强密码（明文仅显示一次），原密码立即失效。请转告家长并提醒其尽快修改。", async () => {
      const r = await PG.api.resetParentPwd(id);
      const emailed = r && r.emailed;
      // newPwd 缺失时显示 "—"（绝不能现场编一个假密码展示）
      const m = PG.ui.modal({ title: "新密码", body: '<div class="stat-line"><span>账号</span><span>' + U.esc(phone) + '</span></div><div class="stat-line"><span>临时密码</span><span style="font-family:monospace;color:var(--brand)">' + U.esc(r.newPwd || "—") + '</span></div><p class="muted" style="font-size:12px">' + (emailed ? "新密码已同步发送至家长登记邮箱。" : "请通过安全渠道发送给家长，并提醒其尽快修改。") + '</p>', foot: '<button class="btn btn-primary" id="ok">知道了</button>' });
      m.el.querySelector("#ok").onclick = m.close;
      PG.ui.toast("ok", "密码已重置", phone);
    });
  }

  function openEdit(id) {
    PG.api.getParent(id).then(p => {
      if (!p) { PG.ui.toast("err", "未找到", id); return; }
      const m = PG.ui.modal({
        title: "编辑家长账号 · " + (p.name || p.phone), width: 480,
        body: `<div class="field"><label>家长姓名</label><input id="e-name" value="${U.esc(p.name || "")}" placeholder="如 张伟"></div>
          <div class="field"><label>手机号</label><input value="${U.esc(p.phone)}" disabled style="background:var(--bg-soft)"></div>
          <div class="field"><label>邮箱（选填）</label><input id="e-email" value="${U.esc(p.email || "")}" placeholder="name@example.com"></div>
          <div class="form-row">
            <div class="field"><label>区域 / 渠道</label><input id="e-region" value="${U.esc(p.region || "")}" placeholder="如 北京"></div>
          </div>
          <div class="field"><label>备注</label><input id="e-remark" value="${U.esc(p.remark || "")}" placeholder="渠道 / 来源等"></div>
          <p class="muted" style="font-size:12px">手机号为登录主键，不可在此修改；如需更换请新建账号并迁移设备。</p>`,
        foot: '<button class="btn btn-ghost" id="e-cancel">取消</button><button class="btn btn-primary" id="e-ok">保存</button>',
      });
      m.el.querySelector("#e-cancel").onclick = m.close;
      m.el.querySelector("#e-ok").onclick = async () => {
        const body = {
          name: m.el.querySelector("#e-name").value.trim(),
          email: m.el.querySelector("#e-email").value.trim(),
          region: m.el.querySelector("#e-region").value.trim(),
          remark: m.el.querySelector("#e-remark").value.trim(),
        };
        // 保存失败必须留在弹窗里并说明原因：静默失败会让运营以为改成功了
        try {
          await PG.api.updateParent(id, body);
        } catch (e) {
          PG.ui.toast("err", "保存失败", (e && e.message) || "");
          return;
        }
        m.close(); PG.ui.toast("ok", "已保存", p.phone);
        PG.router.refresh();
      };
    });
  }

  function delParent(id) {
    PG.api.getParent(id).then(p => {
      if (!p) { PG.ui.toast("err", "未找到", id); return; }
      const devCount = p.deviceCount || 0;
      const warn = devCount > 0
        ? '<p style="color:var(--danger);font-size:12px;margin-top:8px">该账号下仍有 ' + devCount + ' 台已绑定设备，请先解绑或转交设备后再删除，否则将产生孤儿设备。</p>'
        : '';
      PG.ui.confirm("删除家长账号", "确认删除 " + (p.name || p.phone) + "（" + p.phone + "）？删除后该家长无法登录，且关联订阅一并清除，操作不可恢复。" + warn, async () => {
        await PG.api.deleteParent(id);
        PG.ui.toast("ok", "已删除", p.phone);
        PG.router.refresh();
      }, true);
    });
  }

  async function openDetail(id) {
    const p = await PG.api.getParent(id);
    if (!p) { PG.ui.toast("err", "未找到", id); return; }
    const d = PG.ui.drawer({
      title: "家长账号详情 · " + p.name,
      body: `
      <div class="stat-line"><span>账号ID</span><span>${U.esc(p.id)}</span></div>
      <div class="stat-line"><span>手机号</span><span>${U.esc(p.phone)}</span></div>
      <div class="stat-line"><span>邮箱</span><span>${U.esc(p.email || "—")}</span></div>
      <div class="stat-line"><span>状态</span><span>${U.badge(U.PARENT_STATUS, p.status)}</span></div>
      <div class="stat-line"><span>套餐</span><span>${U.esc(p.planName)}</span></div>
      <div class="stat-line"><span>到期时间</span><span>${U.esc(p.expiredAt)}</span></div>
      <div class="stat-line"><span>区域</span><span>${U.esc(p.region)}</span></div>
      <div class="stat-line"><span>注册 / 最近登录</span><span>${U.esc(p.registeredAt)} / ${U.esc(p.lastLoginAt || "—")}</span></div>
      <div class="stat-line"><span>绑定设备</span><span>${p.deviceCount} 台</span></div>
      <h4 style="margin:18px 0 8px">绑定设备</h4>
      ${p.devices && p.devices.length ? '<div class="table-wrap"><table class="tbl"><thead><tr><th>设备</th><th>模式</th><th>在线</th><th>今日用量</th></tr></thead><tbody>' + p.devices.map(dv => '<tr><td>' + U.esc(dv.name) + '<div class="cell-sub">' + U.esc(dv.deviceId) + '</div></td><td>' + U.esc(dv.controlMode) + '</td><td>' + (dv.online ? '<span class="dot green"></span> 在线' : '<span class="dot gray"></span> 离线') + '</td><td>' + U.fmtMinutes(dv.todayUsageMinutes) + '</td></tr>').join("") + '</tbody></table></div>' : '<div class="empty">暂无绑定设备</div>'}
      <h4 style="margin:18px 0 8px">当前订阅</h4>
      ${p.subscription ? '<div class="stat-line"><span>套餐</span><span>' + U.esc(p.subscription.planName) + '</span></div><div class="stat-line"><span>状态</span><span>' + U.badge(U.SUB_STATUS, p.subscription.status) + '</span></div><div class="stat-line"><span>自动续费</span><span>' + (p.subscription.autoRenew ? "是" : "否") + '</span></div>' : '<div class="muted">未订阅付费套餐</div>'}
      `,
      width: 480,
    });
  }

  async function openCreate() {
    // 套餐取自服务端真实可购套餐（Mock 已清空，用 PG.Mock.PLANS 会得到空下拉）
    const list = (await PG.api.listPlans()) || [];
    // 付费套餐的开通需要 billing:manual（服务端 AdminParentService 强制校验）。
    // 若运营没有该权限却仍能选付费套餐，结果会是"账号已建、订阅未开"的半成功状态，
    // 界面只弹一句无权限 —— 因此这里直接不给选项，并说明去哪开通。
    const canManual = PG.can("billing", "manual");
    const plans = '<option value="TRIAL">免费版（试用）</option>' +
      (canManual
        ? list.map(p => '<option value="' + U.esc(p.id) + '">' + U.esc(p.name) + (p.priceFen ? '（' + U.money(p.priceFen) + '/期）' : '') + '</option>').join("")
        : "");
    const planHint = canManual
      ? ""
      : '<p class="muted" style="font-size:12px;margin-top:6px">你当前没有「订阅与计费 → 手工开通」权限，只能先建免费账号；付费套餐请到「订阅与计费」页开通。</p>';
    const m = PG.ui.modal({
      title: "新建家长账号", width: 480,
      body: `<div class="field"><label>家长姓名</label><input id="c-name" placeholder="如 张伟"></div>
        <div class="field"><label>手机号</label><input id="c-phone" placeholder="11 位手机号"></div>
        <div class="field"><label>邮箱（选填）</label><input id="c-email" placeholder="name@example.com"></div>
        <div class="form-row">
          <div class="field"><label>初始套餐</label><select id="c-plan">${plans}</select></div>
          <div class="field"><label>区域</label><input id="c-region" value="北京"></div>
        </div>
        <div class="field"><label>备注</label><input id="c-remark" placeholder="渠道 / 来源等"></div>${planHint}`,
      foot: '<button class="btn btn-ghost" id="c-cancel">取消</button><button class="btn btn-primary" id="c-ok">创建并开通</button>',
    });
    m.el.querySelector("#c-cancel").onclick = m.close;
    m.el.querySelector("#c-ok").onclick = async () => {
      const phone = m.el.querySelector("#c-phone").value.trim();
      if (!/^1\d{10}$/.test(phone)) { PG.ui.toast("err", "手机号格式不正确"); return; }
      // 新建失败（如手机号已存在）必须显式提示，且弹窗保持打开以便修改重试
      let r;
      try {
        r = await PG.api.createParent({ name: m.el.querySelector("#c-name").value.trim() || "新家长", phone, email: m.el.querySelector("#c-email").value.trim(), plan: m.el.querySelector("#c-plan").value, region: m.el.querySelector("#c-region").value.trim(), remark: m.el.querySelector("#c-remark").value.trim() });
      } catch (e) {
        PG.ui.toast("err", "创建失败", (e && e.message) || "");
        return;
      }
      m.close(); PG.ui.toast("ok", "账号已创建", phone);
      PG.router.refresh();
    };
  }

  function debounce(fn, ms) { let t; return () => { clearTimeout(t); t = setTimeout(fn, ms); }; }
})(window.PG);
