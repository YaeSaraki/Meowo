# 12 · 坐下与骑乘（`/sit` `/ride`）

> **状态：已实施。** 全新 context `sit`，同时提供坐下与骑乘。
> 命名跟随子命令，context id = `sit`，发言者 `SitCat`。

---

## 1. ⚠️ 最重要的坑：Paper 26.2 没有 `startRiding`

逐类核实 `paper-api-26.2.build.129-stable.jar`：

| API | 1.21 时代 | **26.2 实测** |
|---|---|---|
| `Entity#startRiding(Entity, boolean)` | ✅ | ❌ **不存在** |
| `Entity#stopRiding()` | ✅ | ❌ **不存在** |
| `Entity#addPassenger(Entity)` | ✅ | ✅ |
| `Entity#removePassenger(Entity)` | ✅ | ✅ |
| `Entity#eject()` / `leaveVehicle()` | ✅ | ✅ |
| `Entity#getVehicle()` / `isInsideVehicle()` | ✅ | ✅ |

```
$ javap -p org/bukkit/entity/Entity.class | grep -c startRiding
0        # Entity / LivingEntity / Player 全部为 0
```

**所有 1.21 时代的骑乘教程在 26.2 都编译不过。** 本项目红线
[01 §9](01-架构红线.md) 说的「不要按 plan.md 的猜测编码」在这里是硬性要求——
这类「教程与版本不符」的坑比猜错参数更隐蔽，因为它**看起来完全正确**。

事件仍然存在，可以放心用：`VehicleEnterEvent` / `VehicleExitEvent` /
`EntityMountEvent` / `EntityDismountEvent`。

### 方向别搞反

```kotlin
stand.addPassenger(player)   // ✅ player 坐在 stand 上
// 不是 player.addPassenger(stand)
```

`X.addPassenger(Y)` 的意思是「**Y 骑在 X 上**」。

---

## 2. 方案来源

调研了 SitPL、EssentialsX Sit Addon、SitOnEverything，**坐下**的主流做法高度一致：

| 步骤 | 做法 |
|---|---|
| 1 | 在玩家脚下生成一个盔甲架 |
| 2 | 设为 `invisible` + `gravity=false` + `invulnerable` + `silent` + `small` + `marker` |
| 3 | `stand.addPassenger(player)` |
| 4 | 移除盔甲架 → 客户端自动脱离 |
| 5 | 潜行或再执行命令时移除；退出与 context 关闭时清理 |

**关键设计：只用「我们自己的盔甲架」当座位。**
骑乘别人的玩家时那个载具不属于我们，**绝不能 `remove()`**——
所以内部只维护 `seats: Map<UUID, ArmorStand>`，管得住的才管。

---

## 2.5 座位不是「哪里都能坐」：五条拒绝规则

`/sit` 只需要脚下有地面，但**右键方块坐下**要判断得多，因为这一类插件的通病是
「椅子被埋进方块里」「隔空坐下」「两个人抢同一把椅子」。

`SitContext.seatRefusal` 返回语言键或 `null`，每条都有独立提示，绝不静默失败：

| 拒绝 | 键 | 为什么 |
|---|---|---|
| 离得太远 | `seat-too-far` | 别的插件会把交互距离调得远超原版 ~4.5 格，房间对面的椅子会变成陷阱 |
| 上方被挡 | `seat-blocked` | 头顶是实心方块，坐下去人就在方块里 |
| 上下颠倒的楼梯 | `seat-stairs-flipped` | 那是个天花板搁板，不是座位 |
| 台阶朝向反了 | `seat-stairs-backwards` | 椅子会埋进楼梯的实心半边 |
| 位置已被占 | `seat-taken` | 两个人抢同一把椅子 |

距离用 `sit.max-distance`（1..8，默认 4），走的是**平方距离**比较，
所以配置和判定都是单调的，不用开方。

### 楼梯要站对边

楼梯的 `facing` 指向它的**实心半边**，可坐的台阶在**对面**，所以椅子要往
`facing.oppositeFace` 方向挪（`stepSideOf`）。玩家从实心那半边点，会被
`seat-stairs-backwards` 拒绝；判定留了 `0.3` 的死区，避免正上方或斜着点时
被一个接近 0 的点积误判。

> ⚠️ **`seatAt` 用 `EPSILON`（平方距离 `1e-6`）判「同一个位置」**，不是 `Location#equals`。
> `Location#equals` 比较的是**精确的 x/y/z/yaw/pitch**，浮点加法让两个逻辑上相同
> 的座位算出来不等，于是两个人能坐进同一把椅子。1e-6 的平方距离 = 1 毫米。

### ⚠️ MockBukkit 的世界默认是实心的

`MockBukkit` 里**没有被设置过的方块是 `GRASS_BLOCK`，不是 `AIR`**。
所以任何「头顶要有净空」的检查在测试里**永远拒绝**，除非测试显式把那块设成空气：

```kotlin
// 真实服上这块是空气；MockBukkit 上必须写出来，否则 seat-blocked 会永远命中
world.getBlockAt(location).type = Material.AIR
```

这是 `MeowoRuntimeTest` 里 `clearSeatRoom` 存在的原因。写任何带空间检查的测试时都要记得。

## 3. 空中防护

没有地面检查的话，玩家可以在半空生成一个盔甲架并坐上去 = **免费悬停飞行**。
所以 `toggleSit` 先要求脚下有可站立的方块：

```kotlin
val below = player.location.block.getRelative(0, -1, 0)
return below.type.isSolid || below.isLiquid
```

> 用 `Material.isSolid()` 而不是 `Block.isPassable()`——后者在 MockBukkit 4.116.1
> 是**未实现的桩**，会让整段逻辑静默 skipped，见 [08 §19](08-坑与反模式.md)。
> 同一份文档的 [10 §6](10-spawn调研.md) 已经踩过一次，这里直接沿用结论。

---

## 4. 骑乘权限

| 目标 | 权限 | 默认 |
|---|---|---|
| 附近玩家 | `meowo.ride` | 所有人 |

玩家名走 `Bukkit.getPlayerExact`。补全和执行共用合法性检查：同世界、在线存活且可见，水平 3×3 区域（各轴 ±1.5 格），高度差也不超过 1.5 格。旧 `sit.target-range` 大于 3 时封顶，不允许管理员绕过；不再骑乘生物。

目标已有乘客时，沿单一玩家乘客链向上找到空位；每层都需合法。拒绝非玩家、分支、循环、已在链上的玩家与旁观者。无 `meowo.ride` 权限时命令路由报错，且不显示补全。详见 [当前模块说明](../../contexts/meowo-sit/README.md)。

**骑乘不消耗任何经济资源**，与 `/spawn` 一样免费——它只是个位置操作，
不值得引入托管计费的复杂度（[10 §4.6](10-spawn调研.md) 说明了那要付出什么代价）。

---

## 5. 生命周期

`close()` 的顺序是**先让乘客下车，再移除盔甲架**：

```kotlin
seats.keys.toList().forEach { id ->
    Bukkit.getPlayer(id)?.leaveVehicle()
    seats.remove(id)?.remove()
}
```

`quit` 事件同样清理，否则下线的玩家会把盔甲架留在世界里。
这两处都写进了测试，**`BUILD SUCCESSFUL` 不足以保证没有泄漏**——
`seats(player)` 断言的是世界里盔甲架的真实数量。

---

## 6. 命令面

全部子命令在 `/` 下都有等价入口，**`/help` 与 `/reload` 除外**：

| 命令 | 说明 |
|---|---|
| `/info` `/eco` `/menu` `/render` `/spawn` `/setspawn` `/sit` `/ride` | 根级直接可用 |
| `/pay` `/tpa` `/tpaccept` `/tpdeny` `/tpacancel` | 原有别名 |
| `/meo [子命令]` | `/meowo` 的短别名（`plugin.yml` 里是独立命令，`prefix = []`） |
| `/meowo help` `/meowo reload` `/mw …` | 仅在插件前缀下 |

> **为什么 `/help` 不下沉**：Bukkit 里同名插件命令会**覆盖**原版 `/help`，
> 玩家输 `/help` 看到的是插件帮助而不是服务器完整命令列表。
> **`/reload`** 则是服务器管理插件的常规领地。
> 两者都保留在 `/meowo` 与 `/meo` 后面，并由测试断言
> `plugin.getCommand("help") == null`——防止以后有人「顺手补全」。

机制见 [05 §3](05-改造手册.md) 的 `aliases` map：
根级命令的 prefix 就是**子命令自己的名字**，所以 `/eco balance` 与
`/meowo eco balance` 命中同一条 route。

---

## 7. 排障

| 现象 | 原因 | 修法 |
|---|---|---|
| `/sit` 说「没有可坐的地面」 | 脚下是空气/非固体 | 设计如此，空中禁止 |
| `/ride Cow` 找不到 | 仅支持玩家目标 | 使用附近玩家名 |
| `/ride Nobody` 找不到 | 玩家不在线、不可见或超出 3×3 范围 | 靠近目标 |
| 世界里留下盔甲架 | 检查 `close()` / `quit` 是否被改动 | 见 §5 |
| `/sit` 没反应 | `contexts.sit: false` | `/meowo info` 看实际启用状态 |
| 编译报 `startRiding` 未解析 | 抄了 1.21 的教程 | 见 §1 |

## 8. 自查

```bash
# 1) 语言键对齐（两个文件必须完全一致）
comm -3 <(grep -oE '^[a-z0-9_-]+:' core/src/main/resources/lang/zh_CN.yml | tr -d ':' | sort) \
        <(grep -oE '^[a-z0-9_-]+:' core/src/main/resources/lang/en_US.yml | tr -d ':' | sort)
# 2) 新 context 已进全部门禁
grep -n '"sit"' build.gradle.kts
grep -n 'meowo-sit' settings.gradle.kts core/build.gradle.kts
# 3) 完整验证（先 clean，iCloud 环境铁律）
./gradlew clean && ./gradlew test :core:build verifyArchitecture verifyCoverage --no-daemon
```
