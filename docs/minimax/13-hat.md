# 13 · 帽子（`/hat`）

> **状态：已实施。** 全新 context `hat`，发言者 `HatCat`。

---

## 1. 行为：EssentialsX 的经典语义 + 「除工具外都能戴」

`/hat` 是 EssentialsX 最老的功能之一，语义已经被玩家熟悉了几十年：

| 手上 | 头上 | 结果 |
|---|---|---|
| 可戴物（除工具外） | 任意 | **交换**：手上→头，头上→手 |
| 空 | 有东西 | 摘下到手上 |
| **工具**（镐/斧/铲/锄/剑/弓/…） | 任意 | 拒绝，**什么都不动** |
| 空 | 空 | 提示「没什么可做的」 |

**「除工具外都能戴」**才是服主真正想要的：羊毛、灯笼、唱片、花、南瓜、
乃至把胸甲戴在头上都可以。判定规则在 `platform/HatPolicy.kt`（纯函数）。

```kotlin
fun isTool(name: String, maxDurability: Int, equippable: Boolean): Boolean =
    maxDurability > 0 && !equippable && name !in NOT_TOOLS
```

只看「有耐久 + 不能装备」：护甲和鞘翅也有耐久，但它们能装备，所以被排除。
`SHIELD` 单独放行（它是被手持而非装备，经典头饰）。

配置（`config.yml`）：

```yaml
hat:
  allow-tools: false     # true = 工具也允许戴
  deny: []              # 额外禁止的材质名，例如 BEDROCK
  inventory-slot: true   # 物品栏里点到头部槽是否生效
```

**必须是交换，不是丢弃。** 早年的 `/hat` 实现会把旧头盔丢在地上，
EssentialsX 改成了交换。这个语义要保留——它不丢东西，也不碰经济，
因为每一步都是在玩家本来就拥有的两个槽位之间搬。

---

## 2. ⚠️ 怎么判断「能不能戴」

**不要用物品名判断**（`name.endsWith("_HELMET")`）——
那样模组加的自定义头盔全部失效，而且原版以后加新材料也会漏。

### 26.2 的正确判据

```kotlin
if (held.type.getEquipmentSlot() != EquipmentSlot.HEAD) { /* 拒绝 */ }
```

### ❌ 已不存在的 API

```kotlin
// org.bukkit.material.ArmorMaterial / ToolMaterial / SwordMaterial
// 在 Paper 26.2 **三个都不存在**了，material 包只剩方块相关的旧类
$ unzip -l paper-api-26.2.jar | grep 'material/.*Armor'      # 无输出
```

`Material.getHelmetMaterial()` 也没有。`Material` 上与装备相关的只剩
`getEquipmentSlot()`（`@Nullable`，不可装备的物品返回 `null`）和
`getItemAttributes(EquipmentSlot)`。另外 **`InventoryType` 也搬家了**——
现在是 `org.bukkit.event.inventory.InventoryType`。

这是 [08 §6d](08-坑与反模式.md) 说的「跨版本 API 漂移」的又一例：
网上（包括 LLM 生成的）判头盔代码几乎全是 `ArmorMaterial`。

**测试专门覆盖了这一点**——`HatPolicyTest` 用真实原版数值逐个断言：
12 种工具被拒、7 种护甲可戴、12 种普通物品可戴、盾牌可戴、AIR 不可戴。

---

## 3. 纯槽位操作

`/hat` 命令本身**没有异步、没有状态**（库存点击部分见 §4）：

```kotlin
val inventory = sender.inventory
val held = inventory.itemInMainHand
val worn = inventory.helmet
// …判断…
inventory.setHelmet(held)
inventory.setItemInMainHand(worn)
```

全是主线程的同步槽位读写，因此：

- `close()` 只需撤销 route，**不需要恢复任何东西**（对比 `/sit` 要清盔甲架）
- 停用 context 不会让玩家卡在「头盔凭空消失」的状态
- 不需要 `active` 之外的任何状态

`active` 闸门仍然保留，用于和其余 context 保持一致的契约（见 [02 §4](02-运行时与生命周期.md)）。
`close()` 额外解绑监听器，因为现在它是个 `Listener`。

**不丢物品**：`/hat` 是交换；库存点击时换下来的旧头饰先进背包，满了才掉脚下。

---

## 4. 物品栏头部槽：为什么必须自己实现

**客户端本身禁止把非护甲物品放进护甲槽**，所以原生拖拽根本不会产生一次
合法的交换，服务端观察不到。唯一可行的办法是拦截 `InventoryClickEvent`、
自己完成交换、再取消事件：

```kotlin
// 点击
if (event.view.type != InventoryType.CRAFTING) return   // 只处理自己的背包
if (event.rawSlot != HEAD_SLOT) return                   // 5 = 视图内的头部槽
val incoming = event.cursor ?: return
if (!wearable(incoming.type)) return
val outgoing = wornHelmet(player)
event.isCancelled = true
val split = HatPolicy.splitForHead(incoming.amount)   // ⚠️ 余量必须回光标，见下
event.setCursor(if (split.cursor > 0) incoming.clone().apply { amount = split.cursor } else null)
setHelmet(player, incoming.clone().apply { amount = split.head })
if (outgoing != null) give(player, outgoing)
player.updateInventory()

// 拖拽：同样要自己完成——只取消是错的，那会整段作废、头部还是空的
if (!event.rawSlots.contains(HEAD_SLOT)) return          // 不涉及头部就交给原版
val dragged = event.oldCursor.takeIf { !it.type.isAir } ?: return
if (!wearable(dragged.type)) return                      // 原版自己会跳过头部槽
event.isCancelled = true
val split = HatPolicy.splitForHead(dragged.amount)   // ⚠️ 同样
event.setCursor(if (split.cursor > 0) dragged.clone().apply { amount = split.cursor } else null)
setHelmet(player, dragged.clone().apply { amount = split.head })
if (outgoing != null) give(player, outgoing)
player.updateInventory()
```

### ⚠️ 取消事件后光标必须被消耗，否则刷物品

**取消 `InventoryClickEvent` / `InventoryDragEvent` 只会回滚槽位，光标上的物品原封不动。**
若把余量塞进背包而不改光标，玩家就同时拿到「背包的 N-1 + 头上的 1 + 光标上的 N」= **翻倍**。

**本项目真实发生过一次**：上一轮为了让代码「不用废弃 API」，把
`event.setCursor(...)` 换成 `inventory.addItem(...)`——那正是刷物品的做法。

```kotlin
// ✅ 余量回光标
event.setCursor(if (split.cursor > 0) incoming.clone().apply { amount = split.cursor } else null)
// ❌ 余量进背包：光标上的原件还在 → 复制
give(player, incoming.clone().apply { amount = incoming.amount - 1 })
```

`setCursor` 确实标着 `@Deprecated`（说明是「会在物品栏计算之前改动玩家手上的物品」），
**但在「取消事件」这个场景里它恰恰是唯一正确的手段**。
判断 API 该不该用看**语义**，不看废弃标注。

分配规则抽成了纯函数并被测死：

```kotlin
HatPolicy.splitForHead(amount)   // HeadSplit(head + cursor == amount)
```

`HatPolicyTest` 对 0..64 全量断言守恒，**任何人再改成分配给背包，这条立刻红**。

### 三个真实踩过的坑

| 易错 | 正确 | 后果 |
|---|---|---|
| 判头部用 `event.slot` | **`event.rawSlot`** | 点头部永远不生效（见下） |
| 拖拽只 `isCancelled = true` | 取消 + 自己放置 | 拖拽完全没用 |
| 取消事件后余量进背包 | 余量**回光标** | 刷物品（见上） |

#### `getSlot` vs `getRawSlot`（本项目真实 bug）

官方 javadoc 写得很清楚：

> `getSlot()`：被点击的槽号，可直接传给 `Inventory.getItem(int)`。
> **一个视图连接两个物品栏，所以可能有两个槽共用同一个号码。**
> `getRawSlot()`：可直接传给 `InventoryView.getItem(int)`，**在视图内唯一**。

玩家背包视图里，头部是：

| 位置 | 头部下标 |
|---|---|
| 视图内（`getRawSlot` / `InventoryView`） | **5** |
| `PlayerInventory` 内（`getSlot`） | **39**（护甲占 36–39） |

**曾经写成 `event.slot != 5`**，结果点头部永远匹配不上——
没有编译错误、没有异常，只有玩家反馈「用不了」才发现。

头部只放 1 个，多余的和换下来的旧头饰都进背包，满了才掉脚下——**任何路径都不丢物品**。

> ⚠️ **这段路径没有自动化测试，而且这是有意的。**
> MockBukkit **连 `InventoryClickEvent` 的构造函数都会抛
> `UnimplementedOperationException`**（已实测：构造即抛）。
> 硬写一个测试只会得到 **skipped**——`BUILD SUCCESSFUL` 却什么都没验证，
> 正是 [08 §19](08-坑与反模式.md) 描述的最阴险的失败模式。
> 因此这段代码**必须真服 + 真客户端验收**。
> 它依赖的「什么能戴」规则本身在 `HatPolicyTest` 里被完整覆盖（5 个纯 JUnit 用例）。

---

## 5. 命令与权限

| 命令 | 权限 | 默认 |
|---|---|---|
| `/meowo hat` | `meowo.hat` | 所有人 |
| `/hat` | 同上 | 根级快捷方式 |

控制台执行会收到 `player-only`——控制台没有头可戴。

**停用 `contexts.hat` 会直接撤掉 `/hat` 路由**，测试
`disabling the hat context removes its route` 锁定这一点。

---

## 6. 排障

| 现象 | 原因 | 修法 |
|---|---|---|
| `/hat` 回「未知命令」 | `contexts.hat: false` | `/meowo info` 看实际状态 |
| 模组头盔戴不上 | 用了名字判断 | 应为 `getEquipmentSlot() == HEAD` |
| 旧头盔不见了 | 写成了「移动」而不是「交换」 | 头→手，不是丢弃 |
| 编译报 `ArmorMaterial` 未解析 | 抄了旧版本代码 | 见 §2 |
| 测试报 `plugin.getCommand("hat") == null` | `build/resources/main/plugin.yml` 没重新生成 | `./gradlew clean`（TabooLib 的 `processResources` 增量判断会漏） |
| 物品栏里点不进头部 | 正常——客户端不让放，得靠 §4 的事件；确认 `hat.inventory-slot: true` | 检查配置 |
| 覆盖率高但库存点击没测 | MockBukkit 构造不了 `InventoryClickEvent` | 见 §4，需真服验收 |
| 物品越用越多 | 余量进背包而非回光标 | 见上，必须真服核对数量 |

> 上面最后一条**不是理论问题**：加 `hat` 命令后我第一次跑测试，
> 66 个用例全挂在 `MeowoRuntime.start()` 的 `requireNotNull(getCommand("hat"))` 上，
> 因为产物里的 `plugin.yml` 还是旧的。`clean` 之后一次通过。
> **改完 `plugin.yml` 跑测试前先 `clean`**，与 iCloud 那条铁律同源。
