# D3 派单:org 成员管理面(v1.3;主会话代执行版)

## 语义(老账③ + SPEC §3)

- org 成员管理是 **OWNER 面**:成员列表(用户名/角色/加入时间)、移除成员、角色调整(OWNER↔MEMBER,自操作护栏:不可降自己——最后一个 OWNER 结构性锁死,照 AdminUsersController A0513 先例);成员加入方式 = OWNER 直接添加(输入用户名,池内须存在)——邀请/申请流 v2+,记档
- 审计:成员生命周期事件(词表新增 MEMBER_ADDED/MEMBER_REMOVED/MEMBER_ROLE_CHANGED,由 OrgService 服务路径发布,照 INSTALL_* 先例)
- 语义红线:org 删除不做(另有记档价值,v2+);移除成员不级联清该成员在本 org 的授权/安装(那是用户侧语义,D1 已按主体面覆盖)

## D0 记账 #7(本批必修)

`org-apps.html` L30 与 `org-installations.html` L29 的 `${org.name}` 徽标在 `*Supported` 守卫**之外**——嵌入宿主未装 jauth 域 bean 时控制器早退不设 org,SpEL null 取属性即 C0101,「当前装配不支持」提示永不可达。修法:徽标行包进 `th:if="${orgAppsSupported}"/"${installationsSupported}"`(或控制器早退前设空 org 名,取模板侧守卫优先——最小 diff)。

## 待核实接线事实(主会话探查后补记于此)

- OrgService 现有 API(create/isOwner/…)与 OrgRepository;成员表 jauth_org_member 的读写面(成员行 record/仓储方法)
- MyOrgsController/my-orgs.html 结构;OrgApps/OrgInstallations 两控制器的早退守卫形态(修 #7 用)
- 审计发布口径(OrgService 内已有的 INSTALL_* 打点形态)

## 实施清单(探查后细化)

1. core `org`:成员域方法(listMembers/addMember/removeMember/changeMemberRole,OWNER 门在服务层照 isOwner 先例或控制器层照 OrgAppsController A0508 先例——取与既有 org 面一致的一层)+ 审计三事件
2. selfservice:成员管理面(扩展 my-orgs 页:每 org 行进成员管理;或独立 /selfservice/orgs/{orgId}/members 页——取页面信息架构最小方案)+ JSON 端点(@RequiresSudo 只挂销毁性的移除/降级?与 D2 分界一致:非销毁不加,移除挂)+ i18n zh/en
3. 模板 #7 修复(两处守卫外包行)
4. 测试:core 服务单测(双实现契约照 Installation 先例)+ selfservice 控制器单测 + app 全上下文渲染一例(members 页)+ #7 修复的负路径(无域 bean 上下文不可测——以模板静态断言/守卫形态断言替代,记档)

## 禁做

- 不动协议端点/rs-starter;不建 migration(成员表已在);org 删除/邀请流不做;README 归 D6;git 禁止(subagent 执行时)

## 验收

- `mvn verify` 全绿,测试数 ≥448 只增;汇报 ≤20 行
