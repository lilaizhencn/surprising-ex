# C2C Java 分层整改

本文件记录已落地的职责边界。全项目尚未整改完，逐项状态以 `audit/java-mvc-remediation-status-20261021.csv` 为准。

## 后台列表与市场准入

业务顺序：管理员权限校验 → Controller 校验分页、状态和用户编号 → `C2cAdminQueryService` 读取当前页 → 批量补充市场信息 → 返回脱敏列表。列表仍为 `items/hasMore/offset`，int64、金额、版本维持字符串协议，未增加付款明文或证件原件。

原 `C2cAdminQueries` 已删除。七类独立 SQL 分别进入对应表的 Repository，Service 不再依赖 `JdbcTemplate`。商家、收款账号、资金意图、申诉、举报列表使用明确的数据库投影和 HTTP 响应记录；广告/订单仍使用当前公共视图，后续继续统一 C2C 的类型化契约。

广告和订单列表各最多两次查询：当前页根记录一次，当前页去重市场 ID 一次。无记录时只查询根表。读取 `limit+1` 条判断 `hasMore`，只为返回的 `limit` 条读取市场信息；不建立跨请求市场缓存。Controller 限制 `limit` 为 1–100、`offset` 为 0–10000。

原 `C2cMarkets` 已删除。`C2cMarketService` 聚合开放状态、商家质押准入和原资金意图身份校验。市场模型归 `c2c.model`，三个 Repository 分别负责配置、市场和质押事实。资金意图身份、金额、SPOT 账户类型及原 Core 续办顺序沿用现有规则。

## 本批关联读取例外

| Repository | 所属表 | 关联及用途 | 边界 |
| --- | --- | --- | --- |
| `C2cMarketRepository` | `c2c_markets` | `assets` 提供权威精度和交易开关 | 资产唯一键一对一；不复制资产配置；只读关联 |
| `C2cMerchantRepository` | `c2c_merchants` | 档案昵称、KYC状态、最新质押、质押资产精度 | 商家为稳定分页根；最新质押最多一条；不读付款资料或KYC原件 |
| `C2cPaymentAccountRepository` | `c2c_payment_accounts` | 档案昵称 | 账号为稳定分页根；不选择付款密文或完整账号 |
| `C2cMerchantBondRepository` | `c2c_merchant_bonds` | 当前唯一政策的资产和最低金额 | 原子读取锁定质押能否覆盖政策；不更新政策表、不查询Core余额 |

`C2cAppealRepository` 和 `C2cReportRepository` 仅访问各自表。广告、订单、资金意图的后台根记录查询进入已有对应 Repository；它们其余职责仍在后续 C2C 批次处理，不能据此视为全部单表整改完成。

## 验证

真实 PostgreSQL、Spring 事务代理和 HTTP 编解码覆盖七类列表、脱敏、审批、资金意图续办及查询次数。临时恢复逐行市场查询后，查询预算测试捕获 3 次查询超出 2 次预算；恢复批量读取后再执行干净构建回归。

实际命令、逐类测试结果、源码摘要及未验证范围保存在 `validation/java-mvc-c2c-admin-20261021.json`。本批未启动真实 Core、wallet 或外部对象存储；这些环境不能由受控 Core 数据库测试代替，涉及后续资金写路径的验证仍保留在完整整改计划中。

## 安全凭据、审核与收款事实

本批继续将以下聚合中的 SQL 移入对应表的 Repository，事务仍由原 Service 承担：

- 资金验证：共享锁读取用户验证时长 → 检查当前登录令牌摘要的有效凭据 → 验证安全因子 → 保存有期限凭据。`C2cAccess` 保留独立提交边界，失败次数和验证码消费不会被后续业务回滚撤销；`C2cProfileRepository`、`C2cSecurityGrantRepository` 只访问各自表。
- 商家审核：权限与审批 → 锁用户和商家行 → 核对当前版本、KYC及质押 → 更新商家，必要时暂停广告 → 必需审计 → 共同提交。举报裁决同样将状态变更与审计放在原事务内；独立审核人与交易参与者规则保持。
- 收款账号变更：检查当前收款方式和附件归属 → 加密资料 → 先暂停并锁定引用账号的广告 → 按本人/原版本更新账号，重新进入待审核 → 共同提交。版本冲突必须撤销前面的广告暂停；已创建订单继续读取原加密快照。

收款账号、付款方式和附件分别由 `C2cPaymentAccountRepository`、`C2cPaymentMethodRepository`、`C2cAttachmentRepository` 负责。Repository 返回模型，审批、身份归属、状态约束和加解密留在 Service。用户行锁回到已有 `GatewayUserRepository`，没有另建跨表审核 Repository。摘要计算归 `c2c.utils.C2cDigests`，原请求及审批正文的摘要算法和编码保持不变。

这些类仍有待处理的包归类和入口参数/类型契约问题，后续批次继续完成；本批只关闭已验证的 Service 直接 SQL 问题。

本批定向45项及干净构建扩大107项全部通过；四个事务负例均能捕获部分提交。完整证据及依赖重建记录见 `validation/java-mvc-c2c-services-20261021.json`。

## 报价、下单和订单时间线

`C2cOrdersRepository` 仅写订单表；报价由 `C2cQuoteRepository` 负责，时间线由 `C2cOrderEventRepository` 负责。`C2cOrders` 在原事务内决定买卖双方、初始状态、价格/费用快照和付款截止时间，并聚合库存、订单、事件与原资金意图。Repository 不再替 Service 选择交易双方或追加另一张表的事件。

保留原锁顺序：请求回执和配置读取之后，创建订单先锁用户，再锁广告和报价；已有订单操作先锁广告，再锁订单，裁决继续锁申诉行。广告发布与订单操作的用户、商家、付款方式、关系、证明附件和计数 SQL 分别进入对应表的 Repository。报价仍使用数据库时钟计算业务指定的60秒有效期；订单已有收款、价格、费率和条件快照不随配置或账号修改而变化。

广告的初始 `RESERVING/ACTIVE` 状态也由发布 Service 明确选择。广告/订单的续办查询保留只读关联原资金意图的例外，并具体注释筛选目的、批次和不读取Core余额的边界。

`C2cAd`、`C2cOrder`、`C2cQuote` 和订单状态归 `c2c.model`。`C2cOrderPlacement` 是单次持久化交接的数据：只包含业务已经确定的创建事实，不维护另一份库存、余额或订单状态。

事件写入故障验证覆盖创建、付款、放币和终态续办。特别是受控Core回复已确认意图、终态事件写入失败时，订单与库存仍留在可续办状态；修复后沿用原已确认意图收尾，不重新发送转账。

本批干净构建113项通过，0失败、0跳过；移除四个交易入口事务后，5项中的4项按预期失败。完整验证见 `validation/java-mvc-c2c-orders-20261021.json`。

## 广告资格与价格预警

列表和预警共用普通只读视图 `c2c_eligible_ads`，资格来自原广告、市场/资产开关、商家状态、账户、有效KYC、已锁定质押及当前质押政策、最低库存限额和有效付款方式。视图不保存派生状态；唯一身份关联、唯一有效质押及付款方式的EXISTS保证每个广告至多一行。查看者自身、双向屏蔽、价格和统计条件仍在对应有界查询中限定。

这修复了原预警查询缺少锁定质押及政策金额条件的问题。视图定义在 `deployment/migrations/20261021-c2c-eligible-ads.sql`，同一DDL已并入 `init.sql`；测试schema也执行该定义。后台全量管理列表继续读取业务根表。

原 `C2cNotificationJobs` 已删除。`c2c.task.C2cNotificationTask` 保留原5秒默认间隔、开关和调度器，只调用 Service。`C2cNotificationService.processBatch` 在真实业务事务中领取最多32条预警、关闭本次触发并批量写通知，然后清理最多100条过期凭据及100条超过一天且未被订单引用的报价。失败整体回滚，锁定行由并行实例跳过；过程仅有数据库操作。

`C2cPriceAlertRepository` 只写预警表，关联市场只取通知字段，资格视图和关系表只用于EXISTS筛选，方法注释明确锁定、用途和批次。`C2cNotificationRepository` 只写通知表，凭据和报价清理各自回到对应Repository。通知的标识与价格仍按字符串协议返回，已被订单引用的报价保留。

本批定向32项、干净构建扩大119项全部通过。资格/事务负例6项中3项按预期失败；DDL同源与重复执行已验证。完整记录见 `validation/java-mvc-c2c-alerts-20261021.json`。
