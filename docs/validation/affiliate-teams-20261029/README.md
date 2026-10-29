# 返佣团队隔离与 Gateway 编译验证

2026-10-29，在 `master`、起点 `2e37775ef` 验证本轮全部待提交后端改动。环境为
HotSpot Corretto 27（27+33-FR）、Maven 3.9.16、PostgreSQL 18.4。

## 改动

- 团队关系异常持久化隔离，同产品健康团队继续核算；接收与确认断点分离，未解决费用阻止确认断点越过。
- 隔离费用保留不可变来源身份、有界容量与重试，修复后使用原发生时间的历史关系和费率；数据库与金融身份错误仍回滚事务。
- 自动结算保留周期费用截止时间，迟到的成熟费用可补结，不提前结算下一周期。
- `AffiliateLedgerRepository` 的 JDBC 批量写入显式声明回调参数类型，减少泛型推断嵌套，不改变 SQL 绑定含义。
- 删除无调用的测试辅助类 `CoreTestCompletion`；维护和多产品资金测试直接读取现有余额，不再调用已删除的 Core 测试辅助方法或异步作用域。
- 迁移见 `deployment/migrations/20261029-affiliate-team-isolation.sql`，`init.sql` 同步包含相同 DDL。已有库部署前须停止所有 Gateway 写入。

## 编译

```bash
mvn -DskipTests clean test-compile
mvn -DskipTests test-compile
```

第一次干净构建暴露一个残留的 `CoreUserState.totalUnits` 调用，修复后第二条命令的
23 个 reactor 项全部成功。后续相关功能测试又编译了 Gateway 与依赖，包括最后新增的测试源码。
最早 `mvn -pl surprising-gateway -am -DskipTests test-compile` 暴露的 8 处旧 Core API
引用错误也已修复。以上 `test-compile` 仅编译源码和测试源码，没有执行全量测试。

## 相关功能测试

专用临时数据库为 `affiliate_ide_20261029_101536`，未使用业务数据库。
返佣夹具在其中创建并清理独立 schema。环境变量使用本机 PostgreSQL 测试连接：

```bash
export INSTRUMENT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:5432/affiliate_ide_20261029_101536
export MAINTENANCE_TEST_JDBC_URL="$INSTRUMENT_TEST_JDBC_URL"
export INSTRUMENT_TEST_DB_USER=atomex
export INSTRUMENT_TEST_DB_PASSWORD=''

mvn -pl surprising-gateway -am test \
  -Dtest='AffiliatePostgresTest,AffiliateSafetyPostgresTest,AffiliateTeamIsolationPostgresTest,AffiliateResourceTest,CommissionAllocationTest,CommittedPayoutTransferTest,MaintenanceIntegrationTest#cancelsOrdersAndTriggersRestartsFromPersistedActionsAndReleases,MultiProductMoneyIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

首次执行 51 次通过，维护场景的 6 次执行因独立数据库缺少维护表而报错。
从当前 `init.sql` 提取 `trading_maintenance_task`、`trading_maintenance_action` 两张表及索引，
只在该临时库执行 DDL 后，单独重跑维护场景，6 次全部通过：

```bash
mvn -pl surprising-gateway -am test \
  '-Dtest=MaintenanceIntegrationTest#cancelsOrdersAndTriggersRestartsFromPersistedActionsAndReleases' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

收尾时工作区新增的独立团队费率测试单独补跑通过：

```bash
mvn -pl surprising-gateway -am test \
  '-Dtest=AffiliateTeamIsolationPostgresTest#independentTeamsKeepTheirOwnRootRatesAndBranchShares' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

按最终成功执行去重，共 8 个测试类、58 次执行，失败 0、错误 0、跳过 0：

| 测试类 | 成功执行数 |
| --- | ---: |
| AffiliatePostgresTest | 15 |
| AffiliateSafetyPostgresTest | 19 |
| AffiliateTeamIsolationPostgresTest | 9（首轮 8，加补跑 1） |
| AffiliateResourceTest | 4 |
| CommissionAllocationTest | 3 |
| CommittedPayoutTransferTest | 1 |
| MaintenanceIntegrationTest | 6（只选上述参数化场景，覆盖六产品线） |
| MultiProductMoneyIntegrationTest | 1 |

覆盖关系异常隔离、健康团队继续推进、队列满后停止与恢复、事务中断、乱序修复的确认断点、
不可变费用与核算凭证、原历史费率、异常循环、独立团队费率、迟到成熟费用、
累计舍入与付款恢复，以及维护撤单/重启的资金守恒和多产品资金隔离。

## 证据与未测范围

完整命令、失败与成功日志、Surefire XML、摘要和源码哈希归档于
`/Users/atomex/.codex/artifacts/affiliate-commit-20261029-102816`。

按用户要求，未运行全量测试、压测、JMH/JFR、长稳、真实 Aeron 网络/三节点部署。
`AffiliateTeamWorkload` 只编译、未执行；[原计划](plan.md) 中规模与采样部分不代表实测结果。
本轮没有重新验证 Web、运营后台或 App 交互，也未以此记录宣称完整前端验收通过。
IDEA 后续检查按用户要求停止，没有修改 IDE 或全局 SDK 配置。
