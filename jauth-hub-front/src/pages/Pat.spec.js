import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import Pat from './Pat.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

const state = {
  educational: false,
  patSupported: true,
  scopes: [
    { name: 'openid', description: '身份标识' },
    { name: 'profile', description: '基础资料' },
  ],
  validityDays: [30, 90, 365],
  defaultValidityDays: 90,
  pats: [
    {
      id: 'p1',
      name: null,
      prefix: 'jpat_abc',
      scopes: ['openid'],
      status: 'ACTIVE',
      expired: true,
      createdAt: '2026-09-29T10:00:00Z',
      expiresAt: '2026-12-28T10:00:00Z',
      lastUsedAt: null,
    },
  ],
  now: '2026-10-02T00:00:00Z',
  csrfToken: 'tok-2',
  csrfHeaderName: 'X-CSRF-Token',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('PAT 页', () => {
  it('列表渲染：未命名回退、过期 pill；有效期阶梯出前端字典键 validity-{days}', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse(state)));
    const wrapper = mount(Pat);
    await flushPromises();

    expect(wrapper.findAll('tbody tr')).toHaveLength(1);
    expect(wrapper.find('tbody td').text()).toBe(t('pat.unnamed'));
    const pill = wrapper.find('.pill-danger');
    expect(pill.text()).toBe(t('pat.expired'));

    const options = wrapper.findAll('select option');
    expect(options.map((o) => o.text())).toEqual([t('pat.validity-30'), t('pat.validity-90'), t('pat.validity-365')]);
    expect(wrapper.find('select').element.value).toBe('90'); // defaultValidityDays 预选
  });

  it('创建令牌：POST 载荷 {name, scopes, validityDays}，token 仅此一次展示', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' ? stateResponse({ token: 'jpat_plain_secret' }) : stateResponse(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(Pat);
    await flushPromises();

    await wrapper.find('input[type="text"]').setValue('ci-deploy');
    await wrapper.findAll('input[type="checkbox"]').at(0).setValue(true); // openid（目录序）
    await wrapper.find('select').setValue('30');
    await wrapper.find('form').trigger('submit');
    await flushPromises();

    const [path, init] = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST');
    expect(path).toBe('/selfservice/pat');
    expect(init.headers['X-CSRF-Token']).toBe('tok-2');
    expect(JSON.parse(init.body)).toEqual({ name: 'ci-deploy', scopes: ['openid'], validityDays: 30 });

    expect(wrapper.find('.alert.warn').text()).toContain(t('pat.token-once')); // 明示不可再查
    expect(wrapper.find('.code-line').text()).toBe('jpat_plain_secret');
  });
});
