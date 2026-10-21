# Aeron 交易核心源码分组

本目录保留聚合 POM，供 Maven/IDE 把以下五个模块作为一组导航。
子模块继续直接继承 `surprising-parent`；artifactId、Java 包名和产物路径不变。

| 模块 | 职责 |
| --- | --- |
| `surprising-aeron-protocol` | 交易命令、事件和实时帧协议 |
| `surprising-aeron-client` | 应用侧 Cluster 命令客户端及实时传输 |
| `surprising-aeron-service` | 交易裁决、撮合、账户结算和快照恢复 |
| `surprising-aeron-tools` | 集群探针、诊断和离线回放工具 |
| `surprising-aeron-benchmarks` | 基准、交易链路及运行验证工具 |

从仓库根目录使用 HotSpot JDK 27 构建，例如：

```bash
mvn -pl surprising-aeron-core/surprising-aeron-service -am verify
mvn -pl surprising-aeron-core/surprising-aeron-tools -am package
```

也可从仓库根目录执行 `scripts/build-incremental.sh :surprising-aeron-core`，
展开聚合入口及相关依赖后构建。
交易 Core 仍独立运行，六产品线、Account Lane、Ledger、撮合、资金和恢复边界保持不变。
