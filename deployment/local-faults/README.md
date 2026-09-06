# 本机 Aeron Cluster 故障功能验证

仅运行少量顺序样本，不采集吞吐、压测或 JMH。范围为真实三个 Core JVM、Aeron Cluster/Archive 和直接客户端；不启动 Provider、推送、Kafka、数据库或 wallet。

宿主机以 HotSpot JDK 25 构建：

```sh
mvn -B -ntp -pl surprising-aeron-core/surprising-aeron-tools -am \
  '-Dtest=*Test,!CorePerpetualEndToEndBenchmarkTest,!CommittedTradeExportIntegrationTest,!Http*Test,!P10CapacityGateTest,!W4LifecycleQaMainTest' \
  -Dsurefire.failIfNoSpecifiedTests=false package
```

在独立 Linux VM 中运行，要求 root、Python 3、iproute2、iptables、util-linux（nsenter/mount），HotSpot JDK 25 位于 `/opt/fault-jdk25`，并挂载整个仓库。当前使用专用 Colima profile `aeron-fault-qa`，4 vCPU、6 GiB。三个节点位于独立 network namespace；不应在已有生产网络或生产数据的机器上运行。

```sh
sudo python3 deployment/local-faults/run-suite.py --root /tmp/afqa-new-suite
# 或单独执行场景：
sudo python3 deployment/local-faults/verify.py --root /tmp/afqa-new-availability --scenario availability
sudo python3 deployment/local-faults/verify.py --root /tmp/afqa-new-snapshot --scenario snapshot-cut
sudo python3 deployment/local-faults/verify.py --root /tmp/afqa-new-storage --scenario storage
sudo python3 deployment/local-faults/verify.py --root /tmp/afqa-new-log --scenario corrupt-log
sudo python3 deployment/local-faults/verify.py --root /tmp/afqa-new-read-only --scenario read-only
sudo python3 deployment/local-faults/verify.py --root /tmp/afqa-new-spot --scenario smoke --product SPOT
```

`smoke` 对其余五产品线分别执行：`LINEAR_PERPETUAL`、`INVERSE_PERPETUAL`、`LINEAR_DELIVERY`、`INVERSE_DELIVERY`、`OPTION`。脚本拒绝覆盖已有 root，失败目录也保留。每次只运行一个场景，不能共享同一组 `afqa0..3` namespace 并行运行。

- `run-suite.py` 顺序执行 11 组，逐组写入 summary.json；进程清理后压缩 MediaDriver 临时缓冲区为 media-evidence.tgz，保留 Archive 原始目录。
- `events.jsonl` 包含实际命令、响应、故障和断言结果；`node*.log`、`client.log` 保存进程日志，`data/` 保存恢复数据。
- `snapshot-cut` 的 Java agent 仅来自 `src/test/java`，不打入生产 jar。它在真实快照的首个成功 publication offer 后暂停，再由脚本强杀节点，避免靠随机定时猜测“快照进行中”。
- 网络分区阻断成员通信；少数派测试还阻断客户端到多数派，防止自动重定向掩盖问题。只读故障通过 nsenter 注入目标 JVM 的挂载命名空间，并先验证真实写入返回 EROFS。磁盘满只填充本轮独立的 256 MiB tmpfs；退出前复制存储证据并卸载。日志损坏修改专用 fixture 的金额字节，保留原始备份，必须拒绝回放后才允许记为通过。
- `snapshot-state` 只支持本轮小样本单 segment，跳过 Aeron service-container 元数据后解码真实 Core/native snapshot；不能代替生产 Archive 恢复工具。
- 长暂停可能超过 Aeron 10 秒 driver timeout，预期允许节点 fail-stop 后由 supervisor 重启。脚本确认进程死亡并等待心跳租约过期，不删除持久化目录来伪造恢复成功。

## 恢复配置变更

Core Archive 默认记录并回放校验 CRC32C，JDK 25 启动必须增加 `--add-opens=java.base/java.util.zip=ALL-UNNAMED`。AWS render 已同步此参数；Core systemd 单元按失败重启，等待 11 秒，120 秒内最多启动 3 次。损坏数据仍需人工处理，不能无限重启掩盖问题。

旧版未记录 checksum 的 Archive 不能直接作为新版本恢复验收数据。对已有数据应先停止写入并备份，再用匹配版本的 Aeron Archive 工具离线校验/生成 checksum，或用已核验的恢复源重建；不能禁用 replay 校验，也不能直接删除旧数据。启用 checksum 的兼容与性能影响需在部署前单独评估；本轮不提供性能结论。

完整结果见根目录 `FAULT_RECOVERY_VALIDATION.md`。官方说明：[Archive checksums](https://github.com/aeron-io/aeron/wiki/Aeron-Archive)、[Media Driver timeout](https://aeron.io/docs/cookbook-content/aeron-media-driver-timeout/)。
