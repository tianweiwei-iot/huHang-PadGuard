/**
 * RBAC 前端视图渲染冒烟测试
 *
 * 为什么需要它：rbac.js 是一次整体重写（矩阵布局、内置角色放开编辑、容器拆分修复明细空白）。
 * 语法检查只能保证能解析，无法证明 mount→drawMatrix→paintRoleList→drawDetail 这条链路
 * 在真实数据下不抛异常。这里用一个最小 DOM 沙箱 + **真实服务端数据**跑通完整渲染，
 * 任何一个 undefined / 类型错误都会在这里暴露。
 *
 * 用法：node tools/smoke_rbac.js
 */
const fs = require("fs");
const path = require("path");
const vm = require("vm");

const BASE = process.env.BASE || "http://127.0.0.1:8090";
const ROOT = path.resolve(__dirname, "..");

// ---------------- 最小 DOM 桩 ----------------
class El {
  constructor(id) {
    this.id = id || "";
    this._html = "";
    this.dataset = {};
    this.classList = { toggle() {}, add() {}, remove() {}, contains: () => false };
    this.style = {};
    this.textContent = "";
    this.checked = false;
  }
  set innerHTML(v) { this._html = String(v); }
  get innerHTML() { return this._html; }
  querySelector(sel) { return REG(sel); }
  querySelectorAll(sel) {
    // 让角色卡片列表可枚举 —— 用于真实模拟"点击卡片切换明细"这条链路。
    // 旧实现的缺陷正是"容器重建后仍向被丢弃的旧节点写明细"，只有真实走一遍
    // cards.onclick → drawDetail 才能证明明细确实写到了当前有效的容器上。
    // 同一份 HTML 必须返回同一批元素对象，否则视图里绑定的 onclick 会在下一次查询时丢失
    if (sel === "[data-role]") {
      const ids = [...this._html.matchAll(/data-role="([^"]+)"/g)].map((m) => m[1]);
      const key = ids.join(",");
      if (!this._cards || this._cardsKey !== key) {
        this._cards = ids.map((id) => { const e = new El(); e.dataset = { role: id }; return e; });
        this._cardsKey = key;
      }
      return this._cards;
    }
    if (sel === ".tab") return TABS;
    return [];
  }
  closest() { return new El(); }
}
const CACHE = new Map();
function REG(sel) {
  if (!CACHE.has(sel)) CACHE.set(sel, new El(sel));
  return CACHE.get(sel);
}
const TABS = ["matrix", "overview", "admins"].map((t) => {
  const e = new El();
  e.dataset = { tab: t };
  return e;
});

// ---------------- 真实 API（打向运行中的服务端） ----------------
let TOKEN = "";
async function http(method, p, body) {
  const res = await fetch(BASE + p, {
    method,
    headers: { "Content-Type": "application/json", ...(TOKEN ? { Authorization: "Bearer " + TOKEN } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const j = await res.json();
  if (!res.ok) throw new Error((j && j.message) || "HTTP " + res.status);
  return j && j.data !== undefined ? j.data : j;
}

let VIEW = null;
let VIEW_NAME = "";
const api = {
  permissionSchema: () => http("GET", "/v1/admin/rbac/permissions"),
  listRoles: () => http("GET", "/v1/admin/rbac/roles"),
  listAdmins: () => http("GET", "/v1/admin/rbac/admins"),
  saveRole: (r) => http("PUT", "/v1/admin/rbac/roles", r),
  createRole: (r) => http("POST", "/v1/admin/rbac/roles", r),
  deleteRole: (id) => http("DELETE", "/v1/admin/rbac/roles/" + encodeURIComponent(id)),
  resetRolePrivileges: (id) => http("DELETE", "/v1/admin/rbac/roles/" + encodeURIComponent(id) + "/privileges"),
};

const ui = {
  esc: (s) => String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c])),
  money: (n) => String(n),
  toast: () => {},
  modal: (o) => ({ el: new El("modal"), close: () => {}, body: o && o.body }),
  confirm: (t, x, onOk) => { if (onOk) onOk(); },
};

(async function main() {
  const login = await fetch(BASE + "/v1/admin/login", {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: "admin", password: "admin123" }),
  });
  const lj = await login.json();
  TOKEN = (lj && lj.data && lj.data.token) || "";
  if (!TOKEN) { console.error("登录失败，无法继续"); process.exit(1); }
  console.log("已登录服务端 " + BASE);

  const sandbox = {
    console,
    document: { querySelector: (s) => REG(s), querySelectorAll: () => [] },
    Promise, JSON, Object, Array, String, Number, Date, Math, RegExp, Error, Set, Map, setTimeout,
  };
  sandbox.window = {
    PG: {
      ui, api,
      // 注意是两参数签名： registerView("rbac", { ... })
      registerView: (name, v) => { VIEW = v; VIEW_NAME = String(name); },
      router: { refresh: () => {} },
    },
  };
  vm.createContext(sandbox);
  const code = fs.readFileSync(path.join(ROOT, "..", "padguard-admin", "assets", "js", "views", "rbac.js"), "utf8");
  vm.runInContext(code, sandbox, { filename: "rbac.js" });

  if (!VIEW) { console.error("registerView 未捕获到视图定义"); process.exit(1); }
  console.log("视图已注册：name=" + VIEW_NAME + " title=" + VIEW.title);

  // —— 渲染外壳 ——
  VIEW.render();
  console.log("[OK] render() 模板生成成功");

  // —— 挂载（真实拉数据 + 全量渲染） ——
  const tabHolder = new El();
  const root = {
    querySelector: (s) => (s === "#r-tab" ? tabHolder : REG(s)),
    querySelectorAll: (sel) => (sel === ".tab" ? TABS : []),
  };

  try {
    await VIEW.mount(root);
    console.log("[OK] mount() 渲染通过（默认=矩阵页）");
  } catch (e) {
    console.error("[FAIL] mount() 抛异常：" + e.message + "\n" + (e.stack || ""));
    process.exit(1);
  }

  // —— 关键回归：点击角色卡片能否切换明细（旧版点了之后明细区空白） ——
  const cardBox = REG("#r-roles");
  const cards = cardBox.querySelectorAll("[data-role]");
  if (!cards.length) { console.error("[FAIL] 角色卡片未渲染"); process.exit(1); }
  console.log("[OK] 渲染出 " + cards.length + " 张角色卡片");
  console.log("\n--- 逐个角色切换明细，统计不可编辑(disabled)的复选框数 ---");
  let editableRoles = 0, lockedRoles = 0;
  for (const c of cards) {
    await c.onclick();
    const html = REG("#r-detail").innerHTML;
    const dis = (html.match(/disabled/g) || []).length;
    const boxes = (html.match(/type="checkbox"/g) || []).length;
    if (!html || !/permissions|权限矩阵/.test(html) && !boxes) {
      console.error("[FAIL] 点击角色 " + c.dataset.role + " 后明细区为空 —— DOM 孤儿问题复现");
      process.exit(1);
    }
    if (dis === 0) editableRoles++; else lockedRoles++;
    console.log("    " + String(c.dataset.role).padEnd(10) + " 复选框=" + String(boxes).padEnd(4) + " disabled=" + String(dis).padEnd(4) + (dis === 0 ? "→ 可编辑" : "→ 系统锁定"));
  }
  if (editableRoles < 4) {
    console.error("[FAIL] 可编辑角色仅 " + editableRoles + " 个，内置角色应可编辑");
    process.exit(1);
  }
  console.log("[OK] " + editableRoles + " 个角色可编辑、 " + lockedRoles + " 个系统锁定（预期仅 super）");

  // —— 三个 Tab 分支覆盖 ——
  for (const t of TABS) {
    await t.onclick();
    if (!tabHolder.innerHTML) { console.error("[FAIL] tab " + t.dataset.tab + " 渲染为空"); process.exit(1); }
    console.log("[OK] Tab " + t.dataset.tab + " 渲染通过");
  }

  // 断言：矩阵 HTML 真的包含领域行与复选框
  await TABS[0].onclick();
  const detailHtml = REG("#r-detail").innerHTML;
  if (!/data-area="parents"/.test(detailHtml)) {
    console.error("[FAIL] 矩阵未渲染出 parents 领域的复选框");
    process.exit(1);
  }
  console.log("[OK] 矩阵渲染出领域行与复选框");

  if (!/type="checkbox"/.test(detailHtml)) {
    console.error("[FAIL] 矩阵内没有可勾选的复选框");
    process.exit(1);
  }
  console.log("[OK] 复选框存在（可编辑状态验证见下方 saved同种disabled 计数）");

  const disabled = (detailHtml.match(/disabled/g) || []).length;
  console.log("[INFO] 默认选中角色的 disabled 复选框数量：" + disabled);

  // 总览页 HTML
  const overviewHtml = tabHolder.innerHTML;
  console.log("[OK] 三个分支均渲染完成，无异常");

  // —— 服务端数据一致性：每个内置角色都能被解析出权限 ——
  const roles = await api.listRoles();
  for (const r of roles) {
    if (!r.privileges || typeof r.privileges !== "object") {
      console.error("[FAIL] 角色 " + r.id + " 缺少 privileges");
      process.exit(1);
    }
  }
  console.log("[OK] " + roles.length + " 个角色全部具备权限矩阵");
  roles.forEach((r) => {
    const n = Object.keys(r.privileges).reduce((s, k) => s + r.privileges[k].length, 0);
    console.log("    - " + r.id.padEnd(10) + " 权限项=" + String(n).padEnd(4) + " 成员=" + r.memberCount + (r.overridden ? "  (已调整)" : ""));
  });
  void overviewHtml;
  console.log("\n全部冒烟检查通过。");
})().catch((e) => { console.error("[FAIL] 未捕获异常：" + (e.stack || e.message)); process.exit(1); });
