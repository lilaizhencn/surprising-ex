# ADR-0001: Aeron Cluster 作为交易主链路权威

- 状态：已接受
- 日期：2026-08-18

## 背景

当前部分 Provider 仍通过 JDBC repository 读取或写入账户、资金、风险、保险、强平和业务审计数据。即使交易撮合已经进入 Aeron Cluster，这些依赖仍会让 PostgreSQL 成为交易命令完成和故障恢复的隐含前提。Instrument 服务的 PostgreSQL 管理职责不在本次迁移范围内。

## 决策

所有交易裁决和交易当前态查询统一进入 Aeron Cluster。每条产品线使用独立三节点 Cluster；Cluster 内单写者按 position 顺序更新 User State、Book State、Risk、Funding、Insurance、Liquidation、ADL、Trigger、Settlement 和已导入的 Instrument Version。Provider 只负责协议转换、权限、调度和订阅，不保留第二裁决状态。Instrument 服务继续通过 PostgreSQL 管理产品配置与生命周期，并以版本化 Aeron command 将交易所需状态导入 Core。

## 明确禁止

- 交易 Provider 为完成交易命令而创建 DataSource、执行 migration 或探测 PostgreSQL；Instrument 服务除外。
- 下单、撤单、成交、资金、风险、强平、触发单、交割、行权和恢复调用 JDBC、Valkey 或 Kafka 等待结果。
- 用数据库 lease、sequence、outbox 或 projection 决定业务顺序。
- 把 Kafka consumer、PostgreSQL projection 或缓存当作当前态权威。

## 历史与登录边界

Cluster Log/Archive 是交易恢复和历史事实的原始来源。当前态查询走 Aeron Query；审计与历史事实由独立 exporter 发布到 Kafka，再由 projector 幂等写入 PostgreSQL、分段文件或对象存储。Exporter 不连接 PostgreSQL；Kafka、projector 或审计 PostgreSQL 停止、延迟或删除都不能影响在线交易。Instrument PostgreSQL 的可用性只影响配置管理和新版本发布，不参与已导入版本下的命令裁决。

用户认证、MFA、权限和会话也由独立 Gateway Auth Cluster 保存为可快照状态；访问令牌使用签名 JWT。充值、提现和外部托管回调先变成经过幂等校验的 Aeron command，再由 Core 原子改变资金状态。

## 迁移规则

1. 先定义每个 JDBC repository 的 Aeron command/query/fact 替代物。
2. Provider 构造图默认禁止数据库 Bean；在线 classpath 不包含数据库驱动。
3. 完成六产品线交易主链路脱库门禁：预先导入 Instrument 后，停止审计 PostgreSQL、Kafka、Valkey，仍能下单、查询、恢复和资金对账。
4. 通过门禁后删除交易主链路 JDBC 实现；仅保留 Aeron→Kafka exporter、隔离 projector 和离线历史工具。

## 后果

需要把当前态查询从分页 SQL 改为 Core 内存索引，并定义 Archive Replay 的分页游标、保留策略和快照 manifest。这增加了状态机和恢复工作，但消除了数据库单点、同步 IO 和数据双写竞争，满足无数据库上线目标。
