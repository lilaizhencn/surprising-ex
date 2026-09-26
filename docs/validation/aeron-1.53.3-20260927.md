# Aeron 1.53.3 升级验证（2026-09-27）

## 变更与恢复

- surprising-parent/pom.xml 的 aeron.version 从 1.53.1 升至 1.53.3，沿用统一 aeron-all 依赖管理，未更改业务协议或资金模型。
- 官方版本：https://github.com/aeron-io/aeron/releases/tag/1.53.3 。
- 升级前对本地 LINEAR_PERPETUAL 集群 101 发出 SNAPSHOT；计数由 0 增至 1，控制状态恢复 NEUTRAL 后重启，保留原 Archive、集群目录和账户状态。
- 六个 jar 均重新构建并重启。核心 shaded jar 构建包含 aeron-all-1.53.3；网关、realtime、price、lifecycle 的 BOOT-INF/lib 包含同版本；maker 无直接 Aeron 依赖。
- 核心与共享应用 Media Driver 计数器均报告 version=1.53.3 commit=377943bcb8、Errors=0；核心为 LEADER，Cluster Errors/Container Errors 为 0。

## 已测范围

HotSpot Corretto 27+33、Maven 3.9.16，磁盘可用 417 GiB。

```
mvn -pl :surprising-aeron-service,:surprising-gateway,:surprising-realtime-provider,:surprising-price-provider,:surprising-derivatives-lifecycle-provider,:surprising-maker -am verify
```

- 构建成功，14 个有测试的模块合计 2001 项，失败 0、错误 0、跳过 38；日志 /tmp/aeron-1533-six-services-verify.log。包括共享核心六产品线测试；需要显式环境的 live/基准测试未在此命令中启用。
- 网关 9094、realtime 9095、price 9082、lifecycle 9087、maker 9096 的 actuator/health 均 UP，前端 5174 HTTP 200。20 个做市策略均 RUNNING。
- 升级后 20 币对标记价连续 3 轮均可用，60 次中位数及保护带计算全部一致；/tmp/aeron-1533-mark-validation.log。
- 显式启用 LocalTradingLifecycleLiveTest#usersTradeAgainstLiveMakersAndReconcileFromExecutions，2 用户、各 1 轮、并发 2：真实 API 下单、撤单、成交、平仓、私有 WS 及资金核对通过。用户 530/531 各 4 笔 execution，余额分别 99999421640 / 99998277820 units，资金差额均 0，未留冻结或非零持仓。报告原路径 surprising-gateway/target/live-qa/qa-1790440918764，日志 /tmp/aeron-1533-live-funds.log。
- 公共 WS 订阅全部 20 币对，5 秒预热后采样 30 秒：3616 笔成交、423 个盘口事件，增量 previousSequence 断档数 0。/tmp/aeron-1533-ws-validation.log。

## 已知缺口与范围限制

- 本轮 live 仅验证 U 本位永续，未启动其他产品线或 wallet；其他产品线覆盖由上述 Maven 测试承担，未做六条线同时运行或多节点滚动升级验证。
- 30 秒采样每币对 176–184 笔成交，但部分秒仍为 0；双边盘口最低档数 29–49，尚未满足“每秒多笔且始终各 50 档”的既有高频目标，不能据此宣称高频验收通过。
- 本轮未新增强平、资金费结算或 OCO live 验收，历史未完成事项不计为本轮通过。
- 重启过程中 gateway 出现 SocketException: Invalid argument / setSoLinger 日志；其后 HTTP、真实交易与 WS 检查恢复通过，未将该现象归因于 Aeron 升级。
- 按用户要求保留六个服务、前端和做市运行，保留业务 Archive；未创建临时集群或 JFR。本轮验证报告摘要已入档，临时诊断文件清理情况见后续记录。

## 清理记录

上述结论入档后，删除本轮 /tmp/aeron-1533-* 验证日志、已完成用户 530/531 的 target/live-qa/qa-1790440918764 临时报告；文中这些路径仅作历史定位。未删除业务日志、Archive、其他轮次报告或已有未跟踪目录。浏览器 CDP 检查未生成有效输出，因此本轮只报告前端 HTTP 可访问，不宣称完成页面视觉复验。
