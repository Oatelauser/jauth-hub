# D4 派单:设备流多 org 选择(老⑤)+ consent 已授权默认勾选(老⑥)(v1.3;主会话代执行版)

## 裁定:老⑤ = 已由 B9 架构性关闭(记档收账,不新建机械)

设备流的 org 选择发生在**授权确认页**而非设备验证页:DeviceVerifyController 仅渲染 /device/verify(GET),user_code POST 由框架消费;验证通过且需 consent 时,框架把用户重定向到 `/oauth2/consent?client_id&state&scope`——与授权码流**同一落点**,B9 在该页建的 org 三态(plain/guide+selector/selected+ceiling)与会话暂存(state 键控)对设备流天然生效。多 org 设备客户端的验证→consent→选 org 链路无 jauth 侧缺口;若未来框架改设备流独立 consent 端点再开新账。

## 老⑥ 实施

- ConsentController 注入 `OAuth2AuthorizationConsentService`;`grantedScopes(clientId, principal)` 查该用户在该 client 的既有授权 scope(内部 id 口径:findByClientId → getId → findByClientIdAndPrincipalName)
- `ScopeItem` 增 `alreadyGranted` 位;勾选语义**不变**(consent 重录需完整勾选集),徽标只做增量授权的知情区分
- consent.html 徽标行(`.scope-granted`,core jauth.css 补样式)+ core i18n zh/en(`jauth.consent.granted`)
- 接线:starter jauthConsentController + ProtocolPagesTest/ConsentOrgContextPageTest 构造补参
- 测试:ConsentOrgContextPageTest 增「已授权徽标」用例(personal-app 桩:openid 已授→徽标;profile 新增→无;两项仍默认勾选)

## 验收

- `mvn verify` 全绿,测试数 ≥457 只增
