# B8 派单词:org/安装域核心(core + starter,V6 迁移,审计接线)

> 你是 B8 批次的实施 subagent。本文自包含,读完直接开工。**禁止任何 git 操作**(add/commit/push/restore 都不行);越界(动本清单外模块、改 pom 门禁、调测试阈值)即停并汇报。

## 一、决议摘录(实施宪法,SPEC.md + 2026-09-30 拍板补记)

1. **org 模型**(SPEC §3):单 issuer + org 维度,org 是权限边界,一个用户可属多 org;成员角色 `OWNER|MEMBER`,**安装审批权在 OWNER**。表 `jauth_org` / `jauth_org_member` / `jauth_installation` 已在 V2 建好(见 `jauth-hub-core/src/main/resources/io/github/oatelauser/jauth/core/flyway/V2__jauth_domain_tables.sql`),本批只补列 + 写 Java 域。
2. **安装流向 = A 两步制**(拍板):任何登录用户发起安装请求(PENDING,记 `requested_by`/`requested_scopes`)→ org OWNER 审批时勾 `ceiling_scopes` 生效(APPROVED)/驳回(REJECTED);APPROVED 可撤销(REVOKED)。**"任何登录用户可发起"是拍板原文,不要加"必须是 org 成员"的限制**——控制点在 OWNER 审批,不在发起。
3. **org 创建 = 自助**(拍板):任何登录用户可建 org,创建者自动成为 OWNER。本批只有域服务,**无页面无端点**("我的组织"页是 B11)。
4. **发行 scopes = 请求 ∩ consent ∩ ceiling 是运行时取交,B9 才接线**——本批**严禁碰**令牌发行/consent 流程/内省富化。
5. 词表归代码枚举、DB 列不加 CHECK;主键 UUIDv7(`core/util/UuidV7` 现成);TIMESTAMP 无时区;集合语义列用 TEXT 空格分隔(scope 序列化惯例照 `JauthJdbcRegisteredClientRepository`);H2/PG 双兼容 SQL。

## 二、改动清单

### 新建(core/org/ 包,绿地;命名与结构照 user 包三件套模式)

- `OrgRole` 枚举(OWNER/MEMBER)、`Org`(id/name/createdAt)、`OrgMember`(orgId/userId/role/createdAt)
- `InstallationStatus` 枚举(PENDING/APPROVED/REJECTED/REVOKED)、`Installation`(id/registeredClientId/orgId/status/ceilingScopes/requestedBy/requestedScopes/approvedBy/approvedAt/createdAt;scopes 用 `Set<String>`)
- `OrgRepository` / `InstallationRepository` 接口 + `Jdbc`/`InMemory` 双实现(照 `user/UserRepository` + `JdbcUserRepository` + `InMemoryUserRepository` 三件套)
- `OrgService`:
  - `create(name, creatorUserId)`:建 org + 创建者 OWNER 成员;重名 → `JauthException`(表有 name 唯一约束,先查后插 + 约束兜底)
  - 查询:`findByUser(userId)`(org+role 列表)、`isOwner(orgId, userId)`——审批门的依赖
  - 成员增删**不做**(无调用方,YAGNI,B11 有页面语义时再定)
- `InstallationService`(全部动作接审计,见下):
  - `request(clientId, orgId, requestedScopes, requestedBy)` → PENDING 行。同 (client,org) 已有 PENDING 或 APPROVED → `JauthException`;REJECTED/REVOKED → 允许重发(原行重置:status=PENDING、更新 requested_by/requested_scopes、清空 approved_by/approved_at;created_at 不动)。client/org 不存在 → `JauthException`
  - `approve(id, approverUserId, ceilingScopes)` → APPROVED。前置:approver 是该 org OWNER;当前 PENDING;`ceilingScopes ⊆ requestedScopes`(越界=审批错,`JauthException`)。落 approved_by/approved_at/ceiling_scopes
  - `reject(id, approverUserId)` → REJECTED。前置:OWNER + PENDING
  - `revoke(id, operatorUserId)` → REVOKED。前置:操作者是 org OWNER + 当前 APPROVED
  - 超管短路不做(那是 spring-plus 注解层语义,域服务不管)
- 测试:`OrgServiceTest`、`InstallationServiceTest`(状态机全转移 + 越权 + ceiling 越界 + 重发语义 + 重名;双实现各自跑——jdbc 侧照现有 Jdbc repo 测试基建,H2 即可)

### 修改

- `V6__installation_request_columns.sql`(flyway 目录,编号接 V5):`ALTER TABLE jauth_installation ADD COLUMN requested_by CHAR(36) DEFAULT NULL` 与 `ADD COLUMN requested_scopes TEXT DEFAULT NULL`,两条独立 ALTER(H2/PG 双兼容);加 `requested_by → jauth_user(id)` 外键,镜像 V2 里 `approved_by` 的 FK 写法
- `AuditEventType`:追加 `ORG_CREATED("org.created")`、`INSTALL_REQUESTED("installation.requested")`、`INSTALL_APPROVED("installation.approved")`、`INSTALL_REJECTED("installation.rejected")`、`INSTALL_REVOKED("installation.revoked")`;javadoc 里"届时扩枚举值"那句同步收掉。服务路径经 `AuditEventPublisher.publish` 发布(actor=操作者,target_type=org|installation,target_id=对应 id)
- `JauthHubAutoConfiguration`:org 域 bean 装配,双实现按 `jauth-hub.storage` 条件注册(memory matchIfMissing / jdbc 显式;装配位置与风格照该文件 ~L600/748 的既有两段 storage 配置),可替换性用 `@ConditionalOnMissingBean`
- `FlywayMigrationTest` / `PostgreSqlMigrationTest`:补两新列存在断言(两测试类都补)

### 明确不做

页面/控制器/selfservice/app 任何文件;orgs claim 富化(B9);`jauth_pat` 名列(V7,B10);TransactionTemplate 收口(B10);版本号不动(仍是 1.0.0,1.1.0 在 B13 发)。

## 三、开工前先读(模式参照,别跳过)

`AGENTS.md`(代码风格/质量门禁)、`V2__jauth_domain_tables.sql`(DDL)、`user/` 三件套、`JauthJdbcRegisteredClientRepository`(scopes TEXT 序列化)、`audit/AuditEventPublisher` + `AuditEvent`、`response/JauthException` + `ErrorCode`(A05xx 客户端/请求错段;错误码命名跟既有风格)、`client/ClientOwner`、`util/UuidV7`、`JauthHubAutoConfiguration` 的两段 storage 配置。

## 四、验收标准(主会话按此验收 diff)

1. `mvn verify` 全绿(196 存量 + 新增测试,三门禁 p3c/Spotless/SpotBugs+FindSecBugs 过);**禁止调阈值/加豁免换绿**
2. 状态机与拍板一致:PENDING→APPROVED/REJECTED,APPROVED→REVOKED,REJECTED/REVOKED→可重发 PENDING;每个非法转移有测试
3. OWNER 门与 ceiling ⊆ requested 在服务层强制且有测试
4. 五个新审计事件入词表且服务路径真实发布
5. V6 双库兼容(H2 本地过,PG Testcontainers CI 过)
6. 回合末 hook 会自动 `spotless:apply`(纯格式化)。若发现任何**非格式化**的自动改动,立即停下汇报,不要自行"顺手修"
7. hook 的评审提醒(git 门)由主会话处理,你不用管,也不要跑 git

## 五、汇报(≤40 行)

改动文件清单(新建/修改分列)、新增测试数、`mvn verify` 结果证据(命令+尾行)、遗留/豁免事项(无则写无)。验证用临时产物不留。
