# Economy

完整四层样板：domain 独立 Gradle 模块，application 串行异步编排，SQLite infrastructure 实现 WalletRepository，interfaces 负责命令与权限。公开 EconomyAPI 通过 Bukkit ServicesManager 注册；订阅 PlayerJoined，提交后发布 BalanceChanged。仅依赖 api/shared/platform，不依赖其他业务 context。

数据库位于 `plugins/Meowo/economy/wallets.db`。金额用 INTEGER 分存储，流水与余额原子提交。详细操作、错误码、并发和停用语义见根 README。domain 测试不需要 Bukkit；存储测试真实执行 SQLite。

`SettlementAPI` 同时注册到 Bukkit ServicesManager，提供原子转账、预估/预扣/结算传送税、每日补助和税收池查询。与 EconomyAPI 共用有界串行工作线程和同一 SQLite 连接。

- `/pay 玩家/UUID 金额` 或 `/meowo eco pay ...`：金额必须大于 0，禁止自己转自己。收款人溢出或任一流水写入失败时，双方余额一起回滚。付款方额外承担转账金额 1% 的支付税（`economy.pay-tax.rate` 可调整），向上取整到 0.01。例：pay 100，扣 101，对方收到 100。余额必须足够覆盖本金加税。
- 传送税默认扣费前余额 × 0.001，向上取整到 0.01，最低 1、最高 233。单位默认 Meo、符号 ฅ；支持配置与热重载。
- `teleport_charges` 保存费用暂存状态；PENDING 不进入税收池，COMMITTED 只入账一次，REFUNDED 只退款一次。钱包保留未完成传送的退款容量，避免期间收款/管理员设置填满钱包而无法退款。
- `eco_treasury` 保存池余额；`eco_ledger` 记录每笔 TP_TAX、PAY_TAX 与 DAILY_SUBSIDY，并用唯一引用去重。`transactions` 记录钱包变动，`transfers` 记录转账双方、本金和支付税。
- 默认每日按 Asia/Shanghai 日期发放 1 Meo eco 补助，从已有税收池支出。资金不足时发剩余金额；发出正金额就算今日已领取。空池、关闭补助或钱包无容量时不消耗资格，可用 `/meowo eco daily` 重试。
- `daily_claims(player, day)` 唯一键保证重连、重载、重启和并发连接不会重复领取。`/meowo eco treasury` 查看税收池，默认仅 OP。

服务升级只新增表/索引/列，保留已有钱包。历史异常未完成的传送预扣款在启动时自动退回。多服务器共享一个 SQLite 文件不在支持范围内。

垫付传送使用 `reserveTeleport(ticket, requester, payer)`：传送税始终取申请者余额计算；付款人与申请者不同时额外征收同一支付税率。例：申请者余额 0，传送税 1.00，垫付支付税 0.01，合计 1.01；垫付者余额 500000 也不会将传送税变为 233。成功时分开记录 TP_TAX 与 PAY_TAX，全部加入 eco 池；移动取消、主动取消、失败和启动恢复均将全部预扣退回实际付款人。暂存记录保存两种税的快照，等待期间重载税率也不会改变已预扣金额。
