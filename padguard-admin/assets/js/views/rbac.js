/* 权限与角色（RBAC） */
(function (PG) {
  const U = PG.ui;
  const AREAS = {
    parents: ["read", "write", "create", "suspend", "reset_pwd", "delete"],
    devices: ["read", "write", "lock", "unlock", "unbind"],
    billing: ["read", "write", "refund", "manual"],
    rbac: ["read", "write", "assign", "reset_pwd", "delete"],
    server: ["read", "write"],
    audit: ["read", "write"],
    settings: ["read", "write"],
    monitor: ["read", "snapshot", "lock", "unlock"],
  };
  const AREA_CN = { parents: "家长账号", devices: "设备管理", billing: "订阅计费", rbac: "角色权限", server: "服务端", audit: "审计日志", settings: "系统设置", monitor: "实时监控" };
  const ACT_CN = { read: "查看", write: "编辑", create: "创建", suspend: "冻结", reset_pwd: "重置密码", delete: "删除", lock: "锁屏", unlock: "解锁", unbind: "解绑", refund: "退款", manual: "手动开通", assign: "授权", snapshot: "截屏" };

  PG.registerView("rbac", {
    title: "权限与角色", icon: "🛡️", group: "系统",
    render: () => `
      <div class="page-head"><div><h2>权限与角色（RBAC）</h2><p>基于 <code>area:action</code> 动词模型的细粒度授权；前端仅展示后端下发的权限</p></div>
      <div class="page-actions"><button class="btn btn-primary" id="r-newrole">＋ 新建角色</button></div></div>
      <div class="card">
        <div class="row" style="gap:8px;margin-bottom:14px;border-bottom:1px solid var(--border-soft);padding-bottom:10px">
          <button class="btn btn-xs btn-primary tab" data-tab="roles">角色权限</button>
          <button class="btn btn-xs tab" data-tab="admins">管理员账号</button>
        </div>
        <div id="r-tab"></div>
      </div>`,
    mount: async (root) => {
      const tabs = root.querySelectorAll(".tab");
      const show = (t) => { tabs.forEach(b => b.classList.toggle("btn-primary", b.dataset.tab === t)); t === "roles" ? renderRoles(root) : renderAdmins(root); };
      tabs.forEach(b => b.onclick = () => show(b.dataset.tab));
      show("roles");
    },
  });

  async function renderRoles(root) {
    const roles = await PG.api.listRoles();
    const el = root.querySelector("#r-tab");
    root.querySelector("#r-newrole").onclick = () => newRole(root);
    el.innerHTML = '<div class="grid grid-4" id="r-cards"></div><div id="r-detail" style="margin-top:16px"></div>';
    const cards = el.querySelector("#r-cards");
    cards.innerHTML = roles.map(r => `<div class="card" data-role="${r.id}" style="cursor:pointer;border:1px solid ${r._sel ? 'var(--brand)' : 'var(--border-soft)'}">
      <div class="between"><strong>${r.name}</strong>${r.isSystem ? '<span class="badge gray">系统</span>' : '<span class="badge blue">自定义</span>'}</div>
      <div class="cell-sub" style="margin:8px 0;min-height:34px">${r.desc}</div>
      <div class="muted" style="font-size:12px">${Object.keys(r.privileges).length} 个领域 · ${Object.values(r.privileges).reduce((s, a) => s + a.length, 0)} 项权限</div>
    </div>`).join("");
    const detail = el.querySelector("#r-detail");
    cards.querySelectorAll("[data-role]").forEach(c => c.onclick = () => {
      roles.forEach(r => r._sel = r.id === c.dataset.role);
      renderRoles(root);
      drawDetail(detail, roles.find(r => r.id === c.dataset.role), root);
    });
    // 默认选中第一个
    if (roles[0]) { roles[0]._sel = true; drawDetail(detail, roles[0], root); }
  }

  function drawDetail(detail, role, root) {
    let working = JSON.parse(JSON.stringify(role.privileges));
    const render = () => {
      detail.innerHTML = `<div class="card-head"><h3>${role.name} · 权限矩阵</h3>${role.isSystem ? '<span class="badge gray">系统角色不可编辑</span>' : '<button class="btn btn-primary btn-sm" id="r-save">保存修改</button>'}</div>
      <div class="table-wrap"><table class="tbl"><thead><tr><th>动作 \ 领域</th>${Object.keys(AREAS).map(a => '<th>' + AREA_CN[a] + '</th>').join("")}</tr></thead><tbody>
      ${matrixRows(working, role.isSystem)}
      </tbody></table></div>
      <p class="muted" style="font-size:12px;margin-top:10px">勾选即授予该领域对应动作；保存后即时生效，相关管理员下次刷新即应用新权限，所有变更记入审计日志。</p>`;
      detail.querySelectorAll("input[type=checkbox][data-a]").forEach(cb => cb.onchange = () => toggle(cb.dataset.a, cb.dataset.x));
      if (!role.isSystem) detail.querySelector("#r-save").onclick = async () => { await PG.api.saveRole(Object.assign({}, role, { privileges: working })); PG.ui.toast("ok", "角色已保存", role.name); renderRoles(root); };
    };
    function toggle(area, act) {
      if (role.isSystem) return;
      const arr = working[area] || (working[area] = []);
      const i = arr.indexOf(act);
      if (i >= 0) arr.splice(i, 1); else arr.push(act);
      if (!arr.length) delete working[area];
      render();
    }
    render();
  }

  function matrixRows(priv, locked) {
    // 行=动作，列=领域
    const acts = ["read", "write", "create", "suspend", "reset_pwd", "delete", "lock", "unlock", "unbind", "refund", "manual", "assign", "snapshot"];
    return acts.map(act => {
      const cells = Object.keys(AREAS).map(area => {
        const has = (priv[area] || []).includes(act);
        const allowed = AREAS[area].includes(act);
        if (!allowed) return '<td></td>';
        return '<td><input type="checkbox" ' + (has ? "checked" : "") + ' ' + (locked ? "disabled" : "") + ' data-a="' + area + '" data-x="' + act + '"></td>';
      }).join("");
      return '<tr><td class="area-name">' + (ACT_CN[act] || act) + '</td>' + cells + '</tr>';
    }).join("");
  }

  function newRole(root) {
    const m = PG.ui.modal({
      title: "新建角色", width: 420,
      body: `<div class="field"><label>角色名称</label><input id="nr-name" placeholder="如 区域运营"></div>
        <div class="field"><label>描述</label><input id="nr-desc" placeholder="职责说明"></div>
        <p class="muted" style="font-size:12px">新建后从「全量只读」起步，请在角色权限页勾选需放开的动作。</p>`,
      foot: '<button class="btn btn-ghost" id="nr-cancel">取消</button><button class="btn btn-primary" id="nr-ok">创建</button>',
    });
    m.el.querySelector("#nr-cancel").onclick = m.close;
    m.el.querySelector("#nr-ok").onclick = async () => {
      const name = m.el.querySelector("#nr-name").value.trim();
      if (!name) { PG.ui.toast("err", "请填写角色名称"); return; }
      const id = "r" + Date.now();
      const ro = { id, name, desc: m.el.querySelector("#nr-desc").value.trim(), isSystem: false, privileges: { parents: ["read"], devices: ["read"], billing: ["read"], rbac: ["read"], server: ["read"], audit: ["read"], settings: ["read"], monitor: ["read"] } };
      PG.Mock.ROLES.push(ro);
      m.close(); PG.ui.toast("ok", "角色已创建", name); renderRoles(root);
    };
  }

  async function renderAdmins(root) {
    const admins = await PG.api.listAdmins();
    const roles = await PG.api.listRoles();
    const el = root.querySelector("#r-tab");
    root.querySelector("#r-newrole").onclick = () => newAdmin(root, roles);
    el.innerHTML = `<div class="table-wrap"><table class="tbl"><thead><tr><th>账号</th><th>姓名</th><th>角色</th><th>状态</th><th>MFA</th><th>最近登录</th><th>操作</th></tr></thead><tbody>
      ${admins.map(a => `<tr><td class="cell-strong">${a.username}</td><td>${U.esc(a.name)}</td><td><span class="badge blue">${a.roleName}</span></td><td>${a.status === "active" ? '<span class="badge green">启用</span>' : '<span class="badge gray">停用</span>'}</td><td>${a.mfa ? '<span class="badge green">开启</span>' : '<span class="badge gray">关闭</span>'}</td><td class="cell-sub">${a.lastLoginAt || "—"}</td><td><div class="row-actions"><button class="btn btn-xs" data-assign="${a.id}">变更角色</button><button class="btn btn-xs" data-edit="${a.id}">编辑</button><button class="btn btn-xs" data-reset="${a.id}">重置密码</button><button class="btn btn-xs btn-danger" data-del="${a.id}">删除</button></div></td></tr>`).join("")}
    </tbody></table></div>`;
    el.querySelectorAll("[data-assign]").forEach(b => b.onclick = () => assignRole(b.dataset.assign, roles, root));
    el.querySelectorAll("[data-edit]").forEach(b => b.onclick = () => editAdmin(b.dataset.edit, roles, root));
    el.querySelectorAll("[data-reset]").forEach(b => b.onclick = () => resetAdminPwd(b.dataset.reset));
    el.querySelectorAll("[data-del]").forEach(b => b.onclick = () => delAdmin(b.dataset.del));
  }

  async function assignRole(adminId, roles, root) {
    const admins = await PG.api.listAdmins();
    const a = admins.find(x => x.id === adminId);
    const opts = roles.map(r => '<option value="' + r.id + '" ' + (r.id === a.roleId ? "selected" : "") + '>' + r.name + '</option>').join("");
    const m = PG.ui.modal({
      title: "变更管理员角色 · " + a.name, width: 400,
      body: `<div class="stat-line"><span>当前角色</span><span>${a.roleName}</span></div><div class="field" style="margin-top:14px"><label>新角色</label><select id="ar-role">${opts}</select></div>
        <p class="muted" style="font-size:12px">遵循最小权限原则：仅可授予自身已持有的权限集合；变更记入审计。</p>`,
      foot: '<button class="btn btn-ghost" id="ar-cancel">取消</button><button class="btn btn-primary" id="ar-ok">保存</button>',
    });
    m.el.querySelector("#ar-cancel").onclick = m.close;
    m.el.querySelector("#ar-ok").onclick = async () => { await PG.api.assignRole(adminId, m.el.querySelector("#ar-role").value); m.close(); PG.ui.toast("ok", "角色已变更", a.name); renderAdmins(root); };
  }

  async function editAdmin(adminId, roles, root) {
    const a = (await PG.api.listAdmins()).find(x => x.id === adminId);
    if (!a) { PG.ui.toast("err", "未找到", adminId); return; }
    const m = PG.ui.modal({
      title: "编辑管理员 · " + a.name, width: 420,
      body: `<div class="form-row"><div class="field"><label>登录账号</label><input value="${U.esc(a.username)}" disabled style="background:var(--bg-soft)"></div><div class="field"><label>姓名</label><input id="ea-n" value="${U.esc(a.name || "")}"></div></div>
        <div class="form-row"><div class="field"><label>邮箱</label><input id="ea-e" value="${U.esc(a.email || "")}" placeholder="name@padguard.com"></div><div class="field"><label>状态</label><select id="ea-s">${a.status === "active" ? '<option value="active" selected>启用</option><option value="disabled">停用</option>' : '<option value="active">启用</option><option value="disabled" selected>停用</option>'}</select></div></div>
        <p class="muted" style="font-size:12px">角色变更请使用「变更角色」；停用最后一个超级管理员会被系统拒绝以防锁死后台。</p>`,
      foot: '<button class="btn btn-ghost" id="ea-cancel">取消</button><button class="btn btn-primary" id="ea-ok">保存</button>',
    });
    m.el.querySelector("#ea-cancel").onclick = m.close;
    m.el.querySelector("#ea-ok").onclick = async () => {
      const body = {
        name: m.el.querySelector("#ea-n").value.trim(),
        email: m.el.querySelector("#ea-e").value.trim(),
        status: m.el.querySelector("#ea-s").value,
      };
      try {
        await PG.api.updateAdmin(adminId, body);
        m.close(); PG.ui.toast("ok", "已保存", a.username); renderAdmins(root);
      } catch (e) { PG.ui.toast("err", "保存失败", e.message); }
    };
  }

  async function resetAdminPwd(adminId) {
    const a = (await PG.api.listAdmins()).find(x => x.id === adminId);
    if (!a) { PG.ui.toast("err", "未找到", adminId); return; }
    PG.ui.confirm("重置管理员密码", "将为 " + a.username + " 生成随机强密码，原密码立即失效。", async () => {
      const r = await PG.api.resetAdminPwd(adminId);
      const m = PG.ui.modal({ title: "新密码", body: '<div class="stat-line"><span>账号</span><span>' + a.username + '</span></div><div class="stat-line"><span>临时密码</span><span style="font-family:monospace;color:var(--brand)">' + (r.newPwd || "ADM" + Math.floor(100000 + Math.random() * 900000)) + '</span></div><p class="muted" style="font-size:12px">请通过安全渠道发送给该管理员，并提醒及时修改。</p>', foot: '<button class="btn btn-primary" id="ok">知道了</button>' });
      m.el.querySelector("#ok").onclick = m.close;
      PG.ui.toast("ok", "密码已重置", a.username);
    }, true);
  }

  async function delAdmin(adminId) {
    const a = (await PG.api.listAdmins()).find(x => x.id === adminId);
    if (!a) { PG.ui.toast("err", "未找到", adminId); return; }
    PG.ui.confirm("删除管理员", "确认删除 " + a.username + "（" + a.name + "）？删除后该账号无法登录，操作不可恢复。", async () => {
      try {
        await PG.api.deleteAdmin(adminId);
        PG.ui.toast("ok", "已删除", a.username); PG.router.refresh();
      } catch (e) { PG.ui.toast("err", "删除失败", e.message); }
    }, true);
  }

  function newAdmin(root, roles) {
    const opts = roles.map(r => '<option value="' + r.id + '">' + r.name + '</option>').join("");
    const m = PG.ui.modal({
      title: "新建管理员", width: 420,
      body: `<div class="form-row"><div class="field"><label>登录账号</label><input id="na-u" placeholder="英文/数字"></div><div class="field"><label>姓名</label><input id="na-n" placeholder="真实姓名"></div></div>
        <div class="form-row"><div class="field"><label>邮箱</label><input id="na-e" placeholder="name@padguard.com"></div><div class="field"><label>角色</label><select id="na-r">${opts}</select></div></div>`,
      foot: '<button class="btn btn-ghost" id="na-cancel">取消</button><button class="btn btn-primary" id="na-ok">创建</button>',
    });
    m.el.querySelector("#na-cancel").onclick = m.close;
    m.el.querySelector("#na-ok").onclick = async () => {
      const username = m.el.querySelector("#na-u").value.trim();
      if (!username) { PG.ui.toast("err", "请填写登录账号"); return; }
      await PG.api.createAdmin({ username, name: m.el.querySelector("#na-n").value.trim() || username, email: m.el.querySelector("#na-e").value.trim(), roleId: m.el.querySelector("#na-r").value });
      m.close(); PG.ui.toast("ok", "管理员已创建", username); PG.router.refresh();
    };
  }
})(window.PG);
