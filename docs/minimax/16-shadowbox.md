# 14 · 潜影盒（`/shadowbox`）

> **状态：已实施。** 全新 context `shadowbox`，发言者 `ShadCat`。

---

## 1. 行为：两个键位，默认所有人

| 操作 | 结果 |
|---|---|
| 物品栏里 **Shift + 右键** | 打开盒子界面 |
| 手拿潜影盒 **Shift + 右键** | 打开盒子界面（不会把方块放下去）；**副手同样有效** |
| `/shadowbox` | 打开主手上的潜影盒 |
| `/shadowbox give <玩家>` | 发放一个潜影盒（**默认需要 `meowo.shadowbox.give`，普通玩家看不到这个子命令**） |

两个键位是同一段代码的两个人口。权限 `meowo.shadowbox` 是 `default: true`，
**两个键位和 `/shadowbox` 打开自己手上的盒子都不需要任何权限**。

**`give` 是这里唯一一个会凭空造出物品的动作，所以默认是关着的**：
`shadowbox.give-permission` 默认为 `true`，`meowo.shadowbox.give` 是 `default: op`。
权限不够时，**这个子命令在三个地方都不出现**——Tab 补全的前缀匹配、用法提示文本、
以及补全出来的玩家名列表——三处都走同一个 `mayGive()` 判定，不会各自漂移。

> 这里曾经有个反向的坑：默认值是 `false`，而**配置注释还写着「只有 give 需要权限」**，
> 于是任何玩家 `/shadowbox give 自己` 都能拿到盒子，而代码根本没检查。
> 「默认值选在危险的那一侧 + 注释说反话」是最难查的一种，所以现在默认值选在安全侧。

**Shift + 右键必须潜行。** 潜行 + 右键在原版里本来就不放方块，所以不潜行时
潜影盒就是个普通方块物品、正常放下——这同时保证了它不会抢走开门、开箱等右键。

**物品栏里的 Shift + 左键不接管。** 那是原版的「快捷栏 ↔ 储物栏互移」，
玩家还要用它搬别的东西。判定写在 `ShadowBoxTrigger.inventoryClick`：只认
`SHIFT_RIGHT`，并且只在**玩家自己的背包界面**（`InventoryView#getType() == CRAFTING`）
上接管，所以箱子里的潜影盒仍然是原版的「快速移动到另一个容器」，连同这两点
一起被纯 JUnit 钉住。

---

## 2. 它是什么：物品即容器，**任何盒子都行**

**没有资源包**，所以潜影盒是「一个带方块实体背包的原版方块物品」。
判定只看**物品自身的能力**，不看我们的标记：

```kotlin
fun isBox(item: ItemStack?): Boolean {
    val size = capacityOf(item) ?: return false       // 方块状态是否带背包
    return size in MIN_SLOTS..MAX_SLOTS && size % 9 == 0   // 1~6 行，摆得成窗口
}
```

于是**任何颜色的潜影贝盒、箱子、木桶、末影箱都能当潜影盒开**，包括玩家
自己捡来的；`/shadowbox give` 发出的那个额外带 `meowo:shadowbox` 标记 + 唯一 id
+ 专属名称和说明，所以更清楚、回写也更稳。插件被卸载后，这些盒子仍然是正常的
原版容器物品。

| 选择 | 后果 |
|---|---|
| 用 `TileStateInventoryHolder` 而不是 `Container` | **末影箱必须这样**：`EnderChest` 只实现 `Lidded, TileState`，问 `Container` 会把它悄悄排除掉 |
| 容量必须是 9 的倍数、1~6 行 | 漏斗（5 格）、熔炉（3 格）摆不成窗口，就不是盒子，也就不会出现「能点开但开出来是空的」 |
| 不用标记判定 | 标记只用于**回写时定位**，不用于**判断是不是盒子**；两件事混在一起会让没标记的真盒子打不开 |

### 2.1 没有 id 的盒子会被「认领」

`finish` 按 **盒子 id** 在玩家背包里找那一个盒子（因为玩家可以在窗口开着的时候
整理背包，槽位会变）。玩家自己捡来的盒子一开始没有 id，所以**第一次打开时就给它补一个**
（`BoxItems.adopt`），并把补过的物品放回原来的位置。之后按 id 找就永远找得到。

标记在游戏里**完全不可见**，物品外观一个字都不会变。

> 两个完全一样的空盒子被同时认领时，只有先被找到的那个会拿到 id，另一个仍然是
> 无标记的。两者内容相同、id 不同，所以**不会共享内容、不会复制**；最坏的情况是
> 玩家放进去的东西出现在另一个外观相同的盒子里。

**盒子里的东西存在物品自己的 `BlockStateMeta` 里**，和原版潜影贝盒完全同构。
这一条选择换来三个免费属性：

| 属性 | 为什么免费 |
|---|---|
| **内容跟着物品走** | 掉落、交易、存箱子、死亡掉落都自动跟随 |
| **没有孤儿数据、不需要异步** | 不建表、不迁移、不对账，主线程永远不碰数据库 |
| **原版自己防复制** | NBT 一旦不同客户端就会拆栈，一个盒子装不下两套内容 |

唯一诚实的代价：**崩服时最多丢「刚放进窗口、还没结算」的那部分内容**，
盒子本身不会丢。这和原版潜影贝盒的行为一致。

### ⚠️ 不要改 `shadowbox-name`

物品改名后**已存在的盒子仍显示旧名字**——新名字只写进新建的盒子里。
这是刻意的：让改名永远不会变成复制物品的手段。需要改名就重新发一批。

---

## 3. 三个必须小心的点

### 3.1 从点击事件里开窗口必须等一个 tick

Paper 的 `InventoryClickEvent` javadoc 写明该事件**在库存修改过程中**触发，
任何「改变玩家正在看的界面」的方法都必须用 `BukkitScheduler#runTask` 排到下一 tick。
直接在 handler 里 `openInventory` 会让客户端错位。

所以点击这条路径是**在 handler 里读盒子，下一 tick 再开窗口**
（`ShadowBoxContext.nextTick`）。测试里 `server.scheduler.performTicks(1)`
是这个交互的一部分，不是加速手段。

`PlayerInteractEvent` 那条路径没有这个问题（没有库存修改在进行），直接开。

### 3.2 窗口里**一个像素都不能碰**（这条真的咬过人）

`InventoryClickEvent` 是在**原版搬移之前**触发的。第一版为了「不让盒子被塞进窗口」
写了这样的代码：

```kotlin
// ❌ 错误：清空窗口 + 取消点击
if (inOurWindow) { top.clear(); event.isCancelled = true; return }
```

玩家点一下取东西，结果是：**东西没了**。因为 handler 执行时物品还在窗口里，
`clear()` 把它删了，而点击又被取消，原版不会把它搬到背包里——物品就这么凭空消失。
真服上报的现象是「可以放进去，取出来直接消失」，放进去没事（放进取的是下层背包，
走的不是这条分支）。

正确写法是**只拒绝盒子，其余全部交给原版**：

```kotlin
if (inOurWindow) {
    if (isBox(event.currentItem) || isBox(event.cursor)) event.isCancelled = true
    return
}
```

`InventoryDragEvent` 有一模一样的坑：把「从窗口里拖出来」的源槽清空，同样是删物品。
所以拖拽也只判断「会不会把盒子拖走 / 会不会把盒子拖进窗口」。

> **为什么 MockBukkit 没抓到**：`InventoryClickEvent` 被触发，但 MockBukkit **不执行
> 原版的搬移事务**。所以测试只能手工搬运，而手工搬运恰好绕开了这条真实路径。
> 教训：MockBukkit 绿的路径不等于真服的路径，尤其是**依赖原版事务**的那些。
> 现在有两个用例专门钉死它（`taking things out never destroys them`），
> 并且断言的是「**我们没有取消、也没有清空**」，而不是「物品到了背包」。

> ⚠️ **这条规则只管点击，不管关闭。** 分界是**事件**，不是「窗口」：
> `InventoryClickEvent` 在原版搬移**之前**触发，那里清空 = 销毁物品；
> `InventoryCloseEvent` 是关窗，**那里必须清空**，否则刷物品——见 §4.1。
> 把本节无差别套到 close 上，会亲手造出 §4.1 那个洞。

### 3.3 窗口开着的时候，盒子自己不能动

原版允许玩家在窗口打开时把下层背包的潜影盒丢掉（Q）、换到副手（F）、
数字键换位、Shift 丢进窗口里——那样关闭时内容就写不回正确的盒子了。
所以这些统统取消（`ShadowBoxTrigger.protectBox`）。

**判断必须用 `rawSlot`，不能用 `event.slot`。** 窗口打开时盒子在**下层**背包里，
而 `event.slot` 是「该库存自己的下标」，从 0 开始；只有在自己物品栏界面
（`view.type == CRAFTING`）里 `slot` 才是 9..45。这个坑真的咬过一次：
第一版用 `event.slot` 判「是不是玩家自己的槽」，结果下层背包前 8 格完全没被保护。

同理，`drag` 里**不能只看上层的分支然后 `return`**，否则下层的保护又会被吞掉。
两个 handler 的结构必须一致：先处理上层（清空 + 取消），再处理下层保护。

### 3.4 窗口的识别靠 holder，不靠标题

标题是本地化的，而且别的插件也可能用同一个标题，所以标题**不能**用来识别窗口。
`Session` 自己就是 `InventoryHolder`：

```kotlin
val session = Session(boxId)
session.window = Bukkit.createInventory(session, contents.size, title)
```

第一版写成了 `Bukkit.createInventory(null, …)`，于是 `top.holder` 永远是 null、
窗口永远认不出来，**盒子可以被 Shift 进窗口里然后在关闭时无处可回**。
`Bukkit#createInventory` 要求 holder 在前，所以用 `lateinit`/`lateinit var window`。

### 3.5 判定「玩家自己的槽位」要用 **raw** 槽位

`InventoryClickEvent#getSlot()` 是 `view.convertSlot(rawSlot)`，**不是**玩家看到/以为的下标。
在 `view.type == CRAFTING`（玩家按 E 打开自己背包）时 Bukkit 会重编号玩家的背包，
**快捷栏 raw 36-44 被换算成 converted 0-8**，落在储物区 9-35 的**下面**。
主手就是快捷栏第 0 格——玩家最可能拿着盒子的地方——于是
「盒子在快捷栏里 Shift + 右键打不开」。

修法是不要用转换后的槽位做范围判断：

```kotlin
playerSlot = event.rawSlot >= event.view.topInventory.size,
```

上半部分是合成网格，raw 从这里开始就是玩家自己那一半；容器视图同理是容器本身。
没有魔法数字，对所有 view 都成立。详见 [08 §21](08-坑与反模式.md)。

---

## 4. 关闭时怎么把内容还回去

按 **盒子 id**（不是槽位）在玩家背包 + 副手里找那一个盒子，把内容写回去：

| 触发 | 处理 |
|---|---|
| `InventoryCloseEvent` | 回写 |
| `PlayerQuitEvent` | 回写（背包在事件之后才保存，所以回写就是持久化） |
| `PlayerDeathEvent`（`HIGHEST`） | 回写。盒子此刻**还在背包里**，之后才计算掉落，所以内容跟着盒子一起掉 |
| context `close()`（`/meowo reload`） | 先回写再关界面，否则关闭事件会写第二次 |

**找不到盒子时把内容直接还给玩家，并打一条 WARN。** 我们的点击保护让正常玩法
不可能走到这一步，所以只可能是别的插件动了它——丢一个盒子已经不好了，
销毁别人的物品更糟。`finish` 的注释写了这条不变量。

> 用 id 而不是槽位：窗口开着的时候玩家可以整理背包，槽位会变。

### 4.1 存完之后**必须清空窗口**，否则原版会退第二份

这是本功能**唯一的刷物品入口**，也是最容易在重构时被 §3.2 顺手改回去的地方。

`Bukkit.createInventory` 造的窗口**背后没有方块实体**（Paper 的 `CraftInventoryCustom`
在 NMS 侧是裸 `NonNullList`），所以关闭窗口时原版**会自己把内容还给玩家**。
CraftBukkit 的顺序是「先派发 `InventoryCloseEvent`，再调 `doCloseContainer()` 退款」——
也就是说我们的 handler 跑在**退款之前**。只存不清 = 一份进盒子、一份进背包。

```kotlin
try { save(player, session) }
catch (e: Throwable) { /* 退回给玩家，绝不吞掉 */ }
finally { drain(session.window) }   // ← 防刷的就是这一行
```

用 `finally` 是因为**每条退出路径都要清**：存成功、盒子丢了、写回抛异常、
存进去的物品又被退回给玩家——任何一条漏掉都会留下原版要退的那份。
顺序是**先克隆再清空**，反过来就真删了（这正是 §3.2 那个 bug 的形状）。

> **MockBukkit 复现不了这个 bug**：它的 `closeInventory()` 只是把视图重置，不做退款。
> 所以照抄这个 bug 测试是绿的。用例必须**直接断言窗口被清空**
> （`the window is emptied on close so vanilla cannot refund it a second time`）——
> 那个空窗口正是真服上会被原版再退一次的东西。

### 4.2 只有「一个」的盒子才允许打开

`amount > 1` 的叠堆**拒绝打开**：整叠是**一个**物品对象，共享同一个 id 和同一份
方块实体 NBT，写回之后原版按 NBT 拆栈，**每只都带着同一份内容**。
带内容的盒子在原版里本来就不可能叠，所以这只影响「一叠空盒子」这种场景。
不要为了「方便」去自动拆叠——那要动玩家背包槽位，做错的代价是吞盒子。

同理，**已经有 id 的盒子不进 `adopt`**，所以「把改过的副本放回原处」这件事
只发生在首次认领时；位置由调用点显式传入（命令=主手、`event.hand`=主/副手、
`event.slot`=背包下标），**绝不靠 `==` 去猜**——两个没标记的潜影盒是相等的。

---

## 5. 物品改写的两个 Paper 26.2 细节

```kotlin
meta.getPersistentDataContainer().set(tagKey, PersistentDataType.BYTE, 1)
meta.setBlockState(state)   // 写回方块实体，NBT 才落到物品上
stack.itemMeta = meta
```

- `ItemStack` **不会**因为 `getItemMeta()` 改了 meta 就更新。
  `BoxItems.store` 返回一个 **新的** `ItemStack`，调用方必须 `setItem` 回原槽位。
- `BlockStateMeta#hasBlockState()` **不能**用来判断「这是不是一个能装东西的物品」。
  它只表示「有没有存下来的 NBT」，全新物品合法地没有 NBT；而且 **MockBukkit 对所有
  新物品都返回 false**。真正要问的是「`blockState` 是不是带可用背包的 `Container`」。

---

## 6. 测试

| 文件 | 覆盖 |
|---|---|
| `platform/.../ShadowBoxTriggerTest.kt` | 15 个纯 JUnit：两个键位的正反例、副手只取消不开、拖拽保护 |
| `core/.../MeowoRuntimeTest.kt` | 19 个 MockBukkit 集成：双键位、**取东西不销毁**、盒子不能停进窗口、**窗口里的盒子会被退回**、副手开箱、两个方向的存取往返、按 id 回写、五种移动全被拦、双手只开一个、**整片背包都能开**、**上半区不接管**、非本插件发的盒子、不是盒子的容器、退出、停用 context、材料兜底、give 权限、空手与控制台 |

> ⚠️ **曾经有三个用例从来没跑过**（其中包含「不是本插件发的盒子也能开」这条本轮新增的），
> 而构建一直是绿的。原因见下。

**这两个键位是用真事件测的，但 MockBukkit 有一个必须绕开的坑**：
玩家**没开任何界面**时 `player.openInventory` 是 `SimpleInventoryViewMock`，
它的 `top` 是 `null`、`convertSlot` 直接抛 `UnimplementedOperationException`，
于是 `InventoryClickEvent(view, …)` **在构造器里就炸**。JUnit 把测试方法抛出的
`UnimplementedOperationException` 报成 **skipped 而不是 failed**（见 [08 §19](08-坑与反模式.md)），
所以「测试通过」和「测试跑过」是两件事。

修法是自建一个能承载点击的视图：

```kotlin
private fun ownInventoryView(player: PlayerMock): InventoryView =
    PlayerInventoryViewMock(player, Bukkit.createInventory(null, 9, "crafting")).apply {
        type = InventoryType.CRAFTING
    }
```

两个附带的 mock 特性，写测试时会绊到：

- `createInventory` 的容量**必须是 9 的倍数**，造不出原版那个 5 格合成网格。
  用 9 没关系——生产代码读的是 `top.size`，不是字面量。
- `InventoryViewMock#getItem` 是**朴素偏移**（`raw - top.size`），
  **不套用** `convertSlot` 那套重编号。所以玩家背包下标 *i* 要用 raw *i + 9* 去点。
  两者不一致本身就是 §3.5 那个坑的成因，测试里值得顺手钉住。

仍然需要真服确认的只有**视觉与手感**（标题渲染、拆栈表现、Geyser 下的长按）。

MockBukkit 不模拟原版 quick-move 的实际搬移，所以往返测试是手工搬运的；
被测的是**回写逻辑**，不是它模仿的客户端行为。
**这条限制同时意味着：断言必须写「我们没有干预」，不能写「物品到了背包」**——
后者在 MockBukkit 上永远不成立，而前者正好能钉住 §3.2 那个真实 bug。

---

## 7. 排障

| 现象 | 原因 | 修法 |
|---|---|---|
| Shift + 右键没反应 | `contexts.shadowbox: false` | `/meowo info` 看实际状态 |
| Shift + 右键在快捷栏没反应、储物格却正常 | 用转换后的槽位判范围，见 §3.5 | 已修；用 raw 槽位 |
| 关闭窗口后物品**凭空多出来** | 窗口没清空，原版退了一份，见 §4.1 | 已修；`finally` 里 drain |
| 一叠盒子打开后每只都有内容 | 整叠共用一个 id，见 §4.2 | 已修；拒绝 `amount > 1` |
| 打开 A 结果 B 被标记了 | 两个没标记的盒子 `==` 相等 | 已修；位置由调用点传入 |
| Shift + 右键把方块放下了 | 没潜行 | 这是刻意的，见 §1 |
| 盒子进不了窗口 / 放不进箱子 | 正常的，快捷键只有一个 | 用 `/shadowbox` 或手上的潜影盒 |
| 界面开了但看不到物品 | 崩服时正好开着窗口 | 见 §2 的代价说明 |
| `/shadowbox give` 说你没权限 | 默认就是这样 | 需要 `meowo.shadowbox.give`（OP 默认有） |
| 补全里看不到 `give`，想给玩家发盒子 | 默认对普通玩家隐藏 | 用 OP，或自行设 `give-permission: false` |
| Tab 补全冒出不相关的子命令 | 补全没按已输入前缀过滤 | 已修；`CommandRouter` 统一过滤 |
| 日志出现 `cannot hold an inventory` | `shadowbox.material` 写了非容器方块 | 已自动回落到 `SHULKER_BOX`，改配置 |
| 两个键位都没反应且没有任何日志 | 版本漂移，`ClickType` / `EventPriority` 改名 | 查 [08 §6d](08-坑与反模式.md) |
