/* 系统设置 */
(function (PG) {
  const U = PG.ui;
  PG.registerView("settings", {
    title: "系统设置", icon: "⚙️", group: "系统",
    render: () => `
      <div class="page-head"><div><h2>系统设置</h2><p>品牌、计费策略、安全策略与套餐定价（运营可调，无需改代码）</p></div>
      <div class="page-actions"><button class="btn btn-primary" id="st-save">保存设置</button></div></div>
      <div id="st-body"><div class="empty">加载中…</div></div>`,
    mount: async (root) => {
      const s = await PG.api.settings();
      root.querySelector("#st-save").onclick = async () => save(root);
      root.querySelector("#st-body").innerHTML = `
      <div class="grid grid-2">
        <div class="card">
          <div class="card-head"><h3>基础</h3></div>
          <div class="form-row"><div class="field"><label>品牌名称</label><input id="st-brand" value="${s.brand}"></div><div class="field"><label>结算币种</label><select id="st-cur"><option>CNY</option><option>USD</option></select></div></div>
          <div class="field"><label>免费试用天数</label><input id="st-trial" type="number" value="${s.trialDays}"></div>
        </div>
        <div class="card">
          <div class="card-head"><h3>安全策略</h3></div>
          <div class="field"><label>密码强度策略</label><select id="st-pwd"><option value="low">宽松</option><option value="medium" ${s.passwordPolicy === "medium" ? "selected" : ""}>中等</option><option value="high">强</option></select></div>
          <label class="row" style="gap:10px;align-items:center;cursor:pointer"><input type="checkbox" id="st-mfa" ${s.mfaRequired ? "checked" : ""} style="width:18px"> 管理员强制开启 MFA（双因素认证）</label>
          <label class="row" style="gap:10px;align-items:center;cursor:pointer;margin-top:10px"><input type="checkbox" id="st-mock" ${s.mockPay ? "checked" : ""} style="width:18px"> 启用模拟支付（仅测试环境，生产必须关闭）</label>
        </div>
      </div>
      <div class="card" style="margin-top:16px">
        <div class="card-head"><h3>套餐定价</h3><span class="sub">价格单位：分</span></div>
        <div class="table-wrap"><table class="tbl"><thead><tr><th>套餐</th><th>单价（分）</th><th>设备额度</th><th>核心权益</th></tr></thead><tbody>
          ${s.plans.map(p => `<tr><td class="cell-strong">${p.name}</td><td><input class="btn" style="height:34px;width:120px" data-price="${p.id}" value="${p.priceFen}"></td><td>${p.deviceLimit}</td><td class="cell-sub">${p.features.join(" / ")}</td></tr>`).join("")}
        </tbody></table></div>
        <p class="muted" style="font-size:12px;margin-top:10px">金额一律以「分」存储（避免浮点误差）；价格调整即时生效于新下单，不影响已购订阅。</p>
      </div>`;
    },
  });

  async function save(root) {
    const plans = PG.Mock.PLANS.map(p => ({ id: p.id, priceFen: parseInt(root.querySelector('[data-price="' + p.id + '"]').value) || 0 }));
    const body = {
      brand: root.querySelector("#st-brand").value, currency: root.querySelector("#st-cur").value,
      trialDays: parseInt(root.querySelector("#st-trial").value) || 14, passwordPolicy: root.querySelector("#st-pwd").value,
      mfaRequired: root.querySelector("#st-mfa").checked, mockPay: root.querySelector("#st-mock").checked, plans,
    };
    await PG.api.saveSettings(body);
    PG.ui.toast("ok", "设置已保存", "部分项需重启服务端生效");
  }
})(window.PG);
