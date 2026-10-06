/**
 * 侧边栏红点位置验证
 *
 * 位置类问题必须看实际渲染的几何数据，不能靠肉眼看截图（不同缩放下肉眼会骗人）。
 * 本脚本用 getBoundingClientRect 拿到红点与菜单项的真实坐标，
 * 断言红点确实在菜单项的右上角区域。
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

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });

  console.log('='.repeat(68));
  console.log('侧边栏红点位置验证');
  console.log('='.repeat(68));

  // 登录并注入 token
  const lr = await page.request.post(`${API}/user/login`, {
    data: { username: 'admin', password: '123456' }
  });
  const login = (await lr.json()).data;
  await page.goto(`${BASE}/`, { waitUntil: 'domcontentloaded' });
  await page.evaluate(({ t }) => {
    localStorage.setItem('token', t);
    localStorage.setItem('user', JSON.stringify({
      userId: 1, username: 'admin', nickname: '系统管理员', role: 2
    }));
  }, { t: login.token });

  await page.goto(`${BASE}/#/merchant/audit`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(1800);

  // 测量每个菜单项与其红点的几何关系
  // 注意：绝对定位挂在外层 .el-badge 上，内部 .el-badge__content 是 static，
  // 所以要测 .el-badge 的位置，不是 content
  const measures = await page.evaluate(() => {
    const out = [];
    for (const item of document.querySelectorAll('.el-menu-item')) {
      const label = item.textContent.replace(/\s+/g, ' ').trim();
      const badge = item.querySelector('.menu-badge');
      if (!badge) continue;
      const ir = item.getBoundingClientRect();
      const br = badge.getBoundingClientRect();
      out.push({
        label,
        item: { x: ir.x, y: ir.y, w: ir.width, h: ir.height },
        badge: { x: br.x, y: br.y, w: br.width, h: br.height },
        // 红点中心相对菜单项中心的偏移
        offsetX: (br.x + br.width / 2) - (ir.x + ir.width / 2),
        offsetY: (br.y + br.height / 2) - (ir.y + ir.height / 2),
        // 红点顶部相对菜单项顶部的距离
        topGap: br.y - ir.y,
        rightGap: ir.x + ir.width - (br.x + br.width)
      });
    }
    return out;
  });

  console.log('\n【红点几何位置】');
  console.log('-'.repeat(68));
  if (!measures.length) {
    console.log('  未找到任何红点（可能没有待审数据）');
  }
  for (const m of measures) {
    console.log(`\n  ${m.label}`);
    console.log(`    菜单项: y=${m.item.y.toFixed(0)} h=${m.item.h.toFixed(0)} w=${m.item.w.toFixed(0)}`);
    console.log(`    红点  : y=${m.badge.y.toFixed(0)} h=${m.badge.h.toFixed(0)} w=${m.badge.w.toFixed(0)}`);
    console.log(`    垂直偏移(中心): ${m.offsetY.toFixed(1)}px  <- 负=偏上，正=偏下`);
    console.log(`    水平偏移(中心): ${m.offsetX.toFixed(1)}px  <- 负=偏左，正=偏右`);
    console.log(`    距菜单项顶部: ${m.topGap.toFixed(1)}px`);
    console.log(`    距菜单项右侧: ${m.rightGap.toFixed(1)}px`);

    // 断言：红点在菜单项上半部分（topGap < 高度的一半）
    check(`${m.label} 红点位于菜单项上半部`, m.topGap < m.item.h / 2,
      `topGap=${m.topGap.toFixed(1)} > h/2=${(m.item.h / 2).toFixed(1)}`);
    // 断言：红点不越出菜单项右边界
    check(`${m.label} 红点未越出右侧边界`, m.rightGap >= -1,
      `rightGap=${m.rightGap.toFixed(1)}`);
    // 断言：红点与菜单项有合理间距（不贴在角上也不飘太远）
    check(`${m.label} 红点位置合理(距顶 5-12px)`,
      m.topGap >= 4 && m.topGap <= 12, `topGap=${m.topGap.toFixed(1)}`);
    // 容器不该继承菜单项高度（56px），否则数字被挤到中下部
    check(`${m.label} 红点容器高度紧凑`,
      m.badge.h <= 20, `badge高=${m.badge.h.toFixed(0)}px（应≤20）`);
  }

  // 截图：侧边栏局部
  const sidebar = await page.$('.sidebar');
  if (sidebar) {
    await sidebar.screenshot({ path: path.join(OUT, '侧边栏红点.png') });
    console.log(`\n  侧边栏截图: ${path.join(OUT, '侧边栏红点.png')}`);
  }
  await page.screenshot({ path: path.join(OUT, '红点-整页.png'), fullPage: true });

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
