# 币本位交割成交量与重启后的行情查询

`PublicTradeEventMapper` 在 realtime 把已提交的 INVERSE_DELIVERY 成交换成基础币数量，供逐笔查询和 K 线聚合使用：

```
baseQuantity = quantitySteps × notionalMultiplierUnits / (priceTicks × priceTickUnits)
```

分子、分母都以报价币单位表示，报价币 scale 相消；不能用现货 `quantityStepUnits / baseScaleUnits` 表示反向合约面值。
例如一张面值 1000 USDT 的合约成交于 50000 USDT，基础币成交量是 0.02 BTC。
换算采用 BigDecimal 的 18 位展示精度；它不改变账户、手续费或结算的整数运算。
`quantitySteps` 仍为原始张数，Web/client 的逐笔数量列继续使用张数，图表 `baseVolume` 使用 BTC。

`SymbolRegistryService` 的 K 线可接收合约包括 SETTLING 与 CLOSED：直接减仓及历史回放产生的真实成交必须进入行情。
Core 的新下单及新条件单状态门控保持原逻辑。Kafka key、永久合约 ID、产品隔离、去重和迟到成交处理继续生效。

分钟表只持久化关闭的真实 1m K 线。`CandleQueryRepository` 从这些分钟聚合历史与当前大周期；
当前周期标为 PARTIAL，周期结束才标为 CLOSED。冷启动后即使行情内存视图尚为空，当前 4h/1d/1w 也能由已持久化分钟返回。
`CandleQueryService` 继续组合已提交分钟与最新实时完整快照，不查询在线 Core，不根据客户端逐笔列表猜测 K 线。

## 旧行情投影重建

修改换算后，仅重启原消费组不能修复已经聚合的错误分钟：既有状态保存了去重身份，分钟 upsert 也拒绝同成交数的旧替换。
需对目标产品做受控重建，保留原始 committed match-trades Kafka 数据及 Core/Archive/资金账本。

1. 停止目标产品的 realtime，记录 Kafka 水位、原消费组、原状态目录及目标永久合约 ID；备份相关已生成分钟。
2. 备份并清理目标产品的派生 `candle.events` topic，使用全新派生 Streams 状态；仅清理该产品合约的行情派生分钟。旧派生事件不能混入新聚合，否则同成交数的旧值会污染实时大周期。其他产品、原始 match-trades topic 及交易/账户/回执投影不变。
3. 在 realtime 明确配置 `surprising.candlestick.kafka.application-id-override` 为新的重建组，使用独立状态，读取原已提交成交。
4. 核对成交 ID、张数、OHLC、基础币量、报价币额和所有周期；查询、实时订阅以及 Core 暂停时的只读访问均应可恢复。
5. 核对原账户、做市及财库不变后，按部署流程保留有效组及状态。不能自动再次清空投影或重发交易命令。

本轮仅在自建隔离数据库及独立 Kafka 中，备份并重建两个 QA 合约的分钟和该产品的派生 K 线 topic；原始成交水位不变，未操作线上数据。无新增表、DDL、交易 topic 或 Core 资金模型变化。
实栈证据、实际边界及临时环境清理见 `docs/validation/inverse-delivery-protection-20261020.json`。
