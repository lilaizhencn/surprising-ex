# 测试服务器链路日志与 JVM 诊断

## 业务消息怎样追踪

HTTP 入口 `HttpTraceFilter` 验证或生成 `X-Trace-Id`，响应返回同一 ID，记录 `http.start/http.end`、状态码和单机耗时。同步下单经 `AeronClientPool` 捕获 ID 后交给异步传输线程；复制日志的 `CoreMessageHeader` 保存有限 ASCII 元数据，Core 提交后生成的实时帧继续携带该 ID。`RealtimeRouter`、Gateway fanout 和 WebSocket 发送队列交接同一 ID，可关联订单、成交、账户及持仓变化。

主要阶段：`aeron.start/aeron.end`、`core.start/core.end`、`router.send.end`、`ws.send.end`。Aeron 单向 `ADMITTED`、router 的 `OFFERED` 和 WebSocket 的 `OK` 分别只表示各自交接完成，不能当作交易提交或浏览器已渲染。`ws.send.end` 分开记录排队和 socket 写入耗时。未知交易结果仍使用原有结果未知与幂等处理，不自动重复提交。

KafkaTemplate 在投递前写 `X-Trace-Id` 头；监听器恢复 MDC 并记录 topic/partition/offset，结束或异常后恢复原线程上下文。批量消费者保留每条消息 ID，不把整批当成同一请求。已投递或重试的 ProducerRecord 保留原 ID，不覆盖已冻结 headers。`kafka.ack` 只表示 broker 回执；可靠成交导出只有事务提交后才记录 `trade.kafka.committed`。Gateway 的每产品子容器也安装 Kafka 拦截器。

多输入聚合不能宣称来自一个 HTTP 请求：指数/标记价采用 `price-产品-类型-instrumentId-sequence` 生命周期，日志关联输入指数和成交序号。K 线采用 `candle-产品-instrumentId-period-openTime`，同一蜡烛全部实时修订共用 ID；`trade.candle.applied` 用输入成交的 ID 记录目标蜡烛 ID。分钟及更高周期快照通过 Kafka、realtime 与 WS 保留该蜡烛 ID。原有无追踪头的归档/历史消息以命令 ID、日志位置或 Kafka 坐标生成稳定关联标识。

协议保留 schema v8 的已声明 headerLength 边界；无 trace 扩展的既有 76 字节归档仍可恢复。实时帧带追踪 ID 时使用 version 2，无追踪帧仍解码 version 1。trace 元数据不参与 CommandFingerprint、资金计算、业务幂等键或权威状态哈希。发布时 Core、客户端、实时服务和 Gateway 必须协同升级，旧 Core 不接受新扩展头。

日志不记录 Authorization、token、请求正文、完整查询参数、Kafka value 或数据库密码。异常生命周期记录异常类；业务原有必要错误现场继续保留。所有服务使用 UTC，耗时使用各 JVM 的 System.nanoTime，不比较不同 JVM 的纳秒时钟。

## JFR 与 JVM 参数

单节点入口 `scripts/linear-perpetual-single-node.sh` 默认开启六个长驻 JVM 的 JFR：`settings=default,maxage=30m,maxsize=64m,dumponexit=true`，栈深度 128；Core 启用原有稀疏命令延迟诊断。一次性 core-probe 不录制。通用开发启动器仍通过 `JFR_ENABLED` 选择开启。

保留 JDK 27 HotSpot、ZGC、分服务堆上限、GC 与 safepoint 日志以及 Aeron 所需的 opens/native-access 参数。GC 文件轮转为 5×20 MiB。JFR 可查看 JVM 分配、CPU、锁、socket/文件 I/O，并关联自定义 `surprising.HttpRequest`、`surprising.ClientTransportBoundary` 和 Core 命令事件中的 traceId。HTTP 自定义事件默认只记录 ≥20 ms；原有命令事件按 1/64 采样，完整生命周期仍以日志为准。

```bash
# PID 从本部署 pids 目录读取，先确认进程归属。
jcmd PID JFR.check
jcmd PID VM.flags
# 临时深入分析应限定时长和容量；不要覆盖正在录制的文件。
jcmd PID JFR.start name=investigate settings=profile duration=30s maxsize=64m filename=/安全目录/investigate.jfr
```

## 日志只保留两天

`deployment/test-single-node/logback.xml` 按服务写 `*-application.log`，UTC 每日及 20 MiB 轮转压缩，maxHistory=2，每服务最多 2 GiB 历史归档。stdout 仅写 WARN/ERROR，避免复制高频 INFO。`surprising-runtime.logrotate` 管理启动器 stdout 和 GC，保留最多两次轮转/两天。

每小时 `retain-runtime-diagnostics.sh` 删除超过 48 小时的非活动日志和已经完成的顶层 JFR dumps。容量保护可能使高流量日志保留不足两天；两天是保留上限。JFR 运行中的 repository chunks 不由清理脚本删除，停止后的最终 dump 同样最多保留两天。当前活动日志不会直接 unlink。

```bash
install -m 644 deployment/test-single-node/surprising-runtime.logrotate /etc/logrotate-surprising-runtime.conf
install -m 644 deployment/test-single-node/surprising-runtime-diagnostics.service /etc/systemd/system/
install -m 644 deployment/test-single-node/surprising-runtime-diagnostics.timer /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now surprising-runtime-diagnostics.timer
logrotate -d /etc/logrotate-surprising-runtime.conf
```

只处理本部署的运维日志与诊断录制。Aeron Archive、资金流水、数据库、快照、成交导出 checkpoint 和发布备份不属于日志保留规则，禁止按两天清理。

## WebSocket 应用生命周期

Web 应用通过 `useRealtimeApplication` 持有唯一 `/ws/v1` 连接；页面只增减订阅，产品线放在订阅帧中。公共行情不等待认证。登录发送一次 `authenticate`，通过后安装各启用产品的本用户私有订阅；匿名连接可原地认证，退出/切换账户/替换 token 先关闭旧身份再重连。每次断线重连重新认证一次，旧连接回调与旧账户订阅失效。token 不放 URL，认证失败不安装私有订阅。详见前端 REALTIME.md。
