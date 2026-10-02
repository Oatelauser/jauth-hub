import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import { saveTokens } from '../demo/flow';
import DemoToken from './DemoToken.vue';

const RouterLinkStub = { props: ['to'], template: '<a :href="to"><slot /></a>' };

const demoConfig = {
  introspectEndpoint: 'http://localhost:8080/oauth2/introspect',
  rsClientId: 'demo-rs',
  rsClientSecret: 'demo-rs-dev-only-placeholder',
};

function configResponse() {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: '00000', message: 'ok', data: { educational: true, demoConfig } }),
  };
}

const introspectBody = JSON.stringify({
  active: true,
  sub: 'u-1',
  username: 'alice',
  scope: 'openid profile',
  client_id: 'demo-public',
  token_type: 'Bearer',
  exp: 1760000000,
});

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe('/demo 令牌面板', () => {
  it('无令牌：降级提示且不取配置', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(DemoToken, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    expect(wrapper.find('.alert.warn').text()).toBe(t('demo.token.no-token'));
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('令牌默认折叠（前 12 位）、倒计时走字；reveal 切换全文', async () => {
    saveTokens({ access_token: 'at-abcdef123456789', expires_in: 300 });
    vi.stubGlobal('fetch', vi.fn(() => configResponse()));
    const wrapper = mount(DemoToken, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    expect(wrapper.find('.demo-token').text()).toBe('at-abcdef123' + t('demo.token.masked'));
    expect(wrapper.find('.demo-countdown').text()).toMatch(/^\d+:\d{2}$/);

    await wrapper.find('button.btn').trigger('click'); // 显示 / 隐藏
    expect(wrapper.find('.demo-token').text()).toBe('at-abcdef123456789');
  });

  it('内省演示：POST introspectEndpoint，Basic 机密客户端凭证 + form 体携带 access token', async () => {
    saveTokens({ access_token: 'at-1', expires_in: 300 });
    const fetchMock = vi.fn((path) =>
      path === '/api/demo/config' ? configResponse() : { ok: true, status: 200, text: async () => introspectBody }
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(DemoToken, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    await wrapper.find('button.primary').trigger('click');
    await flushPromises();

    const [path, init] = fetchMock.mock.calls.find(([p]) => p === demoConfig.introspectEndpoint);
    expect(path).toBe(demoConfig.introspectEndpoint);
    expect(init.method).toBe('POST');
    expect(init.headers.Authorization).toBe('Basic ' + btoa('demo-rs:demo-rs-dev-only-placeholder'));
    expect(init.body.toString()).toBe('token=at-1');
    expect(wrapper.find('.demo-kv').text()).toContain('alice');
    expect(wrapper.find('.demo-kv').text()).toContain('openid profile');
    expect(wrapper.find('.demo-json').text()).toContain('"active": true');
    // 第三层教学在场：说明折叠块承载令牌面板教学文案（内省边界说明在其内，zh 主文案）
    expect(wrapper.find('.demo-teach').text()).toContain(t('demo.token.teach'));
  });

  it('链路窜连：持令牌调接口 → /demo/api-call，重新发起 → /demo', async () => {
    saveTokens({ access_token: 'at-1', expires_in: 300 });
    vi.stubGlobal('fetch', vi.fn(() => configResponse()));
    const wrapper = mount(DemoToken, { global: { stubs: { RouterLink: RouterLinkStub } } });
    await flushPromises();

    const links = wrapper.findAll('a.btn').map((a) => a.attributes('href'));
    expect(links).toContain('/demo/api-call');
    expect(links).toContain('/demo');
  });
});
