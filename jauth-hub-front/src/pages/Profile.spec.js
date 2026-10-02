import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import Profile from './Profile.vue';

function ok(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

const state = {
  educational: false,
  username: 'alice',
  displayName: '爱丽丝',
  csrfToken: 'tok-p',
  csrfHeaderName: 'X-CSRF-Token',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('个人资料页', () => {
  it('渲染：账号行出 username，显示名回填输入框', async () => {
    vi.stubGlobal('fetch', vi.fn(() => ok(state)));
    const wrapper = mount(Profile);
    await flushPromises();

    expect(wrapper.text()).toContain('alice');
    expect(wrapper.find('input[type="text"]').element.value).toBe('爱丽丝');
  });

  it('改显示名：POST /api/profile 载荷 {displayName}（trim）+ CSRF 头，成功出 success 警示条', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' && path === '/api/profile'
        ? ok({ username: 'alice', displayName: '新名' })
        : ok(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(Profile);
    await flushPromises();

    await wrapper.find('input[type="text"]').setValue('  新名  ');
    await wrapper.findAll('form')[0].trigger('submit');
    await flushPromises();

    const call = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST' && p === '/api/profile');
    expect(JSON.parse(call[1].body)).toEqual({ displayName: '新名' });
    expect(call[1].headers['X-CSRF-Token']).toBe('tok-p');
    expect(wrapper.find('.alert.success').text()).toBe(t('profile.saved'));
  });

  it('改密：POST /api/profile/password 载荷 {oldPassword,newPassword}，成功提示重登语义并清空表单', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' && path === '/api/profile/password' ? ok(null) : ok(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(Profile);
    await flushPromises();

    const inputs = wrapper.findAll('input[type="password"]');
    await inputs[0].setValue('old-secret');
    await inputs[1].setValue('new-secret-9');
    await wrapper.findAll('form')[1].trigger('submit');
    await flushPromises();

    const call = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST' && p === '/api/profile/password');
    expect(JSON.parse(call[1].body)).toEqual({ oldPassword: 'old-secret', newPassword: 'new-secret-9' });
    const notice = wrapper.find('.alert.success').text();
    expect(notice).toContain(t('profile.password-changed'));
    expect(notice).toContain(t('profile.relogin-hint'));
    expect(wrapper.findAll('input[type="password"]')[0].element.value).toBe('');
  });
});
