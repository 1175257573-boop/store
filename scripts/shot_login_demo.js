// 登录页演示入口验证：截图 + 一键登录 + 权限验证
const { chromium } = require(process.env.PW || 'E:/WorkBuddy/Temp/uitest/node_modules/playwright');

(async () => {
  const b = await chromium.launch();
  const shots = 'E:/WorkBuddy/Temp/login-shots';
  require('fs').mkdirSync(shots, { recursive: true });
  const errs = [];

  // ---------- 桌面端 ----------
  const p = await b.newPage({ viewport: { width: 1440, height: 1000 } });
  p.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 90)); });
  p.on('pageerror', (e) => errs.push('PAGE: ' + e.message.slice(0, 90)));

  await p.goto('http://localhost:5173/#/login', { waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 2200));
  await p.screenshot({ path: `${shots}/01-登录页-桌面.png`, fullPage: true });

  const cards = await p.locator('.demo-card').count();
  console.log('演示卡片数:', cards);
  const names = await p.locator('.role-name').allTextContents();
  console.log('角色:', names.join(' | '));
  const creds = await p.locator('.demo-cred').allTextContents();
  console.log('凭据示例:', creds[0]?.replace(/\s+/g, ' '));

  // ---------- 一键登录商家 ----------
  console.log('\n--- 测试 1：一键登录商家（点第一张卡）---');
  await p.locator('.demo-card').first().click();
  await new Promise((r) => setTimeout(r, 3000));
  console.log('登录后 URL:', p.url());
  const nav = await p.locator('.nav').innerText().catch(() => '');
  console.log('导航显示:', nav.replace(/\s+/g, ' ').slice(0, 60));
  const hasMerchantEntry = nav.includes('商家中心');
  console.log('出现「商家中心」入口:', hasMerchantEntry);
  await p.screenshot({ path: `${shots}/02-商家登录后.png` });

  // 验证商家确实只能看到自己店铺
  if (hasMerchantEntry) {
    await p.goto('http://localhost:5173/#/merchant/dashboard', { waitUntil: 'networkidle' });
    await new Promise((r) => setTimeout(r, 2600));
    await p.screenshot({ path: `${shots}/03-商家工作台.png` });
    const t = await p.locator('body').innerText();
    console.log('工作台显示店铺:', t.includes('数码优选') ? '数码优选旗舰店' :
      (t.match(/[^\s]*优选[^\s]*/) || ['未识别'])[0]);
    console.log('是否泄露别店名（生活优选）:', t.includes('生活优选'));
  }

  // ---------- 填入而非直接登录 ----------
  console.log('\n--- 测试 2：「填入表单」不直接登录 ---');
  const p2 = await b.newPage({ viewport: { width: 1440, height: 1000 } });
  p2.on('pageerror', (e) => errs.push('PAGE2: ' + e.message.slice(0, 90)));
  await p2.goto('http://localhost:5173/#/login', { waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 2200));
  const card = p2.locator('.demo-card').nth(1);
  await card.locator('button').first().click();   // 「填入表单」
  await new Promise((r) => setTimeout(r, 900));
  const uname = await p2.locator('input').first().inputValue();
  console.log('填入的用户名:', uname, '| 仍在登录页:', p2.url().includes('login'));
  await p2.screenshot({ path: `${shots}/04-填入表单.png`, fullPage: true });

  // ---------- 移动端 ----------
  console.log('\n--- 测试 3：移动端布局 ---');
  const p3 = await b.newPage({ viewport: { width: 390, height: 844 } });
  p3.on('pageerror', (e) => errs.push('PAGE3: ' + e.message.slice(0, 90)));
  await p3.goto('http://localhost:5173/#/login', { waitUntil: 'networkidle' });
  await new Promise((r) => setTimeout(r, 2200));
  await p3.screenshot({ path: `${shots}/05-登录页-移动.png`, fullPage: true });
  // 检查是否单列（卡片宽度接近容器宽度）
  const cw = await p3.locator('.demo-card').first().evaluate((el) => el.getBoundingClientRect().width);
  const gw = await p3.locator('.demo-grid').evaluate((el) => el.getBoundingClientRect().width);
  console.log(`移动端卡片宽 ${cw.toFixed(0)} / 容器 ${gw.toFixed(0)} → ${cw / gw > 0.9 ? '单列 OK' : '仍多列'}`);

  console.log('\n控制台错误:', errs.length ? errs.slice(0, 3) : '无');
  await b.close();
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });