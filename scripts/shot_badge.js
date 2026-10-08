// 红点验证：造未读数据 → 截图 → 进入页面 → 验证清除
const { chromium } = require(process.env.PW || 'E:/WorkBuddy/Temp/uitest/node_modules/playwright');

async function login(page, username, password) {
  await page.goto('http://localhost:5173/#/', { waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 1200));
  await page.evaluate(async ([u, p]) => {
    const r = await fetch('/api/user/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: u, password: p })
    });
    const j = await r.json();
    if (j.code === 200) {
      localStorage.setItem('token', j.data.token);
      if (j.data.user) localStorage.setItem('user', JSON.stringify(j.data.user));
    }
    return j.code;
  }, [username, password]);
  await page.reload({ waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 2800));
}

(async () => {
  const b = await chromium.launch();
  const shots = 'E:/WorkBuddy/Temp/badge-shots';
  require('fs').mkdirSync(shots, { recursive: true });
  const errs = [];
  const out = [];

  // ---------- 买家视角 ----------
  const p = await b.newPage({ viewport: { width: 1440, height: 950 } });
  p.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 90)); });
  p.on('pageerror', (e) => errs.push('PAGE: ' + e.message.slice(0, 90)));

  await login(p, 'im_buyer', 'buyer123');
  out.push('买家已登录');

  // 读初始红点
  const read = async (page) => {
    return page.evaluate(() => {
      const r = {}
      document.querySelectorAll('.nav-item.has-badge').forEach((el) => {
        const label = el.textContent.replace(/\d+/g, '').trim()
        const badge = el.querySelector('.nav-badge')
        if (badge) {
          r[label] = badge.classList.contains('is-num') ? badge.textContent.trim() : '●'
        }
      })
      return r
    });
  };

  let badges = await read(p);
  out.push('初始红点: ' + JSON.stringify(badges));
  await p.screenshot({ path: `${shots}/01-买家-红点.png`, clip: { x: 0, y: 0, width: 1440, height: 130 } });

  // 进入消息页 → 应清除
  if (Object.keys(badges).length) {
    await p.goto('http://localhost:5173/#/messages', { waitUntil: 'networkidle' });
    await new Promise((r) => setTimeout(r, 3000));
    await p.reload({ waitUntil: 'networkidle' });
    await new Promise((r) => setTimeout(r, 3000));
    badges = await read(p);
    out.push('进入消息页后: ' + JSON.stringify(badges));
    await p.screenshot({ path: `${shots}/02-进入消息后.png`, clip: { x: 0, y: 0, width: 1440, height: 130 } });
  }

  // ---------- 商家视角 ----------
  const p2 = await b.newPage({ viewport: { width: 1440, height: 950 } });
  p2.on('pageerror', (e) => errs.push('P2: ' + e.message.slice(0, 90)));
  await login(p2, 'digital_shop', 'shop123456');
  const b2 = await read(p2);
  out.push('商家红点: ' + JSON.stringify(b2));
  await p2.screenshot({ path: `${shots}/03-商家-红点.png`, clip: { x: 0, y: 0, width: 1440, height: 130 } });

  // ---------- 移动端 ----------
  const p3 = await b.newPage({ viewport: { width: 390, height: 844 } });
  p3.on('pageerror', (e) => errs.push('P3: ' + e.message.slice(0, 90)));
  await login(p3, 'im_buyer', 'buyer123');
  await p3.screenshot({ path: `${shots}/04-移动端红点.png`, clip: { x: 0, y: 0, width: 390, height: 130 } });
  out.push('移动端截图完成');

  console.log(out.join('\n'));
  console.log('控制台错误:', errs.length ? errs.slice(0, 3) : '无');
  await b.close();
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });