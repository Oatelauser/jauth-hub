# D5 派单:横切小项清尾(老④+⑦+⑧)(v1.3;主会话代执行版)

## 语义

1. **老④ 注册/PAT 面限流**:应用注册(个人+org 两面)与 PAT 创建加**每主体创建节流**——防已认证会话刷行(垃圾 client/PAT 行堆库)。形态:滑窗计数(默认 20 次/小时/主体,内存实现,属性可调?→ 不做属性,常量+注释,过设计红线)。429/限流错误码选型:登录限流用 429+头?看既有 RateLimiter 形态再定,家族语义优先。
2. **老⑦ 用户列表分页**:AdminUsersController.page 全量 → 分页(?page=&size=,默认 1/20,上限 100),家族 PageResponse 契约口径(item/total/pageNum/pageSize/totalPage)——SSR 页 data 形态:模型携带同字段,模板分页导航;UserRepository 加 count + 分页查询(LIMIT ? OFFSET ? 双库兼容,排序 username ASC 保持)。
3. **老⑧ 审计词表扩展**=生命周期覆盖补缺(无审计展示面,词表的意义在覆盖):注册面补 `CLIENT_REGISTERED`(个人+org);app 管理面补 `USER_CREATED/USER_ROLE_CHANGED/USER_STATUS_CHANGED`(管理员建号/改角色/停启用——动作本身打点;D1 已打清剿侧,重置密码动作并入 CREDENTIALS_REVOKED detail 已覆盖?不——补 `PASSWORD_RESET` 独立事件更清晰,查重后定)。

## 待核实事实(主会话探查)

- core ratelimit.RateLimiter 接口形态(是否可复用/需新建 CreationThrottle)
- PatController.create 入口形状;AdminUsersController.page 与 users.html 列表块
- UserRepository findAll 签名与 jdbc 实现(加 count/分页)

## 禁做

- 不动协议端点;不引依赖;i18n zh/en 补;README 归 D6

## 验收

- `mvn verify` 全绿,测试数 ≥458 只增
