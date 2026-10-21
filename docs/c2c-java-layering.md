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
