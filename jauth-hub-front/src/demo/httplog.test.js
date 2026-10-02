import { afterEach, describe, expect, it, vi } from 'vitest';
import { createHttpLog, fetchLogged, logEntry, prettyJson } from './httplog';

function textResponse(body, ok = true, status = 200) {
  return { ok, status, text: async () => body };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('demo HTTP 日志面板（请求/响应全程入日志）', () => {
  it('fetchLogged：请求行先行，响应回填状态/耗时/摘要与可折叠全文', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => textResponse('{"active":true}')));
    const log = createHttpLog();
    const promise = fetchLogged(log, 'POST', '/oauth2/token', { method: 'POST' });

    // 响应未到时请求行已上屏（level=req，无状态）
    expect(log.entries).toHaveLength(1);
    expect(log.entries[0]).toMatchObject({ method: 'POST', url: '/oauth2/token', status: null, level: 'req' });

    const result = await promise;
    expect(result).toEqual({ status: 200, ok: true, text: '{"active":true}' });
    const entry = log.entries[0];
    expect(entry.level).toBe('ok');
    expect(entry.status).toBe(200);
    expect(entry.elapsed).toBeGreaterThanOrEqual(0);
    expect(entry.summary).toBe('{"active":true}');
    expect(entry.body).toBe('{"active":true}');
  });

  it('长响应体摘要截断到 160 字符加省略号（SSR summarize 同款口径）', async () => {
    const long = 'x'.repeat(300);
    vi.stubGlobal('fetch', vi.fn(async () => textResponse(long)));
    const log = createHttpLog();
    await fetchLogged(log, 'GET', '/api', {});
    expect(log.entries[0].summary).toBe('x'.repeat(160) + '…');
    expect(log.entries[0].body).toHaveLength(300); // 摘要截断、折叠全文保留
  });

  it('非 2xx 响应 level=err 且原样返回 {status, ok, text}（调用方按协议语义解包）', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => textResponse('{"error":"invalid_grant"}', false, 400)));
    const log = createHttpLog();
    const result = await fetchLogged(log, 'POST', '/oauth2/token', {});
    expect(result.ok).toBe(false);
    expect(result.status).toBe(400);
    expect(log.entries[0].level).toBe('err');
  });

  it('网络错误：level=err、摘要记网络错误，异常上抛', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => Promise.reject(new Error('boom'))));
    const log = createHttpLog();
    await expect(fetchLogged(log, 'GET', '/api', {})).rejects.toThrow('boom');
    expect(log.entries[0].level).toBe('err');
    expect(log.entries[0].summary).toContain('网络错误');
  });

  it('logEntry：手工条目整行直显（回调页把授权响应导航记入日志）', () => {
    const log = createHttpLog();
    logEntry(log, 'GET /front/demo/callback?code=abc12345…&state=xyz', 'ok');
    expect(log.entries[0]).toMatchObject({ text: 'GET /front/demo/callback?code=abc12345…&state=xyz', level: 'ok' });
  });

  it('prettyJson：合法 JSON 缩进两格，非法原文返回', () => {
    expect(prettyJson('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(prettyJson('<html>')).toBe('<html>');
  });
});
