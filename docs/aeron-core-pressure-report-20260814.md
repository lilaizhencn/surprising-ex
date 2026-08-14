# Aeron Core 单产品线压力测试报告

## 结论

2026-08-14 在本机 Apple 开发环境逐条产品线完成预热后 600 秒 Core-only MATCH 压测。每次只启动该产品线 Aeron 三节点集群和压测客户端，不启动 Exporter、Projection、PostgreSQL、Valkey 或 Kafka。六条线均通过资金守恒、订单簿清空和失败数检查；本次稳定负载为 **20 Aeron commands/s**，不是生产容量承诺。

## 环境与方法

- JDK 25；Aeron Cluster 三节点；4 workers/4 connections；单 symbol；预热 30 秒，测量 600 秒。
- MATCH 场景由 maker GTC + taker IOC 构成，覆盖下单、成交、建仓/平仓状态变化及 Core reducer 提交。
- CANCEL 场景 60 秒，覆盖用户下单后手动撤销/关闭未成交订单。
- MARK_PRICE 场景 30 秒，覆盖标记价命令入口；风险/强平使用 100 对仓位生命周期专项，交割/期权使用 100 对结算专项。
- 原始证据目录：`reports/aeron-core-pressure/20260814/`。

## 主 MATCH 能力

| 产品线 | committed ops/s | match events/s | P99 (µs) | max (µs) | failures | funds diff |
|---|---:|---:|---:|---:|---:|---:|
| SPOT | 20.000 | 10.000 | 31,890 | 108,811 | 0 | 0 |
| LINEAR_PERPETUAL | 20.001 | 10.000 | 32,395 | 64,217 | 0 | 0 |
| INVERSE_PERPETUAL | 20.001 | 10.000 | 32,632 | 91,784 | 0 | 0 |
| LINEAR_DELIVERY | 20.001 | 10.000 | 32,682 | 85,326 | 0 | 0 |
| INVERSE_DELIVERY | 20.001 | 10.000 | 32,402 | 84,190 | 0 | 0 |
| OPTION | 20.000 | 10.000 | 31,645 | 102,035 | 0 | 0 |

## 独立阶段能力

| 阶段 | 结果 |
|---|---|
| 用户手动撤单/平仓（CANCEL，六线各 60 秒） | 六线均约 20.01 commands/s，P99 17.05–17.68ms，failures=0，fundsDiff=0 |
| 标记价格（MARK_PRICE，六线各 30 秒） | 六线 20.02–20.03 commands/s，P99 12.21–12.76ms，failures=0，fundsDiff=0 |
| 永续风险扫描与强平（100 对仓位） | LINEAR_PERPETUAL 105.910 liquidations/s；INVERSE_PERPETUAL 107.574 liquidations/s；均 fundsDiff=0 |
| 线性交割结算（100 对仓位） | 12,910.480 settled positions/s，fundsDiff=0 |
| 反向交割结算（100 对仓位） | 16,134.942 settled positions/s，fundsDiff=0 |
| 期权行权/结算（100 对仓位） | 14,115.239 settled positions/s，fundsDiff=0 |

## 资源观测

主 MATCH 600 秒期间，各线最大 Core 进程 CPU 为 62.8%–76.2%，总 RSS 为约 1.25–1.29 GiB，GC 暂未捕获停顿事件。测试机共享开发环境，结果只能用于当前实现的回归基线；上线容量需在目标机器上提高 offered ops 逐级寻找首次失败边界。

## 修复的问题

新增 MARK_PRICE 工作负载后发现压测脚本参数校验仍只允许 MATCH/CANCEL，已修复；同时修复并发压测客户端对同一 symbol 使用重复标记价序列导致 Core 拒绝的问题，改为按 symbol 串行并使用全局单调序列。所有修复已重新编译并通过短跑验证。

## 限制与后续

本轮未启动 Export/PG 投影，故不报告 Kafka lag、Projection 延迟或历史落库吞吐；未把这些异步模块混入 Core 能力边界。正式上线前仍需在目标硬件执行更高 offered ops、故障切主、冷恢复、用户 API 身份隔离和资金审计门禁。
