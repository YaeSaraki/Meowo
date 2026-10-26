# Home 调研与实施计划（2026-10-05）

已确认按水平 X/Z 直线距离计费。作为 Meowo 的独立 context 实现，不另装第三方插件。

## 调研
- [HuskHomes](https://github.com/WiIIiam278/HuskHomes)：参考私有 homes、持久化、经济和延迟传送的职责划分。
- [HuskHomesGUI EditMenu](https://github.com/WiIIiam278/HuskHomesGUI/blob/master/src/main/java/net/william278/huskhomes/gui/menu/EditMenu.java)、[ListMenu](https://github.com/WiIIiam278/HuskHomesGUI/blob/master/src/main/java/net/william278/huskhomes/gui/menu/ListMenu.java)：已阅读源码；参考列表进入编辑、独立迁址和改名操作，不复制代码。原项目已归档。
- [Waypoints](https://github.com/Sytm/waypoints/tree/v5/main)：参考可见性、颜色、GUI 图标和距离信息。
- 用户追加要求：home 所有列表、编辑、选择和确认页面使用 TabooLib Bukkit UI（Chest），配置安装 UI 模块；名称由 GUI 发起私聊输入，避免引入版本相关的 Anvil NMS。
- [LocatorBar Ultimate Paper](https://github.com/Krylo-60/LocatorBar-Ultimate/blob/main/paper/src/main/java/pl/fuzjajadrowa/locatorbar/paper/LocatorBarPaperPlugin.java)：已阅读源码，其 plugin message 方案需要对应客户端模组，因此本项目采用原版 waypoint 协议。
- [Minecraft 官方 1.21.6 发布说明](https://www.minecraft.net/en-us/article/minecraft-java-edition-1-21-6)：定位栏占经验条区域，120°视野范围、高低箭头、距离图标；原版内置 default / bowtie 两种样式，自定义资源需要资源包。获取经验时原版会暂时显示经验条。
- 本地 PacketEvents 2.14.0 API 源码确认存在 WaypointIcon、TrackedWaypoint、Vec3iWaypointInfo 和 TRACK / UPDATE / UNTRACK。仅向 home 所有者发包，不创建公共实体，不修改真实经验或世界规则。

## 规则与界面
1. 默认最多 10 个家，固定编号 1–10；名称、16 色预设及命令 RGB、两种定位栏样式、GUI 物品图标、导航开关均可设置。列表显示世界、坐标、距离、高度差；选中导航可在 actionbar 显示名称与方向。
2. 创建/迁址收当前余额 5%，最低 100、最高 2333 Meo；改名/颜色/图标/导航设置免费。所有收费操作 GUI 二次确认。
3. 回家收 sqrt((dx)^2+(dz)^2)/10，最低 100、最高 2333；金额向上取两位小数。跨世界默认 2333，可配置或禁止。缺失世界、危险落点、失败传送不收费。
4. 回家默认 3s，允许转动视角；位移超过已有 TPA 的 1/16 方块容差取消并退款。共享 TPA 白色符文串、到达六芒星阵和反胃/黑暗/缓慢实现。
5. home 与 TPA 使用统一行程互斥；位置保存和费用采用持久化预扣/结算。迁址操作保存旧记录与 ticket，重启按经济账本状态恢复或回滚，避免保存失败扣费和崩溃免费迁址。
6. 税款统一进入 eco，继续用于每日登录补助。数量、比例、最低/最高费用、跨世界费用、倒计时、粒子、辅助导航均配置化。

## 实施与验证
- 扩展通用税收预扣端口，保留原有钱包、TPA 账本迁移兼容。
- 将现有传送表现抽取到 platform，共用实现，保留原有测试接口。
- 实现 home 持久化、GUI、命令、私有 waypoint transport、运行时配置和 lifecycle。
- 验证费用边界、并发/重启恢复、10-home 限额、GUI 免费编辑、移动/准星回归、危险/失败落点退款、导航隐私与生命周期。
- 运行构建、架构与覆盖率检查，生成交付 jar；备份并更新本地测试服插件，现有在线服不自动重启。

## Paper 26.2 / TabooLib UI 兼容验证
固定的 TabooLib 6.2.4-c90a237 内置 NMS 支持列表截至 1.21.11。BukkitUI 默认附带 NMS 模块，版本保护会在 Paper 26.2 停止整个插件。home 只使用标准 Chest、公开 Bukkit 容器和事件（未启用虚拟背包、Anvil、raw-title 或 NMS 操作），所以从模块集合排除 bukkit-nms；保留默认的版本保护，不修改版本判断、不伪装版本。已在隔离的真实 Paper 26.2 上启动并通过客户端打开/点击页面、添加及移除 waypoint，未出现 NMS 初始化或版本警告。原版定位栏发包由已有的 PacketEvents 2.14.0 完成。
