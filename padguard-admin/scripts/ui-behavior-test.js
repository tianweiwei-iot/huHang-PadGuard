#!/usr/bin/env node
/**
 * padguard-admin 核心纯函数行为测试（node scripts/ui-behavior-test.js，零依赖）。
 *
 * 为什么需要它：guard.js 是「源码断言」，只能守住"视图插值都调用了 U.esc"，
 * 却无法验证 U.esc 本身是否真的转义正确。若有人手滑把 esc 的正则或映射改坏，
 * 全站 XSS 防线会在 guard.js 全绿的情况下静默失效。这里用 Node vm 加载真实的
 * components.js，对这些最底层的安全/口径函数做**行为断言**，补上这个覆盖缺口。
 *
 * 被测函数全部是纯函数（不触 DOM），仅 downloadCsv 用轻量 stub 捕获输出。
 * 任何断言失败非 0 退出，由 guard.js 汇入 CI 单一入口。
 */
'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const ROOT = path.resolve(__dirname, '..');
const src = fs.readFileSync(path.join(ROOT, 'assets/js/components.js'), 'utf8');

// downloadCsv 用 stub 捕获 Blob 内容，其余函数不触 DOM。
let lastBlobParts = null;
class MockBlob {
  constructor(parts) { lastBlobParts = parts; }
}
const stubEl = () => ({
  style: {}, classList: { contains: () => false, add() {}, remove() {} },
  setAttribute() {}, appendChild() {}, remove() {}, click() {},
  querySelector() { return null; },
});
const sandbox = {
  window: {},
  document: {
    getElementById: () => null,
    createElement: stubEl,
    body: { appendChild() {} },
  },
  setTimeout() {}, clearTimeout() {},
  Blob: MockBlob,
  URL: { createObjectURL: () => 'blob:stub', revokeObjectURL() {} },
  console,
};
vm.createContext(sandbox);
vm.runInContext(src, sandbox, { filename: 'components.js' });
const ui = sandbox.window.PG && sandbox.window.PG.ui;

let failures = 0;
function check(label, cond, detail) {
  if (cond) {
    console.log('PASS  ' + label);
  } else {
    failures++;
    console.error('FAIL  ' + label + (detail ? '\n      ' + detail : ''));
  }
}
function eq(label, actual, expected) {
  check(label, actual === expected, '实际=' + JSON.stringify(actual) + ' 期望=' + JSON.stringify(expected));
}

if (!ui || typeof ui.esc !== 'function') {
  console.error('FAIL  components.js 未挂载 window.PG.ui（IIFE 结构变了？）');
  process.exit(1);
}

// ---- esc：admin 全站 XSS 防线的最底层 ----
eq('esc 转义全部 5 个 HTML 敏感字符 & < > " \'',
  ui.esc('&<>"\''), '&amp;&lt;&gt;&quot;&#39;');
check('esc 让 <img onerror> 注入串彻底失效',
  (() => { const s = ui.esc('<img src=x onerror=alert(1)>'); return s.includes('&lt;img') && !s.includes('<img'); })());
eq('esc(null) → 空串', ui.esc(null), '');
eq('esc(undefined) → 空串', ui.esc(undefined), '');
eq('esc(0) → "0"（不能因假值判空而丢失合法零值）', ui.esc(0), '0');

// ---- fmtMinutes：无数据必须显示破折号，绝不粉饰成 0 ----
eq('fmtMinutes(null) → —', ui.fmtMinutes(null), '—');
eq('fmtMinutes(undefined) → —', ui.fmtMinutes(undefined), '—');
eq('fmtMinutes(NaN) → —', ui.fmtMinutes(NaN), '—');
eq('fmtMinutes(0) → 0h0m（真实零值与无数据必须可区分）', ui.fmtMinutes(0), '0h0m');
eq('fmtMinutes(150) → 2h30m', ui.fmtMinutes(150), '2h30m');

// ---- badge：未命中映射的键来自服务端字段，必须转义 ----
eq('badge 已知键渲染正常',
  ui.badge({ active: ['green', '正常'] }, 'active'),
  '<span class="badge green">正常</span>');
check('badge 未知键携带 XSS 载荷时被转义',
  (() => { const s = ui.badge({}, '<img src=x>'); return !s.includes('<img') && s.includes('&lt;img'); })());

// ---- downloadCsv：BOM + RFC4180 + 公式注入防护 ----
lastBlobParts = null;
ui.downloadCsv('t.csv',
  ['名字', '数值'],
  [['=1+1', 'a,b'], ['@SUM(A1)', 'line1\nline2'], ['-cmd', '正常'], ['a"b', 'plain']]);
const csv = lastBlobParts ? lastBlobParts.join('') : '';
check('CSV 带 UTF-8 BOM（Excel 打开中文不乱码）', csv.startsWith('\uFEFF'));
check('CSV 公式注入 = 开头前置单引号', csv.includes("'=1+1"), csv);
check('CSV 公式注入 @ 开头前置单引号', csv.includes("'@SUM(A1)"), csv);
check('CSV 公式注入 - 开头前置单引号', csv.includes("'-cmd"), csv);
check('CSV 逗号字段按 RFC4180 加引号', csv.includes('"a,b"'), csv);
check('CSV 换行字段按 RFC4180 加引号', csv.includes('"line1\nline2"'), csv);
check('CSV 内部引号按 RFC4180 双写', csv.includes('"a""b"'), csv);

// ---- 图表退化路径：NaN 坐标会被 SVG 静默丢弃，界面出现空白 ----
eq('sparkline 空数组 → 空串', ui.sparkline([]), '');
check('sparkline 单点不产生 NaN 坐标',
  (() => { const s = ui.sparkline([5]); return s.includes('<circle') && !s.includes('NaN'); })());
check('line 空序列 → 暂无数据占位且不产生 NaN',
  (() => { const s = ui.line([]); return s.includes('暂无数据') && !s.includes('NaN'); })());

if (failures > 0) {
  console.error('\nadmin UI 行为测试: ' + failures + ' 项失败');
  process.exit(1);
}
console.log('\nadmin UI 行为测试: 全部通过（' + 22 + ' 项）');
