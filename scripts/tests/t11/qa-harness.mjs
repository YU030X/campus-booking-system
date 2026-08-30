import { spawn, spawnSync } from 'node:child_process';
import { appendFileSync, existsSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND = (process.env.T11_QA_FRONTEND || 'http://127.0.0.1:4174').replace(/\/$/, '');
const BACKEND = (process.env.T11_QA_BACKEND || 'http://127.0.0.1:18081').replace(/\/$/, '');
const MYSQL_CONTAINER = process.env.T11_QA_MYSQL_CONTAINER || 'campus-booking-validation-mysql-1';
const PASSWORD = 'Qa-Passw0rd!2026';
const ADMIN = { username: 't11qa_admin', password: PASSWORD, realName: 'T11 QA 管理员' };
const STUDENT = { username: 't11qa_student', password: PASSWORD, realName: 'T11 QA 学生' };
const loginPayload = (fixture) => ({ username: fixture.username, password: fixture.password });
const RESOURCE_ID = '1180001';
const BOOKING_APPROVE_ID = '1181001';
const BOOKING_REJECT_ID = '1181002';
const WAIT_MS = 10000;
const stamp = () => new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const RUN_DIR = path.join(HERE, 'artifacts', `run-${stamp()}-${process.pid}`);
const SCREEN_DIR = path.join(RUN_DIR, 'screenshots');
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const CHROME_CANDIDATES = [
  String.raw`C:\Program Files\Google\Chrome\Application\chrome.exe`,
  String.raw`C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`,
  process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Google/Chrome/Application/chrome.exe') : '',
  String.raw`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`,
].filter(Boolean);

class CaseFailure extends Error {}
const expect = (condition, message) => { if (!condition) throw new CaseFailure(message); };

function redact(text) {
  return String(text ?? '')
    .replace(/"(?:token|password|accessToken|refreshToken)"\s*:\s*"[^"]*"/gi, (value) => value.replace(/:"[^"]*"/, ':"***REDACTED***"'))
    .slice(0, 24000);
}

function mysql(sql) {
  const result = spawnSync('docker', [
    'exec', '-i', MYSQL_CONTAINER, 'sh', '-c', 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" booking_db',
  ], { input: sql, encoding: 'utf8', windowsHide: true });
  if (result.status !== 0) throw new Error(`mysql fixture failed: ${redact(result.stderr)}`);
}

async function api(method, pathname, body, token = null) {
  const headers = { 'content-type': 'application/json' };
  if (token) headers.authorization = `Bearer ${token}`;
  const response = await fetch(`${BACKEND}${pathname}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* reported by caller */ }
  return { status: response.status, json, text };
}

async function setupFixtures() {
  mysql(`
DELETE FROM approval_record WHERE booking_id IN (${BOOKING_APPROVE_ID}, ${BOOKING_REJECT_ID});
DELETE FROM booking_slot WHERE booking_id IN (${BOOKING_APPROVE_ID}, ${BOOKING_REJECT_ID}) OR resource_id=${RESOURCE_ID};
DELETE FROM booking WHERE id IN (${BOOKING_APPROVE_ID}, ${BOOKING_REJECT_ID}) OR resource_id=${RESOURCE_ID};
DELETE FROM resource_time_rule WHERE resource_id=${RESOURCE_ID};
DELETE FROM resource_closure WHERE resource_id=${RESOURCE_ID};
DELETE FROM resource WHERE id=${RESOURCE_ID};
DELETE FROM resource_category WHERE id=${RESOURCE_ID};
DELETE FROM notification WHERE user_id IN (SELECT id FROM user WHERE username IN ('${ADMIN.username}','${STUDENT.username}'));
DELETE FROM blacklist WHERE user_id IN (SELECT id FROM user WHERE username IN ('${ADMIN.username}','${STUDENT.username}'));
DELETE FROM user WHERE username IN ('${ADMIN.username}','${STUDENT.username}');
`);
  for (const fixture of [ADMIN, STUDENT]) {
    const registered = await api('POST', '/api/v1/auth/register', {
      username: fixture.username,
      password: fixture.password,
      realName: fixture.realName,
      studentNo: null,
      phone: null,
      email: null,
      avatar: null,
    });
    expect(registered.status === 201 && registered.json?.code === 0,
      `register ${fixture.username} failed: ${registered.status}/${registered.text}`);
  }
  mysql(`
UPDATE user SET role='ADMIN' WHERE username='${ADMIN.username}' AND deleted=0;
INSERT INTO resource_category(id,name,parent_id,sort_order,deleted)
VALUES (${RESOURCE_ID},'T11 QA 分类',0,0,0);
INSERT INTO resource(id,category_id,name,location,capacity,description,need_approval,max_advance_days,min_duration_minutes,max_duration_minutes,status,deleted)
VALUES (${RESOURCE_ID},${RESOURCE_ID},'T11 QA 审批室','QA-201',20,'T11 headless fixture',1,7,30,120,1,0);
INSERT INTO booking(id,booking_no,user_id,resource_id,start_time,end_time,purpose,attendee_count,status,deleted,created_at,updated_at)
SELECT ${BOOKING_APPROVE_ID},'T11QA-APPROVE',id,${RESOURCE_ID},DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 9 HOUR,DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 10 HOUR,'T11 批准夹具',2,'PENDING_APPROVAL',0,NOW()-INTERVAL 2 MINUTE,NOW()-INTERVAL 2 MINUTE
FROM user WHERE username='${STUDENT.username}' AND deleted=0;
INSERT INTO booking(id,booking_no,user_id,resource_id,start_time,end_time,purpose,attendee_count,status,deleted,created_at,updated_at)
SELECT ${BOOKING_REJECT_ID},'T11QA-REJECT',id,${RESOURCE_ID},DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 11 HOUR,DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 12 HOUR,'T11 驳回夹具',2,'PENDING_APPROVAL',0,NOW()-INTERVAL 1 MINUTE,NOW()-INTERVAL 1 MINUTE
FROM user WHERE username='${STUDENT.username}' AND deleted=0;
INSERT INTO booking_slot(resource_id,slot_time,booking_id)
VALUES (${RESOURCE_ID},DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 9 HOUR,${BOOKING_APPROVE_ID}),
       (${RESOURCE_ID},DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 11 HOUR,${BOOKING_REJECT_ID});
`);
  const adminLogin = await api('POST', '/api/v1/auth/login', loginPayload(ADMIN));
  const studentLogin = await api('POST', '/api/v1/auth/login', loginPayload(STUDENT));
  expect(adminLogin.status === 200 && adminLogin.json?.data?.user?.role === 'ADMIN', 'admin promotion/login failed');
  expect(studentLogin.status === 200 && studentLogin.json?.data?.user?.role === 'STUDENT', 'student login failed');
  return {
    adminId: String(adminLogin.json.data.user.id),
    studentId: String(studentLogin.json.data.user.id),
  };
}

class CdpConnection {
  constructor(url) { this.url = url; this.seq = 1; this.pending = new Map(); this.listeners = new Map(); }
  async connect() {
    this.ws = new WebSocket(this.url);
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('CDP connect timeout')), 15000);
      this.ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      this.ws.addEventListener('error', () => { clearTimeout(timer); reject(new Error('CDP websocket error')); }, { once: true });
    });
    this.ws.addEventListener('message', (event) => this.frame(event.data));
  }
  frame(data) {
    const raw = typeof data === 'string' ? data : Buffer.from(data).toString('utf8');
    let msg; try { msg = JSON.parse(raw); } catch { return; }
    if (msg.id != null) {
      const item = this.pending.get(msg.id); if (!item) return;
      this.pending.delete(msg.id);
      if (msg.error) item.reject(new Error(`CDP ${msg.error.message}`)); else item.resolve(msg.result);
      return;
    }
    for (const fn of this.listeners.get(msg.method) || []) fn(msg.params || {}, msg.sessionId);
  }
  send(method, params = {}, sessionId = null) {
    const id = this.seq++;
    this.ws.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`CDP timeout ${method}`)); }, 18000);
      this.pending.set(id, { resolve: (v) => { clearTimeout(timer); resolve(v); }, reject: (e) => { clearTimeout(timer); reject(e); } });
    });
  }
  on(name, fn) { const list = this.listeners.get(name) || []; list.push(fn); this.listeners.set(name, list); }
  off(name, fn) { const list = this.listeners.get(name) || []; const i = list.indexOf(fn); if (i >= 0) list.splice(i, 1); }
  close() { try { this.ws?.close(); } catch { /* best effort */ } }
}

class Journal {
  constructor() { this.calls = []; this.dialogs = []; this.console = []; }
  attach(conn, sid) {
    conn.on('Network.requestWillBeSent', (p, s) => {
      if (s !== sid || !p.request?.url?.includes('/api/v1/')) return;
      const item = { requestId: p.requestId, at: Date.now(), method: p.request.method, url: p.request.url, postData: redact(p.request.postData || ''), status: null, body: null };
      this.calls.push(item);
      appendFileSync(path.join(RUN_DIR, 'network.jsonl'), `${JSON.stringify({ phase: 'request', ...item, requestId: undefined })}\n`);
    });
    conn.on('Network.responseReceived', (p, s) => {
      if (s !== sid) return;
      const item = this.calls.find((call) => call.requestId === p.requestId);
      if (!item) return;
      item.status = p.response.status;
      appendFileSync(path.join(RUN_DIR, 'network.jsonl'), `${JSON.stringify({ phase: 'response', method: item.method, url: item.url, status: item.status })}\n`);
    });
    conn.on('Network.loadingFinished', (p, s) => {
      if (s !== sid) return;
      const item = this.calls.find((call) => call.requestId === p.requestId);
      if (!item) return;
      conn.send('Network.getResponseBody', { requestId: p.requestId }, sid).then((body) => {
        item.body = redact(body?.base64Encoded ? Buffer.from(body.body || '', 'base64').toString('utf8') : body?.body || '');
        try { item.json = JSON.parse(item.body); } catch { item.json = null; }
        appendFileSync(path.join(RUN_DIR, 'network.jsonl'), `${JSON.stringify({ phase: 'body', method: item.method, url: item.url, status: item.status, body: item.body })}\n`);
      }).catch(() => {});
    });
    conn.on('Page.javascriptDialogOpening', (p, s) => { if (s === sid) this.dialogs.push({ at: Date.now(), type: p.type, message: p.message }); });
    conn.on('Runtime.consoleAPICalled', (p, s) => {
      if (s !== sid) return;
      const text = (p.args || []).map((arg) => arg.value ?? arg.description ?? '').join(' ').slice(0, 1000);
      this.console.push(text);
      appendFileSync(path.join(RUN_DIR, 'console.jsonl'), `${JSON.stringify({ at: new Date().toISOString(), type: p.type, text })}\n`);
    });
  }
  after(marker, predicate) { return this.calls.filter((call) => call.at >= marker && predicate(call)); }
  last(marker, predicate) { return [...this.calls].reverse().find((call) => call.at >= marker && predicate(call)); }
}

class Browser {
  constructor(journal) { this.journal = journal; }
  async launch() {
    this.exe = CHROME_CANDIDATES.find(existsSync);
    expect(this.exe, 'Chrome/Edge executable not found');
    const profile = path.join(RUN_DIR, 'chrome-profile'); mkdirSync(profile, { recursive: true });
    this.proc = spawn(this.exe, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu', '--window-size=1440,900', 'about:blank'], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    const wsUrl = await new Promise((resolve, reject) => {
      let output = ''; const timer = setTimeout(() => reject(new Error('Chrome devtools timeout')), 20000);
      const onData = (chunk) => { output += chunk; const m = /ws:\/\/[^\s]+/.exec(output); if (m) { clearTimeout(timer); resolve(m[0]); } };
      this.proc.stdout.on('data', onData); this.proc.stderr.on('data', onData);
    });
    this.conn = new CdpConnection(wsUrl); await this.conn.connect();
    ({ targetId: this.targetId } = await this.conn.send('Target.createTarget', { url: 'about:blank' }));
    ({ sessionId: this.sid } = await this.conn.send('Target.attachToTarget', { targetId: this.targetId, flatten: true }));
    await Promise.all(['Page.enable', 'Runtime.enable', 'Network.enable'].map((name) => this.conn.send(name, {}, this.sid)));
    this.journal.attach(this.conn, this.sid);
    return (await this.conn.send('Browser.getVersion')).product;
  }
  async close() {
    this.conn?.close();
    if (this.proc?.exitCode == null) this.proc.kill();
    await sleep(250);
    if (this.proc?.exitCode == null && process.platform === 'win32') spawnSync('taskkill', ['/PID', String(this.proc.pid), '/T', '/F'], { windowsHide: true });
  }
}

class Session {
  constructor(browser) { this.conn = browser.conn; this.sid = browser.sid; this.shotSeq = 0; }
  async eval(expression) {
    const result = await this.conn.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true }, this.sid);
    if (result.exceptionDetails) throw new CaseFailure(result.exceptionDetails.exception?.description || result.exceptionDetails.text);
    return result.result?.value;
  }
  evalObj(fn, ...args) { return this.eval(`(${fn})(...${JSON.stringify(args)})`); }
  async navigate(url) {
    await this.conn.send('Page.navigate', { url }, this.sid);
    await this.wait('navigation', () => document.readyState === 'complete');
    await sleep(250);
  }
  async wait(label, fn, timeout = WAIT_MS, ...args) {
    const until = Date.now() + timeout; let value = null;
    while (Date.now() < until) {
      value = await this.evalObj(fn, ...args).catch(() => null);
      if (value) return value;
      await sleep(120);
    }
    throw new CaseFailure(`timeout: ${label}; last=${JSON.stringify(value)}`);
  }
  async shot(name) {
    this.shotSeq += 1;
    const result = await this.conn.send('Page.captureScreenshot', { format: 'png', fromSurface: true }, this.sid);
    const file = `${String(this.shotSeq).padStart(2, '0')}-${name.replace(/[^\w.-]+/g, '_')}.png`;
    writeFileSync(path.join(SCREEN_DIR, file), Buffer.from(result.data, 'base64'));
    return file;
  }
}

async function waitLocal(label, fn, timeout = WAIT_MS) {
  const until = Date.now() + timeout; let value = null;
  while (Date.now() < until) { value = fn(); if (value) return value; await sleep(120); }
  throw new CaseFailure(`timeout: ${label}`);
}

const clickText = (root, text) => `(function(){const el=[...document.querySelectorAll(${JSON.stringify(root)}+' button')].find((node)=>(node.textContent||'').trim().includes(${JSON.stringify(text)}));if(!el)return false;el.click();return true;})()`;

async function loginBrowser(session, fixture) {
  await session.navigate(`${FRONTEND}/login`);
  const result = await session.evalObj(async (user) => {
    const response = await fetch('/api/v1/auth/login', { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(user) });
    const body = await response.json();
    if (response.status !== 200 || body.code !== 0) return { ok: false, status: response.status };
    sessionStorage.setItem('campus.auth.session', JSON.stringify({ token: body.data.token, tokenType: 'Bearer', expiresAt: Date.now() + body.data.expiresIn * 1000 }));
    return { ok: true, id: body.data.user.id, role: body.data.user.role };
  }, loginPayload(fixture));
  expect(result.ok, `browser login failed ${fixture.username}`);
  await session.navigate(`${FRONTEND}/resources`);
  await session.wait('authenticated shell', () => document.body.textContent.includes('Campus Booking'));
  return result;
}

async function caseStudentBoundary(ctx) {
  const { session, journal, summary } = ctx;
  await loginBrowser(session, STUDENT);
  const marker = Date.now();
  for (const route of ['/admin/users', '/admin/approvals']) {
    await session.evalObj(async (target) => {
      const router = (await import('/src/router/index.js')).default;
      try { await router.push(target); } catch { /* navigation denial */ }
      return location.pathname;
    }, route);
    await session.wait('in-layout forbidden', () => document.body.textContent.includes('You do not have permission'));
    await session.shot(`student-denied-${route.split('/').pop()}`);
  }
  const leaked = journal.after(marker, (call) => /\/api\/v1\/admin\/(?:users|approvals)/.test(call.url));
  expect(leaked.length === 0, `student navigation leaked ${leaked.length} admin requests`);

  const directMarker = Date.now();
  const outcome = await session.evalObj(async () => {
    const { http } = await import('/src/api/http.js');
    let status = null; let code = null;
    try { await http.get('/admin/users'); } catch (error) { status = error.response?.status; code = error.response?.data?.code; }
    return { status, code, sessionPresent: sessionStorage.getItem('campus.auth.session') !== null };
  });
  expect(outcome.status === 403 && outcome.code === 40300 && outcome.sessionPresent, `student 403 contract failed: ${JSON.stringify(outcome)}`);
  await waitLocal('student direct 403 network', () => journal.last(directMarker, (call) => call.url.includes('/api/v1/admin/users') && call.status === 403));
  summary.studentBoundary = { routes: 2, leakedAdminRequests: 0, directStatus: 403, code: 40300, sessionPreserved: true };
}

async function caseAdminUsers(ctx) {
  const { session, journal, fixtureIds, summary } = ctx;
  await loginBrowser(session, ADMIN);
  let marker = Date.now();
  await session.navigate(`${FRONTEND}/admin/users`);
  await session.wait('admin user rows', () => document.querySelectorAll('table.user-table tbody tr').length >= 2);
  await waitLocal('admin users GET', () => journal.last(marker, (call) => call.method === 'GET' && call.url.includes('/api/v1/admin/users') && call.status === 200));
  await session.shot('admin-users-list');

  marker = Date.now();
  await session.evalObj((username) => {
    const section = document.querySelector('section.filters');
    const input = section.querySelector('input'); input.value = `  ${username}  `; input.dispatchEvent(new Event('input', { bubbles: true }));
    const selects = section.querySelectorAll('select');
    selects[0].value = 'STUDENT'; selects[0].dispatchEvent(new Event('change', { bubbles: true }));
    selects[1].value = '1'; selects[1].dispatchEvent(new Event('change', { bubbles: true }));
    [...section.querySelectorAll('button')].find((b) => b.textContent.includes('搜索')).click();
    return true;
  }, STUDENT.username);
  const filtered = await waitLocal('filtered user GET', () => journal.last(marker, (call) => {
    if (call.method !== 'GET' || !call.url.includes('/api/v1/admin/users?')) return false;
    const url = new URL(call.url);
    return url.searchParams.get('keyword') === STUDENT.username && url.searchParams.get('role') === 'STUDENT' && url.searchParams.get('status') === '1';
  }));
  await session.wait('filtered student row', (name) => document.querySelectorAll('table.user-table tbody tr').length === 1 && document.body.textContent.includes(name), WAIT_MS, STUDENT.username);

  marker = Date.now();
  await session.eval(clickText('table.user-table tbody tr', '禁用'));
  await session.wait('status dialog', () => !!document.querySelector('section[role="dialog"][aria-labelledby="user-status-title"]'));
  await session.eval(clickText('section[role="dialog"]', '下一步'));
  await session.eval(clickText('section[role="dialog"]', '确认执行'));
  const patch = await waitLocal('student status PATCH 200', () => journal.last(marker, (call) => call.method === 'PATCH' && call.url.includes(`/api/v1/admin/users/${fixtureIds.studentId}/status`) && call.status === 200));
  await session.wait('status dialog closed', () => !document.querySelector('section[role="dialog"][aria-labelledby="user-status-title"]'));

  await session.navigate(`${FRONTEND}/admin/users`);
  await session.wait('unfiltered rows', () => document.querySelectorAll('table.user-table tbody tr').length >= 2);
  marker = Date.now();
  const opened = await session.evalObj((adminName) => {
    const row = [...document.querySelectorAll('table.user-table tbody tr')].find((tr) => tr.textContent.includes(adminName));
    const button = [...row.querySelectorAll('button')].find((b) => b.textContent.includes('禁用'));
    button.click(); return true;
  }, ADMIN.username);
  expect(opened, 'admin self-disable control missing');
  await session.eval(clickText('section[role="dialog"]', '下一步'));
  await session.eval(clickText('section[role="dialog"]', '确认执行'));
  const conflict = await waitLocal('self-disable 409', () => journal.last(marker, (call) => call.method === 'PATCH' && call.url.includes(`/api/v1/admin/users/${fixtureIds.adminId}/status`) && call.status === 409));
  await waitLocal('self-disable response body', () => conflict.json?.code === 41000 && conflict);
  await session.wait('self-disable error rendered', () => document.querySelector('section[role="dialog"] [role="alert"]')?.textContent.includes('拒绝'));
  const sessionKept = await session.evalObj(() => sessionStorage.getItem('campus.auth.session') !== null);
  expect(sessionKept, 'self-disable 409 cleared admin session');
  await session.shot('admin-self-disable-409');
  summary.users = { initialGet: 200, filteredQuery: new URL(filtered.url).search, studentPatch: patch.status, selfDisable: { status: conflict.status, code: conflict.json.code, sessionPreserved: true } };
}

async function caseApprovals(ctx) {
  const { session, journal, summary } = ctx;
  let marker = Date.now();
  await session.navigate(`${FRONTEND}/admin/approvals`);
  await session.wait('two pending approvals', () => document.querySelectorAll('table.approval-table tbody tr').length === 2);
  await session.shot('approvals-pending-list');

  marker = Date.now();
  const firstId = await session.evalObj(() => {
    const row = document.querySelector('table.approval-table tbody tr');
    const id = row.querySelector('td').textContent.trim();
    [...row.querySelectorAll('button')].find((b) => b.textContent.includes('批准')).click();
    return id;
  });
  await session.wait('approve dialog', () => !!document.querySelector('section[role="dialog"][aria-labelledby="approval-action-title"]'));
  await session.eval(clickText('section[role="dialog"]', '下一步'));
  await session.eval(clickText('section[role="dialog"]', '确认批准'));
  const approved = await waitLocal('approve 200', () => journal.last(marker, (call) => call.method === 'POST' && call.url.endsWith(`/api/v1/admin/bookings/${firstId}/approve`) && call.status === 200));
  expect(JSON.parse(approved.postData).comment === null, 'blank approve comment did not serialize as null');
  await session.wait('one pending remains', () => document.querySelectorAll('table.approval-table tbody tr').length === 1);

  marker = Date.now();
  const secondId = await session.evalObj(() => {
    const row = document.querySelector('table.approval-table tbody tr');
    const id = row.querySelector('td').textContent.trim();
    [...row.querySelectorAll('button')].find((b) => b.textContent.includes('驳回')).click();
    return id;
  });
  await session.wait('reject dialog', () => !!document.querySelector('section[role="dialog"][aria-labelledby="approval-action-title"]'));
  const blankGuard = await session.evalObj(() => {
    const dialog = document.querySelector('section[role="dialog"]');
    return { alert: dialog.querySelector('[role="alert"]')?.textContent || '', disabled: [...dialog.querySelectorAll('button')].find((b) => b.textContent.includes('下一步'))?.disabled };
  });
  expect(blankGuard.disabled && blankGuard.alert.includes('必填'), `blank reject was not blocked: ${JSON.stringify(blankGuard)}`);
  await session.evalObj(() => {
    const area = document.querySelector('section[role="dialog"] textarea');
    area.value = '  T11 QA 驳回原因  '; area.dispatchEvent(new Event('input', { bubbles: true })); return true;
  });
  await session.wait('reject validation clears', () => !document.querySelector('section[role="dialog"] [role="alert"]'));
  await session.eval(clickText('section[role="dialog"]', '下一步'));
  await session.eval(clickText('section[role="dialog"]', '确认驳回'));
  const rejected = await waitLocal('reject 200', () => journal.last(marker, (call) => call.method === 'POST' && call.url.endsWith(`/api/v1/admin/bookings/${secondId}/reject`) && call.status === 200));
  expect(JSON.parse(rejected.postData).comment === 'T11 QA 驳回原因', 'reject comment was not trimmed');
  await session.wait('pending list empty', () => document.body.textContent.includes('暂无待审批预约'));
  await session.shot('approvals-completed');

  marker = Date.now();
  const boundaries = await session.evalObj(async (approvedId) => {
    const { http } = await import('/src/api/http.js');
    const capture = async (url, body) => { try { await http.post(url, body); return null; } catch (error) { return { status: error.response?.status, code: error.response?.data?.code }; } };
    return {
      missing: await capture('/admin/bookings/999999999999/approve', { comment: null }),
      opposite: await capture(`/admin/bookings/${approvedId}/reject`, { comment: 'opposite action' }),
      sessionPresent: sessionStorage.getItem('campus.auth.session') !== null,
    };
  }, firstId);
  expect(boundaries.missing?.status === 404 && boundaries.missing?.code === 40400, `missing boundary failed: ${JSON.stringify(boundaries)}`);
  expect(boundaries.opposite?.status === 409 && boundaries.opposite?.code === 43000, `opposite boundary failed: ${JSON.stringify(boundaries)}`);
  expect(boundaries.sessionPresent, '404/409 cleared the admin session');
  await waitLocal('boundary network evidence', () => journal.after(marker, (call) => call.status === 404 || call.status === 409).length >= 2);
  summary.approvals = { approved: { id: firstId, status: approved.status, comment: null }, rejected: { id: secondId, status: rejected.status, trimmedComment: true }, boundaries };
}

async function caseShared401(ctx) {
  const { session, journal, summary } = ctx;
  const marker = Date.now();
  const outcome = await session.evalObj(async () => {
    sessionStorage.setItem('campus.auth.session', JSON.stringify({ token: 't11-invalid-token', tokenType: 'Bearer', expiresAt: Date.now() + 600000 }));
    const { http, getAuth401Promise } = await import('/src/api/http.js');
    let status = null;
    try { await http.get('/admin/users'); } catch (error) { status = error.response?.status; await (error.authRecovery || getAuth401Promise()); }
    await new Promise((resolve) => setTimeout(resolve, 300));
    return { status, path: location.pathname, cleared: sessionStorage.getItem('campus.auth.session') === null };
  });
  expect(outcome.status === 401 && outcome.path === '/login' && outcome.cleared, `shared 401 failed: ${JSON.stringify(outcome)}`);
  await waitLocal('401 network evidence', () => journal.last(marker, (call) => call.url.includes('/api/v1/admin/users') && call.status === 401));
  await session.shot('shared-401-cleared');
  summary.shared401 = outcome;
}

const CASES = [
  ['01', 'STUDENT 路由拒绝零管理员请求并保留 403 会话', caseStudentBoundary],
  ['02', 'ADMIN 用户筛选、状态变更与自禁用 409', caseAdminUsers],
  ['03', 'ADMIN 审批批准/驳回、验证、刷新及 404/409', caseApprovals],
  ['04', '共享 401 清会话并跳转登录', caseShared401],
];

async function smoke() {
  const exe = CHROME_CANDIDATES.find(existsSync); if (!exe) return 1;
  const proc = spawn(exe, ['--headless=new', '--remote-debugging-port=0', '--no-first-run', 'about:blank'], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  await sleep(1200); const alive = proc.exitCode == null; proc.kill();
  console.log(alive ? `T11_CHROME_SMOKE_OK ${exe}` : 'T11_CHROME_SMOKE_FAIL');
  return alive ? 0 : 1;
}

async function main() {
  if (process.argv.includes('--list')) { CASES.forEach(([id, title]) => console.log(`${id}  ${title}`)); return 0; }
  if (process.argv.includes('--smoke')) return smoke();
  mkdirSync(SCREEN_DIR, { recursive: true });
  writeFileSync(path.join(RUN_DIR, 'network.jsonl'), ''); writeFileSync(path.join(RUN_DIR, 'console.jsonl'), '');
  const front = await api('GET', '/actuator/health').catch(() => null);
  const frontProbe = await fetch(`${FRONTEND}/`).catch(() => null);
  if (front?.status !== 200 || frontProbe?.status !== 200) {
    console.error(`GATES_DOWN frontend=${frontProbe?.status || 'down'} backend=${front?.status || 'down'}`);
    return 2;
  }
  const fixtureIds = await setupFixtures();
  const journal = new Journal(); const browser = new Browser(journal); const summary = {}; const results = [];
  let chrome = null;
  try {
    chrome = await browser.launch(); const session = new Session(browser); const ctx = { session, journal, fixtureIds, summary };
    for (const [id, title, fn] of CASES) {
      const started = Date.now(); process.stdout.write(`[${id}] ${title} ... `);
      try { await fn(ctx); results.push({ id, title, status: 'passed', durationMs: Date.now() - started }); console.log('PASS'); }
      catch (error) { results.push({ id, title, status: 'failed', durationMs: Date.now() - started, error: String(error.message || error) }); console.log(`FAIL\n  -> ${error.message || error}`); }
    }
  } finally { await browser.close(); }
  const passed = results.every((item) => item.status === 'passed') && journal.dialogs.length === 0;
  const report = { passed, chrome, frontend: FRONTEND, backend: BACKEND, fixtureIds, nativeDialogs: journal.dialogs, summary, results };
  writeFileSync(path.join(RUN_DIR, 'summary.json'), JSON.stringify(report, null, 2));
  writeFileSync(path.join(RUN_DIR, 'REPORT.md'), `# T11 Headless QA\n\n- Result: ${passed ? 'PASS' : 'FAIL'}\n- Cases: ${results.filter((r) => r.status === 'passed').length}/${results.length}\n- Native dialogs: ${journal.dialogs.length}\n- Chrome: ${chrome}\n\n${results.map((r) => `- ${r.id} ${r.title}: ${r.status}${r.error ? ` — ${r.error}` : ''}`).join('\n')}\n`);
  if (passed) writeFileSync(path.join(RUN_DIR, 'PASS'), `PASS ${new Date().toISOString()}\ncases=${results.length}\nnativeDialogs=0\n`); else rmSync(path.join(RUN_DIR, 'PASS'), { force: true });
  console.log(`ARTIFACTS ${RUN_DIR}`);
  return passed ? 0 : 1;
}

main().then((code) => process.exit(code)).catch((error) => { console.error(error.stack || error); process.exit(1); });
