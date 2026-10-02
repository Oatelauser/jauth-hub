import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import MyOrgs from './MyOrgs.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

const RouterLinkStub = { props: ['to'], template: '<a :href="to"><slot /></a>' };
const state = {
  educational: false,
  orgsSupported: true,
  orgs: [
    { orgId: 'org1', orgName: 'Acme', role: 'OWNER' },
    { orgId: 'org2', orgName: 'Beta', role: 'MEMBER' },
  ],
  csrfToken: 'tok-4',
  csrfHeaderName: 'X-CSRF-Token',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('我的组织页', () => {
  it('OWNER 行链到三个 org 子页（窜连），MEMBER 行无入口', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse(state)));
    const wrapper = mount(MyOrgs, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    const rows = wrapper.findAll('tbody tr');
    expect(rows[0].findAll('a').map((a) => a.attributes('href'))).toEqual([
      '/selfservice/orgs/org1/installations',
      '/selfservice/orgs/org1/apps',
      '/selfservice/orgs/org1/members',
    ]);
    expect(rows[1].findAll('a')).toHaveLength(0);
  });

  it('创建组织：POST {name} 带 CSRF 头', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' ? stateResponse({}) : stateResponse(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(MyOrgs, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    await wrapper.find('input[type="text"]').setValue('研发一部');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    const [path, init] = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST');
    expect(path).toBe('/selfservice/my-orgs');
    expect(init.headers['X-CSRF-Token']).toBe('tok-4');
    expect(JSON.parse(init.body)).toEqual({ name: '研发一部' });
  });
});
