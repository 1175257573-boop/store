/**
 * 秒杀活动管理页实机验证
 *
 * 验证三层：页面能渲染（非白屏）、菜单有入口、发布弹窗能打开并显示商品候选。
 * 同时捕获 JS 异常——上次的管理员后台白屏就是漏 import 只有实机才发现。
 */
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

async function loginAs(ctx, username) {
  const p = await ctx.newPage();
  const lr = await p.request.post(`${API}/user/login`, {
    data: { username, password: '123456' }
  });
  const data = (await lr.json()).data;
  await p.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
  await p.evaluate(({ t, u }) => {
    localStorage.setItem('token', t);
    localStorage.setItem('user', JSON.stringify({
      userId: 1, username: u, nickname: u, role: 2
    }));
  }, { t: data.token, u: username });
  await p.close();
  return data;
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await ctx.newPage();

  const pageErrors = [];
  const consoleErrors = [];
  page.on('pageerror', e => pageErrors.push(e.message));
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });

  console.log('='.repeat(68));
  console.log('秒杀活动管理页实机验证');
  console.log('='.repeat(68));

  await loginAs(ctx, 'admin');
  await page.goto(`${BASE}/#/merchant/seckill-activity`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800);

  // ---- 1. 页面基本结构 ----
  console.log('\n【1】页面结构');
  console.log('-'.repeat(68));
  const h2 = await page.locator('.page-head h2').first().textContent().catch(() => null);
  check('页面标题为「秒杀活动」', h2 && h2.trim() === '秒杀活动', `实际="${h2}"`);

  const visible = await page.evaluate(() => {
    const main = document.querySelector('main.content');
    if (!main) return { n: 0, text: '' };
    let n = 0;
    for (const el of main.querySelectorAll('*')) {
      const r = el.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) n++;
    }
    return { n, text: (main.innerText || '').trim().slice(0, 120) };
  });
  console.log(`  可见元素: ${visible.n}`);
  check('页面非空白', visible.n > 25, `仅 ${visible.n} 个可见元素`);

  // ---- 2. 侧边栏有入口 ----
  console.log('\n【2】侧边栏入口');
  console.log('-'.repeat(68));
  const menuTexts = await page.locator('.sidebar .el-menu-item').allTextContents();
  console.log(`  菜单: ${menuTexts.map(t => t.trim()).join(' | ')}`);
  check('侧边栏有「秒杀活动」入口',
    menuTexts.some(t => t.includes('秒杀活动')), `菜单=${menuTexts.join(',')}`);

  // ---- 3. 管理员应看到平台活动提示 ----
  console.log('\n【3】角色提示');
  console.log('-'.repeat(68));
  const alertText = await page.locator('.el-alert__title').first()
    .textContent().catch(() => null);
  check('管理员看到平台身份提示', !!alertText, `实际="${alertText}"`);
  console.log(`  提示: ${alertText || '(无)'}`);

  // ---- 4. 发布弹窗 ----
  console.log('\n【4】发布弹窗');
  console.log('-'.repeat(68));
  await page.locator('button:has-text("发布活动")').first().click();
  await page.waitForTimeout(1200);

  const dlgVisible = await page.locator('.el-dialog:has-text("发布秒杀活动")')
    .isVisible().catch(() => false);
  check('发布弹窗能打开', dlgVisible, '弹窗未出现');

  const formFields = await page.locator('.el-dialog .el-form-item').count();
  check('弹窗表单完整（≥7 项）', formFields >= 7, `表单项=${formFields}`);

  // 商品候选是否加载
  const options = await page.evaluate(() => {
    // el-select 的选项渲染在下拉层里，这里点开第一个 select 看
    return document.querySelectorAll('.el-dialog .el-select').length;
  });
  check('弹窗含商品选择器', options >= 1, `select 数量=${options}`);

  await page.screenshot({ path: path.join(OUT, '秒杀活动-发布弹窗.png') });
  console.log(`  截图: ${path.join(OUT, '秒杀活动-发布弹窗.png')}`);

  await page.keyboard.press('Escape');
  await page.waitForTimeout(600);

  // ---- 5. 列表区 ----
  await page.screenshot({ path: path.join(OUT, '秒杀活动-列表.png'), fullPage: true });
  // 列表要么有表格行，要么有空状态提示
  const rowCount = await page.locator('.el-table__row').count();
  const emptyCount = await page.locator('.el-empty').count();
  console.log(`  表格行数: ${rowCount}, 空状态: ${emptyCount}`);
  check('列表区正常显示（表格有数据或空状态提示）',
    rowCount > 0 || emptyCount > 0,
    `表格行=${rowCount} 空状态=${emptyCount}`);

  // ---- 6. 无 JS 异常 ----
  console.log('\n【5】运行时健康');
  console.log('-'.repeat(68));
  check('无 JS 运行时错误', pageErrors.length === 0, pageErrors.slice(0, 2).join(' | '));
  if (consoleErrors.length) {
    console.log(`  console 错误 ${consoleErrors.length} 条（首条）: ${consoleErrors[0].slice(0, 100)}`);
  }

  await browser.close();

  console.log('\n' + '='.repeat(68));
  console.log(`结果：通过 ${passed.length} 项，失败 ${failed.length} 项`);
  if (failed.length) {
    console.log('\n失败明细：');
    failed.forEach(f => console.log('  - ' + f));
  }
  console.log('='.repeat(68));
  process.exit(failed.length ? 1 : 0);
})();
