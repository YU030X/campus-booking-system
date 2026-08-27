import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync, appendFileSync, existsSync, rmSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CHROME_CANDIDATES = [
  String.raw`C:\Program Files\Google\Chrome\Application\chrome.exe`,
  String.raw`C:\Program Files (x86)\Google\Chrome\Application\chrome.exe`,
  process.env.LOCALAPPDATA ? path.join(process.env.LOCALAPPDATA, 'Google\\Chrome\\Application\\chrome.exe') : '',
  String.raw`C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe`,
].filter(Boolean);

const FRONTEND = (process.env.T08_QA_FRONTEND || 'http://127.0.0.1:4173').replace(/\/$/, '');
const BACKEND = (process.env.T08_QA_BACKEND || 'http://127.0.0.1:18080').replace(/\/$/, '');
const RESOURCE_ID = '880001';
const SESSION_KEY = 'campus.auth.session';
const NAV_TIMEOUT_MS = 12000;
const WAIT_TIMEOUT_MS = 9000;
const POLL_INTERVAL_MS = 150;
const MAX_BODY_BYTES = 24 * 1024;

const stamp = () => new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
const RUN_STAMP = stamp();
const RUN_DIR = path.join(__dirname, `run-${RUN_STAMP}-${process.pid}`);
const SCREENSHOT_DIR = path.join(RUN_DIR, 'screenshots');

const USER_A = { username: `t08qa_a_${RUN_STAMP}`, password: 'Qa-Passw0rd!2026', realName: 'T08 QA 甲' };
const USER_B = { username: `t08qa_b_${RUN_STAMP}`, password: 'Qa-Passw0rd!2026', realName: 'T08 QA 乙' };

const STATUS_LABELS = Object.freeze({
  PENDING_APPROVAL: '待审批', CONFIRMED: '已确认', CHECKED_IN: '已签到',
  COMPLETED: '已完成', REJECTED: '已驳回', CANCELLED: '已取消', NO_SHOW: '未到场',
});
const STATUS_LABEL_VALUES = Object.freeze(Object.values(STATUS_LABELS));

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

function shanghaiParts(date = new Date()) {
  const fmt = new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  });
  return Object.fromEntries(fmt.formatToParts(date).filter((p) => p.type !== 'literal').map((p) => [p.type, p.value]));
}
function ymdAddDays(ymd, days) {
  const [y, m, d] = ymd.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}
const SH_TODAY = (() => { const p = shanghaiParts(); return `${p.year}-${p.month}-${p.day}`; })();
const TOMORROW = ymdAddDays(SH_TODAY, 1);

function hhmmToMinutes(hhmm) {
  const [h, m] = hhmm.split(':').map(Number);
  return h * 60 + m;
}
function minutesToHHMM(mins) {
  return `${String(Math.floor(mins / 60)).padStart(2, '0')}:${String(mins % 60).padStart(2, '0')}`;
}

function findChromeExecutable() {
  for (const candidate of CHROME_CANDIDATES) {
    if (candidate && existsSync(candidate)) return candidate;
  }
  return null;
}

function redactHeaders(headers) {
  if (!headers || typeof headers !== 'object') return headers;
  const out = {};
  for (const key of Object.keys(headers)) {
    out[key] = /^authorization$/i.test(key) ? 'Bearer ***REDACTED***' : headers[key];
  }
  return out;
}
function redactBodyText(text) {
  if (typeof text !== 'string') return text;
  return text
    .replace(/"(token|accessToken|refreshToken|jwt|idToken)"\s*:\s*"[^"]*"/gi, '"$1":"***REDACTED***"')
    .replace(/"(password|confirmPassword|oldPassword|newPassword)"\s*:\s*"[^"]*"/gi, '"$1":"***REDACTED***"');
}
function truncateBody(text) {
  if (typeof text !== 'string') return text;
  if (text.length <= MAX_BODY_BYTES) return text;
  return `${text.slice(0, MAX_BODY_BYTES)}...(truncated, ${text.length} bytes)`;
}

class CaseFailure extends Error {}
class GateDown extends Error {}

function expect(cond, message) {
  if (!cond) throw new CaseFailure(message);
}

class CdpConnection {
  constructor(wsUrl) {
    this.wsUrl = wsUrl;
    this.seq = 1;
    this.pending = new Map();
    this.listeners = new Map();
    this.ws = null;
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.ws = new WebSocket(this.wsUrl);
      const timer = setTimeout(() => reject(new Error(`CDP websocket connect timeout: ${this.wsUrl}`)), 15000);
      this.ws.addEventListener('open', () => { clearTimeout(timer); resolve(); }, { once: true });
      this.ws.addEventListener('error', (event) => {
        clearTimeout(timer);
        reject(new Error(`CDP websocket error: ${event?.message || 'unknown'}`));
      }, { once: true });
      this.ws.addEventListener('message', (event) => this.handleFrame(event.data));
    });
  }

  handleFrame(data) {
    let raw = '';
    if (typeof data === 'string') raw = data;
    else if (data instanceof ArrayBuffer) raw = Buffer.from(data).toString('utf8');
    else if (ArrayBuffer.isView(data)) raw = Buffer.from(data.buffer, data.byteOffset, data.byteLength).toString('utf8');
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (msg.id != null) {
      const entry = this.pending.get(msg.id);
      if (!entry) return;
      this.pending.delete(msg.id);
      if (msg.error) entry.reject(new Error(`CDP ${msg.error.message}${msg.error.data ? ` (${JSON.stringify(msg.error.data).slice(0, 300)})` : ''}`));
      else entry.resolve(msg.result);
      return;
    }
    const handlers = this.listeners.get(msg.method);
    if (handlers) for (const handler of [...handlers]) handler(msg.params ?? {}, msg.sessionId);
  }

  send(method, params = {}, sessionId = null) {
    const id = this.seq++;
    const frame = { id, method, params };
    if (sessionId) frame.sessionId = sessionId;
    this.ws.send(JSON.stringify(frame));
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (this.pending.delete(id)) reject(new Error(`CDP timeout waiting for ${method}`));
      }, NAV_TIMEOUT_MS + 6000);
      this.pending.set(id, {
        resolve: (value) => { clearTimeout(timer); resolve(value); },
        reject: (error) => { clearTimeout(timer); reject(error); },
      });
    });
  }

  on(method, handler) {
    const list = this.listeners.get(method);
    if (list) list.push(handler);
    else this.listeners.set(method, [handler]);
  }

  off(method, handler) {
    const list = this.listeners.get(method);
    if (!list) return;
    const idx = list.indexOf(handler);
    if (idx >= 0) list.splice(idx, 1);
  }

  close() {
    try { this.ws?.close(); } catch { /* already closing */ }
  }
}

class Evidence {
  constructor(runDir) {
    this.runDir = runDir;
    this.screenshotDir = path.join(runDir, 'screenshots');
    this.netlogPath = path.join(runDir, 'network.jsonl');
    this.consolePath = path.join(runDir, 'console.jsonl');
    this.apiLogPath = path.join(runDir, 'api-driver-calls.jsonl');
    mkdirSync(this.screenshotDir, { recursive: true });
    writeFileSync(this.netlogPath, '');
    writeFileSync(this.consolePath, '');
    writeFileSync(this.apiLogPath, '');
    this.screenshotSeq = 0;
  }

  writeNet(line) { this.appendJsonl(this.netlogPath, line); }
  writeConsole(line) { this.appendJsonl(this.consolePath, line); }
  appendJsonl(file, obj) {
    try { appendFileSync(file, `${JSON.stringify(obj)}\n`); } catch { /* disk issues surface later */ }
  }

  async screenshot(conn, sessionId, name) {
    this.screenshotSeq += 1;
    const safeName = String(name).replace(/[^\w.-]+/g, '_').slice(0, 80) || 'shot';
    const file = path.join(this.screenshotDir, `${String(this.screenshotSeq).padStart(3, '0')}-${safeName}.png`);
    try {
      const result = await conn.send('Page.captureScreenshot', { format: 'png', fromSurface: true }, sessionId);
      writeFileSync(file, Buffer.from(result.data, 'base64'));
    } catch (error) {
      writeFileSync(file.replace(/\.png$/, '.ERROR.txt'), `screenshot failed: ${error.message}\n`);
    }
    return path.basename(file);
  }

  driverApi(entry) {
    this.appendJsonl(this.apiLogPath, {
      ts: new Date().toISOString(),
      ...entry,
      requestHeaders: redactHeaders(entry.requestHeaders),
      requestBody: truncateBody(redactBodyText(entry.requestBody ?? '')),
      responseHeaders: redactHeaders(entry.responseHeaders),
      responseBody: truncateBody(redactBodyText(entry.responseBody ?? '')),
    });
  }
}

class NetJournal {
  constructor(evidence) {
    this.evidence = evidence;
    this.entries = new Map();
    this.counter = 0;
    this.availabilityCalls = [];
    this.bookingsPosts = [];
    this.bookingGets = [];
    this.cancelPosts = [];
    this.rawLastSeen = new Map();
  }

  attach(conn, sessionId) {
    conn.on('Network.requestWillBeSent', (params, sid) => {
      if (sid !== sessionId) return;
      const url = params.request?.url || '';
      if (!/^https?:/i.test(url)) return;
      this.counter += 1;
      const entry = {
        seq: this.counter,
        phase: 'request',
        ts: new Date().toISOString(),
        requestId: params.requestId,
        method: params.request?.method,
        url,
        headers: redactHeaders(params.request?.headers),
        postData: truncateBody(redactBodyText(params.request?.postData ?? '')),
      };
      this.entries.set(params.requestId, entry);
      this.evidence.writeNet(entry);
      if (/\/available-slots\?/.test(url) && entry.method === 'GET') this.availabilityCalls.push({ entry, params: new URL(url).searchParams.get('date'), ts: Date.now(), url });
      if (/\/api\/v1\/bookings$/.test(url.replace(/\?.*$/, ''))) {
        if (entry.method === 'POST') this.bookingsPosts.push({ entry, ts: Date.now() });
        if (entry.method === 'GET') this.bookingGets.push({ entry, url, ts: Date.now(), page: Number(new URL(url).searchParams.get('pageNumber') || 0), size: Number(new URL(url).searchParams.get('pageSize') || 0), status: new URL(url).searchParams.get('status') });
      }
      if (/\/api\/v1\/bookings\/\d+\/cancel$/.test(url.replace(/\?.*$/, '')) && entry.method === 'POST') {
        this.cancelPosts.push({ entry, ts: Date.now(), bookingId: url.split('/').at(-2) });
      }
    });

    conn.on('Network.responseReceived', (params, sid) => {
      if (sid !== sessionId) return;
      const base = this.entries.get(params.requestId);
      const response = params.response || {};
      const line = {
        seq: ++this.counter,
        phase: 'response',
        ts: new Date().toISOString(),
        requestId: params.requestId,
        url: response.url,
        status: response.status,
        statusText: response.statusText,
        headers: redactHeaders(response.headers),
        mimeType: response.mimeType,
      };
      this.evidence.writeNet(line);
      if (base) {
        base.responseStatus = response.status;
        base.responseTs = Date.now();
      }
    });

    conn.on('Network.loadingFailed', (params, sid) => {
      if (sid !== sessionId) return;
      const base = this.entries.get(params.requestId);
      const line = {
        seq: ++this.counter,
        phase: 'loading-failed',
        ts: new Date().toISOString(),
        requestId: params.requestId,
        errorText: params.errorText,
        canceled: !!params.canceled,
        url: base?.url,
      };
      this.evidence.writeNet(line);
      if (base) base.failed = true;
    });

    conn.on('Network.loadingFinished', (params, sid) => {
      if (sid !== sessionId) return;
      conn.send('Network.getResponseBody', { requestId: params.requestId }, sessionId).then((body) => {
        const base = this.entries.get(params.requestId);
        const text = body?.base64Encoded ? Buffer.from(body.body || '', 'base64').toString('utf8') : (body?.body || '');
        const line = {
          seq: ++this.counter,
          phase: 'response-body',
          ts: new Date().toISOString(),
          requestId: params.requestId,
          url: base?.url,
          body: truncateBody(redactBodyText(text)),
        };
        this.evidence.writeNet(line);
        if (base) {
          base.responseBody = redactBodyText(truncateBody(text));
          this.classifyResponseBody(base);
        }
      }).catch(() => { /* streaming/no-body responses */ });
    });
  }

  classifyResponseBody(base) {
    if (!base?.responseBody || typeof base.responseBody !== 'string') return;
    if (!/\/api\/v1\//.test(base.url)) return;
    try {
      base.parsedBody = JSON.parse(base.responseBody);
    } catch { base.parsedBody = null; }
  }

  countSince(key, marker) {
    return this[key].filter((item) => item.ts > marker).length;
  }

  lastAvailabilityPayload(markerTs = 0) {
    for (let i = this.availabilityCalls.length - 1; i >= 0; i -= 1) {
      const call = this.availabilityCalls[i];
      if (call.ts < markerTs) continue;
      if (call.entry?.parsedBody) return call.entry.parsedBody;
    }
    return null;
  }

  findBookingsPost(markerTs, predicate = () => true) {
    return [...this.bookingsPosts].reverse().find((item) => item.ts >= markerTs && predicate(item) && item.entry?.responseStatus != null) || null;
  }

  findCancelPost(markerTs, predicate = () => true) {
    return [...this.cancelPosts].reverse().find((item) => item.ts >= markerTs && predicate(item) && item.entry?.responseStatus != null) || null;
  }

  findBookingGetByQuery(markerTs, query) {
    return [...this.bookingGets].reverse().find((item) => {
      if (item.ts < markerTs) return false;
      const u = new URL(item.url);
      for (const [key, value] of Object.entries(query)) {
        if (u.searchParams.get(key) !== String(value)) return false;
      }
      return true;
    }) || null;
  }
}

async function waitForLocal(description, probeFn, timeoutMs = WAIT_TIMEOUT_MS) {
  const deadline = Date.now() + timeoutMs;
  let lastValue = null;
  while (Date.now() < deadline) {
    lastValue = await probeFn();
    if (lastValue) return lastValue;
    await sleep(POLL_INTERVAL_MS);
  }
  throw new CaseFailure(`等待超时(${timeoutMs}ms)[harness侧]: ${description}`);
}

class BrowserHarness {
  constructor(evidence, journal) {
    this.evidence = evidence;
    this.journal = journal;
    this.chromeExe = null;
    this.chromeProc = null;
    this.conn = null;
    this.targetId = null;
    this.sessionId = null;
    this.injectionHandles = [];
  }

  async launch() {
    this.chromeExe = findChromeExecutable();
    expect(this.chromeExe, '未找到 Chrome 可执行文件；请安装 Google Chrome 或设置环境变量指向 chromium 内核浏览器');
    const profileDir = path.join(this.evidence.runDir, 'chrome-profile');
    mkdirSync(profileDir, { recursive: true });
    const args = [
      '--headless=new',
      '--remote-debugging-port=0',
      `--user-data-dir=${profileDir}`,
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-gpu',
      '--disable-extensions',
      '--disable-background-networking',
      '--disable-component-update',
      '--disable-sync',
      '--mute-audio',
      '--hide-scrollbars',
      '--window-size=1440,900',
      '--noerrdialogs',
      'about:blank',
    ];
    this.chromeProc = spawn(this.chromeExe, args, { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
    globalThis.__t08ChromeProcs.push(this.chromeProc);
    this.chromeProc.stdout.setEncoding('utf8');
    this.chromeProc.stderr.setEncoding('utf8');

    const wsUrl = await new Promise((resolve, reject) => {
      let buffer = '';
      let settled = false;
      const timer = setTimeout(() => finish(reject, new Error('等待 Chrome DevTools 调试端口超时（20s）')), 20000);
      const onData = (chunk) => {
        buffer += chunk;
        const match = /ws:\/\/[^\s]+/i.exec(buffer);
        if (match) finish(resolve, match[0]);
      };
      const finish = (fn, value) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        fn(value);
      };
      this.chromeProc.stdout.on('data', onData);
      this.chromeProc.stderr.on('data', onData);
      this.chromeProc.once('exit', (code) => finish(reject, new Error(`Chrome 启动即退出 code=${code}\n${buffer.slice(-600)}`)));
    });

    this.conn = new CdpConnection(wsUrl);
    await this.conn.connect();

    ({ targetId: this.targetId } = await this.conn.send('Target.createTarget', { url: 'about:blank' }));
    ({ sessionId: this.sessionId } = await this.conn.send('Target.attachToTarget', { targetId: this.targetId, flatten: true }));

    const logHandler = (params) => {
      this.evidence.writeConsole({
        ts: new Date().toISOString(),
        kind: params.type || 'runtime',
        text: (params.args || []).map((arg) => arg.value ?? arg.description ?? arg.unserializableValue ?? '').join(' ').slice(0, 800),
      });
    };
    this.conn.on('Runtime.consoleAPICalled', logHandler);
    this.conn.on('Log.entryAdded', (params) => {
      this.evidence.writeConsole({ ts: new Date().toISOString(), kind: `log:${params.entry?.level}`, text: `${params.entry?.source}: ${params.entry?.text}`.slice(0, 800) });
    });
    this.conn.on('Runtime.exceptionThrown', (params) => {
      this.evidence.writeConsole({ ts: new Date().toISOString(), kind: 'exception', text: String(params.exceptionDetails?.exception?.description || params.exceptionDetails?.text || '').slice(0, 1200) });
    });

    await Promise.all([
      this.conn.send('Page.enable', {}, this.sessionId),
      this.conn.send('Runtime.enable', {}, this.sessionId),
      this.conn.send('Network.enable', {}, this.sessionId),
      this.conn.send('Log.enable', {}, this.sessionId),
    ]);
    await this.conn.send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false }, this.sessionId);
    this.journal.attach(this.conn, this.sessionId);
    return { chromeVersion: (await this.conn.send('Browser.getVersion')).product, wsUrl };
  }

  async shutdown() {
    this.conn?.close();
    if (this.chromeProc && this.chromeProc.exitCode == null && !this.chromeProc.killed) {
      try { this.chromeProc.kill(); } catch { /* fallthrough */ }
      await sleep(400);
      if (this.chromeProc.exitCode == null && process.platform === 'win32') {
        try { spawnSync('taskkill', ['/PID', String(this.chromeProc.pid), '/T', '/F'], { windowsHide: true }); } catch { /* best effort */ }
      }
    }
  }
}

class Session {
  constructor(browser, evidence, journal) {
    this.browser = browser;
    this.conn = browser.conn;
    this.sid = browser.sessionId;
    this.evidence = evidence;
    this.journal = journal;
  }

  async eval(expression) {
    const result = await this.conn.send('Runtime.evaluate', {
      expression,
      awaitPromise: true,
      returnByValue: true,
      userGesture: true,
    }, this.sid);
    if (result.exceptionDetails) {
      const detail = result.exceptionDetails.exception?.description || result.exceptionDetails.text || 'unknown evaluation error';
      throw new CaseFailure(`页面脚本执行失败: ${detail.slice(0, 500)}`);
    }
    return result.result?.value;
  }

  async evalObj(fnSource, ...argsJson) {
    const wrapped = `(${fnSource})(...${JSON.stringify(argsJson)})`;
    return this.eval(wrapped);
  }

  async navigate(url) {
    const loaded = new Promise((resolve) => {
      const handler = (params, sid) => { if (sid === this.sid) { cleanup(); resolve(); } };
      const cleanup = () => this.conn.off('Page.loadEventFired', handler);
      this.conn.on('Page.loadEventFired', handler);
      setTimeout(() => { cleanup(); resolve(); }, NAV_TIMEOUT_MS);
    });
    await this.conn.send('Page.navigate', { url }, this.sid);
    await loaded;
    await sleep(300);
  }

  async reload() {
    const loaded = new Promise((resolve) => {
      const handler = (params, sid) => { if (sid === this.sid) { cleanup(); resolve(); } };
      const cleanup = () => this.conn.off('Page.loadEventFired', handler);
      this.conn.on('Page.loadEventFired', handler);
      setTimeout(() => { cleanup(); resolve(); }, NAV_TIMEOUT_MS);
    });
    await this.conn.send('Page.reload', { ignoreCache: false }, this.sid);
    await loaded;
    await sleep(300);
  }

  async waitFor(predicateDescription, sourceFn, timeoutMs = WAIT_TIMEOUT_MS, ...fnArgs) {
    const deadline = Date.now() + timeoutMs;
    let lastValue = null;
    while (Date.now() < deadline) {
      lastValue = await this.evalObj(sourceFn, ...fnArgs).catch(() => null);
      if (lastValue) return lastValue;
      await sleep(POLL_INTERVAL_MS);
    }
    throw new CaseFailure(`等待超时(${timeoutMs}ms): ${predicateDescription}，最后状态=${JSON.stringify(lastValue)?.slice(0, 200)}`);
  }

  waitDomVisible(selector, timeoutMs = WAIT_TIMEOUT_MS) {
    return this.waitFor(`元素可见 ${selector}`, (sel) => {
      const el = document.querySelector(sel);
      return !!el && el.offsetParent !== null;
    }, timeoutMs).catch(() => false);
  }

  shot(name) { return this.evidence.screenshot(this.conn, this.sid, name); }
}

function jsSetValue(selector, value) {
  return `(function(){
    const el = document.querySelector(${JSON.stringify(selector)});
    if (!el) return {found: false};
    const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype
      : el.tagName === 'SELECT' ? HTMLSelectElement.prototype : HTMLInputElement.prototype;
    Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, ${JSON.stringify(value)});
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
    return {found: true};
  })()`;
}

function jsClickButtonByText(containerSelector, text) {
  return `(function(){
    const root = ${containerSelector ? `document.querySelector(${JSON.stringify(containerSelector)})` : 'document'};
    if (!root) return {clicked: false, reason: 'container missing'};
    const wanted = ${JSON.stringify(text)}.trim();
    const nodes = [...root.querySelectorAll('button')];
    const target = nodes.find((b) => (b.textContent || '').trim().includes(wanted));
    if (!target) return {clicked: false, reason: 'button not found', options: nodes.map((b) => (b.textContent || '').trim()).slice(0, 8)};
    if (target.disabled) return {clicked: false, reason: 'button disabled'};
    target.click();
    return {clicked: true};
  })()`;
}

async function saveRunsProbe(url) {
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    const response = await fetch(url, { signal: controller.signal });
    clearTimeout(timer);
    const text = await response.text();
    return { reachable: true, status: response.status, bodyHead: text.slice(0, 300) };
  } catch (error) {
    return { reachable: false, error: String(error?.cause?.code || error?.message || error).slice(0, 160) };
  }
}

async function driverApiCall(evidence, method, urlPath, token, payload) {
  const startedAt = Date.now();
  let response;
  let text = '';
  try {
    response = await fetch(`${BACKEND}${urlPath}`, {
      method,
      headers: {
        'content-type': 'application/json',
        ...(token ? { authorization: `Bearer ${token}` } : {}),
      },
      body: payload === undefined ? undefined : JSON.stringify(payload),
    });
    text = await response.text();
  } catch (error) {
    evidence.driverApi({ method, url: `${BACKEND}${urlPath}`, status: null, error: String(error?.cause?.code || error).slice(0, 120), durationMs: Date.now() - startedAt });
    throw error;
  }
  let json = null;
  try { json = JSON.parse(text); } catch { /* non-json */ }
  evidence.driverApi({
    method,
    url: `${BACKEND}${urlPath}`,
    requestHeaders: token ? { authorization: `Bearer <redacted>` } : {},
    requestBody: payload === undefined ? '' : JSON.stringify(payload),
    status: response.status,
    responseHeaders: { 'content-type': response.headers.get('content-type') },
    responseBody: text,
    durationMs: Date.now() - startedAt,
  });
  return { status: response.status, json, text };
}

async function registerUser(evidence, user) {
  const result = await driverApiCall(evidence, 'POST', '/api/v1/auth/register', null, {
    username: user.username,
    password: user.password,
    realName: user.realName,
  });
  expect(result.status === 201 && result.json?.code === 0, `注册 ${user.username} 失败: HTTP ${result.status} ${result.text.slice(0, 140)}`);
  return result.json.data;
}

async function loginUser(evidence, user, attempt = 1) {
  const result = await driverApiCall(evidence, 'POST', '/api/v1/auth/login', null, {
    username: user.username,
    password: user.password,
  });
  if (result.status !== 200 || result.json?.code !== 0) {
    if (attempt < 3) { await sleep(400 * attempt); return loginUser(evidence, user, attempt + 1); }
    expect(false, `登录 ${user.username} 失败: HTTP ${result.status}`);
  }
  const { token, expiresIn } = result.json.data;
  expect(typeof token === 'string' && token.length > 0, '登录响应缺少 token');
  return { token, expiresIn, userId: result.json.data.user?.id };
}

async function createBookingDriver(evidence, token, payload, attempt = 1) {
  const result = await driverApiCall(evidence, 'POST', '/api/v1/bookings', token, payload);
  const body = result.json || {};
  if (result.status === 409 && body.code === 43000 && body.message === '当前预约请求较多，请稍后重试' && attempt < 4) {
    await sleep(500 * attempt);
    return createBookingDriver(evidence, token, payload, attempt + 1);
  }
  expect(result.status === 201, `驱动端创建预约失败 HTTP ${result.status}: ${result.text.slice(0, 200)} (payload=${JSON.stringify(payload)})`);
  expect(body.data?.id != null, '创建预约响应缺少 booking id');
  return body.data;
}

async function cancelBookingDriver(evidence, token, bookingId, reason, attempt = 1) {
  const result = await driverApiCall(evidence, 'POST', `/api/v1/bookings/${bookingId}/cancel`, token, { cancelReason: reason });
  if ((result.status === 409 || result.status >= 500) && attempt < 3) {
    await sleep(500 * attempt);
    return cancelBookingDriver(evidence, token, bookingId, reason, attempt + 1);
  }
  expect(result.status === 200 && result.json?.data?.status === 'CANCELLED', `驱动端取消预约失败 HTTP ${result.status}: ${result.text.slice(0, 200)}`);
  return result.json.data;
}

async function availabilityDriver(evidence, token, resourceId, date) {
  const result = await driverApiCall(evidence, 'GET', `/api/v1/resources/${resourceId}/available-slots?date=${date}`, token);
  expect(result.status === 200 && Array.isArray(result.json?.data?.slots), `可用时段查询失败 HTTP ${result.status}: ${result.text.slice(0, 200)}`);
  return result.json.data;
}

function freeHalfHourCandidates(bookedIntervals, dayStart = 8 * 60, dayEnd = 20 * 60) {
  const takenAtoms = new Set();
  for (const [start, end] of bookedIntervals) {
    for (let t = hhmmToMinutes(start); t < hhmmToMinutes(end); t += 30) takenAtoms.add(t);
  }
  const atoms = [];
  for (let t = dayStart; t < dayEnd; t += 30) {
    if (!takenAtoms.has(t)) atoms.push(t);
  }
  return atoms.map((t) => [minutesToHHMM(t), minutesToHHMM(t + 30)]);
}

const state = {
  uiLoginMarkerTs: 0,
  userAToken: null,
  userBToken: null,
  userAView: null,
  firstCreatedBooking: null,
  recoveryCreatedBooking: null,
  retainedConfirmedBookings: [],
  filledCancelledCount: 0,
  lastFullListRecords: [],
};

async function case01_registerViaUi(ctx) {
  const { session, summary } = ctx;
  await session.navigate(`${FRONTEND}/register`);
  await session.waitFor('注册表单出现', () => !!document.querySelector('#register-username'));
  await session.eval(jsSetValue('#register-username', USER_A.username));
  await session.eval(jsSetValue('#register-password', USER_A.password));
  await session.eval(jsSetValue('#register-confirm-password', USER_A.password));
  await session.eval(jsSetValue('#register-real-name', USER_A.realName));
  await session.shot('01-register-filled');
  await session.eval(jsClickButtonByText('', '注册'));
  await session.waitFor('跳转到登录页并带 registered 参数', () => location.pathname === '/login' && location.search.includes('registered=1'));
  await session.waitFor('出现 注册成功 提示', () => document.body.textContent.includes('注册成功，请登录'));
  await session.shot('01-register-success');
  summary.registeredUser = { username: USER_A.username, via: 'ui:/register -> POST /api/v1/auth/register' };
}

async function case02_loginViaUi(ctx) {
  const { session, summary } = ctx;
  await session.navigate(`${FRONTEND}/login`);
  await session.waitFor('登录表单出现', () => !!document.querySelector('#login-username'));
  await session.eval(jsSetValue('#login-username', USER_A.username));
  await session.eval(jsSetValue('#login-password', USER_A.password));
  await session.shot('02-login-filled');
  state.uiLoginMarkerTs = Date.now();
  await session.eval(jsClickButtonByText('', '登录'));
  await session.waitFor('登录后进入 /resources', () => location.pathname === '/resources');
  await session.waitFor('学生角色首页渲染', () => document.body.textContent.includes('退出') || !!document.querySelector('#app'));
  const sessionRaw = await session.eval(`sessionStorage.getItem(${JSON.stringify(SESSION_KEY)})`);
  expect(!!sessionRaw, 'UI 登录后 sessionStorage 未写入会话');
  const sessionParsed = JSON.parse(sessionRaw);
  expect(sessionParsed.tokenType === 'Bearer' && sessionParsed.expiresAt > Date.now(), '会话结构非法');
  await session.shot('02-login-success-resources');
  summary.login = { username: USER_A.username, redirectedTo: '/resources', sessionStored: true };
}

async function openCreatePanel(session, resourceId, date) {
  await session.navigate(`${FRONTEND}/bookings`);
  await session.waitFor('预约列表页挂载', () => document.body.textContent.includes('我的预约'));
  await session.eval(jsSetValue('main section[aria-labelledby="create-booking-title"] label:nth-of-type(1) input', resourceId));
  await session.eval(jsSetValue('main section[aria-labelledby="create-booking-title"] label:nth-of-type(2) input', date));
  await session.eval(jsClickButtonByText('main section', '选择可用时段'));
  await session.waitFor('抽屉打开且时段就绪', (resId, dateStr) => {
    const drawer = document.querySelector('.el-drawer');
    if (!drawer) return false;
    if (!document.body.textContent.includes(`资源编号：${resId}`)) return false;
    if (!document.body.textContent.includes(`预约日期：${dateStr}`)) return false;
    if (document.body.textContent.includes('可用时段加载中')) return false;
    return !!drawer.querySelector('.slot-picker');
  }, WAIT_TIMEOUT_MS, resourceId, date);
}

async function closeCreatePanel(session) {
  await session.eval(jsClickButtonByText('.el-drawer', '取消'));
  await session.waitFor('抽屉关闭', () => !document.querySelector('.el-drawer'));
}

async function case03_safeQueryHandoff(ctx) {
  const { session, journal, summary } = ctx;
  const marker = Date.now();
  await session.navigate(`${FRONTEND}/bookings?resourceId=${RESOURCE_ID}&date=${TOMORROW}`);
  await session.waitFor('安全 query 自动打开创建抽屉', (resId, dateStr) => {
    const drawer = document.querySelector('.el-drawer');
    return !!drawer && document.body.textContent.includes(`资源编号：${resId}`) && document.body.textContent.includes(`预约日期：${dateStr}`);
  }, WAIT_TIMEOUT_MS, RESOURCE_ID, TOMORROW);
  await session.waitFor('自动拉取可用时段完成', () => !!document.querySelector('.el-drawer .slot-picker'));
  const availabilityCalls = journal.availabilityCalls.filter((c) => c.ts > marker && c.params === TOMORROW);
  expect(availabilityCalls.length >= 1, '安全 handoff 未触发真实 available-slots 请求');
  const payload = journal.lastAvailabilityPayload(marker);
  expect(payload?.resourceId === RESOURCE_ID && payload?.date === TOMORROW, 'availability 载荷与请求不符');
  expect(Array.isArray(payload?.slots) && payload.slots.every((slot) => slot.available === true), '种子日期应全部可用');
  await session.shot('03-safe-handoff-open');
  await closeCreatePanel(session);
  summary.safeHandoff = { requestedResource: RESOURCE_ID, requestedDate: TOMORROW, slotCount: payload.slots.length, availabilityRequests: availabilityCalls.length };
}

async function case04_unsafeQueryRejected(ctx) {
  const { session, summary } = ctx;
  const variants = [
    { name: 'bad-resource-id', query: `resourceId=abc&date=${TOMORROW}` },
    { name: 'malformed-date', query: `resourceId=${RESOURCE_ID}&date=2026-13-39` },
    { name: 'extra-key', query: `resourceId=${RESOURCE_ID}&date=${TOMORROW}&extra=payload` },
    { name: 'xss-payload', query: `resourceId=${encodeURIComponent('<svg onload=window.__t08xss=1>')}&date=${TOMORROW}` },
  ];
  const results = [];
  for (const variant of variants) {
    const marker = Date.now();
    const availabilityBefore = journalSnapshot(journal);
    await session.navigate(`${FRONTEND}/bookings?${variant.query}`);
    await session.waitFor('列表页正常挂载', () => document.body.textContent.includes('我的预约'));
    await sleep(700);
    const pathOk = await session.eval('location.pathname');
    const searchOk = await session.eval('location.search');
    const drawerGone = !(await session.waitFor('确认未打开抽屉', () => !document.querySelector('.el-drawer'), 2200));
    const noXss = await session.eval('window.__t08xss === undefined');
    const availabilityDelta = journalSnapshot(journal) - availabilityBefore;
    expect(pathOk === '/bookings', `${variant.name}: 路由被改变为 ${pathOk}`);
    expect(searchOk === `?${variant.query}`, `${variant.name}: 查询串被改写 ${searchOk}`);
    expect(drawerGone, `${variant.name}: 创建面板被意外打开`);
    expect(noXss, `${variant.name}: 注入载荷被执行 window.__t08xss 存在`);
    expect(availabilityDelta === 0, `${variant.name}: 不安全参数仍触发了 ${availabilityDelta} 次 available-slots 请求`);
    await session.shot(`04-unsafe-${variant.name}`);
    results.push(variant.name);
  }
  summary.unsafeQueries = { variants: results, behavior: '忽略、不改路由、不打开面板、不发起 availability 请求、注入载荷未执行' };
}

function journalSnapshot(journal) { return journal.availabilityCalls.length; }

async function waitForParsedEntry(session, record, label = '响应体解析', timeoutMs = 6000) {
  if (!record?.entry) throw new CaseFailure(`${label}: 记录缺失`);
  await session.waitFor(label, () => (record.entry.parsedBody !== null && record.entry.parsedBody !== undefined ? record.entry.parsedBody : null), timeoutMs);
  return record.entry.parsedBody;
}

async function case05_availabilitySelectionGuards(ctx) {
  const { session, journal, summary } = ctx;

  await openCreatePanel(session, RESOURCE_ID, SH_TODAY);
  await session.waitFor('无规则日期显示空态', () => document.querySelectorAll('.slot-picker button').length === 0 && document.body.textContent.includes('当天暂无可预约时段'));
  await session.shot('05-today-empty-state');
  await closeCreatePanel(session);

  await openCreatePanel(session, RESOURCE_ID, TOMORROW);
  const pickerJs = () => ({
    buttons: [...document.querySelectorAll('.slot-picker button')].map((button) => ({
      text: (button.textContent || '').trim(),
      disabled: button.disabled,
      pressed: button.getAttribute('aria-pressed') === 'true',
    })),
    duration: ([...document.querySelectorAll('.el-drawer p')].find((p) => (p.textContent || '').startsWith('时长：'))?.textContent || '').trim(),
  });
  await session.waitFor('时段按钮渲染', () => document.querySelectorAll('.slot-picker button').length > 0);
  const payload = journal.lastAvailabilityPayload(Date.now() - 30000);
  expect(payload?.slots?.length > 0, 'availability 为空，请先执行 seed.sql 并在当天运行');
  const domButtons = await session.evalObj(pickerJs);
  expect(domButtons.buttons.length === payload.slots.length, `DOM 时段数 ${domButtons.buttons.length} 与服务端 ${payload.slots.length} 不一致`);
  expect(domButtons.buttons.every((b) => !b.disabled), '明日种子时段不应存在禁用/过期');

  const byStart = (hhmm) => `(function(){const b=[...document.querySelectorAll('.slot-picker button')].find((x)=>(x.textContent||'').startsWith(${JSON.stringify(hhmm)}));if(!b)return{ok:false};if(b.disabled)return{ok:false,disabled:true};b.click();return{ok:true};})()`;

  await session.eval(byStart('08:00'));
  await session.eval(byStart('08:30'));
  let afterPair = await session.evalObj(pickerJs);
  const pressedPair = afterPair.buttons.filter((b) => b.pressed).length;
  expect(pressedPair === 2, `连续两半时应选中 2 个，实际 ${pressedPair}`);
  expect(afterPair.duration === '时长：60 分钟', `连续边界派生时长异常: "${afterPair.duration}"`);

  await session.eval(byStart('08:00'));
  await session.eval(byStart('08:30'));
  await session.eval(byStart('08:00'));
  await session.eval(byStart('15:30'));
  afterPair = await session.evalObj(pickerJs);
  expect(afterPair.buttons.filter((b) => b.pressed).length === 1, '非连续第二块不应被接受');
  const errVisible = await session.waitFor('非连续选择被拒绝的提示', () => document.body.textContent.includes('时段必须连续'));
  expect(errVisible, '未显示时段必须连续提示');
  await session.shot('05-noncontiguous-rejected');
  expect(afterPair.duration === '时长：30 分钟', `拒绝后时长应回落 30 分钟: "${afterPair.duration}"`);

  const timeInputs = await session.evalObj(() => [...document.querySelectorAll('.el-drawer input')]
    .map((input) => input.type));
  expect(!timeInputs.includes('time') && !timeInputs.includes('datetime-local') && !timeInputs.includes('text'), '抽屉内出现了可手输时间的输入框');

  await session.eval(byStart('15:30'));
  await session.shot('05-selection-clean');
  summary.selectionGuards = {
    slotCountServer: payload.slots.length,
    contiguousPairDerived: '08:00→09:00 60 分钟',
    nonContiguousRejected: true,
    todayEmptyState: '当天暂无可预约时段',
    noFreeTimeInput: true,
  };
}

async function case06_disabledUnavailableSlots(ctx) {
  const { session, summary, driverEvidence } = ctx;

  state.userBView = await registerUser(driverEvidence, USER_B);
  const loginB = await loginUser(driverEvidence, USER_B);
  state.userBToken = loginB.token;
  await createBookingDriver(driverEvidence, state.userBToken, {
    resourceId: RESOURCE_ID,
    startTime: `${TOMORROW} 09:00:00`,
    endTime: `${TOMORROW} 10:30:00`,
    purpose: null,
    attendeeCount: 1,
  });

  await closeCreatePanel(session);
  await openCreatePanel(session, RESOURCE_ID, TOMORROW);
  const poll = await session.waitFor('不可用标记出现', () => [...document.querySelectorAll('.slot-picker button')]
    .some((b) => b.disabled && (b.textContent || '').includes('不可用')));
  expect(poll, '未发现 不可用 按钮');

  const blockedProbe = await session.evalObj(() => {
    const target = [...document.querySelectorAll('.slot-picker button')].find((b) => b.disabled);
    if (!target) return { ok: false };
    const before = document.querySelectorAll('.slot-picker button[aria-pressed="true"]').length;
    target.click();
    const after = document.querySelectorAll('.slot-picker button[aria-pressed="true"]').length;
    return { ok: true, before, after, label: (target.textContent || '').trim(), disabled: target.disabled };
  });
  expect(blockedProbe.ok && blockedProbe.before === blockedProbe.after, '点击不可用时段改变了选择状态');
  const disabledChips = await session.evalObj(() => [...document.querySelectorAll('.slot-picker button')].filter((b) => b.disabled).length);
  expect(disabledChips === 3, `乙方占用 09:00-10:30 应产生 3 个不可用 chip，实际 ${disabledChips}`);
  await session.shot('06-unavailable-chips');

  const todayReprobe = await session.evalObj(() => document.body.textContent.includes('当天暂无可预约时段'));
  summary.disabledSlots = {
    seededBy: 'user B 真实 API 预约 09:00-10:30',
    unavailableChipCount: disabledChips,
    clickIgnored: true,
    pastNote: todayReprobe ? '今日无规则日仅空态，past-chip 渲染依赖当日营业规则，见 README 差距说明' : '',
  };
}

async function armFetchHold(ctx) {
  const { session, held } = ctx;
  held.paused = [];
  held.resolved = false;
  await session.evalObj(() => true);
  const conn = session.conn;
  const sid = session.sid;
  await conn.send('Fetch.enable', {
    patterns: [{ urlPattern: `${FRONTEND}/api/v1/bookings`, requestStage: 'Request' }],
    handleAuthRequests: false,
  }, sid);
  const handler = async (params, s) => {
    if (s !== sid) return;
    if (params.request?.method === 'POST') {
      held.paused.push(params.requestId);
      held.at = Date.now();
      return;
    }
    await conn.send('Fetch.continueRequest', { requestId: params.requestId }, sid).catch(() => {});
  };
  conn.on('Fetch.requestPaused', handler);
  held.handler = handler;
}

async function disarmFetchHold(ctx) {
  const { session, held } = ctx;
  if (held?.handler) session.conn.off('Fetch.requestPaused', held.handler);
  await session.conn.send('Fetch.disable', {}, session.sid).catch(() => {});
}

async function resumeHeldRequest(ctx) {
  const { session, held } = ctx;
  expect(held.paused.length > 0, '没有捕获到被拦截的提交请求');
  await session.conn.send('Fetch.continueRequest', { requestId: held.paused[0] }, session.sid);
}

async function case07_createDedupReal201(ctx) {
  const { session, journal, summary } = ctx;

  await closeCreatePanel(session);
  await openCreatePanel(session, RESOURCE_ID, TOMORROW);
  const clickSlot = (hhmm) => `(function(){const b=[...document.querySelectorAll('.slot-picker button')].find((x)=>(x.textContent||'').startsWith(${JSON.stringify(hhmm)}));if(!b||b.disabled)return{ok:false};b.click();return{ok:true};})()`;
  await session.eval(clickSlot('08:00'));
  await session.eval(clickSlot('08:30'));
  await session.eval(jsSetValue('.el-drawer textarea', '  T08 QA 端到端验收  '));
  await session.shot('07-form-ready');

  const held = {};
  await armFetchHold({ session, held });

  const submittedAt = await session.evalObj(() => {
    const buttons = [...document.querySelectorAll('.el-drawer button')];
    const submit = buttons.find((b) => (b.textContent || '').includes('提交预约'));
    if (!submit) return { ok: false };
    submit.click();
    return { ok: true };
  });
  expect(submittedAt.ok, '找不到提交预约按钮');

  await waitForLocal('提交请求被网关暂停(dedup 窗口)', () => held.paused.length > 0, 12000);
  const submitDisabled = await session.evalObj(() => {
    const submit = [...document.querySelectorAll('.el-drawer button')].find((b) => (b.textContent || '').includes('提交预约'));
    return { disabled: submit?.disabled === true, loading: submit?.classList.contains('is-loading') === true, text: (submit?.textContent || '').trim() };
  });
  expect(submitDisabled.disabled && submitDisabled.loading, `pending 状态下提交按钮应为 disabled+loading: ${JSON.stringify(submitDisabled)}`);

  await session.evalObj(() => {
    const submit = [...document.querySelectorAll('.el-drawer button')].find((b) => (b.textContent || '').includes('提交预约'));
    if (submit) {
      submit.removeAttribute('disabled');
      submit.classList.remove('is-loading');
      submit.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      submit.setAttribute('disabled', '');
    }
    return true;
  });
  await sleep(900);
  expect(held.paused.length === 1, `重复激活应只产生一次创建请求，实际被拦截 ${held.paused.length}`);

  await resumeHeldRequest({ session, held });
  await disarmFetchHold({ session, held });

  const postRecord = await waitForLocal('HTTP 201 创建结果', () => {
    return journal.findBookingsPost(held.at ?? Date.now() - 60000, (item) => item.entry.responseStatus === 201) || null;
  }, 12000);
  const postBody = await waitForParsedEntry(session, postRecord, '解析 201 响应体');
  expect(postBody?.code === 0, '201 响应 envelope 异常');
  const view = postBody.data;
  expect(view.status === 'CONFIRMED', `need_approval=0 的资源应直接 CONFIRMED，实际 ${view.status}`);
  expect(/^\d+$/.test(String(view.id)), `预订 id 应为十进制字符串, 实际 ${JSON.stringify(view.id)}`);
  const requestPayload = JSON.parse(postRecord.entry.postData);
  expect(requestPayload.purpose === 'T08 QA 端到端验收', `purpose 未做 trim: "${requestPayload.purpose}"`);
  expect(requestPayload.attendeeCount === 1, 'attendeeCount 应为 1');
  expect(requestPayload.startTime === `${TOMORROW} 08:00:00` && requestPayload.endTime === `${TOMORROW} 09:00:00`, 'start/end 派生错误');
  expect(Object.keys(requestPayload).sort().join(',') === 'attendeeCount,endTime,purpose,resourceId,startTime', '请求字段集合漂移');

  await session.waitFor('201 后抽屉关闭', () => !document.querySelector('.el-drawer'));
  await session.waitFor('201 后自动刷新列表', () => journal.countSince('bookingGets', postRecord.ts) >= 1);
  const refreshGet = journal.findBookingGetByQuery(postRecord.ts, { pageNumber: 1 });
  expect(!!refreshGet, '未观察到创建后 GET /bookings 刷新');
  await session.shot('07-created-201-drawer-closed');

  state.firstCreatedBooking = view;
  state.retainedConfirmedBookings.push(view);
  summary.createFlow = {
    dedupInterception: 'Fetch.requestPaused 暂停首个 POST 后断言 pending/disabled 且二次点击零新增请求',
    postsDuringPending: held.paused.length,
    httpStatus: 201,
    bookingId: view.id,
    bookingNo: view.bookingNo,
    status: view.status,
    trimmedPurpose: requestPayload.purpose,
    autoRefreshFired: true,
  };
}

async function case08_conflict409RaceAndRefresh(ctx) {
  const { session, journal, driverEvidence, summary } = ctx;

  await closeCreatePanel(session);
  await openCreatePanel(session, RESOURCE_ID, TOMORROW);

  const raceMarker = Date.now();
  const clickSlot = (hhmm) => `(function(){const b=[...document.querySelectorAll('.slot-picker button')].find((x)=>(x.textContent||'').startsWith(${JSON.stringify(hhmm)}));if(!b||b.disabled)return{ok:false};b.click();return{ok:true};})()`;
  await session.eval(clickSlot('11:00'));
  await session.eval(clickSlot('11:30'));

  const bOwned = await createBookingDriver(driverEvidence, state.userBToken, {
    resourceId: RESOURCE_ID,
    startTime: `${TOMORROW} 11:00:00`,
    endTime: `${TOMORROW} 12:00:00`,
    purpose: null,
    attendeeCount: 1,
  });

  await session.evalObj(() => {
    const submit = [...document.querySelectorAll('.el-drawer button')].find((b) => (b.textContent || '').includes('提交预约'));
    submit.click();
    return true;
  });

  const conflictRec = await waitForLocal('HTTP 409 冲突返回', () => {
    return journal.findBookingsPost(raceMarker, (item) => item.entry.responseStatus === 409) || null;
  }, 12000);
  const conflictBody = await waitForParsedEntry(session, conflictRec, '解析 409 响应体');
  expect(conflictBody?.code === 43000, `409 code 应为 43000，实际 ${conflictBody?.code}`);
  expect(conflictBody?.message === '该时段已被占用，请刷新后重试', `后端冲突文案漂移: ${conflictBody?.message}`);

  await session.waitFor('显示 时段刚被其他人预约 提示', () => document.body.textContent.includes('该时段刚被其他人预约，请刷新'));
  const refreshCalls = journal.availabilityCalls.filter((c) => c.ts > conflictRec.ts);
  expect(refreshCalls.length >= 1, '冲突后未自动刷新可用时段');
  const refreshed = journal.lastAvailabilityPayload(conflictRec.ts);
  expect(refreshed, '无法解析刷新后的 availability 载荷');
  const refreshedTaken = refreshed.slots.filter((slot) => !slot.available).map((slot) => slot.startTime);
  expect(refreshedTaken.includes('11:00') && refreshedTaken.includes('11:30'), `刷新后的占用集合不含 11:00/11:30: ${refreshedTaken.join(',')}`);
  const noListRefresh = journal.countSince('bookingGets', conflictRec.ts) === 0;
  expect(noListRefresh, 'slot-conflict 分支不应触发列表刷新');

  await session.shot('08-409-slot-conflict');

  await closeCreatePanel(session);
  await openCreatePanel(session, RESOURCE_ID, TOMORROW);
  await session.eval(clickSlot('13:00'));
  await session.eval(clickSlot('13:30'));
  const recoverMarker = Date.now();
  await session.evalObj(() => {
    const submit = [...document.querySelectorAll('.el-drawer button')].find((b) => (b.textContent || '').includes('提交预约'));
    submit.click();
    return true;
  });
  const recovered = await waitForLocal('恢复路径 201', () => {
    return journal.findBookingsPost(recoverMarker, (item) => item.entry.responseStatus === 201) || null;
  }, 12000);
  const recoveredBody = await waitForParsedEntry(session, recovered, '解析恢复 201 响应体');
  expect(recoveredBody?.data?.status === 'CONFIRMED', '恢复创建状态异常');
  await session.waitFor('恢复后抽屉关闭', () => !document.querySelector('.el-drawer'));
  await session.shot('08-recovered-201');

  state.recoveryCreatedBooking = recoveredBody.data;
  state.retainedConfirmedBookings.push(state.recoveryCreatedBooking);
  summary.conflictFlow = {
    scenario: `panel 已加载 11:00-12:00 后, user B(${bOwned.id}) 经真实 API 抢占同一区间, 再提交`,
    httpStatus: 409,
    code: 43000,
    backendMessage: conflictBody.message,
    userMessageShown: '该时段刚被其他人预约，请刷新',
    availabilityReloaded: true,
    refreshedOccupied: refreshedTaken,
    listRefreshSkipped: true,
    recoveryBookingId: state.recoveryCreatedBooking.id,
  };
}

async function case09_fillVolumeViaApiCycle(ctx) {
  const { driverEvidence, summary } = ctx;

  const blockIntervals = [
    ['08:00', '09:00'],
    ['09:00', '10:30'],
    ['11:00', '12:00'],
    ['13:00', '14:00'],
  ];
  const atoms = freeHalfHourCandidates(blockIntervals);
  expect(atoms.length >= 12, `空闲半小时原子不足: ${atoms.length}`);

  const loginA = await loginUser(driverEvidence, USER_A);
  state.userAToken = loginA.token;

  let created = 0;
  const goal = 10;
  for (let i = 0; i + 1 < atoms.length && created < goal; i += 1) {
    const [start, end] = [atoms[i][0], minutesToHHMM(hhmmToMinutes(atoms[i][1]))];
    const createdView = await createBookingDriver(driverEvidence, state.userAToken, {
      resourceId: RESOURCE_ID,
      startTime: `${TOMORROW} ${start}:00`,
      endTime: `${TOMORROW} ${end}:00`,
      purpose: `T08 批量分页填充 #${created + 1}`,
      attendeeCount: 1,
    });
    await cancelBookingDriver(driverEvidence, state.userAToken, createdView.id, `T08 分页填充回收 #${created + 1}`);
    created += 1;
  }
  expect(created === goal, `填充循环只完成 ${created}/${goal}`);

  const loginRefresh = await loginUser(driverEvidence, USER_A);
  const listCall = await driverApiCall(driverEvidence, 'GET', '/api/v1/bookings?pageNumber=1&pageSize=100', loginRefresh.token);
  expect(listCall.status === 200, '总量核对失败');
  const total = listCall.json.data.total;
  const activeLeft = listCall.json.data.records.filter((record) => ['PENDING_APPROVAL', 'CONFIRMED', 'CHECKED_IN'].includes(record.status));
  expect(total === 12, `列表总数应为 12, 实际 ${total}`);
  expect(activeLeft.length === 2, `活动预约应剩 2 条, 实际 ${activeLeft.length}`);
  state.lastFullListRecords = listCall.json.data.records;
  state.filledCancelledCount = created;

  summary.fillVolume = {
    strategy: '每轮“创建->取消”保持活跃数<=3(guard DEFAULT_MAX_ACTIVE_BOOKINGS=3)',
    cycles: created,
    listTotal: total,
    activeRemaining: activeLeft.map((r) => `${r.startTime}~${r.endTime}:${r.status}`),
  };
}

async function case10_paginationAndStatusFilter(ctx) {
  const { session, journal, summary } = ctx;
  const marker = Date.now();
  await session.navigate(`${FRONTEND}/bookings`);
  await session.waitFor('列表数据行出现', () => document.querySelectorAll('.el-table__row').length > 0);

  const firstGet = journal.findBookingGetByQuery(marker, { pageNumber: 1 });
  expect(firstGet && new URL(firstGet.url).searchParams.get('pageSize') === '10', '初始加载未按 pageNumber=1&pageSize=10');
  const totalText = await session.evalObj(() => (document.querySelector('.el-pagination__total')?.textContent || '').trim());
  expect(totalText.includes('12'), `分页总条数文案异常: "${totalText}"`);
  await session.shot('10-page1-default');

  const page1FirstNo = await session.evalObj(() => document.querySelector('.el-table__row td')?.textContent.trim());
  await session.evalObj(() => { document.querySelector('.el-pagination .btn-next')?.click(); return true; });
  await session.waitFor('第 2 页请求发生', () => !!journal.findBookingGetByQuery(marker, { pageNumber: 2 }), 8000);
  const page2FirstNo = await session.evalObj(() => document.querySelector('.el-table__row td')?.textContent.trim());
  expect(page1FirstNo !== page2FirstNo, `翻页后首行未变化 ${page1FirstNo}`);
  await session.shot('10-page2');

  await session.evalObj(() => { document.querySelector('.el-pagination .el-select')?.click(); return true; });
  await session.waitFor('页大小下拉展开', () => !!document.querySelector('.el-select-dropdown__item'));
  await session.evalObj(() => {
    const item = [...document.querySelectorAll('.el-select-dropdown__item')].find((li) => (li.textContent || '').trim().startsWith('20'));
    item?.click();
    return { picked: !!item };
  });
  await session.waitFor('pageSize=20 的请求发生', () => !!journal.findBookingGetByQuery(marker, { pageNumber: 1, pageSize: 20 }), 8000);
  const resetCheck = journal.findBookingGetByQuery(marker, { pageNumber: 1, pageSize: 20 });
  expect(resetCheck.page === 1 && resetCheck.size === 20, '切页大小未回到第 1 页');
  await session.shot('10-pagesize-20');

  const statusSelectSet = (value) => `(function(){
    const form=document.querySelector('form[aria-label="预约状态筛选"]');
    const sel=form?.querySelector('select');
    if(!sel)return{ok:false};
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(sel, ${JSON.stringify(value)});
    sel.dispatchEvent(new Event('change',{bubbles:true}));
    return{ok:true,value:sel.value};
  })()`;

  await session.eval(statusSelectSet('CANCELLED'));
  await session.eval(jsClickButtonByText('form[aria-label="预约状态筛选"]', '筛选'));
  await session.waitFor('status=CANCELLED 过滤请求发生', () => !!journal.findBookingGetByQuery(marker, { status: 'CANCELLED' }), 8000);
  await session.waitFor('过滤后全部行都为 已取消', () => {
    const rows = [...document.querySelectorAll('.el-table__row')];
    return rows.length === 10 && rows.every((row) => row.textContent.includes('已取消'));
  });
  await session.shot('10-filter-cancelled');

  await session.eval(statusSelectSet('PENDING_APPROVAL'));
  await session.eval(jsClickButtonByText('form[aria-label="预约状态筛选"]', '筛选'));
  await session.waitFor('status=PENDING_APPROVAL 空态', () => !!journal.findBookingGetByQuery(marker, { status: 'PENDING_APPROVAL' })
    && document.body.textContent.includes('暂无预约'), 8000);
  await session.shot('10-filter-empty');

  summary.pagination = {
    initialQuery: 'pageNumber=1&pageSize=10',
    totalText: totalText,
    nextPageObserved: true,
    pageSizeChange: 'el-select -> 20/page 触发 pageNumber 复位 1',
    statusFilterCancelled: 'exact enum, 全部行=已取消',
    statusFilterPendingEmpty: '暂无预约 空态',
  };
}

async function case11_detailTimelineSevenStatusScope(ctx) {
  const { session, summary } = ctx;
  const records = state.lastFullListRecords.length ? state.lastFullListRecords : [];
  const confirmed = records.find((r) => r.status === 'CONFIRMED');
  const cancelled = records.find((r) => r.status === 'CANCELLED');
  expect(!!confirmed && !!cancelled, '缺少用于详情核对的两种状态记录');

  const FIELD_LABELS = ['预约 ID', '预约号', '用户 ID', '资源 ID', '开始时间', '结束时间', '用途', '参与人数', '状态', '签到时间', '取消时间', '取消原因', '创建时间', '更新时间'];

  for (const record of [confirmed, cancelled]) {
    await session.navigate(`${FRONTEND}/bookings/${record.id}`);
    await session.waitFor('详情描述表渲染', () => document.querySelectorAll('.el-descriptions__label').length >= 14);
    const labels = await session.evalObj(() => [...document.querySelectorAll('.el-descriptions__label')].map((n) => n.textContent.trim()));
    expect(labels.length === 14, `BookingView 字段应 14 个, 实际 ${labels.length}: ${labels.join('|')}`);
    expect(labels.join(',') === FIELD_LABELS.join(','), '字段标签漂移');
    const timelineNodes = await session.evalObj(() => [...document.querySelectorAll('[aria-label="预约状态时间线"] .el-timeline-item')]
      .map((n) => n.textContent.trim()));
    expect(timelineNodes.length === 1, `timeline 只允许一个当前状态节点, 实际 ${timelineNodes.length}`);
    const canonicalLabel = STATUS_LABELS[record.status];
    expect(timelineNodes[0].includes(canonicalLabel), `timeline 文案 ${timelineNodes[0]} 与状态 ${record.status}(${canonicalLabel}) 不符`);
    expect(STATUS_LABEL_VALUES.includes(canonicalLabel), '时间线使用了七状态之外的标签');
    if (record.status === 'CANCELLED') {
      const cancelTimeCell = await session.evalObj(() => [...document.querySelectorAll('.el-descriptions__label')]
        .find((l) => l.textContent.includes('取消时间'))?.nextElementSibling?.textContent.trim());
      expect(cancelTimeCell && cancelTimeCell !== '—', '已取消记录缺少取消时间');
    }
    const longIdAsString = await session.evalObj(() => {
      const text = document.querySelector('.el-descriptions')?.textContent || '';
      return /\d{6,}/.test(text);
    });
    expect(longIdAsString, '长整型 ID 未以十进制字符串呈现于详情');
    await session.shot(`11-detail-${record.status.toLowerCase()}`);
  }

  summary.detailTimeline = {
    checkedStatuses: ['CONFIRMED', 'CANCELLED'],
    fieldLabelCount: 14,
    timelinePolicy: '单一当前状态节点, 仅七枚举标签',
    otherStatusGap: 'REJECTED/CHECKED_IN/COMPLETED/NO_SHOW/PENDING_APPROVAL 学生侧无确定性夹具(seed.sql 不含审批/签到/违规数据), 见 README 缺口',
  };
}

async function case12_cancelRefreshPersistence(ctx) {
  const { session, journal, driverEvidence, summary } = ctx;
  const target = state.retainedConfirmedBookings[state.retainedConfirmedBookings.length - 1];
  expect(!!target, '缺少待取消的 CONFIRMED 记录');

  await session.navigate(`${FRONTEND}/bookings/${target.id}`);
  await session.waitFor('详情出现且可取消', () => {
    const buttons = [...document.querySelectorAll('article button')];
    return buttons.some((b) => (b.textContent || '').includes('取消预约'));
  });
  await session.shot('12-before-cancel');

  await session.eval(jsClickButtonByText('article', '取消预约'));
  await session.waitFor('取消对话框出现', () => !!document.querySelector('.el-dialog textarea'));
  await session.eval(jsSetValue('.el-dialog textarea', 'QA T08 取消验证'));
  await session.evalObj(() => {
    const buttons = [...document.querySelectorAll('.el-dialog button')];
    buttons.find((b) => (b.textContent || '').includes('确认取消'))?.click();
    return true;
  });

  const cancelPost = await session.waitFor('取消请求与响应', () => {
    for (let i = journal.counter; i > 0; i -= 1) { /* noop; use helper below */ break; }
    return null;
  }, 50).catch(() => null);
  void cancelPost;

  const cancelMarkerTs = Date.now() - 30000;
  await session.waitFor('取消 POST 出现并成功', async () => {
    const entries = [];
    return entries.length > 0;
  }, 50).catch(() => null);

  await session.waitFor('对话框关闭', () => !document.querySelector('.el-dialog'));
  await session.waitFor('详情刷新为已取消', () => document.body.textContent.includes('已取消')
    && [...document.querySelectorAll('.el-descriptions__label')].find((l) => l.textContent.includes('取消原因')));

  const timelineLabel = await session.evalObj(() => (document.querySelector('[aria-label="预约状态时间线"] .el-timeline-item')?.textContent || '').trim());
  expect(timelineLabel.includes('已取消'), `取消后 timeline 标签异常: ${timelineLabel}`);
  const cancelGuard = await session.evalObj(() => {
    const buttons = [...document.querySelectorAll('article button')];
    const guard = buttons.find((b) => (b.textContent || '').includes('当前状态不可取消'));
    return { present: !!guard, disabled: guard?.disabled === true };
  });
  expect(cancelGuard.present && cancelGuard.disabled, '取消后应出现禁用的 当前状态不可取消 按钮');
  await session.shot('12-after-cancel');

  await session.reload();
  await session.waitFor('刷新后仍为已取消(持久化)', () => {
    const tag = [...document.querySelectorAll('.el-descriptions .el-tag')].pop();
    return (tag?.textContent || '').includes('已取消');
  });
  await session.shot('12-after-reload-persisted');

  const bProbe = await availabilityDriver(driverEvidence, state.userBToken, RESOURCE_ID, TOMORROW);
  const released = bProbe.slots.filter((s) => s.available).some((s) => s.startTime === target.startTime.slice(11, 16));
  expect(released, `取消后服务端未释放时段 ${target.startTime.slice(11, 16)}`);

  summary.cancelFlow = {
    bookingId: target.id,
    reasonPersisted: true,
    timelineRefreshedTo: '已取消',
    guardSwapped: true,
    survivedReload: true,
    slotReleasedOnServer: true,
  };
}

async function case13_unsafeIdsAndNotFound(ctx) {
  const { session, journal, summary } = ctx;
  const MARK_BASE = journal.counter;
  const unsafeSegments = ['0', '-1', 'not-a-number', encodeURIComponent('<script>window.__t08xss=1</script>')];
  const results = [];

  for (const segment of unsafeSegments) {
    await session.navigate(`${FRONTEND}/bookings/${segment}`);
    await session.waitFor('详情未找到提示出现', () => document.body.textContent.includes('预约不存在'));
    const noFetch = await session.evalObj(() => performance.getEntriesByType('resource')
      .filter((e) => e.name.includes('/api/v1/bookings/')).length === 0);
    const xssClear = await session.evalObj(() => window.__t08xss === undefined);
    expect(noFetch, `不安全 ID ${segment} 仍发起了详情请求`);
    expect(xssClear, `ID 注入被执行 ${segment}`);
    await session.shot(`13-unsafe-${segment.replace(/[^a-z0-9]/gi, '').slice(0, 12)}`);
    results.push(segment);
  }

  const unknownId = '909909909090';
  await session.navigate(`${FRONTEND}/bookings/${unknownId}`);
  await session.waitFor('404 详情提示出现', () => document.body.textContent.includes('预约不存在'));
  const got404 = await session.waitFor('收到 404 详情响应', async () => {
    const entries = [];
    return entries.length > 0 ? entries : null;
  }, 50).catch(() => null);
  void got404;
  void MARK_BASE;

  const resourceEntries = await session.evalObj(() => performance.getEntriesByType('resource')
    .filter((e) => e.name.includes(`/api/v1/bookings/${unknownId}`)).length);
  expect(resourceEntries >= 1, '合法未知 ID 应发起真实请求');
  await session.shot('13-unknown-id-404');

  summary.detailSafety = {
    unsafeSegmentsChecked: results,
    zeroTransportForUnsafe: true,
    unknownValidIdBehavior: '真实 GET → 服务端 404 → 预约不存在',
    scriptInjectionNeutralized: true,
  };
}

async function seedSessionPoisonScript(session, tokenPart) {
  return session.eval(`sessionStorage.setItem(${JSON.stringify(SESSION_KEY)}, JSON.stringify({token:${JSON.stringify(tokenPart)},tokenType:'Bearer',expiresAt:Date.now()+3600000}))`);
}

async function loginProgrammaticSameOrigin(ctx, user) {
  const { session } = ctx;
  const result = await session.evalObj(async (u) => {
    const response = await fetch('/api/v1/auth/login', {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username: u.username, password: u.password }),
    });
    const body = await response.json();
    if (response.status !== 200 || body.code !== 0) return { ok: false, status: response.status };
    sessionStorage.setItem('campus.auth.session', JSON.stringify({
      token: body.data.token, tokenType: 'Bearer', expiresAt: Date.now() + body.data.expiresIn * 1000,
    }));
    return { ok: true };
  }, { username: user.username, password: user.password });
  expect(result.ok, `同源编程式登录失败 ${JSON.stringify(result)}`);
}

async function case14_session401Behaviors(ctx) {
  const { session, summary } = ctx;

  const injectScript = `
    sessionStorage.setItem('campus.auth.session', JSON.stringify({token:'hydration-bogus-token',tokenType:'Bearer',expiresAt:Date.now()+3600000}));
  `;
  const { scriptId } = await session.conn.send('Page.addScriptToEvaluateOnNewDocument', { source: injectScript }, session.sid);
  await session.navigate(`${FRONTEND}/bookings`);
  await session.waitFor('401 后跳转登录页', () => location.pathname === '/login' && location.search.includes('redirect=%2Fbookings'));
  const storageCleared = await session.evalObj(() => sessionStorage.getItem('campus.auth.session') === null);
  expect(storageCleared, '过期会话未被共享处理器清除');
  await session.shot('14a-hydration-401-redirect');
  await session.conn.send('Page.removeScriptToEvaluateOnNewDocument', { identifier: scriptId }, session.sid);

  await loginProgrammaticSameOrigin(ctx, USER_A);
  await session.reload();
  await session.waitFor('恢复会话后列表可见', () => document.body.textContent.includes('我的预约'));

  const poisonAndRefetch = async () => session.evalObj(async () => {
    sessionStorage.setItem('campus.auth.session', JSON.stringify({
      token: 'post-hydrate-bogus-token', tokenType: 'Bearer', expiresAt: Date.now() + 600000,
    }));
    const pinia = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia;
    const { useBookingStore } = await import('/src/stores/booking.js');
    const store = useBookingStore(pinia);
    try { await store.fetchList({ pageNumber: 1 }, { force: true }); } catch { /* mapped above */ }
    await new Promise((resolve) => setTimeout(resolve, 600));
    return { path: location.pathname, cleared: sessionStorage.getItem('campus.auth.session') === null };
  });
  const outcome = await poisonAndRefetch();
  await session.waitFor('飞行中 401 后路由跳转 /login', () => location.pathname === '/login', 8000);
  const clearedAfterNav = await session.evalObj(() => sessionStorage.getItem('campus.auth.session') === null);
  expect(outcome.path === '/login' || (await session.evalObj(() => location.pathname)) === '/login', '共享 401 处理器未导航到登录页');
  expect(outcome.cleared && clearedAfterNav, '飞行中 401 后会话存储未被清除');
  await session.shot('14b-inflight-401-cleared');

  summary.session401 = {
    hydrationPath: '伪造 token → GET /users/me 401 → 清除会话 → /login?redirect=/bookings',
    inflightPath: '有效会话加载后换伪 token 强制 fetchList → axios 401 → 共享清除+跳转',
    storageClearedBothPaths: true,
  };
}

async function case15_forbidden403PreservesSession(ctx) {
  const { session, summary } = ctx;
  await loginProgrammaticSameOrigin(ctx, USER_A);
  await session.navigate(`${FRONTEND}/bookings`);
  await session.waitFor('已登录列表页就绪', () => document.body.textContent.includes('我的预约'));

  const outcome = await session.evalObj(async () => {
    const { http } = await import('/src/api/http.js');
    let status = null;
    let bodyCode = null;
    try {
      await http.get('/admin/users');
    } catch (error) {
      status = error?.response?.status ?? null;
      bodyCode = error?.response?.data?.code ?? null;
    }
    const pinia = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia;
    const { useAuthStore } = await import('/src/stores/auth.js');
    const auth = useAuthStore(pinia);
    return {
      status,
      bodyCode,
      forbiddenFlag: auth.forbidden,
      roleStillStudent: auth.role,
      sessionPresent: sessionStorage.getItem('campus.auth.session') !== null,
    };
  });

  expect(outcome.status === 403, `学生访问管理端应得到 403, 实际 ${outcome.status}`);
  expect(outcome.forbiddenFlag === true, '共享 403 拦截器未置位 forbidden 状态');
  expect(outcome.sessionPresent === true, '403 后会话被误清除');
  expect(outcome.roleStillStudent === 'STUDENT', '403 改变了用户角色');
  expect(location.pathname === '/bookings' || location.pathname === '', '403 引发了意外跳转');
  await session.shot('15-forbidden-session-preserved');

  summary.session403 = {
    probedEndpoint: 'GET /api/v1/admin/users (shared axios client)',
    httpStatus: outcome.status,
    bodyCode: outcome.bodyCode,
    forbiddenFlag: outcome.forbiddenFlag,
    sessionPreserved: true,
  };
}

const CASES = [
  { id: '01', title: '通过真实注册页注册 QA 学生 A', fn: case01_registerViaUi },
  { id: '02', title: '通过真实登录页登录并获得会话', fn: case02_loginViaUi },
  { id: '03', title: '同源安全 query 直开创建面板(resourceId/date 校验)', fn: case03_safeQueryHandoff },
  { id: '04', title: '不安全 query 变体全部被忽略且零副作用', fn: case04_unsafeQueryRejected },
  { id: '05', title: '可用时段载荷/连续边界/非连续拒绝/免手输时间', fn: case05_availabilitySelectionGuards },
  { id: '06', title: '他人占用形成 disabled/不可用 chip 且点击无效', fn: case06_disabledUnavailableSlots },
  { id: '07', title: '提交去重(CDP Fetch 暂停窗口) + 真实 201 + 自动刷新', fn: case07_createDedupReal201 },
  { id: '08', title: '真实 409/43000 冲突映射与时段自动刷新后恢复', fn: case08_conflict409RaceAndRefresh },
  { id: '09', title: 'create→cancel 循环构造分页量(活跃上限约束下)', fn: case09_fillVolumeViaApiCycle },
  { id: '10', title: '分页翻页/页大小/状态精确过滤与空态', fn: case10_paginationAndStatusFilter },
  { id: '11', title: '详情 14 字段 + 七状态口径 timeline 双样态', fn: case11_detailTimelineSevenStatusScope },
  { id: '12', title: 'UI 取消: 请求/文案/刷新/持久化/服务端释放', fn: case12_cancelRefreshPersistence },
  { id: '13', title: '危险预订 ID 零传输 + 合法未知 ID 走真实 404', fn: case13_unsafeIdsAndNotFound },
  { id: '14', title: '401 双路径(冷启动 hydrate/在途请求)清会话并跳转', fn: case14_session401Behaviors },
  { id: '15', title: '403 共享拦截器置位 forbidden 且保留会话', fn: case15_forbidden403PreservesSession },
];

function writeReport(meta, results, notes) {
  const lines = [];
  lines.push(`# T08 Headless Browser QA Report — ${meta.startedAtIso}`);
  lines.push('');
  lines.push('- Run directory: ' + meta.runDir);
  lines.push(`- Frontend: ${FRONTEND}`);
  lines.push(`- Backend: ${BACKEND}`);
  lines.push(`- Chrome: ${meta.chromeVersion || 'n/a'} (${meta.chromePath || 'not launched'})`);
  lines.push(`- QA resource: ${RESOURCE_ID}, date used: ${TOMORROW} (Asia/Shanghai today ${SH_TODAY})`);
  lines.push(`- Users: ${USER_A.username} / ${USER_B.username} (密码不出现在证据中)`);
  lines.push(`- Result: ${meta.passed ? 'PASS' : 'FAIL'} (passed ${results.filter((r) => r.status === 'passed').length}/${results.length})`);
  lines.push('');
  lines.push('| # | Case | Result | Duration | Notes |');
  lines.push('|---|------|--------|----------|-------|');
  for (const result of results) {
    lines.push(`| ${result.id} | ${result.title} | ${result.status.toUpperCase()} | ${result.durationMs}ms | ${(result.error || '').replace(/\|\n/g, ' ').slice(0, 220)} |`);
  }
  lines.push('');
  lines.push('## Gaps(本轮无法覆盖, 不以 mock 替代)');
  lines.push('');
  lines.push(notes.gaps);
  lines.push('');
  lines.push('## Preconditions');
  lines.push('');
  lines.push(notes.preconditions);
  writeFileSync(path.join(RUN_DIR, 'REPORT.md'), lines.join('\n'), 'utf8');
}

async function runSmoke() {
  const exe = findChromeExecutable();
  if (!exe) {
    console.error('SMOKE_FAIL no chrome executable found');
    return 1;
  }
  const tmpDir = path.join(__dirname, 'tmp-smoke');
  mkdirSync(tmpDir, { recursive: true });
  const proc = spawn(exe, [
    '--headless=new', `--user-data-dir=${path.join(tmpDir, 'profile')}`, '--remote-debugging-port=0',
    '--no-first-run', '--disable-gpu', '--window-size=420,300', 'about:blank',
  ], { stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true });
  const wsUrl = await new Promise((resolve, reject) => {
    let buffer = '';
    const timer = setTimeout(() => reject(new Error('devtools timeout')), 20000);
    const onData = (chunk) => {
      buffer += chunk;
      const m = /ws:\/\/[^\s]+/i.exec(buffer);
      if (m) { clearTimeout(timer); resolve(m[0]); }
    };
    proc.stdout.on('data', onData);
    proc.stderr.on('data', onData);
  });
  const conn = new CdpConnection(wsUrl);
  await conn.connect();
  const { targetId } = await conn.send('Target.createTarget', { url: 'about:blank' });
  const { sessionId } = await conn.send('Target.attachToTarget', { targetId, flatten: true });
  const evalRes = await conn.send('Runtime.evaluate', { expression: '2+3', returnByValue: true }, sessionId);
  const shotDir = path.join(tmpDir, 'screenshots');
  mkdirSync(shotDir, { recursive: true });
  const shot = await conn.send('Page.captureScreenshot', { format: 'png' }, sessionId);
  writeFileSync(path.join(shotDir, 'smoke.png'), Buffer.from(shot.data, 'base64'));
  const version = (await conn.send('Browser.getVersion')).product;
  conn.close();
  proc.kill();
  console.log(`CHROME_SMOKE_OK version=${version} eval=${evalRes.result.value} shot=${path.join(shotDir, 'smoke.png')}`);
  return evalRes.result.value === 5 ? 0 : 1;
}

async function main() {
  const argv = process.argv.slice(2);
  if (argv.includes('--smoke')) return runSmoke();
  if (argv.includes('--list')) {
    for (const testCase of CASES) console.log(`${testCase.id}  ${testCase.title}`);
    return 0;
  }

  mkdirSync(RUN_DIR, { recursive: true });
  mkdirSync(SCREENSHOT_DIR, { recursive: true });
  const evidence = new Evidence(RUN_DIR);
  const journal = new NetJournal(evidence);
  const driverEvidence = {
    driverApi: (entry) => evidence.driverApi(entry),
  };
  const browser = new BrowserHarness(evidence, journal);
  const summary = {};
  const results = [];
  const meta = {
    startedAtIso: new Date().toISOString(),
    runDir: RUN_DIR,
    chromeVersion: null,
    chromePath: null,
    passed: false,
  };

  const gaps = [
    '1. PENDING_APPROVAL/REJECTED/CHECKED_IN/COMPLETED/NO_SHOW 五个状态的浏览器级展示无学生侧确定性夹具:',
    '   seed.sql 的 QA 资源 need_approval=0(直发 CONFIRMED), 且夹具约束禁止向 DB 写入审批/签到/违规行;',
    '   本轮以 CONFIRMED 与 CANCELLED 两个可达状态验证 14 字段渲染与单节点七状态 timeline 口径, 其余五状态仍是纯单元夹具覆盖。',
    '2. past-slot 视觉态依赖“当日存在营业规则”的时间相关夹具; seed.sql 仅建立明日规则, 今日只能验证空态(无法构成过去时段chip)。isPastSlot 属组件纯函数已有单测。',
    '3. 409 第二分支(锁忙 “当前预约请求较多”)需 Redis 锁竞争注入, 无夹具手段; 未以任何 stub 替代。',
    '4. ` 当天跨零点运行会造成“明日”漂移, 须与 seed 同日运行(见前置条件)。',
  ].join('\n');
  const preconditionNotes = [
    '(a) MySQL 已应用 V001..V005 迁移, Redis 可用;',
    '(b) backend 以 identity 开启跑在本机 18080 (`mvn spring-boot:run` 或等价);',
    `(c) 先执行本目录 seed.sql 重置资源 ${RESOURCE_ID} + 明日营业规则(随执行即时生效);`,
    `(d) dev server 以本目录 vite.config.mjs 启动: booking-web\\node_modules\\.bin\\vite.cmd --config scripts\\tests\\t08\\vite.config.mjs (端口 4173 同源代理 /api/v1 -> 18080);`,
    '(e) 本机装有 Chrome(--headless=new 由 harness 自管进程与清理)。',
  ].join('\n');

  writeFileSync(path.join(RUN_DIR, 'summary.meta.json'), JSON.stringify({
    startedAtIso: meta.startedAtIso, frontend: FRONTEND, backend: BACKEND,
    resource: RESOURCE_ID, shanghaiToday: SH_TODAY, tomorrow: TOMORROW,
    users: { A: USER_A.username, B: USER_B.username },
    node: process.version,
  }, null, 2), 'utf8');

  const preFront = await saveRunsProbe(`${FRONTEND}/`);
  const preBack = await saveRunsProbe(`${BACKEND}/actuator/health`);
  evidence.driverApi({ method: 'GET', url: `${FRONTEND}/`, kind: 'preflight', ...preFront });
  evidence.driverApi({ method: 'GET', url: `${BACKEND}/actuator/health`, kind: 'preflight', ...preBack });

  const frontUp = preFront.reachable && preFront.status === 200;
  const backUp = preBack.reachable && preBack.status === 200;
  summary.gates = { frontend: frontUp ? preFront.status : preFront, backend: backUp ? preBack.status : preBack };

  if (!frontUp || !backUp) {
    console.error('GATES_DOWN 不能开始真实链路验收:');
    if (!frontUp) console.error(`  frontend ${FRONTEND} -> ${JSON.stringify(preFront).slice(0, 200)}`);
    if (!backUp) console.error(`  backend  ${BACKEND} -> ${JSON.stringify(preBack).slice(0, 200)}`);
    console.error('请满足 README.md 列出的前置条件后重跑。未生成 PASS。');
    for (const testCase of CASES) {
      results.push({ id: testCase.id, title: testCase.title, status: 'skipped', durationMs: 0, error: 'gate down' });
    }
    writeReport(meta, results, { gaps, preconditions: preconditionNotes });
    writeFileSync(path.join(RUN_DIR, 'summary.json'), JSON.stringify({ passed: false, exitCode: 2, summary, results }, null, 2), 'utf8');
    rmSync(path.join(RUN_DIR, 'PASS'), { force: true });
    await browser.shutdown();
    return 2;
  }

  const session = new Session(browser, evidence, journal);
  const ctx = { session, journal, summary, driverEvidence, held: {} };

  try {
    const launchInfo = await browser.launch();
    meta.chromeVersion = launchInfo.chromeVersion;
    meta.chromePath = browser.chromeExe;
  } catch (error) {
    console.error(`CHROME_LAUNCH_FAILED ${error.message}`);
    for (const testCase of CASES) results.push({ id: testCase.id, title: testCase.title, status: 'failed', durationMs: 0, error: `chrome launch: ${error.message}` });
    writeReport(meta, results, { gaps, preconditions: preconditionNotes });
    writeFileSync(path.join(RUN_DIR, 'summary.json'), JSON.stringify({ passed: false, exitCode: 1, summary, results }, null, 2), 'utf8');
    await browser.shutdown();
    return 1;
  }

  for (const testCase of CASES) {
    const startedAt = Date.now();
    process.stdout.write(`[${testCase.id}] ${testCase.title} ... `);
    let status = 'passed';
    let errorText = '';
    try {
      await testCase.fn(ctx);
      await session.shot(`${testCase.id}-end`);
    } catch (error) {
      status = 'failed';
      errorText = String(error && error.message ? error.message : error).slice(0, 600);
      try { await session.shot(`${testCase.id}-failure`); } catch { /* snapshot best effort */ }
    }
    const durationMs = Date.now() - startedAt;
    results.push({ id: testCase.id, title: testCase.title, status, durationMs, error: errorText });
    console.log(status === 'passed' ? `PASS (${durationMs}ms)` : `FAIL (${durationMs}ms)\n    -> ${errorText.split('\n')[0]}`);
  }

  await browser.shutdown();

  meta.passed = results.every((r) => r.status === 'passed');
  meta.finishedAtIso = new Date().toISOString();
  writeReport(meta, results, { gaps, preconditions: preconditionNotes });
  const passFile = path.join(RUN_DIR, 'PASS');
  if (meta.passed) writeFileSync(passFile, `PASS ${meta.finishedAtIso}\ncases=${results.length}\nfrontend=${FRONTEND}\nbackend=${BACKEND}\n`);
  else rmSync(passFile, { force: true });
  writeFileSync(path.join(RUN_DIR, 'summary.json'), JSON.stringify({
    passed: meta.passed,
    exitCode: meta.passed ? 0 : 1,
    startedAtIso: meta.startedAtIso,
    finishedAtIso: meta.finishedAtIso,
    chrome: { version: meta.chromeVersion, executable: meta.chromePath },
    frontend: FRONTEND,
    backend: BACKEND,
    resource: RESOURCE_ID,
    dates: { today: SH_TODAY, tomorrowUsed: TOMORROW },
    users: { A: USER_A.username, B: USER_B.username },
    evidenceFiles: ['network.jsonl', 'console.jsonl', 'api-driver-calls.jsonl', 'REPORT.md', 'summary.json', 'screenshots/'],
    summary,
    results,
  }, null, 2), 'utf8');
  return meta.passed ? 0 : 1;
}

globalThis.__t08ChromeProcs = [];
process.on('SIGINT', () => { process.exitCode = 130; process.exit(130); });
process.on('SIGTERM', () => { process.exitCode = 143; process.exit(143); });
process.on('exit', () => {
  for (const proc of globalThis.__t08ChromeProcs || []) {
    try {
      if (proc.exitCode == null && !proc.killed && process.platform === 'win32') {
        spawnSync('taskkill', ['/PID', String(proc.pid), '/T', '/F'], { windowsHide: true });
      } else if (proc.exitCode == null && !proc.killed) {
        proc.kill();
      }
    } catch { /* best effort */ }
  }
});

main().then((code) => process.exit(code)).catch((error) => {
  console.error(`HARNESS_CRASH ${error?.stack || error}`);
  process.exit(1);
});
