# 永久标的身份与配置边界

六条产品线统一以 `(productLine, instrumentId)` 定位标的。数据库目录的 ID 是正整数，交易 HTTP、Kafka、WebSocket 和 Core 命令使用同一 ID 的十进制字符串，不接受名称、空格、前导零或名称哈希。`symbol` 只在目录响应中作为显示、搜索和排序字段存在。不同产品线的相同 ID 不能互换。

## 从配置到交易

1. 管理后台从 `assets` 的已上线、允许交易币种中选择基础币、计价币、结算币和合约价值币，创建产品线合约。`InstrumentStorageService` 校验引用及产品规则，数据库分配 ID，配置与审计在同一事务提交。
2. `InstrumentEvent` 携带产品线、永久 ID、显示名称和配置版本；`InstrumentSnapshotCache` 按产品线和 ID 更新，各服务不按名称查找业务状态。
3. 下单、撤单、持仓、风险、资金费、交割和行权引用 ID。Core 的原生成交引擎使用这个整数 ID，不再对名称生成哈希。业务状态投影使用 `instrument_id`。
4. 改显示名称保留 ID、账务单位和既有状态；费率等可变配置保留版本化审计。基础/计价/结算币、价格和数量的账务单位、底层标的身份等会改变已有金额解释的字段禁止原地修改，需要新建合约。
5. 交割、期权底层标的使用 `underlyingProductLine + underlyingInstrumentId`，不通过名称推断。外部行情源单独保存平台代码，`RestReferenceMarketProvider` 的 URL 模板使用 `{externalSymbol}`。

“修改配置不丢数据”不代表允许重新解释历史金额。币种 `asset_id` 永久，账务代码及 `scale_units` 固定，显示名称和 logo 可以修改。账户/Core 仍使用不可变账务代码，不能把可编辑显示名称当作账务资产代码。`asset_networks` 是币种的多个链上入口，不是多个余额。

## 客户端协议

先获取 `/api/v1/gateway/instrument/default`（必须指定产品线），进入交易页只初始化当前标的。打开币对选择器时获取该产品线目录。目录响应同时包含 ID 和名称，名称用于展示，订阅和操作传 ID。例如：

```json
{"op":"subscribe","channel":"depth","productLine":"LINEAR_PERPETUAL","instrumentId":"604"}
```

`604` 只是本机种子数据示例，客户端必须使用实际目录返回值。行情、订单、持仓缓存及收藏均按产品线和 ID 隔离，更新使用相同身份替换旧值。

兼容 API 的外部 `symbol` 仅在适配边界解析。`GATEWAY_BINANCE_SYMBOL_ALIASES` 显式配置如 `{"LINEAR_PERPETUAL:BTCUSDT":"604"}`，未配置时拒绝请求，不把名称作为内部 ID 透传。

## 本机启动和状态格式

Core 协议版本为 8。项目尚未上线，不实现旧名称状态的兼容或回退，切换时重新初始化本地交易数据库、Kafka/Redis 和 Core Archive，账号及安全配置可单独保留。

`./scripts/local-perpetual.sh up` 在启动前增量构建当前源码，再暂存六个服务 JAR。`scripts/render-local-market-ids.py` 首次将 20 个种子市场解析为 ID，保存到运行目录 `market-ids.json`；后续启动沿用这些 ID。修改显示名称不会重新映射。发现缺失/停牌的配置 ID 时明确报错。已运行环境需先 `down`，再 `up`。

## 历史订单与恢复

`CommittedTradeReplay` 在 Realtime 的独立导出线程读取已提交 Archive 命令，同时产出成交和完整订单状态。`CommittedOrderProjectionRepository` 按 `(productLine, orderId)` 写入 `core_order_projection`，只接受更高订单版本；累计成交数量、累计成交金额、均价和手续费使用与 WebSocket 相同的 `CoreOrderStateView` 编码。订单与查询水位同一数据库事务提交，随后发送成交 Kafka 事务，最后写本地检查点。数据库失败不推进检查点，重试不能用旧状态覆盖终态。该读模型不参与撮合或资金结算。同一 SQL 批次内按订单 ID 合并到最高版本，再使用 PostgreSQL 批量写入，避免单个 `ON CONFLICT` 语句多次修改同一订单；合并容器只在本次持久化调用内存在，提交或失败后释放，不在 Core 热路径维护副本。

币种与网络管理目前负责目录、配置校验和审计；本轮只启动交易相关六个服务，不启动 Wallet，也不代表已完成外部链上充提、手续费及确认数的联调。

相关入口：`InstrumentIds`、`InstrumentRepository`、`InstrumentStorageService`、`InstrumentSnapshotCache`、`AssetConfigurationService`。

做市专用账户由该产品线的 `surprising.trade-export.market-maker-account-ids` 明确列出（包含模拟成交账户）。普通用户订单全部入历史查询表；纯做市互成交和未成交撤单不入表。做市订单只要曾与普通用户成交，完整累计状态就持续保存，包括后续内部成交、撤单和重启后的更新。过滤只位于历史持久化边界，不影响 Core 权威状态、资金流水、公开成交或 WebSocket。

## 后台日常配置权限（2026-10-05）

`GatewayProxyService` 对合约新增、编辑、状态变更、做市定义及参数、价格配置和生命周期运行参数校验管理员身份与对应 `admin.gateway.<service>.write` 权限，并记录操作审计；这些明确列举的 POST 配置接口不要求第二位管理员审批。合约和配置服务继续校验变更原因、预期版本及参数边界，版本冲突必须重新读取后提交。做市暂停、恢复保留既有状态流转及审计。

账户余额调整、保险基金划拨、代客撤单、维护清算等资金或业务干预接口仍要求审批，不通过关闭全局审批开关来简化日常配置。未列入日常配置清单的接口沿用原审批规则。管理端 `src/api/admin.ts` 与网关保持同样的日常接口范围。

`GatewayProxyServiceTest` 覆盖日常配置无需第二位管理员、缺少服务权限拒绝访问，以及资金、维护干预继续要求审批；本次 40 项测试通过。

原独立 `ClusterInstrumentSeedMain` 手工初始化入口已移除。合约注册统一由网关从后台数据库目录通过 `InstrumentCoreSyncService` 自动完成，启动脚本不再维护第二套合约状态及风险预算环境变量入口。
