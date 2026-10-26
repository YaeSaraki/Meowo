# Home / Menu 验证记录

2026-10-05，Paper 26.2 独立测试服，Java 25，端口 25577。

Gradle test、core build、verifyArchitecture、verifyCoverage 全部通过。JUnit 总数 217，失败 0，错误 0，跳过 2（既有 MockBukkit shadowbox 限制）。总体行覆盖率 87.56%，domain 94.74%。

实服协议验证：Shift+F 打开总菜单，点击家园入口进入 1/3 页，点击下一页进入 2/3 页；新家默认 TRACK bowtie；导航添加与页面按钮移除各一个 BossBar 数据包，静止不重复更新标题。ViaVersion/ViaBackwards 仅用于测试客户端连接，未部署到用户服务器。

测试覆盖旧 SQLite 十格约束迁移、待结算日志保留、权限容量扩展、默认标记、分页、快捷键以及原有收费、退款和相机移动行为。

插件 SHA-256：`9fbb32bfed3a8c48a967d58ee4eb0a2b40fdbb319b09ce1161ee62f2f5c9139b`。
