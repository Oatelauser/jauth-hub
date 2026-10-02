import { afterEach, describe, expect, it, vi } from 'vitest';
import { getJson, postJson } from './api';

// ResponseRenderer 形状 {code,message,data}；成功 code='00000'
function mockFetch(payload, ok = true, status = 200) {
  return vi.fn(async () => ({ ok, status, json: async () => payload }));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('api fetch 包装', () => {
  it('code=00000 解出 data', async () => {
    vi.stubGlobal('fetch', mockFetch({ code: '00000', message: 'ok', data: { x: 1 } }));
    await expect(getJson('/api/x')).resolves.toEqual({ x: 1 });
  });

  it('非 00000 抛 message 给页面渲染', async () => {
    vi.stubGlobal('fetch', mockFetch({ code: 'A0520', message: '用户名或密码错误' }));
    await expect(getJson('/api/x')).rejects.toThrow('用户名或密码错误');
  });

  it('无统一形状体的 401（安全链未认证拦截）整页跳登录', async () => {
    vi.stubGlobal('fetch', mockFetch(null, false, 401));
    vi.stubGlobal('location', { search: '', href: '' });
    await expect(getJson('/api/consent?client_id=c')).rejects.toThrow('unauthenticated');
    expect(window.location.href).toBe('/front/login');
  });

  it('code=A0515（sudo 过期）整页跳强验证页，returnTo 去 /front 前缀并保留查询串', async () => {
    vi.stubGlobal('fetch', mockFetch({ code: 'A0515', message: '需要强验证' }, false, 403));
    vi.stubGlobal('location', { pathname: '/front/selfservice/passkey', search: '?tab=1', href: '' });
    await expect(getJson('/api/selfservice/passkey')).rejects.toThrow('sudo-required');
    expect(window.location.href).toBe('/front/sudo?returnTo=' + encodeURIComponent('/selfservice/passkey?tab=1'));
  });

  it('带 code 的 401（登录桥 A0520/A0521 业务失败）不跳转、抛 message', async () => {
    vi.stubGlobal('fetch', mockFetch({ code: 'A0521', message: '尝试过多已锁定' }, false, 401));
    vi.stubGlobal('location', { search: '', href: '' });
    await expect(postJson('/api/login', { username: 'u', password: 'p' })).rejects.toThrow('尝试过多已锁定');
    expect(window.location.href).toBe('');
  });

  it('postJson 挂 CSRF 头并构造 JSON 体', async () => {
    const fetchMock = mockFetch({ code: '00000', message: 'ok', data: {} });
    vi.stubGlobal('fetch', fetchMock);
    await postJson('/api/login', { username: 'u', password: 'p' }, { csrfToken: 'tok-9', csrfHeaderName: 'X-CSRF-Token' });
    const [path, init] = fetchMock.mock.calls[0];
    expect(path).toBe('/api/login');
    expect(init.method).toBe('POST');
    expect(init.headers['X-CSRF-Token']).toBe('tok-9');
    expect(JSON.parse(init.body)).toEqual({ username: 'u', password: 'p' });
  });
});
