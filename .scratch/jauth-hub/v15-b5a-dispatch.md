# v1.5 B5a 派单词:front-dist jar 工件 + 全链接线(纯增量,不拆 SSR)

> 自包含任务书。你只做本文范围内的事;范围外一律停下汇报。**禁止任何 git 操作**(提交权在主会话)。

## 背景与目标

v1.5 定案:同一份 jauth-hub-front 代码双形态发行——①同域名分离部署(已有);②**dist 打 jar 发布 Maven Central,嵌入宿主加依赖即白得整套 UI**。本批只做增量:新 Maven 模块 + 接线 + CI,**不动任何 SSR 资源**(B5b 才拆)。

## 已核实接线事实

1. Boot 静态资源聚合:任一 jar 的 `classpath:/static/**` 都会被宿主默认资源处理在 `/**` 出网——dist jar 放 `static/front/**` 即零代码服务 `/front/**`(Boot LaunchedURLClassLoader 对嵌套 jar 同样成立,java -jar 可用)。
2. app 已有 `/front/**` 出网 + 深链回退(AppWebConfiguration,B4 v1.4)——dist jar 在 classpath 即被其服务;嵌入宿主无 AppWebConfiguration,靠 Boot 默认 `/**` 静态映射即可命中 `/front/index.html`(深链回退缺失是宿主侧已知边界,javadoc 记录)。
3. app 侧现挂 front-skin Maven profile(拷 dist 进 app jar)——本批后由模块依赖取代,profile 保留不动(B5b 清理)。
4. 先例:app pom 的 front-skin profile(enforcer requireFilesExist 兜 copy-resources 静默缺口 + resources 拷贝,两插件均 Boot BOM 管)。

## 任务清单

1. **新模块 `jauth-hub-front-dist/`**(进 reactor,版本随父 1.4.0,packaging jar):
   - pom:profile `dist`(默认关)内 enforcer(requireFilesExist:`${maven.multiModuleProjectDirectory}/jauth-hub-front/dist/index.html`,消息提示先 npm 构建)+ maven-resources-plugin 把 dist 拷到 `${project.build.outputDirectory}/static/front`;
   - 无 dist 时(默认)产出**空 jar**并打 WARN(不 fail)——reactor 常规构建不被 npm 绑架;
   - 模块 README 一页:是什么/怎么构建(npm → mvn -Pdist)/怎么消费(坐标 + 依赖即得 /front/**)。
   - 根 pom `<modules>` 增一行;模块顺序放 examples 前。
2. **app 接线**:`jauth-hub-app/pom.xml` 增 jauth-hub-front-dist 依赖(provided?否——compile,使 java -jar 制品内嵌 UI);README 快速入门的 front 运行段改为"打包自带(经 dist 模块)"口径。
3. **examples/embedded-demo 接线**:增同一依赖,README/embedded-demo 文档一句话("白得 UI:引此依赖,/front/** 即你的登录皮")——嵌入契约新形态示范。
4. **CI(ci.yml)**:verify job 增 node 步骤(setup-node 22 + jauth-hub-front 下 npm ci + npm run build)置于 mvn 前;**并激活 `mvn -B verify -Pdist`**——从此 Java CI 全链含真 dist(enforcer 活,integration 测试可见真 UI 资源);front job 保持独立并行(双保险)。
5. **一个集成测试**:starter 或 app 测试(以 -Pdist 构建时可跑)断言 `/front/index.html` 从 classpath 出网——用测试资源夹具替代真 dist(不依赖构建序):在 app 的既有 FrontSkin 集成测试里补一条"dist jar 形态"断言(测试资源 static/front/index.html 已有夹具,断言语义不变即可;若已覆盖则说明,不硬凑)。

## 约束

- **禁做**:git 操作;删除/改动任何 SSR 模板与页面行为(B5b 范围);版本号改动(1.4.0);新增第三方依赖(插件均 Boot BOM 管);动 jauth-hub-front/ 工程(只读其 dist 产物)。
- 代码/文档风格照仓;enforcer/copy-resources 注释引用 v1.4 B4 实测教训(copy-resources 静默缺口)。
- 自测:`npm run build`(供 dist)→ `mvn -B verify -Pdist` 全绿(enforcer 活性验证);再跑一次默认 `mvn -B verify` 全绿(空 jar 降级验证)。

## 汇报格式(≤15 行)

1. 结论一句话;2. 变更文件清单;3. 两态验证结果(-Pdist / 默认)+ 测试数;4. 偏差与理由(如有);5. 临时文件清单(无则写"无")。
