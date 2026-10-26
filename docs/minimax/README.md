# Meowo 开发知识库

本目录是 Meowo 插件的**开发准本**：记录从源码中读出来的、不能靠猜的约束与手法，用于让后续改动不破坏既有语义。

与根 [README.md](../../README.md) 的分工：

| 文档 | 回答什么 |
|---|---|
| 根 `README.md` | 产品行为：命令、配置、玩家可见语义 |
| 各 `contexts/*/README.md` | 单个 context 的职责与业务规则 |
| `docs/versions.md` / `docs/acceptance.md` | 版本锁定与历史验收快照 |
| **`docs/minimax/`（本目录）** | **怎么改才不破坏东西**：红线、生命周期、线程模型、数据不变量、改造步骤、测试手法、环境坑 |

本目录不重复产品文档，只补三类内容：**源码隐含的不变量**、**精确到文件的改造步骤**、**反模式与坑**。

---

## 按意图跳转

| 我要做的事 | 先读 |
|---|---|
| 新增一个 context | [05-改造手册](05-改造手册.md) §1，然后 [01-架构红线](01-架构红线.md) |
| 给已有 context 加命令 / 子动作 | [05-改造手册](05-改造手册.md) §2–§3 |
| 加一条用户可见文案 | [05-改造手册](05-改造手册.md) §4（注意 §4 的「双语言 + 错误码」陷阱） |
| 加一个 `EconomyError` 错误码 | [05-改造手册](05-改造手册.md) §5 —— **两个语言文件都必须加键** |
| 改数据库表结构 | [04-经济与数据模型](04-经济与数据模型.md) + [05-改造手册](05-改造手册.md) §6 |
| 动并发 / 线程 / 异步回调 | [03-线程与并发](03-线程与并发.md) |
| 动 reload、启停、关闭顺序 | [02-运行时与生命周期](02-运行时与生命周期.md) |
| 动跨 context 通信（API / 事件） | [01-架构红线](01-架构红线.md) §2、§4、§5 |
| 写测试 / 覆盖率掉了 | [06-测试与验证](06-测试与验证.md) |
| 怀疑某条测试在**给假信心** | [19-测试审计](19-测试审计.md)（四类垃圾测试的判定标准与实例） |
| 构建失败、报奇怪的错 | [08-坑与反模式](08-坑与反模式.md) §1（先看这个）、[07-环境与构建](07-环境与构建.md) |
| 启动本地服 / 客户端联调 | [07-环境与构建](07-环境与构建.md) §4 |
| 想知道某条消息归哪只猫 / 改发言者名字 | [05-改造手册](05-改造手册.md)「发言者」+ [09](09-模拟玩家发言可行性.md) |
| 玩家反馈语言不对 / 想按客户端语言切换 | [11-多语言与 per-player 切换](11-多语言与per-player切换.md) |
| 改回城 / 安全落点相关逻辑 | [10-spawn 调研与实施](10-spawn调研.md) §4 |
| 写骑乘 / 坐下相关代码 | [12-坐下与骑乘](12-sit与ride.md) §1（**26.2 没有 startRiding**）、§2.5（五条拒绝规则） |
| 测试里写「这里必须是空气」 | [08-坑与反模式](08-坑与反模式.md) §20（MockBukkit 默认是实心方块） |
| 加子命令 / 想知道哪些能在 / 下用 | [12-坐下与骑乘](12-sit与ride.md) §6 |
| 判断物品能否穿戴 / 写 /hat 类功能 | [13-帽子](13-hat.md) §2（`ArmorMaterial`/`ToolMaterial` 已不存在） |
| 头顶名牌 / AFK 标记没反应 | [14-AFK 与状态圆点](14-afk与状态显示.md) §1（`setCustomName` 对玩家无效） |
| help 里出现了自己没有的指令 | [05-改造手册](05-改造手册.md)「help 必须按权限过滤」 |
| 挂机标题无缘无故弹出 / 聊天不算在线 | [14-AFK 与状态圆点](14-afk与状态显示.md)「所有动作都要重置空闲计时」 |
| 标记显示但颜色不变 | [14-AFK 与状态圆点](14-afk与状态显示.md) §2b（`fromHexString` 只认 `#`） |
| 接风纪委员 AI / 加聊天过滤或表情 | [15-chat](15-chat.md) §8（三个接缝） |
| 玩家被推挤就退出 AFK | [14-AFK 与状态圆点](14-afk与状态显示.md)（`setCollidable(false)`） |
| 写物品栏点击 / 潜行右键 / 容器 GUI | [16-潜影盒](16-shadowbox.md) §3.2（**不要在点击里清空窗口，物品会消失**） |
| 写「查看 / 改动别人背包」类功能 | [18-invsee](18-invsee.md) §1（**窗口只是镜像，每次搬运必须立刻写进真实背包**） |

---

## 当前基线（2026-10-25 实测）

在干净工作区执行 `./gradlew check build --no-daemon`（两道门禁 `verifyArchitecture` 与
`verifyCoverage` 已经挂在 `check` 里，见 [06 §7](06-测试与验证.md)）：

| 项目 | 实测值 |
|---|---|
| 测试 | **1430 个**，0 failure / 0 error，**0 skipped**（128 个测试类） |
| 总体行覆盖率 | **83.46%**（门禁 ≥ 70%） |
| economy domain 行覆盖率 | **94.74%**（门禁 ≥ 90%） |
| 语言键 | 中英各 **626** 键（顶层键，不含 `help-header` 块标量里的命令行），键名与占位符完全对齐 |
| 构建 | `BUILD SUCCESSFUL` |
| 产物 | `core/build/libs/Meowo-1.0.0.jar`（单 jar，含全部 context，约 3.2 MB） |

分模块（同一次运行）：`agents` 467 · `core` 295 · `chat` 168 · **`platform` 122** ·
`pomodoro` 78 · `ui` 52 · `nametag` 40 · **`invsee` 37** · `mine` 35 · `economy` 29+13 ·
**`avatar` 26** · `teleport` 23 · `sit` 11 · `shop` 10 · `home` 9 · `shared` 8 · `render` 2。

> **`avatar`（不睡不刷幻翼 + 调整体型）是 2026-10-19 新接的 context**，26 个测试，见
> [17-avatar](17-avatar.md)。它顶掉了被删掉的 `fly`（`/fly` 与 `meowo.fly.use` 一并移除，
> 见 [06 §7 的验证命令](06-测试与验证.md)）。
>
> **2026-10-24 语义改写**：「幻翼」原本是「召唤一只跟着你的幻翼」，现在是**取消原版「不睡觉就刷
> 幻翼」的机制**（只对该玩家）。跟随幻翼、阿蝶降级、`avatar.companion.*` 配置与相关语言键全部删除，
> `config-version` 3 → 4。实现走 Paper 的 `PhantomPreSpawnEvent`（它直接给出这只幻翼是冲谁来的），
> 备份路径是 `CreatureSpawnEvent` + `Phantom.getSpawningEntity()`。

> **`ProactiveLaneTest`「the greeting opens again once the cooldown has passed」曾被记为偶发失败**
> （2026-10-23 之前的记录：连跑三次里挂过两次，原因未定位）。**2026-10-25 复核：单独连跑 5 次全绿，
> 全量构建亦绿**；冷却戳改由 `Cooldowns` 统一管理之后，这条用例不再有共享状态的写法。原来的成因
> 没有被定位，所以它仍按「可疑用例」看待，只是不再被当作已知的偶发失败记着。

> **`docs/acceptance.md` 已过期。** 它记录的 28 tests / 83.93% / domain 100% 是加入 `teleport` context 之前的历史快照。**以本表的 1425 tests / 83.25% / 94.74% 为准**；改动基线时请同步更新这里和 `docs/acceptance.md`。
>
> **2026-10-08 新增的 `meowo-invsee`**（`/invsee`）先以「145 行、零测试」接进 `settings.gradle.kts` /
> `build.gradle.kts`，把总体覆盖率从 84.78% 拉到 82.88%；补齐 37 个测试（`InvseeLayoutTest` 7 /
> `InvseeClickTest` 9 / `InvseeContextTest` 21）后回到 **84.76%**。
> 这条记在这里是因为"新增 context 会被计入总体分母"这件事只有从覆盖率上才看得出来。

### 受限环境下怎么跑构建

Gradle 默认写 `~/.gradle`，**Kotlin 编译守护进程默认写
`~/Library/Application Support/kotlin/daemon`**。在只允许写工作区的沙箱里两者都会被拒——
后者会让编译退化成"进程内编译"，撞上 `gradle.properties` 的 2g 堆，**构建中途被 OOM 杀掉**，
留下一个**空的模块 jar**，随后测试报出与真实原因毫无关系的 `NoClassDefFoundError: com/meowo/invsee/InvseeSettings`。

```bash
cp -Rc ~/.gradle/caches/modules-2/. .gradle-home/caches/modules-2/
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test :core:build verifyArchitecture verifyCoverage \
  --offline --no-daemon --no-parallel \
  -Dorg.gradle.jvmargs="-Xmx4g -Dfile.encoding=UTF-8" \
  -Pkotlin.compiler.execution.strategy=in-process
```

`.gradle-home/` 是构建缓存，不是产物，不该提交。

> **看到测试报"某个类找不到"，先看那个模块的 jar 是不是空的**（`unzip -l …/build/libs/*.jar`）。
> 空 jar = 上一次构建被杀，不是代码问题；`./gradlew --stop && ./gradlew clean` 后重来。
> 这与 [08 §1](08-坑与反模式.md) 的 iCloud 冲突副本是**两个不同**的坑，症状都是 `NoClassDefFoundError`。

> **MockBukkit 还会在 `java.io.tmpdir` 下建插件类加载器目录**，所以系统临时目录不可写时，
> 所有用 `MockBukkit.load(...)` 的用例（`meowo-invsee`、`meowo-avatar` …）会一起挂在
> `PluginLoadException: Failed to instantiate plugin`，而**根因是文件权限，不是代码**。
> 测试进程不继承 daemon 的 `-Djava.io.tmpdir`，要改得用 init 脚本：

```bash
cat > .tmp/tmpinit.gradle <<'EOF'
allprojects { tasks.withType(Test).configureEach {
    systemProperty('java.io.tmpdir', new File(rootDir, '.tmp').absolutePath) } }
EOF
./gradlew --init-script .tmp/tmpinit.gradle test --offline --no-daemon --no-parallel ...
```

> **还有个更迷惑的**：Gradle 报
> `Could not write XML test results for … to file …/test-results/test/TEST-…xml`，
> 于是 `:core:test FAILED`、整场构建红——**但那些 XML 其实都写出来了，测试也全绿**。
> 这是 iCloud 在构建过程中搬运/加锁 `build/test-results/`。判断方法：直接读 XML 统计，
> 失败数若为 0，就是环境而非代码；重跑一次同样的命令通常就过。

按测试类分布（共 122 个类，下表是最大的 16 个；`meowo-agents` 一个 context 占了 36 个类、444 个测试）：

```
 131  com.meowo.core.MeowoRuntimeTest
  47  com.meowo.core.PomodoroIntegrationTest
  46  com.meowo.agents.domain.PromptBuilderTest
  26  com.meowo.platform.ChatLineTest
  25  com.meowo.chat.domain.EmojiParserTest
  24  com.meowo.agents.application.AgentRuntimeTest
  24  com.meowo.agents.application.ImpressionServiceTest
  24  com.meowo.core.HomeIntegrationTest
  21  com.meowo.invsee.InvseeContextTest
  20  com.meowo.chat.domain.ThreadLogTest
  20  com.meowo.agents.domain.GuardTest
  20  com.meowo.agents.infrastructure.PlayerProfileStoreTest
  19  com.meowo.pomodoro.application.SessionServiceTest
  18  com.meowo.ui.UILoaderTest
  18  com.meowo.chat.infrastructure.ChatLogStoreTest
  18  com.meowo.agents.application.CatToolsTest
```

另有 `ModerationFeedbackTest`（被拦截必须出声 / 禁言在公屏与私信都生效）与
`PresenceBoardTest` + `SqliteSessionJournalTest`（猫的记忆跨重启、公屏才上看板）。
猫的**聊天范围**由 `CatScopeTest`（12）盯着：十二只猫各管各的一段、世界边界十二只逐字相同、
`scope` 不许出现现实世界的东西也不许带数字。猫的**身份与性格**由 `CatPersonalityTest`（11）盯着：
人设以英文名开头、十二个 MBTI 码两两不同、回复上限 320 字且不再全员「一两句」——
见 [15-chat §8b](15-chat.md)。
聊天日志的保留期从 7 天改成 **15 天**，而且**按玩家最后一次登录算、不按消息日期**：
天天上线的人三个月前的话留着，一个月没上线的人说过什么全删 —— 收件人那份也一样删，
因为「他给我的那份」也是他的数据。升级时用每行自己的时间回填一次登录记录，不会抹掉老库。
猫的记忆还有一条约束：**记得 ≠ 要说** —— 记忆照常喂进提示词，但窗口正下方写着
「除非对方自己先提起，不要主动翻出来」，system 里另有一条通用的 `记得 ≠ 提得起`
（窗口是空的时候，猫手上还有印象和特征）。

**agents 模块这一轮找到五个「能力存在、上线代码不存在」的洞**，全部已修：
`daily-cap` 从不按天重置（实际是「服务器启动以来的总量」）· 预算按**问题**扣而
工具循环最多 4 次 HTTP（实际 4 倍）· `disclose` 硬编码成全拒（连问自己都拒，
`economy.treasury` 因此对所有人失效）· `lookupPlayer` 与 `askerIsAdmin` 从没被赋过值 ·
**三层印象只有两层是活的**（`[your impression]` 每次都渲染成占位文本，`cat_impression` 零行）。
接通名字解析的同时，两个风纪工具被**补上了 disclose 门** —— 它们此前从没问过，
安全只是因为解析器坏着，那是偶然不是门。
这一轮找到的「能力看起来存在、实际没接线」的洞，纯领域测试测不到，只有真人视角才看得见。
111  com.meowo.core.MeowoRuntimeTest              MockBukkit 集成（命令/权限/菜单/语言/reload/tpa/补助/消息/发言者/回城/坐下/骑乘/帽子/潜影盒）
 21  com.meowo.platform.ChatLineTest              纯 JUnit（行格式 / 单空格 / 徽记在前 / 无 <> / 公开聊天行可私聊 / 回复按读者 / 行末回复按钮 / #code）
 18  com.meowo.chat.domain.EmojiParserTest        纯 JUnit（反引号触发 / 拼音别名 / Tab 补全片段判定）
 15  com.meowo.platform.ShadowBoxTriggerTest      纯 JUnit（两个键位 / 副手 / 玩家槽 / 拖拽保护）
 14  com.meowo.chat.domain.ModerationGateTest     纯 JUnit（AI 插口的判定顺序）
 14  com.meowo.economy.SettlementRepositoryTest   真实 SQLite（转账/托管/每日/回滚/并发）
 12  com.meowo.core.HomeIntegrationTest           MockBukkit 集成（回城 context）
 12  com.meowo.chat.domain.ReferenceTest          纯 JUnit（#code + ❝ 解析 / 预填命令往返 / #ff0000 不是码）
 11  com.meowo.chat.domain.ChatPipelineTest       纯 JUnit（管线顺序 / 归一化）
 11  com.meowo.chat.domain.MailboxTest            纯 JUnit（有界 / 限流 / 已读已回）
 11  com.meowo.chat.domain.ThreadLogTest          纯 JUnit（会话串 / 环形挤掉后仍可查 / 环切断）
 11  com.meowo.chat.domain.ThreadTreeTest         纯 JUnit（回复树嵌套 / 父缺失提升为根 / 环切断）
 11  com.meowo.sit.SeatPlacementTest              纯 JUnit（26.2 无 startRiding，改 addPassenger）
 10  com.meowo.mine.MineVeinTest                  纯 JUnit
 10  com.meowo.mine.infrastructure.MineLedgerTest 真实 SQLite
 10  com.meowo.nametag.AfkGuardTest               纯 JUnit（AFK 防护）
 10  com.meowo.nametag.NametagServiceTest         纯 JUnit（team suffix 标记）
  9  com.meowo.chat.domain.DirectoryTest          纯 JUnit（名称歧义：系统优先）
  9  com.meowo.chat.domain.IntentRouterTest        纯 JUnit（应答台意图）
  8  com.meowo.chat.domain.MessageCodeTest         纯 JUnit（4 位 base36 / 不撞号）
  7  com.meowo.platform.DurationsTest             纯 JUnit（时长不是 ISO-8601 / 零单位省略 / 不为负）
```

> 源码扫描型门禁各 1 个用例：`LanguageKeyExistsTest`（引用的语言键必须存在）、
> `NoChatTypeDecorationTest`（禁止再出现 `ChatType`，见 [09 §2](09-模拟玩家发言可行性.md)）。

> **统计测试数时必须排除 iCloud 冲突副本**（`TEST-xxx 3.xml` 这类文件），否则会把旧快照重复计数。
> 上面的脚本已用 `re.search(r' \d+\.xml$', p)` 过滤，见 [06 §6](06-测试与验证.md)。

---

## 文档地图

| 文件 | 内容 |
|---|---|
| [01-架构红线](01-架构红线.md) | 9 条不可违反的约束：谁强制、为什么、怎么自查 |
| [02-运行时与生命周期](02-运行时与生命周期.md) | 启动/重载/关闭三张顺序表，context 资源登记契约 |
| [03-线程与并发](03-线程与并发.md) | 三个执行域的允许矩阵、EventBus 语义、worker 排空 |
| [04-经济与数据模型](04-经济与数据模型.md) | Money、8 张表、传送托管状态机、容量预留、每日补助 |
| [05-改造手册](05-改造手册.md) | 11 个精确到文件与行的改造配方 |
| [06-测试与验证](06-测试与验证.md) | 测试分层、MockBukkit 夹具、覆盖率门禁与报告位置 |
| [07-环境与构建](07-环境与构建.md) | JDK 矩阵、构建命令、脚本、真服验收清单 |
| [08-坑与反模式](08-坑与反模式.md) | 会真实咬人的 22 个坑（§6b 归属降级 / §6c 语言匹配 / §6d API 漂移 / §6e getSlot vs getRawSlot / §6f 刷物品 / §6g setCustomName 对玩家无效 / §6h 颜色十六进制前缀） |
| [09-模拟玩家发言（方案 B）](09-模拟玩家发言可行性.md) | 已实施：全部聊天消息以 `<XxxCat>` 身份发言，及由此带来的身份限制 |
| [10-spawn 调研与实施](10-spawn调研.md) | `/spawn` + `/setspawn`：主流实现对比、API 可用性、安全落点扫描的坑位清单 |
| [11-多语言与 per-player 切换](11-多语言与per-player切换.md) | 按客户端语言切换、`language-mode` 语义、TabooLib I18n 的角色、排障表 |
| [12-坐下与骑乘](12-sit与ride.md) | `/sit` `/ride`：26.2 骑乘 API 变更、方案来源、权限、命令下沉面 |
| [13-帽子](13-hat.md) | `/hat`：交换语义、「除工具外都能戴」、26.2 判断可穿戴的正确 API、库存点击为何没测试 |
| [14-AFK 与状态圆点](14-afk与状态显示.md) | `/afk` 与名牌圆点：为什么 `setCustomName` 对玩家无效、改用计分板队伍 |
| [15-chat](15-chat.md) | 聊天解析管线、审核闸门、发言者一等公民化、风纪委员 AI 接缝 |
| [16-潜影盒](16-shadowbox.md) | `/shadowbox`：物品即容器、两个键位、点击事件跳 tick、holder 识别、rawSlot 陷阱 |
| [17-avatar](17-avatar.md) | `avatar` context：**取消原版「不睡觉就刷幻翼」+ 调整体型**（取消走 Paper 的 `PhantomPreSpawnEvent`、它直接给出这只幻翼是冲谁来的；体型是原版 `scale` 属性且落在格子上；菜单靠 `api` 里的契约读状态。**2026-10-24 之前的语义是「召唤一只跟着你的幻翼」，已作废**） |
| [18-invsee](18-invsee.md) | `/invsee`：**窗口是镜像、每次点击自己搬运并取消事件**（否则每点一次复制一次）、槽位表单一来源、拖拽与不支持的点击一律拒绝、权限每次重校验 |

> **玩家消息**（`§` 颜色码、`\n` 换行、控制台去色、占位符约定）见
> [05 §4](05-改造手册.md#4-新增一条文案) 与 `platform/.../Messages.kt` 的类注释。

---

## 维护约定

- **红线只增不减。** 01/02/03/04 记录的是被 `verifyArchitecture`、覆盖率门禁或测试固定的语义。改动这些语义时，必须同时改门禁或测试，不能只改文档。
- **配方写到能照抄。** 05 里每条配方都给出文件路径与插入位置，不写「视情况调整」这种话。
- **坑要写触发条件。** 08 每条都写「什么现象 → 什么根因 → 怎么修」，方便按症状检索。
- **基线要实测。** 上表数字来自实际执行命令，不是抄来的。改完记得重跑并更新。
