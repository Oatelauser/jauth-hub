# jauth-hub-front-dist

`jauth-hub-front`（Vue 3 + Vite 分离前端）构建产物 dist 的 **jar 工件**（v1.5 B5a）：dist 以
`classpath:/static/front/**` 的形态住进 jar，发布 Maven Central 后，任何 Boot 宿主加一个依赖即
白得整套 UI——Boot 默认静态资源映射（`/**`）直接把 `/front/**` 出网，对嵌套 jar 同样成立
（Boot 的 LaunchedURLClassLoader，`java -jar` 可用）。

## 构建

```bash
cd jauth-hub-front && npm ci && npm run build   # 产出 jauth-hub-front/dist/
cd .. && mvn -Pdist -pl jauth-hub-front-dist install   # enforcer 校验 dist 在场后拷成 static/front
```

- **默认态（不激活 profile）产出空 jar 并打 WARN，不 fail**——reactor 常规构建不被 npm 绑架；
- **`-Pdist` 缺 dist 直接 fail-fast**（enforcer）：copy-resources 对缺失源目录静默跳过是 v1.4 B4
  实测教训，发布场景绝不许静默出空皮制品；
- 两条命令都须从仓库根目录执行（`${maven.multiModuleProjectDirectory}` 随调用目录变化）。

## 消费

```xml
<dependency>
  <groupId>io.github.oatelauser</groupId>
  <artifactId>jauth-hub-front-dist</artifactId>
  <version>1.4.0</version>
</dependency>
```

`/front/index.html` 即你的登录皮；页面 GET（登录/consent/设备验证/sudo 等）一律 302 到
`/front/<路由>`（查询串原样转发，v1.5 起无条件）。两个宿主侧已知边界（jauth-hub-app 侧由 AppWebConfiguration
代管，嵌入宿主没有）：

1. **history 深链回退缺失**：`/front/login` 这类无物理文件的路径不走回退，须宿主自配
   （参考 jauth-hub-app 的 `AppWebConfiguration`：`PathResourceResolver` 回退 index.html）；
2. **安全链放行**：宿主 default 链若 denyAll + 显式白名单（嵌入契约推荐形态），记得把
   `/front/**` 加进白名单，否则皮在 classpath 也被 401 拦在门前。

（这是纯资源 jar：无 java 代码、无传递依赖。）
