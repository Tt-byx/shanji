/* ============================================================
 * 闪记 · 视图层与交互接线 (app.js)
 * 职责：屏幕切换 / 明细渲染 / 截屏识别流程 / 表单编辑与删除 / 统计渲染
 * 数据全部来自 Store(数据层) 与 Ledger(业务层)，本文件不含统计规则。
 * ============================================================ */
(function () {
  'use strict';

  const $ = sel => document.querySelector(sel);
  const { CATEGORIES, getCategory, TX_TYPES } = Store;
  const fmt = Ledger.fmtAmount;

  /* ---------------- 全局状态 ---------------- */
  const now = new Date();
  const state = {
    screen: 'home',
    viewY: now.getFullYear(),
    viewM: now.getMonth() + 1,
    statsRange: 'month',
    // 识别会话：选了截图但还没保存
    scan: { open: false, screenshot: null, category: 'dining', type: TX_TYPES.EXPENSE },
    // 编辑会话
    edit: { id: null, category: 'dining', type: TX_TYPES.EXPENSE },
    lastCategory: 'dining'
  };

  /* ---------------- 工具 ---------------- */
  function toast(msg) {
    const el = $('#toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toast._t);
    toast._t = setTimeout(() => el.classList.remove('show'), 2200);
  }

  function toInputValue(ts) {
    const d = new Date(ts);
    const p = n => String(n).padStart(2, '0');
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()) + 'T' + p(d.getHours()) + ':' + p(d.getMinutes());
  }
  function fromInputValue(v) {
    const t = new Date(v).getTime();
    return isFinite(t) ? t : Date.now();
  }

  function catIconHTML(key, size) {
    const c = getCategory(key);
    return '<span class="tx-icon" style="background:' + c.bg + '">' +
      '<svg viewBox="0 0 24 24" style="width:' + size + 'px;height:' + size + 'px">' +
      '<path d="' + c.icon + '" fill="none" stroke="' + c.color + '" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg></span>';
  }

  /** 压缩截图为缩略 dataURL（避免 localStorage 被大图撑爆） */
  function compressImage(fileOrBlob, cb) {
    const url = URL.createObjectURL(fileOrBlob);
    const img = new Image();
    img.onload = function () {
      try {
        const MAXW = 360;
        const scale = Math.min(1, MAXW / img.width);
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);
        cb(canvas.toDataURL('image/jpeg', 0.6));
      } catch (e) {
        cb(null);
      } finally {
        URL.revokeObjectURL(url);
      }
    };
    img.onerror = function () { URL.revokeObjectURL(url); cb(null); };
    img.src = url;
  }

  /* ---------------- 首次启动：载入演示数据 ---------------- */
  function boot() {
    let seeded = false;
    try { seeded = localStorage.getItem(Store.seedFlagKey) === '1'; } catch (e) { /* 忽略 */ }
    if (!seeded) {
      Store.seedDemo();
      try { localStorage.setItem(Store.seedFlagKey, '1'); } catch (e) { /* 忽略 */ }
      setTimeout(() => toast('已载入演示数据，可在「我的」里清空'), 800);
    }
    renderAll();
  }

  function renderAll() {
    renderHome();
    renderStats();
    renderProfile();
  }

  /* ================= 屏幕切换 ================= */
  const SCREENS = ['home', 'stats', 'profile'];
  function switchScreen(name) {
    if (name === 'scan') { openScan(); return; }
    state.screen = name;
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
    $('#screen-' + name).classList.add('active');
    document.querySelectorAll('#tabbar .tab').forEach(t => {
      t.classList.toggle('active', t.dataset.screen === name);
    });
    $('#screen-' + name).scrollTop = 0;
    if (name === 'stats') renderStats();
    if (name === 'profile') renderProfile();
  }

  document.querySelectorAll('#tabbar .tab').forEach(tab => {
    tab.addEventListener('click', () => switchScreen(tab.dataset.screen));
  });

  /* ================= 明细页 ================= */
  function renderHome() {
    // 月份标签与按钮状态
    $('#month-label').textContent = state.viewM + '月';
    const isCurrent = state.viewY === now.getFullYear() && state.viewM === now.getMonth() + 1;
    $('#month-next').disabled = isCurrent;

    const txs = Store.getAll();
    const s = Ledger.monthSummary(txs, state.viewY, state.viewM);
    $('#home-expense').textContent = '¥ ' + fmt(s.expense);
    $('#home-income').textContent = '¥' + fmt(s.income);
    $('#home-balance').textContent = '¥' + fmt(s.balance);

    renderPendingBar();
    renderTxList(txs);
  }

  function renderPendingBar() {
    const bar = $('#pending-bar');
    if (state.scan.screenshot) {
      $('#pending-text').textContent = '检测到 1 张付款截图待确认';
      bar.classList.remove('hidden');
    } else {
      bar.classList.add('hidden');
    }
  }

  function renderTxList(txs) {
    const wrap = $('#tx-list');
    const groups = Ledger.dailyGroups(txs, state.viewY, state.viewM);
    if (groups.length === 0) {
      wrap.innerHTML =
        '<div class="empty-state">' +
        '<svg viewBox="0 0 24 24"><rect x="3" y="7" width="18" height="13" rx="3" fill="none" stroke="currentColor" stroke-width="1.6"/><path d="M8.5 7l1.2-2.2h4.6L15.5 7" fill="none" stroke="currentColor" stroke-width="1.6"/><circle cx="12" cy="13.5" r="3.4" fill="none" stroke="currentColor" stroke-width="1.6"/></svg>' +
        '<b>这个月还没有账单</b><span>付款后截图，点底部「截屏」一键记账</span></div>';
      return;
    }
    let html = '';
    for (const g of groups) {
      html += '<div><div class="group-label">' + g.label + '</div><div class="card tx-card">';
      for (const t of g.items) {
        const c = getCategory(t.category);
        const tag = t.source === 'screenshot' ? '<span class="tx-tag">截屏</span>' : '';
        const noteText = t.note ? t.note.replace(/^备注[:：]/, '') : c.label;
        html += '<button class="tx-row" data-id="' + t.id + '">' +
          catIconHTML(t.category, 20) +
          '<span class="tx-main">' +
          '<span class="tx-name">' + esc(t.merchant) + tag + '</span>' +
          '<span class="tx-note">' + esc(noteText) + '</span>' +
          '</span>' +
          '<span class="tx-amount mono ' + (t.type === 'income' ? 'income' : '') + '">' +
          (t.type === 'income' ? '+' : '-') + '¥' + fmt(t.amount) + '</span>' +
          '</button>';
      }
      html += '</div></div>';
    }
    wrap.innerHTML = html;
    wrap.querySelectorAll('.tx-row').forEach(row => {
      row.addEventListener('click', () => openEdit(row.dataset.id));
    });
  }

  function esc(s) {
    return String(s).replace(/[&<>"']/g, ch => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    }[ch]));
  }

  // 月份切换
  $('#month-prev').addEventListener('click', () => {
    const d = new Date(state.viewY, state.viewM - 2, 1);
    state.viewY = d.getFullYear(); state.viewM = d.getMonth() + 1;
    renderHome();
  });
  $('#month-next').addEventListener('click', () => {
    const d = new Date(state.viewY, state.viewM, 1);
    state.viewY = d.getFullYear(); state.viewM = d.getMonth() + 1;
    renderHome();
  });
  // 提醒条 → 打开识别页继续未完成的截图
  $('#pending-bar').addEventListener('click', openScan);

  /* ================= 统计页 ================= */
  function renderStats() {
    const txs = Store.getAll();
    const isWeek = state.statsRange === 'week';
    const y = state.viewY, m = state.viewM;

    let total, mom, trend, cats, title, insightsHTML = '';

    if (isWeek) {
      const ws = Ledger.weekSummary(txs);
      const wow = Ledger.weekOverWeek(txs);
      total = ws.summary.expense;
      $('#stat-total-label').textContent = '本周总支出 (' + ws.startLabel + ' - ' + ws.endLabel + ')';
      mom = { cur: wow.cur, prev: wow.prev, diff: wow.diff };
      renderBars(Ledger.weekDaily(txs), true);
      cats = Ledger.categoryBreakdownOf(txs.filter(t =>
        t.type === 'expense' && t.ts >= ws.start && t.ts < ws.end));
      title = '本周各天支出';
    } else {
      const ms = Ledger.monthSummary(txs, y, m);
      total = ms.expense;
      $('#stat-total-label').textContent = m + '月总支出';
      mom = Ledger.monthOverMonth(txs, y, m);
      renderBars(Ledger.monthlyTrend(txs, y, m, 5), false);
      cats = Ledger.categoryBreakdown(txs, y, m);
      title = '近 5 个月趋势';
      Ledger.insights(txs, y, m).forEach(t => { insightsHTML += '<p>' + esc(t) + '</p>'; });
    }

    $('#stat-total').textContent = '¥ ' + fmt(total);
    // 环比：少花 = 绿色向下，多花 = 琥珀色
    const row = $('#stat-trend-row');
    if (mom.prev <= 0 && mom.cur <= 0) {
      row.classList.add('hidden');
    } else {
      row.classList.remove('hidden');
      row.classList.toggle('up', mom.diff > 0);
      $('#stat-trend-text').textContent = mom.diff <= 0
        ? '较上' + (isWeek ? '周' : '月') + '少花 ¥' + fmt(-mom.diff)
        : '较上' + (isWeek ? '周' : '月') + '多花 ¥' + fmt(mom.diff);
    }
    $('#chart-title').textContent = title;

    // 分类占比
    const catWrap = $('#cat-breakdown');
    if (cats.length === 0) {
      catWrap.innerHTML = '<div class="rank-empty">' + (isWeek ? '本周' : m + '月') + '还没有支出记录</div>';
    } else {
      const max = cats[0].amount;
      catWrap.innerHTML = cats.map(c =>
        '<div class="cat-row">' +
        '<div class="cat-top">' +
        '<span class="cat-dot" style="background:' + c.color + '"></span>' +
        '<span class="cat-name">' + esc(c.label) + '</span>' +
        '<span class="cat-amount">¥' + fmt(c.amount) + '</span>' +
        '<span class="cat-pct">' + c.pct + '%</span>' +
        '</div>' +
        '<div class="cat-track"><div class="cat-fill" style="width:' + Math.max(2, c.amount / max * 100) + '%;background:' + c.color + '"></div></div>' +
        '</div>').join('');
    }

    // 洞察（仅月视图；周视图复用卡片显示占比摘要）
    const ic = $('#insight-card');
    if (isWeek) {
      const ws = Ledger.weekSummary(txs);
      ic.classList.remove('hidden');
      $('#insight-body').innerHTML = cats.length
        ? '<p>本周最大开销是「' + esc(cats[0].label) + '」¥' + fmt(cats[0].amount) + '；周支出日均在 ¥' + fmt(ws.summary.expense / 7) + ' 左右。</p>'
        : '<p>本周还没有支出，保持住。</p>';
    } else if (insightsHTML) {
      ic.classList.remove('hidden');
      $('#insight-body').innerHTML = insightsHTML;
    } else {
      ic.classList.add('hidden');
    }
  }

  /** 柱状图渲染。items: [{label, expense, isCurrent?}]；week=true 时 isToday 高亮 */
  function renderBars(items, week) {
    const wrap = $('#bar-chart');
    const max = Math.max(...items.map(i => i.expense), 1);
    wrap.innerHTML = items.map(it => {
      const h = Math.max(4, Math.round(it.expense / max * 70));
      const cur = week ? it.isToday : it.isCurrent;
      return '<div class="bar-col' + (cur ? ' current' : '') + '">' +
        '<div class="bar" style="height:' + h + 'px"></div>' +
        '<span class="bar-label">' + it.label + '</span></div>';
    }).join('');
  }

  document.querySelectorAll('#stats-seg .seg-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      if (btn.dataset.range === state.statsRange) return;
      state.statsRange = btn.dataset.range;
      document.querySelectorAll('#stats-seg .seg-btn').forEach(b => b.classList.toggle('active', b === btn));
      renderStats();
    });
  });

  /* ================= 我的页 ================= */
  function renderProfile() {
    const txs = Store.getAll();
    const s = Ledger.summary(txs);
    $('#profile-count').textContent = txs.length;
    $('#profile-expense').textContent = '¥' + (s.expense >= 10000 ? (s.expense / 10000).toFixed(1) + 'w' : fmt(s.expense));
    $('#profile-income').textContent = '¥' + (s.income >= 10000 ? (s.income / 10000).toFixed(1) + 'w' : fmt(s.income));
    $('#storage-hint').textContent = Store.isStorageOk()
      ? '数据保存在本机浏览器中，不上传任何服务器。'
      : '当前处于隐私/无痕模式，数据仅保存在本次会话中，关闭即丢失。';
  }

  $('#btn-export').addEventListener('click', () => {
    const data = Store.exportJSON();
    const blob = new Blob([data], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = '闪记账单_' + new Date().toISOString().slice(0, 10) + '.json';
    a.click();
    URL.revokeObjectURL(a.href);
    toast('已导出 ' + Store.getAll().length + ' 笔账单');
  });

  $('#btn-demo').addEventListener('click', () => {
    if (!confirm('载入演示数据会覆盖当前全部账单，继续？')) return;
    Store.seedDemo();
    renderAll();
    toast('已载入演示数据');
  });

  $('#btn-clear').addEventListener('click', () => {
    if (!confirm('确定清空全部账单？此操作不可恢复，建议先导出备份。')) return;
    Store.clear();
    state.scan.screenshot = null;
    renderAll();
    toast('已清空全部数据');
  });

  /* ================= 分类 chips（两个表单共用渲染） ================= */
  function renderChips(wrapSel, activeKey, onPick) {
    const wrap = $(wrapSel);
    wrap.innerHTML = CATEGORIES.map(c =>
      '<button class="chip' + (c.key === activeKey ? ' active' : '') + '" data-key="' + c.key + '">' + c.label + '</button>'
    ).join('');
    wrap.querySelectorAll('.chip').forEach(chip => {
      chip.addEventListener('click', () => onPick(chip.dataset.key));
    });
  }

  /* ================= 截屏识别流程 ================= */
  const scanSheet = $('#scan-sheet');

  function openScan() {
    state.scan.open = true;
    scanSheet.classList.add('open');
    if (state.scan.screenshot) {
      showScanForm(); // 恢复未保存的截图会话
    } else {
      $('#scan-step-pick').classList.remove('hidden');
      $('#scan-step-form').classList.add('hidden');
      $('#scan-save').classList.add('hidden');
    }
  }
  function closeScan() {
    state.scan.open = false;
    scanSheet.classList.remove('open');
    renderHome(); // 刷新待确认提醒条
  }

  $('#scan-cancel').addEventListener('click', closeScan);
  scanSheet.addEventListener('click', e => { if (e.target === scanSheet) closeScan(); });

  $('#drop-zone').addEventListener('click', () => $('#file-input').click());
  $('#drop-zone').addEventListener('keydown', e => { if (e.key === 'Enter') $('#file-input').click(); });
  $('#file-input').addEventListener('change', e => {
    if (e.target.files && e.target.files[0]) handleShotFile(e.target.files[0]);
    e.target.value = '';
  });
  // 拖拽
  ['dragover', 'dragleave', 'drop'].forEach(ev => {
    $('#drop-zone').addEventListener(ev, e => {
      e.preventDefault();
      $('#drop-zone').classList.toggle('dragover', ev === 'dragover');
      if (ev === 'drop' && e.dataTransfer.files && e.dataTransfer.files[0]) {
        handleShotFile(e.dataTransfer.files[0]);
      }
    });
  });
  // 全局粘贴（识别面板打开时）
  document.addEventListener('paste', e => {
    if (!state.scan.open) return;
    const items = e.clipboardData && e.clipboardData.items;
    if (!items) return;
    for (const it of items) {
      if (it.type && it.type.startsWith('image/')) {
        handleShotFile(it.getAsFile());
        break;
      }
    }
  });

  function handleShotFile(file) {
    if (!file.type || !file.type.startsWith('image/')) { toast('请选择图片文件'); return; }
    compressImage(file, dataURL => {
      if (!dataURL) { toast('图片读取失败，换一张试试'); return; }
      state.scan.screenshot = dataURL;
      showScanForm();
      toast('截图已载入，确认信息后保存');
    });
  }

  function showScanForm() {
    $('#scan-step-pick').classList.add('hidden');
    $('#scan-step-form').classList.remove('hidden');
    $('#scan-save').classList.remove('hidden');
    $('#shot-preview').src = state.scan.screenshot || '';
    // 预填：时间 = 现在，分类 = 上次使用，金额/商户留白待确认
    $('#f-time').value = toInputValue(Date.now());
    $('#f-amount').value = '';
    $('#f-merchant').value = '';
    $('#f-note').value = '';
    state.scan.category = state.lastCategory;
    state.scan.type = TX_TYPES.EXPENSE;
    bindScanChips();
    setType('#type-switch', state.scan.type, t => { state.scan.type = t; });
  }

  // 识别表单分类 chips：点选后重渲染以刷新激活态
  function bindScanChips() {
    renderChips('#chip-wrap', state.scan.category, key => {
      state.scan.category = key;
      bindScanChips();
    });
  }

  function setType(switchSel, active, onChange) {
    document.querySelectorAll(switchSel + ' button').forEach(b => {
      b.classList.toggle('active', b.dataset.type === active);
      b.classList.toggle('income-on', b.dataset.type === 'income' && b.dataset.type === active);
    });
    // 重新绑定（元素不变，直接委托一次即可）
    const sw = $(switchSel);
    if (!sw._bound) {
      sw._bound = true;
      sw.addEventListener('click', e => {
        const btn = e.target.closest('button[data-type]');
        if (btn) onChange(btn.dataset.type, switchSel);
      });
    }
  }

  $('#scan-save').addEventListener('click', () => {
    const amount = parseFloat($('#f-amount').value);
    const merchant = $('#f-merchant').value.trim();
    if (!isFinite(amount) || amount <= 0) { toast('请填写正确的金额'); return; }
    if (!merchant) { toast('请填写商户或事项'); return; }
    try {
      Store.add({
        type: state.scan.type,
        amount, merchant,
        category: state.scan.category,
        note: $('#f-note').value.trim(),
        ts: fromInputValue($('#f-time').value),
        source: 'screenshot',
        screenshot: state.scan.screenshot
      });
      state.lastCategory = state.scan.category;
      state.scan.screenshot = null;   // 会话结束，提醒条消失
      closeScan();
      switchScreen('home');
      toast('已保存：' + merchant + ' ¥' + fmt(amount));
    } catch (err) {
      toast(err.message || '保存失败');
    }
  });

  /* ================= 编辑账单 ================= */
  const editSheet = $('#edit-sheet');

  function openEdit(id) {
    const t = Store.get(id);
    if (!t) return;
    state.edit.id = id;
    state.edit.category = t.category;
    state.edit.type = t.type;
    $('#e-amount').value = t.amount;
    $('#e-merchant').value = t.merchant;
    $('#e-time').value = toInputValue(t.ts);
    $('#e-note').value = t.note;
    const d = new Date(t.ts);
    $('#edit-meta').textContent = '记录于 ' + (d.getMonth() + 1) + '月' + d.getDate() + '日 ' +
      String(d.getHours()).padStart(2, '0') + ':' + String(d.getMinutes()).padStart(2, '0') +
      (t.source === 'screenshot' ? ' · 来自截屏识别' : ' · 手动记录');
    bindEditChips();
    setType('#etype-switch', t.type, () => {});
    editSheet.classList.add('open');
  }
  function bindEditChips() {
    renderChips('#echip-wrap', state.edit.category, key => {
      state.edit.category = key;
      bindEditChips();
    });
  }
  // 编辑表单的支出/收入切换
  $('#etype-switch').addEventListener('click', e => {
    const btn = e.target.closest('button[data-type]');
    if (!btn) return;
    state.edit.type = btn.dataset.type;
    document.querySelectorAll('#etype-switch button').forEach(b => {
      b.classList.toggle('active', b === btn);
      b.classList.toggle('income-on', b === btn && b.dataset.type === 'income');
    });
  });

  $('#edit-save').addEventListener('click', () => {
    const amount = parseFloat($('#e-amount').value);
    const merchant = $('#e-merchant').value.trim();
    if (!isFinite(amount) || amount <= 0) { toast('请填写正确的金额'); return; }
    if (!merchant) { toast('请填写商户或事项'); return; }
    try {
      Store.update(state.edit.id, {
        type: state.edit.type,
        amount, merchant,
        category: state.edit.category,
        note: $('#e-note').value.trim(),
        ts: fromInputValue($('#e-time').value)
      });
      state.lastCategory = state.edit.category;
      editSheet.classList.remove('open');
      renderAll();
      toast('已保存修改');
    } catch (err) {
      toast(err.message || '保存失败');
    }
  });

  $('#edit-delete').addEventListener('click', () => {
    if (!confirm('删除这笔账单？')) return;
    Store.remove(state.edit.id);
    editSheet.classList.remove('open');
    renderAll();
    toast('已删除');
  });
  editSheet.addEventListener('click', e => { if (e.target === editSheet) editSheet.classList.remove('open'); });

  /* ---------------- 启动 ---------------- */
  boot();
})();
