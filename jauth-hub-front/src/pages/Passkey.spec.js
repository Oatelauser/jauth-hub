import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import Passkey from './Passkey.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}
function rawResponse(data, status = 200) {
  return { ok: status < 400, status, json: async () => data };
}

const state = {
  educational: false,
  passkeyEnabled: true,
  credentials: [
    { credentialId: 'cred-1', credentialIdShort: 'AbCdEfGh…', label: '我的手机', createdAt: '2026-10-01T10:00:00Z', lastUsedAt: null },
    { credentialId: 'cred-2', credentialIdShort: 'ZzYyXxWv…', label: null, createdAt: '2026-10-02T10:00:00Z', lastUsedAt: null },
  ],
  csrfToken: 'tok-9',
  csrfHeaderName: 'X-CSRF-Token',
};

afterEach(() => {
  vi.unstubAllGlobals();
  delete window.PublicKeyCredential;
});

describe('通行密钥页', () => {
  it('凭据列表渲染（label=null 回退未命名）', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse(state)));
    const wrapper = mount(Passkey);
    await flushPromises();

    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].find('td').text()).toBe('我的手机');
    expect(rows[1].find('td').text()).toBe(t('passkey.unnamed'));
    expect(rows[0].findAll('td')[1].text()).toBe('AbCdEfGh…');
  });

  it('注册 ceremony：options → credentials.create → POST /webauthn/register（label 同行，二进制 base64url）', async () => {
    const bytes = (str) => new Uint8Array([...str].map((c) => c.charCodeAt(0))).buffer;
    const credential = {
      id: 'cred-3',
      rawId: bytes('rawid'),
      type: 'public-key',
      response: { attestationObject: bytes('attest'), clientDataJSON: bytes('client'), getTransports: () => ['usb'] },
      authenticatorAttachment: null,
    };
    const options = {
      challenge: 'Y2hhbGxlbmdl', // 'challenge'
      rp: { id: 'example.com', name: 'jauth' },
      user: { name: 'alice', displayName: 'Alice', id: 'dXNlcg' },
      pubKeyCredParams: [],
      timeout: 60000,
      excludeCredentials: [],
      authenticatorSelection: {},
      attestation: 'none',
    };
    const fetchMock = vi.fn((path, init) => {
      if (init && init.method === 'POST' && path === '/webauthn/register/options') return rawResponse(options);
      if (init && init.method === 'POST' && path === '/webauthn/register') return rawResponse({});
      return stateResponse(state);
    });
    vi.stubGlobal('fetch', fetchMock);
    window.PublicKeyCredential = function PublicKeyCredential() {};
    vi.stubGlobal('navigator', { credentials: { create: vi.fn(async () => credential) } });
    const wrapper = mount(Passkey);
    await flushPromises();

    await wrapper.find('input[type="text"]').setValue('我的电脑');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    const registerCall = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST' && p === '/webauthn/register');
    const body = JSON.parse(registerCall[1].body);
    expect(body.publicKey.label).toBe('我的电脑');
    expect(body.publicKey.credential.id).toBe('cred-3');
    expect(body.publicKey.credential.rawId).toBe('cmF3aWQ'); // 'rawid' 的 base64url
    expect(body.publicKey.credential.response.transports).toEqual(['usb']);
  });

  it('删除凭据必经确认，确认后 DELETE 框架端点带 CSRF 头，204 后重载', async () => {
    const fetchMock = vi.fn((path, init) => {
      if (init && init.method === 'DELETE') return { ok: true, status: 204, json: async () => null };
      return stateResponse(state);
    });
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(Passkey);
    await flushPromises();

    await wrapper.findAll('tbody button')[0].trigger('click');
    expect(wrapper.find('.dialog').exists()).toBe(true);
    const callsBefore = fetchMock.mock.calls.length;
    await wrapper.find('.dialog .btn.danger').trigger('click');
    await flushPromises();

    const deleteCall = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'DELETE');
    expect(deleteCall[0]).toBe('/webauthn/register/cred-1');
    expect(deleteCall[1].headers['X-CSRF-Token']).toBe('tok-9');
    expect(fetchMock.mock.calls.length).toBe(callsBefore + 2); // DELETE + 状态面重载
  });
});
