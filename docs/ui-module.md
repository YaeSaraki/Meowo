# UI 模块：放置位置与调用方式

## 结论

新建**独立模块 `ui/`**（与 `platform/`、`api/` 平级），而不是把 UI 塞进 `platform`。跨模块调用分两条路：

| 方向 | 机制 | 用于 |
|---|---|---|
| `ui` → 其他模块 | **指令**（`player.performCommand("pomodoro start")`） | 按钮被点击后要执行的功能 |
| 其他模块 → `ui` | **`api/UIAPI` 服务**（`ServicesManager`） | 模块把自己的界面交给 `ui` 打开 |

配置全部落在 config：`ui.yml` 里逐个界面声明 `title / rows / slot / icon / name / lore / action`。

## 为什么是独立模块，而不是 platform

`platform` 现在承担的是**宿主契约**：`PlatformHost`（主线程、指令注册、消息）、`Messages`、`CommandRouter`，外加 `MenuChrome` 这份不依赖菜单框架的"房子风格"。它的测试全部不需要服务端，这是刻意维持的性质——`verifyArchitecture` 也在守着模块纯度。

UI 模块必须依赖 TabooLib（`buildMenu` / `PageableChest` / `ClickEvent`）。把这份依赖放进 `platform`，等于把"宿主契约"和"菜单框架"绑死：任何只想发消息、注册指令的模块都会被拖上 TabooLib 的传递依赖，platform 的单测也不再能脱离菜单框架跑。

分开放之后：

- `platform`：宿主契约 + 风格常量（`MenuChrome`）。不引入 TabooLib。
- `ui`：唯一的菜单框架集成点 + 配置模型 + 动作派发。
- `api`：`UIAPI`，让模块把界面"递"给 `ui`，模块之间仍然互不 import。

## 调用方式

### 1. `ui` → 其他模块：指令

配置里一个按钮的 `action` 就是一条指令；`ui` 用 `player.performCommand(...)` 执行，权限照常生效：

```yaml
screens:
  pomodoro:
    title: "&d◆ 番茄钟"
    rows: 6
    buttons:
      10: { icon: AMETHYST_CLUSTER, name: "开始专注", lore: ["挑一个预设开始"], action: "pomodoro start" }
      11: { icon: REDSTONE_TORCH,  name: "暂停",     lore: ["停表，回来再继续"], action: "pomodoro pause" }
      46: { icon: PURPLE_STAINED_GLASS_PANE, name: "返回", action: "@back" }
      45: { icon: BEDROCK, name: "主菜单", action: "@main-menu" }
```

`@` 前缀是 `ui` 自己实现的内置动作（`@close` / `@back` / `@main-menu` / `@page-prev` / `@page-next`），不经过指令。

好处：**功能可改**（换成别的指令即可）、**图标可改**、**位置可改**，都不用改代码；模块之间只通过指令名耦合，符合"互不 import"的架构约束；玩家权限与指令一致，不会出现"菜单能点、指令不能跑"的错位。

### 手势与参数

一个格子不止一种动作，配置里写全：

```yaml
buttons:
  4: { icon: EMERALD, name: "新建", action: "pomodoro create",
       action-right: "pomodoro copy", action-shift: "@cancel" }
```

动态列表的格子无法写死指令（"开始**这个**预设"要带上哪一个），所以指令支持占位符，由元素填充：

```yaml
action: "pomodoro start {index}"     # {index} 全列表下标，{name} 元素名
```

`{index}` / `{name}` 由 `UICommand.fill` 在点击时替换；**未知占位符保持原样**而不是清空——清空会执行一条与被写下不同的指令，比看得见的拼写错误更糟。元素（模块在 `openList` 时交付）同样带 `action/right/shift`，所以廊、编辑器这类界面能整块落在 config 里。

按钮还可以写 `permission:`——缺权限的玩家**看不到这一格**（槽位留给边框），而不是看到一个点了才被拒绝的按钮。这与指令自身的权限检查是同一条规则，只是提前到点击之前。

### 迁移中留下的既有配置

菜单模块把布局从 `config.yml` 的 `menu.items` 移到 `ui.yml` 的 `screens.menu`。原先的两条"内建动作"（打开家园、读余额）本来就等价于指令，`/homes` 与 `/eco balance` 都已存在，所以整块行都能用 `action: "…"` 表达，不需要为它们发明新的动作类型。

### 按次打开的值表

有些指令需要模块才知道的数据，例如"当前展示的这个家园的编号"：配置里仍写完整的指令，数据由打开界面时传入：

```yaml
# ui.yml
10: { icon: ENDER_PEARL, name: home-travel, action: "home {slot}" }
```
```kotlin
ui.open(viewer, "home-edit", values = mapOf("slot" to home.slot.toString()))
```

这样动作依然归配置，模块只提供值——比"用一个覆盖把整格换掉"更符合本模块的目的。

### 2. 其他模块 → `ui`：`UIAPI` 服务

纯配置表达不了**动态内容**——预设列表来自 SQLite、家园列表来自玩家数据，槽位数量随数据变。所以 `api/UIAPI.kt`：

```kotlin
interface UIAPI {
    /** 按配置打开一个静态界面。 */
    fun open(player: Player, screen: String)
    /** 打开一个列表界面：槽位/图标/标题来自配置，元素由调用方按数据生成。 */
    fun openList(player: Player, screen: String, elements: List<ListElement>)

    data class ListElement(val icon: String, val name: String, val lore: List<String>, val action: String)
}
```

模块注册服务后，把数据交给 `ui`；`ui` 负责槽位、翻页、边框、点击派发。模块不需要知道 TabooLib，也不需要知道底行是哪几个槽位。

## 配置布局

一个文件按界面分节，`ui.yml`：

```yaml
screens:
  menu:            # 主菜单
  menu-homes:      # 家园总览（列表）
  home-detail:     # 家园详情
  pomodoro:        # 番茄钟柱殿
  pomodoro-gallery:  # 预设长廊（列表）
  pomodoro-editor:
  confirm:         # 通用确认页（标题/详情由调用方传入）
```

也可拆成 `ui/*.yml` 每界面一份——`ui` 启动时读整个目录。**推荐拆分**：单文件改动冲突少，符合"分别并单独放置"。

共有键（写在 `defaults` 节，界面可以覆盖）：

```yaml
defaults:
  rows: 6
  rail:                # 底行导航，所有界面共用一套语义
    plinth: [45, 53]   # 基岩底座 → 回主菜单
    glass: [46, 52]    # 玻璃 → 返回上一层 / 上一页
```

## 迁移顺序

1. 建 `ui/`，搬 `TabooPomodoroScreens` 的渲染与 `PomodoroIcons` 里的物品构造（内容与槽位一起进 `ui`）。
2. 加 `ui.yml`，把番茄钟四个界面先配置化（含动态长廊走 `UIAPI.openList`）。
3. 菜单与家园按同一套键配置化；`HomeContext.open` 的底行补齐逻辑删掉，改由 `ui` 统一负责。
4. 删掉各 context 里的 TabooLib 适配器（`TabooMenuScreens` / `TabooHomeMenus` / `TabooPomodoroScreens`），全插件只剩 `ui` 一处菜单代码。
5. `verifyArchitecture` 增加一条：除 `ui` 外任何模块不得 import `taboolib.module.ui`。

## 与测试的关系

`ui` 的渲染需要真实服务端（TabooLib 菜单），因此：

- **配置解析与动作派发**做成纯函数，可在 MockBukkit 下单测（槽位 → 动作、`@back` 解析、权限过滤）。
- 渲染本身留给真实服务端验收；`TestPomodoroScreens` 那种"镜像渲染器"随迁移一起删掉——配置化之后，槽位与文案来自 config，测试直接断言配置模型即可，不再需要镜像。
