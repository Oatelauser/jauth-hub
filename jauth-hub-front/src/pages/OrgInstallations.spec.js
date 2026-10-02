import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import OrgInstallations from './OrgInstallations.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

const RouterLinkStub = { props: ['to'], template: '<a :href="to"><slot /></a>' };
const state = {
  educational: false,
  installationsSupported: true,
  org: { orgId: 'org1', orgName: 'Acme' },
  pending: [
    {
      id: 'i1',
      clientName: 'Org App',
      clientLabel: 'app_org',
      status: 'PENDING',
      ceilingScopes: [],
      requestedByName: 'alice',
      requestedScopes: ['openid', 'profile'],
      approvedByName: null,
      approvedAt: null,
      createdAt: '2026-10-02T09:00:00Z',
    },
  ],
  installations: [
    {
      id: 'i2',
      clientName: 'Org App',
      clientLabel: 'app_org',
      status: 'APPROVED',
      ceilingScopes: ['openid'],
      requestedByName: 'alice',
      requestedScopes: ['openid'],
      approvedByName: 'owner',
      approvedAt: '2026-10-02T10:00:00Z',
      createdAt: '2026-10-02T09:00:00Z',
    },
    {
      id: 'i3',
      clientName: 'Org App 2',
      clientLabel: 'app_org2',
      status: 'REJECTED',
      ceilingScopes: [],
      requestedByName: 'bob',
      requestedScopes: ['openid'],
      approvedByName: null,
      approvedAt: null,
      createdAt: '2026-10-01T09:00:00Z',
    },
  ],
  scopes: [{ name: 'openid', description: '身份标识' }],
  csrfToken: 'tok-6',
  csrfHeaderName: 'X-CSRF-Token',
};

function mountAt(fetchMock) {
  vi.stubGlobal('location', { pathname: '/front/selfservice/orgs/org1/installations', search: '', href: '' });
  vi.stubGlobal('fetch', fetchMock);
  return mount(OrgInstallations, { global: { stubs: { RouterLink: RouterLinkStub } } });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('安装审批页（OWNER 面）', () => {
  it('status pill 前端字典 status-{枚举名}：APPROVED→success、REJECTED→danger、PENDING→muted', async () => {
    const wrapper = mountAt(vi.fn(() => stateResponse(state)));
    await flushPromises();

    const pills = wrapper.findAll('tbody .pill');
    expect(pills).toHaveLength(2);
    expect(pills[0].classes()).toContain('pill-success');
    expect(pills[0].text()).toBe(t('orginst.status-APPROVED'));
    expect(pills[1].classes()).toContain('pill-danger');
    expect(pills[1].text()).toBe(t('orginst.status-REJECTED'));

    const pendingPill = wrapper.find('.panel'); // 待批分区在场（双分区）
    expect(pendingPill.exists()).toBe(true);
  });

  it('批准：勾选集即 ceiling，POST approve 载荷为收窄后的 scopes', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' ? stateResponse({}) : stateResponse(state)
    );
    const wrapper = mountAt(fetchMock);
    await flushPromises();

    const panel = wrapper.find('.panel');
    const checkboxes = panel.findAll('input[type="checkbox"]');
    expect(checkboxes).toHaveLength(2);
    expect(checkboxes.every((c) => c.element.checked)).toBe(true); // 默认全选

    await checkboxes.at(1).setValue(false); // 收窄：去掉 profile
    await panel.find('.btn.primary').trigger('click'); // 批准
    await flushPromises();

    const [path, init] = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST');
    expect(path).toBe('/selfservice/orgs/org1/installations/i1/approve');
    expect(init.headers['X-CSRF-Token']).toBe('tok-6');
    expect(JSON.parse(init.body)).toEqual({ scopes: ['openid'] });
  });

  it('撤销必经确认对话框，确认后 POST revoke', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' ? stateResponse({}) : stateResponse(state)
    );
    const wrapper = mountAt(fetchMock);
    await flushPromises();

    await wrapper.find('tbody button').trigger('click'); // APPROVED 行的撤销键
    expect(wrapper.find('.dialog').exists()).toBe(true);
    await wrapper.find('.dialog .btn.danger').trigger('click');
    await flushPromises();

    const calls = fetchMock.mock.calls.filter(([p, i]) => i && i.method === 'POST');
    const revokeCall = calls.find(([p]) => p.endsWith('/revoke'));
    expect(revokeCall[0]).toBe('/selfservice/orgs/org1/installations/i2/revoke');
  });
});
