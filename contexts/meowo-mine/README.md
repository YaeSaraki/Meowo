# meowo-mine

稀有矿物首挖全服通报，发言者 `MineCat`。`/meowo mine`（根级 `/mine`）查询玩家统计。

## 通报什么

| 矿物 | 方块 |
|---|---|
| 钻石 | `DIAMOND_ORE`、`DEEPSLATE_DIAMOND_ORE` |
| 绿宝石 | `EMERALD_ORE`、`DEEPSLATE_EMERALD_ORE` |
| 下界合金 | `ANCIENT_DEBRIS` |

> ⚠️ **本版本没有 `NETHERITE_ORE` 方块。** 26.2 的 `Material` 里根本不存在这个常量
> （已逐项核实），写下它编译不过。下界合金的实际来源方块是**下界古残骸**
> `ANCIENT_DEBRIS`，掉落下界合金碎片。`MineContextTest` 里有一条测试专门断言
> `Material` 中不存在 `NETHERITE_ORE`，将来 Paper 若加了这个方块，测试会失败提醒
> 重新评估，而不是让它悄悄生效。

## 「只对没有被挖掘过的矿物有效」

这是核心规则，实现成一个**纯值对象** `DiscoveryLedger`：

```
DiscoveryLedger(record 钻石 1) → 通报
DiscoveryLedger(record 钻石 1) → null，不通报
```

一旦某种矿物被通报过，它就不会再通报第二次。三种矿物各通报一次，互不影响。

### 两种范围

| `mine.scope` | 行为 |
|---|---|
| `server`（默认） | 每种矿物**全服只通报第一次**，给第一个挖到的人 —— 首挖礼 |
| `player` | 每个玩家每种矿物**各通报一次**，人人有份 |

写规则时把「矿物本身」当主语（`只对没有被挖掘过的矿物有效`），所以默认取 `server`。
若你的本意是「每人每种都通报一次」，把配置改成 `player` 即可，**不用改代码**。

## 结构

```
mine/
  MineContext.kt                Context 契约、BlockBreak 监听、广播、清理
  MineSettings.kt               配置
  domain/RareKind.kt            稀有矿物种类（纯枚举）
  domain/DiscoveryLedger.kt     首挖判定（纯值对象）
```

`domain` 两个文件都不依赖 Bukkit，所以「只通报没被挖过的」这条规则**不需要服务器就能证明**——
这正是把它从 `MineContext` 里拆出来的原因。

## 材质映射写成显式 when

```kotlin
Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE -> RareKind.DIAMOND
Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE -> RareKind.EMERALD
Material.ANCIENT_DEBRIS -> RareKind.NETHERITE
```

没有用「名字以 `_ORE` 结尾」这种正则：它会**误匹配 `LEGACY_DIAMOND_ORE`**（已废弃的
旧版材质），而且 Paper 一旦改名就静默失效。`MineContextTest` 对 `LEGACY_*` 有专门断言。

## 落盘：一个世界的首挖只发生一次

`infrastructure/MineLedger`（SQLite，`plugins/Meowo/mine/discovery.db`）：

```sql
CREATE TABLE IF NOT EXISTS rare_discovery (
  world   TEXT NOT NULL,     -- 分世界，换世界不串账
  kind    TEXT NOT NULL,     -- 枚举名
  player  TEXT NOT NULL,     -- '' = server scope，否则是 UUID
  amount  INTEGER NOT NULL CHECK(amount > 0),
  seen_at INTEGER NOT NULL,
  PRIMARY KEY (world, kind, player)   -- 幂等 upsert 的依据
);
```

`player` 列用「空串 / UUID」区分两种 scope，**一张表服务两种配置**。

### 关键：数据库是缓存，不是查询路径

| 时机 | 动作 | 频率 |
|---|---|---|
| `start()` | 一次 `SELECT` 读进内存 `DiscoveryLedger`（**异步**） | 启动 1 次 |
| `BlockBreakEvent` | `kind in announced`，**纯内存，零 I/O** | 每挖一块，O(1) |
| 命中首挖 | 一次 `INSERT ... ON CONFLICT DO UPDATE`（异步） | **每世界每矿物一辈子 1 次** |

如果改成每次破坏方块都查一次库，`BlockBreakEvent` 是全服最高频事件，
20 人速挖 ≈ 每秒 200 个任务灌进 economy 的单线程 worker（有界队列 1024 + AbortPolicy）
→ 队列积压 → 返回 `UNAVAILABLE`。**这正是把它降级成缓存的原因。**

### 加载完成前不通报

`hydrated` 门控：读取是异步的（红线禁止 tick 线程 JDBC），但**在读到之前不通报**，
否则玩家在这个窗口里挖到钻石会被误报成首挖。

### 独立的库文件

不挂到 economy 的 `wallets.db` 上——那会让两个 context 产生实现耦合，
违反 `01-架构红线 §2`。建表用 `IF NOT EXISTS`，升级不破坏已有记录（`05 §6`）。

### 已知的两个缺口（落盘也解决不了）

- **其他插件挖的**（WorldEdit、快速挖矿、爆炸）不触发 `BlockBreakEvent`，不计入
- **插件启用前挖过的**不计入（除非那段时间也是本插件在跑）

## 配置

```yaml
mine:
  announce: true     # 关闭后 /meowo mine 仍可查询
  sound: true
  scope: server      # server | player
contexts:
  mine: true
```

## 命令与权限

| 命令 | 权限 | 默认 | 行为 |
|---|---|---|---|
| `/meowo mine` / `/mine` | `meowo.mine` | 所有人 | 查询自己的统计；`/mine <玩家>` 查他人；控制台需带名字 |

## 测试

`DiscoveryLedgerTest`（7，内存规则）+ `MineContextTest`（8，材质映射）+ `MineLedgerTest`（8，真实 SQLite）= 23 个纯 JUnit，`skipped=0`。

内存规则：

- 首挖通报 / 重复不通报 / 三种矿物互不影响
- 数量 ≤ 0 不算发现，也不消耗该矿物的「首挖」资格
- 重复挖掘不会虚增累计数
- 钻石与深板岩钻石算同一种；`LEGACY_DIAMOND_ORE` 不算
- `DIAMOND`（物品）、`NETHERITE_BLOCK`、`NETHERITE_SCRAP` 都不是被挖的方块
- 红石矿、金矿、石头、原木等普通方块不通报
- 每种 `RareKind` 都有至少一个方块映射到它
- 断言 `Material` 中不存在 `NETHERITE_ORE`

持久化（真实 SQLite，`@TempDir`）：

- 记录后**重开文件**仍然算已发现——这正是落盘的意义
- 每个世界各自独立，`world_nether` 不影响 `world`
- server scope 与 player scope 互不可见，第三个玩家仍然是新的
- 重复记录同一矿物**累加数量**而不是产生新行（主键保证）
- 数据库里出现未知 `kind`（未来版本写入）时**跳过该行**而不是加载失败
- `amount <= 0` 在进表前就被拒绝，不留脏行
- 反复开关不丢数据（`IF NOT EXISTS`）

domain 层的 15 个用例在落盘前后**完全不变**——因为 `DiscoveryLedger` 从头到尾都是
纯内存值对象，数据库只是给它灌了个初始值。