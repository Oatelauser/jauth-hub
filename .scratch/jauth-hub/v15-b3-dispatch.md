# v1.5 B3 派单词:app 面两页(profile / admin users)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。

## 背景与目标

B2b(9e713ff)已落:导航壳 + 自助面七页,窜连闭环。本批 = **app 面两页**迁入:jprofile(个人资料)与 admin users(用户管理)。设计语言/组件照 B2a/B2b 现场代码;后端**零改动**(B1a 已发 GET /api/profile 与 GET /api/admin/users)。普查档案 `.scratch/jauth-hub/research/04-v15-page-api-survey.md` §3.1/§3.2 有两页的端点清单;**操作端点字段以 SSR 模板内联 JS 为准**(`jauth-hub-app/src/main/resources/templates/profile.html`、`admin/users.html`)——先读模板再写页。

## 任务清单

### 1. Profile 页(壳内路由 `/profile`)

- 状态:GET `/api/profile`(username/displayName)。
- 改显示名:POST `/api/profile`(字段照模板);改密:POST `/api/profile/password`(sudo 面——A0515 分支自动跳,密码策略 ≥8 位前端只做提示不强校验,服务端为准)。
- 成功反馈用警示条(success 态);改密成功后建议提示重新登录语义(照 SSR 文案键)。

### 2. AdminUsers 页(壳内路由 `/admin/users`)

- 状态:GET `/api/admin/users`(**PageResponse 形状 item/total/pageNum/pageSize/totalPage**,page/size 参数)——分页导航(上一页/下一页/页码摘要,clamp 服务端已做)。
- 操作(字段照模板):建号 POST `/api/admin/users`;角色切换 POST `/api/admin/users/{id}/role`(sudo);停用/启用 POST `/api/admin/users/{id}/status`(sudo);重置密码 POST `/api/admin/users/{id}/password`(sudo,**新密码 shown-once**)。
- 危险操作(停用/重置/角色切换)确认对话框;sudo 触发走 A0515 分支。
- **SUPER_ADMIN 门**:非超管调用状态面被 @RequiresRole 拦(403 形态)——页面渲染无权限错误态,不白屏(照 A0508 错误态先例)。

### 3. 导航壳增项

- ShellLayout 导航加:个人资料 `/profile`、用户管理 `/admin/users`(两项追加在自助五项之后;admin 项不做角色条件显隐——SPA 无角色数据,点进去由错误态兜底,javadoc/注释写明)。

### 4. i18n

- 两页 zh/en 文案键(普查:profile 16 / admin 29);shown-once/确认框键照 B2b 先例。

## 约束

- **禁做**:git 操作;后端任何改动;新增 npm 依赖;动 B2b 已发页面(ShellLayout 导航数组追加除外);package.json 版本 1.4.0;运行时外链。
- 代码风格:组件小而直、注释只写为什么;危险操作确认;新密码 shown-once。
- 自测:`npm run test` 全绿 + `npm run build` 无报错 + dist 外链扫描零命中。规格:每页至少渲染/动作/分页(admin)冒烟 + 非超管错误态。

## 汇报格式(≤12 行)

1. 结论一句话;2. 变更文件清单;3. 测试数与 build 结果;4. 偏差与理由(如有);5. 临时文件清单(无则写"无")。
