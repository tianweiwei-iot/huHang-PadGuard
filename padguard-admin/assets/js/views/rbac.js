/* 权限与角色（RBAC）
 *
 * 设计要点：
 * 1. 矩阵由服务端 `GET /v1/admin/rbac/permissions` 下发的元数据驱动，前端**不硬编码**领域/动作。
 *    此前前端自带一份 area 清单，里面多出一个服务端未登记的 monitor 领域，
 *    运营勾选后却在服务端 sanitize 时被静默丢弃 —— 表现为"矩阵不可设置"。
 * 2. 内置角色除系统锁定角色（super）外全部可编辑。
 *    此前前端用 isSystem 一刀切把所有复选框置灰，导致运营/客服/财务/审计四个最常用角色
 *    只能看不能改，RBAC 失去了意义。
 * 3. 左侧角色卡片与右侧矩阵是两个独立容器，切换角色时各自局部重绘。
 *    此前在同一个 innerHTML 里重建容器后，仍向已被丢弃的旧节点写明细 —— 点卡片后明细区空白。
 */
(function (PG) {
  const U = PG.ui;

  // 运行期状态
  let SCHEMA = { areas: [], lockedRoles: [] };
  let ROLES = [];
  let currentId = null;
  let working = null;   // 正在编辑的权限副本 { area: [action...] }
  let baseline = null;  // 服务端原值，用于判断脏值与「放弃修改」

  /**
   * Promise 版确认框。
   * 内置 U.confirm 只提供 onOk 回调、没有取消分支，用 Promise 包装会在用户取消时永久挂起。
   * 这里复用同一个 modal，把「取消」也变成一次 resolve(false)，便于用 await 串联流程。
   */
  function confirmP(title, text, danger) {
    return new Promise((resolve) => {
      const m = U.modal({
        title,
        body: '<p style="margin:0;color:var(--text-2);line-height:1.7">' + U.esc(text) + '</p>',
        foot: '<button class="btn btn-ghost" id="cf-cancel">取消</button>' +
          '<button class="btn ' + (danger ? "btn-danger" : "btn-primary") + '" id="cf-ok">' +
          (danger ? "确认执行" : "确认") + '</button>',
      });
      const done = (v) => { m.close(); resolve(v); };
      m.el.querySelector("#cf-cancel").onclick = () => done(false);
      m.el.querySelector("#cf-ok").onclick = () => done(true);
    });
  }

  const areaDef = (k) => SCHEMA.areas.find((a) => a.key === k);
  const isLocked = (role) => (SCHEMA.lockedRoles || []).indexOf(role.id) >= 0;
  const countOf = (priv) => Object.keys(priv || {}).reduce((s, k) => s + ((priv[k] || []).length), 0);
  const samePriv = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));
  function canon(p) {
    const out = {};
    Object.keys(p || {}).sort().forEach((k) => {
      const acts = (p[k] || []).slice().sort();
      if (acts.length) out[k] = acts;
    });
    return out;
  }

  PG.registerView("rbac", {
    title: "权限与角色", icon: "🛡️", group: "系统",
    render: () => `
      <div class="page-head"><div><h2>权限与角色（RBAC）</h2>
        <p>基于 <code>area:action</code> 动词模型的细粒度授权；矩阵由服务端下发，保存后即时生效</p></div>
        <div class="page-actions"><button class="btn btn-primary" id="r-newrole">＋ 新建角色</button></div></div>
      <div class="card">
        <div class="row" style="gap:8px;margin-bottom:14px;border-bottom:1px solid var(--border-soft);padding-bottom:10px">
          <button class="btn btn-xs btn-primary tab" data-tab="matrix">权限矩阵</button>
          <button class="btn btn-xs tab" data-tab="overview">权限总览</button>
          <button class="btn btn-xs tab" data-tab="admins">管理员账号</button>
        </div>
        <div id="r-tab"></div>
      </div>`,
    mount: async (root) => {
      const tabs = root.querySelectorAll(".tab");
      const load = async () => {
        const [schema, roles] = await Promise.all([PG.api.permissionSchema(), PG.api.listRoles()]);
        SCHEMA = schema; ROLES = roles;
        if (!ROLES.some((r) => r.id === currentId)) {
          // 默认落在第一个**可编辑**的角色上：超级管理员整张矩阵是禁用的，
          // 若默认选中它，用户进入页面的第一印象仍是"什么都改不了"。
          const pick = ROLES.find((r) => !isLocked(r)) || ROLES[0];
          currentId = pick ? pick.id : null;
        }
      };
      const show = async (t) => {
        tabs.forEach((b) => b.classList.toggle("btn-primary", b.dataset.tab === t));
        await load();
        if (t === "matrix") drawMatrix(root);
        else if (t === "overview") drawOverview(root);
        else renderAdmins(root);
      };
      tabs.forEach((b) => (b.onclick = () => show(b.dataset.tab)));
      await load();
      drawMatrix(root); tabs.forEach(b => b.classList.toggle("btn-primary", b.dataset.tab === "matrix"));
    },
  });

  // ============================ 权限矩阵 ============================

  function matrixShell() {
    return `
      <div style="display:grid;grid-template-columns:236px 1fr;gap:16px;align-items:start">
        <div id="r-roles"></div>
        <div id="r-detail"></div>
      </div>`;
  }

  /**
   * 渲染左侧角色列表。
   * 注意：这里只重写 #r-roles 的 innerHTML，**不动** #r-detail，
   * 两个容器彼此独立，避免旧实现里"容器被重建后仍向孤儿节点写明细"的问题。
   */
  function paintRoleList(refreshDetail) {
    const box = document.querySelector("#r-roles");
    if (!box) return;
    box.innerHTML = ROLES.map((r) => {
      const on = r.id === currentId;
      return `<div class="card" data-role="${U.esc(r.id)}" style="cursor:pointer;margin-bottom:10px;border:1px solid ${on ? "var(--brand)" : "var(--border-soft)"};background:${on ? "var(--bg-soft)" : ""}">
        <div class="between"><strong>${U.esc(r.name)}</strong>${
          r.isSystem ? '<span class="badge gray">系统</span>' : '<span class="badge blue">自定义</span>'}</div>
        <div class="cell-sub" style="margin:6px 0;min-height:30px">${U.esc(r.desc || "")}</div>
        <div class="muted" style="font-size:12px">
          ${countOf(r.privileges)} 项权限 · ${Object.keys(r.privileges).length} 个领域
          ${r.memberCount ? ` · ${r.memberCount} 名成员` : ""}
        </div>
        ${r.overridden ? '<div style="margin-top:6px"><span class="badge orange">已调整</span></div>' : ""}
      </div>`;
    }).join("");
    box.querySelectorAll("[data-role]").forEach((c) => {
      c.onclick = async () => {
        if (dirty()) {
          const ok = await askDiscard();
          if (!ok) return;
        }
        currentId = c.dataset.role;
        paintRoleList(true);
      };
    });
    if (refreshDetail) drawDetail();
  }

  function drawMatrix(root) {
    const el = root.querySelector("#r-tab");
    el.innerHTML = matrixShell();
    root.querySelector("#r-newrole").onclick = () => newRole(root);
    if (!ROLES.length) {
      root.querySelector("#r-detail").innerHTML = '<p class="muted">暂无角色。</p>';
      paintRoleList(false);
      return;
    }
    if (!currentId) currentId = ROLES[0].id;
    paintRoleList(true);   // paintRoleList 在最后调用 drawDetail
  }

  function dirty() {
    if (!working) return false;
    return !samePriv(working, baseline);
  }

  function askDiscard() {
    return confirmP("放弃未保存的修改", "当前角色的权限已修改但尚未保存，切换角色将丢失这些改动。", true);
  }

  /** 渲染右侧：选中角色的权限矩阵 */
  function drawDetail() {
    const detail = document.querySelector("#r-detail");
    const role = ROLES.find((r) => r.id === currentId);
    if (!detail || !role) return;

    baseline = JSON.parse(JSON.stringify(role.privileges || {}));
    working = JSON.parse(JSON.stringify(role.privileges || {}));
    const locked = isLocked(role);

    // 表头：取全部动作并集（各领域未登记的动作渲染为占位，保证列对齐）
    const acts = [];
    SCHEMA.areas.forEach((a) => a.actions.forEach((x) => { if (acts.indexOf(x.key) < 0) acts.push(x.key); }));
    const actName = {};
    const actDanger = {};
    SCHEMA.areas.forEach((a) => a.actions.forEach((x) => { actName[x.key] = x.name; actDanger[x.key] = x.danger; }));

    const head = acts.map((k) =>
      `<th style="text-align:center;font-weight:${actDanger[k] ? "600" : "400"};white-space:nowrap">
        <span class="${actDanger[k] ? "" : "muted"}">${U.esc(actName[k] || k)}</span></th>`).join("");

    const rows = SCHEMA.areas.map((area) => {
      const cells = acts.map((act) => {
        const allowed = area.actions.some((x) => x.key === act);
        if (!allowed) return '<td style="text-align:center;color:var(--border)">·</td>';
        const has = (working[area.key] || []).indexOf(act) >= 0;
        return `<td style="text-align:center">
          <input type="checkbox" data-area="${area.key}" data-act="${act}" ${has ? "checked" : ""} ${locked ? "disabled" : ""}>
        </td>`;
      }).join("");
      const n = (working[area.key] || []).length;
      return `<tr>
        <td class="cell-strong" style="white-space:nowrap">${U.esc(area.name)}
          <span class="muted" style="font-size:12px">${n}/${area.actions.length}</span></td>
        ${cells}
        <td style="white-space:nowrap">${locked ? "" : `
          <button class="btn btn-xs" data-row-all="${area.key}">全选</button>
          <button class="btn btn-xs" data-row-none="${area.key}">清空</button>`}</td>
      </tr>`;
    }).join("");

    detail.innerHTML = `
      <div class="card-head">
        <h3>${U.esc(role.name)} · 权限矩阵</h3>
        <div class="row-actions">
          ${isLocked(role)
            ? '<span class="badge gray">系统锁定 · 不可编辑</span>'
            : `<button class="btn btn-sm" id="r-readonly">一键只读</button>
               <button class="btn btn-sm" id="r-selectall">全选</button>
               ${role.overridden ? '<button class="btn btn-sm btn-ghost" id="r-reset">还原默认</button>' : ''}
               ${role.isSystem ? '' : '<button class="btn btn-sm btn-danger" id="r-del">删除角色</button>'}`}
        </div>
      </div>
      ${locked
        ? '<p class="muted" style="font-size:12px;margin-bottom:10px">超级管理员是系统最后的破窗账号，权限由代码固定。一旦允许收窄，超管会自锁导致后台无法恢复。</p>'
        : '<p class="muted" style="font-size:12px;margin-bottom:10px">勾选即授予。查看权限是其余动作的前提（未勾选查看将无法打开对应页面）；红色列头为高危动作。服务端口径与此处完全一致。</p>'}
      <div class="table-wrap"><table class="tbl">
        <thead><tr><th>领域</th>${head}<th></th></tr></thead>
        <tbody>${rows}</tbody>
      </table></div>
      <div id="r-savebar"></div>`;

    // —— 绑定交互 ——
    detail.querySelectorAll("input[type=checkbox][data-area]").forEach((cb) => {
      cb.onchange = () => {
        const area = cb.dataset.area, act = cb.dataset.act;
        const arr = working[area] || (working[area] = []);
        const i = arr.indexOf(act);
        if (cb.checked && i < 0) arr.push(act);
        if (!cb.checked && i >= 0) arr.splice(i, 1);
        if (!arr.length) delete working[area];
        paintSaveBar();
        paintRowCount(area);
      };
    });
    detail.querySelectorAll("[data-row-all]").forEach((b) => (b.onclick = () => setRow(b.dataset.rowAll, true)));
    detail.querySelectorAll("[data-row-none]").forEach((b) => (b.onclick = () => setRow(b.dataset.rowNone, false)));

    const ro = detail.querySelector("#r-readonly");
    if (ro) ro.onclick = () => {
      const only = {};
      SCHEMA.areas.forEach((a) => (only[a.key] = ["read"]));
      working = only; redraw(); U.toast("ok", "已设为全领域只读", role.name);
    };
    const sa = detail.querySelector("#r-selectall");
    if (sa) sa.onclick = () => {
      const all = {};
      SCHEMA.areas.forEach((a) => (all[a.key] = a.actions.map((x) => x.key)));
      working = all; redraw(); U.toast("ok", "已选中全部权限", role.name);
    };
    const rs = detail.querySelector("#r-reset");
    if (rs) rs.onclick = async () => {
      if (!(await confirmP("还原默认权限", "将清除对「" + role.name + "」的所有后台调整，恢复到系统内置权限。", true))) return;
      try {
        await PG.api.resetRolePrivileges(role.id);
        U.toast("ok", "已还原默认权限", role.name);
        await reload(); redraw();
      } catch (e) { U.toast("err", "还原失败", e.message); }
    };
    const del = detail.querySelector("#r-del");
    if (del) del.onclick = () => {
      const msg = role.memberCount
        ? `该角色下仍有 ${role.memberCount} 名管理员，服务端将拒绝删除，请先在「管理员账号」页改派角色。`
        : "删除后不可恢复。";
      U.confirm("删除角色", `${U.esc(role.name)}：${msg}`, async () => {
        try { await PG.api.deleteRole(role.id); U.toast("ok", "角色已删除", role.name); await reload(); redraw(); }
        catch (e) { U.toast("err", "删除失败", e.message); }
      }, true);
    };

    function setRow(area, on) {
      const def = areaDef(area);
      working[area] = on ? def.actions.map((x) => x.key) : [];
      if (!on) delete working[area];
      redraw();
    }
    function paintRowCount(area) {
      const row = detail.querySelector(`[data-row-all="${area}"]`)?.closest("tr");
      if (!row) return;
      const n = (working[area] || []).length;
      const def = areaDef(area);
      row.querySelector(".cell-strong .muted").textContent = `${n}/${def.actions.length}`;
    }
    /** 重绘整张明细（用于全选/清空/还原等批量变更） */
    function redraw() { drawDetail(); paintSaveBar(); }
    paintSaveBar();
  }

  function paintSaveBar() {
    const bar = document.querySelector("#r-savebar");
    if (!bar) return;
    const role = ROLES.find((r) => r.id === currentId);
    if (!role) return;
    const d = dirty();
    const n = countOf(working || {});
    bar.innerHTML = `
      <div class="row" style="gap:10px;align-items:center;margin-top:14px;padding-top:12px;border-top:1px solid var(--border-soft)">
        <span class="muted" style="font-size:13px">共 <strong>${n}</strong> 项权限${d ? ' · <span style="color:var(--warn)">有未保存的修改</span>' : " · 已与服务端同步"}</span>
        <div style="margin-left:auto" class="row-actions">
          ${d ? '<button class="btn btn-ghost btn-sm" id="r-cancel">放弃修改</button><button class="btn btn-primary btn-sm" id="r-save">保存权限</button>' : ""}
        </div>
      </div>`;
    const c = bar.querySelector("#r-cancel");
    if (c) c.onclick = () => { working = JSON.parse(JSON.stringify(baseline)); drawDetail(); };
    const s = bar.querySelector("#r-save");
    if (s) s.onclick = doSave;
  }

  async function doSave() {
    const role = ROLES.find((r) => r.id === currentId);
    try {
      await PG.api.saveRole({ id: role.id, privileges: working });
      U.toast("ok", "权限已保存并生效", role.name);
      await reload();
      drawDetail();
      paintRoleList(false);
    } catch (e) {
      U.toast("err", "保存失败", e && e.message ? e.message : String(e));
    }
  }

  async function reload() {
    const roles = await PG.api.listRoles();
    ROLES = roles;
    if (!ROLES.some((r) => r.id === currentId)) currentId = ROLES.length ? ROLES[0].id : null;
  }

  function newRole(root) {
    const seeds = [{ value: "readonly", text: "全领域只读（最小权限，推荐）" }]
      .concat(ROLES.map((r) => ({ value: "copy:" + r.id, text: "复制自：" + r.name })));
    const m = U.modal({
      title: "新建角色", width: 440,
      body: `<div class="form-row">
          <div class="field"><label>角色标识</label><input id="nr-id" placeholder="如 region_ops"></div>
          <div class="field"><label>角色名称</label><input id="nr-name" placeholder="如 区域运营"></div>
        </div>
        <div class="field"><label>描述</label><input id="nr-desc" placeholder="职责说明"></div>
        <div class="field"><label>初始权限</label><select id="nr-seed">${seeds.map((s) => `<option value="${U.esc(s.value)}">${U.esc(s.text)}</option>`).join("")}</select></div>
        <p class="muted" style="font-size:12px">标识用于服务端判定，只能用小写字母、数字与下划线，创建后不可更改。创建后可在矩阵中继续细调。</p>`,
      foot: '<button class="btn btn-ghost" id="nr-cancel">取消</button><button class="btn btn-primary" id="nr-ok">创建</button>',
    });
    m.el.querySelector("#nr-cancel").onclick = m.close;
    m.el.querySelector("#nr-ok").onclick = async () => {
      const name = m.el.querySelector("#nr-name").value.trim();
      const rawId = m.el.querySelector("#nr-id").value.trim();
      const seed = m.el.querySelector("#nr-seed").value;
      if (!name) { U.toast("err", "请填写角色名称"); return; }
      const id = rawId || ("role_" + Date.now().toString(36));
      if (!/^[a-z][a-z0-9_]{1,31}$/.test(id)) {
        U.toast("err", "角色标识不合法", "仅允许小写字母开头，含小写字母/数字/下划线，长度 2-32");
        return;
      }
      let privileges = {};
      SCHEMA.areas.forEach((a) => (privileges[a.key] = ["read"]));
      if (seed.startsWith("copy:")) {
        const src = ROLES.find((r) => r.id === seed.slice(5));
        if (src && src.privileges) privileges = JSON.parse(JSON.stringify(src.privileges));
      }
      try {
        await PG.api.createRole({ id, name, desc: m.el.querySelector("#nr-desc").value.trim(), privileges });
        m.close(); U.toast("ok", "角色已创建", name);
        await reload(); currentId = id; paintRoleList(true);
      } catch (e) {
        U.toast("err", "创建失败", String((e && e.message) || e));
      }
    };
  }

  // ============================ 权限总览（角色对比） ============================

  function drawOverview(root) {
    const el = root.querySelector("#r-tab");
    root.querySelector("#r-newrole").onclick = () => newRole(root);
    const head = ROLES.map((r) =>
      `<th style="text-align:center">${U.esc(r.name)}${r.memberCount ? ` <span class="muted" style="font-size:11px">${r.memberCount}人</span>` : ""}</th>`).join("");
    const rows = SCHEMA.areas.map((area) => {
      const cells = ROLES.map((r) => {
        const acts = (r.privileges || {})[area.key] || [];
        const total = area.actions.length;
        const ratio = total ? acts.length / total : 0;
        const bg = acts.length === 0 ? "transparent"
          : ratio >= 1 ? "rgba(220,38,38,.14)"
          : ratio >= 0.5 ? "rgba(245,158,11,.16)"
          : "rgba(37,99,235,.10)";
        const tip = acts.length
          ? area.actions.filter((a) => acts.indexOf(a.key) >= 0).map((a) => a.name).join("、")
          : "无权限";
        return `<td style="text-align:center;background:${bg}" title="${U.esc(tip)}">${acts.length}/${total}</td>`;
      }).join("");
      return `<tr><td class="cell-strong" style="white-space:nowrap">${U.esc(area.name)}</td>${cells}</tr>`;
    }).join("");
    el.innerHTML = `
      <div class="table-wrap"><table class="tbl">
        <thead><tr><th>领域 \\ 角色</th>${head}</tr></thead>
        <tbody>${rows}</tbody>
      </table></div>
      <p class="muted" style="font-size:12px;margin-top:10px">
        单元格为「已授予动作数 / 该领域动作总数」，鼠标悬停可查看具体已授予的动作。
        红色表示该角色拥有该领域全部权限，黄色为半数以上。
      </p>`;
  }

  // ============================ 管理员账号 ============================

  async function renderAdmins(root) {
    const el = root.querySelector("#r-tab");
    root.querySelector("#r-newrole").onclick = () => newAdmin(root);
    const [admins, roles] = await Promise.all([PG.api.listAdmins(), PG.api.listRoles()]);
    el.innerHTML = `<div class="table-wrap"><table class="tbl"><thead><tr>
        <th>账号</th><th>姓名</th><th>角色</th><th>状态</th><th>最近登录</th><th>操作</th>
      </tr></thead><tbody>
      ${admins.map((a) => `<tr>
        <td class="cell-strong">${U.esc(a.username)}</td>
        <td>${U.esc(a.name || "—")}</td>
        <td><span class="badge blue">${U.esc(a.roleName || a.roleId)}</span></td>
        <td>${a.status === "active" ? '<span class="badge green">启用</span>' : '<span class="badge gray">停用</span>'}</td>
        <td class="cell-sub">${U.esc(a.lastLoginAt || "—")}</td>
        <td><div class="row-actions">
          <button class="btn btn-xs" data-assign="${U.esc(a.id)}">变更角色</button>
          <button class="btn btn-xs" data-edit="${U.esc(a.id)}">编辑</button>
          <button class="btn btn-xs" data-reset="${U.esc(a.id)}">重置密码</button>
          <button class="btn btn-xs btn-danger" data-del="${U.esc(a.id)}">删除</button>
        </div></td></tr>`).join("")}
    </tbody></table></div>`;
    el.querySelectorAll("[data-assign]").forEach((b) => (b.onclick = () => assignRole(b.dataset.assign, roles, root)));
    el.querySelectorAll("[data-edit]").forEach((b) => (b.onclick = () => editAdmin(b.dataset.edit, roles, root)));
    el.querySelectorAll("[data-reset]").forEach((b) => (b.onclick = () => resetAdminPwd(b.dataset.reset)));
    el.querySelectorAll("[data-del]").forEach((b) => (b.onclick = () => delAdmin(b.dataset.del)));
  }

  async function assignRole(adminId, roles, root) {
    const a = (await PG.api.listAdmins()).find((x) => x.id === adminId);
    const opts = roles.map((r) => `<option value="${U.esc(r.id)}" ${r.id === a.roleId ? "selected" : ""}>${U.esc(r.name)} · ${countOf(r.privileges)}项权限</option>`).join("");
    const m = U.modal({
      title: "变更管理员角色 · " + (a.name || a.username), width: 420,
      body: `<div class="stat-line"><span>当前角色</span><span>${U.esc(a.roleName || a.roleId)}</span></div>
        <div class="field" style="margin-top:14px"><label>新角色</label><select id="ar-role">${opts}</select></div>
        <p class="muted" style="font-size:12px">变更立即生效，该管理员下次请求即按新角色校验；所有变更记入审计日志。</p>`,
      foot: '<button class="btn btn-ghost" id="ar-cancel">取消</button><button class="btn btn-primary" id="ar-ok">保存</button>',
    });
    m.el.querySelector("#ar-cancel").onclick = m.close;
    m.el.querySelector("#ar-ok").onclick = async () => {
      try {
        await PG.api.assignRole(adminId, m.el.querySelector("#ar-role").value);
        m.close(); U.toast("ok", "角色已变更", a.username); renderAdmins(root);
      } catch (e) { U.toast("err", "变更失败", e.message); }
    };
  }

  async function editAdmin(adminId, roles, root) {
    const a = (await PG.api.listAdmins()).find((x) => x.id === adminId);
    if (!a) { U.toast("err", "未找到", adminId); return; }
    const m = U.modal({
      title: "编辑管理员 · " + (a.name || a.username), width: 420,
      body: `<div class="form-row">
          <div class="field"><label>登录账号</label><input value="${U.esc(a.username)}" disabled style="background:var(--bg-soft)"></div>
          <div class="field"><label>姓名</label><input id="ea-n" value="${U.esc(a.name || "")}"></div></div>
        <div class="form-row">
          <div class="field"><label>邮箱</label><input id="ea-e" value="${U.esc(a.email || "")}" placeholder="name@example.com"></div>
          <div class="field"><label>状态</label><select id="ea-s">
            <option value="active" ${a.status === "active" ? "selected" : ""}>启用</option>
            <option value="disabled" ${a.status !== "active" ? "selected" : ""}>停用</option></select></div></div>
        <p class="muted" style="font-size:12px">角色变更请使用「变更角色」；停用最后一个超级管理员会被系统拒绝，以防锁死后台。</p>`,
      foot: '<button class="btn btn-ghost" id="ea-cancel">取消</button><button class="btn btn-primary" id="ea-ok">保存</button>',
    });
    m.el.querySelector("#ea-cancel").onclick = m.close;
    m.el.querySelector("#ea-ok").onclick = async () => {
      try {
        await PG.api.updateAdmin(adminId, {
          name: m.el.querySelector("#ea-n").value.trim(),
          email: m.el.querySelector("#ea-e").value.trim(),
          status: m.el.querySelector("#ea-s").value,
        });
        m.close(); U.toast("ok", "已保存", a.username); renderAdmins(root);
      } catch (e) { U.toast("err", "保存失败", e.message); }
    };
  }

  async function resetAdminPwd(adminId) {
    const a = (await PG.api.listAdmins()).find((x) => x.id === adminId);
    if (!a) { U.toast("err", "未找到", adminId); return; }
    U.confirm("重置管理员密码", "将为 " + a.username + " 生成随机密码（按系统密码策略生成），原密码立即失效。", async () => {
      try {
        const r = await PG.api.resetAdminPwd(adminId);
        const emailed = r && r.emailed;
        const m = U.modal({
          title: "新密码",
          body: `<div class="stat-line"><span>账号</span><span>${U.esc(a.username)}</span></div>
            <div class="stat-line"><span>临时密码</span><span style="font-family:monospace;color:var(--brand)" id="rp-pwd">${U.esc(r.newPwd || "—")}</span>
              <button class="btn btn-xs" id="rp-copy" style="margin-left:8px">复制</button></div>
            <p class="muted" style="font-size:12px">${
              emailed
                ? "已自动向该管理员登记邮箱发送密码通知邮件。"
                : "未向其登记邮箱发送邮件（账号未登记邮箱，或服务端未配置 SMTP），请通过安全渠道转告对方，并提醒其及时修改。"
            }</p>`,
          foot: '<button class="btn btn-primary" id="ok">知道了</button>',
        });
        m.el.querySelector("#ok").onclick = m.close;
        const cp = m.el.querySelector("#rp-copy");
        if (cp) cp.onclick = () => {
          if (navigator.clipboard) navigator.clipboard.writeText(r.newPwd || "");
          U.toast("ok", "已复制", "新密码已复制到剪贴板");
        };
        U.toast("ok", "密码已重置", a.username);
      } catch (e) { U.toast("err", "重置失败", e.message); }
    }, true);
  }

  async function delAdmin(adminId) {
    const a = (await PG.api.listAdmins()).find((x) => x.id === adminId);
    if (!a) { U.toast("err", "未找到", adminId); return; }
    U.confirm("删除管理员", "确认删除 " + a.username + "（" + (a.name || "") + "）？删除后该账号无法登录，操作不可恢复。", async () => {
      try { await PG.api.deleteAdmin(adminId); U.toast("ok", "已删除", a.username); PG.router.refresh(); }
      catch (e) { U.toast("err", "删除失败", e.message); }
    }, true);
  }

  function newAdmin(root) {
    const opts = ROLES.map((r) => `<option value="${U.esc(r.id)}">${U.esc(r.name)}</option>`).join("");
    const m = U.modal({
      title: "新建管理员", width: 440,
      body: `<div class="form-row">
          <div class="field"><label>登录账号</label><input id="na-u" placeholder="英文/数字"></div>
          <div class="field"><label>姓名</label><input id="na-n" placeholder="真实姓名"></div></div>
        <div class="form-row">
          <div class="field"><label>邮箱</label><input id="na-e" placeholder="name@example.com"></div>
          <div class="field"><label>角色</label><select id="na-r">${opts}</select></div></div>
        <p class="muted" style="font-size:12px">密码由系统按密码策略自动生成，创建后弹出显示一次。</p>`,
      foot: '<button class="btn btn-ghost" id="na-cancel">取消</button><button class="btn btn-primary" id="na-ok">创建</button>',
    });
    m.el.querySelector("#na-cancel").onclick = m.close;
    m.el.querySelector("#na-ok").onclick = async () => {
      const username = m.el.querySelector("#na-u").value.trim();
      if (!username) { U.toast("err", "请填写登录账号"); return; }
      try {
        const r = await PG.api.createAdmin({
          username, name: m.el.querySelector("#na-n").value.trim() || username,
          email: m.el.querySelector("#na-e").value.trim(),
          roleId: m.el.querySelector("#na-r").value,
        });
        m.close(); U.toast("ok", "管理员已创建", username);
        if (r && r.password) {
          const m2 = U.modal({
            title: "初始密码",
            body: `<div class="stat-line"><span>账号</span><span>${U.esc(username)}</span></div>
              <div class="stat-line"><span>初始密码</span><span style="font-family:monospace;color:var(--brand)">${U.esc(r.password)}</span></div>
              <p class="muted" style="font-size:12px">请通过安全渠道发送给该管理员。</p>`,
            foot: '<button class="btn btn-primary" id="ok">知道了</button>',
          });
          m2.el.querySelector("#ok").onclick = m2.close;
        } else PG.router.refresh();
      } catch (e) { U.toast("err", "创建失败", e.message); }
    };
  }
})(window.PG);
