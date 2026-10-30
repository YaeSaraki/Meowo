# AGENTS.md

面向 Paper / Minecraft 26.2 的 Kotlin 多模块插件脚手架。18 个可独立启停的业务 context，加 `api`、`shared`、`platform`、`ui`、`core` 五个基础模块。

**改动本项目前先读 [docs/minimax/README.md](docs/minimax/README.md)**：那里记录了架构红线、生命周期与线程模型、数据不变量、精确到文件的改造步骤，以及本机环境的坑。

## Setup commands

- 需要 JDK **21 + 25**：Gradle 8 daemon 用 21，编译与测试用 25。字节码目标是 21，产物跑在 Java 21 服务端上。Gradle 会自动发现已安装的 JDK。
- 构建与测试：`./gradlew check build`（`check` 已经挂上两道门禁，等于全部）
- 只跑测试：`./gradlew test`
- 产物：`core/build/libs/Meowo-1.0.0.jar`（单 jar，含全部 context）
- 启动本地服：`ACCEPT_MINECRAFT_EULA=true ./gradlew :core:runServer`，或 `./scripts/start-server.sh`

### 受限沙箱里的构建

Gradle 默认写 `~/.gradle`，Kotlin 编译守护进程默认写 `~/Library/Application Support/kotlin/daemon`。两者都不可写时，构建会中途被 OOM 杀掉，留下**空的模块 jar**，随后测试报出与真实原因无关的 `NoClassDefFoundError`。沙箱里改用：

```bash
cp -Rc ~/.gradle/caches/modules-2/. .gradle-home/caches/modules-2/
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test :core:build verifyArchitecture verifyCoverage \
  --offline --no-daemon --no-parallel \
  -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" \
  -Pkotlin.compiler.execution.strategy=in-process
```

MockBukkit 还需要可写的 `java.io.tmpdir`——测试进程不继承 daemon 的 `-Djava.io.tmpdir`，要用 init 脚本指到 `.tmp/`（见 [docs/minimax/README.md](docs/minimax/README.md) 与 [19-测试审计](docs/minimax/19-测试审计.md)）。看到「某个类找不到」先 `unzip -l` 那个模块的 jar 确认是不是空的。

## Project layout

- `api/` — 跨 context 契约（`EconomyAPI`、`SettlementAPI`、`UIAPI`、`FocusAPI`、`AvatarAPI`、`HomeMenuAPI`、`ChatModerationAPI`）
- `shared/` — 无状态工具
- `platform/` — Bukkit 门面层（`ChatLine`、`Durations`、`Messages`）
- `ui/` — **唯一**允许出现 TabooLib 菜单代码的模块；屏幕是数据，不是代码
- `core/` — 组合根 `MeowoRuntime`，唯一知道具体 context 的地方
- `contexts/meowo-*/` — 18 个业务 context，各自可独立启停
- `docs/minimax/` — 开发准本：红线、生命周期、线程模型、数据模型、改造配方、坑
- `scripts/` — 本地服与客户端脚本
- `contexts/` 的清单以 `settings.gradle.kts` 为准，`verifyArchitecture` 从同一处派生

## Code style

- Kotlin，`kotlin.code.style=official`
- context 内部分层：`domain` / `application` / `infrastructure` / `interfaces`；小 context 保持平铺即可
- **`domain` 是纯净层**：不得 import `org.bukkit`、`io.papermc`、`taboolib`、`net.kyori`、`com.meowo.platform`、`com.google.gson`。门禁从目录结构派生纯净根，不靠手写清单
- **context 之间不得互相 import**。跨 context 通信只走 `api/` 的接口契约（注册到 Bukkit `ServicesManager`）
- 中文注释解释「为什么」，不复述代码

## Testing instructions

- `./gradlew test` — 单元与集成测试（JUnit 5 + MockBukkit，域层测试不碰 Bukkit，存储测试跑真实 SQLite）
- 覆盖率门禁：总体行覆盖 ≥ 70%，economy domain ≥ 90%
- 新增 context 会进入总体覆盖率分母——补测试不是可选项
- 改动基线（测试数、覆盖率、语言键数）时同步更新 `docs/minimax/README.md` 与 `docs/acceptance.md`
- 加一条用户可见文案要同时改 `zh_CN.yml` 与 `en_US.yml`，两个文件键名与占位符完全对齐

## PR & commit conventions

- 分支从 `main` 切出，不直接推 `main`
- 提交信息用 conventional commits，带 scope：`feat(economy):`、`build(gate):`、`docs:`、`ci:`、`chore(repo):`；正文用中文
- 发布靠 tag：`git push origin v1.2.3`，`release.yml` 会构建并发布
- CI 跑 `./gradlew check build`，并把 jar 存为 workflow artifact

## Agent skills

### Issue tracker

Issues 与 spec 存在 GitHub Issues 里，用 `gh` CLI 操作。仓库当前**没有配 git remote，也还没装 `gh`**——先 `gh auth login` 并 `git remote add origin <owner>/<repo>`，否则 tracker 操作无法进行。见 `docs/agents/issue-tracker.md`。

### Triage labels

五个分诊状态标签 `needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`，加两个分类标签 `bug`、`enhancement`。见 `docs/agents/triage-labels.md`。

### Domain docs

多上下文布局：根目录 `CONTEXT-MAP.md` 指向各 context，术语表在 `contexts/meowo-<name>/CONTEXT.md`，决策记录在 `docs/adr/`。见 `docs/agents/domain.md`。
