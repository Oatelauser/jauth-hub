// 自写 zh/en 双字典（依赖红线：不引 vue-i18n）。文案逐条对齐后端 messages*.properties
// （core: brand/login/consent/device，selfservice: sudo）；zh 主填，en 骨架，
// navigator.language 判 en、缺键回退 zh，其余语言一律 zh（与 SSR messageSource 行为同向）。
// 教学文案（teach.*/flow.*）已随 v1.5 B2a 企业化换皮退场，/demo 专区需要时 B4 再加。
const zh = {
  'brand': 'jauth-hub',
  'login.title': '登录',
  'login.username': '用户名',
  'login.password': '密码',
  'login.submit': '登 录',
  'login.error': '用户名或密码错误，请重试',
  'login.passkey.button': '使用通行密钥登录',
  'login.passkey.error': '通行密钥验证未通过，请重试',
  'login.passkey.unsupported': '当前浏览器不支持通行密钥，请改用密码登录',
  'consent.title': '授权确认',
  'consent.clientLabel': '应用',
  'consent.requested': '该应用请求获得以下权限：',
  'consent.authorize': '同意授权',
  'consent.deny': '拒绝',
  'consent.orgContext': '授权组织',
  'consent.orgSelect': '请选择本次授权使用的组织上下文',
  'consent.orgGuide':
    '该应用需要先安装到你所在的某个组织，并由组织 OWNER 审批后才能授权。请联系组织管理员发起安装。',
  'consent.granted': '已授权（此前授权过，本次无需重新决定）',
  'device.title': '设备验证',
  'device.codeLabel': '用户码',
  'device.submit': '验证',
  'device.hint': '请输入设备屏幕上显示的用户码（形如 BTVJ-HDQK）',
  'sudo.title': '强验证',
  'sudo.intro': '该操作需要先确认是你本人：使用通行密钥完成一次验证。',
  'sudo.verify': '使用通行密钥验证',
  'sudo.error': '验证未通过，请重试',
  'sudo.unsupportedBrowser': '当前浏览器不支持通行密钥',
  'sudo.back': '暂不验证，返回',
  'sudo.unsupported':
    '强验证（sudo）未启用：默认关闭，需以 jauth-hub.sudo.enabled=true 且 jauth-hub.passkey.enabled=true 部署后使用。',
};

const en = {
  'brand': 'jauth-hub',
  'login.title': 'Sign in',
  'login.username': 'Username',
  'login.password': 'Password',
  'login.submit': 'Sign in',
  'login.error': 'Invalid username or password',
  'login.passkey.button': 'Sign in with a passkey',
  'login.passkey.error': 'Passkey verification failed, please try again',
  'login.passkey.unsupported': 'Your browser does not support passkeys, please sign in with your password',
  'consent.title': 'Authorize application',
  'consent.clientLabel': 'Application',
  'consent.requested': 'This application requests the following permissions:',
  'consent.authorize': 'Authorize',
  'consent.deny': 'Deny',
  'consent.orgContext': 'Authorizing organization',
  'consent.orgSelect': 'Choose the organization context for this authorization',
  'consent.orgGuide':
    'This application must be installed into one of your organizations and approved by its OWNER before you can authorize it. Contact your organization owner.',
  'consent.granted': 'Already granted (previously approved; no new decision needed)',
  'device.title': 'Device verification',
  'device.codeLabel': 'User code',
  'device.submit': 'Verify',
  'device.hint': 'Enter the user code shown on the device (e.g. BTVJ-HDQK)',
  'sudo.title': 'Re-verify',
  'sudo.intro': 'This action needs to confirm it is really you: verify once with a passkey.',
  'sudo.verify': 'Verify with a passkey',
  'sudo.error': 'Verification did not pass, please try again',
  'sudo.unsupportedBrowser': 'This browser does not support passkeys',
  'sudo.back': 'Not now, go back',
  'sudo.unsupported':
    'Sudo mode is disabled: set jauth-hub.sudo.enabled=true together with jauth-hub.passkey.enabled=true to enable it.',
};

const locale = String(navigator.language || 'zh').toLowerCase().startsWith('en') ? 'en' : 'zh';

export function t(key) {
  const table = locale === 'en' ? en : zh;
  return table[key] || zh[key] || key;
}
