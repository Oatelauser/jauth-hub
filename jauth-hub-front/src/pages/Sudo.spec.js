import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import Sudo from './Sudo.vue';

function okFetch(data) {
  const body = { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
  return vi.fn(async () => body);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('Sudo 页', () => {
  it('sudoEnabled=false 渲染未启用态，无验证按钮无返回链接', async () => {
    vi.stubGlobal('fetch', okFetch({ educational: false, sudoEnabled: false, returnTo: '/selfservice/apps', csrfToken: null, csrfHeaderName: null }));
    vi.stubGlobal('location', { search: '?returnTo=%2Fselfservice%2Fapps', href: '' });
    const wrapper = mount(Sudo);
    await flushPromises();

    expect(wrapper.text()).toContain(t('sudo.unsupported'));
    expect(wrapper.find('button').exists()).toBe(false);
    expect(wrapper.find('a').exists()).toBe(false);
  });

  it('sudoEnabled=true：returnTo 展示为返回链接，returnTo 参数透传状态面', async () => {
    const fetchMock = okFetch({ educational: false, sudoEnabled: true, returnTo: '/selfservice/apps', csrfToken: 'tok-s', csrfHeaderName: 'X-CSRF-Token' });
    vi.stubGlobal('fetch', fetchMock);
    vi.stubGlobal('location', { search: '?returnTo=%2Fselfservice%2Fapps', href: '' });
    const wrapper = mount(Sudo);
    await flushPromises();

    expect(fetchMock.mock.calls[0][0]).toBe('/api/sudo?returnTo=%2Fselfservice%2Fapps');
    expect(wrapper.find('button').text()).toBe(t('sudo.verify'));
    expect(wrapper.find('a').attributes('href')).toBe('/selfservice/apps');
    expect(wrapper.find('a').text()).toBe(t('sudo.back'));
  });
});
