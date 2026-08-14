# 10,000 用户异步 In-flight Pipeline 对照报告

## 安全性验证

异步撮合对每一对订单使用 `maker future -> taker future` 链式依赖：只有 maker 命令收到 `APPLIED` 后才提交 taker IOC。短跑和正式压测均 `fundsDiff=0`、`bookLevels=0`、`failures=0`，没有出现 taker 先于对手单进入 Book 的问题。

## 规模

- `LINEAR_PERPETUAL`，单 symbol，10,000 个逻辑用户。
- 64 workers、64 Aeron connections、每 worker `asyncInFlight=4`。
- 预热 30 秒，正式运行 600 秒；Core-only，不启动 Exporter、Projection、PG、Valkey、Kafka。

## 结果

| 指标 | 同步基线 | 异步 pipeline |
|---|---:|---:|
| offered commands/s | 80 | 100 |
| committed commands/s | 32.740 | 26.426 |
| match events/s | 16.370 | 13.213 |
| P50 | 29.487ms | 18.314s |
| P95 | 50.273ms | 34.331s |
| P99 | 53.847ms | 41.455s |
| max latency | 229.804ms | 60.314s |
| failures | 0 | 0 |
| funds diff | 0 | 0 |

## 结论

当前异步实现没有破坏 Book 或资金安全，但没有提升单热点 symbol 吞吐，反而因 64 个连接、每 worker 多个阻塞异步任务和单 symbol 确定性撮合，形成严重响应排队。这个结果证明“把同步 RPC 包装成 CompletableFuture”不是有效的生产级 pipeline；下一步必须在同一 Aeron client session 上实现真正的批量 offer/异步响应关联，并配合 symbol 分片，不能直接把本实现用于生产。

原始证据：`reports/aeron-10k-async/20260814/LINEAR_PERPETUAL/`。
