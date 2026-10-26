# 11 · 多语言与 per-player 切换

> **状态：已实施。** 玩家按**客户端语言**自动收到对应语言的文案；`locale` 降级为
> 「控制台 / 未提供语言的文件」使用的兜底语言。

---

## 1. 语义变化（升级必读）

| | 旧行为 | 新行为 |
|---|---|---|
| 文案份数 | 全服**一份**（`@Volatile var values`） | 启动/reload 时**同时装载 zh_CN + en_US** |
| 语言选择 | 只看 `config.yml` 的 `locale` | `language-mode: player` 时看 `Player.locale()` |
| `locale` 含义 | 全服语言 | **兜底语言**（控制台、未知客户端语言） |
| 菜单标题 | 全服一份 | 按打开菜单的玩家语言渲染 |

**`language-mode`**（`config.yml`）：

| 值 | 行为 |
|---|---|
| `player`（默认） | 玩家按客户端语言；控制台与未知语言回落 `locale` |
| `server` | 全服统一 `locale`，忽略客户端语言 |

非法值（既不是 `player` 也不是 `server`）会让 `/meowo reload` 整体拒绝并保留运行中的模块，
与其他配置项的失败语义一致（见 [02 §2](02-运行时与生命周期.md)）。

---

## 2. TabooLib 的角色

装了 TabooLib 的 `I18n` 模块（`core/build.gradle.kts` 的 `install(Basic, Bukkit, I18n)`）。
在 6.2 里它的常量叫 **`I18n`**（6.1 及更早叫 `module-lang`，`Lang` 常量不存在），
映射到 artifact `io.izzel.taboolib:minecraft-i18n`。

**它不会出现在 jar 里，这是正常的。** TabooLib 6 用「运行时按需下载」，
模块清单写在 jar 的 `META-INF/taboolib/env.properties`：

```properties
module=platform-bukkit,minecraft-i18n,basic-configuration,basic-submit-chain,minecraft-chat,platform-bukkit-impl
```

`minecraft-i18n` 会带上 `minecraft-chat` / `basic-configuration` 两个传递依赖。
**自查方法**是看这个 properties，不是 `unzip -l`：

```bash
unzip -p core/build/libs/Meowo-1.0.0.jar META-INF/taboolib/env.properties | grep module=
```

> ⚠️ 首次启动会从 Maven Central / TabooLib 仓库下载这些模块，**离线部署需要预置
> `.run/server/libraries` 缓存**，见 [07 §5](07-环境与构建.md)。

它提供的能力里有两条我们直接用得上：

| 能力 | 用在哪 |
|---|---|
| 地区变体归一化 `en_gb`/`en_ca`/`en_au`/`en_nz` → `en_US`，`zh_hans_cn` → `zh_CN` | `Messages.CLIENT_LOCALE_ALIASES`（同一张表） |
| `PlayerSelectLocaleEvent` 允许覆盖某玩家的语言 | 目前未用，见下 |

> ⚠️ **不要直接用 `Language.sendLang`。** 它挂在 `ProxyCommandSender` 上，
> 而本项目 `Messages` 收的是 Bukkit `CommandSender`；且 `Language` 是全局单例 `@Awake`，
> 它的 `plugins/{0}/lang/{1}` 释放路径会与本项目自己的 `lang/` 装载逻辑撞车
> （我们的语言包就在 jar 根的 `lang/`，TabooLib 会把同一批文件再解析一遍）。
> 见 [3. 为什么保留自实现](#3-为什么保留自实现)。

---

## 3. 为什么保留自实现

`Messages` 的公开 API **签名全部保持不变**（只新增了带 `sender` 的重载），
因此 60+ 处调用点和既有测试断言都不用重写。对比：

| 方案 | 代价 |
|---|---|
| 全面迁移到 `sendLang` | 要改所有 `messages.send(x, "key")` → `x.sendLang("key")`；<br>并且失去「控制台去色 + 按行拆分」「自己拼的发言者前缀」「点击组件」这三项自定义行为 |
| **保留 `Messages`，per-player 解析（当前）** | 新增 sender 重载 + 一份 locale 归一化表；其余全部不动 |

---

## 4. 实现要点

语言规则全部集中在 **`platform/LocaleResolver.kt`**——一个**没有 Bukkit / plugin 依赖的纯对象**。
`Messages` 只负责装载与投递，解析规则一律委托给它。

```kotlin
object LocaleResolver {
    val SUPPORTED = linkedSetOf("zh_CN", "en_US")     // ← 唯一来源
    private val REGION_ALIASES = mapOf("zh_hk" to "zh_CN", "en_gb" to "en_US", ...)
    fun languageOf(tag: String): String?              // 任意地区码 → 语言码
    fun pick(clientLocale: String?, supported: Set<String> = SUPPORTED): String?
    fun tagOf(locale: Locale): String                 // Locale → "en" / "en_us"
}
```

`Messages` 侧：

```kotlin
@Volatile private var bundles: Map<String, Map<String, String>>   // locale -> key -> text
@Volatile var defaultLocale: String = "zh_CN"; private set
@Volatile var perPlayer: Boolean = true; private set

fun load(locale: String, languageMode: String = "player") {
    require(locale in LocaleResolver.SUPPORTED) { ... }
    defaultLocale = locale
    perPlayer = languageMode == "player"
    bundles = LocaleResolver.SUPPORTED.associateWith { readBundle(it) }   // 一次装全
}

private fun resolve(sender: CommandSender?): Map<String, String> {
    // `locale()` 显式调用：Player 也有 getLocale()，Kotlin 会合成同名 String 属性遮蔽它
    val client = if (perPlayer && sender is Player) LocaleResolver.tagOf(sender.locale()) else null
    val match = LocaleResolver.pick(client)
    return bundles[match] ?: bundles[defaultLocale] ?: bundles.values.first()
}
```

**解析顺序**（`pick`）：

```
null（控制台/非玩家）        → null → defaultLocale
精确地区别名命中已发布语言     → 该 locale
精确 locale 命中             → 该 locale
只比语言码（"en" → "en_US"） → 第一个同语言 bundle
都不命中（fr、ja…）          → null → defaultLocale
```

**`readBundle` 的回落仍然是「文件优先、jar 补缺」**：

```kotlin
config.getString(key) ?: defaults.getString(key)!!
```

所以 `lang/en_US.yml` 存在但只有 24 个键时，其余 60 个键仍会用 jar 内的英文，
不会漏。但那 24 个键会**冻结在旧值**上——见 [08 §2a](08-坑与反模式.md)。

### 为什么必须是纯函数

`REGION_ALIASES` 里写一个我们**不发布**的 locale（例如 `zh_tw`）不会报错，
只会静默落到 `defaultLocale`——看起来像「支持繁体」，其实没有。
所以 `LocaleResolverTest` 里有一条断言专门盯这个：

```kotlin
@Test fun `no region alias points at a locale we do not ship`()
```

同理，`GlobalConfig` 现在直接 `require(locale in LocaleResolver.SUPPORTED)`，
**不再自己写一份 `setOf("zh_CN", "en_US")`**。两处硬编码曾经是必然分叉的隐患
（见 [08 §6](08-坑与反模式.md) 的「静默漏检」模式）。

---

## 5. 测试要点

`platform/src/test/kotlin/com/meowo/platform/LocaleResolverTest.kt`（**纯 JUnit，不启 MockBukkit**）
覆盖解析规则本身：

| 用例 | 断言 |
|---|---|
| `languageOf reduces any regional tag to its language` | `en_us`/`EN-GB` → `en`；空串与 `_us` → null |
| `a bare English locale still reaches the English bundle` | **回归**：`Locale.ENGLISH` → `"en"` → `en_US` |
| `every region variant of a shipped language resolves to that language` | 11 个地区码全覆盖 |
| `an unshipped language returns null so the caller can apply its default` | `fr_fr`/`ja_jp`/null → null |
| `no region alias points at a locale we do not ship` | 防止再次写入死映射 |
| `the shipped set is the single source of truth` | `pick()` 的结果必须都在 `SUPPORTED` 里 |

`core` 的 MockBukkit 集成层再端到端验证 5 条（见上表），两层分工：
**纯函数证明规则正确，集成测试证明接线正确。**

### 夹具必须显式固定玩家语言

`PlayerMock` 的 `locale` 默认是 **`Locale.ENGLISH`**。`language-mode: player` 生效后，
测试服里所有玩家都会拿到英文，**本项目大量中文断言会全部失败**。

```kotlin
private fun addPlayer(name: String): PlayerMock {
    val player = server.addPlayer(name)
    player.setLocale(Locale.SIMPLIFIED_CHINESE)     // 本套件按 zh_CN 书写
    return player
}
private fun PlayerMock.asEnglishClient() { setLocale(Locale.ENGLISH) }
```

> **必须返回 `PlayerMock` 而不是 `Player`。** Bukkit 的 `Player` 接口没有
> `nextMessage()` / `setLocale()`，用 `Player` 声明会让整套断言编译失败。
> 另外 `setLocale(Locale)` 与 `getLocale(): String` 凑不成属性对，
> 所以要**调方法**而不是写 `player.locale = …`。

> **用 `generateSequence { p.nextMessage() }.toList()` 整体断言**，不要只取第一行——
> 多行文案会拆成多条消息，见 [08 §15](08-坑与反模式.md)。

---

## 6. 排障

| 现象 | 原因 | 修法 |
|---|---|---|
| 玩家收到中文但客户端是英文 | `language-mode: server` | 改成 `player` 后 `/meowo reload` |
| 改了 `lang/en_US.yml` 没反应 | 改的是 `.run/server` 里的旧文件 | 确认改的是**服务器**那个文件；`saveResource(..., false)` 不覆盖已有文件 |
| 某些文案是半年前的老翻译 | 数据目录里的语言文件残缺 | 删掉 `plugins/Meowo/lang/<locale>.yml` 让插件重新释放完整版 |
| 只有部分玩家是英文 | 那些玩家的客户端语言是未收录地区码 | 在 `CLIENT_LOCALE_ALIASES` 补映射 |
| reload 报 `language-mode must be player or server` | 拼写错误 | 改成合法值 |

**自查**：

```bash
# 1) 键集合对齐
comm -3 <(grep -oE '^[a-z0-9_-]+:' core/src/main/resources/lang/zh_CN.yml | tr -d ':' | sort) \
        <(grep -oE '^[a-z0-9_-]+:' core/src/main/resources/lang/en_US.yml | tr -d ':' | sort)
# 2) 数据目录里的是否是残缺旧版
wc -l .run/server/plugins/Meowo/lang/*.yml    # 完整版应约 200+ 行，不是 24
# 3) 完整验证
./gradlew clean && ./gradlew test :core:build verifyArchitecture verifyCoverage --no-daemon
```
