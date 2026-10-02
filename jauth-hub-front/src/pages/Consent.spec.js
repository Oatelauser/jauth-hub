import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import Consent from './Consent.vue';

const consentData = (over = {}) => ({
  clientId: 'demo-client',
  state: 'st-1',
  clientName: 'Demo App',
  educational: false,
  orgGuide: false,
  orgChoices: [],
  orgBadge: null,
  scopes: [
    { name: 'openid', description: 'identity', checked: true, grantable: true, alreadyGranted: false },
    { name: 'profile', description: 'profile', checked: true, grantable: true, alreadyGranted: true },
    { name: 'admin', description: 'over ceiling', checked: false, grantable: false, alreadyGranted: false },
  ],
  csrfToken: 'tok-c',
  csrfHeaderName: 'X-CSRF-Token',
  ...over,
});

function okFetch(data) {
  const body = { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
  return vi.fn(async () => body);
}

const PAGE_QUERY = '?client_id=cid&state=st&scope=openid';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Consent 页', () => {
  it('scope 列表：预勾选/grantable=false 禁用/已授权徽标 + orgBadge + 透传参数 + 表单契约', async () => {
    const fetchMock = okFetch(consentData({ orgBadge: 'acme' }));
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('location', { search: PAGE_QUERY, href: '' });
    const wrapper = mount(Consent);
    await flushPromises();

    // 参数与 SSR 页逐一同名透传
    expect(fetchMock.mock.calls[0][0]).toBe('/api/consent?client_id=cid&state=st&scope=openid');

    const boxes = wrapper.findAll('input[name="scope"]');
    expect(boxes).toHaveLength(3);
    expect(boxes[0].element.checked).toBe(true);
    expect(boxes[2].attributes('disabled')).toBeDefined();
    expect(wrapper.find('.scope-granted').text()).toBe(t('consent.granted'));
    expect(wrapper.find('.org-badge strong').text()).toBe('acme');

    // 原生表单导航契约（照 SSR consent.html）：action + 隐藏域 + action 按钮
    const form = wrapper.find('form');
    expect(form.attributes('action')).toBe('/oauth2/authorize');
    expect(form.attributes('method')).toBe('post');
    expect(wrapper.find('input[name="client_id"]').attributes('value')).toBe('demo-client');
    expect(wrapper.find('input[name="state"]').attributes('value')).toBe('st-1');
    expect(wrapper.find('input[name="_csrf"]').attributes('value')).toBe('tok-c');
    expect(wrapper.find('button[value="authorize"]').exists()).toBe(true);
    expect(wrapper.find('button[value="deny"]').exists()).toBe(true);
  });

  it('org 多候选：选择器链接重入本 SPA 页挂 org，不出 scope 表单与同意按钮', async () => {
    vi.stubGlobal('fetch', okFetch(consentData({ orgChoices: [{ orgId: 'o1', orgName: 'acme', href: '/oauth2/authorize?x' }] })));
    vi.stubGlobal('location', { search: PAGE_QUERY, href: '' });
    const wrapper = mount(Consent);
    await flushPromises();

    const link = wrapper.find('a.org-choice');
    expect(link.text()).toBe('acme');
    expect(link.attributes('href')).toBe('/front/consent?client_id=cid&state=st&scope=openid&org=o1');
    expect(wrapper.find('input[name="scope"]').exists()).toBe(false);
    expect(wrapper.find('button[value="authorize"]').exists()).toBe(false);
    expect(wrapper.find('button[value="deny"]').exists()).toBe(true);
  });

  it('orgGuide 引导态：不出 scope 表单与同意按钮，保留拒绝出口', async () => {
    vi.stubGlobal('fetch', okFetch(consentData({ orgGuide: true })));
    vi.stubGlobal('location', { search: PAGE_QUERY, href: '' });
    const wrapper = mount(Consent);
    await flushPromises();

    expect(wrapper.find('.org-guide').text()).toBe(t('consent.orgGuide'));
    expect(wrapper.find('input[name="scope"]').exists()).toBe(false);
    expect(wrapper.find('button[value="authorize"]').exists()).toBe(false);
    expect(wrapper.find('button[value="deny"]').exists()).toBe(true);
  });
});
