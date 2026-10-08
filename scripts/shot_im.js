// IM 前端端到端验证：登录 → 商品详情 → 联系商家 → 发消息
//
// 两个踩过的坑（写在这里避免下次重踩）：
// 1. 路由守卫「已登录访问 /login 会跳首页」，
//    所以不能停留在 /login 注入 token 后直接 goto，
//    要先注入再 goto 首页。
// 2. 注入 localStorage 后必须 reload 让 Pinia store 重新初始化，
//    否则 store 里的 token ref 还是空，isLogin 为 false。
const { chromium } = require(process.env.PW || 'E:/WorkBuddy/Temp/uitest/node_modules/playwright');

(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1440, height: 950 } });
  const shots = 'E:/WorkBuddy/Temp/im-shots';
  require('fs').mkdirSync(shots, { recursive: true });
  const errs = [];
  p.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 100)); });

  // 1) 先访问首页（避免守卫把 /login 弹走）
  await p.goto('http://localhost:5173/#/', { waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 1500));

  // 2) 注入 token
  const code = await p.evaluate(async () => {
    const r = await fetch('/api/user/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'im_buyer', password: 'buyer123' })
    });
    const j = await r.json();
    if (j.code === 200) {
      localStorage.setItem('token', j.data.token);
      if (j.data.user) localStorage.setItem('user', JSON.stringify(j.data.user));
    }
    return j.code;
  });
  console.log('登录:', code);

  // 3) reload 让 store 初始化（关键）
  await p.reload({ waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 2800));
  const nav = await p.locator('.nav').innerText();
  console.log('已登录态:', !nav.includes('登录'));

  // 4) 商品详情
  await p.locator('.product-card').first().click();
  await new Promise((r) => setTimeout(r, 2600));
  const pname = (await p.locator('.name').first().innerText()).trim();
  console.log('商品:', pname.slice(0, 26));
  await p.screenshot({ path: `${shots}/02-详情页.png` });

  // 5) 点「联系商家」
  await p.locator('button').filter({ hasText: '联系商家' }).click();
  await new Promise((r) => setTimeout(r, 3000));
  const hasInput = await p.locator('.input-bar textarea').count();
  console.log('聊天面板已打开:', hasInput > 0);
  await p.screenshot({ path: `${shots}/03-聊天打开.png` });

  // 6) 发消息
  if (hasInput > 0) {
    await p.locator('.input-bar textarea').fill('请问这款有货吗？');
    await p.locator('.input-bar button').click();
    await new Promise((r) => setTimeout(r, 2400));
    await p.screenshot({ path: `${shots}/04-发送消息.png` });
    const t = await p.locator('.msg-list').innerText();
    console.log('消息已发出:', t.includes('请问这款有货吗'));
  }

  // 7) 消息中心页
  await p.goto('http://localhost:5173/#/messages', { waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 2600));
  await p.screenshot({ path: `${shots}/05-消息中心.png`, fullPage: false });
  const body = await p.locator('body').innerText();
  console.log('消息中心页有会话:', body.includes('咨询') || body.includes('这家') || body.includes('优选'));

  console.log('控制台错误:', errs.length ? errs.slice(0, 2) : '无');
  await b.close();
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });