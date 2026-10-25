# Aeron 七项运行路径简化验收

任务基线为 `e8302bb3b`，按 6 → 5 → 7 → 1 → 2 → 3 → 4 实施后统一验证。主要实现已进入 `9e7c02fc2`；全量验证纳入共享 master 同期的 `1ac0ff7f7` 和本轮后续修正；全量运行期间又合入 `5ed64763b`，另对最终合并状态做干净补验，保留其他任务改动。

## 最终实现

| 项目 | 删除的复杂度 | 保留的实际边界 |
| --- | --- | --- |
| 6 | `pollEgressBounded` 测试算法副本 | 直接验证真实客户端的轮询预算 |
| 5 | Session 单项取响应、负数哨兵和遍历 pending 的备用路径 | 唯一批量响应接口，单 Dispatcher 关联与完成请求 |
| 7 | 八个便利/注入构造器和重复初始化 | Spring 入口及完整独立装配入口，共用初始化 |
| 1 | 单分片快照特例、内层操作注册表、自定义不可取消 Future、同步 Matcher call/await 包装 | 全部分片在提交栅栏捕获；Owner 负责一致性、完成保护、审计与超时 |
| 2 | 内部查询编号、独立成功/失败/风险续投递表 | 一张 Owner UUID 表；任务自己持有结果，保留有界路由、完成位和续投递 |
| 3 | 2048 个原始响应交接槽及额外解码读者计数 | 回调内编码、单一解码租约；背压队列只持有独立字节，保留 FIFO、预算和角色代次 |
| 4 | 同步 Lane 泛型结果数组、结果掩码和包装层 | 两处业务直接使用已有 `onLane`，每 Lane 执行一次，保留预检与回滚 |

没有新增工厂、注册框架或兼容通道。没有修改 HTTP/WebSocket、交易协议字段、快照线格式或资金公式。

## 验证中修正的边界

- 释放快照栅栏后，原生捕获可能仍在 Matcher 上执行。实际捕获完成前继续拒绝新快照及交易，避免改变捕获中的状态。
- 在途快照使用 JDK 只读完成信号；取消调用方的等待视图不提前清除在途保护。用例实际阻塞并完成原生持久化。
- Matcher 读取遇到 `Error` 时先完成读取 Future，再将致命错误抛到工作线程边界，避免聚合快照永久等待。
- 基准夹具在排空 Owner 后取得账户访问权并进入原 `onLane`，保持生产账户归属校验；修正旧协议帧偏移和 TWAP 显式字段。
- D1 夹具保留最初交易异常，关闭异常作为 suppressed；覆盖单笔/批量及建仓/反向平仓，保留资金与持仓断言。
- 并行流水线测试断言实际窗口、水位和响应边界，不依赖一次轮询恰好完成多少调度工作。

## 环境与统一验证

- Corretto HotSpot JDK 27，`27.0.0.33.1`；Maven 3.9.16。
- 执行前可用磁盘 194 GiB；验证期间保持约 194 GiB。
- 共享工作区曾有其他 Maven 进程重建 target；使用本轮独立 master 验证副本，未停止其他任务进程。
- 最终干净构建前核对全部 2452 个跟踪文件，工作区与独立副本逐文件 SHA-256 一致。
- 独占 PostgreSQL 18，绑定 `127.0.0.1:52640`，数据库 `aeron_seven_items`，使用当前 `init.sql` 初始化；不接触外部数据库，不启动 wallet。

统一命令：

```sh
MAINTENANCE_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:52640/aeron_seven_items \
INSTRUMENT_TEST_DB_USER=postgres INSTRUMENT_TEST_DB_PASSWORD='' \
mvn -B -pl surprising-aeron-core/surprising-aeron-benchmarks,surprising-gateway -am clean verify
```

16 模块全量命令退出 1，完成全部测试后停在 Gateway。结果按本轮干净构建的 Surefire XML 汇总：

| 范围 | Tests run | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| Aeron 五个子模块 | 2263 | 0 | 0 | 1 |
| 其他依赖与父模块 | 429 | 0 | 0 | 3 |
| Gateway | 1682 | 1 | 0 | 354 |
| 合计 | 4374 | 1 | 0 | 358 |

Aeron 分项：protocol 257、client 69、service 1604、tools 20、benchmarks 313。326 项多分片流水线组合、125 项批量交易夹具、5 项 D1 终态资金检查均通过。维护 PostgreSQL 集成 38 项和多产品资金集成 1 项也通过。

唯一失败为范围外已有的 `AdminCursorPageTest.pageBuildsStableNextCursorFromLastReturnedRow`：测试要求拒绝旧 `epochMillis:id` 游标，共享工具仍接受它。生产工具及测试自 `e8302bb3b` 起均未变；单独以 JDK 27 编译基线工具，旧输入实际返回 id=99，独立复现这组既有矛盾。本轮没有改变管理端分页行为。

358 项跳过：macOS 的 Linux CPU 绑定检查 1 项；价格 PostgreSQL 配置缺失 3 项；Gateway 354 项缺少各自数据库、浏览器或真实服务环境。完整跳过原因按环境变量汇总到旁边的 JSON，不把环境跳过视为已测通过。

最终合并干净补验基于 `5ed64763b` 加本轮改动，重编译所有 16 模块并选择 107 个相关测试类；命令退出 0，16 模块均为 SUCCESS。实际执行 **1013 项，0 failures、0 errors、0 skipped**：protocol 257、client 69、service 335、benchmarks 313、Gateway 39。覆盖 Owner 装配、查询、快照、资金、恢复及两组 Gateway 集成。精确命令数组及测试类选择列表见 JSON 的 `postmergeAffectedVerification.run`，不与全量数量重复相加。

补充检查：19 项快照、致命读取和账户/账本查询定向检查通过。反向验证临时移除取消隔离后，快照保护用例按预期失败（1 failure、0 errors）；已逐字节恢复源码，最终干净构建重新验证恢复后的实现。38 项维护 PostgreSQL 集成及 1 项多产品资金集成已在最终合并补验中再次通过。

## 范围与资源清理

本轮运行功能测试及有限基准夹具调用，没有执行 JMH、JFR、性能对比、长期压力测试或生产部署，不据此给出吞吐和分配收益结论。未影响前端契约。

测试数量、模块结果、跳过原因、既有失败的独立基线复现、日志 SHA-256 与源码核对摘要见 [机器可读验收摘要](seven-item-simplification-20261025.json)。最后一次补验的 2383 个非 Markdown 文件与工作区逐文件一致，验证开始后未变。

专属 PostgreSQL 已用 `pg_ctl -m fast -w stop` 停止；未遗留本轮 Java/PostgreSQL 进程。独立验证副本、数据库目录、临时基线编译和原始日志均已在摘要归档后删除。保留其他任务资源；共享 CodeGraph 已完整重建，索引无待同步修改。
