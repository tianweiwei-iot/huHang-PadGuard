#!/usr/bin/env node
/**
 * padguard-admin API 客户端行为测试（node scripts/api-behavior-test.js，零依赖）。
 *
 * 为什么需要它：guard.js 只能对 api.js 做源码文本断言（"出现了某字符串"），
 * 无法验证这些安全逻辑的**行为**。这里用 Node vm 加载真实 api.js，在内存里
 * 追加一行导出内部函数（不写回磁盘），对最关键的鉴权/降级逻辑做行为断言：
 *   - isPrivateHost：?api= 覆盖白名单，防诱导链接把管理员口令 POST 到恶意域名；
 *   - 401 令牌代次比对：旧会话的迟到 401 不得踢掉刚登录的新会话；
 *   - 假 token 拦截、写操作绝不降级、读操作仅网络失败才降级；
 *   - qs 空值剔除、中文错误文案。
 * 任何断言失败非 0 退出，由 guard.js 汇入 CI 单一入口。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
let src = fs.readFileSync(path.join(ROOT, 'assets/js/api.js'), 'utf8');

// 内存注入：把 IIFE 内部函数挂到 PG.__test 供断言（不修改磁盘源码）。
const tail = '})(window);';
const at = src.lastIndexOf(tail);
if (at < 0) {
  console.error('FAIL  api.js 未找到 IIFE 结尾 ' + tail);
  process.exit(1);
}
src = src.slice(0, at) +
  'global.PG.__test = { isPrivateHost, allowedOverride, qs, httpStatusHint, http, liveOrMock, liveWrite, config, NetworkError, api };\n' +
  src.slice(at);

// ---- 最小浏览器环境 stub ----
const locationStub = { protocol: 'http:', origin: 'http://admin.example.com', search: '' };
let fetchImpl = async () => { throw new Error('fetch 未配置'); };
let unauthCalls = 0;
let toastCalls = [];
const sandbox = {
  window: {},
  location: locationStub,
  URL, URLSearchParams, AbortController,
  console,
  setTimeout: (fn) => { if (typeof fn === 'function') fn(); return 0; }, // 立即触发，免去真实等待
  clearTimeout: () => {},
  fetch: (...a) => fetchImpl(...a),
};
vm.createContext(sandbox);
vm.runInContext(src, sandbox, { filename: 'api.js' });

const PG = sandbox.window.PG;
const T = PG.__test;
if (!T || typeof T.isPrivateHost !== 'function') {
  console.error('FAIL  内存注入未生效（IIFE 结构变了？）');
  process.exit(1);
}
PG.onUnauthorized = () => { unauthCalls++; };
PG.toast = (...a) => { toastCalls.push(a); };

let failures = 0, total = 0;
function check(label, cond, detail) {
  total++;
  if (cond) console.log('PASS  ' + label);
  else { failures++; console.error('FAIL  ' + label + (detail ? '\n      ' + detail : '')); }
}
function eq(label, actual, expected) {
  check(label, actual === expected, '实际=' + JSON.stringify(actual) + ' 期望=' + JSON.stringify(expected));
}

async function main() {
  // ---- isPrivateHost：?api= 覆盖白名单的最底层判定 ----
  // 旧实现用整条 URL 前缀正则，这 3 个域名会被误判为内网放行 → 凭据外泄。
  eq('isPrivateHost 拒绝 192.168.evil.com', T.isPrivateHost('192.168.evil.com'), false);
  eq('isPrivateHost 拒绝 10.evil.com', T.isPrivateHost('10.evil.com'), false);
  eq('isPrivateHost 拒绝 localhost.evil.com', T.isPrivateHost('localhost.evil.com'), false);
  // 合法内网/本机必须放行
  eq('isPrivateHost 放行 192.168.1.1', T.isPrivateHost('192.168.1.1'), true);
  eq('isPrivateHost 放行 10.0.0.1', T.isPrivateHost('10.0.0.1'), true);
  eq('isPrivateHost 放行 172.16.0.1', T.isPrivateHost('172.16.0.1'), true);
  eq('isPrivateHost 放行 172.31.255.255（边界）', T.isPrivateHost('172.31.255.255'), true);
  eq('isPrivateHost 拒绝 172.32.0.1（越界）', T.isPrivateHost('172.32.0.1'), false);
  eq('isPrivateHost 放行 127.0.0.1', T.isPrivateHost('127.0.0.1'), true);
  eq('isPrivateHost 放行 localhost', T.isPrivateHost('localhost'), true);
  eq('isPrivateHost 放行 ::1', T.isPrivateHost('::1'), true);
  eq('isPrivateHost 放行带方括号 [::1]', T.isPrivateHost('[::1]'), true);
  eq('isPrivateHost 放行 IPv6 ULA fc00::1', T.isPrivateHost('fc00::1'), true);
  eq('isPrivateHost 放行 IPv6 链路本地 fe80::1', T.isPrivateHost('fe80::1'), true);
  eq('isPrivateHost 拒绝公网 8.8.8.8', T.isPrivateHost('8.8.8.8'), false);
  eq('isPrivateHost 拒绝非法 999.1.1.1', T.isPrivateHost('999.1.1.1'), false);

  // ---- allowedOverride：http 打开时 https 外链一律拒绝（P0-1 钓鱼防线）----
  eq('allowedOverride 拒绝公网 https（非 file 打开）', T.allowedOverride('https://attacker.example'), false);
  eq('allowedOverride 放行同源', T.allowedOverride('http://admin.example.com/x'), true);
  eq('allowedOverride 放行内网 http', T.allowedOverride('http://192.168.1.1:8090'), true);
  eq('allowedOverride 拒绝伪装内网域名', T.allowedOverride('http://192.168.evil.com'), false);
  eq('allowedOverride 拒绝非法 URL', T.allowedOverride('not a url'), false);

  // ---- qs：剔除空值键，但保留合法 0 ----
  eq('qs 剔除 undefined/null/空串', T.qs({ status: undefined, page: 1, size: 20, kw: '', x: null }), 'page=1&size=20');
  eq('qs 保留数字 0（不能当假值删掉）', T.qs({ a: null, b: '', c: 0 }), 'c=0');

  // ---- 中文错误文案 ----
  check('httpStatusHint 401 为中文且不含英文 HTTP', (() => {
    const s = T.httpStatusHint(401); return /[\u4e00-\u9fa5]/.test(s) && !/HTTP/i.test(s);
  })());
  eq('explainError 中文原因原样透传', PG.explainError(new Error('密码错误')), '密码错误');
  check('explainError 把 HTTP 401 翻成中文', /[\u4e00-\u9fa5]/.test(PG.explainError(new Error('HTTP 401'))));
  check('explainError 把 Failed to fetch 翻成中文', /无法连接服务端/.test(PG.explainError(new Error('Failed to fetch'))));

  // ---- 401 令牌代次比对：旧会话迟到响应不得踢掉新会话 ----
  T.config.useMock = false;
  T.config.token = 'OLD';
  T.config.baseUrl = 'http://admin.example.com';
  unauthCalls = 0;
  fetchImpl = async () => {
    // 请求在途时用户登出并用新账号登录 → config.token 已被替换为 NEW
    T.config.token = 'NEW';
    return { ok: false, status: 401, json: async () => ({ message: '登录已失效' }) };
  };
  let lateErr = null;
  try { await T.http('GET', '/v1/admin/dashboard'); } catch (e) { lateErr = e; }
  check('迟到 401 仍如实上抛错误（不静默）', !!lateErr);
  eq('旧会话迟到 401 不清空新 token', T.config.token, 'NEW');
  eq('旧会话迟到 401 不触发强制登出', unauthCalls, 0);

  // 当前会话的 401：必须清 token + 触发登出
  T.config.token = 'CURRENT';
  unauthCalls = 0;
  fetchImpl = async () => ({ ok: false, status: 401, json: async () => ({ message: '登录已失效' }) });
  try { await T.http('GET', '/v1/admin/dashboard'); } catch (e) { /* 预期抛错 */ }
  eq('当前会话 401 清空 token', T.config.token, '');
  eq('当前会话 401 触发一次强制登出', unauthCalls, 1);

  // 登录接口自身的 401（无 token）不应触发强制登出（避免踢来踢去）
  T.config.token = '';
  unauthCalls = 0;
  fetchImpl = async () => ({ ok: false, status: 401, json: async () => ({ message: '密码错误' }) });
  try { await T.http('POST', '/v1/admin/login', {}); } catch (e) { /* 预期抛错 */ }
  eq('登录接口 401（无 token）不触发强制登出', unauthCalls, 0);

  // ---- 假 token 拦截：服务端响应缺 token 必须报错，不得"看似成功" ----
  fetchImpl = async () => ({ ok: true, json: async () => ({ code: 0, data: { admin: { username: 'a' } } }) });
  let loginErr = null;
  try { await T.api.login('a', 'b'); } catch (e) { loginErr = e; }
  check('登录响应缺 token 时报错（无假会话）', !!loginErr && /缺少 token/.test(loginErr.message));

  // ---- 写操作绝不降级：网络失败必须上抛 ----
  T.config.token = 'W';
  fetchImpl = async () => { const e = new Error('Failed to fetch'); throw e; };
  let writeRejected = false;
  try { await T.api.suspendParent('P1'); } catch (e) { writeRejected = true; }
  check('写操作网络失败上抛（无假成功）', writeRejected);

  // ---- 读操作仅网络失败降级，HTTP 错误如实上抛 ----
  let degraded = await T.liveOrMock(
    () => Promise.reject(new T.NetworkError('断网')),
    () => 'MOCK-DATA'
  );
  eq('读操作网络失败降级到空数据', degraded, 'MOCK-DATA');
  let httpDegraded = false;
  try {
    await T.liveOrMock(
      () => Promise.reject(new Error('HTTP 403 无权限')),
      () => 'MOCK-DATA'
    );
  } catch (e) { httpDegraded = true; }
  check('读操作 HTTP 错误不上抛假数据（如实拒绝）', httpDegraded);

  if (failures > 0) {
    console.error('\nadmin API 行为测试: ' + failures + ' 项失败（共 ' + total + '）');
    process.exit(1);
  }
  console.log('\nadmin API 行为测试: 全部通过（' + total + ' 项）');
}

main().catch((e) => {
  console.error('admin API 行为测试异常终止: ' + (e && e.stack || e));
  process.exit(1);
});
