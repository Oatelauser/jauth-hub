import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import { saveTokens } from '../demo/flow';
import DemoApiCall from './DemoApiCall.vue';

const demoConfig = {
  whoamiUri: 'http://localhost:8080/api/demo/whoami',
};

function configResponse() {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: '00000', message: 'ok', data: { educational: true, demoConfig } }),
  };
}

const whoamiBody = JSON.stringify({
  code: '00000',
  message: 'ok',
  data: { sub: 'u-1', username: 'alice', scope: 'openid profile', authorities: ['SCOPE_openid', 'SCOPE_profile'] },
});

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

async function mountWithToken() {
  saveTokens({ access_token: 'at-1', expires_in: 300 });
  const fetchMock = vi.fn((path, init) => {
    if (path === '/api/demo/config') return configResponse();
    const bare = !(init && init.headers && init.headers.Authorization);
    return bare
      ? { ok: false, status: 401, text: async () => '' }
      : { ok: true, status: 200, text: async () => whoamiBody };
  });
  vi.stubGlobal('fetch', fetchMock);
  const wrapper = mount(DemoApiCall);
  await flushPromises();
  return { wrapper, fetchMock };
}

describe('/demo API 调用页', () => {
  it('无令牌：降级提示', async () => {
    vi.stubGlobal('fetch', vi.fn(() => configResponse()));
    const wrapper = mount(DemoApiCall);
    await flushPromises();

    expect(wrapper.find('.alert.warn').text()).toBe(t('demo.api.need-token'));
  });

  it('带令牌调用：GET whoamiUri 携 Bearer；SimpleResponse.ok 解包展示用户视图（sub/username/scope/authorities）', async () => {
    const { wrapper, fetchMock } = await mountWithToken();

    await wrapper.find('button.primary').trigger('click');
    await flushPromises();

    const [path, init] = fetchMock.mock.calls.find(([p]) => p === demoConfig.whoamiUri);
    expect(path).toBe(demoConfig.whoamiUri);
    expect(init.headers.Authorization).toBe('Bearer at-1');

    expect(wrapper.find('.demo-status').text()).toBe('HTTP 200');
    const view = wrapper.find('.demo-kv').text();
    expect(view).toContain('alice');
    expect(view).toContain('openid profile');
    expect(view).toContain('SCOPE_openid SCOPE_profile');
    // 原始报文照旧全文展示（教学价值：看见资源服务器的原样应答）
    expect(wrapper.find('.demo-json').text()).toContain('"username": "alice"');
  });

  it('不带令牌调用：预期 401 对照——红徽标、无用户视图', async () => {
    const { wrapper, fetchMock } = await mountWithToken();

    const buttons = wrapper.findAll('button');
    await buttons[1].trigger('click'); // 不带令牌调用
    await flushPromises();

    const bare = fetchMock.mock.calls.filter(([p]) => p === demoConfig.whoamiUri)[0];
    expect(bare[1].headers).toEqual({});
    expect(wrapper.find('.demo-status').text()).toBe('HTTP 401');
    expect(wrapper.find('.demo-status').classes()).toContain('s4xx');
    expect(wrapper.find('.demo-kv').exists()).toBe(false);
  });
});
