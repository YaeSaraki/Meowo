# 14 · AFK 与状态圆点（`nametag`）

> **状态：已实施并修复。** `/afk` 切换挂机标记，头顶名牌后跟一个圆点。

---

## 1. ⚠️ 为什么最初的实现完全无效

第一版 `NametagService.apply()` 是这么写的：

```kotlin
player.customName(nametag(player, afk))   // 加上名字 + 圆点
player.isCustomNameVisible = !player.isSneaking
```

**这段代码对玩家一点作用都没有。** 官方 Spigot API javadoc 对
`Entity#setCustomNameVisible` 和 `Entity#isCustomNameVisible` 两个访问器写了同一句话：

> "This value has **no effect on players**, they will always display their name."

`setCustomName` 同理——**它对玩家无效，玩家永远显示真实名字**。

所以症状是：**`/afk` 有反馈消息，状态完全不变，测试全绿，没有任何报错。**
这和 [08 §2](08-坑与反模式.md) 的「静默降级」是同一类：调用合法、编译通过、什么都不会发生。

> 再次印证红线 [01 §9](01-架构红线.md)：**动手前查官方 javadoc，不要凭印象**。
> `setCustomName` 在mob 上完全正常，所以大量教程（包括 LLM 生成的）
> 会把它当作玩家名牌的正确做法。

---

## 2. 默认时长

`nametag.afk-after-seconds` 默认 **180 秒 = 3 分钟**。
值同时写在 `config.yml` 与 `GlobalConfig.read` 的兜底里，两处必须一致
（见 [05 §10](05-改造手册.md) 的「默认值改两处」）。

---

## 3. 改用计分板队伍（team）后缀

队伍的前缀/后缀由客户端在**头顶名牌**（以及 Tab 列表）里渲染，
而且**对玩家有效**，且不需要任何发包。

```kotlin
private fun team(board: Scoreboard, name: String): Team =
    board.getTeam(name) ?: board.registerNewTeam(name)

// 两种状态各一个队伍，状态翻转时把玩家挪过去
other?.removeEntry(player.name)
target?.addEntry(player.name)
```

| | 队伍 | 后缀 |
|---|---|---|
| 离开 | `mw_away` | **加粗** `C9A063` 深琥珀 `'` |
| 在线 | `mw_present` | **加粗** `A3D9A0` 浅绿 `'` |
| 学习中 | `mw_study` | **加粗** `D64545` 红 `'` |

标记是**加粗的撇号 `'`**，不是圆点——`•` 在远处会糊进名字里，`'` 更细更清楚。
加粗是必须的：原版名牌默认是**斜体**，斜体会和颜色打架，所以两处装饰都要显式写死。

### 三个状态，不是一个布尔值

> 新增。原先 `apply(player, afk: Boolean)` 只能表达「在」或「不在」，但**番茄钟里的玩家既是 idle、又是在专心**——
> 布尔值说不清这件事，结果一个正在专注的人头顶显示的是深琥珀「离开」。现在是三个状态：

| 状态 | 队伍 | 颜色 | 含义 |
|---|---|---|---|
| `PRESENT` | `mw_present` | `A3D9A0` 浅绿 | 在线 |
| `AWAY` | `mw_away` | `C9A063` 深琥珀 | 挂机 |
| `STUDY` | `mw_study` | `D64545` **红** | 番茄钟进行中 |

**`study` 压过 `afk`**（`NametagState.resolve`）。空闲规则是用来发现「人离开键盘了」，而跑番茄钟的玩家是**主动声明**自己在这儿；给别人看的最有用的信号是「别打扰」，不是「这人不在」。所以两者同时成立时取 `study`。

学习中的来源是 `FocusAPI`（`api` 模块，Bukkit ServicesManager 发布），**每次 sweep 现取**：

```kotlin
private val studying: (UUID) -> Boolean = { false }   // 构造参数，不在构造期解析
```

用函数而不是构造期取一次：`MeowoRuntime` 里 nametag 比 pomodoro 先启用，写死引用会让先启动的一方永远拿不到对方。
跨 context 调用包了 `runCatching`——另一个 context 正在卸载时，丢掉一个红色标记只是外观损失，带着整个 sweep 崩掉不是。

> ⚠️ **护盾和标记是两件事。** 红点表示「在专注」，但免伤/防推仍然按**真的 idle** 判定（`apply` 里单独算 `afk`）。原因是番茄钟漂移超过 `max-distance` 会自动暂停，玩家还站在原地，那种情况下依然需要护盾。

### 三个必须知道的副作用

1. **一个玩家只能属于一个队伍。** 所以这个 context 拥有它注册的两个队伍，
   名字用 `mw_` 前缀避开其他插件，`close()` 时 `unregister()` 掉。
2. **队伍后缀也会出现在 Tab 列表和原版聊天里。** 这是机制本身的性质，
   没有纯 Bukkit 办法只影响头顶。圆点只有一个字符，视觉噪音有限；
   不想要就 `nametag.enabled: false` 关掉整个 context。
3. **潜行隐藏名牌的行为归原版管。** 队伍后缀不受插件控制，
   所以原来的 `sneak` 监听和 `refreshVisibility` 都被删掉了——
   留着只会假装自己能控制，实际什么也做不了。

---

## 2b. ⚠️ 颜色一直是默认色：Adventure 只认 `#` 前缀

标记画出来是**白的、不会随状态变化**——原因和 §1 是同一类静默失效：

```kotlin
// ❌ 原写法
val colour: String get() = "0x$rgb"        // -> "0xC9A063"
TextColor.fromHexString(colour)             // -> null！
Component.text(text, null)                  // null 颜色 = 客户端默认色
```

实测：

| 传入 | `TextColor.fromHexString` |
|---|---|
| `"0xC9A063"` | **`null`** |
| `"C9A063"` | **`null`** |
| `"#C9A063"` | `#C9A063` |

**Adventure 的十六进制词法器只接受 `#` 前缀**，而代码写的是 `0x`（那是 NBT 的写法）。
返回 `null` 不抛异常，`Component.text(text, null)` 就安静地退回默认色。

修法是**自己解析**并**对坏值大声失败**：

```kotlin
fun colour(): TextColor {
    val value = rgb.toIntOrNull(16)
        ?: throw IllegalArgumentException("Marker colour must be hex digits, got '$rgb'")
    return TextColor.color(value)
}
```

原来的测试 `the colour is exposed in the hex form Adventure expects` 断言的是
**字符串** `"0xC9A063"`，所以它一直是绿的——**断言了错误的层**。
现在断言的是**最终送进客户端的颜色值**：

```kotlin
assertEquals(0xC9A063, MarkerStyle.AWAY.colour().value())
```

> **教训**：颜色相关的断言要断言**解析后的值**，不是断言中间那个字符串。
> 字符串对了不代表颜色被解析了——这次就是字符串完全正确而颜色是 null。

---

## 2c. AFK 期间的「假离线」防护

### 调研：为什么不做成真正的假离线

| 路线 | 代表 | 做法 | 在本项目的可行性 |
|---|---|---|---|
| **A 防护 + 位置锁** | AFK Shield / AdvancedAFK / SmartAFK / AFK Plugin | 无敌 + 不可推 + 清怪目标 | ✅ 纯公开 API |
| **B 真·假离线** | AfkDummy | 删掉玩家实体，用 `PlayerList#placeNewPlayer()` 注入 NPC 顶替 | ❌ 需要 NMS |

B 是字面意义上的「假离线」，但它**违反本项目红线 [01 §8](01-架构红线.md)（不使用 NMS）**。
看 AfkDummy 的更新日志就知道代价：NMS 位置重置、头部/身体旋转同步、Netty `EmbeddedChannel`
内存泄漏修补、原子 JSON 存储、重复实体检测、传送延迟处理……

所以采用 A 路线，这也是绝对主流做法。

### 实现（`application/AfkGuard.kt`）

| 手段 | API | 作用 |
|---|---|---|
| 免疫伤害 | `isInvulnerable = true` + 取消 `EntityDamageEvent` | 生物/玩家/爆炸/虚空都打不动 |
| **位置锁** | `isCollidable = false` | **推不动**（26.2 的 `LivingEntity#setCollidable`） |
| 清怪目标 | `Mob#setTarget(null)` | 怪物不再锁定 |
| 取消击退 | 取消 `EntityKnockbackEvent` | 击退与伤害分开结算，要单独拦 |

> ⚠️ **击退必须单独取消。** 它**不走伤害流程**，所以即使免伤，被打中仍然会被推开。

### 2d. ⚠️ `setCollidable(false)` 在 26.2 对玩家已失效

第一版就是用 `player.isCollidable = false` 锁位置的，**结果玩家照样被推**。
Paper 开发者 electronicboy 在 PaperMC/Paper#11440 的原话：

> "setCollidable and collidable exemptions are **purely server concepts**, the player doesn't
> know it's not supposed to collide with a certain entity. The only way I can see that this could
> be fixed would be for the server to send **team packets with collision set to never**."

同一帖还有：*"Player movement from collisions happens **on client side** and can't be disabled."*
自 SPIGOT-6399（1.19）起 `setCollidable` 对玩家**一直是空操作**。

**team 碰撞规则也救不了**：`Team.Option.COLLISION_RULE` 只管**同队成员之间**，
而一个玩家只能在一个队——我们已经用队做 away 标记了，不可能把所有玩家和怪物都拉进来。

所以改成**锚点回正**：

```kotlin
anchors.putIfAbsent(id, player.location.clone())   // 进入 AFK 时记住位置
// 每次 sweep：漂移超过 5cm 就归零速度并传送回锚点
```

这是**与推力来源无关**的方案——玩家推、划船、活塞、水流、风弹，一并兜住。

#### 怎么区分「被推」和「自己在走」

两者都是位置包，光看位置分不出来。**速度能分**：推挤会附带冲量，走路不会。

```kotlin
player.velocity.lengthSquared() > 1.0E-4   // 有冲量 = 被推 → 回正，保持 AFK
                                           // 无冲量 = 自己走 → 正常解除 AFK
```

修正用的传送本身也会触发 `PlayerMoveEvent`，用 `correcting` 标记识别，
**否则自己的修正会被当成「玩家动了」而解除 AFK**。

`setCollidable(false)` 仍然保留——对非玩家实体有效，只是不再指望它挡玩家。

### 这修的正是你说的「被推挤就退出 AFK」

多个主流 AFK 插件的官方说明里都挂着同一条已知 bug：

> **Known Bugs: Animals/Players can cancel your AFK status by pushing you.**

推一下 → 位置变化 → 触发移动事件 → 空闲计时被清零 → AFK 静默解除。
`setCollidable(false)` **从源头掐断推挤**，而不是去堵移动事件那个症状。

### 恢复现场而不是一刀切

```kotlin
priorInvulnerable.putIfAbsent(id, player.isInvulnerable)   // 记住原状
priorCollidable.putIfAbsent(id, player.isCollidable)
```

用 `putIfAbsent` 是必须的：**每 tick 的 sweep 会对同一玩家反复 `engage`**，
若每次都记录，已经被改成 `true` 的值会覆盖掉原始状态，解除时反而把创造模式玩家的
无敌**清掉**——那是实打实的 bug。`AfkGuardTest` 里
`engaging twice does not overwrite the remembered state` 和
`a player who was already invulnerable keeps it` 专门锁这两点。

### 退出 AFK 的触发条件

任何一个**主动操作**都解除 away 状态，全部走同一个 `activity(player)`：

| 触发 | 事件 | 说明 |
|---|---|---|
| **下蹲** | `PlayerToggleSneakEvent` | 明确的「我回来了」手势 |
| 挥手 | `PlayerAnimationEvent` | 不移动也能解除 |
| 切换物品栏 | `PlayerItemHeldEvent` | 换快捷栏 |
| 吃东西 | `PlayerItemConsumeEvent` | 进食 / 喝 |
| 说话 | `AsyncChatEvent` | Paper 的聊天事件（非废弃的 `PlayerChatEvent`） |
| `/afk` | 命令 | 仅 OP，见下 |

### 移动的处理：只观察，从不移动玩家

这一版走过三次弯路，最后的结论是**插件永远不要动挂机玩家**：

| 版本 | 做法 | 结果 |
|---|---|---|
| 1 | 每一步都拉回（漂移 > 5cm） | **走一步退一步，永远退不出** |
| 2 | 用速度区分「被推 / 自己走」，只有被推才拉回 | 走路自带速度 → 误判 → **仍然退不出** |
| 3 | 超过 5 格就**传送拉回** | 能用，但擅自移动玩家 |
| **4（现在）** | 超过 5 格就**结束挂机** | ✅ 插件从不移动玩家 |

```kotlin
// 第 4 版：PlayerMoveEvent 里
if (!service.isGuarded(id)) { activity(player); return }   // 还没挂机 → 正常重置计时
if (service.hasDrifted(player)) activity(player)           // 被拖远 > 5 格 → 直接退出挂机
```

`hasDrifted` 是一个**问句而不是动作**——它只报告「被拖远了」，由调用方结束挂机。
所以这条路径**不可能移动玩家**，`AfkGuardTest` 里
`being carried past the leash reports a drift and never moves the player`
专门断言拖到 9 格之后玩家**仍在原地**。

语义因此很干净：

- **走动本身不解除挂机**（避免误判）
- **被生物/船拖走 5 格以上 → 解除挂机**（说明确实出事了）
- **解除方式**：下蹲、挥手、吃东西、切换物品栏、说话（都不依赖速度，判定可靠）

### ⚠️ 所有这些动作**都**要重置空闲计时，不只是解除挂机

这一点曾经写反过，是「标题无缘无故弹出来」的根因：

```kotlin
// ❌ 旧代码：还没挂机时提前 return，空闲计时根本没被重置
val wasAway = service.isAfk(id, now)
if (!wasAway) return          // ← 聊天/挥手/吃东西在空闲窗口内等于什么都没发生
service.moved(player, now)
```

后果：一个**每分钟都在聊天**的玩家，空闲计时从未被重置，3 分钟后照样被判为挂机，
标题「无缘无故」地弹出来——因为他确实一直在操作。

```kotlin
// ✅ 现在：先无条件重置计时，再决定要不要撤销挂机表现
val wasAway = service.registerActivity(player, System.currentTimeMillis())
if (!wasAway) return   // 计时已重置，只需撤销表现时才继续
service.apply(player, false)
host.messages.clearTitle(player)
```

`NametagService.registerActivity` **无论是否挂机都会重置时钟**，并返回「之前是否挂机」，
调用方据此决定要不要清标记/标题。这样「操作」只有一个语义：**把空闲计时归零**。

回归测试 `activity while not yet away resets the idle clock anyway`
用真实的 180 秒阈值、连续模拟 10 分钟且每 30 秒操作一次，断言玩家**始终没有**被判挂机；
随后静默 3 分钟才挂机——证明计时器确实在跑，而不是被误判成「一直活跃」。

### 屏幕中央的挂机提示

进入 away 时发一次标题，并在**每次 sweep 重复发**——标题会自动淡出，不刷新的话
一秒后就没了，提示也就白写了：

```kotlin
// NametagContext.refreshTitles()，挂在已有的 sweep 上
if (service.isAfk(player.uniqueId, now)) showAwayTitle(player)
```

文案在语言文件里（`nametag-away-title` / `nametag-away-hint`），用 Paper 的
`sendTitle(title, subtitle, fadeIn, stay, fadeOut)`，`§` 颜色码照常生效。
离开 away 时用空标题清除。

时长：**fadeIn 10 / stay 200 / fadeOut 20** tick（标题停留 10 秒）。
**续期时 `fadeIn` 传 0**——否则每秒重发都会重新开始淡入，标题看起来在闪烁。

提示文案只列**真正能用的**退出方式（下蹲、挥手、吃东西、切换物品栏、发言），
不含移动——移动已经不是退出条件，提示里出现一个做不到的操作比不写更糟。

### `/afk` 改为仅 OP

`meowo.nametag.afk` 的 `default` 由 `true` 改为 `op`。
普通玩家**只能靠固定时间无操作进入 AFK**，无法主动进入；
服主仍可用它在打怪等场景手动标记。命令与语言键都保留。

---

## 3. 顺带修掉的两个小问题

| 问题 | 修法 |
|---|---|
| `/afk` 要等最多 1 秒才有反应（要等 sweep） | `toggleAfk` 里直接 `service.apply(...)` |
| `join` 没有写入 `applied`，第一轮 sweep 会重复改一次队伍 | 新增 `markApplied(id, afk)` |

---

## 4. 测试

`AfkGuardTest`（10 个）锁定防护语义：

| 用例 | 锁定的事实 |
|---|---|
| `engaging makes the player invulnerable and non collidable` | 免疫 + 不可推 |
| `releasing restores the flags the player actually had` | 退出恢复**原始**状态 |
| `a player who was already invulnerable keeps it` | 不误清创造模式的无敌 |
| `engaging twice does not overwrite the remembered state` | 每 tick sweep 不覆盖记忆 |
| `a mob hunting an away player loses its target` | 清掉追杀自己的怪 |
| `a mob hunting somebody else is left alone` | **不动**追杀别人的怪 |
| `walking freely does not end the away state` | 走 2 格**不解除挂机**，且玩家不被移动 |
| `being carried past the leash reports a drift and never moves the player` | 9 格触发，且**玩家仍在原地** |
| `staying put is not a drift` | 没动就不算漂移 |
| `releasing forgets the anchor` | 解除后不再被拉回 |



`NametagServiceTest` + `MarkerStyleTest`（共 22 个，MockBukkit 完整支持 Scoreboard/Team）：

**颜色与样式（防本次静默失效）**

| 用例 | 锁定的事实 |
|---|---|
| `the two teams carry different real colours, not the default` | 断言**送进客户端的颜色值**，away≠present |
| `the colour resolves to a real RGB value, never null` | `AWAY.colour().value() == 0xC9A063` |
| `a malformed colour fails loudly instead of rendering as the default` | 坏值抛异常，不静默变白 |
| `the mark is bold, because vanilla nametags are italic` | BOLD=TRUE、ITALIC=FALSE |
| `the mark is a quoted tick with a gap` | 符号是 `'`，`gap+glyph` 共 2 字符 |

**队伍生命周期**

| 用例 | 锁定的事实 |
|---|---|
| `install registers both teams with a marker suffix` | 两个队伍都带圆点后缀 |
| `apply puts a player on the team matching the state` | 状态翻转是**移动**不是复制 |
| `a player is never in both teams at once` | 反复翻转不会同时占两个队伍 |
| `forget takes a quitting player off both teams` | 退出不留残队 |
| `uninstall removes the teams from the scoreboard entirely` | 停用 context 不留队伍 |
| `installing twice reuses the same teams` | reload 不累积队伍 |
| `sweep moves a player onto the away team once the threshold passes` | 空闲超时真的换队伍 |
| `a disabled service never puts anybody on a team` | `enabled: false` 完全不生效 |

加上 `IdleStateTest`（6），该 context 共 **33 个测试**。

> 注意 `MockBukkit` 里主计分板要通过 `server.scoreboardManager.mainScoreboard` 取，
> **`ServerMock` 没有 `.scoreboard` 属性**。

---

## 5. 排障

| 现象 | 原因 | 修法 |
|---|---|---|
| `/afk` 有提示但头顶没变化 | 用了 `setCustomName`（对玩家无效） | 见 §1，改用队伍 |
| 标记是白的、不随状态变 | 用了 `fromHexString("0x…")` 返回 null | 见 §2b，改用 `colour()` |
| 标记太细看不清 | 距离太远或客户端关了名牌 | 选项 → 皮肤自定义 → 始终显示名称标签 |
| reload 后 Tab 出现两个同名队伍 | 旧队伍没注销 | 见 §2，`uninstall()` 已处理 |
| 别的插件的等级/前缀消失了 | 队伍冲突，一人只能一队 | 服主自行协调队伍归属 |
| 什么都不显示 | `nametag.enabled: false` | 改回 true 后 `/meowo reload` |
| AFK 仍被推走 | 队伍/无敌正常但 `isCollidable` 被别的插件改回 | 见 §2c，注意插件间冲突 |
| AFK 解除后仍无敌 | 重复 `engage` 覆盖了记忆状态 | `putIfAbsent`，见 §2c |
| 怎么都退不出 AFK | 每步都被拉回 | 见「移动的处理」，改为只观察不动人 |
| 挂机时被船/怪群拖走 | 超出 5 格即结束挂机 | 这是设计行为，不是传送 |
| 玩家 `/afk` 没反应 | 权限已改为 op | 给 `meowo.nametag.afk` |

**自查**：

```bash
grep -n "customName" contexts/meowo-nametag/src/main/kotlin/com/meowo/nametag/application/NametagService.kt
# 应无输出：customName 对玩家无效，任何时候都不要再用它做玩家名牌
```
