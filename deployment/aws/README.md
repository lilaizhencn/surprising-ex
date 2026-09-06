# AWS 构建机与三节点部署准备

这是部署准备入口，脚本不改变交易代码，也不会自动购买压测节点或开始性能采样。
每次只启动一个产品线的三成员集群；六产品分别运行独立 JVM、端口与持久目录。正式压测固定 256 in-flight、1 matcher，做市进程保持运行。

## 已创建的构建机（2026-09-06）

- AWS CLI profile：`aeron-test`，账户尾号 `3618`，区域 `ap-southeast-1`。
- 实例：`i-0ddff02035ffe9298`，Name `surprising-build`，`m7i-flex.large`，2 vCPU / 8 GiB；Ubuntu 24.04 amd64，30 GiB 加密 gp3。
- 创建时余额 40.32 USD，账户为 PAID。该实例在 EC2 API 中标记 FreeTierEligible；抵扣不等于永久免费。
- 查询到的计算按需单价 0.1197 USD/小时，公网 IPv4 与 EBS 另计；停机保留 EBS，仍有磁盘费用。
- SSH 使用单独密钥 `~/.ssh/surprising-aws-build`，安全组只允许创建时操作端公网 IP 的 TCP 22；未把 AWS/GitHub 私密凭据放到服务器。
- 初始公网 IP `54.251.230.217`，私网 IP `172.31.38.21`；停机再启动后重新查询公网 IP。
- 开机 6 小时触发 `surprising-autostop.timer`，触发的是 EC2 stop，保留代码、依赖缓存和发布包。
- Java：Temurin OpenJDK 25.0.4.1+1 HotSpot，`/opt/java`；Maven 3.9.16，`/opt/maven`；Git 已设置 fast-forward only、fetch prune，无写入凭据。
- 后端目录 `/home/ubuntu/work/surprising-ex`，fork `/home/ubuntu/work/exchange-core-lilaizhencn`，日志 `/home/ubuntu/build-logs`。

```bash
aws --profile aeron-test --region ap-southeast-1 ec2 start-instances --instance-ids i-0ddff02035ffe9298
aws --profile aeron-test --region ap-southeast-1 ec2 describe-instances --instance-ids i-0ddff02035ffe9298 \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
# 使用查询出的 IP；操作端地址变化时，只更新本实例安全组的 SSH /32 来源。
ssh -i ~/.ssh/surprising-aws-build ubuntu@PUBLIC_IP
# 完成后停机：
aws --profile aeron-test --region ap-southeast-1 ec2 stop-instances --instance-ids i-0ddff02035ffe9298
```

## 拉取、构建、发布

构建机为只读 Git 使用，不设置虚构的提交人，不安装个人 GitHub token。两个仓库当前都是公开仓库。
新增构建机可以将 `bootstrap-build.sh` 作为 Ubuntu cloud-init user-data；运行节点执行 `sudo bash bootstrap-build.sh runtime`，不会配置构建机的 6 小时停机定时器，也不会克隆仓库。
安装包下载校验 JDK SHA256 与 Maven SHA512。运行节点与构建机保持相同 amd64 架构和 JDK 版本；切勿在正式采样过程中更新软件。

```bash
. /etc/profile.d/surprising-java.sh
cd /home/ubuntu/work/surprising-ex
git pull --ff-only origin master
export FORK_SOURCE=/home/ubuntu/work/exchange-core-lilaizhencn
bash deployment/aws/prepare-fork.sh
export PINNED_FORK_JAR="$FORK_SOURCE/target/exchange-core-0.5.18-emporia.jar"
export RELEASE_OUTPUT=/home/ubuntu/releases
bash deployment/aws/build-release.sh
```

fork 必须是 `surprising-parent/pom.xml` 锁定的 clean SHA，不能自行跟随 fork master 升级。
`prepare-fork.sh` 进行依赖编译，显式跳过 fork 测试；生成 JAR 必须与锁定 SHA256 相同，否则停止。
`build-release.sh` 默认运行后端测试；`BUILD_TESTS=false` 只代表打包检查，证据中会记录，不能宣称测试通过。
发布包按当前 master 的完整 commit SHA 命名，包含可执行 JAR、部署工具、init.sql、版本/构建日志及 SHA256SUMS。
首次试构建允许单独执行 Maven；最终用于分发的包必须通过 release 脚本生成，不将本地 Maven 缓存或秘钥整个打包。

```bash
bash deployment/aws/distribute.sh /home/ubuntu/releases/COMMIT.tar.gz \
  ubuntu@CORE0_PRIVATE_IP ubuntu@CORE1_PRIVATE_IP ubuntu@CORE2_PRIVATE_IP ubuntu@APP_PRIVATE_IP
```

分发使用已校验的 SSH host key；先在构建机配置对应节点的访问权限，不复制个人私钥。也可以由操作端使用 SSH ProxyJump 发包。
分发后校验外层与逐文件 checksum，不自动重启进程，不覆盖已有发布目录，不删除 Archive/快照。运行期间不 SCP 或重新构建。

## 四台 8 vCPU / 16 GiB 的布局

拟使用 `c7i.2xlarge`：EC2 API 返回 8 vCPU、16 GiB、**4 个物理核 × 2 SMT**，不是 8 个物理核心。
三台 Core 成员编号固定 0/1/2，一台应用机运行 Provider、Router、WS、做市及按需的外围依赖。
单台应用机是初期验证布局，它的 CPU/内存、数据库和 Kafka 可能先成为瓶颈，不能把整个系统的上限归为 Aeron 上限。
发压客户端应独立于这四台；构建机可做功能探针，但其 2 vCPU 是否足够正式发压必须单独验证。

当前账户标准实例额度仅 **8 vCPU**。四台压测节点需要 32 vCPU；构建机同时运行需要至少 34 vCPU，独立发压机另计。
本次没有创建四台压测节点、没有提交额度申请。容量需要先解决，否则脚本无法把四节点环境启动出来。

## Aeron 1.53.0 的配置依据

通过官方 GitHub releases API 核实：1.53.0 发布于 2026-08-26，当前项目已锁定此版本。
启动入口是 `SurprisingClusterNode`，同时启动 MediaDriver、Archive、ConsensusModule 与业务 ServiceContainer；无需另装一份 Aeron 服务端发行包。

- `ClusterTopology`：产品线、node-id、三个私网 IP、持久数据目录。
- `ProductLineClusterLayout`：clusterId=100+产品 ordinal；端口=20000+产品 ordinal×1000+node-id×100+offset；offset 1/2/3/4/5 为 Archive/ingress/consensus/log/transfer。
- 8 vCPU 起步采用源码支持的 `SHARED_NETWORK`，Archive 保持 SHARED；1 matcher、4 Account Lane、0 exchange-core risk engine。后续只能在预先锁定的独立场景中调参。
- Core heap 起步 4 GiB/G1，NMT summary、GC/safepoint 日志。应用 heap 总额有界，但需为 native、Aeron、Kafka、Valkey、PostgreSQL 和 OS 另留内存。
- 必须保留 `--add-opens/--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`。
- `aeron.dir` 在 `/dev/shm`；`ClusterTopology` 自动追加产品与节点后缀。实时 sender 使用同一个实际 Driver 目录，不能误配成不存在的另一目录。
- Archive/Cluster 放 `/var/lib/surprising/aeron/<product>/nodeN` 的持久 EBS 上，不放 tmpfs，不在重启时删除。
- Router 使用 manual MDC 向三个成员发查询控制请求，仅 leader 处理；公共/私有推送仍按目标 WS 节点路由。
- 每产品最多 64 Cluster sessions 起步，和用户 WebSocket 连接数不同；256 in-flight 是客户端全局负载口径，也不是 Cluster session 数。
- Core 与 app 使用专属安全组，UDP 只在测试组内互通。由于 egress、replication、Archive response 使用 `endpoint=:0`，仅开放五个固定端口不够；需允许组内动态 UDP。禁止把 UDP 暴露到公网。
- 三成员跨 AZ 用于故障域验证；同 AZ 和跨 AZ 的延迟结果必须分开，不能混在一个容量结论里。

官方参考：[1.53.0 发布](https://github.com/aeron-io/aeron/releases/tag/1.53.0)、[三节点教程](https://github.com/aeron-io/aeron/wiki/Cluster-Tutorial)、[Cluster clients](https://aeron.io/docs/aeron-cluster/cluster-clients/)、[AWS 免费额度](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html)。

## 生成与安装配置

复制 `inventory.example.json` 到仓库外，填写实际私网地址、发布 commit、数据库和 Valkey 凭据；示例凭据会被拒绝。
每次生成一个产品线，输出目录必须不存在，生成文件包含密码，不提交 Git。

```bash
python3 deployment/aws/render.py /secure/inventory.json /secure/generated
# 将 generated/core0、core1、core2、app 分别 SCP 到对应节点。
# 在各节点执行，路径为该节点收到的目录：
bash /opt/surprising/releases/COMMIT/deployment/aws/install-config.sh /secure/generated/core0 SPOT
sudo systemctl start surprising-spot-core
sudo systemctl status surprising-spot-core
journalctl -u surprising-spot-core -n 100 --no-pager
```

应用机先安装 app 目录，再启动 Driver、Router 和业务服务。不要用通配符一次启动所有 unit（probe 是单次查询；SPOT 不需要 funding/derivatives-lifecycle）。

```bash
sudo systemctl start surprising-spot-driver
# 用只读 probe 验证集群连通，完成不等于功能/性能验收。
sudo systemctl start surprising-spot-probe
journalctl -u surprising-spot-probe --no-pager -n 30
```

服务 unit 默认不自动开机启用、不失败重启；先保留故障证据，再决定恢复。
`trade-export` 仅在 core0 生成，单产品只允许一个 active exporter，否则相同 Kafka transactional ID 会互相 fence。
它读取本机 committed Archive，须在 Kafka topics 和事务支持准备完成后单独启动；成员故障时 exporter 的迁移/恢复需另做验证，不能宣称已具备自动 HA。

应用服务完整启动前仍需：PostgreSQL 与 init.sql、Kafka topics（包括单 broker 事务参数）、Valkey、产品 instrument/资产精度、测试用户及做市资金/报价配置、价格源准备。
这些带业务初态的配置不能仅由四个 IP 推导；本次没有启动外围依赖，也没有进行真实 API→Core→WS 联调。

## 功能与性能验证边界

`host-preflight.sh` 只读采集 CPU/内存/网络与系统限制。
`capture.sh PID SECONDS OUTPUT` 用于独立 JFR 归因轮，要求先指定 PERFORMANCE_VALIDATION.md 中的记录 ID；不启动负载、不代替完整指标分析。
保存二进制 JFR、NMT、进程/线程与系统采样，不把全量 allocation 事件展开成巨大 JSON。

正式采样前在根 PERFORMANCE_VALIDATION.md 锁定机器、版本、JVM、负载、阈值、256 in-flight、预热/测量/冷却与数据有效性条件。
现有 `ClusterCapacityMain` 和 `HttpOpenLoopWorkloadMain` 是后续负载工具入口；默认参数不能直接当验收配置。
特别是 capacity async-in-flight 是每 worker 的 pending pair 数，不能把每 worker 256 误称为全局 256。
先验证真实网络、主从切换、停止/重启恢复、资金守恒与快照一致，再进入终态吞吐、API 三段延迟与 WS 推送延迟、丢帧/快照补偿、长期内存的采样。
本次云上编译、配置检查与功能探针不形成性能或 HA 容量结论。

## 本轮已执行的准备检查

2026-09-06 在新建构建机执行：fork 固定提交编译成功，JAR SHA256 与主项目 pin 完全一致；后端 `0fbf69309854720d4a9ab4c5314f63d6ec243feb` 全 reactor `-DskipTests package` 成功。
随后直接运行 `CoreOrderedOrderBatchTest`、`OrderBatchSettlementWaitTest`、`ProductLineClusterLayoutTest` 共 26 个测试，0 失败/错误/跳过。
日志：`/home/ubuntu/build-logs/fork-package.log`、`backend-package.log`、`core-tests.log`，各有 `.exit` 文件；尚未执行云上全量测试。
本地执行配置生成测试（六产品逐个生成、私网/成员唯一性/秘密值校验）、Shell 语法检查及 JDK25 `jfr configure` 参数检查。

发布脚本实跑已通过。另在构建机以 512 MiB/进程运行三 Core + app MediaDriver 做启动检查（仅功能检查）：
第一轮把三个私网地址合并到本机时，测试夹具没有拆开 21020 控制端口，产生 bind 冲突；该轮不能作为完整启动通过。
修正测试夹具使用三个独立控制端口和隔离数据目录后，第二轮三个 Core 日志无 failure/bind conflict，只读 probe 返回 `status=OK`，所有测试进程已退出。
证据在 `/home/ubuntu/build-logs/startup-smoke-round2.log` 与 `startup-smoke-f4a8cd90-round2/`；第一轮日志保留。
这是同机三进程检查，不证明跨 EC2 网络、故障切换、资金回放或完整推送已经通过。
