import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import ShellLayout from './ShellLayout.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

// RouterLink 渲染成 <a :href>（官方测试形态），href 即断言面
const RouterLinkStub = { props: ['to'], template: '<a :href="to"><slot /></a>' };

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('ShellLayout 导航壳', () => {
  it('顶栏取 /api/profile 的 username，退出为原生表单 POST /logout 带 _csrf', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse({ username: 'alice', displayName: null, csrfToken: 'tok-7', csrfHeaderName: 'X-CSRF-Token' })));
    const wrapper = mount(ShellLayout, { global: { stubs: { RouterLink: RouterLinkStub, RouterView: true } } });
    await flushPromises();

    expect(wrapper.find('.shell-user').text()).toBe('alice');
    const form = wrapper.find('.shell-top form');
    expect(form.attributes('action')).toBe('/logout');
    expect(form.attributes('method')).toBe('post');
    expect(form.find('input[name="_csrf"]').attributes('value')).toBe('tok-7');
  });

  it('导航挂自助五页 + B3 app 两页（admin 项不做角色显隐），路由与 i18n 键对齐', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse({ username: 'alice', displayName: null, csrfToken: null, csrfHeaderName: null })));
    const wrapper = mount(ShellLayout, { global: { stubs: { RouterLink: RouterLinkStub, RouterView: true } } });
    await flushPromises();

    const hrefs = wrapper.findAll('.shell-nav a').map((a) => a.attributes('href'));
    expect(hrefs).toEqual([
      '/selfservice/apps',
      '/selfservice/pat',
      '/selfservice/my-apps',
      '/selfservice/my-orgs',
      '/selfservice/passkey',
      '/profile',
      '/admin/users',
    ]);
  });

  it('profile 拉取失败（如 401 由 wrapper 已分流后的残余）不炸壳：用户名空、退出仍可用', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => ({ ok: false, status: 500, json: async () => null })));
    const wrapper = mount(ShellLayout, { global: { stubs: { RouterLink: RouterLinkStub, RouterView: true } } });
    await flushPromises();

    expect(wrapper.find('.shell-user').text()).toBe('');
    expect(wrapper.find('.shell-top form').exists()).toBe(true);
  });
});
