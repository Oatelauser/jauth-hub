import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import MyApps from './MyApps.vue';

function stateResponse(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

const state = {
  educational: false,
  appsSupported: true,
  apps: [
    {
      id: 'a1',
      clientId: 'app_pub',
      name: '公开应用',
      confidential: false,
      redirectUris: ['https://a.example.com/cb'],
      createdAt: '2026-09-30T10:00:00Z',
    },
    {
      id: 'a2',
      clientId: 'app_conf',
      name: '机密应用',
      confidential: true,
      redirectUris: ['https://b.example.com/cb'],
      createdAt: '2026-09-30T11:00:00Z',
    },
  ],
  csrfToken: 'tok-3',
  csrfHeaderName: 'X-CSRF-Token',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('我的应用页', () => {
  it('列表渲染：机密行出轮转按钮，公开行不出', async () => {
    vi.stubGlobal('fetch', vi.fn(() => stateResponse(state)));
    const wrapper = mount(MyApps);
    await flushPromises();

    expect(wrapper.findAll('tbody tr')).toHaveLength(2);
    expect(wrapper.findAll('tbody tr')[1].findAll('button')).toHaveLength(3); // 轮转/编辑/删除
    expect(wrapper.findAll('tbody tr')[0].findAll('button')).toHaveLength(2); // 编辑/删除
  });

  it('注册对话框（my-app-new 复用）：POST 载荷 {name, redirectUris, confidential}，secret 仅此一次展示', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' && path === '/selfservice/my-apps'
        ? stateResponse({ id: 'a3', name: '新应用', clientId: 'app_new', confidential: true, redirectUris: [], clientSecret: 'sec-once' })
        : stateResponse(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(MyApps);
    await flushPromises();

    await wrapper.find('main > p.actions button').trigger('click'); // 注册新应用
    const dialog = wrapper.find('.dialog');
    expect(dialog.exists()).toBe(true);

    await dialog.find('input[type="text"]').setValue('新应用');
    await dialog.find('textarea').setValue('https://c.example.com/cb\nhttps://d.example.com/cb');
    await dialog.find('select').setValue('confidential');
    await dialog.find('form').trigger('submit');
    await flushPromises();

    const [path, init] = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST' && p === '/selfservice/my-apps');
    expect(JSON.parse(init.body)).toEqual({
      name: '新应用',
      redirectUris: 'https://c.example.com/cb\nhttps://d.example.com/cb',
      confidential: true,
    });
    expect(wrapper.text()).toContain('app_new');
    expect(wrapper.find('.code-line').text()).toBe('sec-once'); // shown-once
    expect(wrapper.text()).toContain(t('myapps.secret-once'));
  });

  it('删除必经确认，确认后 DELETE 端点带 CSRF 头', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'DELETE' ? stateResponse({ id: 'a1' }) : stateResponse(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(MyApps);
    await flushPromises();

    await wrapper.findAll('tbody tr')[0].findAll('button').at(1).trigger('click'); // 删除（公开行第 2 键）
    expect(wrapper.find('.dialog').exists()).toBe(true);
    await wrapper.find('.dialog .btn.danger').trigger('click');
    await flushPromises();

    const deleteCall = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'DELETE');
    expect(deleteCall[0]).toBe('/selfservice/my-apps/a1');
    expect(deleteCall[1].headers['X-CSRF-Token']).toBe('tok-3');
  });
});
