# 2026-10-08 测试服务器部署记录

## 部署范围

服务器：SSH 别名 `surprising-ex`，公网 API `https://ex-api.tokdou.com`。
按用户最新要求仅运行 `LINEAR_PERPETUAL`；SPOT 服务停止并禁用，保留其持久数据。
统一 Gateway 使用 9094，不再启动独立 SPOT Gateway 9194。
产品线接入从数据库后台设置恢复，不使用 `GATEWAY_PRODUCT_LINES`。

最终应用代码为拉取 master 时的
`292e4e041d26e8929d890366a2cd708e063294f7`。
服务器直接从 Git 获取源码，使用 HotSpot OpenJDK 27、Maven 3.8.7 构建，未上传本地 JAR。

相对上一应用版本 `310b535e`，运行代码改动涉及：

| 模块 | 改动 | 部署应用 |
| --- | --- | --- |
| aeron-client | 应用 MediaDriver 默认使用 SHARED | 五个应用共同依赖 |
| derivatives-lifecycle | insurance 复用生命周期 Aeron 连接池 | derivatives-lifecycle |
| realtime | 复用受提交位置约束的 Archive 回放会话 | realtime |
| gateway | WebSocket 发送等待状态通知，减少轮询 | gateway |
| deployment/scripts | 单分区测试环境消费者并发预设 | 五个应用 |

因此服务器重打包并替换 Gateway、price、realtime、derivatives-lifecycle、maker。
本轮更新没有重启永续 Core，没有再次重建 Kafka，也没有删除 K 线状态。
独立 admin-web 已在上一迁移阶段从 `2eb62312852cf9f80fffa0919b7df9ac69054e31`
使用服务器 Node 22 / pnpm 10 构建；本轮继续使用该静态产物。

## 服务器构建回归

五个应用及 Aeron tools 的 Maven clean package 通过。
以下为此版本服务器 Surefire XML 的实际统计，共 20 项，失败、错误、跳过均为 0。

| 测试 | 数量 |
| --- | ---: |
| SurprisingAeronClientThreadingModeTest | 4 |
| InsuranceAeronGatewayTest | 6 |
| ArchiveReplayTest | 2 |
| ArchiveReplayIntegrationTest | 1 |
| ClientConnectionIsolationTest | 4 |
| ClientWebSocketHandlerTest | 3 |

Archive 集成测试运行隔离的真实 Archive，验证提交边界和不完整消息恢复。
前一轮完整本地多产品验证见 [单 Gateway 文档](multi-product-gateway.md)。
本次测试服务器验收范围只有 U 本位永续，不代表其他五条产品线已在服务器运行。

## Kafka 单分区迁移

在此前统一 Gateway 迁移阶段，专用测试 Kafka 从 57 个 topic、1106 个分区，
重建为每个 topic 一个分区，包括内部 offsets/transaction topic。
旧 Kafka 数据目录完整保存在私有备份中；旧 K 线 Streams 状态移入备份，
从切换时最新成交继续消费，保留已有数据库 K 线。
本次 master 应用更新保留这些 Kafka 数据、消费位点和新 Streams 状态。

本轮实际审计：57 个 topic、57 个分区，全部为 1；19 个活跃消费组、
19 个成员，无分区分配的成员为 0。
五个应用共采样到 19 个 Kafka listener/Streams 工作线程，8 秒采样中
累计占用约 0.353 个 CPU 核，单个线程最大约占一个核的 5.834%。
这是实时运行时的短窗口观测，不能推导整机 CPU 或长期负载变化。

五应用的另一组 8 秒进程采样：更新前合计约 3.963 核、13512 MiB RSS；
新版本启动及验收后约 4.740 核、9696 MiB RSS。
两个采样窗口的运行阶段和业务负载不同，不能据此宣称整体 CPU 已下降。
消费者并发/分区匹配及无闲置成员的结论来自上述 Kafka 实际分配审计。

测试预设位于
`deployment/test-single-node/kafka-single-partition.env`，
默认 listener、price、index、mark、insurance、funding、WebSocket 并发及
K 线 Streams 线程均为 1。永续单节点启动入口自动加载该预设。
Aeron realtime 模式不再启动重复的 WebSocket Kafka fanout 消费者。

## 数据保护及回滚

切换五个应用时永续 Core PID 保持不变。
在停止报价及应用服务后读取全部 40 个用户的 Core 余额、冻结、持仓和 Treasury；
新 Gateway 接入后、恢复报价前再次读取，规范化结果完全一致。

## 实际业务验收

| 范围 | 结果 |
| --- | --- |
| 启动恢复 | 数据库已启用的 LINEAR_PERPETUAL 自动接入；五应用健康检查 UP |
| 测试资金 | 专属验收用户充值 1000 USDT，重复相同业务标识不重复入账 |
| BTC 604、ETH 653、SOL 866 | 各自通过 post-only 挂单、撤单、市价开仓、reduce-only 平仓 |
| 资金核对 | 期初资金 + 本轮已实现盈亏 − 本轮手续费 = 期末余额，差额为 0；手续费合计 84777710 个 USDT 最小单位 |
| 清场 | 验收用户持仓、冻结为 0，剩余测试资金全部收回 |
| 权限清理 | 移除验收用户临时 ADMIN，撤销其全部会话，删除临时登录凭据；原管理员保留 |
| K 线 | 三合约最新 1m K 线有成交，OHLC 边界正确；公网复查数据距当前不足 3 分钟 |
| 成交导出恢复 | 沿用原 checkpoint，新进程运行后 checkpoint 实际推进，无清空和历史重放 |
| WebSocket | 本机和公网各用一条连接完成 JWT 认证、订阅并收到永续私有快照 READY |
| 公网 | `/healthz`、runtime、asset-scales、USDT→USD 估值、后台页面及三合约盘口/K 线均成功 |

公网最初使用 Python 默认客户端的健康检查收到 403；随后使用 curl 经同一公网域名
验证全部接口，并用 Node WebSocket 验证公网连接通过。
没有为通过验收修改或关闭 Cloudflare 防护。
初次失败及复查成功日志均保留，最终通过标记位于本轮备份目录的 `public-opened`。

这次未在服务器启用其余五条产品线，未发起链上充值提现，
未新增强平、到期交割或资金费边界的线上触发测试。

## 备份及回滚材料

服务器私有证据和回滚材料：

- `/var/backups/surprising/20261008-unified-310b535e`：初次迁移数据库备份、旧 Kafka/Streams、构建及初次验收。
- `/var/backups/surprising/20261008-master-292e4e0`：本轮进程状态、应用日志、前后账户快照及验收。
- 当前版本目录：`/opt/surprising-ex-20261008-master-292e4e0`。
- 上一应用目录：`/opt/surprising-ex-20261008-unified-310b535e`。

备份目录仅 root 可读，包含部署密钥及进程环境，不应公开或提交到 Git。
本轮回滚脚本只回退五个应用和代理配置，不重启 Core，不回退 Kafka 或成交消费状态，
不启动 SPOT。执行前应先排查和保存故障证据。
