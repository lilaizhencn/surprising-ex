# 标记价真实输入与启动规则验证（2026-09-27）

## 根因及业务改动

原 MarkPriceService 从没有生产者的 book ticker Kafka topic 读取盘口。缺失/过期盘口和成交被指数价伪造，导致基差长期为零，标记价反复等于指数价并被错误标记 HEALTHY。

1. MarkPriceMarketSubscription 按 ProductLine 订阅既有 Aeron BOOK/TRADE，续租只读盘口查询，转换 ticks/steps；原 MarkPriceService Map 持有唯一最新输入。
2. 移除 syntheticBookTicker/syntheticTrade。真实双边盘口及成交齐全时，沿用资金费收敛价、指数加平均基差价、最后成交价的中位数及指数保护带。
3. 用户明确批准：启动/恢复时缺少双边盘口或成交，但指数有效且新鲜，则使用资金费收敛价及原保护带，状态为 DEGRADED；缺失字段保持 null。指数不可用时不发布新值。
4. 最后真实成交不是心跳；保留原成交时间，较旧成交降级。没有填充成交量、虚构成交、降低指数 quorum。
5. 应用 mark-starting-inputs-20260927.sql，仅放宽审计缺失输入字段并增加“只有 DEGRADED 可缺输入”约束；无资金/订单数据迁移。
6. latest 失败时前端清空对应价格；诊断入口 /api/v1/price/mark/inputs 直接读取实际输入。
7. 本地外部指数设置为已有公开 WS 模式（rest-fallback-enabled=false），避免逐币对 REST 请求串行耗时导致 5 秒新鲜度失效。

## 测试和运行证据

- HotSpot Corretto JDK 27+33，Maven 3.9.16；price provider 92 项测试及直接依赖测试通过，package SUCCESS。日志 /tmp/mark-starting-rule-package.log。
- 覆盖产品线隔离、真实盘口精度换算、同批最后成交更新、过旧更新不能回退、空盘口/无成交启动、缺失指数不发布、真实旧成交降级及保持原时间。
- 前端 57 项测试及构建通过。CDP 离线验证：正常 mark 84095.76/index 84138.45；断网显示“—/—”；恢复显示 84089.67/84133.09。证据 /tmp/mark-ui-live.log。
- 00:33 主 price PID 48949：20 币对连续三轮 latest 均可用，60 个结果按 price1/price2/lastTrade 的中位数和保护带逐项核对全部一致；该样本两价均有真实差异，未人为制造价差。证据 /tmp/real-mark-started-validation.log、/tmp/real-mark-validation.json（脚本后续运行会覆盖）。
- 中途严格要求成交新鲜/双边盘口的初版使部分做市无法恢复，真实交易回归在 RUNNING 前置条件处失败，未产生本次测试资金误差结论。用户确认启动定价后，所有币对恢复；资金回归与 Aeron 升级后的验收继续进行，不能将前置失败写成通过。
- 临时 price 预热进程 PID 45383 已停止；仅在原租约进程退出后释放其 price-index/price-mark 租约，未修改资金和 Archive。临时进程参数文件（含内部配置）已删除；临时探针源文件、输出和快照诊断文件已删除。六服务及前端按用户要求保留运行。
- 无新增状态副本/通用策略框架；唯一新生产类承担独立的行情订阅协议边界。核对了 price2/basis/bid/ask/last 的消费者，现有调用仅透传到响应或可空 SQL 字段；核心 ApplyMarkPrice 固定点协议不变。
