# Surprising Risk

风控 Provider 只查询 Aeron Core 的风险快照和强平候选，不消费价格 Kafka，也不在本地维护标记价、保证金或强平阈值副本。

价格发布链路按当前产品线写入统一的 `surprising.<product-line>.price.events.v1`，事件用 `eventType` 区分指数价和标记价；
Core 的 `ApplyMarkPriceCommand` 是实时风险计算的唯一入口。Risk Provider 通过 Aeron 查询 Core 已应用的
`markPriceTicks`、`priceSequence`、权益、维持保证金和风险状态，并原样提供查询 API。

强平候选的生成、风险档位保证金计算、强平阈值判断和状态迁移全部由 Core 完成。强平 Coordinator 只领取 Core 的有界工作并提交执行命令，
不能回退到 Kafka、价格数据库或 Risk Provider 自己的阈值判断。
