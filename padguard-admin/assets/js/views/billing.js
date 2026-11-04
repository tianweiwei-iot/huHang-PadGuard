/* 订阅与计费管理 */
(function (PG) {
  const U = PG.ui;
  PG.registerView("billing", {
    title: "订阅与计费", icon: "💎", group: "运营",
    render: () => `
      <div class="page-head"><div><h2>订阅与计费</h2><p>套餐定价、订阅生命周期、订单与退款、营收报表</p></div>
      <div class="page-actions">${PG.guard("billing", "manual", '<button class="btn btn-primary" id="b-manual">＋ 手动开通订阅</button>')}</div></div>
      <div class="card">
        <div class="row" style="gap:8px;margin-bottom:14px;border-bottom:1px solid var(--border-soft);padding-bottom:10px">
          <button class="btn btn-xs btn-primary tab" data-tab="plans">套餐</button>
          <button class="btn btn-xs tab" data-tab="subs">订阅</button>
          <button class="btn btn-xs tab" data-tab="orders">订单</button>
          <button class="btn btn-xs tab" data-tab="revenue">营收报表</button>
        </div>
        <div id="b-tab"></div>
      </div>`,
    mount: async (root) => {
      // 资金操作按后端 RBAC 隐藏：无 billing:manual 的账号连按钮都不该看见（点开吃 403 是更差的体验）。
      const manualBtn = root.querySelector("#b-manual");
      if (manualBtn) manualBtn.onclick = openManual;
      const tabs = root.querySelectorAll(".tab");
      const show = (t) => {
        tabs.forEach(b => b.classList.toggle("btn-primary", b.dataset.tab === t));
        // renderTab 内部已有 try/catch，但"取 #b-tab 节点"和"catch 里写 DOM"这两步仍在保护之外。
        // 没有这层兜底时，任何意外都会变成一个 rejected promise（没人 await）→
        // 界面停在"加载中…"且控制台一条 unhandledrejection，运营只会说"点了 Tab 没反应"。
        renderTab(root, t).catch((e) => {
          const el = root.querySelector("#b-tab");
          if (el) el.innerHTML = '<div class="empty">加载失败，请重试</div>';
          PG.ui.toast("err", "加载失败", (e && e.message) || "");
        });
      };
      tabs.forEach(b => b.onclick = () => show(b.dataset.tab));
      show("plans");
    },
  });

  const PAGE_SIZE = 20;
  // tab 级竞态防护：seq 只保护 tab 内部的筛选/分页，tab 切换共用同一个 #b-tab 节点，
  // 慢的旧 tab 响应会把新 tab 的 DOM 整个替换掉（后续 querySelector 返回 null → 一路 TypeError
  // → 外层 catch 把新 tab 内容覆盖成"加载失败"）。
  let tabSeq = 0;

  async function renderTab(root, t) {
    const my = ++tabSeq;
    const el = root.querySelector("#b-tab");
    // 统一兜底：任何 tab 的加载失败都必须可见可重试，不能停在"加载中…"。
    el.innerHTML = '<div class="empty">加载中…</div>';
    // 竞态出口：任何 await 之后 tab 已被再次切换，一律静默放弃本次渲染。
    const stale = () => my !== tabSeq;
    try {
      if (t === "plans") {
        const plans = (await PG.api.listPlans()) || [];
        if (stale()) return;
        if (!plans.length) { el.innerHTML = '<div class="empty">暂无套餐</div>'; return; }
        el.innerHTML = '<div class="grid grid-3">' + plans.map(p => `<div class="card">
          <div class="between"><h3 style="margin:0">${U.esc(p.name)}</h3>${/^yearly$/i.test(String(p.id || "")) ? '<span class="badge purple">推荐</span>' : ""}</div>
          <div style="font-size:26px;font-weight:800;margin:10px 0">${p.priceFen ? U.money(p.priceFen) + '<span class="muted" style="font-size:13px;font-weight:400"> / 期</span>' : "免费"}</div>
          <div class="stat-line"><span>设备额度</span><span>${U.esc(p.deviceLimit)} 台</span></div>
          <ul style="margin:10px 0 0;padding-left:18px;color:var(--text-2);font-size:13px">${(p.features || []).map(f => "<li>" + U.esc(f) + "</li>").join("")}</ul>
        </div>`).join("") + '</div>';
      } else if (t === "subs") {
        el.innerHTML = '<div class="row wrap" style="margin-bottom:12px;gap:8px"><select id="s-st" class="btn" style="height:36px"><option value="">全部状态</option><option value="active">生效中</option><option value="trial">试用</option><option value="expired">已过期</option></select></div><div id="s-wrap"><div class="empty">加载中…</div></div><div id="s-pager"></div>';
        // 筛选走服务端（?status=）；分页同理 —— 拉全量灌浏览器内存且运营无法判断规模。
        let page = 1, seq = 0;
        const load = async () => {
          const my = ++seq;
          const st = root.querySelector("#s-st") ? root.querySelector("#s-st").value : "";
          try {
            const resp = await PG.api.listSubscriptions({ status: st || undefined, page, size: PAGE_SIZE });
            if (my !== seq) return; // 竞态：慢的旧响应不得覆盖新筛选结果
            const list = (resp && resp.items) || [];
            const wrap = root.querySelector("#s-wrap");
            if (!list.length) {
              wrap.innerHTML = '<div class="empty">' + (st ? "该状态下暂无订阅" : "暂无订阅") + '</div>';
              root.querySelector("#s-pager").innerHTML = "";
              return;
            }
            wrap.innerHTML = '<div class="table-wrap"><table class="tbl"><thead><tr><th>家长</th><th>套餐</th><th>状态</th><th>开始</th><th>到期</th><th>自动续费</th><th>渠道</th></tr></thead><tbody>' +
              list.map(s => `<tr><td>${U.esc(s.parentName)}<div class="cell-sub">${U.esc(s.parentPhone)}</div></td><td>${U.esc(s.planName)}</td><td>${U.badge(U.SUB_STATUS, s.status)}</td><td class="cell-sub">${U.esc(s.startedAt)}</td><td class="cell-sub">${U.esc(s.expiredAt)}</td><td>${s.autoRenew ? "是" : "否"}</td><td class="muted">${U.esc(s.channel)}</td></tr>`).join("") +
              '</tbody></table></div>';
            const pagerEl = root.querySelector("#s-pager");
            pagerEl.innerHTML = U.pager(resp.page, resp.totalPages);
            pagerEl.querySelectorAll("[data-pg]").forEach(b => b.onclick = () => { page = parseInt(b.dataset.pg, 10); load(); });
          } catch (e) {
            if (my !== seq) return;
            root.querySelector("#s-wrap").innerHTML = '<div class="empty">加载失败：' + U.esc((e && e.message) || "未知错误") + '</div>';
            root.querySelector("#s-pager").innerHTML = "";
            PG.ui.toast("err", "订阅列表加载失败", (e && e.message) || "");
          }
        };
        await load();
        // 切换筛选必须回到第 1 页，否则会停在旧筛选的页码上显示空列表
        root.querySelector("#s-st").onchange = () => { page = 1; load(); };
      } else if (t === "orders") {
        el.innerHTML = '<div class="row wrap" style="margin-bottom:12px;gap:8px"><select id="o-st" class="btn" style="height:36px"><option value="">全部</option><option value="paid">已支付</option><option value="refunded">已退款</option><option value="unpaid">待支付</option></select></div><div id="o-wrap"><div class="empty">加载中…</div></div><div id="o-pager"></div>';
        let page = 1, seq = 0;
        const load = async () => {
          const my = ++seq;
          const st = root.querySelector("#o-st") ? root.querySelector("#o-st").value : "";
          try {
            const resp = await PG.api.listOrders({ status: st || undefined, page, size: PAGE_SIZE });
            if (my !== seq) return;
            const list = (resp && resp.items) || [];
            const wrap = root.querySelector("#o-wrap");
            if (!list.length) {
              wrap.innerHTML = '<div class="empty">' + (st ? "该状态下暂无订单" : "暂无订单") + '</div>';
              root.querySelector("#o-pager").innerHTML = "";
              return;
            }
            wrap.innerHTML = '<div class="table-wrap"><table class="tbl"><thead><tr><th>订单号</th><th>家长</th><th>套餐</th><th>金额</th><th>状态</th><th>渠道</th><th>创建时间</th><th>操作</th></tr></thead><tbody>' +
              list.map(o => `<tr><td class="cell-strong">${U.esc(o.orderNo)}</td><td>${U.esc(o.parentName)}<div class="cell-sub">${U.esc(o.parentPhone)}</div></td><td>${U.esc(o.planName)}</td><td class="cell-strong">${U.money(o.amountFen)}</td><td>${U.badge(U.ORDER_STATUS, o.status)}</td><td class="muted">${U.esc(o.channel)}</td><td class="cell-sub">${U.esc(o.createdAt)}</td><td>${o.status === "paid" ? PG.guard("billing", "refund", '<button class="btn btn-xs" data-ref="' + U.esc(o.orderNo) + '">退款</button>') : "—"}</td></tr>`).join("") +
              '</tbody></table></div>';
            const pagerEl = root.querySelector("#o-pager");
            pagerEl.innerHTML = U.pager(resp.page, resp.totalPages);
            pagerEl.querySelectorAll("[data-pg]").forEach(b => b.onclick = () => { page = parseInt(b.dataset.pg, 10); load(); });
            wrap.querySelectorAll("[data-ref]").forEach(b => b.onclick = () => PG.ui.confirm("订单退款", "确认对订单 " + b.dataset.ref + " 发起退款？退款将原路退回，不可撤销。", async () => {
              try {
                await PG.api.refund(b.dataset.ref);
                PG.ui.toast("ok", "已退款", b.dataset.ref);
                load();
              } catch (e) {
                PG.ui.toast("err", "退款失败", (e && e.message) || "");
              }
            }, true));
          } catch (e) {
            if (my !== seq) return;
            root.querySelector("#o-wrap").innerHTML = '<div class="empty">加载失败：' + U.esc((e && e.message) || "未知错误") + '</div>';
            root.querySelector("#o-pager").innerHTML = "";
            PG.ui.toast("err", "订单列表加载失败", (e && e.message) || "");
          }
        };
        await load();
        root.querySelector("#o-st").onchange = () => { page = 1; load(); };
      } else if (t === "revenue") {
        const rev = (await PG.api.revenue()) || [];
        if (stale()) return;
        if (!Array.isArray(rev) || !rev.length) { el.innerHTML = '<div class="empty">暂无营收数据</div>'; return; }
        const total = rev.reduce((s, r) => s + (r.amount || 0), 0);
        const newSubs = rev.reduce((s, r) => s + (r.newSubs || 0), 0);
        const paid = rev.filter(r => (r.amount || 0) > 0).length;
        el.innerHTML = `<div class="grid grid-4" style="margin-bottom:14px">
          ${mini("累计营收", U.moneyK(total))}${mini("近 12 月新增订阅", newSubs + " 单")}${mini("月均营收", U.moneyK(total / 12))}${mini("付费月份", paid + " / 12")}
        </div>
        <div class="card" style="box-shadow:none"><div class="card-head"><h3>月度营收与新增订阅</h3><span class="sub">近 12 个月</span></div>${U.line(rev.map(r => r.amount || 0), { h: 220 })}</div>
        <div class="table-wrap" style="margin-top:14px"><table class="tbl"><thead><tr><th>月份</th><th>营收</th><th>新增订阅</th><th>流失</th></tr></thead><tbody>${rev.map(r => '<tr><td class="cell-strong">' + U.esc(r.month) + '</td><td>' + U.money(r.amount || 0) + '</td><td>' + U.esc(r.newSubs || 0) + '</td><td class="muted">' + U.esc(r.churn || 0) + '</td></tr>').join("")}</tbody></table></div>`;
      }
    } catch (e) {
      // tab 已切换时静默放弃：旧的错误覆盖新 tab 内容是最差结局。
      if (stale()) return;
      el.innerHTML = '<div class="empty">加载失败：' + U.esc((e && e.message) || "未知错误") + '</div>';
      PG.ui.toast("err", "加载失败", (e && e.message) || "");
    }
  }

  function mini(label, val) { return '<div class="mini-stat"><span class="ms-val">' + val + '</span><span class="ms-label">' + label + '</span></div>'; }

  async function openManual() {
    // 套餐取自服务端真实可购套餐（Mock 已清空，用 PG.Mock.PLANS 会得到空下拉——与 parents.js 同款修复）
    // 免费档必须剔除：手工把付费家长降到"免费试用"是不可逆的资费损失。
    // 原来的过滤条件写的是 p.id !== "free"，而实际 key 是 TRIAL（见 BillingModels.kt），
    // 条件恒不成立＝免费试用一直留在下拉里。这里按 key 而不是按展示名匹配。
    let list = [];
    try {
      list = ((await PG.api.listPlans()) || []).filter(p => !/^(TRIAL|FREE)$/i.test(String(p.id || "")));
    } catch (e) {
      PG.ui.toast("err", "套餐加载失败", (e && e.message) || "");
      return;
    }
    if (!list.length) { PG.ui.toast("err", "没有可开通的套餐", "请检查服务端套餐配置"); return; }
    const plans = list.map(p => '<option value="' + U.esc(p.id) + '">' + U.esc(p.name) + (p.priceFen ? '（' + U.money(p.priceFen) + '/期）' : '') + '</option>').join("");
    const m = PG.ui.modal({
      title: "手动开通 / 延长订阅", width: 460,
      body: `<div class="field"><label>家长手机号</label><input id="m-phone" placeholder="如 13800000000"></div>
        <div class="form-row"><div class="field"><label>套餐</label><select id="m-plan">${plans}</select></div>
        <div class="field"><label>时长（天）</label><input id="m-days" value="365"></div></div>
        <p class="muted" style="font-size:12px">用于内部赠送、渠道兑换或线下收款后补开。会同步延长到期时间，不新增重复记录。</p>`,
      foot: '<button class="btn btn-ghost" id="m-cancel">取消</button><button class="btn btn-primary" id="m-ok">确认开通</button>',
    });
    m.el.querySelector("#m-cancel").onclick = m.close;
    m.el.querySelector("#m-ok").onclick = async () => {
      const okBtn = m.el.querySelector("#m-ok");
      // 防重复提交：这个按钮每点一次都会给订阅加 days 天，慢网下双击 = 白送一年（不可逆资损）。
      // 先置 disabled 再发请求，失败时恢复。
      if (okBtn.disabled) return;
      const phone = m.el.querySelector("#m-phone").value.trim();
      if (!/^1\d{10}$/.test(phone)) { PG.ui.toast("err", "手机号格式不正确"); return; }
      // 显式范围校验：parseInt 静默吞 0/负数会把"输错"变成"送一年/送一万天"，都是资损
      const days = parseInt(m.el.querySelector("#m-days").value, 10);
      if (!Number.isFinite(days) || days < 1 || days > 3650) {
        PG.ui.toast("err", "时长不合法", "请输入 1~3650 之间的天数");
        return;
      }
      okBtn.disabled = true;
      const oldText = okBtn.textContent;
      okBtn.textContent = "提交中…";
      try {
        await PG.api.manualActivate(phone, m.el.querySelector("#m-plan").value, days);
      } catch (e) {
        okBtn.disabled = false;
        okBtn.textContent = oldText;
        PG.ui.toast("err", (e && e.message) ? e.message : "开通失败，请重试");
        return;
      }
      m.close(); PG.ui.toast("ok", "订阅已开通", phone); PG.router.refresh();
    };
  }
})(window.PG);
