/* Demo console cho ewallet — vanilla JS, không build step.
   Gọi thẳng gateway; mã trạng thái HTTP là thông tin nghiệp vụ nên được hiển thị nguyên vẹn. */

const $ = (id) => document.getElementById(id);

const state = {
  get baseUrl() { return $('baseUrl').value.replace(/\/+$/, ''); },
  get customerId() { return $('customerId').value; },
};

const fmt = new Intl.NumberFormat('vi-VN');
const money = (n) => (n === null || n === undefined) ? '—' : fmt.format(n) + 'đ';
const uuid = () => (crypto.randomUUID ? crypto.randomUUID()
  : 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = Math.random() * 16 | 0;
      return (c === 'x' ? r : (r & 0x3 | 0x8)).toString(16);
    }));

/* ------------------------------------------------------------------ gọi API */

async function call(method, path, body, opts = {}) {
  const headers = { 'Accept': 'application/json' };
  if (body) {
    headers['Content-Type'] = 'application/json';
    headers['X-Idempotency-Key'] = opts.idempotencyKey || uuid();
  }

  const started = performance.now();
  try {
    const res = await fetch(state.baseUrl + path, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined,
    });
    const elapsed = Math.round(performance.now() - started);
    const text = await res.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* để nguyên text */ }
    return { ok: res.ok, status: res.status, json, text, elapsed };
  } catch (e) {
    return { ok: false, status: 0, json: null, text: String(e),
             elapsed: Math.round(performance.now() - started) };
  }
}

/* ------------------------------------------------------------------ log */

function statusPill(status) {
  if (status === 0) return ['pill-err', 'không kết nối được'];
  if (status < 300) return ['pill-ok', 'HTTP ' + status];
  if (status === 202) return ['pill-wait', 'HTTP 202'];
  if (status < 500) return ['pill-wait', 'HTTP ' + status];
  return ['pill-err', 'HTTP ' + status];
}

function logEntry(title, res, note) {
  const log = $('log');
  const empty = log.querySelector('.log-empty');
  if (empty) empty.remove();

  const d = res.json || {};
  const [pillClass, pillText] = statusPill(res.status);

  const el = document.createElement('div');
  el.className = 'entry';

  const head = document.createElement('div');
  head.className = 'entry-head';
  head.innerHTML =
    `<span class="entry-title"></span>
     <span class="pill ${pillClass}">${pillText}</span>
     <span class="pill pill-muted">${res.elapsed} ms</span>
     <span class="entry-time">${new Date().toLocaleTimeString('vi-VN')}</span>`;
  head.querySelector('.entry-title').textContent = title;
  el.appendChild(head);

  if (note) {
    const p = document.createElement('p');
    p.className = 'entry-note';
    p.textContent = note;
    el.appendChild(p);
  }

  const pairs = [];
  if (d.status) pairs.push(['Trạng thái', d.status]);
  if (d.reasonCode) pairs.push(['Lý do', d.reasonCode]);
  if (d.code) pairs.push(['Mã lỗi', d.code]);
  if (d.message) pairs.push(['Thông báo', d.message]);
  if (d.amount !== undefined) pairs.push(['Số tiền', money(d.amount)]);
  if (d.fee) pairs.push(['Phí', money(d.fee)]);
  if (d.amountVnd !== undefined && d.amountVnd !== d.amount) {
    pairs.push(['Quy đổi VND', money(d.amountVnd)]);
  }
  if (d.orderId) pairs.push(['Mã đơn', d.orderId]);
  if (d.refunded) pairs.push(['Đã hoàn tiền', 'có']);

  if (pairs.length) {
    const dl = document.createElement('dl');
    dl.className = 'entry-kv';
    for (const [k, v] of pairs) {
      const dt = document.createElement('dt'); dt.textContent = k;
      const dd = document.createElement('dd'); dd.textContent = v;
      dl.append(dt, dd);
    }
    el.appendChild(dl);
  }

  if (Array.isArray(d.steps) && d.steps.length) {
    const wrap = document.createElement('div');
    wrap.className = 'steps';
    for (const s of d.steps) {
      const chip = document.createElement('span');
      chip.className = 'step ' + String(s.stepStatus || '').toLowerCase();
      chip.textContent = s.stepName + (s.durationMs != null ? ` · ${s.durationMs}ms` : '');
      wrap.appendChild(chip);
    }
    el.appendChild(wrap);
  }

  const raw = document.createElement('details');
  raw.className = 'raw';
  const summary = document.createElement('summary');
  summary.textContent = 'JSON gốc';
  const pre = document.createElement('pre');
  pre.textContent = res.json ? JSON.stringify(res.json, null, 2) : res.text;
  raw.append(summary, pre);
  el.appendChild(raw);

  log.prepend(el);
  return d;
}

/* ------------------------------------------------------------------ số dư */

async function refreshBalance() {
  const res = await call('GET', `/admin/accounts/${encodeURIComponent(state.customerId)}/balance`);
  if (!res.ok || !res.json) {
    $('balance').textContent = '—';
    $('dailyUsage').textContent = '—';
    $('accountStatus').textContent = res.status === 0 ? 'mất kết nối' : 'không đọc được';
    return;
  }
  const d = res.json;
  $('balance').textContent = money(d.balance);
  $('accountStatus').textContent = d.status || '—';
  const used = d.dailyUsage ? d.dailyUsage.totalAmount : 0;
  $('dailyUsage').textContent = money(used);
  $('limitHint').textContent = `hạn mức theo spec: ${money(50000000)}/ngày`;
}

async function checkHealth() {
  const el = $('health');
  const res = await call('GET', '/actuator/health');
  if (res.ok) {
    el.className = 'pill pill-ok';
    el.textContent = 'gateway sẵn sàng';
  } else {
    el.className = 'pill pill-err';
    el.textContent = res.status === 0 ? 'không kết nối được gateway' : 'gateway lỗi ' + res.status;
  }
}

/* ------------------------------------------------------------------ hành động */

async function withBusy(btn, fn) {
  if (btn) { btn.disabled = true; }
  try { return await fn(); }
  finally { if (btn) btn.disabled = false; }
}

async function doTransfer(amount, note) {
  const body = {
    customerId: state.customerId,
    destCustomerId: $('tfDest').value,
    amount: Number(amount ?? $('tfAmount').value),
    currency: 'VND',
    message: $('tfMessage').value,
  };
  const res = await call('POST', '/api/wallet/transfer', body);
  logEntry(`Chuyển ${money(body.amount)} → ${body.destCustomerId}`, res, note);
  await refreshBalance();
  return res;
}

async function doTopup(amount, note) {
  const body = {
    customerId: state.customerId,
    amount: Number(amount ?? $('tuAmount').value),
    currency: 'VND',
    partnerCode: $('tuPartner').value,
    partnerAccountRef: $('tuRef').value,
  };
  const res = await call('POST', '/api/wallet/topup', body);
  logEntry(`Nạp ${money(body.amount)} qua ${body.partnerCode}`, res, note);
  await refreshBalance();
  return res;
}

async function loadHistory(limit) {
  const n = Number(limit ?? $('histLimit').value) || 20;
  const res = await call('GET',
    `/api/wallet/transactions?customerId=${encodeURIComponent(state.customerId)}&limit=${n}`);

  const table = $('historyTable');
  if (!res.ok || !res.json || !Array.isArray(res.json.items)) {
    table.innerHTML = '<p class="hint">Không tải được lịch sử.</p>';
    logEntry(`Lịch sử (limit ${n})`, res);
    return res;
  }

  const rows = res.json.items.map((o) => `
    <tr>
      <td>${new Date(o.createdAt).toLocaleString('vi-VN')}</td>
      <td>${o.paymentType}</td>
      <td class="num">${money(o.amount)}</td>
      <td class="num">${o.fee ? money(o.fee) : '—'}</td>
      <td><span class="pill ${o.status === 'COMPLETED' ? 'pill-ok'
        : (o.status === 'HELD' ? 'pill-wait' : 'pill-muted')}">${o.status}</span></td>
      <td>${o.steps ? o.steps.length : 0}</td>
    </tr>`).join('');

  table.innerHTML = `
    <table>
      <thead><tr><th>Thời điểm</th><th>Loại</th><th>Số tiền</th><th>Phí</th><th>Trạng thái</th><th>Bước</th></tr></thead>
      <tbody>${rows}</tbody>
    </table>`;

  logEntry(`Lịch sử ${res.json.count} đơn (limit ${n})`, res,
    `Mỗi đơn tốn thêm một câu SQL — đây là lỗi N+1 số 4.`);
  return res;
}

/* ------------------------------------------------------------------ kịch bản lỗi */

async function runBug1() {
  $('customerId').value = 'CUST-003';
  await refreshBalance();
  for (let i = 1; i <= 3; i++) {
    const res = await doTransfer(18000000,
      `Lần ${i}/3 — theo spec, tổng vượt 50 triệu thì phải bị từ chối`);
    if (!res.ok && res.status !== 202) break;
  }
  const bal = await call('GET', `/admin/accounts/CUST-003/balance`);
  const used = bal.json && bal.json.dailyUsage ? bal.json.dailyUsage.totalAmount : 0;
  logEntry('Kết luận lỗi #1', bal,
    used > 50000000
      ? `Đã dùng ${money(used)} trong ngày, vượt hạn mức spec 50 triệu mà vẫn qua — lỗi #1 tái hiện.`
      : `Đã dùng ${money(used)}. Chạy thêm lần nữa để vượt mốc 50 triệu.`);
}

async function runBug25() {
  $('customerId').value = 'CUST-003';
  await refreshBalance();
  const res = await doTransfer(25000000,
    'Vượt ngưỡng rà soát 20 triệu — phải treo duyệt VÀ phát event PaymentHeld');
  const held = res.json && res.json.status === 'HELD';
  const slow = (res.json && res.json.steps || []).find((s) => s.stepName === 'AUTHORIZE');
  logEntry('Kết luận lỗi #2 và #5', { status: res.status, json: null, text: '', elapsed: res.elapsed },
    (held ? 'Đơn đã treo duyệt đúng. ' : 'Đơn không treo — kiểm tra hạn mức ngày. ')
    + (slow ? `Bước AUTHORIZE mất ${slow.durationMs}ms, NFR yêu cầu dưới 500ms (lỗi #5). ` : '')
    + 'Mở Kafka console kiểm tra: sẽ KHÔNG có event PaymentHeld (lỗi #2).');
}

async function runBug4() {
  const n = 15;
  logEntry(`Tạo ${n} đơn nhỏ`, { status: 200, json: null, text: '', elapsed: 0 },
    'Đang tạo dữ liệu để lịch sử đủ dài…');
  for (let i = 0; i < n; i++) {
    await call('POST', '/api/wallet/transfer', {
      customerId: state.customerId, destCustomerId: $('tfDest').value,
      amount: 10000, currency: 'VND', message: 'du lieu demo N+1',
    });
  }
  const small = await loadHistory(3);
  const big = await loadHistory(30);
  logEntry('Kết luận lỗi #4', { status: 200, json: null, text: '', elapsed: 0 },
    `limit=3 mất ${small.elapsed}ms, limit=30 mất ${big.elapsed}ms. `
    + 'Thời gian tăng theo số đơn vì mỗi đơn thêm một câu SQL. '
    + 'NFR-DB-01 cho phép tối đa 2 câu mỗi lần gọi.');
  await refreshBalance();
}

async function runBug6() {
  const res = await call('POST', '/api/wallet/bill/pay', {
    customerId: 'CUST-003', partnerCode: 'EVN',
    billCode: $('billCode').value, amount: 15000, currency: 'USD',
  });
  logEntry('Thanh toán 15.000 USD', res,
    'Tỉ giá 25.000 nên đúng ra là 375 triệu, phải bị từ chối vì vượt hạn mức. '
    + 'Nếu được duyệt với amountVnd = 15.000 thì lỗi #6 đã tái hiện.');
}

/* ------------------------------------------------------------------ gắn sự kiện */

function bind() {
  $('tabs').addEventListener('click', (e) => {
    const tab = e.target.closest('.tab');
    if (!tab) return;
    document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('is-active', t === tab));
    document.querySelectorAll('.tabpanel').forEach((p) =>
      p.classList.toggle('is-active', p.dataset.panel === tab.dataset.tab));
  });

  $('customerId').addEventListener('change', refreshBalance);
  $('refreshBalance').addEventListener('click', refreshBalance);
  $('baseUrl').addEventListener('change', () => { checkHealth(); refreshBalance(); });
  $('clearLog').addEventListener('click', () => {
    $('log').innerHTML = '<p class="log-empty">Chưa có thao tác nào.</p>';
  });

  document.querySelectorAll('.chip[data-amount]').forEach((c) =>
    c.addEventListener('click', () => { $('tfAmount').value = c.dataset.amount; }));
  document.querySelectorAll('.chip[data-topup]').forEach((c) =>
    c.addEventListener('click', () => { $('tuAmount').value = c.dataset.topup; }));

  $('btnTransfer').addEventListener('click', (e) => withBusy(e.target, () => doTransfer()));
  $('btnTopup').addEventListener('click', (e) => withBusy(e.target, () => doTopup()));
  $('btnHistory').addEventListener('click', (e) => withBusy(e.target, () => loadHistory()));

  $('btnBillInquiry').addEventListener('click', (e) => withBusy(e.target, async () => {
    const res = await call('GET',
      `/api/wallet/bill?partnerCode=EVN&billCode=${encodeURIComponent($('billCode').value)}`);
    logEntry('Tra cứu hoá đơn ' + $('billCode').value, res);
  }));

  $('btnBillPay').addEventListener('click', (e) => withBusy(e.target, async () => {
    const res = await call('POST', '/api/wallet/bill/pay', {
      customerId: state.customerId, partnerCode: 'EVN',
      billCode: $('billCode').value, amount: Number($('billAmount').value), currency: 'VND',
    });
    logEntry('Thanh toán hoá đơn ' + $('billCode').value, res);
    await refreshBalance();
  }));

  $('btnTelco').addEventListener('click', (e) => withBusy(e.target, async () => {
    const res = await call('POST', '/api/wallet/telco/topup', {
      customerId: state.customerId, partnerCode: 'VTELCO',
      phoneNumber: $('telcoPhone').value, amount: Number($('telcoAmount').value), currency: 'VND',
    });
    logEntry('Nạp điện thoại ' + $('telcoPhone').value, res);
    await refreshBalance();
  }));

  $('bug1').addEventListener('click', (e) => withBusy(e.target, runBug1));
  $('bug25').addEventListener('click', (e) => withBusy(e.target, runBug25));
  $('bug4').addEventListener('click', (e) => withBusy(e.target, runBug4));
  $('bug6').addEventListener('click', (e) => withBusy(e.target, runBug6));
}

bind();
checkHealth();
refreshBalance();
setInterval(checkHealth, 15000);
