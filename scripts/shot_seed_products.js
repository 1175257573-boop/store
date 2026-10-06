// 截图验证：首页搜索 + 商品详情（验证 SKU 展示）
// 路由以实际 router 为准：/（搜索是首页内联）、/product/:id
const { chromium } = require(process.env.PLAYWRIGHT_PATH || 'playwright');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 } });
  const shots = 'E:/WorkBuddy/Temp/seed-shots';
  require('fs').mkdirSync(shots, { recursive: true });
  const wait = (ms) => new Promise((r) => setTimeout(r, ms));

  // 1) 首页 + 用搜索框搜「笔记本」（走真实交互路径）
  await page.goto('http://localhost:5173/#/', { waitUntil: 'networkidle' });
  await wait(2200);
  await page.screenshot({ path: `${shots}/01-首页.png` });

  const box = page.locator('input[type="text"], input[placeholder*="搜索"]').first();
  await box.click();
  await box.fill('笔记本');
  await box.press('Enter');
  await wait(2200);
  await page.screenshot({ path: `${shots}/02-搜索笔记本.png` });
  const txt = await page.locator('body').innerText();
  const hit = (txt.match(/共\s*(\d+)\s*件商品/) || [])[1];
  console.log(`搜索"笔记本"命中: ${hit} 件`);

  // 2) 点第一个商品进详情，验证 SKU 展示
  const card = page.locator('.product-card, .goods-card, [class*="card"]').first();
  await card.click();
  await wait(2600);
  await page.screenshot({ path: `${shots}/03-商品详情.png` });
  console.log('详情页 URL:', page.url());

  const body = await page.locator('body').innerText();
  console.log('页面含"规格"字样:', body.includes('规格'));
  const priceLines = body.split('\n').filter((l) => /¥|￥/.test(l)).slice(0, 6);
  console.log('价格相关行:', priceLines.map((s) => s.trim()).slice(0, 4));

  // 3) 分类页（用首页分类入口的真实 id：1=手机通讯）
  await page.goto('http://localhost:5173/#/?categoryId=2', { waitUntil: 'networkidle' });
  await wait(2000);
  await page.screenshot({ path: `${shots}/04-电脑办公分类.png` });

  await browser.close();
  console.log('DONE');
})().catch((e) => { console.error('ERR', e.message); process.exit(1); });
