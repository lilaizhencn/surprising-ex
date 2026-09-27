# PMM 恢复与数量一致性验证（2026-09-27）

## 本次修正

昨天的 Hummingbot PMM Java 移植仍在 `surprising-maker` 的 `QuotePlanner`、
`MarketMakerService` 和 `MarketMakerTask`。今天早期启动配置把档数/数量压成 2/1，
未启用外部参考盘口和模拟吃单，不能代表昨天的策略效果。本次只恢复配置，没有重写策略。

入口 `scripts/local-perpetual.sh` 使用新的隔离目录
`~/.local/share/surprising-ex/perpetual-pmm-20`，旧 `perpetual-20` 数据库、Archive 和验证记录保留且停机。
新配置每币对一个工作策略、独立做市账户 900101–900120：

- 每侧目标 50 档，每账户/币对最多 100 张报价单，层间隔 1 tick。
- 外部参考盘口为 Binance 永续 WebSocket 前 20 档；外侧档位沿用既有扩展逻辑，不能称为完整复制 50 档外部深度。
- 基础数量上限 1000 steps，实际按参考深度、库存和预算约束缩放。
- 账户 910001 执行独立本地模拟吃单，每批最多 8 单，每单 1 step；经正常 Core 撮合。
- 20 单批量采样未显示改善且空窗更多，因此最终保留 8 单配置。两次采样负载与行情不同，不作为严格性能对照实验。

## 合约数量

初始目录此前只校正了 price tick 和 notional multiplier，遗漏了 `contractMultiplierPpm`。
这会使 U 本位前端显示的基础币数量与 Core 名义金额含义不一致。
本次仅对新库初始化修正三者，保留既有数据库及其订单/持仓参数。

初始化 SQL 与启动市场检查共同要求：
`priceTickUnits * contractMultiplierPpm == notionalMultiplierUnits * 1000000`。
通过 gateway 实读全部 20 合约符合该等式，其他导入合约为 PRE_TRADING。
新目录 BTC 每 tick=0.1 USDT、每 step=0.01 BTC；数量显示 0.01、0.05、0.12 等实际值。
前端“价格档距”只控制价格聚合，单独显示“数量步长”，不通过补零伪装数量精度。

## 实测

- 6 JAR、依赖和前端启动；启动检查 20/20。maker 有 20 个独立策略，日志确认 referenceMarketEnabled=true、marketTakingEnabled=true、orderLevels=50。
- 8 单配置的 60 秒公共成交 WS 采样：每币对 75–112 笔，约 1.25–1.87 笔/秒平均；逐秒有空窗。
- 随后的 20 币对 REST 全量盘口抽样：各侧约 34–50 档。撤补单及吃单使档位波动；没有证明持续双边 50 档。
- 首轮 WS 盘口采样只捕获了 17 币对盘口事件，因此盘口结论使用独立 REST 抽样，不将缺失事件认定为空盘口。
- 20 单批量的另一次 60 秒采样为每币对 20–100 笔，未达到更高持续频率，未保留该配置。
- 在做市运行中逐对执行测试用户市价买入、reduce-only 平仓、GTX 挂单与撤单：20/20 通过，测试用户持仓全为零，冻结余额零。
- 暂停策略并等待在途命令后，直接查询 Core 中测试用户、20 做市账户、模拟吃单账户和 Treasury；22 账户测试注资总量 220,000,000,000,000 units（2,200,000 USDT）。余额、冻结和国库合计相等，差额 0；20 币对全市场净持仓步数合计均为 0。核验后重启 maker 恢复 8 单批量的持续运行。
- Chrome 检查 1440×1000 与 390×844；价格档距、基础币数量及数量步长正确，手机 scrollWidth=390，无 JS 异常。

HotSpot JDK 27 / Maven 3.9.16：`mvn -pl surprising-maker test -q` 57 项通过；
前端 62 项测试、lint、build 通过，`bash -n` / `git diff --check` 通过。
Java 业务逻辑未改，验证集中于 maker 现有模块、配置加载、20 币对真实交易与资金边界。
未覆盖强平、ADL、资金费结算时点、其他产品线、Linux、长时间压力和高可用。
外部指数源过期仍可能暂时导致拒单/503，未修改其有效性门槛。

证据在运行目录的 `pmm-probe-8.json`、`pmm-probe.json`（20 单实验）、`pmm-depth.json`、
`trading-verification.json`、`verification/surprising-pmm-funds.log` 和 BTC 桌面/手机截图。
运行日志约数 MiB，磁盘余量约 413 GiB；没有开启 JFR 或堆转储。诊断浏览器和查询客户端退出，
按用户查看效果的要求保留服务、数据和受限滚动日志。旧测试目录保持停机，不删除用户原有修改。
