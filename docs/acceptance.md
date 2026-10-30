# 验收记录

> ⚠️ **本页是 2026-10-04 的历史快照，已过期。**
> 下面的 28 tests / 83.93% 停留在加入 `teleport` 之前。
> **当前基线见 [minimax/README.md 的「当前基线」一节](minimax/README.md#当前基线)** ——
> 测试数与覆盖率只在那里写一次，免得这里留一份会自己过期的数字。
> 保留本页是为了对照「当时验证过什么」，不要拿它判断现状。

验证日期：2026-10-04（Asia/Shanghai）。原始 plan 的例子已按真实 API 修正；本页区分自动化、真实服务端和未执行的图形客户端验收。

## 自动化结果

命令：

```bash
./gradlew test :core:build verifyArchitecture verifyCoverage --no-daemon
```

**BUILD SUCCESSFUL；28 tests，0 failure，0 error，0 skipped。**

| 项目 | 结果 | 证据 |
|---|---|---|
| API / shared 无 Bukkit 源码引用，context 不直接导入其他 context | 通过 | `verifyArchitecture`、[实际依赖树](evidence/pure-dependencies.log) |
| Money 精度、范围、溢出；Wallet 存取设置与身份 | 通过 | 9 项 domain 测试 |
| 事件顺序、退订、并发发布、订阅失败隔离 | 通过 | 4 项 EventBus 测试 |
| context 启动失败回滚、逆序停用 | 通过 | 2 项 ContextManager 测试 |
| 异步经济服务、提交后事件、并发存款、关闭超时 | 通过 | 3 项 ApplicationService 测试 |
| SQLite 重开持久化、流水失败回滚、两连接并发 | 通过 | 3 项 Repository 测试 |
| 命令、权限、默认用户菜单、语言切换、模块停用、非法配置保留 | 通过 | 5 项 MockBukkit 集成测试 |
| 菜单点击取物、shift-click、快捷栏交换和拖动保护 | 通过 | 同上 |
| 假实体数量上限、失败回滚、关闭和退出清理 | 通过 | 2 项 RenderService 测试（fake renderer） |
| 总体行覆盖率 | **83.93%**（350 / 417） | 聚合 JaCoCo |
| Economy domain 行覆盖率 / 分支覆盖率 | **100% / 100%** | Domain JaCoCo |
| fat jar 内含 contexts / Kotlin / SQLite / TabooLib 引导 | 通过 | jar 内容检查、真实加载 |
| 三个 shell 脚本语法 | 通过 | `bash -n scripts/*.sh` |

[构建日志](evidence/build.log) · [机器可读测试统计及 jar SHA-256](evidence/verification.json)

本次 MockBukkit 4.116.1（原声明 Paper build 111）在显式 Paper build 129 测试依赖上通过。测试夹具显式加载本项目 plugin.yml，并补充 MockBukkit 未自动执行的描述文件权限注册；没有跳过不支持的方法或将失败测试替换为空断言。

## 真实 Paper 26.2 build 129

已获用户授权在本机测试服接受 EULA。Java 25，`127.0.0.1:25565`，PacketEvents 2.14.0。测试服已验证正常启动及 `stop` 关闭。最后观测到本地玩家连接，因此交付时重新启动并保持运行，便于继续客户端验收。

- [x] 加载 Meowo，实际 context 为 `[economy, menu, render]`。
- [x] `/meowo help`、`info` 输出正常；正常状态事件订阅数为 2。
- [x] 真实玩家入服后自动创建零余额钱包，已用只读 SQL 确认。
- [x] 控制台对测试 UUID `00000000-0000-0000-0000-000000000001` 执行 set 0、give 100、take 20.50，查询为 **79.50**。
- [x] take 1000 被拒绝，提示余额不足。
- [x] locale 切为 en_US，reload 后提示和 info 变为英文。
- [x] 正常关闭服务器并重启，余额仍为 **79.50**。
- [x] 停用 economy/render 后仅剩 menu，订阅数为 1，eco 路由不可调用。
- [x] 再次启用并切回中文，恢复三个模块和 2 个订阅。
- [x] 最终 jar 再次真实启动、查询与正常关闭（见最终运行日志）。

[重启与模块切换日志](evidence/server-restart.log) · [最终 jar 运行日志](evidence/server-final.log)

第一次建服发现空的超平坦生成配置会导致 Paper 报错，已在 `scripts/server.properties` 补齐 layers/biome；后续启动未再出现。第一次启动时 Paper 自身的远程版本检查曾网络超时，Meowo 正常运行；后续重启日志没有 ERROR。第三方运行时会输出 Java Unsafe/native-access 及 Gradle 弃用警告，当前不影响测试或启动，不宣称日志完全无警告。

## 仍需图形客户端人工验收

以下不作“已通过”声明：

- [ ] 配置 Prism Launcher 的 Java 26.2 实例与已有正版账号，验证 `start-client.sh` 自动直连、`dev.sh` 双开。
- [ ] `/meowo menu`：实际客户端显示金币图标、余额和刷新后的内容。
- [ ] `/meowo render spawn 10`：实际客户端可见 10 个盔甲架，其他玩家不可见。
- [ ] `/meowo render clear`、传送、复活、模块停用后，客户端无残留实体。
- [ ] 与其他使用假实体的插件共同运行时检查实体 ID 分配冲突。

本次未操作图形客户端，也未取得启动器实例路径，故未生成游戏截图或验证自动直连脚本。最终日志中已观测到本地玩家入服，但尚未完成菜单与渲染交互验收。PacketEvents 模块真实加载已验证，但纯 RenderService 测试不能替代网络包编码及客户端可见性测试。曾检查可用协议测试库，其稳定版未提供 26.2 数据，未以不匹配版本的结果冒充客户端验收。

人工路径：启动测试服 → 26.2 客户端加入 → 给测试账号 OP → 按上列操作 → 保存截图 → 输入 `stop`。`/minecraft:list` 统计玩家而非实体，不能用作“没有生成服务端实体”的依据。

## 2026-10-05：移动退款、支付税与传送魔法特效

`./gradlew clean test :core:build verifyArchitecture verifyCoverage --no-daemon` 通过。共 92 项测试，0 failure / error / skipped；总行覆盖率 90.43%，economy domain 行覆盖率 94.74%。

新增验证涵盖支付本金加 1% 税、税收流水失败时双方钱包回滚、申请者税额加支付税的垫付预扣与结算、移动/跳跃取消后退回原付款人、旧库迁移与重启退款，以及旋转符咒几何、六芒星六个顶点、实际目的地群系颜色与法阵固定位置。已备份本地钱包及旧插件并更新测试服插件包，尚未重启正在运行的服务器。MockBukkit 对粒子参数和位置的验证不代表已完成客户端视觉验收。

[本次构建日志](evidence/teleport-magic-build.log) · [测试统计与包校验](evidence/teleport-magic-verification.json)

## 2026-10-08：飞行权限有两条线（撤销 0 / 恢复 1），中间是回复期

全量实测：**1076 项测试，0 failure / 0 error / 0 skipped**；总行覆盖率 **82.88%**，
economy domain **94.74%**；`verifyArchitecture` 与 `verifyCoverage` 通过；产物
`core/build/libs/Meowo-1.0.0.jar`。

- **撤除线 0、恢复线 1，中间是回复期。** 蓝量降到 0（含）才撤掉 `allowFlight`；
  回到 1.0 且**站在地上**才还回来。0～1 之间什么都不做：玩家就是普通玩家，
  飞不了、也不会因为蓝回升一点就立刻拿回权限。
- **这段间隔是摔伤生效的前提。** 只设一条线的话，蓝量回升会立刻把权限还回去，
  刚掉下来的人会在落地前被接住，本该落下的摔伤永远不会发生。间隔就是他"还在恢复、
  该摔还得摔"的时间。
- **撤销线在扣费两侧各查一次**：扣费前查，不从已经空了的蓝条里扣钱；扣费后查，
  抓住"这一 tick 正好把最后一点花光"。少查一侧都会漏（只查前侧：0.02 蓝能买到一 tick
  却不掉权限；只查后侧：空蓝还先被扣一次）。
- **摔落伤害仍不由本项目生成**：撤掉权限就撤掉了原版的 `mayfly` 豁免，服务端自己结算。
  `setFlyingFallDamage` 在 26.2 上无效这一点见上一节。
  （本节记录的是 2026-10-08 当时的验收；`/fly` 已整体移除。）
- 计费条件仍是 `Player#isFlying`：跳跃与坠落不计费，只有飞行中的 tick 收 0.02。

`FlyContextTest`（17 项）：空蓝撤权限且不扣费、**带内保留已持有的权限**、**带内不给回权限**
（0.01/0.5/0.99 逐个断言）、扣空那一 tick 撤权限、落地到恢复线才还回来、空中不恢复、
恢复后能再飞并重新计费、跳跃不计费、粒子只在真在飞时画、死亡/退出/关闭 context 都收回权限。

> **覆盖率从 84.78% 变成 82.88% 不是本轮造成的**：工作区里同时有人加了新 context
> `meowo-invsee`（`settings.gradle.kts` / `build.gradle.kts` 已接线，`InvseeContext` 145 行未覆盖），
> 它计入总体分母。fly 相关模块的覆盖率没有下降，门禁照常通过。

**未做真服与客户端验收，摔落伤害尤其需要真人验收。** MockBukkit 既没有原版的免伤逻辑、
也没有落地结算，"掉了多少血"无法在这里断言——测试钉的是**标志位与两条线**，
而那正是摔伤能否回来的原因。

## 2026-10-08（已被上一节取代）：飞行改成「只在飞的时候计费」

> 这一轮的摔落伤害做法（`Player#setFlyingFallDamage(TriState.TRUE)`）**在 26.2 上不生效**，
> 权限模型也被上一节取代；保留记录以对照。

## 2026-10-08（历史）：飞行改成「只有一个价钱」（每 tick 0.02）

> 这一轮的计费条件与续作都已被上面两节取代，保留记录以对照。

`GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test :core:build verifyArchitecture verifyCoverage --offline --no-daemon --rerun-tasks` 全量通过：共 **1063** 项测试，0 failure / 0 error / **0 skipped**；总行覆盖率 **84.19%**，economy domain 行覆盖率 **94.74%**；`verifyArchitecture` 通过，产物 `core/build/libs/Meowo-1.0.0.jar`（约 3.0 MB）。

**一条与本次改动无关的既有不稳定用例**：`meowo-agents` 的 `ProactiveLaneTest.the greeting opens again once the cooldown has passed` 在**并行全量**运行时失败（三次全量运行三次失败，均为同一断言），单独运行该模块 420 项全绿。该 context 对 fly 模块没有任何依赖，断言内容与飞行无关。上面的 1063 是把该模块单独跑通过（420）+ 其余模块全量跑通过（637）+ `shared`（6）相加得到的；**这条 flake 未修，留给 agents 一侧单独处理**。

改的是计价模型，不是粒子：

- **删掉起跳费 1.0。** 旧模型对**每一次**离地收 1.0，于是「落地再起飞」被反复计费。现在唯一的价钱是每 tick 0.02。
- **没蓝 = 飞不起来，而不是关飞行。** 空中扣不动就摘下 `allowFlight` 让玩家掉下去，飞名单和命令状态不变，落地恢复。
- **只有真正在空中才画粒子。**

新增 `FlyContextTest`（11 项）。

> 本节是 2026-10-08 那一轮的历史记录，随 `/fly` 一起作废：`FlyContextTest` 与它那份
> `evidence/fly-mana-hysteresis-verification.json` 都已随 `meowo-fly` 模块删除。**当前基线见
> [minimax/README](minimax/README.md#当前基线)**。
