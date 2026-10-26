# meowo-nametag

自定义名牌：玩家名称后加一个小圆点，挂机时深黄、正常时浅绿。

## 结构

```
contexts/meowo-nametag/src/main/kotlin/com/meowo/nametag/
  NametagContext.kt            Context 契约：事件、/afk 命令、清理
  NametagSettings.kt           配置与取值范围校验
  domain/IdleState.kt          纯 Kotlin：挂机状态（就是那个「是否挂机」的变量）
  domain/MarkerStyle.kt        纯 Kotlin：挂机 / 正常对应的圆点样式
  application/NametagService.kt  应用名牌、按需刷新、退出与停用时还原
```

`domain` 两个类都不依赖 Bukkit，所以挂机规则和配色都能直接单测，不需要服务器。

## 为什么不用发包

先查过 PacketEvents 2.14.0：`EntityDataTypes` 里**没有** `NAMEPLATE` / `CUSTOM_NAME` /
`NAMEPLATE_VISIBLE`。26.2 的名牌已经迁移到 DataComponent，走实体元数据这条路不通。

Paper 直接提供了原生接口：

```kotlin
player.customName(component)        // Nameable
player.isCustomNameVisible = true   // Entity
```

服务端告诉所有客户端画什么即可，**完全不需要发包**。这也带来一个简化：圆点是
per-player 而不是 per-viewer——每个人的挂机状态对所有人是一样的，没必要逐观察者发包。

## 挂机判定

`IdleState` 只记录「上次移动的时间戳」和阈值：

| 输入 | 行为 |
|---|---|
| 移动（**换方块**才算） | 清零，并清除 `/afk` 手动标记 |
| 时间到达阈值 | 变成 away |
| `/afk` | 手动 away，**不看时间**；再动一下就自动解除 |

按方块判定是刻意的：站着不动只转头的人**仍然算挂机**。同时也过滤掉了同一格内
每 tick 都有的移动事件，避免把静止误判成活动。

扫描由 context 自己的定时任务驱动（默认 1 秒一次），不是每 tick 跑。`NametagService`
只在圆点**真的翻转**时才去改名牌，避免每秒重发一次。

## 配色

| 状态 | 色值 | 说明 |
|---|---|---|
| 挂机 | `0xC9A063` | 低饱和的暖琥珀，**不是**警示黄 |
| 正常 | `0xA3D9A0` | 柔和浅绿，不是状态灯那种纯绿 |

两个色都刻意压过饱和度：全饱和的黄色读起来像警报，纯绿读起来像 LED，都不适合
长时间挂在别人头顶。`MarkerStyleTest` 把这条意图写成了断言（色相分离度落在 10..120），
将来谁换成刺眼的颜色，测试会直接红掉。

圆点用 `•` 并前置一个空格，和名称之间留出间隙不会粘连。名称和圆点都显式关闭斜体，
因为原版名牌默认是斜体，不关掉的话启用本功能会顺带改变玩家名字的观感。

## 潜行时的可见性

原版在玩家潜行时会隐藏名牌。强制显示等于在 PvP 里泄露蹲守玩家的位置，所以
`refreshVisibility` 让名牌跟着潜行一起隐藏 / 显示。

## 清理

名牌是**服务端状态**，不是客户端特效，所以：

- 玩家退出时 `customName(null)` + 取消可见性，避免重连继承到旧圆点；
- context 关闭时对所有在线玩家做同样的还原 —— 否则停用模块会留下一片改不回去的名牌；
- 改 `nametag.*` 配置会触发 context 重启（见 `MeowoRuntime.applySettings`）。

## 配置

```yaml
nametag:
  enabled: true
  afk-after-seconds: 60      # 静止多少秒算挂机，构造时限制 1..3600
  check-interval-seconds: 1  # 扫描间隔，构造时限制 1..60
contexts:
  nametag: true
```

## 命令与权限

| 命令 | 权限 | 默认 | 行为 |
|---|---|---|---|
| `/meowo afk` / `/afk` | `meowo.nametag.afk` | 所有人 | 切换自己的 away 标记，再动一下自动解除 |
| — | `meowo.nametag` | 所有人 | 保留给未来的按玩家开关 |

context id 为 `nametag`，发言者是 `IdleCat`（4 字符 + `Cat`），语言键前缀 `nametag-`。
