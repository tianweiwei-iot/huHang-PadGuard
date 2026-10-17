/* ============================================================
   PadGuard 管控后台 · 通用 UI 组件库
   ============================================================ */
(function (global) {
  const PG = global.PG || (global.PG = {});
  const doc = document;

  // 视图注册表（提前定义，供各 view 文件在 router.js 之前注册）
  PG.views = PG.views || {};
  PG.registerView = function (key, def) { def.key = key; PG.views[key] = def; };

  const esc = (s) => String(s == null ? "" : s).replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const money = (fen) => "¥" + ((fen || 0) / 100).toFixed(2);
  const moneyK = (fen) => { const y = (fen || 0) / 100; return y >= 10000 ? "¥" + (y / 10000).toFixed(1) + "万" : "¥" + y.toFixed(0); };
  const pct = (n) => (n || 0) + "%";

  // ---------- Toast ----------
  function toast(type, title, msg) {
    const root = doc.getElementById("toast-root");
    const el = doc.createElement("div");
    el.className = "toast " + (type || "");
    const ico = { ok: "✓", err: "✕", warn: "!", "": "ℹ" }[type] || "ℹ";
    el.innerHTML = '<div style="font-size:16px;line-height:1">' + ico + '</div><div><div class="t-title">' + esc(title) + '</div>' + (msg ? '<div class="t-msg">' + esc(msg) + '</div>' : '') + '</div>';
    root.appendChild(el);
    setTimeout(() => { el.style.opacity = "0"; el.style.transform = "translateX(20px)"; setTimeout(() => el.remove(), 250); }, 3200);
  }

  // ---------- Modal ----------
  function modal({ title, body, foot, width }) {
    const root = doc.getElementById("modal-root");
    const mask = doc.createElement("div"); mask.className = "modal-mask";
    mask.innerHTML = '<div class="modal" style="' + (width ? "width:" + width + "px" : "") + '"><div class="modal-head"><h3>' + esc(title) + '</h3><button class="modal-close">×</button></div><div class="modal-body">' + body + '</div>' + (foot ? '<div class="modal-foot">' + foot + '</div>' : '') + '</div>';
    root.appendChild(mask);
    const close = () => mask.remove();
    mask.querySelector(".modal-close").onclick = close;
    mask.onclick = (e) => { if (e.target === mask && !foot) close(); };
    return { el: mask, close, body: mask.querySelector(".modal-body"), foot: mask.querySelector(".modal-foot") };
  }

  // ---------- Drawer ----------
  function drawer({ title, body, width }) {
    const root = doc.getElementById("modal-root");
    const mask = doc.createElement("div"); mask.className = "drawer-mask";
    const panel = doc.createElement("div"); panel.className = "drawer";
    if (width) panel.style.width = width + "px";
    panel.innerHTML = '<div class="drawer-head"><h3 style="margin:0;font-size:16px">' + esc(title) + '</h3><button class="modal-close">×</button></div><div class="drawer-body">' + body + '</div>';
    root.appendChild(mask); root.appendChild(panel);
    const close = () => { mask.remove(); panel.remove(); };
    panel.querySelector(".modal-close").onclick = close;
    mask.onclick = close;
    return { el: panel, close, body: panel.querySelector(".drawer-body") };
  }

  function confirm(title, text, onOk, danger) {
    const m = modal({
      title, body: '<p style="margin:0;color:var(--text-2);line-height:1.7">' + esc(text) + '</p>',
      foot: '<button class="btn btn-ghost" id="cf-cancel">取消</button><button class="btn ' + (danger ? "btn-danger" : "btn-primary") + '" id="cf-ok">' + (danger ? "确认执行" : "确认") + '</button>',
    });
    m.el.querySelector("#cf-cancel").onclick = m.close;
    m.el.querySelector("#cf-ok").onclick = () => { m.close(); onOk && onOk(); };
  }

  // ---------- 状态徽章 ----------
  const PARENT_STATUS = { active: ["green", "正常"], suspended: ["red", "已冻结"], pending: ["amber", "待激活"] };
  const SUB_STATUS = { active: ["green", "生效中"], expired: ["gray", "已过期"], trial: ["amber", "试用"] };
  const ORDER_STATUS = { paid: ["green", "已支付"], unpaid: ["amber", "待支付"], refunded: ["gray", "已退款"] };
  // 关键：v[1] 可能来自服务端字段（controlMode / status 等枚举都来自 DB），
  // 未命中映射时会直接回退成原始 key 插入 HTML —— 一旦字段被污染即为存储型 XSS。
  function badge(map, key) { const v = map[key] || ["gray", key]; return '<span class="badge ' + esc(v[0]) + '">' + esc(v[1]) + '</span>'; }

  // ---------- SVG 图表 ----------
  function donut(data, opts) {
    opts = opts || {};
    const W = opts.size || 180, H = W, cx = W / 2, cy = H / 2, r = W / 2 - 14, sw = opts.stroke || 22;
    const total = data.reduce((s, d) => s + d.value, 0) || 1;
    let acc = -Math.PI / 2; const arcs = [];
    data.forEach(d => {
      const ang = (d.value / total) * Math.PI * 2;
      const x1 = cx + r * Math.cos(acc), y1 = cy + r * Math.sin(acc);
      const x2 = cx + r * Math.cos(acc + ang), y2 = cy + r * Math.sin(acc + ang);
      const large = ang > Math.PI ? 1 : 0;
      arcs.push('<path d="M' + x1 + ' ' + y1 + ' A' + r + ' ' + r + ' 0 ' + large + ' 1 ' + x2 + ' ' + y2 + '" fill="none" stroke="' + d.color + '" stroke-width="' + sw + '" stroke-linecap="butt"/>');
      acc += ang;
    });
    const center = opts.center ? '<text x="' + cx + '" y="' + (cy - 2) + '" text-anchor="middle" fill="var(--text)" font-size="' + (opts.fontSize || 22) + '" font-weight="700">' + opts.center + '</text><text x="' + cx + '" y="' + (cy + 16) + '" text-anchor="middle" fill="var(--text-3)" font-size="11">' + (opts.centerSub || "") + '</text>' : "";
    return '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%" style="max-width:' + W + 'px">' + arcs.join("") + center + '</svg>';
  }

  function line(series, opts) {
    opts = opts || {};
    const W = 520, H = opts.h || 200, pad = 34;
    const max = Math.max(...series, 1) * 1.15, min = 0;
    const n = series.length;
    const X = (i) => pad + (i * (W - pad * 2) / Math.max(1, n - 1));
    const Y = (v) => H - pad - ((v - min) / (max - min)) * (H - pad * 2);
    let d = "", pts = "";
    series.forEach((v, i) => { d += (i ? "L" : "M") + X(i) + " " + Y(v); pts += '<circle cx="' + X(i) + '" cy="' + Y(v) + '" r="3" fill="var(--brand)"/>'; });
    const grid = [0, .25, .5, .75, 1].map(t => { const y = pad + t * (H - pad * 2); return '<line x1="' + pad + '" y1="' + y + '" x2="' + (W - pad) + '" y2="' + y + '" stroke="var(--border-soft)"/>'; }).join("");
    return '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%"><defs><linearGradient id="lg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="var(--brand)" stop-opacity=".28"/><stop offset="1" stop-color="var(--brand)" stop-opacity="0"/></linearGradient></defs>' + grid + '<path d="' + d + ' L' + X(n - 1) + ' ' + (H - pad) + ' L' + X(0) + ' ' + (H - pad) + ' Z" fill="url(#lg)"/><path d="' + d + '" fill="none" stroke="var(--brand)" stroke-width="2.4"/>' + pts + '</svg>';
  }

  function bar(data, opts) {
    opts = opts || {};
    const W = 520, H = opts.h || 220, pad = 30, n = data.length;
    const max = Math.max(...data.map(d => d.value), 1) * 1.1;
    const bw = (W - pad * 2) / n * 0.56;
    const gap = (W - pad * 2) / n;
    let bars = "", labels = "";
    data.forEach((d, i) => {
      const h = (d.value / max) * (H - pad * 2);
      const x = pad + i * gap + (gap - bw) / 2, y = H - pad - h;
      bars += '<rect x="' + x + '" y="' + y + '" width="' + bw + '" height="' + h + '" rx="5" fill="' + (d.color || "var(--brand)") + '"/>';
      labels += '<text x="' + (x + bw / 2) + '" y="' + (H - 9) + '" text-anchor="middle" fill="var(--text-3)" font-size="10">' + esc(d.label) + '</text>';
    });
    return '<svg viewBox="0 0 ' + W + ' ' + H + '" width="100%"><line x1="' + pad + '" y1="' + (H - pad) + '" x2="' + (W - pad) + '" y2="' + (H - pad) + '" stroke="var(--border-soft)"/><line x1="' + pad + '" y1="' + pad + '" x2="' + pad + '" y2="' + (H - pad) + '" stroke="var(--border-soft)"/>' + bars + labels + '</svg>';
  }

  function sparkline(values, color) {
    const W = 90, H = 30, max = Math.max(...values, 1), min = 0;
    const pts = values.map((v, i) => (i * (W / (values.length - 1))) + "," + (H - (v / max) * H)).join(" ");
    return '<svg viewBox="0 0 ' + W + ' ' + H + '" width="' + W + '" height="' + H + '"><polyline points="' + pts + '" fill="none" stroke="' + (color || "var(--accent)") + '" stroke-width="2"/></svg>';
  }

  PG.ui = {
    esc, money, moneyK, pct, toast, modal, drawer, confirm,
    badge, PARENT_STATUS, SUB_STATUS, ORDER_STATUS,
    donut, line, bar, sparkline,
  };
})(window);
