# 期权行情、参考 Greeks 与公开手续费契约

本模块由 realtime 提供只读参考值，Core 仍只承担已有交易、风险及结算职责。单独报价不会下单、改单、调整余额或触发行权。

## 数据与校验

`OptionQuoteService` 仅在 `surprising.candlestick.kafka.product-line=OPTION` 时注册，消费已有 `surprising.option.price.events.v1` 的 MARK_PRICE 分支。它每合约只保留一个不可变价格发布事件，Kafka 消费线程写、HTTP 线程读，定时清除过期/结束条目；这份读视图重启后由后续价格事件重建，不保存派生 Greeks，不访问在线 Core。

查询沿用现有 realtime 市场数据路由：

```text
GET /api/v1/gateway/candlestick/options/quote?instrumentId=<永久ID>
X-Product-Line: OPTION
```

realtime 内部路径为 `/api/v1/candlestick/options/quote`。无需增加网关服务名称或新 topic。未启用此产品的进程不注册该控制器及价格消费者。

查询先核对合约、生命周期、精度、事件产品/ID、源事件顺序、价格与计算输入一致性，再推导参考值。价格编码匹配 `instrument.changeId`，不能使用 `lastChangeId`：后者包含状态与业务配置变更，前者是已有交易精度版本。原始价格整数与 ticks 也须同一精度换算一致。新编码事件先到而合约元数据未到时拒绝报价，不能继续显示旧编码；定时清理仍保留有效期内的新编码事件，防止旧编码重新进入读视图。

可读取 PRE_TRADING/TRADING/HALT/SETTLING 中尚未到期且有新鲜源数据的合约；报价可见性不授予交易权限。期权下单依然只允许未到期 TRADING，停牌仍禁止下单。未知合约 404，产品头不匹配 404，无有效/新鲜输入或到期关闭 503，非法 ID 400。标记价和指数输入分别检查时间，最晚有效期取两者五秒边界与到期时刻的最小值。源失效、配置不匹配或断流不能用旧数值或猜测值填补。

## 模型与单位

当前提供欧式、现金结算、报价资产等于结算资产的 Black-76 参考模型，贴现因子明确为 1，年计时为 ACT/365F。采用外部同到期远期价 F、行权价 K、外部权利金及剩余时间，反推总标准差，再换算年化 IV。没有自造远期价、默认波动率或按价内外猜测 Delta。

公式校对参照 [LME 官方 Black-76 说明](https://www.lme.com/Trading/Contract-types/Options/Black-Scholes-76-Formula) 和 [QuantLib 原始实现](https://github.com/lballabio/QuantLib/blob/master/ql/pricingengines/blackcalculator.cpp)。本实现使用 Commons Math 的正态分布误差函数，并以有界二分反推波动率；独立 80 位 Decimal 正态积分生成的样本验证数值，没有把实现自身输出作为期望值。

| 字段 | 语义 |
| --- | --- |
| premiumPrice | 每 1 单位标的的报价，非一张合约所付现金 |
| underlyingIndexPrice | 同一价格发布所用的标的指数 |
| sameExpiryForwardPrice | 明确的同到期远期价，与标的指数区分 |
| intrinsicValue | 以标的指数计算的即时内在价值 |
| greeks.impliedVolatility | 年化波动率，小数；两端显示百分数 |
| greeks.forwardDelta | 对远期价的一阶敏感度，不冒充现货 Delta |
| greeks.forwardGamma | 对远期价的二阶敏感度 |
| greeks.vegaPerVolPoint | 波动率变动一个百分点的报价资产价值变化 |
| greeks.thetaPerDay | 固定远期与波动率下，每天的报价资产时间价值变化 |
| model / discountFactor / unit | BLACK_76_ZERO_RATE_ACT_365 / 1 / PER_UNDERLYING_UNIT |
| asOf / validUntil | 源价格时间及最晚有效时刻 |
| sourcePriceSequence | 原始价格序号，以字符串保留整数精度 |

所有参考敏感度以每 1 单位标的为基准；合约的全额权利金与手续费仍按实际 ticks、张数、乘数和资产精度另行计算。价格越过模型边界、没有可辨认时间价值、数值距边界过近或无法收敛时，保留真实价格而返回 `greeks=null`，不能钳制价格或返回伪造 IV。这是明确零贴现假设下的参考模型，不替代 Core 期权风险、组合权益或结算账本。

## 两端行为

Web `OptionMarketPanel` 和 client `OptionReference`/`AppState` 使用同一路由，检查产品、合约、标的、行权价、精确到期时刻、币种、时间范围和有限数值。仅当前期权定时读取，切换合约清除旧引用并忽略迟到结果；超时自动隐藏参考值，恢复后重新取得。费用和 Greeks 是独立读视图，缺少其一不会编造另一项，也不会自动重发订单。

两端期权链按标的产品/ID、精确到期时刻、报价及结算资产分组，行权价使用整数排序，重复 CALL/PUT 合约全部保留为可操作项。选择合约走已有页面状态和订阅切换流程。Web 320px 页头使用紧凑语言显示、保留完整语言菜单和各图标，避免已登录导航撑宽页面。

## 手续费就绪修复

`EffectiveTradingFeeResponse.changeId` 现在与公开 instrument.changeId 对齐；它仍携带原精度版本数值。原先字段名 instrumentChangeId 被网关既有公共脱敏规则删除，client 严格校验该字段导致手续费票据永久未就绪。改正公开 DTO 名称及 client 校验后，继续保留 user/product/instrument/version、费用范围及滑点范围检查，没有接受无版本响应的退路，也没有放开网关的内部字段过滤。

Web 现货费用校验也同步使用公开 `changeId`；回归覆盖正确版本通过、错误/缺失版本及仅含旧字段的响应拒绝。本轮实际交易仅启动 OPTION，未重启其他产品线。

## 发布与验收

更新 realtime（含 price-api 与 Commons Math 依赖）、gateway 的公开费用 DTO，再更新 Web/client。本轮无需 SQL 或 Core 协议/资金模型迁移；前轮回执及期权流水分类迁移若尚未执行，仍按其说明处理。

后端测试覆盖模型黄金样本、平价关系、小额精度、边界/非有限输入、过期/乱序/版本/状态以及六产品上下文隔离；应用上下文不会启动 Core。真实 OPTION 单节点 FUSED 中，用 CALL=1000、PUT=500、F=50500、K=50000 的一致数据验证两端切换、参考值、价格源暂停后的 503/隐藏/恢复、语言主题菜单、真实做市和丢回包恢复。费用版本问题先在旧运行端复现，再更新 gateway，以只读 client 票据验证两张 PUT 的全额权利金加费用为 1.0005 USDT。验证范围、命令、实际结果与清理见 `docs/validation/option-market-reference-20261020.json`。

Web 入口为 `scripts/option-reference-fused-live.mjs`，使用已有 Playwright/Chromium，通过 OPTION_REFERENCE_LIVE_FIXTURE、OPTION_REFERENCE_PRICE_PID_FILE 及可选 OPTION_REFERENCE_PLAYWRIGHT_MODULE 配置。Maven 交接设置 SIX_LINE_LIVE_PRODUCT=OPTION、SIX_LINE_LIVE_TRADING=true、SIX_LINE_LIVE_OPTION_REFERENCE=true 和本轮隔离 JDBC/fixture。只在出现本次 fixture 后运行 UI 测试，两个客户端退出 0 后才写 `.done=PASS`。不要给 OPTION 执行交割专用减仓支路，枚举 isDeliveryProduct 也包含 OPTION。
