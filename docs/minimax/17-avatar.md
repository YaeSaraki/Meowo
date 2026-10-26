# 17 · avatar context：不睡不刷幻翼 + 调整体型

两个**只属于玩家自己**的功能，放在同一个 context 里，因为它们是同一类东西——玩家自己开、自己关、
别人碰不到：

- **幻翼**：取消原版「不睡觉就刷幻翼」这条机制，开 / 关。
- **体型**：把自己的身体变大 / 变小。

两个功能**默认所有人都有权限**（`meowo.phantom` / `meowo.size`，`default: true`）。总菜单里能点，
也能用 `/meowo phantom` 与 `/meowo size`。

---

## 1. 幻翼是「取消原版机制」，不是「召唤一只幻翼」

这一条被误解过两次，所以写清楚。

原版有两条和幻翼有关的路径，**完全不同**：

| 路径 | 是什么 | 本插件做什么 |
| --- | --- | --- |
| 原版「失眠」机制 | 玩家太久没睡 → 服务器从天上刷一只幻翼下来追他 | **这一格取消它**（只对该玩家） |
| 插件自己召唤 | 插件生成一只幻翼，让它跟着玩家飞 | **不做** |

**为什么不做「召唤一只跟着你的幻翼」**：原版幻翼是 `Enemy`——它会从天上锁定玩家然后俯冲。
放着不管，**它会攻击那个叫它来的玩家**。要让它无害，就得把它「关掉脑子」：`setAware(false)`、
`setTarget(null)`、`setShouldBurnInDay(false)`、`setSilent(true)`、`isInvulnerable`、`setGravity(false)`，
然后每 2 tick 手动把它挪到主人身后。一只被拔掉所有自主行为、每 2 tick 被瞬移一次的怪。

更糟的是**和平难度下它根本生不出来**：Paper 的 `CraftRegionAccessor.createEntity` 检查
`world.getDifficulty() == PEACEFUL && !entityType.isAllowedInPeaceful()`，直接抛异常。所以那个版本还得
再加一条「阿蝶降级」——同一个功能在和平服变成另一种生物，菜单还得多说一句话。

**取消机制没有这些问题**：不生成任何实体 ⇒ 没有敌意要解除，没有 AI 要关，没有难度要兼容。

---

## 2. 怎么取消：两个事件，第一个是主力

Paper 26.2 的 `PhantomPreSpawnEvent`（`com.destroystokyo.paper.event.entity`）在**幻翼被创建之前**
触发，并且**直接给出这只幻翼是为谁生成的**：

```java
public class PhantomPreSpawnEvent extends PreCreatureSpawnEvent {
    public Entity getSpawningEntity();   // ← 那只幻翼是冲谁来的
}
```

服务端字节码（`net.minecraft.world.level.levelgen.PhantomSpawner`）里就是：

```
451: new  PhantomPreSpawnEvent
463: invokevirtual ServerPlayer.getBukkitEntity     ← 目标玩家
469: invokespecial PhantomPreSpawnEvent.<init>
476: invokevirtual callEvent
479: ifne 493                 ← 没被取消 → 建实体
482: invokevirtual shouldAbortSpawn
487: ifeq 556                 ← 取消了但不中止 → 换个位置再试
490: goto 562                 ← 取消了并中止 → 跳出这个玩家的循环
562: goto 165                 ← 165 是「遍历玩家」的外层循环
```

所以本插件：

```kotlin
event.isCancelled = true          // 这次不生成
event.setShouldAbortSpawn(true)   // 并且别再为这个玩家重试
```

**只影响那一个玩家**：外层循环还在继续，别人照常有幻翼。

### 备份路径：`CreatureSpawnEvent`

Paper 自己在 `PreCreatureSpawnEvent` 的 javadoc 里说「You should still listen to
`CreatureSpawnEvent` as a backup」——它不是每种情况都触发。所以还有第二条路：幻翼**已经存在**时
才触发的 `CreatureSpawnEvent`。幻翼自己记得它是为谁生成的（`Phantom.getSpawningEntity()`），所以主人
照样认得出来，取消即可。

两条路都在 `AvatarContext` 里，注释写明了各自的理由。

### 监听器为什么写在父类上

`PhantomPreSpawnEvent` **自己没有** `getHandlerList()`，它和 `PreSpawnerSpawnEvent` 共用父类
`PreCreatureSpawnEvent` 的那一个。Bukkit 的 `getRegistrationClass` 会向上找，于是监听器注册在**父类**
的 handler list 上。

如果监听方法写成 `fun onPhantom(event: PhantomPreSpawnEvent)`，Paper 的方法句柄在派发时会把事件强转成
`PhantomPreSpawnEvent`——遇到 `PreSpawnerSpawnEvent` 就 `ClassCastException`，刷怪笼每次刷怪都报一次错。

**写成父类再 `if (event !is PhantomPreSpawnEvent) return`**，两个事件都收得到，只有幻翼那个会被处理。

---

## 3. 体型是原版的 `scale` 属性，不是假玩家

`Attribute.SCALE`（Paper 26.2 里是 `minecraft:scale`）是原版给实体缩放用的属性。改它，玩家**真的**
变大变小：碰撞箱、攻击距离、别人眼里的你，全都跟着变。

用「假玩家」实现过一下，那等于在服务器里多走一个替身，交互距离和碰撞全都对不上。**用属性就没有
这些问题，因为用的就是原版那套。**

属性读不到时（属性被别的插件换掉、或极端的兼容层），插件**说一声**（`avatar-size-unavailable`），
而不是假装改成功了——菜单上写着 1.25 倍而人还是原样大，那比功能坏掉更糟。

### 体型是「格子」，不是一个自由数

`ScaleLimits` 定义了范围、步长与默认值：

- 最小 0.5 倍、最大 3 倍、每步 0.25 倍、默认 1.0 倍；
- **任何时候体型都落在格子上**。按「变大」得到的值，一定能再按一次「变小」回到原处。

如果允许 1.3 这种不在格子上的值，按一次「变大」会变成 1.55——菜单就再也对不回去了。所以
`clamp`（只管范围）与 `snap`（管格子）是两件事，`/size 1.3` 会落到 1.25。

数字的**规则**在 domain（能单测），**数值**在 config（服主能改）。

---

## 4. 状态活多久

| 事件 | 幻翼（取消机制） | 体型 |
| --- | --- | --- |
| 退出游戏 | 规则还在（只是没人可刷） | 属性跟着玩家对象一起没了 |
| 再登进来 | 规则还在 | **重新套用** |
| `/meowo reload` | 清掉 | 复位成 1.0 倍 |
| 服务重启 | 没有 | 回到 1.0 倍 |

**重载时必须把体型复位。** `scale` 是**玩家身上的**标记：重载后还留着它，等于有个人一直是你三倍高，
而能改回去的功能已经不在了。**收走一个功能之前，先把它留在别人身上的东西收回来。**

关掉 context 时也一样：属性复位、状态丢弃。

---

## 5. 跨 context：菜单怎么知道玩家开了幻翼

菜单在 `menu` context，幻翼在 `avatar` context，两个 context 之间不许直接 import
（`verifyArchitecture` 会拦）。所以契约放在 `api` 里：

```kotlin
interface AvatarAPI {
    fun phantomsBlocked(player: UUID): Boolean
    fun scaleOf(player: UUID): Double
}
```

`avatar` context 把它注册成 Bukkit service，菜单用类型去取。

菜单那两格（`30` 幻翼 / `32` 体型）的内容是**随玩家变**的，所以是 `overrides`：

- **幻翼格**的名字跟着开关走（`menu-item-phantom-on` / `-off`）；
- **体型格**的 lore 带着当前倍数。

**名字与静态 lore 传的是语言键，不是成品文字。** 键走的是 UI 模块那条路，只有那条路认 `§` 颜色码；
成品文字走的是「字面量」那条路，那条路认 `&`。把 `§` 编好的文字直接塞进去，玩家会看到
`§7当前体型` 这种带代码的字。

**唯一带数字的那一行**（体型）因此是**纯文字**、没有颜色码——它必须由菜单填好，而填好的东西只能
走字面量那条路。

**avatar context 没开时，那两格是「藏起来」而不是「画成灰的」。** 点了却只得到一句「未知指令」的
按钮，比没有这个按钮更糟：它会让玩家开始不信整个菜单。

---

## 6. 菜单点完要重画

菜单格子画的是**当时**的状态。点一下「幻翼」，开关变了，格子还写着旧的——玩家点了按钮，界面却像
没反应一样。

两个 context 不能互相 import，所以变化以**通知**的形式传过去（`api/event/Events.kt`）：

```kotlin
data class AvatarChanged(val player: UUID)
```

`avatar` context 在开关或体型变动时 `publish`，`menu` context 订阅，收到就重画自己那两格：

```kotlin
host.events.subscribe(AvatarChanged::class.java) { event ->
    val player = Bukkit.getPlayer(event.player) ?: return@subscribe
    ui()?.refresh(event.player, SCREEN_MENU, avatarCells(player))
}
```

`UIAPI.refresh` 是**原地重绘**（`session.inventory.setItem`），不换窗口；而 `UIService.refresh` 先看
玩家是不是正看着这一屏，不是就**什么都不做**——玩家走到别的界面去了，就不该被拽回来。

事件只是「通知」：订阅者漏掉了它，丢的是一次重画，不是一个事实。新状态永远从 `AvatarAPI` 读。

---

## 7. 测试

- `ScaleLimitsTest`（纯）：步长、夹取、落格、格式、越界。
- `AvatarContextTest`（MockBukkit）：开关来回切、`on`/`off`/乱输三种说法、一个玩家的开关不影响
  别人、`PhantomPreSpawnEvent` 对被豁免者**取消**且 `shouldAbortSpawn` 为真、对没开开关的人**不**取消、
  备份路径 `CreatureSpawnEvent` 也能取消、没有主人的幻翼不归我们管、体型步进与落格、关闭 context 复位。
- `AvatarIntegrationTest`（MockBukkit + 整套运行时）：命令、菜单显示的状态、**点格子之后菜单会重画**、
  上下文关掉时那两格藏起来。

**MockBukkit 没有 `scale` 属性**（它比这个属性早），所以 `AvatarContext` 的第三个构造参数就是那个
属性本身：测试塞进去一个假属性，测的才是功能而不是 mock。生产环境的默认值仍然是
`player.getAttribute(Attribute.SCALE)`。

### 真机实测（Paper 26.2）

MockBukkit 说不了谎的部分是**服务器到底会不会刷幻翼**，所以这一条必须在真机上验：

把 `paper-world-defaults.yml` 的失眠阈值临时调低（`player-insomnia-start-ticks: 20`、
`phantoms-spawn-attempt-*: 5/10`），让原版在一分钟里试好几次，再让无头客户端在**午夜**保持清醒：

| 阶段 | 客户端发的命令 | 服务器上有没有幻翼 |
| --- | --- | --- |
| 开着豁免 | `/execute as @e[type=phantom] run say MARK` | **一条都没有**（60 秒里试了 6～12 次） |
| 关掉豁免 | 同上 | **立刻就有**（`say` 打印了 15 条，一条幻翼一条） |

同一段时间、同一台服务器、同一个玩家，**唯一的变量是这个开关**。

菜单重画也是同一套办法验的：客户端打开总菜单（`packet_open_window`），然后在聊天里执行
`/meowo size up` 与 `/meowo phantom`，看服务器有没有再发一包 `set_slot`：

```
SLOT 30  "Phantom · will come"      ← 刚打开时
SLOT 32  "Your size: 1"             ← 刚打开时
SLOT 32  "Your size: 1.25"          ← /meowo size up 之后，原地重画
SLOT 30  "✦ Phantom · will not come" ← /meowo phantom 之后，原地重画
```

**窗口没有重开**（只有一次 `open_window`），格子是**原地换掉的**——这正是「点完就更新」该有的样子。

