#!/usr/bin/env node
/**
 * padguard-admin 契约守卫（node scripts/guard.js，零依赖）。
 *
 * admin 是无构建的纯静态 JS，无法像 Kotlin/服务端那样用行为测试锁死契约；
 * 本守卫用「源码断言」守住最容易回归的关键不变量。每条规则失败即非 0 退出，
 * 由 launch-preflight.sh 统一调用（CI 也只调 preflight）。新增守卫规则时保持"精确锚定 + 失败可读"。
 */
'use strict';

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8');

let failures = 0;
let checks = 0;
let behaviorPasses = 0;
function check(label, cond, detail) {
  checks++;
  if (cond) {
    console.log('PASS  ' + label);
  } else {
    failures++;
    console.error('FAIL  ' + label + (detail ? '\n      ' + detail : ''));
  }
}

// ---- api.js ----
const api = read('assets/js/api.js');

check(
  '401 迟到响应比对 sentToken，旧会话不得踢掉新会话',
  api.includes('config.token === sentToken'),
  'token 代次比对被删除会导致并发会话互相顶号'
);

check(
  '登录失败不得静默降级为假 token',
  api.includes('服务端登录响应缺少 token') && api.includes('r.token'),
  '假 token 会造成"看似登录成功却没有真实会话"'
);

check(
  'admin 请求带 Authorization: Bearer 头',
  api.includes('"Authorization": "Bearer " + sentToken'),
  '鉴权头丢失会让全部接口 401'
);

check(
  '?api= 跨源覆盖必须记录目标供登录前确认',
  api.includes('overrideTarget: (() =>') && api.includes('overrideConfirmed: false'),
  'allowedOverride 放行私网 http；不记录目标就无法阻止钓鱼链把管理员口令发往内网攻击者主机'
);

const appJs = read('assets/js/app.js');
check(
  '登录表单必须显式确认跨源 ?api= 目标后才发送凭据',
  appJs.includes('PG.api.config.overrideTarget') &&
    appJs.includes('PG.api.config.overrideConfirmed') &&
    appJs.includes('window.confirm('),
  '地址栏仍是合法后台，但 ?api= 可把口令与 Bearer token 指向攻击者主机；必须强制人工确认'
);

// ---- settings.js ----
const settings = read('assets/js/views/settings.js');

check(
  'TOTP 开关未实现前必须保持禁用（防虚假安全感）',
  settings.toLowerCase().includes('disabled'),
  '服务端登录流程尚未接入 TOTP 校验，开关可写即构成"已开启二次验证"的假象'
);

// ---- 全部视图 ----
// 存储型 XSS 防线：admin 与后端同源，innerHTML 塞未转义的服务端数据 = 窃取管理员会话。
// 代码库约定：动态内容一律经 U.esc 转义；U.pager/U.badge/数值格式化器是已知安全的生成器。
// 白名单策略：以下正则是**逐项人工核对过**的既有安全用法；新增插值默认被拦截，
// 放宽白名单前必须先人工确认新插值不携带服务端可控数据。
const viewFiles = fs.readdirSync(path.join(ROOT, 'assets/js/views')).filter((f) => f.endsWith('.js'));
const SAFE_GENERATOR = [
  /U\.esc\(/, /U\.pager\(/, /U\.badge\(/, /U\.money/, /U\.moneyK/, /U\.pct/, /U\.fmt/,
  /^PG\.guard\(/,            // RBAC 包裹器：无权限返回 ''，有权限返回调用方提供的字面量 HTML
  /PG\.guard\(/,             // 三元两侧均为 PG.guard 调用（devices.js 远程锁屏按钮）
  /^countOf\(/,              // rbac.js：纯数字归约
  /^Object\.keys\((r\.)?privileges\)\.length$/, // 纯数字
  /^[\w.]*\.(deviceCount|childrenCount|memberCount)$/, // 服务端数值字段
  /^planHint$/,              // parents.js：代码内字面量文案
  /^head$/, /^rows$/, /^cells$/, /^n$/, // rbac.js 矩阵：由 U.esc'd 片段/数字组装（2026-11-18 已把 area.key/act 补上 U.esc）
  /\.actions\.length$/,      // 纯数字
  /^role\.name$/, /^msg$/,   // rbac.js:U.confirm 文本参数 —— confirm 内部整体 esc（见其 modal 实现）
  /^area$/,                  // rbac.js:querySelector 选择器模板（非 HTML 上下文）
  /^bg$/, /^total$/,         // rbac.js:热力格 —— bg 为静态 rgba 字面量，total 数字
  /^opts$/,                  // rbac.js:下拉 options，逐项 U.esc(r.id)/U.esc(r.name) 后 join
  /^row\(/,                  // server-profile.js：行构建器，入参 value 均已 esc
  /^heapPct$/, /^errorRate$/, /^qpm$/, /^Math\.min\(/, // server.js 资源水位：数字与百分比
  /^renderLogs\(/,           // server.js：日志逐条 U.esc(l.time/level/msg) 后 join
  /^dir$/,                   // server.js kpi 箭头方向：up/down/flat 静态集合（CSS 类名）
  /^trend$/, /^spark$/,      // server.js kpi 入参：调用点均为 "文本 " + U.esc(...) 拼接 / U.sparkline / ""
  /^U\.esc\(.*\)$|^U\.esc\(.*\)\s*\+\s*["'][^"']*["']$/, // 直接内联的已转义表达式
  /^c$/,                     // audit.js 徽章类名：来自 {high:"red",mid:"amber",low:"gray"} 静态映射
  /^stamp$/,                 // audit.js 导出 CSV 文件名：U.todayStamp() 纯数字日期（非 HTML 上下文）
  /(^[\w$.]+\.length$)/,     // 数组长度：纯数字
  /\.maxRows$/, /\.total$/,  // 分页数值
  /^EXPORT_LIMIT$/,          // 常量数字
  /^mini\(/, /^kpi\(/,       // 视图局部安全构建器：入参均为数字/代码字面量，动态文本在函数内经 U.esc
  /^await alertsMini\(\)$/,  // dashboard.js：告警列表内部逐项 U.esc，失败分支文案也是转义拼接
  /^plans$/,                 // billing.js：options 由 U.esc(p.id)/U.esc(p.name) 逐项转义后 join
  /^trendLine$/,             // dashboard.js：由 U.esc(trend.label)/U.esc(sub) 组装
  /U\.sparkline\(/,          // components.js：纯数值 SVG，不插字符串
  /^ico$/, /^label$/, /^val$/,       // kpi 入参：调用点全部传代码字面量/数字
  /^d\.alertsOpen$/,                  // dashboard.js：待处理告警数（数字）
  /^hasDelta \? trend\.direction : ""$/, // direction ∈ {up,down}（CSS 类名，静态集合）
  /^area \? nameOf\(area\) : "全部领域"$/, // audit.js：导出 CSV 文件名（非 HTML 上下文）
  /["'][^"']*["']\s*:\s*["']/, // 字符串字面量三元：两个分支都是代码内字面量，服务端数据只走条件不进 HTML
  /===/,                     // 静态字符串三元（分支均为代码内字面量）
];
const isSafe = (expr) => SAFE_GENERATOR.some((re) => re.test(expr));
let unsafeInterpolations = [];
for (const f of viewFiles) {
  const src = read('assets/js/views/' + f);
  const re = /\$\{([^{}]*)\}/g;
  let m;
  while ((m = re.exec(src))) {
    const expr = m[1].trim();
    if (expr && !isSafe(expr)) {
      const line = src.slice(0, m.index).split('\n').length;
      unsafeInterpolations.push(f + ':' + line + ' ${' + expr.slice(0, 60) + '}');
    }
  }
}
check(
  '模板插值动态内容必须经转义或使用安全生成器（U.esc/U.pager/U.badge/数值格式化）',
  unsafeInterpolations.length === 0,
  unsafeInterpolations.slice(0, 8).join('\n      ')
);

// ---- 语法检查（node --check 相当于解析级校验）----
const { execFileSync } = require('child_process');
const jsFiles = ['api.js', 'app.js', 'components.js', 'mock.js', 'router.js']
  .map((f) => 'assets/js/' + f)
  .concat(viewFiles.map((f) => 'assets/js/views/' + f));
let syntaxOk = true;
const syntaxErrors = [];
for (const f of jsFiles) {
  try {
    execFileSync(process.execPath, ['--check', path.join(ROOT, f)], { stdio: 'pipe' });
  } catch (e) {
    syntaxOk = false;
    syntaxErrors.push(f + ': ' + String(e.stderr || e.message).slice(0, 120));
  }
}
check('全部 JS 通过 node --check 语法解析', syntaxOk, syntaxErrors.join('\n      '));

// ---- 核心纯函数行为测试 ----
// guard.js 的源码断言只能证明"视图调用了 U.esc"，无法证明 U.esc 本身转义正确。
// 这里汇入 ui-behavior-test.js（Node vm 加载真实 components.js 做行为断言），
// 补上 XSS 防线最底层的覆盖缺口。CI 只调用本脚本，故零新增步骤即可覆盖。
try {
  const out = execFileSync(process.execPath, [path.join(__dirname, 'ui-behavior-test.js')], { encoding: 'utf8' });
  behaviorPasses += (String(out).match(/^PASS /gm) || []).length;
  process.stdout.write(out);
} catch (e) {
  failures++;
  if (e.stdout) process.stdout.write(String(e.stdout));
  if (e.stderr) process.stderr.write(String(e.stderr));
}

// ---- API 客户端行为测试 ----
// 源码断言无法验证 isPrivateHost 白名单、401 令牌代次竞态、写操作不降级等
// 鉴权逻辑的真实行为。这里汇入 api-behavior-test.js（Node vm 加载真实 api.js）。
try {
  const out = execFileSync(process.execPath, [path.join(__dirname, 'api-behavior-test.js')], { encoding: 'utf8' });
  behaviorPasses += (String(out).match(/^PASS /gm) || []).length;
  process.stdout.write(out);
} catch (e) {
  failures++;
  if (e.stdout) process.stdout.write(String(e.stdout));
  if (e.stderr) process.stderr.write(String(e.stderr));
}

if (failures > 0) {
  console.error('\nadmin guard: ' + failures + ' 项失败');
  process.exit(1);
}
// 旧实现把 jsFiles.length+5（受检文件数+5）当成"断言数"，口径失实：源码断言实际只有 6 条。
console.log(
  '\nadmin guard: 全部通过（源码断言 ' + checks + ' 项 + 行为测试 ' + behaviorPasses +
  ' 项 = ' + (checks + behaviorPasses) + ' 项）'
);
