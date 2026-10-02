import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import OrgMembers from './OrgMembers.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

const RouterLinkStub = { props: ['to'], template: '<a :href="to"><slot /></a>' };
const state = {
  educational: false,
  membersSupported: true,
  org: { orgId: 'org1', orgName: 'Acme' },
  members: [
    { userId: 'u1', username: 'owner', role: 'OWNER', createdAt: '2026-10-01T10:00:00Z' },
    { userId: 'u2', username: 'alice', role: 'MEMBER', createdAt: '2026-10-02T10:00:00Z' },
  ],
  csrfToken: 'tok-8',
  csrfHeaderName: 'X-CSRF-Token',
};

function mountAt(fetchMock) {
  vi.stubGlobal('location', { pathname: '/front/selfservice/orgs/org1/members', search: '', href: '' });
  vi.stubGlobal('fetch', fetchMock);
  return mount(OrgMembers, { global: { stubs: { RouterLink: RouterLinkStub } } });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('成员管理页（OWNER 面）', () => {
  it('成员列表渲染 + 按用户名添加：POST {username}', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' ? stateResponse({}) : stateResponse(state)
    );
    const wrapper = mountAt(fetchMock);
    await flushPromises();

    expect(wrapper.findAll('tbody tr')).toHaveLength(2);
    expect(wrapper.find('tbody .pill').text()).toBe('OWNER');

    await wrapper.find('form input[type="text"]').setValue('bob');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    const [path, init] = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST');
    expect(path).toBe('/selfservice/orgs/org1/members');
    expect(init.headers['X-CSRF-Token']).toBe('tok-8');
    expect(JSON.parse(init.body)).toEqual({ username: 'bob' });
  });

  it('移除（sudo 类）必经确认，确认后 DELETE 带路径 userId 与 CSRF 头', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'DELETE' ? stateResponse({}) : stateResponse(state)
    );
    const wrapper = mountAt(fetchMock);
    await flushPromises();

    await wrapper.findAll('tbody tr')[1].findAll('button').at(1).trigger('click'); // 移除
    expect(wrapper.find('.dialog').exists()).toBe(true);
    await wrapper.find('.dialog .btn.danger').trigger('click');
    await flushPromises();

    const deleteCall = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'DELETE');
    expect(deleteCall[0]).toBe('/selfservice/orgs/org1/members/u2');
    expect(deleteCall[1].headers['X-CSRF-Token']).toBe('tok-8');
  });
});
