// T11 owner runner: T13 ApprovalBrowser intake contract (OCR-8).
// argv: <RunId> <ArtifactRoot>. Ephemeral fixture, generated credentials,
// six refresh cases over the real UI, per-case evidence, exact-scope cleanup.
import { spawn, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RUN_ID = process.argv[2] || '';
const OUT = process.argv[3] || '';
const FRONTEND = (process.env.T11_QA_FRONTEND || 'http://127.0.0.1:4173').replace(/\/$/, '');
const BACKEND = (process.env.T11_QA_BACKEND || 'http://127.0.0.1:18080').replace(/\/$/, '');
const MYSQL_CONTAINER = process.env.T11_QA_MYSQL_CONTAINER || 'campus-booking-mysql-1';
const WAIT_MS = 12000;

if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(RUN_ID)) { console.error('REFUSED RunId'); process.exit(2); }
if (!existsSync(OUT) || !existsSync(path.join(OUT, '.'))) { console.error('REFUSED ArtifactRoot'); process.exit(2); }

const SCREEN_DIR = path.join(OUT, 'screenshots');
const NETWORK_DIR = path.join(OUT, 'network');
mkdirSync(SCREEN_DIR, { recursive: true });
mkdirSync(NETWORK_DIR, { recursive: true });

const userKey = ('t13a_' + RUN_ID.replace(/-/g, '_')).slice(0, 41).replace(/_+$/, '');
const ADMIN = { username: `${userKey}_admin`, realName: 'T13 合同管理员' };
const STUDENT = { username: `${userKey}_student`, realName: 'T13 合同学生' };
for (const u of [ADMIN, STUDENT]) {
  const bytes = new Uint8Array(24); crypto.getRandomValues(bytes);
  u.password = Buffer.from(bytes).toString('hex');
}
const ownershipTag = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, '0')).join('');
const RESOURCE_ID = 1180001;
const APPROVE_ID = 1181001;
const REJECT_ID = 1181002;
const CATEGORY_NAME = 'T13 审批合同分类';
const RESOURCE_NAME = 'T13 审批合同室';
const RESOURCE_DESC = `T13 approval contract room ownership:${ownershipTag}`;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class CaseFailure extends Error {}
const expect = (c, m) => { if (!c) throw new CaseFailure(m); };

function redact(text) {
  return String(text ?? '')
    .replace(/("(?:password|token|accessToken|refreshToken|secret|authorization|apiKey)"\s*:\s*)"[^"]*"/gi, '$1"***"')
    .replace(/(Authorization\s*:\s*)(Bearer|Basic)\s+[A-Za-z0-9._\-]+/gi, '$1$2 ***')
    .slice(0, 20000);
}

function mysql(sql) {
  // One statement per invocation: multi-statement stdin has been observed to
  // silently drop the trailing statement under Windows spawnSync.
  for (const statement of sql.split(';').map((s) => s.trim()).filter(Boolean)) {
    const r = spawnSync('docker', ['exec', '-i', MYSQL_CONTAINER, 'sh', '-c',
      'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" booking_db'], { input: statement + ';', encoding: 'utf8', windowsHide: true });
    if (r.status !== 0) throw new Error(`mysql failed: ${redact(r.stderr)}`);
  }
}
function mysqlScalar(sql) {
  const r = spawnSync('docker', ['exec', '-i', MYSQL_CONTAINER, 'sh', '-c',
    'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --batch --skip-column-names booking_db'], { input: sql, encoding: 'utf8', windowsHide: true });
  if (r.status !== 0) throw new Error(`mysql failed: ${redact(r.stderr)}`);
  return (r.stdout || '').trim();
}

async function api(method, pathname, body, token = null) {
  const headers = { 'content-type': 'application/json' };
  if (token) headers.authorization = `Bearer ${token}`;
  const resp = await fetch(`${BACKEND}${pathname}`, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await resp.text();
  let json = null; try { json = JSON.parse(text); } catch { /* caller reports */ }
  return { status: resp.status, json, text };
}

async function setupFixture() {
  mysql(`
DELETE FROM approval_record WHERE booking_id IN (${APPROVE_ID}, ${REJECT_ID});
DELETE FROM booking_slot WHERE booking_id IN (${APPROVE_ID}, ${REJECT_ID}) OR resource_id=${RESOURCE_ID};
DELETE FROM booking WHERE id IN (${APPROVE_ID}, ${REJECT_ID}) OR resource_id=${RESOURCE_ID};
DELETE FROM resource_time_rule WHERE resource_id=${RESOURCE_ID};
DELETE FROM resource_closure WHERE resource_id=${RESOURCE_ID};
DELETE FROM notification WHERE user_id IN (SELECT id FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\');
DELETE FROM blacklist WHERE user_id IN (SELECT id FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\') OR operator_id IN (SELECT id FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\');
DELETE FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\';
DELETE FROM resource WHERE id=${RESOURCE_ID};
DELETE FROM resource_category WHERE id=${RESOURCE_ID};
`);
  const leftover = mysqlScalar(`SELECT COUNT(*) FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\'; SELECT COUNT(*) FROM resource WHERE id=${RESOURCE_ID};`);
  expect(leftover.split('\n').every((v) => v.trim() === '0'), 'fixture namespace is not empty after scoped cleanup');
  for (const u of [ADMIN, STUDENT]) {
    const reg = await api('POST', '/api/v1/auth/register', {
      username: u.username, password: u.password, realName: u.realName, studentNo: null, phone: null, email: null, avatar: null,
    });
    expect(reg.status === 201 && reg.json?.code === 0, `register ${u.username}: ${reg.status}`);
  }
  mysql(`
UPDATE user SET role='ADMIN' WHERE username='${ADMIN.username}' AND deleted=0;
INSERT INTO resource_category(id,name,parent_id,sort_order,deleted) VALUES (${RESOURCE_ID},'${CATEGORY_NAME}',0,0,0);
INSERT INTO resource(id,category_id,name,location,capacity,description,need_approval,max_advance_days,min_duration_minutes,max_duration_minutes,status,deleted)
VALUES (${RESOURCE_ID},${RESOURCE_ID},'${RESOURCE_NAME}','QA-301',12,'${RESOURCE_DESC}',1,7,30,120,1,0);
INSERT INTO booking(id,booking_no,user_id,resource_id,start_time,end_time,purpose,attendee_count,status,deleted,created_at,updated_at)
SELECT ${APPROVE_ID},'T13AC-APPROVE',id,${RESOURCE_ID},DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 9 HOUR,DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 10 HOUR,'T13 合同批准件',1,'PENDING_APPROVAL',0,NOW()-INTERVAL 2 MINUTE,NOW()-INTERVAL 2 MINUTE
FROM user WHERE username='${STUDENT.username}' AND deleted=0;
INSERT INTO booking(id,booking_no,user_id,resource_id,start_time,end_time,purpose,attendee_count,status,deleted,created_at,updated_at)
SELECT ${REJECT_ID},'T13AC-REJECT',id,${RESOURCE_ID},DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 11 HOUR,DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 12 HOUR,'T13 合同驳回件',1,'PENDING_APPROVAL',0,NOW()-INTERVAL 1 MINUTE,NOW()-INTERVAL 1 MINUTE
FROM user WHERE username='${STUDENT.username}' AND deleted=0;
INSERT INTO booking_slot(resource_id,slot_time,booking_id)
SELECT ${RESOURCE_ID},DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 9 HOUR,id FROM booking WHERE id=${APPROVE_ID};
INSERT INTO booking_slot(resource_id,slot_time,booking_id)
SELECT ${RESOURCE_ID},DATE_ADD(CURDATE(),INTERVAL 1 DAY)+INTERVAL 11 HOUR,id FROM booking WHERE id=${REJECT_ID};
`);
  const admin = await api('POST', '/api/v1/auth/login', { username: ADMIN.username, password: ADMIN.password });
  expect(admin.json?.data?.user?.role === 'ADMIN', 'admin promotion failed');
  return { adminId: String(admin.json.data.user.id) };
}

class Cdp {
  constructor(url) { this.url = url; this.seq = 1; this.pending = new Map(); this.listeners = new Map(); }
  async connect() {
    this.ws = new WebSocket(this.url);
    await new Promise((res, rej) => {
      const t = setTimeout(() => rej(new Error('cdp timeout')), 15000);
      this.ws.addEventListener('open', () => { clearTimeout(t); res(); }, { once: true });
      this.ws.addEventListener('error', () => { clearTimeout(t); rej(new Error('cdp error')); }, { once: true });
    });
    this.ws.addEventListener('message', (e) => {
      let m; try { m = JSON.parse(typeof e.data === 'string' ? e.data : Buffer.from(e.data).toString('utf8')); } catch { return; }
      if (m.id != null) {
        const item = this.pending.get(m.id); if (!item) return;
        this.pending.delete(m.id);
        if (m.error) item.reject(new Error(`CDP ${m.error.message}`)); else item.resolve(m.result);
        return;
      }
      for (const fn of this.listeners.get(m.method) || []) fn(m.params || {}, m.sessionId);
    });
  }
  send(method, params = {}, sessionId = null) {
    const id = this.seq++;
    this.ws.send(JSON.stringify(sessionId ? { id, method, params, sessionId } : { id, method, params }));
    return new Promise((res, rej) => {
      const t = setTimeout(() => { this.pending.delete(id); rej(new Error(`CDP timeout ${method}`)); }, 18000);
      this.pending.set(id, { resolve: (v) => { clearTimeout(t); res(v); }, reject: (e) => { clearTimeout(t); rej(e); } });
    });
  }
  on(name, fn) { const l = this.listeners.get(name) || []; l.push(fn); this.listeners.set(name, l); }
  close() { try { this.ws?.close(); } catch { /* best effort */ } }
}

const CHROME = [
  String.raw`C:\Program Files\Google\Chrome\Application\chrome.exe`,
  String.raw`C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`,
  process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Google/Chrome/Application/chrome.exe') : '',
].filter(Boolean);

const calls = [];
function attach(conn, sid) {
  conn.on('Network.requestWillBeSent', (p, s) => {
    if (s !== sid || !p.request?.url?.includes('/api/v1/')) return;
    calls.push({ at: Date.now(), requestId: p.requestId, method: p.request.method, url: p.request.url, postData: redact(p.request.postData || ''), status: null, body: null });
  });
  conn.on('Network.responseReceived', (p, s) => {
    if (s !== sid) return;
    const c = [...calls].reverse().find((x) => x.requestId === p.requestId);
    if (c) c.status = p.response.status;
  });
  conn.on('Network.loadingFinished', (p, s) => {
    if (s !== sid) return;
    const c = [...calls].reverse().find((x) => x.requestId === p.requestId);
    if (!c) return;
    conn.send('Network.getResponseBody', { requestId: p.requestId }, sid).then((b) => {
      c.body = redact(b?.base64Encoded ? Buffer.from(b.body || '', 'base64').toString('utf8') : b?.body || '');
    }).catch(() => {});
  });
}

const jsSet = (sel, val) => `(function(){
  const el = document.querySelector(${JSON.stringify(sel)});
  if (!el) return false;
  const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
    : el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
  Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(val)});
  el.dispatchEvent(new Event('input', {bubbles: true}));
  el.dispatchEvent(new Event('change', {bubbles: true}));
  return true;
})()`;
const jsClickText = (scope, text) => `(function(){const el=[...document.querySelectorAll(${JSON.stringify(scope)}+' button')].find((n)=>(n.textContent||'').trim().includes(${JSON.stringify(text)}));if(!el)return false;el.click();return true;})()`;
const jsClickExact = (scope, text) => `(function(){
  const el = [...document.querySelectorAll(${JSON.stringify(scope)} + ' button')].find((n) => (n.textContent || '').trim() === ${JSON.stringify(text)});
  if (!el) return false;
  el.click();
  return true;
})()`;

let browser = {};
async function launchBrowser() {
  const exe = CHROME.find(existsSync);
  expect(exe, 'Chrome not found');
  const profile = path.join(os.tmpdir(), `t13appr-chrome-${RUN_ID}`);
  browser.proc = spawn(exe, ['--headless=new', '--remote-debugging-port=0', `--user-data-dir=${profile}`, '--no-first-run', '--disable-gpu', '--window-size=1440,900', 'about:blank'], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const wsUrl = await new Promise((res, rej) => {
    let o = ''; const t = setTimeout(() => rej(new Error('devtools timeout')), 20000);
    const on = (c) => { o += c; const m = /ws:\/\/[^\s]+/.exec(o); if (m) { clearTimeout(t); res(m[0]); } };
    browser.proc.stdout.on('data', on); browser.proc.stderr.on('data', on);
  });
  browser.conn = new Cdp(wsUrl); await browser.conn.connect();
  const { targetId } = await browser.conn.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await browser.conn.send('Target.attachToTarget', { targetId, flatten: true });
  browser.sid = sessionId;
  await Promise.all(['Page.enable', 'Runtime.enable', 'Network.enable'].map((n) => browser.conn.send(n, {}, sessionId)));
  attach(browser.conn, sessionId);
  browser.conn.on('Runtime.consoleAPICalled', (p, s) => {
    if (s !== sessionId) return;
    const text = (p.args || []).map((a) => a.value ?? a.description ?? '').join(' ').slice(0, 500);
    consoleLines.push({ at: Date.now(), type: p.type, text });
  });
}
const consoleLines = [];
async function closeBrowser() {
  browser?.conn?.close();
  if (browser?.proc && browser.proc.exitCode == null) browser.proc.kill();
  await sleep(250);
  if (browser?.proc && browser.proc.exitCode == null && process.platform === 'win32') {
    spawnSync('taskkill', ['/PID', String(browser.proc.pid), '/T', '/F'], { windowsHide: true });
  }
}

async function evaluate(expression) {
  const r = await browser.conn.send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true, userGesture: true }, browser.sid);
  if (r.exceptionDetails) throw new CaseFailure(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
  return r.result?.value;
}
const evalObj = (fn, ...a) => evaluate(`(${fn})(...${JSON.stringify(a)})`);
async function navigate(url) {
  await browser.conn.send('Page.navigate', { url }, browser.sid);
  await waitPage('readyState complete', () => document.readyState === 'complete');
  await sleep(300);
}
async function reload() {
  await browser.conn.send('Page.reload', {}, browser.sid);
  await waitPage('reload complete', () => document.readyState === 'complete');
  await sleep(400);
}
let lastProbeError = '';
async function waitPage(label, fn, timeout = WAIT_MS, ...args) {
  const until = Date.now() + timeout; let v = null;
  while (Date.now() < until) {
    try { v = await evalObj(fn, ...args); } catch (e) { lastProbeError = String(e.message || e).slice(0, 200); v = null; }
    if (v) return v;
    await sleep(150);
  }
  throw new CaseFailure(`timeout ${label}; last=${JSON.stringify(v)}; probeError=${lastProbeError}`);
}
async function waitNode(label, fn, timeout = WAIT_MS) {
  const until = Date.now() + timeout; let v = null;
  while (Date.now() < until) { v = fn(); if (v) return v; await sleep(150); }
  throw new CaseFailure(`timeout ${label}`);
}

async function shot(caseId) {
  const r = await browser.conn.send('Page.captureScreenshot', { format: 'png', fromSurface: true }, browser.sid);
  const file = path.join(SCREEN_DIR, `${caseId}.png`);
  writeFileSync(file, Buffer.from(r.data, 'base64'));
  return `screenshots/${caseId}.png`;
}
function dumpNetwork(caseId, since) {
  const rows = calls.filter((c) => c.at >= since);
  writeFileSync(path.join(NETWORK_DIR, `${caseId}.jsonl`), rows.map((c) => JSON.stringify({
    method: c.method, url: c.url, status: c.status, postData: c.postData, body: c.body,
  })).join('\n'));
  return `network/${caseId}.jsonl`;
}
const observed200 = (since, predicate) => calls.some((c) => c.at >= since && c.method === 'GET' && c.status === 200 && predicate(c.url));

async function formLogin(user) {
  // Land on the app origin first: sessionStorage is inaccessible on about:blank.
  await navigate(`${FRONTEND}/login`);
  await evaluate('sessionStorage.clear(); true');
  await navigate(`${FRONTEND}/login`);
  await waitPage('login inputs', () => !!document.querySelector('#login-username') && !!document.querySelector('#login-password'));
  const fillState = await evalObj(function (uname, pass) {
    const setAndRead = (sel, value) => {
      const el = document.querySelector(sel);
      if (!el) return { sel, found: false };
      const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
        : el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
      el.dispatchEvent(new Event('input', { bubbles: true }));
      el.dispatchEvent(new Event('change', { bubbles: true }));
      return { sel, found: true, inPlaceLen: el.value.length };
    };
    const u = setAndRead('#login-username', uname);
    const p = setAndRead('#login-password', pass);
    return { u, p };
  }, user.username, user.password);
  expect(fillState.u.found && fillState.p.found, `login inputs missing: ${JSON.stringify(fillState)}`);
  expect(fillState.u.inPlaceLen > 0 && fillState.p.inPlaceLen > 0, `in-place set failed: ${JSON.stringify(fillState)}`);
  const filled = await evalObj(function () {
    const u = document.querySelector('#login-username');
    const p = document.querySelector('#login-password');
    return { uLen: u ? u.value.length : -1, pLen: p ? p.value.length : -1 };
  });
  expect(filled.uLen > 0 && filled.pLen > 0, `login fill did not stick: ${JSON.stringify(filled)}`);
  // Dispatch submit directly on the form: robust against native-submit reloads.
  const submitState = await evalObj(function () {
    const f = document.querySelector('form');
    if (!f) return { found: false };
    const ev = new Event('submit', { cancelable: true });
    f.dispatchEvent(ev);
    return { found: true, prevented: ev.defaultPrevented };
  });
  expect(submitState && submitState.found && submitState.prevented, `login form submit not intercepted: ${JSON.stringify(submitState)}`);
  // ADMIN lands on /admin/resources, STUDENT on /resources (Login.vue submit()).
  const target = user === ADMIN ? '/admin/resources' : '/resources';
  await waitPage('login redirect', (t) => location.pathname === t, 30000, target);
  await waitPage('shell render', () => document.body.textContent.includes('退出') || document.body.textContent.includes('资源'));
}

const cases = [];
function record(id, route, shotRel, netRel, sinceReload, apiPred) {
  const apiReloadObserved = observed200(sinceReload, apiPred);
  if (!apiReloadObserved) throw new CaseFailure(`case ${id}: no 200 API reload observed after refresh`);
  cases.push({
    id, status: 'PASS', refreshObserved: true, apiReloadObserved,
    routeAfterRefresh: route, screenshot: shotRel, networkEvidence: netRel,
  });
}

const dialogBtn = (exact) => `(function(){
  const d = document.querySelector('section[role=\"dialog\"]');
  if (!d) return { open: false };
  const btn = [...d.querySelectorAll('button')].find((b) => (b.textContent || '').trim() === ${JSON.stringify(exact)});
  return { open: true, present: !!btn };
})()`;

// The approvals view polls and replaces the dialog target, which resets the
// two-step confirm (armed=false). Arm->confirm is therefore raced with retry
// rounds until the POST is observed on the wire.
async function confirmApprovalViaDialog(rowId, action, comment) {
  const verb = action === 'reject' ? '驳回' : '批准';
  for (let round = 0; round < 6; round++) {
    const opened = await evalObj((want, v) => {
      const row = [...document.querySelectorAll('table.approval-table tbody tr')].find((tr) => tr.querySelector('td').textContent.trim() === want);
      if (!row) return { row: false };
      const btn = [...row.querySelectorAll('button')].find((b) => (b.textContent || '').trim().includes(v));
      if (!btn) return { row: true, btn: false };
      btn.click();
      return { row: true, btn: true };
    }, String(rowId), verb);
    expect(opened.row && opened.btn, `approval row/button missing: ${JSON.stringify(opened)}`);
    await waitPage('dialog opens', (v) => {
      const d = document.querySelector('section[role="dialog"]');
      return !!d && document.body.textContent.includes(v);
    }, WAIT_MS, verb);
    // Atomic arm->confirm inside ONE evaluation: the polling watcher resets
    // `armed` between separate roundtrips, so the 30ms microtask window is the
    // only reliable gap. Retried per round when the watcher wins a race.
    const atomic = await evalObj(async function (v, hasComment, comment) {
      const setVal = (el, value) => {
        const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, value);
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      };
      const d = document.querySelector('section[role="dialog"]');
      if (!d) return { stage: 'no-dialog' };
      const ta = d.querySelector('textarea');
      if (hasComment && ta) setVal(ta, comment);
      const armBtn = [...d.querySelectorAll('button')].find((b) => (b.textContent || '').trim() === `下一步：确认${v}`);
      if (armBtn) armBtn.click();
      await new Promise((r) => setTimeout(r, 40));
      const d2 = document.querySelector('section[role="dialog"]');
      const confirmBtn = d2 && [...d2.querySelectorAll('button')].find((b) => (b.textContent || '').trim() === `确认${v}`);
      if (!confirmBtn) return { stage: 'no-confirm' };
      confirmBtn.click();
      return { stage: 'submitted' };
    }, verb, comment !== null, comment === null ? '' : comment);
    console.log(`round ${round}: atomic=${JSON.stringify(atomic)}`);
    if (atomic.stage !== 'submitted') continue;
    try {
      await waitNode(`${action} POST 200`, () => {
        const c = [...calls].reverse().find((x) => x.at >= Date.now() - 8000 && x.method === 'POST' && x.url.endsWith(`/api/v1/admin/bookings/${rowId}/${action}`));
        return c && c.status === 200;
      }, 6000);
      return;
    } catch (e) { console.log(`round ${round}: confirm clicked but POST unseen: ${String(e.message || e).slice(0, 120)}`); }
  }
  throw new CaseFailure(`approval ${action} did not complete after retries`);
}

async function runCases() {
  // 1) admin-login-refresh
  await formLogin(ADMIN);
  await navigate(`${FRONTEND}/admin/approvals`);
  await waitPage('two pending rows', () => document.querySelectorAll('table.approval-table tbody tr').length === 2);
  let m = Date.now();
  await reload();
  await waitPage('rows after login refresh', () => document.querySelectorAll('table.approval-table tbody tr').length === 2);
  record('admin-login-refresh', '/admin/approvals', await shot('admin-login-refresh'), dumpNetwork('admin-login-refresh', m), m, (u) => u.includes('/api/v1/admin/approvals'));

  // 2) pending-list-refresh
  m = Date.now();
  await reload();
  await waitPage('pending list stable', () => document.querySelectorAll('table.approval-table tbody tr').length === 2);
  record('pending-list-refresh', '/admin/approvals', await shot('pending-list-refresh'), dumpNetwork('pending-list-refresh', m), m, (u) => u.includes('/api/v1/admin/approvals'));

  // 3) approve-refresh
  m = Date.now();
  await confirmApprovalViaDialog(APPROVE_ID, 'approve', null);
  await reload();
  await waitPage('one pending after approve refresh', () => document.querySelectorAll('table.approval-table tbody tr').length === 1);
  expect(await evalObj((id) => ![...document.querySelectorAll('table.approval-table tbody tr')].some((tr) => tr.querySelector('td').textContent.trim() === id), String(APPROVE_ID)), 'approved booking still pending after refresh');
  record('approve-refresh', '/admin/approvals', await shot('approve-refresh'), dumpNetwork('approve-refresh', m), m, (u) => u.includes('/api/v1/admin/approvals'));

  // 4) reject-refresh
  m = Date.now();
  await confirmApprovalViaDialog(REJECT_ID, 'reject', '  T13 合同驳回原因  ');
  await reload();
  await waitPage('pending emptied after reject refresh', () => document.body.textContent.includes('暂无待审批预约'));
  record('reject-refresh', '/admin/approvals', await shot('reject-refresh'), dumpNetwork('reject-refresh', m), m, (u) => u.includes('/api/v1/admin/approvals'));

  // 5) student-approved-detail-refresh
  await formLogin(STUDENT);
  await navigate(`${FRONTEND}/bookings/${APPROVE_ID}`);
  await waitPage('approved detail renders', () => document.querySelectorAll('.el-descriptions__label').length >= 14 && document.body.textContent.includes('已确认'));
  m = Date.now();
  await reload();
  await waitPage('approved detail persists', () => document.querySelectorAll('.el-descriptions__label').length >= 14 && document.body.textContent.includes('已确认'));
  record('student-approved-detail-refresh', `/bookings/${APPROVE_ID}`, await shot('student-approved-detail-refresh'), dumpNetwork('student-approved-detail-refresh', m), m, (u) => u.includes(`/api/v1/bookings/${APPROVE_ID}`));

  // 6) student-rejected-detail-refresh
  await navigate(`${FRONTEND}/bookings/${REJECT_ID}`);
  await waitPage('rejected detail renders', () => document.querySelectorAll('.el-descriptions__label').length >= 14 && document.body.textContent.includes('已驳回'));
  m = Date.now();
  await reload();
  await waitPage('rejected detail persists', () => document.querySelectorAll('.el-descriptions__label').length >= 14 && document.body.textContent.includes('已驳回'));
  record('student-rejected-detail-refresh', `/bookings/${REJECT_ID}`, await shot('student-rejected-detail-refresh'), dumpNetwork('student-rejected-detail-refresh', m), m, (u) => u.includes(`/api/v1/bookings/${REJECT_ID}`));
}

function writeManifest(cleanupPerformed, cleanupStatus) {
  const manifest = {
    schemaVersion: 1,
    runId: RUN_ID,
    cleanup: { performed: cleanupPerformed, status: cleanupStatus },
    cases,
  };
  writeFileSync(path.join(OUT, 'approval-evidence.json'), JSON.stringify(manifest, null, 2));
}

function cleanupFixture() {
  mysql(`
DELETE FROM approval_record WHERE booking_id IN (${APPROVE_ID}, ${REJECT_ID});
DELETE FROM booking_slot WHERE booking_id IN (${APPROVE_ID}, ${REJECT_ID}) OR resource_id=${RESOURCE_ID};
DELETE FROM booking WHERE id IN (${APPROVE_ID}, ${REJECT_ID}) OR resource_id=${RESOURCE_ID};
DELETE FROM resource_time_rule WHERE resource_id=${RESOURCE_ID};
DELETE FROM resource_closure WHERE resource_id=${RESOURCE_ID};
DELETE FROM resource WHERE id=${RESOURCE_ID} AND description='${RESOURCE_DESC}';
DELETE FROM resource_category WHERE id=${RESOURCE_ID} AND name='${CATEGORY_NAME}';
DELETE FROM notification WHERE user_id IN (SELECT id FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\');
DELETE FROM blacklist WHERE user_id IN (SELECT id FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\') OR operator_id IN (SELECT id FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\');
DELETE FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\';
`);
  const left = mysqlScalar(`
SELECT COUNT(*) FROM user WHERE username LIKE '${userKey}\\_%' ESCAPE '\\\\';
SELECT COUNT(*) FROM resource WHERE id=${RESOURCE_ID};
SELECT COUNT(*) FROM resource_category WHERE id=${RESOURCE_ID};
SELECT COUNT(*) FROM booking WHERE resource_id=${RESOURCE_ID};
SELECT COUNT(*) FROM booking_slot WHERE resource_id=${RESOURCE_ID};
SELECT COUNT(*) FROM approval_record WHERE booking_id IN (${APPROVE_ID}, ${REJECT_ID});
`);
  const allZero = left.split('\n').map((v) => v.trim()).every((v) => v === '0');
  if (!allZero) throw new Error(`cleanup left rows: ${left}`);

}

function cleanupFixtureWithRetry(attempts = 3) {
  let lastError = null;
  for (let i = 0; i < attempts; i++) {
    try { cleanupFixture(); return; } catch (e) { lastError = e; sleep(1500); }
  }
  throw lastError;
}

const health = await api('GET', '/actuator/health').catch(() => null);
const frontProbe = await fetch(`${FRONTEND}/`).catch(() => null);
if (health?.status !== 200 || frontProbe?.status !== 200) {
  console.error(`GATES_DOWN frontend=${frontProbe?.status || 'down'} backend=${health?.status || 'down'}`);
  process.exit(2);
}

let exitCode = 0;
let cleanupPerformed = false;
let cleanupStatus = 'FAIL';
try {
  setupFixture();
  await launchBrowser();
  await runCases();
} catch (error) {
  try {
    writeFileSync(path.join(OUT, 'debug-on-failure.json'), JSON.stringify({
      error: String(error.message || error).slice(0, 500),
      path: await evaluate('location.pathname').catch(() => 'n/a'),
      bodySnippet: (await evaluate('document.body.textContent').catch(() => '') || '').slice(0, 300),
      alertText: await evaluate(`(function(){ const e = document.querySelector('#login-error'); return e ? e.textContent : null; })()`).catch(() => null),
      dialogHtml: await evaluate(`(function(){ const d = document.querySelector('section[role="dialog"]'); return d ? d.outerHTML.slice(0, 1200) : null; })()`).catch(() => null),
      dialogButtons: await evaluate(`(function(){ const d = document.querySelector('section[role="dialog"]'); return d ? [...d.querySelectorAll('button')].map((b) => ({ t: (b.textContent||'').trim(), disabled: b.disabled, vis: !!b.offsetParent })) : null; })()`).catch(() => null),
      inputLens: await evaluate(`(function(){ const u=document.querySelector('#login-username'); const p=document.querySelector('#login-password'); return { u: u?u.value.length:-1, p: p?p.value.length:-1 }; })()`).catch(() => null),
      consoleTail: consoleLines.slice(-10),
      formState: await evaluate(`(function(){
        const f = document.querySelector('form');
        const u = document.querySelector('#login-username');
        const p = document.querySelector('#login-password');
        const btns = [...document.querySelectorAll('button')].map((b) => ({ text: (b.textContent||'').trim(), type: b.type, disabled: b.disabled }));
        return { hasForm: !!f, userLen: u ? u.value.length : -1, passLen: p ? p.value.length : -1, btns };
      })()`).catch((e) => String(e).slice(0, 200)),
      calls: calls.slice(-25),
    }, null, 2));
    await evaluate('true').catch(() => {});
  } catch { /* diagnostics best effort */ }
  console.error(`CASE_FAILURE: ${String(error.message || error).slice(0, 500)}`);
  exitCode = 1;
} finally {
  await closeBrowser();
  try {
    cleanupFixtureWithRetry();
    cleanupPerformed = true;
    cleanupStatus = 'PASS';
  } catch (e) {
    console.error(`CLEANUP_FAILURE: ${String(e.message || e).slice(0, 300)}`);
    cleanupPerformed = true;
    cleanupStatus = 'FAIL';
    exitCode = exitCode || 2;
  }
  writeManifest(cleanupPerformed, cleanupStatus);
  try { rmSync(path.join(os.tmpdir(), `t13appr-chrome-${RUN_ID}`), { recursive: true, force: true }); } catch { /* best effort */ }
}
console.log(`T13 APPROVAL CONTRACT RUNNER DONE cases=${cases.length} cleanup=${cleanupStatus}`);
process.exit(exitCode);
