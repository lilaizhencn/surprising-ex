# 本机永续环境

使用 HotSpot JDK 27、Maven、Node、PostgreSQL 18、Kafka 和 Redis。前端位于同级 `surprising-ex-web`，先执行 `npm ci`。

```bash
./scripts/local-perpetual.sh up
./scripts/local-perpetual.sh status
./scripts/local-perpetual.sh down
```

启动 U 本位永续 Core、gateway、price、realtime、derivatives-lifecycle、maker 和用户 Web，不启动 wallet。
首次创建 `~/.local/share/surprising-ex/perpetual-pmm-20/local.env`，仅填写数据库、消息队列、端口和密钥等部署参数。
保留原运行目录名称，避免误建新数据目录。外部数据库设置 `MANAGE_POSTGRES=false`，不会被初始化脚本覆盖。

新托管数据库执行 `init.sql` 和本目录 `catalog.sql`，仅含 BTC、ETH、SOL 三个合约。
已有数据库不得重跑初始化 SQL；按 `deployment/migrations/` 升级结构，现有资金和 Core Archive 保留。

合约、指数行情来源和风险档位通过管理后台持久化维护。`application-local.yml` 默认不包含业务配置。
`render-local-market-ids.py` 只查询数据库生成启动健康探测参数，不读取或生成 JSON 合约配置。
后台添加合约后，快照事件和 Core 配置同步自动完成，业务配置变更无需重启。

做市公共设置、策略和参考盘口从 YAML 初始化；后台热更新只保留在当前 maker 进程，重启后丢弃，历史做市配置表不再读取。
需保留的做市参数应在重启前写入独立 YAML，并在 `local.env` 通过 `SPRING_CONFIG_ADDITIONAL_LOCATION=file:/绝对路径/maker.yml` 加载；也可在重启后通过后台重新配置。
默认引擎关闭、策略为空。管理员先配置做市账户、参考盘口、数量和档数，通过账户页面准备资金，再启用策略；只有显式保存到启动 YAML 的设置会自动恢复。详见 [做市配置与迁移](../../surprising-maker/README.md#单实例部署与配置迁移)。
启动器不再硬编码做市账户、注入测试资金、启用模拟成交或覆盖风险扫描预算。
风险预算根据当前待扫描合约数量自动增加，后台维护基础预算。
启用做市后可执行 `python3 scripts/check-local-perpetual.py`，按当前后台目录检查交易中合约的行情和双边盘口。
空盘口不会被健康探测解释为需要自动注入资金或自动开启做市。

部署新 JAR 需要 `down`、构建、`up`；日常合约编辑及上线不使用此流程。
停机保留 PostgreSQL、Kafka、Core Archive 和行情 checkpoint。不要删除正在使用的运行目录。
日志位于运行目录的 `logs` 与 `runtime/local-perpetual/logs`，启动前至少需要 5 GiB 可用空间。
macOS 使用 launchd 管理进程，前端运行副本由启动器构建；`build` 只构建后端。

历史验证记录保留在本目录，记录中的 YAML 参数、账户和二十市场负载属于当时的环境，不是当前配置来源。


托管的单节点测试 Kafka 新建业务 Topic 默认 1 分区；消费位点和事务状态内部 Topic 首次创建也使用 1 分区。
K 线服务遵循 Broker 默认值，不再强制扩成 32。已有 Topic 分区数保持不变，修改默认值不会缩分区或删除历史数据。
