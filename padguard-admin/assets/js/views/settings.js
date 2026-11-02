/* 系统设置 */
(function (PG) {
  const U = PG.ui;
  PG.registerView("settings", {
    title: "系统设置", icon: "⚙️", group: "系统",
    render: () => `
      <div class="page-head"><div><h2>系统设置</h2><p>品牌、计费策略、安全策略与套餐定价（运营可调，无需改代码）</p></div>
      <div class="page-actions">${PG.guard("settings", "write", '<button class="btn btn-primary" id="st-save">保存设置</button>')}</div></div>
      <div id="st-body"><div class="empty">加载中…</div></div>`,
    mount: async (root) => {
      let s;
      try {
        s = await PG.api.settings();
      } catch (e) {
        root.querySelector("#st-body").innerHTML =
          '<div class="empty">设置加载失败：' + U.esc((e && e.message) || "服务端未响应") +
          '<div style="margin-top:12px"><button class="btn" id="st-retry">重试</button></div></div>';
        const rt = root.querySelector("#st-retry");
        if (rt) rt.onclick = () => PG.views.settings.mount(root);
        return;
      }
      if (!s || typeof s !== "object") s = {};
      // 套餐数组与权益数组都可能是缺省/空值：直接 .map/.join 会抛 TypeError，
      // 页面永久停在"加载中…"（与计费页那次契约断裂同源）
      const plans = Array.isArray(s.plans) ? s.plans : [];
      // 「保存」按钮只在有 settings:write 时渲染，无写权限的角色拿到的是 null
      U.bind(root, "#st-save", async () => save(root));
      root.querySelector("#st-body").innerHTML = `
      <div class="grid grid-2">
        <div class="card">
          <div class="card-head"><h3>基础</h3></div>
          <div class="form-row"><div class="field"><label>品牌名称</label><input id="st-brand" value="${U.esc(s.brand)}"></div><div class="field"><label>结算币种</label><select id="st-cur">${["CNY", "USD"].map(c => "<option" + (s.currency === c ? " selected" : "") + ">" + c + "</option>").join("")}</select></div></div>
          <div class="field"><label>免费试用天数</label><input id="st-trial" type="number" min="0" max="365" value="${U.esc(s.trialDays == null ? 0 : s.trialDays)}"></div>
        </div>
        <div class="card">
          <div class="card-head"><h3>安全策略</h3></div>
          <div class="field"><label>密码强度策略</label>
            <select id="st-pwd">
              <option value="low" ${s.passwordPolicy === "low" ? "selected" : ""}>宽松（8 位，无符号）</option>
              <option value="medium" ${s.passwordPolicy === "medium" ? "selected" : ""}>中等（12 位 + 符号）</option>
              <option value="high" ${s.passwordPolicy === "high" ? "selected" : ""}>强（16 位 + 符号）</option>
            </select>
            <p class="muted" style="font-size:12px;margin-top:6px">作用于服务端生成的初始密码与重置密码（新建/重置家长、管理员账号）。</p>
          </div>
          <label class="row" style="gap:10px;align-items:center;cursor:not-allowed;opacity:.6"><input type="checkbox" id="st-mfa" disabled ${s.mfaRequired ? "checked" : ""} style="width:18px"> 管理员强制开启 MFA（双因素认证）</label>
          <p class="muted" style="font-size:12px;margin:6px 0 0 28px">
            暂未强制生效：服务端登录流程尚未接入 TOTP 校验，此处仅保存配置项。
            在此之前请勿把它当作已生效的安全控制。
          </p>
          <label class="row" style="gap:10px;align-items:center;cursor:pointer;margin-top:10px"><input type="checkbox" id="st-mock" ${s.mockPay ? "checked" : ""} style="width:18px"> 启用模拟支付（仅测试环境，生产必须关闭）</label>
          <p class="muted" style="font-size:12px;margin:6px 0 0 28px">保存后即时生效于支付渠道与模拟支付接口，无需重启服务端。</p>
        </div>
      </div>
      <div class="card" style="margin-top:16px">
        <div class="card-head"><h3>套餐定价</h3><span class="sub">价格单位：分</span></div>
        <div class="table-wrap"><table class="tbl"><thead><tr><th>套餐</th><th>单价（分）</th><th>设备额度</th><th>核心权益</th></tr></thead><tbody>
          ${plans.length ? plans.map(p => {
            const feats = Array.isArray(p.features) ? p.features.join(" / ") : "";
            return `<tr><td class="cell-strong">${U.esc(p.name)}</td><td><input class="btn" style="height:34px;width:120px" data-price="${U.esc(p.id)}" value="${U.esc(p.priceFen == null ? 0 : p.priceFen)}"></td><td>${U.esc(p.deviceLimit == null ? "-" : p.deviceLimit)}</td><td class="cell-sub">${U.esc(feats)}</td></tr>`;
          }).join("") : '<tr><td colspan="4" class="muted">暂无可售套餐（服务端 plans 为空）</td></tr>'}
        </tbody></table></div>
        <p class="muted" style="font-size:12px;margin-top:10px">金额一律以「分」存储（避免浮点误差）；价格调整即时生效于新下单，不影响已购订阅。</p>
      </div>`;
    },
  });

  async function save(root) {
    // 直接按页面上实际渲染的套餐行取 id 与价格，避免与服务端返回的 plans 不一致
    // 逐项显式校验：此前 `parseInt(v) || 0` 会把"输了字母/留空"静默存成 0 元（等于免费），
    // 而 `parseInt(v) || 14` 更糟——试用期填 0（合法的"无试用"）会被悄悄改成 14 天。
    const plans = [];
    const priceEls = [...root.querySelectorAll('[data-price]')];
    for (const el of priceEls) {
      const raw = String(el.value).trim();
      const fen = Number(raw);
      if (raw === "" || !Number.isInteger(fen) || fen < 0 || fen > 100000000) {
        PG.ui.toast("err", "价格不合法", "套餐单价必须是 0~100000000 之间的整数（单位：分）");
        el.focus();
        return;
      }
      plans.push({ id: el.dataset.price, priceFen: fen });
    }
    const trialRaw = String(root.querySelector("#st-trial").value).trim();
    const trialDays = Number(trialRaw);
    if (trialRaw === "" || !Number.isInteger(trialDays) || trialDays < 0 || trialDays > 365) {
      PG.ui.toast("err", "试用天数不合法", "请输入 0~365 之间的整数（0 表示不提供试用）");
      return;
    }
    const body = {
      brand: root.querySelector("#st-brand").value, currency: root.querySelector("#st-cur").value,
      trialDays, passwordPolicy: root.querySelector("#st-pwd").value,
      mfaRequired: root.querySelector("#st-mfa").checked, mockPay: root.querySelector("#st-mock").checked, plans,
    };
    // 第八轮 P2：保存失败必须有提示——此前 rejection 静默，管理员以为改价成功
    try {
      await PG.api.saveSettings(body);
    } catch (e) {
      PG.ui.toast("err", "保存失败", String((e && e.message) || e));
      return;
    }
    PG.ui.toast("ok", "设置已保存", "部分项需重启服务端生效");
  }
})(window.PG);
