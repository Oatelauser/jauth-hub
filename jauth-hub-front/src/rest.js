// DELETE 动作面（my-apps 删除 / org 应用删除 / 成员移除）。派单词只放行 api.js 的 A0515 一处
// 改动，故 DELETE 的统一拆包落本模块；请求语义与 api.js request() 逐行同款（401 无 code 整页跳
// 登录、A0515 整页跳 sudo 带 SPA returnTo、非 00000 抛 message 交页面渲染）。
const LOGIN_PATH = '/front/login';
const SUDO_PATH = '/front/sudo';

export async function deleteJson(path, csrf) {
  const headers = { Accept: 'application/json' };
  if (csrf && csrf.csrfToken && csrf.csrfHeaderName) {
    headers[csrf.csrfHeaderName] = csrf.csrfToken;
  }
  const response = await fetch(path, { method: 'DELETE', headers, credentials: 'same-origin' });
  const payload = await response.json().catch(() => null);
  if (response.status === 401 && !(payload && payload.code)) {
    window.location.href = LOGIN_PATH;
    throw new Error('unauthenticated');
  }
  if (payload && payload.code === 'A0515') {
    const returnTo = window.location.pathname + window.location.search;
    window.location.href = SUDO_PATH + '?returnTo=' + encodeURIComponent(returnTo);
    throw new Error('sudo-required');
  }
  if (!response.ok || !payload || payload.code !== '00000') {
    throw new Error((payload && payload.message) || 'HTTP ' + response.status);
  }
  return payload.data;
}
