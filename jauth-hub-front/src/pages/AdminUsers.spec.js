import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { t } from '../i18n';
import AdminUsers from './AdminUsers.vue';

function ok(data) {
  return { ok: true, status: 200, json: async () => ({ code: '00000', message: 'ok', data }) };
}

// B1a 状态面契约：PageResponse 形状（item/total/pageNum/pageSize/totalPage）+ csrf 对
const state = {
  educational: false,
  item: [
    { id: 'u1', username: 'alice', displayName: '爱丽丝', role: 'SUPERADMIN', status: 'ACTIVE' },
    { id: 'u2', username: 'bob', displayName: null, role: 'USER', status: 'DISABLED' },
  ],
  total: 45,
  pageNum: 2,
  pageSize: 20,
  totalPage: 3,
  csrfToken: 'tok-a',
  csrfHeaderName: 'X-CSRF-Token',
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('用户管理页', () => {
  it('渲染：角色/状态徽标、空显示名占位、分页摘要；下一页带 page=3 重取', async () => {
    const fetchMock = vi.fn(() => ok(state));
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(AdminUsers);
    await flushPromises();

    const rows = wrapper.findAll('tbody tr');
    expect(rows).toHaveLength(2);
    expect(rows[0].text()).toContain(t('adminusers.role-superadmin'));
    expect(rows[1].text()).toContain(t('adminusers.status-disabled'));
    expect(rows[1].text()).toContain('—'); // displayName null 占位
    // 摘要按当前语言字典占位替换（测试环境 locale 随 jsdom navigator，不硬编码文案）
    const summary = t('adminusers.page-summary').replace('{0}', 45).replace('{1}', 2).replace('{2}', 3);
    expect(wrapper.find('.page-nav').text()).toContain(summary);

    const pager = wrapper.findAll('.page-nav button');
    expect(pager[0].attributes('disabled')).toBeUndefined(); // 第 2 页：上一页可用
    await pager[1].trigger('click');
    await flushPromises();
    const lastGet = fetchMock.mock.calls.filter(([p]) => p.indexOf('/api/admin/users') === 0).pop()[0];
    expect(lastGet).toBe('/api/admin/users?page=3&size=20');
  });

  it('建号：POST /api/admin/users 载荷 {username,password,displayName}（trim）', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' && path === '/api/admin/users'
        ? ok({ id: 'u3', username: 'carol' })
        : ok(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(AdminUsers);
    await flushPromises();

    const form = wrapper.findAll('form')[0];
    await form.find('input[type="text"]').setValue('  carol  ');
    await form.find('input[type="password"]').setValue('secret-123');
    await wrapper.findAll('input[type="text"]')[1].setValue('卡罗尔');
    await form.trigger('submit');
    await flushPromises();

    const call = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST' && p === '/api/admin/users');
    expect(JSON.parse(call[1].body)).toEqual({ username: 'carol', password: 'secret-123', displayName: '卡罗尔' });
  });

  it('改角色必经确认对话框：确认后 POST {id}/role，空对象体 + CSRF 头（照 SSR 模板）', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' && path === '/api/admin/users/u1/role'
        ? ok({ id: 'u1', role: 'USER' })
        : ok(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(AdminUsers);
    await flushPromises();

    await wrapper.findAll('tbody tr')[0].findAll('button')[0].trigger('click'); // 改角色
    expect(wrapper.find('.dialog').exists()).toBe(true);
    expect(fetchMock.mock.calls.some(([p, i]) => i && i.method === 'POST' && p.endsWith('/role'))).toBe(false);

    await wrapper.find('.dialog .btn.danger').trigger('click');
    await flushPromises();

    const call = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST' && p === '/api/admin/users/u1/role');
    expect(JSON.parse(call[1].body)).toEqual({});
    expect(call[1].headers['X-CSRF-Token']).toBe('tok-a');
  });

  it('重置密码：对话框给定新值，POST {id}/password，新密码 shown-once 回显', async () => {
    const fetchMock = vi.fn((path, init) =>
      init && init.method === 'POST' && path === '/api/admin/users/u2/password'
        ? ok({ id: 'u2', username: 'bob' })
        : ok(state)
    );
    vi.stubGlobal('fetch', fetchMock);
    const wrapper = mount(AdminUsers);
    await flushPromises();

    await wrapper.findAll('tbody tr')[1].findAll('button')[2].trigger('click'); // 重置密码
    const dialog = wrapper.find('.dialog');
    expect(dialog.exists()).toBe(true);
    await dialog.find('input[type="password"]').setValue('newpass-9');
    await dialog.find('form').trigger('submit');
    await flushPromises();

    const call = fetchMock.mock.calls.find(([p, i]) => i && i.method === 'POST' && p === '/api/admin/users/u2/password');
    expect(JSON.parse(call[1].body)).toEqual({ password: 'newpass-9' });
    expect(wrapper.text()).toContain(t('adminusers.reset-done'));
    expect(wrapper.find('.code-line').text()).toContain('newpass-9'); // shown-once
  });

  it('非超管错误态：状态面 403 渲染错误警示条，不出表格不白屏', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 403, json: async () => ({ code: 'A0301', message: '无权限操作' }) }))
    );
    const wrapper = mount(AdminUsers);
    await flushPromises();

    expect(wrapper.find('h1').text()).toBe(t('adminusers.title')); // 页面在，不白屏
    expect(wrapper.find('.alert.error').text()).toBe('无权限操作');
    expect(wrapper.find('table').exists()).toBe(false);
  });
});
