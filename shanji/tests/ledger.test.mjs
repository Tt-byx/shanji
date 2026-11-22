/* ============================================================
 * 闪记 · 数据层/业务层 单元测试
 * 运行：node tests/ledger.test.mjs  （在 shanji/ 目录下）
 * ============================================================ */
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const Store = require('../js/store.js');
const Ledger = require('../js/ledger.js');

let passed = 0, failed = 0;
function ok(cond, name) {
  if (cond) { passed++; console.log('  PASS ' + name); }
  else { failed++; console.error('  FAIL ' + name); }
}
function eq(a, b, name) {
  const ja = JSON.stringify(a), jb = JSON.stringify(b);
  ok(ja === jb, name + (ja !== jb ? `  (got ${ja}, want ${jb})` : ''));
}
function section(title) { console.log('\n== ' + title + ' =='); }

/* ---------- 内存隔离：测试直接操作导出的原始数组 ---------- */
// Store 走真实 localStorage 的地方在浏览器；Node 下 rawGet/rawSet 抛错会降级到 memoryStore，
// 因此 Node 环境天然就是内存模式，测试之间用 clear() 隔离即可。

/* ========== 数据模型与校验 ========== */
section('数据模型 createTx');
{
  const tx = Store.createTx({ type: 'expense', amount: '19.905', merchant: ' 瑞幸咖啡 ', category: 'dining', note: ' x ', ts: 1700000000123.7 });
  eq(tx.amount, 19.91, '金额四舍五入到分');
  eq(tx.merchant, '瑞幸咖啡', '商户去首尾空格');
  eq(tx.note, 'x', '备注去空格');
  eq(tx.ts, 1700000000124, '时间戳取整');
  ok(tx.id.startsWith('tx_'), '自动生成 id');

  let threw = 0;
  try { Store.createTx({ amount: 0, merchant: 'x' }); } catch (e) { threw++; }
  try { Store.createTx({ amount: -5, merchant: 'x' }); } catch (e) { threw++; }
  try { Store.createTx({ amount: 10, merchant: '' }); } catch (e) { threw++; }
  try { Store.createTx({ amount: NaN, merchant: 'x' }); } catch (e) { threw++; }
  eq(threw, 4, '非法输入全部抛错（0/负数/空商户/NaN）');

  const fallback = Store.createTx({ amount: 10, merchant: 'x', category: 'not_exist' });
  eq(fallback.category, 'other', '未知分类回退到 other');
  ok(Store.getCategory('dining').label === '餐饮' && Store.getCategory('whatever').label === '其他', '分类查找含兜底');
}

/* ========== CRUD ========== */
section('增删改查');
{
  Store.clear();
  const a = Store.add({ type: 'expense', amount: 19.9, merchant: '瑞幸咖啡', category: 'dining', note: '下午提神', ts: new Date('2026-10-07T14:32:00').getTime() });
  const b = Store.add({ type: 'income', amount: 2500, merchant: '生活费', category: 'other', ts: new Date('2026-10-01T10:00:00').getTime() });
  const c = Store.add({ type: 'expense', amount: 4, merchant: '地铁', category: 'transport', ts: new Date('2026-09-30T08:00:00').getTime() });
  eq(Store.getAll().length, 3, '新增 3 笔');
  ok(Store.getAll()[0].id === a.id, '按时间倒序排列（最新的在前）');

  const u = Store.update(a.id, { amount: 25, note: '改过备注' });
  eq(u.amount, 25, '更新金额生效');
  ok(Store.get(a.id).note === '改过备注', '更新备注生效');
  eq(Store.update('tx_not_exist', { amount: 1 }), null, '更新不存在的 id 返回 null');

  ok(Store.remove(c.id) === true, '删除存在的笔');
  ok(Store.remove(c.id) === false, '重复删除返回 false');
  eq(Store.getAll().length, 2, '删除后剩 2 笔');

  Store.add({ type: 'expense', amount: 1, merchant: 'x' }); // 一笔脏数据源（ts 缺省自动 now）
  ok(Store.get(Store.getAll()[0].id) !== null, '缺省 ts 自动补 now');

  Store.clear();
  eq(Store.getAll().length, 0, 'clear 清空');

  // 导入导出
  Store.importAll(JSON.stringify([
    { type: 'expense', amount: 10, merchant: 'a', category: 'dining', ts: 1 },
    { type: 'expense', amount: -3, merchant: '脏数据', category: 'dining', ts: 2 }, // 会被过滤
    { merchant: '缺 type', amount: 5, ts: 3 } // type 缺省 -> expense，合法保留
  ]));
  eq(Store.getAll().length, 2, '导入过滤脏数据、保留合法缺省项');
}

/* ========== 月度统计 ========== */
section('月汇总 / 环比 / 日分组');
{
  Store.clear();
  const T = (y, mo, d, type, amount, cat) => Store.add({
    type, amount, merchant: 'm', category: cat || 'dining',
    ts: new Date(y, mo - 1, d, 12).getTime()
  });
  T(2026, 8, 5, 'expense', 100);
  T(2026, 9, 10, 'expense', 200);
  T(2026, 9, 20, 'expense', 100);
  T(2026, 9, 21, 'income', 500);
  T(2026, 10, 1, 'expense', 50);
  T(2026, 10, 2, 'expense', 19.9);
  T(2026, 10, 2, 'expense', 30.1);

  eq(Ledger.monthSummary(Store.getAll(), 2026, 9), { expense: 300, income: 500, balance: 200 }, '9月汇总');
  const mom = Ledger.monthOverMonth(Store.getAll(), 2026, 10);
  eq([mom.cur, mom.prev, mom.diff], [100, 300, -200], '10月 vs 9月 环比');

  const groups = Ledger.dailyGroups(Store.getAll(), 2026, 10);
  eq(groups.length, 2, '10月按天分组 = 2 天');
  eq(groups[0].items.length, 2, '2 日有 2 笔（倒序在前）');
  eq(groups[0].dayExpense, 50, '2 日当天支出合计 50');

  // 跨年边界
  T(2025, 12, 31, 'expense', 88);
  T(2026, 1, 1, 'expense', 66);
  eq(Ledger.monthSummary(Store.getAll(), 2025, 12).expense, 88, '跨年：2025-12');
  eq(Ledger.monthSummary(Store.getAll(), 2026, 1).expense, 66, '跨年：2026-01');
  const trend = Ledger.monthlyTrend(Store.getAll(), 2026, 1, 5);
  eq(trend.length, 5, '趋势取 5 个月');
  eq(trend[4].expense, 66, '趋势最后一根 = 当前月');
  ok(trend[0].label.includes('2025年'), '跨年趋势 label 带年份');
}

/* ========== 分类占比 ========== */
section('分类占比');
{
  Store.clear();
  const T = (d, amount, cat) => Store.add({
    type: 'expense', amount, merchant: 'm', category: cat,
    ts: new Date(2026, 9, d, 12).getTime()
  });
  T(1, 45, 'dining');   // 45/100 = 45%
  T(2, 24, 'shopping'); // 24%
  T(3, 18, 'transport');// 18%
  T(4, 13, 'entertainment'); // 13%
  const cats = Ledger.categoryBreakdown(Store.getAll(), 2026, 10);
  eq(cats.length, 4, '4 个分类');
  eq(cats.map(c => c.key), ['dining', 'shopping', 'transport', 'entertainment'], '按金额降序');
  eq(cats[0].pct, 45, '餐饮 45%');
  eq(cats[0].color, '#5046E5', '餐饮用主紫色');
  eq(Ledger.categoryBreakdown(Store.getAll(), 2026, 11), [], '无数据月返回空数组');
  // 只记收入的月份，占比为空
  Store.add({ type: 'income', amount: 100, merchant: 'm', category: 'other', ts: new Date(2026, 10, 5).getTime() });
  eq(Ledger.categoryBreakdown(Store.getAll(), 2026, 11), [], '纯收入月份无支出占比');
}

/* ========== 周视图 ========== */
section('周汇总 / 周环比 / 每日柱');
{
  Store.clear();
  // 2026-10-05 是周一。造 10-05(周一)~10-11(周日) 一整周 + 上一周日两笔
  const T = (mo, d, amount) => Store.add({
    type: 'expense', amount, merchant: 'm', category: 'dining',
    ts: new Date(2026, mo - 1, d, 12).getTime()
  });
  T(10, 5, 10); T(10, 6, 20); T(10, 7, 30); T(10, 11, 40); // 本周 100
  T(9, 29, 70); T(10, 3, 30); // 上周二~本周一区间(上周一=9-28): 上周共 100
  ok(Ledger.weekIndex(new Date('2026-10-05T12:00:00').getTime()) === 1, '2026-10-05 是周一');
  ok(Ledger.weekIndex(new Date('2026-10-11T12:00:00').getTime()) === 7, '2026-10-11 是周日');

  const ws = Ledger.weekSummary(Store.getAll(), '2026-10-07');
  eq(ws.summary.expense, 100, '本周(含10-07)支出 100');
  const wow = Ledger.weekOverWeek(Store.getAll(), '2026-10-07');
  eq([wow.cur, wow.prev, wow.diff], [100, 100, 0], '周环比 0');

  const days = Ledger.weekDaily(Store.getAll(), '2026-10-07');
  eq(days.length, 7, '每日柱 7 根');
  eq(days[0].date, 5, '第一根是 10-05');
  eq(days[2].expense, 30, '周三(10-07) 支出 30');
  ok(days.every(d => typeof d.label === 'string'), '每根带 label');
}

/* ========== 消费洞察 ========== */
section('消费洞察');
{
  Store.clear();
  const now = new Date();
  const y = now.getFullYear(), m = now.getMonth() + 1;
  const T = (day, amount, merchant, note) => Store.add({
    type: 'expense', amount, merchant, note: note || '', category: 'dining',
    ts: new Date(y, m - 1, day, 12).getTime()
  });
  T(1, 30, '瑞幸咖啡'); T(2, 20, '瑞幸咖啡', '下午提神'); T(3, 18, '蜜雪冰城', '奶茶');
  T(4, 35, '美团外卖', '外卖'); T(5, 32, '美团外卖', '外卖'); T(6, 28, '饿了么', '外卖');
  T(7, 15, '二食堂');
  const ins = Ledger.insights(Store.getAll(), y, m);
  ok(ins.length >= 2, '命中多条规则（咖啡+外卖+最大分类）: ' + JSON.stringify(ins));
  ok(ins.some(s => s.includes('咖啡')), '咖啡规则命中');
  ok(ins.some(s => s.includes('外卖')), '外卖规则命中');

  Store.clear();
  eq(Ledger.insights(Store.getAll(), y, m), [], '空数据月无洞察');
}

/* ========== 演示数据种子 ========== */
section('演示数据');
{
  const n = Store.seedDemo();
  ok(n > 40, '演示数据 > 40 笔 (实际 ' + n + ')');
  const all = Store.getAll();
  ok(all.every(t => t.amount > 0 && t.merchant), '演示数据全部通过校验');
  const thisMonth = new Date();
  const s = Ledger.monthSummary(all, thisMonth.getFullYear(), thisMonth.getMonth() + 1);
  ok(s.expense > 0 && s.income > 0, '本月有支出也有收入（支出 ' + s.expense + ' / 收入 ' + s.income + '）');
  const prev = new Date(thisMonth.getFullYear(), thisMonth.getMonth() - 1, 1);
  ok(Ledger.monthSummary(all, prev.getFullYear(), prev.getMonth() + 1).expense > 0, '上月也有数据（环比可算）');
  const mom = Ledger.monthOverMonth(all, thisMonth.getFullYear(), thisMonth.getMonth() + 1);
  ok(mom.diff !== 0, '演示数据环比非零');
}

console.log('\n========================================');
console.log(`总计: ${passed + failed}  |  PASS ${passed}  |  FAIL ${failed}`);
process.exit(failed > 0 ? 1 : 0);
