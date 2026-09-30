# B13 派单词:1.1.0 版本冻结 + README 双语 + 动态徽章(发版机械面)

> 你是 B13 批次的实施 subagent。本文自包含。**禁止任何 git 操作**(tag/发版由主会话按 RELEASE_PROCESS 执行)。

## 一、任务(纯文档+版本号,零 Java 改动)

### 1. 版本冻结 1.0.0 → 1.1.0(阶段 1)

照 97e8b3b(1.0.0 发版)的既定面:根 pom + 六模块 pom + examples 两 pom 的 `<version>` 一次性升 1.1.0。先 `grep -rn "1.0.0" --include=pom.xml` 全量清点,**兼容性对比基线引用(指旧 tag 的锚点)不动**;examples 里若另有引用本项目的依赖版本属性一并升(97e8b3b 先例只动各 pom 自身 version,以 grep 结果为准)。文档中的版本号随第 2/3 项一起改,不单独动。

### 2. README.md 刷新

- 徽章行:version→1.1.0;**CI 徽章换动态**:`![CI](https://github.com/Oatelauser/jauth-hub/actions/workflows/ci.yml/badge.svg)`(滑账④ 收口)
- 特性一览补 v1.1 平台层:org 自助创建/安装两步制审批(ceiling 封顶)/发行=请求∩consent∩ceiling 运行时取交/orgs claim 富化/我的应用注册(个人+org)/用户管理与自助改密
- 用户手册/页面导览若有目录:补 v1.1 新页面(我的组织/安装审批/我的应用/用户管理/档案);量化口径(测试数等)按当前实际(341)校准
- 头部语言切换链接加 README_en.md

### 3. README_en.md 新建(滑账③)

- README.md 的英文镜像:同结构同信息量(定位/特性/徽章/quick start/模块/页面/文档链接),**不是逐句直译**——技术文档地道英文
- 双向语言链接(README ↔ README_en)

## 二、验收标准

1. `grep -rn "1.0.0" --include=pom.xml` 零残留(基线锚点除外,如有须汇报指认)
2. `mvn verify` 全绿(版本号变更后必跑,证明无破坏;三门禁过)
3. README 徽章:CI 为动态 workflow badge;两 README 语言链接互通
4. 汇报 ≤30 行:改动文件清单、verify 尾行、README_en 字数、遗留

## 三、开工前先读

README.md 全文、97e8b3b(`git show 97e8b3b --stat` 的版本面)、docs/SPEC.md §0/§2/§5(v1.1 特性与模块口径——README 措辞以 SPEC 为准)。
