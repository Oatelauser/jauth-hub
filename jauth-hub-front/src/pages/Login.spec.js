import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import Login from './Login.vue';

// ResponseRenderer 成功体包装：返回 fetch Response 形状（非函数，供 mock 直接返回）
function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Login 页', () => {
  it('状态 error=true 渲染错误文案', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse({ educational: false, passkeyEnabled: false, error: true, csrfToken: null, csrfHeaderName: null })));
    vi.stubGlobal('location', { search: '', href: '' });
    const wrapper = mount(Login);
    await flushPromises();
    expect(wrapper.find('.alert').text()).toBe(t('login.error'));
  });

  it('企业级单卡结构在场，教学元素（侧栏/发生了什么）退场', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse({ educational: true, passkeyEnabled: true, error: false, csrfToken: null, csrfHeaderName: null })));
    vi.stubGlobal('location', { search: '', href: '' });
    const wrapper = mount(Login);
    await flushPromises();
    expect(wrapper.find('.card').exists()).toBe(true);
    expect(wrapper.findAll('.field')).toHaveLength(2);
    expect(wrapper.find('form').exists()).toBe(true);
    expect(wrapper.find('.btn.primary').exists()).toBe(true);
    expect(wrapper.find('.btn.secondary').exists()).toBe(true); // passkey 次级按钮
    expect(wrapper.find('.sidebar').exists()).toBe(false);
    expect(wrapper.find('.teach').exists()).toBe(false);
    expect(wrapper.find('details').exists()).toBe(false);
  });

  it('提交构造 JSON body + CSRF 头，成功后跳 redirectUrl', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST'
        ? stateResponse({ redirectUrl: '/ok' })
        : stateResponse({ educational: false, passkeyEnabled: false, error: false, csrfToken: 'tok-1', csrfHeaderName: 'X-CSRF-Token' })
    );
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('location', { search: '', href: '' });
    const wrapper = mount(Login);
    await flushPromises();

    await wrapper.find('input[type="text"]').setValue('alice');
    await wrapper.find('input[type="password"]').setValue('secret');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(fetchMock.mock.calls).toHaveLength(2);
    const [path, init] = fetchMock.mock.calls[1];
    expect(path).toBe('/api/login');
    expect(init.method).toBe('POST');
    expect(init.headers['X-CSRF-Token']).toBe('tok-1');
    expect(JSON.parse(init.body)).toEqual({ username: 'alice', password: 'secret' });
    expect(window.location.href).toBe('/ok');
  });

  it('登录落点（B2b）：POST 成功且 redirectUrl === "/" 落 SPA 看板', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST'
        ? stateResponse({ redirectUrl: '/' })
        : stateResponse({ educational: false, passkeyEnabled: false, error: false, csrfToken: null, csrfHeaderName: null })
    );
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('location', { search: '', href: '' });
    const wrapper = mount(Login);
    await flushPromises();

    await wrapper.find('input[type="text"]').setValue('alice');
    await wrapper.find('input[type="password"]').setValue('secret');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(window.location.href).toBe('/front/selfservice/apps');
  });

  it('提交失败（401 A0520）渲染后端 message，不跳转', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST'
        ? { ok: false, status: 401, json: async () => ({ code: 'A0520', message: '用户名或密码错误' }) }
        : stateResponse({ educational: false, passkeyEnabled: false, error: false, csrfToken: 'tok-1', csrfHeaderName: 'X-CSRF-Token' })
    );
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('location', { search: '', href: '' });
    const wrapper = mount(Login);
    await flushPromises();

    await wrapper.find('input[type="text"]').setValue('alice');
    await wrapper.find('input[type="password"]').setValue('wrong');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    expect(wrapper.find('.alert').text()).toBe('用户名或密码错误');
    expect(window.location.href).toBe('');
  });
});
