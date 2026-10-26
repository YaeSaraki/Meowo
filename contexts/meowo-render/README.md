# Render

FakeEntity / FakeEntityRenderer 是领域值与端口，RenderService 管理每个 viewer 的实体数量和生命周期，PacketEntityRenderer 使用 PacketEvents 2.14.0 发送生成/销毁包。没有 NMS 反射和真实世界实体。

只对命令执行者可见。当前演示静止盔甲架；不是完整 NPC 引擎，不提供交互命中或 AI。默认个人总上限 200，可配为 1..1000。传送、复活、退出和 context 停用会清理记录；发送失败会尝试回滚已发送的新实体。实体使用进程内递减负 ID，避免和服务端正常正 ID 冲突；若与其他假实体插件并用，应统一 ID 分配策略。

缺少 PacketEvents 时仅本 context 启动失败。纯 RenderService 测试通过端口 fake 检验生成、数量限制和清理；真实客户端画面仍需客户端验收。
