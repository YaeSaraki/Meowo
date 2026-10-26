# Meowo 插件脚手架 · 提示词

> **关于版本号 `26.2`**  
> 用户明确指定服务器版本为 `26.2`(非 Mojang 标准 `1.X.X` 格式,可能是某 fork 的 tag,例如 Leaf / Purpur / 自编译)。  
> 由于该 tag **不直接对应 Paper API Maven 坐标**,在动手前 AI 必须先做"版本核对",详见提示词 § 二。
>
> **关于架构:借鉴 DDD,非严格 DDD**  
> 借鉴:限界上下文(Bounded Context)、4 层架构(domain/application/infrastructure/interfaces)、领域事件、防腐层(ACL)、共享内核(Shared Kernel)。  
> 舍弃:聚合根严格建模、CQRS、事件溯源等过度工程化模式(MC 插件场景不必要)。

---

````markdown
# 任务:搭建 Meowo 插件脚手架(TabooLib 6 + DDD + 多子插件)

## 一、目标

为 **Meowo** 插件搭建生产级脚手架。要求:

1. **架构思想:借鉴 DDD**,每个业务子模块 = 一个 **Bounded Context**
2. **多子插件架构**:主插件 + N 个子插件(@TabooLib 子入口),每个子插件独立生命周期,可单独禁用
3. **多模块 Gradle**:每个 context 独立 Gradle 模块,domain 层纯净无 Bukkit 依赖,可纯 JUnit 单测
4. **跨上下文通信**:通过**内部事件总线** + **API 接口**解耦,不使用直接类引用
5. **服务端版本:26.2**(用户指定,具体 API 坐标见 § 二)

## 二、版本核对(动手第一步,不可跳过)

用户给定的 `26.2` **不是 Mojang 标准版本号**。在写任何代码前,AI 必须做:

1. **确认 Paper API 实际版本号**
   - 打开 https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/
   - 找到该 fork 实际对应的版本(可能是 `1.21.x` 或 `1.22.x`)
   - 记录为 `ACTUAL_PAPER_API_VERSION`

2. **确认服务端 jar 来源**
   - 是 Paper 自编译?Leaf?Purpur?
   - jar 下载地址:`DOWNLOAD_URL`
   - sha256:`DOWNLOAD_SHA256`(可选但推荐)

3. **确定客户端版本**
   - 与服务端**完全一致**的 `ACTUAL_MC_VERSION`
   - Vanilla 客户端下载地址:`https://minecraft.net/en-us/download`

4. **确定 MockBukkit 版本**
   - 见 https://github.com/MockBukkit/MockBukkit/releases
   - 选择与 `ACTUAL_PAPER_API_VERSION` 兼容的最新版

把以上 4 个值**填入下方 § 三的表格**,再继续。

## 三、项目基本信息(版本核对后填写)

| 项 | 值(待核对) | 占位 |
|---|---|---|
| 插件名 | **Meowo** | 固定 |
| 主命令 | **`/meowo`** | 固定 |
| 服务端版本 | **26.2** | 固定 |
| 服务端 jar | `${DOWNLOAD_URL}` | 待填 |
| Paper API 坐标 | `io.papermc.paper:paper-api:${ACTUAL_PAPER_API_VERSION}` | 待填 |
| 客户端版本 | `${ACTUAL_MC_VERSION}` | 待填 |
| Java | **21** (Temurin LTS) | 固定 |
| 语言 | **Kotlin** | 固定 |
| 构建 | **Gradle 8.x + Kotlin DSL + Version Catalog** | 固定 |
| 框架 | **TabooLib 6.2.x** | 固定 |
| 测试 | **MockBukkit(JUnit 5)+ Domain 层纯 JUnit** | 固定 |

## 四、架构总览

### 4.1 模块拓扑
```
                ┌─────────────┐
                │   :core     │  主插件入口
                │  MeowoCore  │  - 加载顺序
                └──────┬──────┘  - 全局配置
                       │
        ┌──────────────┼──────────────┐
        │              │              │
   ┌────▼────┐    ┌────▼────┐    ┌────▼────┐
   │   :api  │    │ :shared │    │ :contexts│
   │ 公开 API │    │ 共享内核│    │ 子插件  │
   └─────────┘    └─────────┘    └─────────┘
                                    │
                ┌───────────────────┼───────────────────┐
                │                   │                   │
          ┌─────▼─────┐       ┌─────▼─────┐       ┌─────▼─────┐
          │ :meowo-   │       │ :meowo-   │       │ :meowo-   │
          │  economy  │       │   menu    │       │  render   │
          └───────────┘       └───────────┘       └───────────┘
```

### 4.2 单个 Bounded Context 的 4 层架构
```
┌──────────────────────────────────────────┐
│         interfaces/  (接口适配层)         │  ← Bukkit API、命令、UI、Listener
├──────────────────────────────────────────┤
│      infrastructure/ (基础设施层)        │  ← SQLite、Redis、Bukkit事件适配
├──────────────────────────────────────────┤
│       application/ (应用层)              │  ← 用例编排、事务、跨聚合协调
├──────────────────────────────────────────┤
│         domain/       (领域层)            │  ← 实体、值对象、领域服务、领域事件
└──────────────────────────────────────────┘
                  ▲
                  │  依赖方向:由外向内
                  │  (内层不知道外层存在)
```

**依赖规则**(DDD 核心):
- `domain` 不知道 `application`、`infrastructure`、`interfaces`,不知道 Bukkit
- `application` 只依赖 `domain`
- `infrastructure` 实现 `domain` 里定义的接口(Repository、Gateway)
- `interfaces` 是最外层,负责把 Bukkit 调用翻译成 application 调用

## 五、Bounded Context 规划

主命令 `/meowo` 拥有以下子命令,**每个子命令映射到一个 context 子插件**:

| 子命令 | 对应 Context | 业务职责 | 是否样板实现 |
|--------|-------------|---------|-------------|
| `/meowo help` | `:core` | 帮助信息 | ✅ |
| `/meowo info` | `:core` | 运行时统计 | ✅ |
| `/meowo reload` | `:core` | 重载配置 | ✅ |
| `/meowo eco <give/take/set/balance>` | **`:meowo-economy`** | 金币系统 | ✅ 完整 |
| `/meowo menu` | **`:meowo-menu`** | UI 菜单演示 | ✅ 简化 |
| `/meowo render <spawn/clear>` | **`:meowo-render`** | FakeEntity 渲染 | ✅ 简化 |
| `/meowo chat ...` | `:meowo-chat` | 聊天增强 | 📝 仅文档化 |
| `/meowo tp ...` | `:meowo-teleport` | 传送系统 | 📝 仅文档化 |
| `/meowo punish ...` | `:meowo-punish` | 惩罚系统 | 📝 仅文档化 |

> **"完整"样板**:`meowo-economy` 展示完整的 4 层 DDD + Repository + 事件 + 单测  
> **"简化"样板**:`meowo-menu`、`meowo-render` 展示 context 的轻量结构  
> **"仅文档化"**:`meowo-chat` 等只写 README 中的开发指南,不实现代码

## 六、目录结构(完整)

```
Meowo/
├── build.gradle.kts                          # 根构建:配置所有子模块
├── settings.gradle.kts                       # include 子模块
├── gradle.properties
├── gradle/
│   └── libs.versions.toml                    # Version Catalog
│
├── core/                                     # 主插件入口
│   ├── build.gradle.kts                      # 应用 paperweight-userdev
│   └── src/main/
│       ├── kotlin/com/meowo/core/
│       │   ├── MeowoCore.kt                  # @TabooLib 主入口
│       │   ├── PluginManager.kt              # 子插件加载/卸载
│       │   ├── command/
│       │   │   ├── CommandHelp.kt
│       │   │   ├── CommandInfo.kt
│       │   │   └── CommandReload.kt
│       │   ├── config/GlobalConfig.kt        # @ConfigMarker
│       │   └── listener/ServerListener.kt
│       └── resources/
│           ├── plugin.yml
│           ├── config.yml
│           └── lang/{zh_CN,en_US}.yml
│
├── api/                                      # 公开 API(其他插件调用)
│   ├── build.gradle.kts                      # 纯 Kotlin,无 Bukkit
│   └── src/main/kotlin/com/meowo/api/
│       ├── MeowoAPI.kt                       # 入口:MeowoAPI.economy()
│       ├── EconomyAPI.kt                     # 公开接口
│       └── event/                            # 公开事件
│           ├── MoneyDepositedEvent.kt
│           └── MoneyWithdrawnEvent.kt
│
├── shared/                                   # 共享内核(Shared Kernel)
│   ├── build.gradle.kts                      # 纯 Kotlin
│   └── src/main/kotlin/com/meowo/shared/
│       ├── event/                            # 内部事件总线
│       │   ├── DomainEvent.kt                # 标记接口
│       │   ├── EventBus.kt                   # 同步事件总线
│       │   └── AsyncEventBus.kt              # 异步事件总线
│       ├── util/
│       │   ├── Logger.kt
│       │   └── Result.kt                     # 操作结果封装
│       └── ext/
│           └── BukkitExt.kt                  # 仅基础设施层用
│
├── contexts/                                 # Bounded Contexts
│   ├── meowo-economy/                        # 完整样板(完整 4 层)
│   │   ├── build.gradle.kts
│   │   └── src/main/
│   │       ├── kotlin/com/meowo/economy/
│   │       │   ├── EconomyPlugin.kt          # @TabooLib(id="Meowo-Economy")
│   │       │   │
│   │       │   ├── domain/                   # ── 领域层(纯 Kotlin)──
│   │       │   │   ├── model/
│   │       │   │   │   ├── Money.kt          # 值对象
│   │       │   │   │   ├── Wallet.kt         # 实体(聚合根)
│   │       │   │   │   └── Transaction.kt    # 实体
│   │       │   │   ├── repository/
│   │       │   │   │   └── WalletRepository.kt   # 接口(实现由 infra 提供)
│   │       │   │   ├── service/
│   │       │   │   │   └── EconomyDomainService.kt
│   │       │   │   └── event/
│   │       │   │       ├── MoneyDeposited.kt
│   │       │   │       └── MoneyWithdrawn.kt
│   │       │   │
│   │       │   ├── application/              # ── 应用层(纯 Kotlin)──
│   │       │   │   ├── usecase/
│   │       │   │   │   ├── DepositMoneyUseCase.kt
│   │       │   │   │   ├── WithdrawMoneyUseCase.kt
│   │       │   │   │   └── GetBalanceUseCase.kt
│   │       │   │   └── service/
│   │       │   │       └── EconomyApplicationService.kt
│   │       │   │
│   │       │   ├── infrastructure/          # ── 基础设施层(有 Bukkit/SQL 依赖)──
│   │       │   │   ├── persistence/
│   │       │   │   │   └── SqliteWalletRepository.kt
│   │       │   │   └── adapter/
│   │       │   │       └── BukkitEventAdapter.kt
│   │       │   │
│   │       │   └── interfaces/              # ── 接口适配层(ACL)──
│   │       │       ├── command/
│   │       │       │   └── EconomyCommand.kt
│   │       │       └── listener/
│   │       │           └── EconomyPlayerListener.kt
│   │       │
│   │       ├── resources/
│   │       │   ├── plugin.yml                # 子插件独立 plugin.yml
│   │       │   ├── config.yml
│   │       │   └── lang/{zh_CN,en_US}.yml
│   │       └── test/kotlin/com/meowo/economy/
│   │           ├── domain/MoneyTest.kt           # 纯 JUnit,无需 MockBukkit
│   │           ├── domain/WalletTest.kt
│   │           ├── application/DepositMoneyUseCaseTest.kt
│   │           └── interfaces/EconomyCommandTest.kt  # 需要 MockBukkit
│   │
│   ├── meowo-menu/                           # 简化样板
│   │   ├── build.gradle.kts
│   │   └── src/main/kotlin/com/meowo/menu/
│   │       ├── MenuPlugin.kt
│   │       ├── domain/MenuDefinition.kt
│   │       ├── application/OpenMenuUseCase.kt
│   │       ├── infrastructure/MenuRepository.kt
│   │       └── interfaces/MenuCommand.kt
│   │
│   └── meowo-render/                         # 简化样板
│       ├── build.gradle.kts
│       └── src/main/kotlin/com/meowo/render/
│           ├── RenderPlugin.kt
│           ├── domain/FakeEntity.kt
│           ├── application/SpawnFakeEntityUseCase.kt
│           ├── infrastructure/FakeEntityRenderer.kt  # packet 操作
│           └── interfaces/RenderCommand.kt
│
├── scripts/
│   ├── start-server.sh
│   ├── start-client.sh
│   └── dev.sh
└── README.md
```

## 七、关键设计模式

### 7.1 跨 Context 通信:事件总线

定义在 `shared/event/`:
```kotlin
// shared/.../event/DomainEvent.kt
interface DomainEvent

// shared/.../event/EventBus.kt
object EventBus {
    private val handlers = ConcurrentHashMap<Class<*>, MutableList<(Any) -> Unit>>()

    fun <E : DomainEvent> subscribe(eventType: Class<E>, handler: (E) -> Unit) {
        handlers.getOrPut(eventType) { CopyOnWriteArrayList() }.add(handler as (Any) -> Unit)
    }

    fun publish(event: DomainEvent) {
        handlers[event.javaClass]?.forEach { it(event) }
    }
}
```

### 7.2 跨 Context 调用:API 接口

定义在 `api/`,由各 context 实现,主插件统一暴露:
```kotlin
// api/.../EconomyAPI.kt
interface EconomyAPI {
    fun getBalance(player: UUID): BigDecimal
    fun deposit(player: UUID, amount: Money): Result<Unit>
    fun withdraw(player: UUID, amount: Money): Result<Unit>
}

// api/.../MeowoAPI.kt
object MeowoAPI {
    lateinit var economy: EconomyAPI
        private set
    internal fun registerEconomy(api: EconomyAPI) { economy = api }
}
```

context 实现 API 后调用 `MeowoAPI.registerEconomy(...)`,其他 context 只通过 `MeowoAPI.economy.xxx()` 调用。

### 7.3 子插件生命周期

```kotlin
// contexts/meowo-economy/.../EconomyPlugin.kt
@TabooLib(
    id = "Meowo-Economy",
    version = "1.0.0",
    description = "Economy bounded context",
    dependencies = ["Meowo-Core"]
)
class EconomyPlugin : Plugin() {
    override fun onEnable() {
        val repo = SqliteWalletRepository(dataFolder.resolve("wallets.db"))
        val domainSvc = EconomyDomainService(repo)
        val appSvc = EconomyApplicationService(domainSvc, EventBus)

        EconomyCommand.register(this, appSvc)
        BukkitEventAdapter.register(this, appSvc)

        MeowoAPI.registerEconomy(object : EconomyAPI {
            override fun deposit(player: UUID, amount: Money) =
                appSvc.deposit(player, amount).let { Result.success(it) }
            // ...
        })

        EventBus.subscribe(PlayerJoinedEvent::class.java) {
            appSvc.ensureWallet(it.player.uniqueId)
        }
    }
}
```

### 7.4 加载顺序
`:core` 必须最先加载,通过 `dependencies = ["Meowo-Core"]` 强制声明。

## 八、环境配置

### 8.1 Gradle Multi-Module

**`settings.gradle.kts`**(钉死):
```kotlin
rootProject.name = "Meowo"

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.taboop.top/repository/maven-public/")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.taboop.top/repository/maven-public/")
    }
}

include(":core")
include(":api")
include(":shared")
include(":contexts:meowo-economy")
include(":contexts:meowo-menu")
include(":contexts:meowo-render")
```

**`gradle/libs.versions.toml`**(Version Catalog,钉死):
```toml
[versions]
kotlin = "2.0.20"
paperweight = "1.7.1"
taboolib = "6.2.0"
paper-api = "${ACTUAL_PAPER_API_VERSION}"   # ← 填 § 二核对结果
mockbukkit = "${ACTUAL_MOCKBUKKIT_VERSION}" # ← 填 § 二核对结果
junit = "5.10.2"

[libraries]
paper-api = { module = "io.papermc.paper:paper-api", version.ref = "paper-api" }
paper-mojangapi = { module = "io.papermc.paper:paper-mojangapi", version.ref = "paper-api" }
taboolib-common = { module = "io.taboolib:taboolib-common", version.ref = "taboolib" }
taboolib-paper = { module = "io.taboolib:taboolib-platform-paper", version.ref = "taboolib" }
taboolib-database = { module = "io.taboolib:taboolib-database", version.ref = "taboolib" }
taboolib-ui = { module = "io.taboolib:taboolib-ui", version.ref = "taboolib" }
junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
mockbukkit = { module = "com.github.seeseemelk:MockBukkit-v1.21", version.ref = "mockbukkit" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
paperweight = { id = "io.papermc.paperweight.userdev", version.ref = "paperweight" }
```

**根 `build.gradle.kts`**:
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.paperweight) apply false
}

subprojects {
    group = "com.meowo"
    version = "1.0.0"

    apply(plugin = "java-library")
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
```

**`:core/build.gradle.kts`**(唯一用 paperweight 的模块):
```kotlin
plugins {
    alias(libs.plugins.paperweight)
}

dependencies {
    implementation(project(":api"))
    implementation(project(":shared"))
    implementation(project(":contexts:meowo-economy"))
    implementation(project(":contexts:meowo-menu"))
    implementation(project(":contexts:meowo-render"))

    compileOnly(libs.paper.api)
    compileOnly(libs.paper.mojangapi)
    implementation(libs.taboolib.common)
    implementation(libs.taboolib.paper)
    implementation(libs.taboolib.ui)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockbukkit)
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.meowo.core.MeowoCore"
    }
}

// run-paper 任务
tasks.register<JavaExec>("runServer") {
    group = "paper"
    description = "启动 Paper 服务器,自动加载 Meowo"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.papermc.paperweight.userdev.PaperweightMain")
    standardInput = System.`in`
}
```

**`:api/build.gradle.kts`**(纯 Kotlin,无 Bukkit):
```kotlin
dependencies {
    // 没有 paper-api,确保纯净
    testImplementation(libs.junit.jupiter)
}
```

**`:shared/build.gradle.kts`**(纯 Kotlin):
```kotlin
dependencies {
    testImplementation(libs.junit.jupiter)
}
```

**`:contexts/meowo-economy/build.gradle.kts`**(样板):
```kotlin
dependencies {
    implementation(project(":api"))
    implementation(project(":shared"))

    compileOnly(libs.paper.api)
    implementation(libs.taboolib.common)
    implementation(libs.taboolib.database)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockbukkit)
}
```

### 8.2 一键启动
- `./gradlew :core:runServer` → 启动服务端
- `scripts/start-client.sh` → 启动客户端(直连 127.0.0.1:25565)
- `scripts/dev.sh` → 串起两边

### 8.3 `server.properties`
```properties
online-mode=false
server-port=25565
view-distance=8
simulation-distance=6
max-players=20
motd=\u00a7bMeowo Dev Server
```

### 8.4 `core/src/main/resources/plugin.yml`
```yaml
name: Meowo
version: '@version@'
main: com.meowo.core.MeowoCore
api-version: '1.21'   # 按实际填
authors: [你的名字]
description: Meowo production-grade DDD plugin scaffold
depend: []
commands:
  meowo:
    description: Meowo main command
    usage: /meowo <subcommand>
    aliases: [mw]
permissions:
  meowo.use: { default: true }
  meowo.admin: { default: op }
  meowo.economy.*: { default: op }
```

## 九、样板功能清单

| # | 功能 | 所在 Context | DDD 能力展示 | 验收 |
|---|------|-------------|--------------|------|
| 1 | 主命令 + 5 个子命令 | `:core` | 命令路由 | `/meowo help` 完整 |
| 2 | 全局配置 + 热重载 | `:core` | @ConfigMarker | `/meowo reload` 即时生效 |
| 3 | 多语言 | `:core` | lang 模块 | 切语言生效 |
| 4 | **金币完整系统** | `:meowo-economy` | **完整 4 层 + Repository + 事件** | `/meowo eco give/take/set/balance` 全可用,数据持久化 |
| 5 | **领域事件跨 context** | `:meowo-economy` | EventBus | 玩家加入自动建钱包(由 `:core` 触发) |
| 6 | **API 暴露** | `:api` | MeowoAPI | 其他插件 `MeowoAPI.economy.deposit(...)` 可调用 |
| 7 | UI 菜单 | `:meowo-menu` | 简化 4 层 | `/meowo menu` 弹出 |
| 8 | FakeEntity 渲染 | `:meowo-render` | 简化 4 层 + packet | `/meowo render spawn 10` 客户端见 10 个实体 |
| 9 | Domain 单测 | `:meowo-economy` | 纯 JUnit | `MoneyTest`、`WalletTest` 全过,无 MockBukkit 启动 |
| 10 | 接口层单测 | `:meowo-economy` | MockBukkit | `EconomyCommandTest` 全过 |

## 十、编码规范(DDD 强调)

### Domain 层
- **禁止** `import org.bukkit.*` 或任何 Bukkit API
- 实体用 `data class`,值对象用 `@JvmInline value class`
- 领域事件:`data class MoneyDeposited(val player: UUID, val amount: Money) : DomainEvent`
- 业务规则**在领域层完成**,不在 application 层

### Application 层
- UseCase 一个方法 = 一个用例
- 不直接调用 Repository,而是 Domain Service 协调
- 跨上下文通过 EventBus / API,**不直接 import 对方类**

### Infrastructure 层
- 实现 domain 定义的接口(`WalletRepository`)
- 所有 Bukkit/SQL/Redis 代码都在这一层

### Interfaces 层
- 命令只做参数解析,转发给 application
- Listener 把 Bukkit 事件翻译成 domain 事件
- 这一层是**防腐层(ACL)**:外部 API 变了,只改这里

### 通用
- 全用 Kotlin,全用 TabooLib Kotlin DSL
- 业务逻辑必加 KDoc
- 跨 context 通信:EventBus(异步事件)或 API(同步调用)

## 十一、验收清单

### 版本核对
- [ ] 已在 § 二完成 4 个值(API 版本、jar URL、MC 版本、MockBukkit 版本)
- [ ] 已填入 `libs.versions.toml`

### 环境
- [ ] `./gradlew :core:runServer` 启动无 ERROR,日志显示 `127.0.0.1:25565`
- [ ] `./scripts/start-client.sh` 自动连入
- [ ] `./scripts/dev.sh` 一键双开
- [ ] (可选)HotSwap 改方法体生效

### 架构
- [ ] `:api` 和 `:shared` 模块 `gradle dependencies` 中**没有** `io.papermc.paper:paper-api`
- [ ] `:meowo-economy` 模块依赖 `:api`、`:shared`,不直接依赖 `:meowo-menu` 等其他 context
- [ ] Domain 层文件没有 `import org.bukkit`

### 插件 Meowo
- [ ] `/meowo help` 列出全部子命令
- [ ] `/meowo eco give Steve 100` → `/meowo eco balance Steve` 显示 100
- [ ] 重启服务端,余额仍然在
- [ ] `/meowo reload` 改 config 后即时生效
- [ ] `/meowo menu` 弹出 UI,可交互
- [ ] `/meowo render spawn 10` 客户端看到 10 个 FakeEntity,服务端 `/minecraft:list` 不增加实体
- [ ] 切语言,所有提示消息随之变化
- [ ] 写出**另一个** context 模块:`meowo-chat`(README 中给完整 step-by-step 教程)
- [ ] `./gradlew test` 全绿:
  - `domain/MoneyTest`(纯 JUnit)
  - `domain/WalletTest`(纯 JUnit)
  - `application/DepositMoneyUseCaseTest`(纯 JUnit + Fake Repository)
  - `interfaces/EconomyCommandTest`(MockBukkit)
- [ ] 单测覆盖率 ≥70%(domain 层 ≥90%)
- [ ] `./gradlew :core:build` 生成 fat jar,丢进 Paper `plugins/` 目录运行正常,所有子插件自动加载

### 文档
- [ ] README 含:项目介绍、架构图(DDD 分层 + 模块拓扑)、快速开始、目录结构、子命令清单、配置说明、**新增 context 教程**
- [ ] 每个 context 独立 README,说明职责与依赖
- [ ] 演示截图或日志证据覆盖 10 个样板功能

## 十二、交付物(按顺序)

1. **版本核对报告**:4 个值 + 来源链接
2. **完整目录树**(实际创建,非空壳)
3. **根构建文件**:`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`gradle/libs.versions.toml`
4. **各模块 `build.gradle.kts`**
5. **`core/` 全套源文件 + resources**
6. **`api/` 全套源文件**(纯 Kotlin)
7. **`shared/` 全套源文件**(纯 Kotlin)
8. **`contexts/meowo-economy/` 全套源文件 + resources + 4 类单测**
9. **`contexts/meowo-menu/` 简化样板全套**
10. **`contexts/meowo-render/` 简化样板全套**
11. **`contexts/meowo-chat/` 的 README + 接口预留(实现留给后续)**
12. **三个启动脚本**
13. **顶层 README**
14. **验收清单逐项打勾的日志/截图证据**

## 十三、新增 Context 的开发流程(必须写进 README)

将来要加新功能,标准流程:

1. `mkdir -p contexts/meowo-XXX/{src/main/kotlin/com/meowo/xxx/{domain,application,infrastructure,interfaces},src/main/resources,src/test/kotlin/com/meowo/xxx}`
2. 在 `settings.gradle.kts` 加 `include(":contexts:meowo-XXX")`
3. 写 `build.gradle.kts`(复制 economy 的改包名即可)
4. 实现 4 层(从 domain 开始,由内向外)
5. 在 `:core` 的 `MeowoCore.kt` 里订阅 context 启动事件,确保依赖顺序
6. 如需暴露给其他 context,在 `api/` 加接口,context 实现后注册到 `MeowoAPI`
7. 写单测(domain 用纯 JUnit,interfaces 用 MockBukkit)
8. README 写新 context 的职责与命令
````

---

## 关键决策记录

| 决策 | 选择 | 理由 |
|------|------|------|
| 架构哲学 | **借鉴 DDD**,非严格 DDD | 实用主义,MC 插件规模不需要 CQRS/ES |
| 模块化粒度 | **每个 Bounded Context = 一个 Gradle 模块** | 编译期强制依赖方向,domain 层可独立测试 |
| 单 jar vs 多 jar | **单 jar 多 @TabooLib** | 部署简单,TabooLib 子插件机制天然支持 |
| 子插件实现数量 | **3 个**(economy 完整 / menu 简化 / render 简化)+ 1 个仅文档化(chat) | 既展示完整模式,又避免过度膨胀 |
| 跨 context 通信 | **EventBus(异步事件)+ API(同步调用)** | 既解耦又允许必要的同步调用 |
| Domain 层测试 | **纯 JUnit,完全不用 MockBukkit** | 这是 DDD 最大收益,domain 必须无外部依赖 |
| 共享代码组织 | **`:api` 对外、`:shared` 对内** | API 给其他插件用,shared 只给自己用,边界清晰 |
| Version Catalog | **使用 `libs.versions.toml`** | 多模块必备,版本集中管理 |

## 关键收益对比

| 维度 | 单模块版本 | DDD 多模块版本 |
|------|-----------|---------------|
| 单测速度(domain) | 必须启 MockBukkit,~3s | 纯 JUnit,~0.2s ✅ |
| 编译速度 | 全量编译 | 改 domain 只重编 domain |
| 加新功能影响范围 | 容易污染 | 限定在独立模块 |
| 跨功能复用 | 容易直接调,易耦合 | 必须走 API/事件,强制解耦 |
| 团队协作 | 容易冲突 | 模块独立,并行开发 |
| 学习曲线 | 低 | 中(需理解 DDD) |
| 适合规模 | 5 个功能以下 | 5 个功能以上 |

## 后续可扩展点

- [ ] **Velocity 跨服**:contexts 里加 `:meowo-cross-server`,通过 Redis pub/sub 同步
- [ ] **Web 控制台**:新增 `:meowo-web` 模块(Spring Boot + WebSocket)
- [ ] **CI/CD**:GitHub Actions 矩阵测试(多 MC 版本)、自动发布 jar
- [ ] **配置中心**:把 `config.yml` 拆到数据库,运行时热改
- [ ] **插件市场**:每个 context 打包成独立 jar,通过 plugin.yml `depend` 控制
