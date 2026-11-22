/* ============================================================
 * 闪记 · 业务层 (ledger.js)
 * 职责：账本统计聚合 —— 月/周汇总、环比、日分组、趋势、分类占比、消费洞察
 * 不碰 DOM、不碰存储，纯函数入出，方便单测。
 * ============================================================ */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = factory(require('./store.js'));
  } else {
    root.Ledger = factory(root.Store);
  }
})(typeof self !== 'undefined' ? self : this, function (Store) {
  'use strict';

  const round2 = Store.round2;

  /** 取交易所属的 年/月/日/星期（周一=1 … 周日=7） */
  function ym(ts) { const d = new Date(ts); return { y: d.getFullYear(), m: d.getMonth() + 1 }; }
  function dayKey(ts) {
    const d = new Date(ts);
    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0');
  }
  function weekIndex(ts) { // 周一为一周开始
    const d = new Date(ts); const w = d.getDay(); return w === 0 ? 7 : w;
  }

  /** 过滤出某年某月的交易 */
  function monthTx(txs, y, m) {
    return txs.filter(t => { const k = ym(t.ts); return k.y === y && k.m === m; });
  }

  /** 汇总：{expense, income, balance} */
  function summary(txs) {
    let expense = 0, income = 0;
    for (const t of txs) {
      if (t.type === 'expense') expense += t.amount;
      else income += t.amount;
    }
    expense = round2(expense); income = round2(income);
    return { expense, income, balance: round2(income - expense) };
  }

  const Ledger = {
    ym, dayKey, weekIndex, monthTx, summary,

    /** 某月汇总 */
    monthSummary(txs, y, m) { return summary(monthTx(txs, y, m)); },

    /** 本月支出 - 上月支出（正数=比上月多花） */
    monthOverMonth(txs, y, m) {
      const cur = Ledger.monthSummary(txs, y, m).expense;
      const prev = (() => {
        const p = new Date(y, m - 2, 1);
        return Ledger.monthSummary(txs, p.getFullYear(), p.getMonth() + 1).expense;
      })();
      return { cur, prev, diff: round2(cur - prev) };
    },

    /** 明细页按天分组（仅某月），返回 [{key, label, items, dayExpense}]，倒序 */
    dailyGroups(txs, y, m) {
      const list = monthTx(txs, y, m).sort((a, b) => b.ts - a.ts);
      const map = new Map();
      const today = dayKey(Date.now());
      const yesterday = dayKey(Date.now() - 86400000);
      for (const t of list) {
        const k = dayKey(t.ts);
        if (!map.has(k)) map.set(k, { key: k, label: '', items: [], dayExpense: 0 });
        const g = map.get(k);
        g.items.push(t);
        if (t.type === 'expense') g.dayExpense = round2(g.dayExpense + t.amount);
      }
      const groups = [...map.values()];
      for (const g of groups) {
        if (g.key === today) g.label = '今天';
        else if (g.key === yesterday) g.label = '昨天';
        else {
          const d = new Date(g.key + 'T12:00:00');
          g.label = (d.getMonth() + 1) + '月' + d.getDate() + '日';
        }
      }
      return groups;
    },

    /**
     * 趋势：以 (y,m) 为最后一根柱，往回 n 个月的月支出。
     * 返回 [{y, m, label, expense, isCurrent}]
     */
    monthlyTrend(txs, y, m, n) {
      n = n || 5;
      const out = [];
      for (let i = n - 1; i >= 0; i--) {
        const d = new Date(y, m - 1 - i, 1);
        const yy = d.getFullYear(), mm = d.getMonth() + 1;
        out.push({
          y: yy, m: mm,
          label: (yy === y ? '' : yy + '年') + mm + '月',
          expense: Ledger.monthSummary(txs, yy, mm).expense,
          isCurrent: i === 0
        });
      }
      return out;
    },

    /** 分类占比（任意交易子集，纯聚合）[{key,label,color,amount,pct}] 降序 */
    categoryBreakdownOf(subset) {
      const sum = subset.reduce((s, t) => s + t.amount, 0);
      if (sum <= 0) return [];
      const byCat = new Map();
      for (const t of subset) {
        byCat.set(t.category, round2((byCat.get(t.category) || 0) + t.amount));
      }
      return [...byCat.entries()]
        .map(([key, amount]) => {
          const c = Store.getCategory(key);
          return { key, label: c.label, color: c.color, amount, pct: Math.round(amount / sum * 100) };
        })
        .sort((a, b) => b.amount - a.amount);
    },

    /** 分类占比（某月支出） */
    categoryBreakdown(txs, y, m) {
      return Ledger.categoryBreakdownOf(monthTx(txs, y, m).filter(t => t.type === 'expense'));
    },

    /** 周视图：包含 dateStr（今天）的那一周（周一开始）。返回 {start, end, summary} */
    weekSummary(txs, dateStr) {
      const base = dateStr ? new Date(dateStr + 'T12:00:00') : new Date();
      const offset = weekIndex(base.getTime()) - 1; // 距周一的天数
      const start = new Date(base); start.setDate(base.getDate() - offset); start.setHours(0, 0, 0, 0);
      const end = new Date(start); end.setDate(start.getDate() + 7);
      const inWeek = txs.filter(t => t.ts >= start.getTime() && t.ts < end.getTime());
      return {
        start: start.getTime(), end: end.getTime(),
        startLabel: (start.getMonth() + 1) + '.' + start.getDate(),
        endLabel: (end.getMonth() + 1) + '.' + end.getDate(),
        summary: summary(inWeek)
      };
    },

    /** 周视图环比：本周支出 - 上周支出 */
    weekOverWeek(txs, dateStr) {
      const base = dateStr ? new Date(dateStr + 'T12:00:00') : new Date();
      const lastWeek = new Date(base); lastWeek.setDate(base.getDate() - 7);
      const cur = Ledger.weekSummary(txs, dateStr).summary.expense;
      const prevDate = lastWeek.getFullYear() + '-' + String(lastWeek.getMonth() + 1).padStart(2, '0') + '-' + String(lastWeek.getDate()).padStart(2, '0');
      const prev = Ledger.weekSummary(txs, prevDate).summary.expense;
      return { cur, prev, diff: round2(cur - prev) };
    },

    /** 周内每日柱状图（周一~周日） */
    weekDaily(txs, dateStr) {
      const base = dateStr ? new Date(dateStr + 'T12:00:00') : new Date();
      const offset = weekIndex(base.getTime()) - 1;
      const start = new Date(base); start.setDate(base.getDate() - offset); start.setHours(0, 0, 0, 0);
      const days = [];
      const names = ['一', '二', '三', '四', '五', '六', '日'];
      for (let i = 0; i < 7; i++) {
        const d = new Date(start); d.setDate(start.getDate() + i);
        const next = new Date(d); next.setDate(d.getDate() + 1);
        const expense = round2(txs.filter(t => t.type === 'expense' && t.ts >= d.getTime() && t.ts < next.getTime())
          .reduce((s, t) => s + t.amount, 0));
        days.push({
          label: names[i],
          date: d.getDate(),
          isToday: dayKey(d.getTime()) === dayKey(Date.now()),
          expense
        });
      }
      return days;
    },

    /**
     * 消费洞察（规则引擎）：返回文案数组（0~3 条）
     * 规则：1) 最大分类 2) 咖啡/奶茶关键词 3) 外卖占比 4) 环比变化
     */
    insights(txs, y, m) {
      const out = [];
      const month = monthTx(txs, y, m).filter(t => t.type === 'expense');
      if (month.length === 0) return out;
      const total = month.reduce((s, t) => s + t.amount, 0);
      if (total <= 0) return out;

      // 1) 最大分类
      const cats = Ledger.categoryBreakdown(txs, y, m);
      if (cats.length) {
        const top = cats[0];
        out.push('本月「' + top.label + '」花了 ¥' + fmt(top.amount) + '，占总支出 ' + Math.round(top.amount / total * 100) + '%');
      }

      // 2) 咖啡/奶茶
      const coffee = month.filter(t => /咖啡|瑞幸|星巴克|奶茶|蜜雪/.test(t.merchant + t.note));
      if (coffee.length >= 2) {
        out.push('本月咖啡奶茶喝了 ' + coffee.length + ' 次、共 ¥' + fmt(coffee.reduce((s, t) => s + t.amount, 0)) + '，试试隔天自己做？');
      }

      // 3) 外卖占比
      const waimai = month.filter(t => /外卖/.test(t.merchant + t.note));
      if (waimai.length >= 3) {
        const pct = Math.round(waimai.reduce((s, t) => s + t.amount, 0) / total * 100);
        out.push('外卖共 ' + waimai.length + ' 单、占比 ' + pct + '%，本周挑两天自己带饭吧');
      }

      // 4) 环比
      const mom = Ledger.monthOverMonth(txs, y, m);
      if (mom.prev > 0 && Math.abs(mom.diff) >= 50) {
        out.push(mom.diff < 0
          ? '比上月少花 ¥' + fmt(-mom.diff) + '，继续保持'
          : '比上月多花 ¥' + fmt(mom.diff) + '，看看钱花哪去了');
      }
      return out.slice(0, 3);
    }
  };

  /** 千分位格式化（保留两位小数） */
  function fmt(n) {
    return round2(n).toFixed(2).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  }
  Ledger.fmt = fmt;

  /** 金额显示：整数部分千分位 + 固定两位小数 */
  Ledger.fmtAmount = fmt;

  return Ledger;
});
