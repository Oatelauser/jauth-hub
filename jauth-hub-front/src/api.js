// fetch 包装（B3）：同源 JSON 调用 + ResponseRenderer 统一形状 {code,message,data} 解包。
// code==='00000' 才算成功，解出 data；其余（含 HTTP !ok）抛 Error(message) 交页面渲染。
const LOGIN_PATH = '/front/login';
const SUDO_PATH = '/front/sudo';

export async function getJson(path) {
  return request(path, { headers: { Accept: 'application/json' } });
}

export async function postJson(path, body, csrf) {
  const headers = { Accept: 'application/json', 'Content-Type': 'application/json' };
  // 头名随状态面载荷走（宿主链可自定义；CSRF 惰性属性缺席时两字段为 null，免头）
  if (csrf && csrf.csrfToken && csrf.csrfHeaderName) {
    headers[csrf.csrfHeaderName] = csrf.csrfToken;
  }
  return request(path, { method: 'POST', headers, body: JSON.stringify(body) });
}

async function request(path, init) {
  const response = await fetch(path, init); // 同源默认携带会话 cookie
  const payload = await response.json().catch(() => null);
  // 401 分流：带 code 的 401 是登录桥业务失败（A0520/A0521，统一失败体），照常抛给页面渲染；
  // 无 code 的 401 是安全链"未认证"拦截（空/非统一形状体），整页跳登录重建会话
  if (response.status === 401 && !(payload && payload.code)) {
    window.location.href = LOGIN_PATH;
    throw new Error('unauthenticated');
  }
  // A0515 分支（sudo 过期，survey §4.2 六页先例的 SPA 版）：整页跳强验证页。
  // returnTo 取当前 SPA 路径+查询串，去 /front 前缀还原服务端路径——sudo 消毒回看板/原页都安全
  if (payload && payload.code === 'A0515') {
    const returnTo = (window.location.pathname + window.location.search).replace(/^\/front/, '') || '/';
    window.location.href = SUDO_PATH + '?returnTo=' + encodeURIComponent(returnTo);
    throw new Error('sudo-required');
  }
  if (!response.ok || !payload || payload.code !== '00000') {
    throw new Error((payload && payload.message) || 'HTTP ' + response.status);
  }
  return payload.data;
}
