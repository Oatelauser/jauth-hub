import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import { saveVerifierAndState } from '../demo/flow';
import DemoCallback from './DemoCallback.vue';

const RouterLinkStub = { props: ['to'], template: '<a :href="to"><slot /></a>' };

const demoConfig = {
  issuer: 'http://localhost:8080',
  tokenEndpoint: 'http://localhost:8080/oauth2/token',
  clientId: 'demo-public',
  redirectUri: 'http://localhost:8080/front/demo/callback',
};

function configResponse() {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: '00000', message: 'ok', data: { educational: true, demoConfig } }),
  };
}

const tokenBody = JSON.stringify({
  access_token: 'at-1',
  refresh_token: 'rt-1',
  token_type: 'Bearer',
  expires_in: 300,
  scope: 'openid profile',
});

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

function mountCallback(search) {
  vi.stubGlobal('location', { search, pathname: '/front/demo/callback', href: '' });
  return mount(DemoCallback, { global: { stubs: { RouterLink: RouterLinkStub } } });
}

describe('/demo 回调页', () => {
  it('code+state 校验通过后浏览器直连 token 端点换令牌（form 体五字段），令牌入 sessionStorage', async () => {
    saveVerifierAndState('v-1', 'st-1');
    const fetchMock = vi.fn((path) =>
      path === '/api/demo/config'
        ? configResponse()
        : { ok: true, status: 200, text: async () => tokenBody }
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mountCallback('?code=the-code&state=st-1');
    await flushPromises();
    await flushPromises();

    // 授权响应导航先入日志（code 截短防泄屏）
    expect(wrapper.find('.demo-log-entry').text()).toContain('GET /front/demo/callback?code=the-code…&state=st-1');

    const exchange = fetchMock.mock.calls.find(([path]) => path === demoConfig.tokenEndpoint);
    expect(exchange[1].method).toBe('POST');
    expect(exchange[1].headers['Content-Type']).toBe('application/x-www-form-urlencoded');
    expect(exchange[1].body.toString()).toBe(
      'grant_type=authorization_code&code=the-code&redirect_uri=' +
        encodeURIComponent(demoConfig.redirectUri) +
        '&client_id=demo-public&code_verifier=v-1'
    );

    // 令牌入 sessionStorage、verifier/state 一次性取清、结果表 + 令牌面板入口
    expect(JSON.parse(sessionStorage.getItem('jauth_demo_tokens')).access_token).toBe('at-1');
    expect(sessionStorage.getItem('jauth_demo_verifier')).toBeNull();
    expect(wrapper.find('.demo-kv').text()).toContain('Bearer');
    expect(wrapper.find('.demo-kv').text()).toContain('300 s');
    expect(wrapper.find('.demo-kv').text()).toContain(t('demo.callback.has-refresh-token'));
    expect(wrapper.find('a.btn.primary').attributes('href')).toBe('/demo/token');
  });

  it('state 校验失败：如实展示中止原因，不发起换令牌 POST', async () => {
    saveVerifierAndState('v-1', 'st-origin');
    const fetchMock = vi.fn(() => configResponse());
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mountCallback('?code=the-code&state=st-forged');
    await flushPromises();

    expect(wrapper.find('.alert.error').text()).toBe(t('demo.callback.state-mismatch'));
    expect(fetchMock.mock.calls.filter(([path]) => path === demoConfig.tokenEndpoint)).toHaveLength(0);
    expect(sessionStorage.getItem('jauth_demo_verifier')).toBeNull(); // 已取走作废，不留可重放因子
  });

  it('未存 verifier（直接打开回调）：提示回首页重新发起', async () => {
    vi.stubGlobal('fetch', vi.fn(() => configResponse()));
    const wrapper = mountCallback('?code=the-code&state=st-1');
    await flushPromises();

    expect(wrapper.find('.alert.error').text()).toBe(t('demo.callback.missing-verifier'));
  });

  it('授权失败响应（error 参数）：如实展示 error 与 error_description', async () => {
    vi.stubGlobal('fetch', vi.fn(() => configResponse()));
    const wrapper = mountCallback('?error=access_denied&error_description=nope');
    await flushPromises();

    expect(wrapper.find('.alert.error').text()).toBe('access_denied：nope');
  });

  it('无 code 参数：展示无授权码错误', async () => {
    vi.stubGlobal('fetch', vi.fn(() => configResponse()));
    const wrapper = mountCallback('?state=st-1');
    await flushPromises();

    expect(wrapper.find('.alert.error').text()).toBe(t('demo.callback.no-code'));
  });
});
