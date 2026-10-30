# meowo-webapi

只读 HTTP 接口：把服务器状态与在线玩家数据交给 Wofuf 的 Cloudflare Worker。

## 职责

| 端点 | 说明 |
|---|---|
| `GET /health` | 存活检查，不需要密钥 |
| `GET /api/v1/snapshot` | 一次拿到全部：服务器状态 + 每个玩家的统计/成就/皮肤 URL |
| `GET /api/v1/players` | 在线玩家列表 |
| `GET /api/v1/server/status` | 服务器状态 |
| `GET /api/v1/statistics/{uuid}` | 某玩家的统计 |
| `GET /api/v1/advancements/{uuid}` | 某玩家的成就 |
| `GET /api/v1/skins/{uuid}` | 某玩家的皮肤（URL，不是图片） |

## 两道闸

1. **共享密钥**：请求头 `X-Meow-Secret`（也接受旧的 `X-MeovO-Secret`），常量时间比较；
2. **来源白名单**：`webapi.allowed-ips` 非空时，只有列出的来源地址能进。

除 `/health` 外全部需要密钥。方法只有 `GET` / `HEAD`，其余 405。

## 快照，而不是实时读

Bukkit API 不是线程安全的。旧 `yawebapi` 让请求线程直接读 Bukkit，那是在等一次卡顿的竞态。
这里改成：主线程每 `webapi.snapshot-interval-seconds` 秒构建一次**不可变快照**，
HTTP 线程只读已发布的值。跨线程的只有一个值对象。

统计明细两种模式：

- `full`：含物品/方块/实体分类（与旧 yawebapi 一致，慢）；
- `basic`：只含 UNTYPED 分类（快）。

## 快照里可能没有的东西

Worker 侧的读取代码要知道这件事，否则会把「没读到」当成「读到了 0」：

| 字段 | 什么时候没有 |
|---|---|
| `server.tps` | 服务器没有告诉插件 TPS 时（Paper 的 TPS 没有公开 API，插件是反射读的）。以前这里填的是 20.0——理论满分——所以测不出来时它报的是满分；现在读不到就是没有这个字段。 |

另外：某一类数据读不到（成就树、物品注册表）时，只有那一类会是空的，其余照常——不会因为一项读不到就把整个快照丢掉。

`players[].advancements` 是**游戏里的全部成就**，每个都带这个玩家的完成情况——和旧 `yawebapi`
的形状一样，前端读的就是它。

## 成本

快照在**主线程**上构建。`full` 模式下每个玩家要问一次「这个物品的这项统计是多少」，那是
`统计种类 × 物品数量` 次主线程调用；成就同理，是 `玩家数 × 成就数` 次。`basic` 模式完全不做
统计这件事。玩家上限 `webapi.max-players` 与刷新间隔 `webapi.snapshot-interval-seconds` 是两个
控制成本的旋钮。

## 配置

见 `config.yml` 的 `webapi:` 段。`webapi.secret` 留空时该 context **拒绝启动**——
一个没人能鉴权的端口比没有端口更糟。

## 与 Cloudflare 的连通

Worker 在公网边缘，访问不到 `127.0.0.1`。在 MC 服务器上跑 Cloudflare Tunnel：

```bash
cloudflared tunnel create wofuf
cloudflared tunnel route dns wofuf plugin.example.com
# ingress: hostname: plugin.example.com -> service: http://127.0.0.1:8080
```

Worker 侧的 `PLUGIN_API_BASE_URL` 设为 `https://plugin.example.com`，
`PLUGIN_API_SECRET` 与 `webapi.secret` 一致。
