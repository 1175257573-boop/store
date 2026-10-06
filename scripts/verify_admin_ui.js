/**
 * 管理员后台页面实机验证
 *
 * 只测接口是不够的——「页面为空」是渲染层问题，必须真浏览器打开看。
 * 本脚本：
 *   1. 登录 admin，把 token 写进 localStorage
 *   2. 逐个打开管理端页面
 *   3. 捕获控制台报错（页面空白的真正原因往往在这里）
 *   4. 截图 + 统计页面上的可见元素数量
 *   5. 断言「页面不是空白」
 */
// Playwright 装在隔离目录，避免污染项目依赖
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');
const fs = require('fs');
const path = require('path');

const BASE = 'http://127.0.0.1:5173';
const API = 'http://127.0.0.1:8080/api';
const OUT = 'E:/WorkBuddy/Temp/admin-shots';
const passed = [], failed = [];

function check(name, cond, detail = '') {
  if (cond) { passed.push(name); console.log(`  [PASS] ${name}`); }
  else { failed.push(`${name} | ${detail}`); console.log(`  [FAIL] ${name}  -> ${detail}`); }
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });

  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  // 收集所有控制台消息与页面异常
  const consoleErrors = [];
  const pageErrors = [];
  page.on('console', m => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', e => pageErrors.push(e.message));

  console.log('='.repeat(70));
  console.log('管理员后台页面实机验证（Playwright）');
  console.log('='.repeat(70));

  // ---------- 登录并注入 token ----------
  console.log('\n【1】登录 admin 并写入 localStorage');
  console.log('-'.repeat(70));
  const loginResp = await page.request.post(`${API}/user/login`, {
    data: { username: 'admin', password: '123456' }
  });
  const loginData = await loginResp.json();
  const token = loginData?.data?.token;
  const role = loginData?.data?.role;
  check('admin 登录成功', !!token, JSON.stringify(loginData));
  check('role = 2（管理员）', role === 2, `实际 role=${role}`);

  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
  await page.evaluate(({ t, r, n }) => {
    localStorage.setItem('token', t);
    localStorage.setItem('user', JSON.stringify({
      userId: 1, username: 'admin', nickname: '系统管理员', role: r, roleText: '平台管理员'
    }));
  }, { t: token, r: role, n: 'admin' });
  console.log('  token 已注入 localStorage');

  // ---------- 逐页检查 ----------
  const pages = [
    { path: '/#/merchant/audit', name: '入驻审核', expect: '入驻审核' },
    { path: '/#/merchant/product-audit', name: '商品审核', expect: '商品审核' }
  ];

  for (const p of pages) {
    console.log(`\n【2】${p.name}  ${p.path}`);
    console.log('-'.repeat(70));

    consoleErrors.length = 0;
    pageErrors.length = 0;

    await page.goto(`${BASE}${p.path}`, { waitUntil: 'networkidle' });
    await page.waitForTimeout(1500);

    // 页面标题
    const h2 = await page.locator('.page-head h2').first().textContent().catch(() => null);
    check(`${p.name} 有标题`, h2 && h2.trim() === p.expect, `实际标题="${h2}"`);

    // 侧边栏
    const menuCount = await page.locator('.sidebar .el-menu-item').count();
    check(`${p.name} 侧边栏有菜单项`, menuCount > 0, `菜单数=${menuCount}`);

    // 可见元素总数（判断是否空白的关键指标）
    const visibleInfo = await page.evaluate(() => {
      const main = document.querySelector('main.content');
      if (!main) return { total: 0, text: '', rows: 0 };
      const all = main.querySelectorAll('*');
      let visible = 0;
      for (const el of all) {
        const r = el.getBoundingClientRect();
        if (r.width > 0 && r.height > 0) visible++;
      }
      return {
        total: all.length,
        visible,
        text: (main.innerText || '').trim().slice(0, 200),
        rows: main.querySelectorAll('.el-table__row').length,
        empties: main.querySelectorAll('.el-empty').length
      };
    });
    console.log(`  可见元素: ${visibleInfo.visible}/${visibleInfo.total}`);
    console.log(`  表格数据行: ${visibleInfo.rows}`);
    console.log(`  空状态组件: ${visibleInfo.empties}`);
    console.log(`  页面文本: ${JSON.stringify(visibleInfo.text.slice(0, 120))}`);

    check(`${p.name} 页面非空白（可见元素>30）`,
      visibleInfo.visible > 30, `仅 ${visibleInfo.visible} 个可见元素`);
    check(`${p.name} 有文本内容`,
      visibleInfo.text.length > 5, `文本长度=${visibleInfo.text.length}`);

    // 有数据时应有表格行，无数据时应有空状态提示
    if (visibleInfo.rows > 0) {
      check(`${p.name} 表格渲染了数据`, true, '');
    } else {
      check(`${p.name} 无数据时有明确空状态提示`,
        visibleInfo.empties > 0 || visibleInfo.text.includes('暂无'),
        `empties=${visibleInfo.empties}, 文本含"暂无"=${visibleInfo.text.includes('暂无')}`);
    }

    // 无 JS 报错
    check(`${p.name} 无 JS 运行时错误`,
      pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));

    // 截图
    const file = path.join(OUT, `${p.name}.png`);
    await page.screenshot({ path: file, fullPage: true });
    console.log(`  截图: ${file}`);
  }

  // ---------- 商家端对照（确认没被改坏）----------
  console.log('\n【3】商家端对照');
  console.log('-'.repeat(70));
  pageErrors.length = 0;
  await page.goto(`${BASE}/#/merchant/product`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1500);
  const merchantInfo = await page.evaluate(() => {
    const main = document.querySelector('main.content');
    if (!main) return { visible: 0, text: '' };
    let visible = 0;
    for (const el of main.querySelectorAll('*')) {
      const r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) visible++;
    }
    return { visible, text: (main.innerText || '').trim().slice(0, 100) };
  });
  check('管理员访问商家页被引导回审核',
    page.url().includes('/merchant/audit'),
    `当前 URL=${page.url()}`);
  await page.screenshot({ path: path.join(OUT, '管理员访问商家页.png'), fullPage: true });

  await browser.close();

  console.log('\n' + '='.repeat(70));
  console.log(`结果：通过 ${passed.length} 项，失败 ${failed.length} 项`);
  if (failed.length) {
    console.log('\n失败明细：');
    failed.forEach(f => console.log('  - ' + f));
  }
  console.log('='.repeat(70));
  process.exit(failed.length ? 1 : 0);
})();
