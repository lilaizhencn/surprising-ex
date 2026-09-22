# 永续单节点测试部署

这个目录描述 `surprising-ex` 在 `surprising-ex` 测试服务器上的第一阶段部署：只启用 `LINEAR_PERPETUAL`，Core 使用一个节点，PostgreSQL、Redis/Valkey 和 Kafka 使用服务器已有实例。

## 业务启动顺序

1. 连接现有数据库、Kafka 和 Redis，确认依赖可用并初始化缺失 schema。
2. 启动一个 Aeron Core 节点；节点成为单节点 Leader 后，ClusterProbe 才算就绪。
3. 启动应用级 Aeron MediaDriver 和 realtime Router，把 Core 的已提交状态出口连接到 WebSocket Gateway。
4. 启动成交导出器，从 Aeron Archive 的已提交位置恢复可靠 `match.trades` 事件。
5. 按 `instrument → price → account → trading → market-data → derivatives-lifecycle → funding → gateway → maker` 启动永续服务。

Core、账户、撮合、行情、资金费和 Gateway 仍是独立 JVM。它们分别拥有自己的状态、Kafka consumer group 和故障恢复边界；这里合并的是启动编排，不是业务状态。单节点模式会把同一个真实的一节点 host 列表传给 Core client；不能把一个 host 复制成三个成员，否则客户端会等待不存在的成员 endpoint。

## 服务器条件

已检查的服务器条件：JDK 27、Maven 3.8.7、约 47 GiB 内存、根盘约 254 GiB 可用；PostgreSQL 在 `127.0.0.1:5432`，Redis 在 `127.0.0.1:6379`，Kafka 容器在 `127.0.0.1:9092`。Nginx 已将 `ex-api.tokdou.com` 的 HTTP 和 `/ws/v1` WebSocket 请求转发到 Gateway `9094`。

Kafka 当前没有业务 topic。启动前应确认 broker 的自动建 topic 策略；若关闭了自动建 topic，需要按 `ProductTopicNames` 为 `LINEAR_PERPETUAL` 创建 topic，并额外创建全局 `surprising.instrument.events.v1`。不能把其他产品线的 topic 或 consumer group 混用过来。

## 构建与安装

所有 Java/Maven 操作使用 JDK 27：

```bash
java -version
mvn -version
mvn -pl \
  surprising-aeron-core/surprising-aeron-service,\
  surprising-aeron-core/surprising-aeron-tools,\
  surprising-price/surprising-price-provider,\
  surprising-market-data/surprising-market-data-provider,\
  surprising-derivatives-lifecycle/surprising-derivatives-lifecycle-provider,\
  surprising-funding/surprising-funding-provider,\
  surprising-realtime/surprising-realtime-provider,\
  surprising-gateway,\
  surprising-maker \
  -am package -DskipTests
```

`gateway` 现在包含身份、订单、账户、合约四个业务包，不再部署三个 provider 进程。
所有业务和后台实例的环境文件需配置相同的 `BUSINESS_INTERNAL_TOKEN`；不要将该凭证发送给客户端。
Core 先启动，随后业务应用完成 liveness，再启动价格和其他后台服务。

将同一 Git 提交的代码和 `target` 产物放到服务器 `/opt/surprising-ex`，并创建：

```bash
install -d -m 0750 /var/lib/surprising/linear-perpetual/runtime
install -d -m 0750 /etc/surprising
cp deployment/test-single-node/linear-perpetual.env.example \
  /etc/surprising/linear-perpetual.env
chmod 600 /etc/surprising/linear-perpetual.env
```

Core 只按 `exchange-core.version` 解析 Maven 依赖，不再在 `surprising-ex` 内校验 exchange-core provenance。服务器仍应使用已确认可运行的 exchange-core 构建产物；依赖缺失时让 Maven 正常解析或先安装对应版本，不要把不同版本的 jar 混入同一 release。

必须把数据库密码和 `GATEWAY_JWT_SECRET` 改成服务器实际值。测试环境也不要继续使用默认 JWT secret。

本测试 unit 默认设置 `GATEWAY_PRODUCT_TRANSFER_ENABLED=false`，因为只部署永续 provider，没有可供 Gateway 轮询的现货账户路由；这只关闭跨产品转账后台对账，不关闭永续下单、账户、撮合或风险链路。

## 启停与验收

先在服务器上执行只读预检：

```bash
cd /opt/surprising-ex
ACTION=dry-run ./scripts/linear-perpetual-single-node.sh
```

通过后安装 unit：

```bash
cp deployment/test-single-node/surprising-linear-perpetual.service \
  /etc/systemd/system/surprising-linear-perpetual.service
systemctl daemon-reload
systemctl start surprising-linear-perpetual
systemctl status surprising-linear-perpetual --no-pager
```

测试服务器首次启动会按顺序拉起多个独立 JVM，unit 的启动超时为 15 分钟；服务进入 `active (exited)` 后才表示整条产品线启动完成。

启动器会在 `/var/lib/surprising/linear-perpetual/runtime/linear-perpetual-single-node/` 保存 PID、日志、Core 数据、JFR 和成交导出 checkpoint。检查顺序：

```bash
./scripts/linear-perpetual-single-node.sh status
curl -fsS http://127.0.0.1:9094/actuator/health/readiness
curl -fsS https://ex-api.tokdou.com/healthz
```

Gateway 对外仍只走 9094；realtime-router 是无 HTTP 监听的后台 JVM，Core 的 21001–21005、实时 UDP 21010/21020/21030 只绑定本机/内网，不应暴露到公网。

停止服务：

```bash
systemctl stop surprising-linear-perpetual
```

## 当前不包含的验收

这套 unit 只解决单节点测试启动，不等于生产高可用。尚未完成三节点切主、长期容量、真实外部指数源稳定性、完整下单/成交/持仓/强平/资金费资金守恒验收；在这些验收完成前，不应把该服务器当作生产交易节点。
