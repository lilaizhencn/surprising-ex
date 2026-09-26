# 本地做市数量配置核查（2026-09-26）

## 原因与修正

`surprising-maker/.../service/QuotePlanner.java` 的 `adjustedQuantity` 使用
`min(referenceQuantitySteps, baseQuantitySteps)`，随后按库存倾斜调整。
本地 20 个永续策略的 `base-quantity-steps` 都是 1，导致外部参考盘口数量被压成一个数量步长。
BTC 合约一个数量步长为 0.0001 BTC，前端显示与真实挂单一致。

通过既有 `POST /api/v1/admin/market-maker/strategies/{strategyId}/config`，
将本地 20 个策略的 `baseQuantitySteps` 调整为 1000，并同步本地启动覆盖文件
`~/.local/share/surprising-ex-live-20260926/local-primary-20.yml`。
保留库存限制、参考数量上限及分批撤补逻辑，没有一次撤光订单，没有改动交易资金算法。

## 实测与边界

- 调整后策略接口返回 20 个策略均为 RUNNING。
- BTC 真实盘口返回 19、29、239、329、399、520、599、999 等不同数量步长，
  例如 19 步为 0.0019 BTC；ETH 也出现多种数量。
- 数量更新由真实撤单、下单产生，前端不生成随机数量。
- 本地参考源仍为 Binance 前 20 档；外侧扩展档位仍使用配置数量，不能宣称 50 档全部逐档复制外部盘口。
- 采样期间档数仍有波动，曾出现买侧 2 档、卖侧 43 档；本次配置修正不能证明双边持续 50 档。
- 此次仅配置及前端默认周期调整，没有改动 Java 源码，未重新运行 Maven 测试。
- 前端默认周期改为 15m，lint/build 通过，桌面及 390px 手机宽度均确认 15m active、无横向溢出。
- 私有成交回报、全链路资金核验仍未通过，不能据此宣称生产运行验证完成。

按用户要求，本地服务及做市保留运行；本次没有启动临时集群。磁盘剩余约 441 GiB。

## 盘口跟随参考价时保留梯度

`QuotePlanner.plan` 原来逐档裁剪到本地旧对手盘的最优价，参考价明显变化时会把同侧多档压成同价，去重后只剩一档。现改为在最优价避让后保留相邻参考档的价格距离，仍遵守价格偏离范围和库存限制。撤换继续使用原来的滚动批次。

HotSpot JDK 27 下 `QuotePlannerTest,MarketMakerServiceTest` 通过；新增参考价上移/下移时双边各 50 个不同价格档的用例。运行验证另行记录，单元测试不代表实时链路已验收。
