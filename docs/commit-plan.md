# 提交计划

本文记录 Meowo 仓库的提交策略。仓库在 2026-10-26 之前没有版本控制，
本文档描述的是**第一次**入库时采用的切分方式。

## 为什么这样切

三条约束决定了切分方式：

1. **依赖方向必须成立。** 模块的编译顺序是
   `api / shared → platform → ui → contexts → core`。
   core 的 `build.gradle.kts` 直接 `project(":contexts:…")`，所以
   contexts 必须先于 core 入库；`contexts/meowo-economy:domain` 必须先于
   `contexts/meowo-economy`。任何一个提交把顺序倒过来，那次提交就编译
   不过——二分查找会被钉在某个提交上，而那个提交是红的。

2. **每个提交都要能单独说清自己在做什么。** 「新增 X 功能」是一个理由，
   「新增 X 功能，同时把架构门禁也改了」是两个。混在一起的提交在
   `git log` 里看不出边界，回滚时也不知道该退哪一个。

3. **门禁要晚于被它保护的东西。** `verifyArchitecture` 检查的是目录树里
   的纯净层，`verifyCoverage` 检查的是聚合覆盖率。两者都只能在相关代码
   已经存在之后才有意义。所以它们是最后几个提交，不是第一个。

## 提交序列

| # | 类型 | 主题 |
|---|---|---|
| 1 | `chore(repo)` | 建立仓库基线、忽略规则与本地联调脚本 |
| 2 | `build` | 引入 Gradle 多模块骨架与依赖版本目录 |
| 3 | `feat(api)` | 定义跨上下文公开契约与领域事件 |
| 4 | `feat(shared)` | 提供事件总线与上下文生命周期基础设施 |
| 5 | `feat(platform)` | 抽取面向服务端的通用能力与可测纯函数 |
| 6 | `feat(ui)` | 实现配置驱动的菜单框架 |
| 7 | `feat(economy)` | 拆出独立 Gradle 模块的经济领域与钱包结算 |
| 8–18 | `feat(*)` | 逐个限界上下文：teleport / spawn / sit / hat / shadowbox / nametag / mine / shop / chat / menu / render / home / invsee / avatar / pomodoro / agents |
| 19 | `feat(core)` | 组装插件入口、运行时装配与多语言资源 |
| 20 | `build(gate)` | 新增架构门禁，校验纯净层与上下文边界 |
| 21 | `build(gate)` | 新增覆盖率门禁，并把两道门禁接入 `check` |
| 22 | `ci` | 新增 GitHub Actions 校验工作流 |
| 23 | `docs` | 补充 README、架构设计与开发手册 |
| 24 | `docs` | 归档验收证据与验证记录 |

上下文按功能族而不是字母序排列：经济在最前（其他上下文通过
`EconomyAPI` 依赖它的契约），聊天与智能体在后（它们体量最大，且
引用了前面已入库的平台能力）。

## 未入库的内容

- `docs/Pomodoro/`：上游 `noctiro/Pomodoro` 的完整克隆，只在写
  `meowo-pomodoro` 时当参考。它自带 `.git`，提交进来会变成嵌套仓库，
  文件被 gitlink 遮住。已在 `.gitignore` 中排除。
- `.run/`（1.3G 的服务端与客户端）、`.tmp/`（758M）、`.gradle-home/`、
  全部 `build/` 目录：本地状态，不是源码。

## 之后怎么提交

- 一个提交只做一件事。修 bug 和顺手改格式要分开。
- 门禁或 CI 的改动单独成提交，并说明**它挡住了什么**。
- 提交信息用 `type(scope): 摘要`，正文解释「为什么」，不复述 diff。
- 提交前跑 `./gradlew check`；CI 跑的是同一条命令。
