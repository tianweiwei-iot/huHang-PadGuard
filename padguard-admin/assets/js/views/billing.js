/* 订阅与计费管理 */
(function (PG) {
  const U = PG.ui;
  PG.registerView("billing", {
    title: "订阅与计费", icon: "💎", group: "运营",
    render: () => `
      <div class="page-head"><div><h2>订阅与计费</h2><p>套餐定价、订阅生命周期、订单与退款、营收报表</p></div>
      <div class="page-actions"><button class="btn btn-primary" id="b-manual">＋ 手动开通订阅</button></div></div>
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
      root.querySelector("#b-manual").onclick = openManual;
      const tabs = root.querySelectorAll(".tab");
      const show = (t) => { tabs.forEach(b => b.classList.toggle("btn-primary", b.dataset.tab === t)); renderTab(root, t); };
      tabs.forEach(b => b.onclick = () => show(b.dataset.tab));
      show("plans");
    },
  });

  async function renderTab(root, t) {
    const el = root.querySelector("#b-tab");
    if (t === "plans") {
      const plans = await PG.api.listPlans();
      el.innerHTML = '<div class="grid grid-3">' + plans.map(p => `<div class="card">
        <div class="between"><h3 style="margin:0">${U.esc(p.name)}</h3>${p.id === "yearly" ? '<span class="badge purple">推荐</span>' : ""}</div>
        <div style="font-size:26px;font-weight:800;margin:10px 0">${p.priceFen ? U.money(p.priceFen) + '<span class="muted" style="font-size:13px;font-weight:400"> / 期</span>' : "免费"}</div>
        <div class="stat-line"><span>设备额度</span><span>${p.deviceLimit} 台</span></div>
        <ul style="margin:10px 0 0;padding-left:18px;color:var(--text-2);font-size:13px">${p.features.map(f => "<li>" + U.esc(f) + "</li>").join("")}</ul>
      </div>`).join("") + '</div>';
    } else if (t === "subs") {
      const subs = await PG.api.listSubscriptions({});
      el.innerHTML = '<div class="row wrap" style="margin-bottom:12px;gap:8px"><select id="s-st" class="btn" style="height:36px"><option value="">全部状态</option><option value="active">生效中</option><option value="trial">试用</option><option value="expired">已过期</option></select></div><div class="table-wrap"><table class="tbl"><thead><tr><th>家长</th><th>套餐</th><th>状态</th><th>开始</th><th>到期</th><th>自动续费</th><th>渠道</th></tr></thead><tbody id="s-body"></tbody></table></div>';
      const draw = (st) => { const f = st ? subs.filter(s => s.status === st) : subs; root.querySelector("#s-body").innerHTML = f.map(s => `<tr><td>${U.esc(s.parentName)}<div class="cell-sub">${U.esc(s.parentPhone)}</div></td><td>${U.esc(s.planName)}</td><td>${U.badge(U.SUB_STATUS, s.status)}</td><td class="cell-sub">${U.esc(s.startedAt)}</td><td class="cell-sub">${U.esc(s.expiredAt)}</td><td>${s.autoRenew ? "是" : "否"}</td><td class="muted">${U.esc(s.channel)}</td></tr>`).join("") || '<tr><td colspan="7" class="empty">暂无数据</td></tr>'; };
      draw(""); root.querySelector("#s-st").onchange = (e) => draw(e.target.value);
    } else if (t === "orders") {
      const orders = await PG.api.listOrders({});
      el.innerHTML = '<div class="row wrap" style="margin-bottom:12px;gap:8px"><select id="o-st" class="btn" style="height:36px"><option value="">全部</option><option value="paid">已支付</option><option value="refunded">已退款</option><option value="unpaid">待支付</option></select></div><div class="table-wrap"><table class="tbl"><thead><tr><th>订单号</th><th>家长</th><th>套餐</th><th>金额</th><th>状态</th><th>渠道</th><th>创建时间</th><th>操作</th></tr></thead><tbody id="o-body"></tbody></table></div>';
      const draw = (st) => { const f = st ? orders.filter(o => o.status === st) : orders; root.querySelector("#o-body").innerHTML = f.map(o => `<tr><td class="cell-strong">${U.esc(o.orderNo)}</td><td>${U.esc(o.parentName)}<div class="cell-sub">${U.esc(o.parentPhone)}</div></td><td>${U.esc(o.planName)}</td><td class="cell-strong">${U.money(o.amountFen)}</td><td>${U.badge(U.ORDER_STATUS, o.status)}</td><td class="muted">${U.esc(o.channel)}</td><td class="cell-sub">${U.esc(o.createdAt)}</td><td>${o.status === "paid" ? '<button class="btn btn-xs" data-ref="' + U.esc(o.orderNo) + '">退款</button>' : "—"}</td></tr>`).join("") || '<tr><td colspan="8" class="empty">暂无数据</td></tr>';
        el.querySelectorAll("[data-ref]").forEach(b => b.onclick = () => PG.ui.confirm("订单退款", "确认对订单 " + b.dataset.ref + " 发起退款？", async () => { await PG.api.refund(b.dataset.ref); PG.ui.toast("ok", "已退款", b.dataset.ref); renderTab(root, "orders"); }, true)); };
      draw(""); root.querySelector("#o-st").onchange = (e) => draw(e.target.value);
    } else if (t === "revenue") {
      const rev = await PG.api.revenue();
      const total = rev.reduce((s, r) => s + r.amount, 0);
      const newSubs = rev.reduce((s, r) => s + r.newSubs, 0);
      const paid = rev.filter(r => r.amount > 0).length;
      el.innerHTML = `<div class="grid grid-4" style="margin-bottom:14px">
        ${mini("累计营收", U.moneyK(total))}${mini("近 12 月新增订阅", newSubs + " 单")}${mini("月均营收", U.moneyK(total / 12))}${mini("付费月份", paid + " / 12")}
      </div>
      <div class="card" style="box-shadow:none"><div class="card-head"><h3>月度营收与新增订阅</h3><span class="sub">近 12 个月</span></div>${U.line(rev.map(r => r.amount), { h: 220 })}</div>
      <div class="table-wrap" style="margin-top:14px"><table class="tbl"><thead><tr><th>月份</th><th>营收</th><th>新增订阅</th><th>流失</th></tr></thead><tbody>${rev.map(r => '<tr><td class="cell-strong">' + U.esc(r.month) + '</td><td>' + U.money(r.amount) + '</td><td>' + r.newSubs + '</td><td class="muted">' + r.churn + '</td></tr>').join("")}</tbody></table></div>`;
    }
  }

  function mini(label, val) { return '<div class="mini-stat"><span class="ms-val">' + val + '</span><span class="ms-label">' + label + '</span></div>'; }

  async function openManual() {
    // 套餐取自服务端真实可购套餐（Mock 已清空，用 PG.Mock.PLANS 会得到空下拉——与 parents.js 同款修复）
    const list = ((await PG.api.listPlans()) || []).filter(p => p.id !== "free");
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
      const phone = m.el.querySelector("#m-phone").value.trim();
      if (!/^1\d{10}$/.test(phone)) { PG.ui.toast("err", "手机号格式不正确"); return; }
      try {
        await PG.api.manualActivate(phone, m.el.querySelector("#m-plan").value, parseInt(m.el.querySelector("#m-days").value) || 365);
      } catch (e) {
        PG.ui.toast("err", (e && e.message) ? e.message : "开通失败，请重试");
        return;
      }
      m.close(); PG.ui.toast("ok", "订阅已开通", phone); PG.router.refresh();
    };
  }
})(window.PG);
