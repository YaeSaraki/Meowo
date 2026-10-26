# 新增 meowo-chat（设计预留，当前不实现）

职责：聊天格式和过滤规则。不直接引用 economy、menu 或 render。不预注册无实现的命令或服务。

1. 创建 `contexts/meowo-chat/src/main/kotlin/com/meowo/chat/{domain,application,infrastructure,interfaces}` 和测试目录。
2. `settings.gradle.kts` 添加 `include(":contexts:meowo-chat")`。
3. 构建文件：

   ```kotlin
   dependencies {
       implementation(project(":api"))
       implementation(project(":shared"))
       implementation(project(":platform"))
       compileOnly(libs.paper.api)
       testImplementation(libs.mockbukkit)
   }
   ```

4. 领域层先实现 `ChatPolicy` 等无 Bukkit 的规则并测试。跨 context 需要格式化服务时，在 `api` 定义 `ChatAPI`，签名只使用 UUID、String 等不可变值；无实际需求时无需增加 API。
5. 用 `Context` 管理生命周期。示例骨架只展示资源拥有关系，真正功能在 application 与 interfaces 中实现：

   ```kotlin
   class ChatContext(private val host: PlatformHost) : Context {
       override val id = "chat"
       private var command: AutoCloseable? = null
       override fun start() {
           command = host.commands.register("chat", object : Route {
               override val permission = "meowo.chat"
               override fun execute(sender: CommandSender, args: List<String>) {
                   // 参数解析 → ChatApplicationService → localized response
               }
           })
       }
       override fun close() { command?.close(); command = null }
   }
   ```

6. 若监听 Paper 异步聊天事件，不直接访问世界或修改 Bukkit 状态；如需主线程操作调用 `host.mainThread`。事件总线回调同样遵守发布线程语义。
7. core 构建的 evaluationDependsOn 列表加入 chat，添加 `"taboo"(project(":contexts:meowo-chat"))`；core 的配置有效名称、默认 config、组合根启动/停用分支中加入 chat。声明命令权限与中英文语言键。
8. 注册服务用 `Bukkit.getServicesManager().register`，close 时精确撤销自己的实现。订阅持有返回句柄，close 时退订，不持有其他 context 的具体类。
9. 把 chat 加入 `verifyArchitecture` 的 context 集合；写规则单测、权限测试和关闭后不再回调测试。执行根 README 中的完整验证命令。

teleport 与 punish 后续也按该流程新增：前者拥有传送请求与冷却，后者拥有处罚记录和生效策略，均尚未实现。
