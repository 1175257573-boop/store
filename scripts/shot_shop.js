// 店铺主页端到端验证：首页点店铺标签 → 店铺主页 → 分类筛选 → 联系商家
const { chromium } = require(process.env.PW || 'E:/WorkBuddy/Temp/uitest/node_modules/playwright');

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1440, height: 950 } });
  const shots = 'E:/WorkBuddy/Temp/shop-shots';
  require('fs').mkdirSync(shots, { recursive: true });
  const errs = [];
  p.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 100)); });
  p.on('pageerror', (e) => errs.push('PAGE: ' + e.message.slice(0, 100)));

  // 登录（注入 token 后 reload 让 store 初始化）
  await p.goto('http://localhost:5173/#/', { waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 1500));
  await p.evaluate(async () => {
    const r = await fetch('/api/user/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'im_buyer', password: 'buyer123' })
    });
    const j = await r.json();
    if (j.code === 200) {
      localStorage.setItem('token', j.data.token);
      if (j.data.user) localStorage.setItem('user', JSON.stringify(j.data.user));
    }
  });
  await p.reload({ waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 2800));

  // 首页应显示店铺标签
  const tagCount = await p.locator('.shop-tag').count();
  console.log('首页店铺标签数:', tagCount);
  await p.screenshot({ path: `${shots}/01-首页带店铺.png` });

  if (tagCount === 0) { console.log('无店铺标签，提前结束'); await b.close(); return; }

  // 点第一个店铺标签进店铺主页
  const tagText = (await p.locator('.shop-tag').first().innerText()).trim();
  console.log('点击店铺:', tagText);
  await p.locator('.shop-tag').first().click();
  await new Promise((r) => setTimeout(r, 2600));
  console.log('店铺页 URL:', p.url());
  await p.screenshot({ path: `${shots}/02-店铺主页.png` });

  const info = await p.locator('.shop-meta').innerText().catch(() => '');
  console.log('店铺信息:', info.replace(/\s+/g, ' ').slice(0, 70));
  const cards = await p.locator('.product-card').count();
  console.log('店内商品数:', cards);

  // 分类筛选
  const catBtns = await p.locator('.cats .el-radio-button').count();
  console.log('分类按钮数:', catBtns);
  if (catBtns > 2) {
    await p.locator('.cats .el-radio-button').nth(2).click();
    await new Promise((r) => setTimeout(r, 2200));
    await p.screenshot({ path: `${shots}/03-分类筛选.png` });
    const n = await p.locator('.product-card').count();
    console.log('筛选后商品数:', n);
  }

  // 联系商家
  await p.locator('.shop-actions button').click();
  await new Promise((r) => setTimeout(r, 2600));
  await p.screenshot({ path: `${shots}/04-联系商家.png` });
  const hasInput = await p.locator('.input-bar textarea').count();
  console.log('聊天面板已打开:', hasInput > 0);

  console.log('控制台错误:', errs.length ? errs.slice(0, 2) : '无');
  await b.close();
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });