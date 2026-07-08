# AGENTS.md

Surprising-EX 是交易所后端核心项目。改动必须严谨，资金安全优先于交付速度。

## 项目边界

- 这是 Java / Maven 多模块项目，业务包括现货、永续、交割、期权。
- 保持现有架构统一：ProductLine、instrument、Kafka topic、账户、撮合、风控、WebSocket、结算等边界不要随意重构。
- 新逻辑优先沿用现有模块、事件模型、repository、outbox、Kafka topic 和测试脚本。
- 除非任务明确需要，交易后端测试不要启动 wallet 服务。

## 四产品线规则

- 四条业务线必须隔离：现货、永续、交割、期权不能混用订单逻辑、账户类型、topic、instrument、风险模型。
- 永续需要重点验证资金费、标记价、强平、ADL、保险基金。
- 交割需要重点验证到期结算、交割流水、持仓归零或结算后状态。
- 期权需要重点验证权利金、行权、到期失效、买卖方权益和风险边界。
- 现货需要重点验证买卖资产冻结、成交扣减、解冻和余额准确。

## 测试要求

- 每次只测一个产品线，不需要四个撮合业务全部启动。
- 做市进程在交易链路测试中应保持运行。
- 用模拟用户 API 下单覆盖完整流程：下单、撤单、撮合、成交、持仓形成、主动平仓、强平、风控事件、WebSocket 私有/公共推送。
- 必须验证用户账号和做市账号资金正确，持仓正确，业务流程按设计执行。
- 资金守恒/账账核对必须逐项对平：期初、充值/调整、成交、手续费、资金费、强平费、交割/行权流水、期末余额。
- 优先使用现有脚本：
  - `scripts/start-product-line-providers.sh`
  - `scripts/product-line-api-flow-smoke.sh`
  - `scripts/product-line-funds-reconcile.sh`
  - `scripts/live-runtime-trading-reconciliation.sh`
  - `scripts/integration-smoke.sh`
  - `scripts/kafka-trading-smoke.sh`

## 验证命令

- 局部改动优先跑相关 Maven 模块测试：`mvn -pl <module> -am test`。
- 跨账户、撮合、风控、WebSocket 的改动要跑对应集成脚本。
- Kafka topic 或产品线 topic 改动后检查 `ProductTopicNames`、`scripts/create-topics.sh`、consumer group、key 校验和 WebSocket fanout。

## 文档

- 新增或调整产品线、资金模型、撮合、风控、交割、期权、WebSocket、Kafka topic 后，要同步 README、README_CN 和相关 docs。
- 说明要结合源码路径和关键类，避免只写概念。

## 提交

- 每完成一个模块并通过测试后 commit and push。
- 不提交 `.idea/`、`.local-logs/`、`data/`、本地运行产物。

