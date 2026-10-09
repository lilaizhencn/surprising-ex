# 测试服务器部署记录：2026-10-09

已部署后端 `c79d572be87bd9178764e5e385298d3a49bd2765`，发布目录 `/opt/surprising-ex-20261009-unified-c79d572b`。本次仅启动 LINEAR_PERPETUAL（BTC 604、ETH 653、SOL 866），未启动钱包或其他产品线。所有构建均来自已提交源码归档，未部署工作区未提交改动。

## 发布与状态恢复

- 先统一升级六个服务至 `f974fa21`，随后部署构建期间出现的 `c79d572b` Realtime 增量。两个源码归档仅 Realtime README、CommittedTradeExporter、ExportBatchTest 三个文件不同；其他服务源码一致。增量阶段仅重启 Realtime 和 Maker。
- UTC 03:11:33 开始 API 维护，03:27:56 恢复公网访问（北京时间 11:27:56），共 16 分 23 秒。维护配置及一次性恢复校验参数均已移除。
- 停止 Maker、Lifecycle、Price，再退出 Realtime 和 Gateway；Core 停止前生成有效快照，备份完整 Archive、运行目录、导出检查点、K 线状态及 PostgreSQL。
- 停写基线 appliedCommandCount 为 16,331,588，Core 状态哈希 `330fa621a81d5745`；新 Core 在 HTTP 服务启动前通过同一哈希恢复检查。之后实时价格和交易会正常改变哈希。
- Maker 恢复交易前，账户 1、2、3、4、22 的 canonical 用户/订单状态及 treasury 共 11 项对比，差异为零，覆盖余额、冻结、持仓、杠杆、版本和开放订单；未重置、补种或调整资金。
- 恢复后的 Core 在真实做市运行中再次完成快照，有效 recording 数由 126 增至 128，snapshot unit Result=success、ExecMainStatus=0。
- Core 正常运行，五个 HTTP 服务健康检查均 UP，公网 healthz 正常；主服务与快照定时器 active。最终磁盘 387G，总使用 232G，剩余 155G。

## 做市配置

- 将原数据库有效业务设置及三条策略完整迁移至 `/etc/surprising/linear-perpetual-maker.yml`，配置文件权限 600，通过已有 Spring additional location 加载。
- 初次启动使用空策略，避免对恢复订单发出撤单；完成金融状态对比后加载实际策略。YAML 未设置的订阅消息字段改为省略，避免 null 被绑定为空字符串；最终业务设置和三条有效策略完整对比一致。
- Maker 单实例运行，配置从 YAML 读取，热更新仅存在进程内存。启动依赖中无 JDBC、PostgreSQL、Hikari、Redis、Lettuce、Redisson；TCP 对端仅 Gateway、Kafka、外部行情，未连接数据库或 Redis。
- BTC、ETH、SOL 策略均 RUNNING、有实际报单且 lastError 为 null。测试吃单账户 22，tradeInterval=PT1S；其他交易数量、库存及风控设置保留。

## 构建与验证

- 本地 Corretto HotSpot JDK 27 / Maven 3.9.16，服务端 HotSpot JDK 27 / Maven 3.8.7。七个目标产物构建通过。
- 基线 17 个针对性测试类共 304 项通过，覆盖共享拒绝码、资金规则、快照恢复与产品线隔离、Maker 内存配置/任务、订单接入、Gateway 本地业务、K 线 sink/rollup；Realtime 增量 ExportBatchTest 14 项通过。合计 318 项，无失败、错误或跳过。
- 公网和内网各 60 秒只读 WebSocket 检查，无协议错误或意外断线。公网指数 P95 最大 193ms，标记价 P95 最大 240ms；发送队列和背压拒绝指标起止均为 0。
- 该窗口与 03:29:58–03:30:10 UTC 快照重叠，Maker 实际成交日志出现约 8.17–8.62 秒间隔；盘口也有短暂延迟，不能据此宣称快照完全不影响交易。关联已记录，根因未完成性能剖析。
- 快照后额外 30 秒公网检查，各合约收到 26–27 笔成交，最大到达间隔 1.347 秒，成交 P95 最大 114ms，无协议错误。此结果仅代表该观测窗口。
- 成交导出检查点持续推进：logPosition 13,600,982,240 → 13,606,814,496；tradeSequence 197,496 → 197,949。BTC 当前分钟 K 线持续更新，未独立重算全部历史。
- 用户前端已在线使用 `4ac3aa98` 对应主 JS、交易页 JS、公共组件 JS 和 CSS，四个文件与干净构建 SHA-256 完全一致；未重新发布 Cloudflare，未验证 HTML 字节一致。前端 CI 37875109024 成功。

## 备份、异常与范围

- 私有证据和回滚资料保留于 `/var/backups/surprising/deploy-20261009-f974fa21`，目录权限 700。数据库备份 486,495,360 字节，pg_restore -l 验证通过；SHA-256 见配套 JSON。
- 慢速产物上传已取消并清理未用部分文件，改为上传源码并远程构建。SSH 中断后出现过重复构建，本轮构建进程已停止，最终采用单独 clean 构建成功产物。
- 应用数据库角色 pg_dump 曾因旧备份表权限失败，随后通过本机 postgres peer 认证完成只读备份，未改变数据库权限。
- 未执行真实用户杠杆修改，未运行其他五条产品线或进行全产品线资金对账。发布完成后出现的 `240ccf4c` funding 提交不属于本次运行版本。
- 回退时需要协调 Core 与客户端协议，并保留发布后已接受命令；不得直接用维护前数据库或 Archive 覆盖后续成交状态。
- 本次临时探针进程均已结束；私有备份及有效发布目录保留。临时本地构建目录清理状态以配套 JSON 为准。

详细的产物哈希、测试类、探针聚合、版本与私有证据哈希见 [配套 JSON](test-server-deployment-20261009.json)。
