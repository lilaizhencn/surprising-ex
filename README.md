# Surprising EX

## 邀请返佣与 KOL（2026-10-28）

邀请、多层团队、贡献核算与结算放在 Gateway 的 `com.surprising.affiliate`，沿 MVC 分层。普通用户注册即有邀请码，顶层和合作下级的 KOL 权限均由后台开通；获批下级接受邀请后可以继续拓展本分支。默认普通和顶层比例均为 20%，后台可改比例、自动结算和周期。

2026-10-29：未开通 KOL 的 Web/App 仅展示基础邀请返佣、好友与逐人返佣、明细和结算；KOL 团队模块需后台管理员开通后显示。接受合作邀请不再自动开通权限；旧版自动开通账号上线前需后台复核。见 [开通边界验证](docs/validation/affiliate-access-20261029/README.md)。

业务消费 Realtime 独立 Archive 回放导出的已提交实际手续费，不向下单、撮合或在线结算添加推广状态。佣金按产品和资产分别记账，经现有账户划转从已备资预算账户发放，支持未知结果原身份恢复。

路径采用迭代与去重，单笔核算只沿实际归属链，按产品分批推进；默认安全上限 4096 个路径节点，超限费用完整隔离，连续确认游标保留，健康团队继续；隔离容量满时背压停止接收，不截断分账。逐层累计舍入防止小额碎片少分，结算认领带租约隔离，付款前校验关联应付金额。多团队专项已覆盖 1000 队六产品与 10000 队同产品、4 个独立业务容器的并发和资金恢复，见 [多团队记录](docs/validation/affiliate-teams-20261029/MULTI_TEAM_VALIDATION.md)；仍未完成真实网络生产容量验收。运行参数、迁移和限制见 [稳定性专项](docs/validation/affiliate-safety-20261028/README.md)。

部署迁移、完整分配规则、权限和 API 见 [设计文档](docs/affiliate-design.md)，实际命令与验证边界见 [验收记录](docs/validation/affiliate-20261028/README.md)。


## Project overview

Surprising EX is a Java and Maven based exchange backend under active development. It provides order matching, accounts, risk management, market data, and APIs for six separate product lines:

| Product line | Main functions |
| --- | --- |
| Spot | Orders, matching, asset holds, and settlement |
| USDT-margined perpetuals | Positions, margin, funding, and liquidation |
| Coin-margined perpetuals | Inverse contract accounting, funding, and risk |
| USDT-margined delivery futures | Positions and expiry settlement |
| Coin-margined delivery futures | Inverse contracts and expiry settlement |
| Options | Premiums, positions, exercise, and expiry |

Product line identity controls routing and keeps instruments, accounts, trading state, events, and risk rules separate. The single-node deployment below starts **USDT-margined perpetuals only** (`LINEAR_PERPETUAL`).

## Architecture

构建基线：HotSpot JDK 27、Spring Boot 4.1.2、Spring Cloud 2025.1.4；框架版本统一由 `surprising-parent/pom.xml` 管理。

Aeron 基线为 **1.54.0**（由 1.53.3 升级），Client、Driver、Archive、Cluster 统一使用父 POM 管理的 `aeron-all`。
本次重点包括 Java 线程 CPU 亲和性、UDP Traffic Class、收发路径优化，以及 Cluster 超时和 Archive 关闭/恢复修复。
Realtime 的 5 处 `RecordingPos` 测试调用已补充具体 `archiveId`；新增绑核功能未默认开启，也不替换现有业务线程绑定。
更新内容、兼容性核对和验证范围见 [Aeron 1.54.0 升级记录](surprising-aeron-core/validation/aeron-1.54.0-20261028.md)。

产品线定义、topic 命名、币对契约和共享合约公式统一由
[`surprising-instrument-api`](surprising-instrument-api/README.md) 提供。
开发分层与约束见 [AGENTS.md](AGENTS.md)；性能优化和压测前必须读取
[性能子规范](AGENTS-performance.md)。

Core startup, snapshots and command recovery: [Aeron architecture](surprising-aeron-core/README.md).

The single-node stack has six Java processes:

| Process | Responsibility |
| --- | --- |
| Aeron Core | Ordered commands, matching, authoritative balances and positions, snapshots, and recovery |
| Gateway | Authentication, instrument, account and order APIs, WebSocket access, and the shared application Aeron MediaDriver |
| Price | Index and mark prices |
| Realtime | Market data, candles, trade export, and realtime routing |
| Derivatives lifecycle | Funding, risk scans, and liquidation workflows |
| Maker | Reference-market-based quotes and trading |


## 单节点永续压测记录

2026-10-28 使用10,000个账户、256币对、每批20单、全局256报文窗口执行4/8/16发压线程递增测试。16线程档因标记价过期拒单失败，稳定窗存在系统换页与CPU限频，尚未确认Core饱和，吞吐不能作为容量上限。8线程档资金、持仓、冻结与原Archive重启审计通过。详见 [压测报告](docs/validation/perpetual-saturation-20261028/REPORT.md)。

## 网关安全

Gateway 现使用 Spring Security、安全浏览器会话、共享限流/邮件与上传预算、私有健康检查和上传恶意内容扫描。密码采用新 600000 次规则，不兼容旧轮数。实现与部署要求见 [网关安全](docs/gateway-security.md)。
