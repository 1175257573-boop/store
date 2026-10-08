// 角标优化验证：遮挡检测 + 各入口覆盖 + 响应式
const { chromium } = require(process.env.PW || 'E:/WorkBuddy/Temp/uitest/node_modules/playwright');

const readBadges = (page) => page.evaluate(() => {
  const r = {}
  document.querySelectorAll('.nav-item.has-badge').forEach((el) => {
    const badge = el.querySelector('.nav-badge')
    if (!badge) return
    const label = el.textContent.replace(/\d+/g, '').replace('●', '').trim()
    r[label] = {
      isNum: badge.classList.contains('is-num'),
      isDot: badge.classList.contains('is-dot'),
      isCount: badge.classList.contains('is-count'),
      text: badge.textContent.trim()
    }
  })
  return r
});

/**
 * 检测角标是否压住「导航文字」。
 *
 * ⚠️ 前两版都误报了，原因值得记下来：
 * ① 第一版用 TreeWalker 遍历了所有 text node，把 .icon-wrap 内部也算进去
 * ② 第二版改成直接子节点，但用了「矩形相交」判定 —— 而角标本来就该
 *    骑在图标右上角，与图标矩形必然相交；文字在 gap 另一侧，根本不相交。
 *
 * 正确判定：**角标的右边缘 vs 文字的左边缘**。
 * 角标 right > 文字 left 才是真压住（角标伸进了文字区）。
 */
const checkOverlap = (page) => page.evaluate(() => {
  const bad = []
  document.querySelectorAll('.nav-item.has-badge').forEach((el) => {
    const badge = el.querySelector('.nav-badge')
    if (!badge) return
    const b = badge.getBoundingClientRect()
    let hit = false
    let textRect = null
    for (const node of el.childNodes) {
      if (node.nodeType !== Node.TEXT_NODE) continue
      if (!node.textContent.trim()) continue
      const range = document.createRange()
      range.selectNodeContents(node)
      textRect = range.getBoundingClientRect()
      break
    }
    if (textRect && b.right > textRect.left) hit = true
    bad.push({
      item: el.textContent.trim().slice(0, 12),
      badgeRight: Math.round(b.right),
      textLeft: textRect ? Math.round(textRect.left) : null,
      gap: textRect ? Math.round(textRect.left - b.right) : null,
      overlap: hit
    })
  })
  return bad
})

async function login(page, u, p) {
  await page.goto('http://localhost:5173/#/', { waitUntil: 'networkidle' })
  await new Promise((r) => setTimeout(r, 1200))
  await page.evaluate(async ([uu, pp]) => {
    const r = await fetch('/api/user/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: uu, password: pp })
    })
    const j = await r.json()
    if (j.code === 200) {
      localStorage.setItem('token', j.data.token)
      if (j.data.user) localStorage.setItem('user', JSON.stringify(j.data.user))
    }
  }, [u, p])
  await page.reload({ waitUntil: 'networkidle' })
  await new Promise((r) => setTimeout(r, 3000))
}

(async () => {
  const b = await chromium.launch();
  const shots = 'E:/WorkBuddy/Temp/badge2-shots';
  require('fs').mkdirSync(shots, { recursive: true });
  const errs = [];
  const out = [];

  // ---- 桌面端：造未读 ----
  const p = await b.newPage({ viewport: { width: 1440, height: 950 } });
  p.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 90)); });
  p.on('pageerror', (e) => errs.push('PAGE: ' + e.message.slice(0, 90)));
  p.on('response', async (r) => {
    if (r.url().includes('/seckill/activity/current')) {
      try { const j = await r.json(); out.push('秒杀接口: ' + JSON.stringify(j).slice(0, 90)) } catch {}
    }
  });
  await login(p, 'im_buyer', 'buyer123');

  const badges = await readBadges(p);
  out.push('角标清单: ' + JSON.stringify(badges));
  out.push('秒杀有角标: ' + ('秒杀' in badges));
  out.push('购物车用 count 变体: ' + (badges['购物车']?.isCount ?? '无角标'));

  const overlap = await checkOverlap(p);
  out.push('\n遮挡检测:');
  overlap.forEach((o) => out.push(`  ${o.overlap ? '✗ 压住' : '✓ 无遮挡'}  ${o.item}  角标右缘=${o.badgeRight} 文字左缘=${o.textLeft} 间隔=${o.gap}px`));

  await p.screenshot({ path: `${shots}/01-桌面-各入口.png`, clip: { x: 0, y: 0, width: 1440, height: 130 } });

  // ---- 移动端 ----
  const p3 = await b.newPage({ viewport: { width: 390, height: 844 } });
  p3.on('pageerror', (e) => errs.push('P3: ' + e.message.slice(0, 90)));
  await login(p3, 'im_buyer', 'buyer123');
  const mBadges = await readBadges(p3);
  const mOverlap = await checkOverlap(p3);
  out.push('\n移动端:');
  mOverlap.forEach((o) => out.push(`  ${o.overlap ? '✗ 压住' : '✓ 无遮挡'}  ${o.item}  间隔=${o.gap}px`));
  await p3.screenshot({ path: `${shots}/02-移动端.png`, clip: { x: 0, y: 0, width: 390, height: 190 } });

  console.log(out.join('\n'));
  console.log('\n控制台错误:', errs.length ? errs.slice(0, 3) : '无');
  await b.close();
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });