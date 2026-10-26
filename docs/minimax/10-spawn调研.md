# 10 · spawn 功能：调研与实施

> **状态：已实施。** 范围 A（`/spawn` + `/setspawn`）、免费、完整保护。
> 本文既是主流实现调研，也是实施记录——第 3 节的 API 结论和第 4 节的坑位清单
> 都直接决定了代码怎么写。

---

## 1. 主流实现：EssentialsX 的 spawn 功能集

EssentialsX 是几乎所有 Paper/Spigot 服务器的标配，其 spawn 相关能力分两层：

| 层 | 命令 / 配置 | 说明 |
|---|---|---|
| **EssentialsX Core** | `/spawn`、`/setspawn` | 基础功能，很多服只装 Core |
| **EssentialsXSpawn** | `spawn-on-join`、`respawn-at-spawn` | 更细的出生/重生控制 |

Core 侧围绕传送的配置项（`Essentials/config.yml`）：

| 键 | 默认 | 作用 |
|---|---|---|
| `teleport-delay` | 3 | **预热秒数**，玩家需原地站立；**移动会取消** |
| `teleport-cooldown` | 5~10 | 两次传送之间的最小间隔 |
| `teleport-safety` | true | 落点在岩浆/虚空时，**寻找最近的方块** |
| `force-disable-teleport-safety` | false | 允许传送到危险点，**官方明确警告不要开** |
| `teleport-invulnerability` | 4 | 传送后无敌秒数 |
| `teleport-to-center` | false | 对齐到方块中心 |
| `command-cooldowns` | — | 按命令粒度的冷却 |

设计意图很清楚：**`/spawn` 不是「瞬移」，而是一套带防滥用保护的标准传送流程**。

---

## 2. 本项目 Paper 26.2 实际可用的 API（已逐个核实）

从 `paper-api-26.2.build.129-stable.jar` 反查：

| 能力 | API | 状态 |
|---|---|---|
| 读世界出生点 | `World.getSpawnLocation()` | ✅ |
| 设置世界出生点 | `World.setSpawnLocation(Location)` / `(int,int,int[,float])` | ✅ 持久化进 `level.dat` |
| 传送 | `Entity.teleport(Location, TeleportCause)`，另有 `TeleportFlag...` 重载 | ✅ |
| 传送原因 | `PlayerTeleportEvent.TeleportCause.PLUGIN` / `COMMAND` | ✅ |
| **移动取消预热** | `PlayerMoveEvent.getFrom()` / `getTo()` | ✅ 且 `PlayerTeleportEvent extends PlayerMoveEvent` |
| 受伤取消预热 | `PlayerDamageEvent` | ✅ |
| 玩家状态 | `isDead()` / `getGameMode()` / `isInsideVehicle()` / `isInvulnerable()` | ✅ |
| 安全落点扫描 | `World.getBlockAt(x,y,z)` / `isChunkLoaded(x,z)` / `Block.isEmpty()` / `Block.isPassable()` | ✅ |
| 危险方块判定 | `Material.LAVA` / `WATER` / `FIRE` 等 | ✅ |
| 权限 | `Permissible.hasPermission(String)` | ✅ |

> ⚠️ **`PlayerCooldownEvent` 在本版本不存在。** Paper 常用的
> `com.destroystokyo.paper.event.player.PlayerCooldownEvent`（`io.papermc.paper.event.player`
> 包下已确认只有 18 个事件，无它）**没有出现在 26.2**。
> 所以冷却必须自己实现（一个 `Map<UUID, Instant>`），反而更干净、无额外依赖。

---

## 3. 本项目已有的、spawn 应该复用的机制

不要重复造轮子——传送类功能在 TPA 里已经踩过坑并解决了：

| 已有机制 | 位置 | spawn 应如何复用 |
|---|---|---|
| **不加载远处区块** | `TeleportContext.move` 里 `isChunkLoaded` 检查 | 安全落点扫描**必须**先查，否则会同步生成区块拖垮 tick |
| 主线程 confined 状态 | 所有 `flights` / `countdowns` / `book` | spawn 的预热状态同理，放 context 里由 `close()` 清理 |
| `active` 闸门 | `host.mainThread(active::get)` | 异步回调统一经过它 |
| 反向停用顺序 | teleport 先于 economy 停 | 若 spawn 依赖 economy 计费，同样必须先停 spawn |
| 托管计费 | `SettlementAPI` + `teleport_charges` 状态机 | **若 spawn 收费，绝不能直接 withdraw**，必须走 reserve/settle，否则会重复扣款 |

---

## 4. 最容易出 bug 的地方（这是「不出 bug」的重点）

按危险程度排序，每条都给出正确做法：

### 4.1 安全落点扫描 —— 风险最高

**错误写法**：从 `Y=world.maxHeight` 往下 `while` 找第一块固体方块。

会出的 bug：
- **死循环**：虚空世界或全空气列，扫描到 y<0 还不停
- **读取未加载区块** → 触发区块生成，一个命令卡死整个服务器
- **落在岩浆里**：只看「第一个固体」会选到岩浆表面
- **卡在方块内**：只检查脚下没检查玩家体积

**正确做法**（四条同时满足）：

1. 扫描前先 `world.isChunkLoaded(chunkX, chunkZ)`，**未加载直接放弃并告知玩家**，绝不触发生成
2. 扫描**有界**：`for (y = min(startY + 64, maxHeight-1) downTo minY)`，且设定步数上限
3. 落点需同时满足：脚下方块 `isSolid`、**身体所在方块 `isPassable`**、脚下不是危险方块
4. 找不到就**明确失败并提示**，不静默留在原地

### 4.2 预热（warmup）被误取消

**错误写法**：监听 `PlayerMoveEvent` 看到 `from != to` 就取消。

会出的 bug：玩家**只是转头**（视角变化不产生 `to` 变化，但某些情况会）、
或者被水流推动 0.01 格就被取消，玩家永远传不出去。

**正确做法**：只比较**方块坐标**：

```kotlin
if (from.blockX != to.blockX || from.blockY != to.blockY || from.blockZ != to.blockZ) cancel()
```

**并且**：`PlayerTeleportEvent extends PlayerMoveEvent`，所以别人的传送也会触发 MoveEvent ——
必须排除由自己发起的传送，否则会自己取消自己。

### 4.3 预热状态泄漏

- 玩家中途退出 → 定时任务仍在队列里 → 对已离线的 Player 操作
- context 被停用 → 玩家身上还挂着「预热中」
- 同一玩家连发两次 → 两个定时任务竞争

**正确做法**：预热状态存 `Map<UUID, Pending>`；`quit` 事件、`close()`、重复发起
三种情况都要显式清理；发起新预热前先取消旧的。

### 4.4 无敌状态恢复错误

**错误写法**：`setInvulnerable(true)` … 结束后 `setInvulnerable(false)`。

会出的 bug：把**本来就无敌**的玩家（创造模式 OP）变成可被伤害。

**正确做法**：进入前 `val was = player.isInvulnerable`，结束时**恢复到 `was`**，不是硬编码 `false`。

### 4.5 冷却表泄漏

`Map<UUID, Instant>` 若不在 `quit` 时清理，离线玩家的条目会永久驻留。
**做法**：`quit` 里移除；或定期清理已过期条目。

### 4.6 如果 spawn 收费：重复扣款

**错误写法**：`economy.withdraw(uuid, amount)` 然后传送。

会出的 bug：传送被其他插件取消 / 预热被打断 / 传送失败 → **钱扣了人没动**。
项目里 `Error.transfer_pending` 这类错误码就是为托管设计的。

**正确做法**：完全复用 TPA 的 `reserveTeleport` → 执行 → `settleTeleport(success)` 三段式。
但 `teleport_charges` 目前按 `teleport` 语义设计，接入 spawn 需要**新增一个用途**，
属于对领域层的扩展，**不是纯 UI 改动**，成本和风险都要重新评估。

### 4.7 其他

- 死亡 / 创造 / 观战模式下的传送要有明确策略（建议直接拒绝并提示）
- 跨世界传送时目标世界未加载
- 同一玩家同时触发 `/spawn` 与 `/tpa`，两个 context 争抢

---

## 5. 已确定的范围

| 决定 | 选择 |
|---|---|
|---|---|
| 功能范围 | **A** —— 只 `/spawn` + `/setspawn`（`/home`、`/back` 未做，需另建持久化表） |
| 是否收费 | **免费** —— 不触碰经济领域层 |
| 安全深度 | **完整** —— 预热+移动/受伤取消、冷却、安全落点、传送后无敌、方块中心对齐 |

---

## 6. 实际实施结果

- **范围 A**：`/spawn`、`/setspawn`（`meowo.spawn.set` 默认 OP），不引入玩家持久化数据
- **免费**：与主流服一致
- **完整安全**：预热 3 秒、冷却 5 秒、有界安全落点扫描、传送后无敌 4 秒、方块中心对齐
- 全部保护项写进 `config.yml` 的 `spawn:` 段，可关可调
- 状态完全存于 context 内存，`close()` 与 `quit` 双清理

代码位置：

| 文件 | 职责 |
|---|---|
| `platform/SafeDestination.kt` | 安全落点扫描（纯 Bukkit 机制，无 context 知识） |
| `contexts/meowo-spawn/SpawnContext.kt` | 命令、预热、冷却、无敌、事件 |
| `contexts/meowo-spawn/SpawnSettings.kt` | 配置与取值范围校验 |
| 发言者 | `WarpCat`（`speaker-spawn` 键。原名 `HomeCat` 与 `home` 上下文的 `NestCat` 语义撞车；且 `SpawnCat` 是 5 字母前缀，会破坏「4 字母 + `Cat`」的聊天栏等宽规则，故取同义的 `Warp`——`/spawn` 本就是把人送回一个固定传送点） |

### 实施中与调研预测不一致的地方

- **`Block.isPassable()` 换成 `Material.isSolid()`。** 语义等价（不能站在实心方块里），
  但 `BlockMock.isPassable()` 在 MockBukkit 4.116.1 是**未实现的桩**，会抛
  `UnimplementedOperationException`，导致整段逻辑无法单测。详见 [08 §19](08-坑与反模式.md)。
- **`WorldMock.getSpawnLocation()` 也未实现**（新世界未设出生点时）。
- **`PlayerCooldownEvent` 确认不存在**，与调研一致，冷却自己用 `Map<UUID, Long>` 实现。

- **范围 A**：`/spawn`、`/setspawn`（OP），不引入玩家持久化数据
- **免费**：与主流服一致，回城不该收费
- **完整安全**：预热 3 秒（移动/受伤取消）+ 冷却 5 秒 + 有界安全落点扫描 + 传送后短暂无敌
- 全部保护项写进 `config.yml`，可关可调
- 状态完全存于 context 内存，`close()` 与 `quit` 双清理
- 测试覆盖：预热取消、冷却拒绝、安全落点各分支、关停后不再执行、**领地内不触发区块生成**
