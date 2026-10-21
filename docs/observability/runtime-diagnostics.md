# 运行日志与 JVM 诊断

## 业务定位与完成边界

通过产品线、订单 ID、成交 ID、命令 ID、事件版本、日志位置及 Kafka topic/partition/offset 定位业务。HTTP 和消息通道不再创建或传递额外的字符串追踪身份；日志不记录 token、密钥或请求正文。

`RealtimeRouter` 的 OFFERED 只表示传输交接，`ClientConnection` 的发送成功只表示 socket 写入完成。可靠成交导出以 Kafka 事务提交为完成边界，失败不得推进导出 checkpoint。未知交易结果仍通过原业务 ID 查询或幂等重试；不能根据传输成功推断资金结算或客户端渲染完成。WebSocket 继续保留队列容量、顺序、发送超时与慢连接隔离。

## JFR 与 JVM 参数

使用 HotSpot JDK 27。JFR 可检查 JVM 分配、CPU、锁、socket 与文件 I/O；录制应限制时长和容量。Aeron 所需的 opens/native-access 参数沿用各服务启动配置。

```bash
# PID 从本部署 pids 目录读取，先确认进程归属。
jcmd PID JFR.check
jcmd PID VM.flags
# 临时深入分析应限定时长和容量；不要覆盖正在录制的文件。
jcmd PID JFR.start name=investigate settings=profile duration=30s maxsize=64m filename=/安全目录/investigate.jfr
```

## 日志只保留两天

`deployment/test-single-node/logback.xml` 按服务写 `*-application.log`，UTC 每日及 20 MiB 轮转压缩，maxHistory=2，每服务最多 2 GiB 历史归档。stdout 仅写 WARN/ERROR，避免复制高频 INFO。`surprising-runtime.logrotate` 管理启动器 stdout 和 GC，保留最多两次轮转/两天。

两天是日志保留上限；容量保护可能使高流量日志保留不足两天。清理录制时只删除已完成的文件，运行中的 JFR repository chunks 和当前活动日志应保留。

只处理本部署的运维日志与诊断录制。Aeron Archive、资金流水、数据库、快照、成交导出 checkpoint 和发布备份不属于日志保留规则，禁止按两天清理。

## WebSocket 应用生命周期

Web 应用通过 `useRealtimeApplication` 持有唯一 `/ws/v1` 连接；页面只增减订阅，产品线放在订阅帧中。公共行情不等待认证。登录发送一次 `authenticate`，通过后安装各启用产品的本用户私有订阅；匿名连接可原地认证，退出/切换账户/替换 token 先关闭旧身份再重连。每次断线重连重新认证一次，旧连接回调与旧账户订阅失效。token 不放 URL，认证失败不安装私有订阅。详见前端 REALTIME.md。
