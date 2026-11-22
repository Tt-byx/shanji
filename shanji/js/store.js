/* ============================================================
 * 闪记 · 数据层 (store.js)
 * 职责：数据模型定义 / 校验与规范化 / localStorage 持久化 / 演示数据
 * 运行环境：浏览器(全局 Store) + Node(CommonJS) 双端
 * ============================================================ */
(function (root, factory) {
  if (typeof module !== 'undefined' && module.exports) module.exports = factory();
  else root.Store = factory();
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  /* ---------------- 分类定义 ---------------- */
  // icon 为 24x24 SVG path 片段；color 用于统计色点/进度条，bg 用于列表图标底色
  const CATEGORIES = [
    { key: 'dining',        label: '餐饮', color: '#5046E5', bg: '#EEEDFC',
      icon: 'M4 3v5a2.5 2.5 0 0 0 5 0V3M6.5 3v18M17 10.5V21M17 3.5a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z' },
    { key: 'transport',     label: '交通', color: '#10B981', bg: '#E7F8F1',
      icon: 'M5 6a3 3 0 0 1 3-3h8a3 3 0 0 1 3 3v9a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V6zM5 10h14M8.5 15h.01M15.5 15h.01M7.5 21l1.2-3M16.5 21l-1.2-3' },
    { key: 'shopping',      label: '购物', color: '#F59E0B', bg: '#FEF3E2',
      icon: 'M5 8h14l-1.2 12a1.5 1.5 0 0 1-1.5 1.3H7.7A1.5 1.5 0 0 1 6.2 20L5 8zM9 11V6a3 3 0 0 1 6 0v5' },
    { key: 'daily',         label: '日用', color: '#0EA5E9', bg: '#E5F5FC',
      icon: 'M21 8l-9-5-9 5v8l9 5 9-5V8zM3 8l9 5 9-5M12 13v9' },
    { key: 'entertainment', label: '娱乐', color: '#8B85F0', bg: '#EEEDFC',
      icon: 'M4 8a4 4 0 0 1 4-4h8a4 4 0 0 1 4 4v8a4 4 0 0 1-4 4H8a4 4 0 0 1-4-4V8zM10.5 9.2l4.6 2.8-4.6 2.8V9.2z' },
    { key: 'health',        label: '医疗', color: '#EF4444', bg: '#FDEBEB',
      icon: 'M12 4v16M4 12h16' },
    { key: 'education',     label: '学习', color: '#0EA373', bg: '#E7F8F1',
      icon: 'M12 4L2 9l10 5 10-5-10-5zM6 11.5V16c0 1.7 2.7 3 6 3s6-1.3 6-3v-4.5' },
    { key: 'other',         label: '其他', color: '#6B7280', bg: '#F3F3F6',
      icon: 'M5 12h.01M12 12h.01M19 12h.01' }
  ];

  const CATEGORY_MAP = {};
  CATEGORIES.forEach(c => { CATEGORY_MAP[c.key] = c; });

  function getCategory(key) {
    return CATEGORY_MAP[key] || CATEGORY_MAP.other;
  }

  /* ---------------- 数据模型 ---------------- */
  // Transaction: { id, type:'expense'|'income', amount, merchant, category, note, ts, source, screenshot }
  const TX_TYPES = { EXPENSE: 'expense', INCOME: 'income' };

  function round2(n) { return Math.round(n * 100) / 100; }

  /**
   * 规范化并校验一笔交易，非法字段直接抛错。
   * @returns {object} 干净的交易对象
   */
  function createTx(input) {
    const type = input.type === TX_TYPES.INCOME ? TX_TYPES.INCOME : TX_TYPES.EXPENSE;
    const amount = round2(Number(input.amount));
    if (!isFinite(amount) || amount <= 0) throw new Error('金额必须大于 0');
    if (amount > 99999999) throw new Error('金额过大');
    const merchant = String(input.merchant || '').trim().slice(0, 30);
    if (!merchant) throw new Error('商户/事项不能为空');
    const category = CATEGORY_MAP[input.category] ? input.category
      : (type === TX_TYPES.INCOME ? 'other' : 'other');
    const note = String(input.note || '').trim().slice(0, 100);
    let ts = Number(input.ts);
    if (!isFinite(ts) || ts <= 0) ts = Date.now();
    const source = input.source === 'screenshot' ? 'screenshot' : 'manual';
    const screenshot = (typeof input.screenshot === 'string' && input.screenshot.startsWith('data:image'))
      ? input.screenshot : null;
    return {
      id: input.id || genId(),
      type, amount, merchant, category, note,
      ts: Math.round(ts),
      source, screenshot
    };
  }

  function genId() {
    return 'tx_' + Date.now().toString(36) + Math.random().toString(36).slice(2, 8);
  }

  /* ---------------- localStorage 持久化 ---------------- */
  const STORAGE_KEY = 'shanji.transactions.v1';
  let memoryStore = null;   // localStorage 不可用时的内存降级
  let storageOk = true;

  function rawGet() {
    try { return localStorage.getItem(STORAGE_KEY); }
    catch (e) { storageOk = false; return memoryStore; }
  }
  function rawSet(data) {
    memoryStore = data;
    try { localStorage.setItem(STORAGE_KEY, data); }
    catch (e) { storageOk = false; }
  }

  function parseList(raw) {
    let arr;
    try { arr = JSON.parse(raw); } catch (e) { return []; }
    if (!Array.isArray(arr)) return [];
    // 逐条容错：脏数据丢弃而不是让整个存储挂掉
    return arr.filter(t => t && typeof t === 'object')
      .map(t => { try { return createTx(t); } catch (e) { return null; } })
      .filter(Boolean);
  }

  /* ---------------- 对外 API ---------------- */
  const Store = {
    CATEGORIES, CATEGORY_MAP, TX_TYPES, getCategory, createTx, round2,

    /** 存储是否真实可用（false = 内存降级模式） */
    isStorageOk() { return storageOk; },

    /** 读取全部交易（按时间倒序：新的在前） */
    getAll() {
      return parseList(rawGet() || '[]')
        .sort((a, b) => b.ts - a.ts);
    },

    /** 保存全部（内部用，也供导入覆盖） */
    _saveAll(list) {
      rawSet(JSON.stringify(list));
    },

    /** 新增一笔，返回规范化后的对象 */
    add(input) {
      const tx = createTx(input);
      const list = parseList(rawGet() || '[]');
      list.push(tx);
      Store._saveAll(list);
      return tx;
    },

    /** 更新：按 id 合并字段，返回更新后的对象；不存在返回 null */
    update(id, patch) {
      const list = parseList(rawGet() || '[]');
      const idx = list.findIndex(t => t.id === id);
      if (idx < 0) return null;
      const merged = createTx(Object.assign({}, list[idx], patch, { id }));
      list[idx] = merged;
      Store._saveAll(list);
      return merged;
    },

    /** 删除，返回是否真的删了 */
    remove(id) {
      const list = parseList(rawGet() || '[]');
      const next = list.filter(t => t.id !== id);
      if (next.length === list.length) return false;
      Store._saveAll(next);
      return true;
    },

    /** 按 id 取单笔 */
    get(id) {
      return parseList(rawGet() || '[]').find(t => t.id === id) || null;
    },

    /** 清空全部 */
    clear() { Store._saveAll([]); },

    /** 导入（覆盖）：校验后写入，返回成功条数 */
    importAll(json) {
      const list = parseList(typeof json === 'string' ? json : JSON.stringify(json));
      Store._saveAll(list);
      return list.length;
    },

    /** 导出 JSON 字符串 */
    exportJSON() {
      return JSON.stringify(parseList(rawGet() || '[]'), null, 2);
    },

    /* ---------------- 演示数据 ---------------- */
    seedDemo() {
      const list = [];
      const now = new Date();
      const rnd = mulberry32(20261008); // 固定种子，可复现
      const pick = arr => arr[Math.floor(rnd() * arr.length)];
      const money = (min, max) => round2(min + rnd() * (max - min));

      const addTx = (daysAgo, type, merchant, category, amount, note) => {
        const d = new Date(now);
        d.setDate(d.getDate() - daysAgo);
        // 今天的账单时间不能落在未来（最多到当前小时前 1 小时）
        let h = 8 + Math.floor(rnd() * 13);
        if (daysAgo === 0) h = Math.min(h, Math.max(8, now.getHours() - 1));
        d.setHours(h, Math.floor(rnd() * 60), 0, 0);
        list.push(createTx({
          type, merchant, category, amount, note,
          ts: d.getTime(),
          source: rnd() > 0.5 ? 'screenshot' : 'manual'
        }));
      };

      // 覆盖近 42 天：本月 + 上月部分，保证趋势图与环比都有数据
      const food = ['瑞幸咖啡', '美团外卖', '学校二食堂', '蜜雪冰城', '兰州拉面', '瑞幸咖啡', '美团外卖'];
      const foodNote = ['下午提神', '加班晚餐', '午饭', '和室友拼单', '晚饭', '下午茶 · 和小林', '备注：夜宵'];
      for (let d = 0; d < 42; d++) {
        const n = 1 + Math.floor(rnd() * 3); // 每天 1~3 笔
        for (let i = 0; i < n; i++) {
          const r = rnd();
          if (r < 0.42) addTx(d, 'expense', pick(food), 'dining', money(6, 46), pick(foodNote));
          else if (r < 0.62) addTx(d, 'expense', pick(['地铁通勤', '公交', '共享单车']), 'transport', money(1.5, 8), '');
          else if (r < 0.78) addTx(d, 'expense', pick(['美团外卖', '学校二食堂']), 'dining', money(12, 40), '备注：外卖');
          else if (r < 0.88) addTx(d, 'expense', pick(['屈臣氏', '淘宝', '拼多多']), 'shopping', money(19, 168), '备注：日用品');
          else if (r < 0.95) addTx(d, 'expense', pick(['腾讯视频会员', '网易云黑胶', 'Steam']), 'entertainment', money(15, 98), '');
          else addTx(d, 'expense', pick(['校园超市', '水果店']), 'daily', money(9, 55), '');
        }
      }
      // 每月 1 号生活费收入
      for (let m = 0; m < 2; m++) {
        const d = new Date(now.getFullYear(), now.getMonth() - m, 1, 10, 0, 0);
        list.push(createTx({
          type: 'income', merchant: '生活费', category: 'other',
          amount: 2500, note: '爸妈打的生活费', ts: d.getTime(), source: 'manual'
        }));
      }
      // 本月固定会员
      addTx(6, 'expense', '腾讯视频会员', 'entertainment', 25, '');
      addTx(13, 'expense', '屈臣氏', 'shopping', 68, '备注：洗面奶和纸巾');

      Store._saveAll(list);
      return list.length;
    },

    /** 是否已做过首次种子（供 UI 判断） */
    seedFlagKey: 'shanji.seeded.v1'
  };

  /* 固定种子伪随机（mulberry32），保证演示数据每次一致 */
  function mulberry32(a) {
    return function () {
      a |= 0; a = a + 0x6D2B79F5 | 0;
      let t = Math.imul(a ^ a >>> 15, 1 | a);
      t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
      return ((t ^ t >>> 14) >>> 0) / 4294967296;
    };
  }

  return Store;
});
