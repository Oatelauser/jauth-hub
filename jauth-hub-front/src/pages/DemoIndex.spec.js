import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import { sha256B64Url } from '../demo/flow';
import DemoIndex from './DemoIndex.vue';

const config = {
  educational: true,
  demoConfig: {
    issuer: 'http://localhost:8080',
    authorizeEndpoint: 'http://localhost:8080/oauth2/authorize',
    tokenEndpoint: 'http://localhost:8080/oauth2/token',
    introspectEndpoint: 'http://localhost:8080/oauth2/introspect',
    clientId: 'demo-public',
    redirectUri: 'http://localhost:8080/front/demo/callback',
    scope: 'openid profile',
    rsClientId: 'demo-rs',
    rsClientSecret: 'demo-rs-dev-only-placeholder',
    whoamiUri: 'http://localhost:8080/api/demo/whoami',
  },
};

function configResponse() {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data: config }) };
}

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
});

describe('/demo 首页', () => {
  it('消费 GET /api/demo/config；三层教学在场（步骤条高亮第 1 步 + httplog 面板 + 说明折叠块）', async () => {
    const fetchMock = vi.fn(() => configResponse());
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(DemoIndex);
    await flushPromises();

    expect(fetchMock.mock.calls[0][0]).toBe('/api/demo/config');
    expect(wrapper.find('.demo-client-line').html()).toContain('demo-public');
    expect(wrapper.find('.demo-steps li.active').text()).toBe(t('demo.flow.step1'));
    expect(wrapper.find('.demo-log-entries').exists()).toBe(true);
    expect(wrapper.find('.demo-teach').exists()).toBe(true);
  });

  it('开始授权：verifier/state 入 sessionStorage，authorize URL 携 S256 challenge 后顶层导航', async () => {
    vi.stubGlobal('fetch', vi.fn(() => configResponse()));
    vi.stubGlobal('location', { href: '' });
    const wrapper = mount(DemoIndex);
    await flushPromises();

    await wrapper.find('button.primary').trigger('click');
    await flushPromises();

    const href = window.location.href;
    expect(href.startsWith(config.demoConfig.authorizeEndpoint + '?response_type=code')).toBe(true);
    const params = new URLSearchParams(href.slice(href.indexOf('?') + 1));
    expect(params.get('client_id')).toBe('demo-public');
    expect(params.get('redirect_uri')).toBe(config.demoConfig.redirectUri);
    expect(params.get('scope')).toBe('openid profile');
    expect(params.get('code_challenge_method')).toBe('S256');
    // challenge 必须是已存 verifier 的 SHA-256 派生（PKCE 配对，防截码）
    const verifier = sessionStorage.getItem('jauth_demo_verifier');
    await expect(sha256B64Url(verifier)).resolves.toBe(params.get('code_challenge'));
    expect(params.get('state')).toBe(sessionStorage.getItem('jauth_demo_state'));
  });

  it('educational=false 时三层教学整块退场（照 SSR th:if 语义），主操作仍在', async () => {
    const degraded = { ...config, educational: false };
    vi.stubGlobal(
      'fetch',
      vi.fn(() => ({ ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data: degraded }) }))
    );
    const wrapper = mount(DemoIndex);
    await flushPromises();

    expect(wrapper.find('.demo-steps').exists()).toBe(false);
    expect(wrapper.find('.demo-side').exists()).toBe(false);
    expect(wrapper.find('.demo-teach').exists()).toBe(false);
    expect(wrapper.find('button.primary').exists()).toBe(true);
  });
});
