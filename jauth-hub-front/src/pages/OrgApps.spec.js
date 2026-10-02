import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import OrgApps from './OrgApps.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

const RouterLinkStub = { props: ['to'], template: '<a :href="to"><slot /></a>' };
const state = {
  educational: false,
  orgAppsSupported: true,
  org: { orgId: 'org1', orgName: 'Acme' },
  apps: [
    { id: 'a1', clientId: 'app_org', name: '组织应用', confidential: false, redirectUris: ['https://o.example.com/cb'], createdAt: '2026-10-01T10:00:00Z' },
  ],
  csrfToken: 'tok-5',
  csrfHeaderName: 'X-CSRF-Token',
};

function mountAt(path, fetchMock) {
  vi.stubGlobal('location', { pathname: path, search: '', href: '' });
  vi.stubGlobal('fetch', fetchMock);
  return mount(OrgApps, { global: { stubs: { RouterLink: RouterLinkStub } } });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('组织应用页（OWNER 面）', () => {
  it('A0508 失败渲染为页面错误态（无权限不白屏），不出列表与表单', async () => {
    const wrapper = mountAt('/front/selfservice/orgs/org1/apps', vi.fn(() => ({
      ok: false,
      status: 403,
      json: async () => ({ code: 'A0508', message: '仅组织 OWNER 可访问' }),
    })));
    await flushPromises();

    expect(wrapper.find('.alert.error').text()).toBe('仅组织 OWNER 可访问');
    expect(wrapper.find('table').exists()).toBe(false);
    expect(wrapper.find('form').exists()).toBe(false);
  });

  it('注册表单载荷打 org 路径：POST /selfservice/orgs/{orgId}/apps {name, redirectUris, confidential}', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' && path === '/selfservice/orgs/org1/apps'
        ? stateResponse({ id: 'a2', name: '新组织应用', clientId: 'app_org2', confidential: false, redirectUris: [] })
        : stateResponse(state)
    );
    const wrapper = mountAt('/front/selfservice/orgs/org1/apps', fetchMock);
    await flushPromises();

    expect(wrapper.find('.pill').text()).toBe('Acme'); // org 徽标
    const form = wrapper.find('form'); // 页内注册表单（SSR 同款内联式）
    await form.find('input[type="text"]').setValue('新组织应用');
    await form.find('textarea').setValue('https://n.example.com/cb');
    await form.trigger('submit');
    await flushPromises();

    const [path, init] = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST');
    expect(path).toBe('/selfservice/orgs/org1/apps');
    expect(JSON.parse(init.body)).toEqual({ name: '新组织应用', redirectUris: 'https://n.example.com/cb', confidential: false });
  });
});
