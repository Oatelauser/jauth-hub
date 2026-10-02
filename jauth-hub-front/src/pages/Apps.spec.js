import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import Apps from './Apps.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

const RouterLinkStub = { props: ['to'], template: '<a :href="to"><slot /></a>' };
const state = {
  educational: false,
  appsSupported: true,
  passkeyEnabled: true,
  apps: [
    { clientId: 'client-1', clientName: 'Demo App', scopes: ['openid', 'profile'], lastAuthorizedAt: '2026-09-29T10:00:00Z' },
  ],
  csrfToken: 'tok-1',
  csrfHeaderName: 'X-CSRF-Token',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('看板 Apps 页', () => {
  it('列表渲染 + passkeyEnabled 时出通行密钥入口（窜连）', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse(state)));
    const wrapper = mount(Apps, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    expect(wrapper.findAll('tbody tr')).toHaveLength(1);
    expect(wrapper.find('tbody td').text()).toBe('Demo App');
    expect(wrapper.find('tbody tr td:nth-child(2)').text()).toBe('openid profile');
    expect(wrapper.find('.hint a').attributes('href')).toBe('/selfservice/passkey');
  });

  it('revoke 必经确认对话框，确认后 POST 撤销端点带 CSRF 头', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' ? stateResponse({}) : stateResponse(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(Apps, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    await wrapper.find('tbody button').trigger('click');
    expect(wrapper.find('.dialog').exists()).toBe(true); // 危险动作先确认
    const callsBefore = fetchMock.mock.calls.length;

    await wrapper.find('.dialog .btn.danger').trigger('click');
    await flushPromises();

    const revokeCall = fetchMock.mock.calls.find(([path, init]) => init && init.method === 'POST');
    expect(revokeCall[0]).toBe('/selfservice/apps/client-1/revoke');
    expect(revokeCall[1].headers['X-CSRF-Token']).toBe('tok-1');
    // 确认撤销 + 确认后重载状态面：两次请求
    expect(fetchMock.mock.calls.length).toBe(callsBefore + 2);
  });

  it('appsSupported=false 渲染降级提示，不出表格', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse({ ...state, appsSupported: false, apps: [] })));
    const wrapper = mount(Apps, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    expect(wrapper.find('.alert.warn').text()).toBe(t('apps.unsupported'));
    expect(wrapper.find('table').exists()).toBe(false);
  });
});
