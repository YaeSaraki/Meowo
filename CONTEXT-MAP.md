# Context Map

Meowo is a Gradle multi-module Minecraft plugin. Each `contexts/meowo-*` module is one bounded context, and `api/` holds the contracts they share. `settings.gradle.kts` is the source of truth for which contexts exist.

Each context's glossary lives at `contexts/meowo-<name>/CONTEXT.md`. These files are created on demand — when a term is actually resolved, not up front. Until then, the context's business rules are in its `README.md`.

## Contexts

- [Economy](./contexts/meowo-economy/CONTEXT.md): 持久化钱包、转账、付费传送与登录补助；`domain` 是独立的 Gradle 模块 · 业务规则 [README](./contexts/meowo-economy/README.md)
- [Agents](./contexts/meowo-agents/CONTEXT.md): LLM 智能体运行时、纪律循环与持久化，十二只猫的运行时
- [Chat](./contexts/meowo-chat/CONTEXT.md): 聊天解析管线、审核闸门、发言者一等公民化、十二只猫的发言范围与性格 · 业务规则 [README](./contexts/meowo-chat/README.md)
- [Menu](./contexts/meowo-menu/CONTEXT.md): 交互菜单框架，跨 context 接入的示例 · 业务规则 [README](./contexts/meowo-menu/README.md)
- [Render](./contexts/meowo-render/CONTEXT.md): 客户端假实体渲染（PacketEvents），只对命令执行者可见 · 业务规则 [README](./contexts/meowo-render/README.md)
- [Teleport](./contexts/meowo-teleport/CONTEXT.md): `/tpa` 传送请求、扣费托管与传送特效 · 业务规则 [README](./contexts/meowo-teleport/README.md)
- [Home](./contexts/meowo-home/CONTEXT.md): `/home` 家、严格落点校验与强制传送
- [Spawn](./contexts/meowo-spawn/CONTEXT.md): `/spawn` 回城、`/setspawn` 设置世界出生点 · 业务规则 [README](./contexts/meowo-spawn/README.md)
- [Shop](./contexts/meowo-shop/CONTEXT.md): 喵达 ModaCat 商店 · 业务规则 [README](./contexts/meowo-shop/README.md)
- [Mine](./contexts/meowo-mine/CONTEXT.md): 稀有矿物首挖全服通报 · 业务规则 [README](./contexts/meowo-mine/README.md)
- [Sit](./contexts/meowo-sit/CONTEXT.md): `/sit` 坐下与 `/ride` 骑乘 · 业务规则 [README](./contexts/meowo-sit/README.md)
- [Hat](./contexts/meowo-hat/CONTEXT.md): `/hat` 帽子交换 · 业务规则 [README](./contexts/meowo-hat/README.md)
- [Shadowbox](./contexts/meowo-shadowbox/CONTEXT.md): `/shadowbox` 潜影盒，物品即容器 · 业务规则 [README](./contexts/meowo-shadowbox/README.md)
- [Invsee](./contexts/meowo-invsee/CONTEXT.md): `/invsee` 查看与搬运他人背包 · 业务规则 [README](./contexts/meowo-invsee/README.md)
- [Nametag](./contexts/meowo-nametag/CONTEXT.md): 自定义名牌与 AFK 状态圆点 · 业务规则 [README](./contexts/meowo-nametag/README.md)
- [Pomodoro](./contexts/meowo-pomodoro/CONTEXT.md): 番茄钟状态机、预设与三处读数 · 业务规则 [README](./contexts/meowo-pomodoro/README.md)
- [Avatar](./contexts/meowo-avatar/CONTEXT.md): 取消原版「不睡觉就刷幻翼」机制与玩家体型调整
- [Webapi](./contexts/meowo-webapi/CONTEXT.md): 只读 HTTP 接口，把服务器状态交给 Wofuf 的 Cloudflare Worker · 业务规则 [README](./contexts/meowo-webapi/README.md)

> `meowo-agents`、`meowo-avatar`、`meowo-home` 三个 context 目前还没有 README，上面的描述取自 `docs/minimax/` 与根 README 的对应章节。

## Relationships

跨 context 的通信只走 `api/` 里的接口契约（注册到 Bukkit `ServicesManager`），不互相 import 实现——`verifyArchitecture` 门禁会直接报错。

- **Economy → 多个 context**：`EconomyAPI` 与 `SettlementAPI` 提供余额、转账、传送税预扣/结算与每日补助。被 Chat（猫答余额）、Shop（消费）、Agents（风纪工具）、Teleport 与 Home（传送税）使用。
- **Avatar → Menu**：Menu 通过 `AvatarAPI` 读体型与幻翼开关状态。
- **Home → Agents / Menu**：Home 通过 `HomeMenuAPI` 暴露家园菜单入口。
- **Pomodoro → Nametag**：Pomodoro 实现 `FocusAPI`（专注/挂机状态），Nametag 读它来决定名牌圆点颜色。
- **Menu → 多个 context**：Menu 是接入示例，聚合 Home、Shop、Pomodoro、Avatar 的状态；菜单构建代码只允许存在于 `ui` 模块。

## 共享的基础模块

- `api/` — 跨 context 契约：`EconomyAPI`、`SettlementAPI`、`UIAPI`、`FocusAPI`、`AvatarAPI`、`HomeMenuAPI`、`ChatModerationAPI`，以及 `api/event/` 的事件。
- `shared/` — 无状态工具，两边都能依赖。
- `platform/` — Bukkit 门面层（`ChatLine`、`Durations`、`Messages` 等）。**任何 `domain` 包都不得 import 它**，门禁会拦。
- `ui/` — 唯一的菜单构建处。TabooLib 菜单代码只允许出现在这里。
- `core/` — 组装根：`MeowoRuntime` 是唯一的 composition root，负责启停、重载与顺序。
