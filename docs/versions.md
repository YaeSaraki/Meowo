# 版本核对（2026-10-04）

原始 plan 保留作历史参考。26.2 已是正式 Minecraft 版本，不需要映射为 1.21。

| 项目 | 锁定版本 |
|---|---|
| Minecraft / 客户端 | 26.2 |
| 服务端 | 官方 Paper 26.2 build 129，不是未知 fork |
| Paper API | `io.papermc.paper:paper-api:26.2.build.129-stable` |
| Java | 编译、测试、服务端 Java 25；Gradle 8 daemon Java 21 |
| Gradle | 8.14.3（Wrapper SHA-256 校验） |
| Kotlin | 2.2.21；插件字节码目标 21，运行时仍必须 Java 25 |
| TabooLib | 6.2.4-c90a237 / 构建插件 2.0.38 |
| MockBukkit | `org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.116.1` |
| JUnit | 5.13.4 |
| PacketEvents | 2.14.0，作为独立可选服务器插件，仅 render 使用 |

MockBukkit 4.116.1 声明 Paper 26.2 build 111；本项目测试依赖统一为实际目标 build 129，兼容性由测试验证。

- [Paper 项目配置](https://docs.papermc.io/paper/dev/project-setup/)
- [Paper Java 要求](https://docs.papermc.io/paper/getting-started/)
- [Paper 官方版本元数据](https://fill.papermc.io/v3/projects/paper/versions/26.2/builds)
- [Paper jar](https://fill-data.papermc.io/v1/objects/b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083/paper-26.2-129.jar)
- SHA-256: `b1d8f6bfa1b6101fa8e947b53041cb3bdf5540e7b83b6547ca19ba7edefeb083`
- [Minecraft 客户端](https://www.minecraft.net/download)
- [MockBukkit 发布](https://github.com/MockBukkit/MockBukkit/releases)
- [MockBukkit Maven 元数据](https://repo.maven.apache.org/maven2/org/mockbukkit/mockbukkit/mockbukkit-v26.2/maven-metadata.xml)
- [TabooLib 模块版本](https://repo.tabooproject.org/repository/releases/io/izzel/taboolib/common/maven-metadata.xml)
- [TabooLib 构建插件](https://plugins.gradle.org/plugin/io.izzel.taboolib/2.0.38)
- [PacketEvents 发布](https://github.com/retrooper/packetevents/releases/tag/v2.14.0)
- PacketEvents SHA-256: `060087c58ec268eae7dd0eb1f17ac3bea1eaadda1693414141ff64e506091bd7`

`@TabooLib(id=...)` 多入口、`paper-mojangapi` 和 `PaperweightMain` 均未按原伪代码采用；使用官方框架入口、显式 context 管理与实际 jar 启动脚本。不使用 NMS，因此不需要 paperweight。
