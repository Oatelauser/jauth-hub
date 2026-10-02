// /demo 教学区 HTTP 日志面板状态（B4）：SSR demo.js httplog 的 Vue 化。请求行即时上屏，
// 响应回填状态/耗时/摘要，响应体全文可折叠（教学区逐请求观察的三层教学之一）。
// 不走 api.js 包装：token/introspect 是 OAuth2 协议端点（非 ResponseRenderer 形状），
// 教学要看的正是原始报文。
import { reactive } from 'vue';

export function createHttpLog() {
  return reactive({ entries: [], nextId: 1 });
}

// 手工条目（回调页把授权响应导航本身记入日志等场景）：文本整行直显
export function logEntry(log, text, level) {
  const entry = { id: log.nextId++, time: new Date().toLocaleTimeString(), level: level || '', text };
  log.entries.push(entry);
  return entry;
}

// 记录并执行一次 fetch：method/URL 请求行先行，响应回填 status/耗时/摘要与可折叠全文。
// 返回 {status, ok, text}——调用方按协议语义自行解包（demo 页不假设统一响应形状）
export async function fetchLogged(log, method, url, options) {
  const entry = logRequest(log, method, url);
  const started = performance.now();
  try {
    const response = await fetch(url, options);
    const bodyText = await response.text();
    entry.status = response.status;
    entry.elapsed = Math.round(performance.now() - started);
    entry.level = response.ok ? 'ok' : 'err';
    entry.summary = summarize(bodyText);
    entry.body = bodyText || null;
    return { status: response.status, ok: response.ok, text: bodyText };
  } catch (error) {
    entry.level = 'err';
    entry.summary = '网络错误：' + error;
    throw error;
  }
}

function logRequest(log, method, url) {
  const entry = {
    id: log.nextId++,
    time: new Date().toLocaleTimeString(),
    level: 'req',
    method,
    url,
    status: null,
    elapsed: null,
    summary: '',
    body: null,
  };
  log.entries.push(entry);
  return entry;
}

function summarize(bodyText) {
  const compact = String(bodyText).replace(/\s+/g, ' ').trim();
  return compact.length > 160 ? compact.slice(0, 160) + '…' : compact;
}

export function prettyJson(text) {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}
