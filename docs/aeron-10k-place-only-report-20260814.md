# 10,000 用户纯下单压力测试报告

## 测试模型

- 产品线：`LINEAR_PERPETUAL`。
- 10,000 个逻辑用户，单 symbol，64 workers/64 Aeron connections。
- 每次只提交一笔 BUY IOC 下单命令，不提交对手单，不等待成交；IOC 未成交后由 Core 释放预留，因此最终 Book 为空。
- 预热 30 秒，正式运行 600 秒；只启动 Aeron Core 三节点。

## 结果

| 指标 | 数值 |
|---|---:|
| offered commands/s | 1,000 |
| committed orders/s | 29.605 |
| orders | 17,864 |
| P50 | 2.169s |
| P95 | 3.401s |
| P99 | 3.489s |
| max latency | 3.623s |
| failures | 0 |
| funds diff | 0 |
| final book levels | 0 |
| max process CPU | 198.1% |
| max total RSS | 3.35GiB |

## 结论

纯下单吞吐低于撮合对吞吐（10k MATCH 基线约 32.74 ops/s），说明当前瓶颈不是等待对手单，而是单笔 `PLACE_ORDER` 本身的同步 Core 往返、订单校验、保证金预留、用户状态写入和单写入状态机排队。这个结果确认下单命令没有丢失资金或残留订单，但 `1,000 offered/s` 远高于本机当前可承载的实际吞吐，不能作为生产目标。

原始证据：`reports/aeron-10k-place/20260814/LINEAR_PERPETUAL/`。
