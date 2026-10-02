// 自写 zh/en 双字典（依赖红线：不引 vue-i18n）。文案逐条对齐后端 messages*.properties
// （core: brand/teach/flow/login/consent/device，selfservice: sudo）；zh 主填，en 骨架，
// navigator.language 判 en、缺键回退 zh，其余语言一律 zh（与 SSR messageSource 行为同向）。
const zh = {
  'brand': 'jauth-hub',
  'teach.whatHappened': '发生了什么',
  'teach.flowTitle': '当前流程',
  'teach.httplogTitle': 'HTTP 日志（预留）',
  'teach.httplogPlaceholder': '/demo 教学区将在此展示实时 HTTP 请求与响应（B6 接入）',
  'flow.authcode.step1': '应用发起授权，跳转登录',
  'flow.authcode.step2': '用户授权确认',
  'flow.authcode.step3': '回调用授权码换取令牌',
  'flow.authcode.step4': '持令牌访问资源',
  'flow.device.step1': '设备向认证中心申请用户码',
  'flow.device.step2': '用户在本页输入用户码验证',
  'flow.device.step3': '设备轮询换取令牌',
  'flow.device.step4': '持令牌访问资源',
  'login.title': '登录',
  'login.username': '用户名',
  'login.password': '密码',
  'login.submit': '登 录',
  'login.error': '用户名或密码错误，请重试',
  'login.teach':
    '你提交的用户名和密码经表单认证校验，通过后服务器建立会话（session）；auth_time（认证时间）在此刻产生，后续签发的 ID 令牌都以它作为你最近一次登录的依据。',
  'login.passkey.button': '使用通行密钥登录',
  'login.passkey.error': '通行密钥验证未通过，请重试',
  'login.passkey.unsupported': '当前浏览器不支持通行密钥，请改用密码登录',
  'login.passkey.teach':
    '通行密钥（passkey）以设备指纹 + 生物识别替代密码：私钥永不出设备，登录时服务器下发挑战，设备本地签名回传验签——没有密码可被钓鱼或撞库。需先在“通行密钥”自助页注册。',
  'consent.title': '授权确认',
  'consent.clientLabel': '应用',
  'consent.requested': '该应用请求获得以下权限：',
  'consent.authorize': '同意授权',
  'consent.deny': '拒绝',
  'consent.teach':
    '这是授权码流程的第二步：应用请求的权限（scope）与你在下方勾选的项取交集，只有交集内的权限才会写进最终发放的令牌。',
  'consent.orgContext': '授权组织',
  'consent.orgSelect': '请选择本次授权使用的组织上下文',
  'consent.orgGuide':
    '该应用需要先安装到你所在的某个组织，并由组织 OWNER 审批后才能授权。请联系组织管理员发起安装。',
  'consent.granted': '已授权（此前授权过，本次无需重新决定）',
  'device.title': '设备验证',
  'device.codeLabel': '用户码',
  'device.submit': '验证',
  'device.hint': '请输入设备屏幕上显示的用户码（形如 BTVJ-HDQK）',
  'device.teach':
    '设备授权流程的用户确认端：你在设备屏幕上看到的用户码在此输入确认，设备端随后凭轮询拿到令牌，全程接触不到你的密码。',
  'sudo.title': '强验证',
  'sudo.teach':
    '刚才的操作比较敏感（如改密码、改角色），需要确认最近一次通行密钥验证仍有效：登录密码可能早已泄露，而通行密钥的私钥不出设备、无法被钓鱼。验证一次后的一段时间内（默认 15 分钟）同类操作不再重复验证；过期后再触达敏感操作会再次来到本页，原表单需要重新填写提交。',
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
  'teach.whatHappened': 'What is happening',
  'teach.flowTitle': 'Current flow',
  'teach.httplogTitle': 'HTTP log (reserved)',
  'teach.httplogPlaceholder': 'The /demo area will show live HTTP requests and responses here (B6)',
  'flow.authcode.step1': 'App starts authorization, redirects to login',
  'flow.authcode.step2': 'User grants consent',
  'flow.authcode.step3': 'Exchange code for token',
  'flow.authcode.step4': 'Access resource with token',
  'flow.device.step1': 'Device requests a user code',
  'flow.device.step2': 'User verifies the code on this page',
  'flow.device.step3': 'Device polls for token',
  'flow.device.step4': 'Access resource with token',
  'login.title': 'Sign in',
  'login.username': 'Username',
  'login.password': 'Password',
  'login.submit': 'Sign in',
  'login.error': 'Invalid username or password',
  'login.teach':
    'Form login validates your credentials and establishes a session; auth_time is recorded now and referenced by later ID tokens.',
  'login.passkey.button': 'Sign in with a passkey',
  'login.passkey.error': 'Passkey verification failed, please try again',
  'login.passkey.unsupported': 'Your browser does not support passkeys, please sign in with your password',
  'login.passkey.teach':
    'A passkey replaces your password with device-local biometrics: the private key never leaves your device, the server sends a challenge and verifies the local signature — nothing phishable to steal. Register one first on the passkey self-service page.',
  'consent.title': 'Authorize application',
  'consent.clientLabel': 'Application',
  'consent.requested': 'This application requests the following permissions:',
  'consent.authorize': 'Authorize',
  'consent.deny': 'Deny',
  'consent.teach':
    'Step two of the authorization code flow: requested scopes intersect with your checked boxes; only the intersection is written into the issued token.',
  'consent.orgContext': 'Authorizing organization',
  'consent.orgSelect': 'Choose the organization context for this authorization',
  'consent.orgGuide':
    'This application must be installed into one of your organizations and approved by its OWNER before you can authorize it. Contact your organization owner.',
  'consent.granted': 'Already granted (previously approved; no new decision needed)',
  'device.title': 'Device verification',
  'device.codeLabel': 'User code',
  'device.submit': 'Verify',
  'device.hint': 'Enter the user code shown on the device (e.g. BTVJ-HDQK)',
  'device.teach':
    'The user confirmation end of the device flow: confirm the code shown on the device; the device then obtains the token by polling and never sees your password.',
  'sudo.title': 'Re-verify',
  'sudo.teach':
    'This action is sensitive (password change, role change) and requires a recent passkey verification: a login password may long be leaked, while a passkey’s private key never leaves the device and cannot be phished. After one verification, similar actions stay unlocked for a while (15 minutes by default); once that expires you will land here again and must re-fill the original form.',
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

// 教学侧栏步骤条文案（authcode/device 两套流程，active 由调用页传 0 基序号）
export function flowSteps(flow) {
  return ['step1', 'step2', 'step3', 'step4'].map((step) => t('flow.' + flow + '.' + step));
}
