# 05 学习型前端页面清单与形态

Type: grilling
Status: resolved
Blocked by: 04

## Question

用户定位：页面简单即可，核心是**教学**——前端开发者看后能明白底层认证与交互逻辑（作为学习 demo）。基于票 04 的 v1 范围决定：

- 页面清单：登录页、Consent 页、已授权应用看板、（独立模式）应用注册/管理页、PAT 管理页、用户管理页（列表/禁用/重置密码，管理员用，04 复审新增）——哪些进 v1
- 教学性怎么体现：页面内嵌流程图/步骤标注（如授权码流程每一步高亮）、每页配"发生了什么"说明块、演示用示例客户端页面（模拟第三方走完整流程）？
- 技术：Thymeleaf SSR + 少量原生 JS/htmx（用户已接受推荐）
- 嵌入模式下这些页面随库提供还是宿主自渲染（与票 03 契约对齐）

产出：页面清单 + 每页一句话教学内容 + 一个"示例客户端 demo 页"的去留决定。

## Answer

裁定：Q1 分档照推荐、**Q2 demo 真实联调**、Q3 三层教学、**Q4 A+B**、Q5 语言外观照推荐。

**页面清单（10 页 + demo 区，按里程碑）**

| 里程碑 | 页面 | 所属 | 一句话教学内容 |
|---|---|---|---|
| v1.0 | 登录页 | core/web | 表单认证建立 session、auth_time 在此产生 |
| v1.0 | Consent 页 | core/web | 授权码流程第二步，scope 交集在此成形 |
| v1.0 | 设备验证页 | core/web | device flow 的用户确认端 |
| v1.0 | PAT 管理页 | selfservice | 不走浏览器的另一种令牌发放路径 |
| v1.0 | 已授权应用看板 | selfservice | 授权的持久化与撤销语义 |
| v1.1 | 应用注册/管理页 | selfservice | client 凭证模型（secret/PKCE、回调白名单） |
| v1.1 | org 安装审批页 | selfservice | 权限封顶：org 批准上限与用户 consent 取交集 |
| v1.1 | 用户管理页 | **app 专属** | 管理的是自持用户库（嵌入宿主用户自己管） |
| v1.2 | Passkey 注册/管理页 | core/web + selfservice | WebAuthn 凭据生命周期 |
| v1.2 | sudo 再认证页 | core/web | step-up：敏感操作要求近期认证 |

**demo 区（app 内置 `/demo`，真实联调非模拟）**：模拟第三方应用对真实端点完整走授权码 + PKCE（含公开客户端 code_verifier 生成），实时展示每步 HTTP 请求/响应日志（302 重定向、code、token 交换、introspection），最后用 token 调受保护接口展示鉴权结果。前端开发者由此看懂全部底层交互。

**教学三层**：① 每页可折叠"发生了什么"说明块（默认开，`jauth-hub.educational=false` 关闭）② demo 页实时 HTTP 日志 ③ 登录/consent 侧边小流程图高亮当前步骤。

**模块增补（Q4=A+B，修订 03 的四模块 → 五模块）**：新增 `jauth-hub-selfservice` 可选模块——看板/PAT/应用管理等**jauth-hub 域数据页面**，app 依赖它，嵌入宿主也可选依赖它；**用户管理页只留 app**（绑定自持用户库，嵌入宿主的用户体系归宿主管）。

**语言与外观**：UI 中文单语 + i18n 资源结构就位（`messages_zh` 填满、`messages_en` 骨架留白）；README 中文先行、英文随 v1.0 发布补齐；代码/提交信息英文；手写单文件 CSS，无框架无 CDN 外链。
