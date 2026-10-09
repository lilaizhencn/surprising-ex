# 最近成交查询的 Kafka 客户端生命周期

`RecentTradeQueryService.recent` 校验产品线内的 instrument 和 limit 后，借用服务持有的唯一 KafkaConsumer，读取该 instrument 所在分区已提交的最后 500 条记录，按成交序号倒序返回。查询只读 Kafka，不写数据库，不参与撮合或 K 线聚合。

消费者由服务惰性创建，直到服务关闭时销毁。KafkaConsumer 非线程安全，因此查询、seek/poll 和关闭共用一个锁；等待锁、元数据和拉取共同受 2 秒预算约束。超时抛异常，不将未读完的结果伪装成完整成交列表。手动 assign，不创建每请求 consumer group，也不提交消费位点。没有额外后台工作线程或重复成交缓存。

查询捕获 read_committed end offset，忽略拉取期间新到达的边界外记录，并检查消息 instrument 与 Kafka key 一致。base quantity 仍由 `PublicTradeEventMapper` 按合约面值转换，quantitySteps 单独返回，不能混作成交量。

验证：HotSpot JDK 27 下 `RecentTradeQueryServiceTest` 和 `PublicTradeEventMapperTest`，10 个用例通过，包括六产品线配置隔离、并发互斥、消费者复用/关闭、提交边界、币对筛选、倒序及数量单位。保留原接口“最后 500 条分区记录”的窗口语义；同分区成交稀少的币对可能不足 50 条。
