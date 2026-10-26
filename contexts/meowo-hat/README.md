# meowo-hat

`/meowo hat`（根级 `/hat`）—— 把手上的头盔和头上交换，空手执行则摘下头盔。纯外观功能，
不碰经济、不掉落任何东西：每一步都是在玩家本来就拥有的两个槽位之间交换。

## 命令与权限

| 命令 | 权限 | 默认 | 行为 |
|---|---|---|---|
| `/meowo hat` / `/hat` | `meowo.hat` | 所有人 | 手上有头盔 → 与头上交换；空手且头戴头盔 → 摘下；空手且没戴 → 提示无事可做；手上不是头盔 → 拒绝且不改动任何槽位 |

context id 为 `hat`，可用 `contexts.hat: false` 关闭。

## 关键实现约束

**能否戴在头上用材质名判断，不用 `Material.getEquipmentSlot()`。**

这个方法签名看起来是显然的选择，但在 MockBukkit 4.116.1 下它对**所有**材质都返回
`null` 且**不抛异常**——用它写测试会「通过」，而实际永远判不出头盔。
`org.bukkit.material.ArmorMaterial#getHelmetMaterial()`（1.21 之前的经典写法）
在 Paper 26.2 中整个类都已不存在。

所以判据是纯枚举方法：

```kotlin
private fun isHelmet: (Material) -> Boolean = { it.name.endsWith("_HELMET") }
```

覆盖全部原版头盔（含 `TURTLE_HELMET`、`COPPER_HELMET`）。模组头盔需要管理员另行适配。
`HatContext` 把这个判据做成**构造参数**，测试可注入自己的实现。

> 教训见 [08 §6c](../docs/minimax/08-坑与反模式.md)：验证新 API 要做两件事——
> jar 里确认方法**存在**，以及在 MockBukkit 里**真的调用一次**并断言结果。
> 只做前者会被「返回 null 不抛异常」骗过。

## 测试

`MeowoRuntimeTest` 覆盖：头盔交换、交换回原头盔、空手摘下、非头盔拒绝且不改动、
无物可做时的提示、每种可戴头盔材质、context 停用后路由消失、根级 `/hat` 可达且拒绝控制台。
