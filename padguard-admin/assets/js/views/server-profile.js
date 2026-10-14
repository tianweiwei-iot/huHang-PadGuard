/* 服务器基本信息配置（后台可配置：对外 IP / 端口 / 协议等） */
(function (PG) {
  const U = PG.ui;
  function esc(s) { return U && U.esc ? U.esc(s == null ? "" : String(s)) : (s == null ? "" : String(s)); }

  PG.registerView("serverProfile", {
    title: "服务器设置", icon: "🌐", group: "系统",
    render: () => `
      <div class="page-head"><div><h2>服务器基本信息</h2><p>配置服务端对外访问地址；IP 漂移后在此更新即可，无需改代码或重打包</p></div>
      <div class="page-actions"><button class="btn" id="sp-refresh">↻ 刷新</button><button class="btn btn-primary" id="sp-save">保存</button></div></div>
      <div id="sp-body"><div class="empty">加载中…</div></div>`,
    mount: async (root) => {
      root.querySelector("#sp-refresh").onclick = () => PG.views.serverProfile.mount(root);
      let p;
      try { p = await PG.api.serverProfile(); }
      catch (e) { U.toast && U.toast("err", "加载失败", e.message); return; }
      if (!p) p = { publicHost: "8.148.68.183", port: 8090, scheme: "http", serverName: "PadGuard 管控服务端", remark: "" };

      const row = (label, inner) =>
        '<div style="display:flex;justify-content:space-between;align-items:center;gap:12px;padding:9px 0;border-bottom:1px solid var(--bg-2)">' +
        '<span style="min-width:120px;color:var(--text-2)">' + label + '</span>' + inner + '</div>';
      const inputStyle = "flex:1;padding:8px 10px;border-radius:8px;border:1px solid var(--bg-2);background:var(--bg-2);color:var(--text);";

      root.querySelector("#sp-body").innerHTML = `
      <div class="card" style="max-width:680px">
        ${row("对外地址 (域名/IP)", '<input id="sp-host" style="' + inputStyle + '" placeholder="如 8.148.68.183" value="' + esc(p.publicHost) + '">')}
        ${row("端口", '<input id="sp-port" type="number" style="' + inputStyle + '" placeholder="8090" value="' + esc(p.port) + '">')}
        ${row("协议", '<select id="sp-scheme" style="' + inputStyle + '"><option value="http"' + (p.scheme === "http" ? " selected" : "") + '>http</option><option value="https"' + (p.scheme === "https" ? " selected" : "") + '>https</option></select>')}
        ${row("服务器名称", '<input id="sp-name" style="' + inputStyle + '" placeholder="PadGuard 管控服务端" value="' + esc(p.serverName) + '">')}
        ${row("备注", '<textarea id="sp-remark" style="' + inputStyle + ';min-height:60px" placeholder="内部备注">' + esc(p.remark) + '</textarea>')}
        ${row("最后更新", '<span class="muted">' + esc(p.updatedAt ? new Date(p.updatedAt).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" }) : "未设置") + '</span>')}
        ${row("对外访问地址预览", '<code id="sp-preview" style="color:var(--accent)"></code>')}
      </div>`;

      const preview = () => {
        const h = root.querySelector("#sp-host").value.trim();
        const port = root.querySelector("#sp-port").value.trim();
        const scheme = root.querySelector("#sp-scheme").value;
        root.querySelector("#sp-preview").textContent = scheme + "://" + (h || "-") + ":" + (port || 8090) + "/v1/";
      };
      ["sp-host", "sp-port", "sp-scheme"].forEach(function (id) {
        root.querySelector("#" + id).addEventListener("input", preview);
        root.querySelector("#" + id).addEventListener("change", preview);
      });
      preview();

      root.querySelector("#sp-save").onclick = async () => {
        const body = {
          publicHost: root.querySelector("#sp-host").value.trim(),
          port: parseInt(root.querySelector("#sp-port").value, 10),
          scheme: root.querySelector("#sp-scheme").value,
          serverName: root.querySelector("#sp-name").value.trim(),
          remark: root.querySelector("#sp-remark").value
        };
        if (!body.publicHost) { U.toast && U.toast("warn", "请填写对外地址"); return; }
        if (isNaN(body.port) || body.port < 1 || body.port > 65535) { U.toast && U.toast("warn", "端口需在 1-65535"); return; }
        try {
          await PG.api.saveServerProfile(body);
          U.toast && U.toast("ok", "已保存", "服务器基本信息已更新");
          PG.views.serverProfile.mount(root);
        } catch (e) { U.toast && U.toast("err", "保存失败", e.message); }
      };
    },
  });
})(window.PG);
