/* 联调走查脚本：驱动 Edge 完整跑一遍记账 App 主流程 */
const path = require('path');
const fs = require('fs');

const PW = require(path.join('C:/Users/28182/AppData/Roaming/npm/node_modules/playwright'));
const OUT = path.join(__dirname, 'shots');

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  let browser;
  try {
    browser = await PW.chromium.launch({ channel: 'msedge', headless: true });
  } catch (e) {
    console.log('msedge 不可用:', e.message.slice(0, 100));
    browser = await PW.chromium.launch({ channel: 'chrome', headless: true });
  }
  const page = await browser.newPage({ viewport: { width: 430, height: 900 } });
  const errors = [];
  page.on('pageerror', e => errors.push('PAGEERROR: ' + e.message));
  page.on('console', m => { if (m.type() === 'error') errors.push('CONSOLE: ' + m.text()); });

  let passCount = 0, failCount = 0;
  const step = async (name, fn) => {
    try { await fn(); passCount++; console.log('PASS ' + name); }
    catch (e) { failCount++; console.log('FAIL ' + name + ' :: ' + e.message.split('\n')[0]); }
  };

  await page.goto('http://127.0.0.1:8742', { waitUntil: 'networkidle' });
  await page.waitForTimeout(600);

  await step('明细页渲染（演示数据已载入）', async () => {
    const txt = await page.textContent('#home-expense');
    if (!/¥\s?[\d,]+\.\d\d/.test(txt)) throw new Error('月支出未渲染: ' + txt);
    const rows = await page.locator('.tx-row').count();
    if (rows < 3) throw new Error('账单行过少: ' + rows);
  });
  await page.screenshot({ path: OUT + '/1-home.png' });

  await step('月份切换（上月→本月）', async () => {
    await page.click('#month-prev');
    await page.waitForTimeout(150);
    const label = await page.textContent('#month-label');
    if (!/月/.test(label)) throw new Error('label 异常');
    await page.click('#month-next');
    await page.waitForTimeout(150);
  });

  await step('打开截屏识别面板', async () => {
    await page.click('#tabbar .tab[data-screen="scan"]');
    await page.waitForTimeout(400);
    if (!(await page.isVisible('#scan-step-pick'))) throw new Error('上传步骤未显示');
  });
  await page.screenshot({ path: OUT + '/2-scan-pick.png' });

  await step('上传截图 → 表单出现', async () => {
    // 生成一张最小 PNG 作为"付款截图"
    const png = Buffer.from(
      'iVBORw0KGgoAAAANSUhEUgAAAAgAAAAICAYAAADED76LAAAAF0lEQVR4nGP8z8Dwn4GBgYGJgYGBAQA+AQP/ASkX8gAAAABJRU5ErkJggg==', 'base64');
    fs.writeFileSync(OUT + '/fake-pay.png', png);
    await page.setInputFiles('#file-input', OUT + '/fake-pay.png');
    await page.waitForTimeout(600);
    if (await page.isVisible('#scan-step-pick')) throw new Error('未进入表单步骤');
    if (!(await page.isVisible('#shot-preview'))) throw new Error('预览图未显示');
  });
  await page.screenshot({ path: OUT + '/3-scan-form.png' });

  await step('填表保存 → 回明细页出现新账单', async () => {
    await page.fill('#f-amount', '38.5');
    await page.fill('#f-merchant', '瑞幸咖啡');
    await page.fill('#f-note', '联调测试 · 下午茶');
    await page.click('.chip[data-key="dining"]');
    await page.click('#scan-save');
    await page.waitForTimeout(500);
    const toast = await page.textContent('#toast');
    if (!toast.includes('38.50')) throw new Error('toast 异常: ' + toast);
    const first = await page.textContent('.tx-row .tx-name');
    if (!first.includes('瑞幸咖啡')) throw new Error('列表第一条不是新账单: ' + first);
  });
  await page.screenshot({ path: OUT + '/4-saved-home.png' });

  await step('待确认提醒条已消失', async () => {
    if (await page.isVisible('#pending-bar')) throw new Error('提醒条仍显示');
  });

  await step('点击账单 → 编辑金额 → 保存', async () => {
    await page.click('.tx-row');
    await page.waitForTimeout(400);
    await page.fill('#e-amount', '45.00');
    await page.click('#edit-save');
    await page.waitForTimeout(400);
    const amt = await page.textContent('.tx-row .tx-amount');
    if (!amt.includes('45.00')) throw new Error('金额未更新: ' + amt);
  });

  await step('编辑面板删除账单', async () => {
    await page.click('.tx-row');
    await page.waitForTimeout(400);
    page.once('dialog', d => d.accept());
    await page.click('#edit-delete');
    await page.waitForTimeout(400);
    const first = await page.textContent('.tx-row .tx-name');
    if (first.includes('瑞幸咖啡')) throw new Error('账单未删除');
  });

  await step('统计页（月视图）渲染', async () => {
    await page.click('#tabbar .tab[data-screen="stats"]');
    await page.waitForTimeout(400);
    const total = await page.textContent('#stat-total');
    if (!/¥\s?[\d,]+\.\d\d/.test(total)) throw new Error('总支出未渲染: ' + total);
    const bars = await page.locator('.bar-col').count();
    if (bars !== 5) throw new Error('趋势柱数: ' + bars);
    const cats = await page.locator('.cat-row').count();
    if (cats < 1) throw new Error('分类占比为空');
    if (!(await page.isVisible('#insight-card'))) throw new Error('洞察卡未显示');
  });
  await page.screenshot({ path: OUT + '/5-stats-month.png' });

  await step('统计页切周视图', async () => {
    await page.click('.seg-btn[data-range="week"]');
    await page.waitForTimeout(300);
    const bars = await page.locator('.bar-col').count();
    if (bars !== 7) throw new Error('周柱数: ' + bars);
  });
  await page.screenshot({ path: OUT + '/6-stats-week.png' });

  await step('我的页统计与清空确认', async () => {
    await page.click('#tabbar .tab[data-screen="profile"]');
    await page.waitForTimeout(300);
    const count = await page.textContent('#profile-count');
    if (Number(count) <= 0) throw new Error('笔数为 0');
  });
  await page.screenshot({ path: OUT + '/7-profile.png' });

  await step('数据持久化（reload 后仍在）', async () => {
    const before = await page.textContent('#profile-count');
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForTimeout(600);
    await page.click('#tabbar .tab[data-screen="profile"]');
    await page.waitForTimeout(300);
    const after = await page.textContent('#profile-count');
    if (before !== after) throw new Error(`重载前后笔数不一致 ${before} -> ${after}`);
  });

  console.log('\n---- JS 错误 ----');
  console.log(errors.length ? errors.join('\n') : '(无)');
  console.log('\n========================================');
  console.log(`E2E: PASS ${passCount} | FAIL ${failCount} | JS错误 ${errors.length}`);
  await browser.close();
  process.exit(failCount > 0 || errors.length > 0 ? 1 : 0);
})().catch(e => { console.error('FATAL', e); process.exit(1); });
