# 2026-10-09 价格时效与杠杆拒绝排查

## 线上观察

测试机 surprising-ex，仅只读访问。BTC 为 LINEAR_PERPETUAL / instrumentId 604。明细摘要见同目录 `price-leverage-errors-20261009.json`。

`MarkPriceCalculator` 使用 median(资金费调整后的指数价, 指数价 + 60 秒平均盘口基差, 最新成交价)，再限制在指数价 ±3% 范围内。01:50:16Z 样本：指数 81966.4983、price1 81948.5682、price2 81933.0393、最新成交 81933.5，因此标记价为 81933.5；相差约 33U 符合公式。现货指数与永续合约价格本来就可能有基差，不能要求三者相等。

两轮各 90 秒采样，探针均运行在服务器上，避免跨机器时钟影响：

| 入口 | 频道 | 事件数 | 事件生成到接收 P50 / P95 / 最大值 (ms) | 最大推送间隔 (ms) |
| --- | --- | --- | --- | --- |
| 本机 9094 | index | 90 | 29 / 97 / 120 | 1263 |
| 本机 9094 | mark | 90 | 93.5 / 297 / 446 | 1410 |
| 公网 ex-api.tokdou.com | index | 90 | 26.5 / 91 / 277 | 1233 |
| 公网 ex-api.tokdou.com | mark | 89 | 95 / 238 / 370 | 1282 |

两轮无 WebSocket 错误帧。首轮 HTTP 指数快照年龄 P50 273ms / 最大 772ms；标记快照年龄 P50 826ms / 最大 1121ms；各序列递增 89。WebSocket queue.depth=0、backpressure.rejections=0。价格审计与资金费消费者 lag=0；活动做市成交消费者瞬时 lag=1，K 线 Streams 成交 lag=2。历史无成员 consumer group 的大 lag 不代表正在运行的消费者堵塞。

公网盘口有重复版本；按版本首次出现统计 P95 199ms、最大 2010ms，不能把重复发送的旧 eventTime 全部当成传输延迟。未发现持续积压，存在个别盘口延迟样本。没有覆盖用户浏览器到公网入口的最后一段网络，也不能据 90 秒窗口断言以后不会拥堵。

价格服务已有独立的异步 MarkPriceCorePublisher，按币对合并待发送数据，未在计算线程同步等待 Core 回包。页面公共事件经 newerPublicEvent 检查版本，feed 按约 100ms 合并渲染。确认的显示缺口是单独某个价格频道停更时仍保留旧值；前端本次给指数/标记价添加独立 5 秒过期显示及断连隐藏，恢复有效更新后自动恢复。该规则只控制显示，不参与风控或结算。

## 杠杆与错误链路

Nginx 03:46:57+02:00 记录页面 POST trading-leverage/settings 返回 404；此前 GET 对应账户 1、合约 604。01:58:48Z 的账户观察显示，账户仍有 170 步 CROSS/NET 持仓、该合约挂单为 0。普通 UPDATE_LEVERAGE 在有持仓或挂单时被 LEVERAGE_UPDATE_BLOCKED 拒绝。此次未提交任何真实账户杠杆、平仓或撤单操作；事后观察与拒绝条件吻合，但未采集原 POST 的倍数与 Core 原始响应。

根因链路：DerivativeAccountCommandProcessor 抛出业务代码 → CoreResultCode 未登记该代码，降为 INVALID_COMMAND → OrderAeronGateway 抛 IllegalStateException，拼入 Aeron 内部文案 → LeverageRequestService 当作不存在返回 404 → 页面直接展示原文。

修复：

- 补齐生产 Core 源码中 38 个遗漏的字面量拒绝码，包含杠杆、订单类型、算法单、撤单计时器等；原 wire code 保持不变，新值追加为 86–123。
- OrderCommandRejectedException 仅表达已确认的业务拒绝，使用 409、业务文案和结构化 code；普通订单命令结果同样使用此边界。
- LocalBusinessApi 保留结构化 code；账户拒绝也保留 code。未知结果与未受理仍走原来的独立路径。
- 前端统一处理 JSON/下载错误、正常 HTTP 响应内的订单和止盈止损拒绝；可识别代码翻译为中文，未知异常、HTML、驱动报错不直接显示；提交超时提示核查结果，禁止自动重试写操作。
- 杠杆滑条、数字输入及快捷倍数同步；未修改数值时关闭弹窗，不发送命令。未放宽持仓限制，也未自动启用 repriceCrossMargin。

新异常类承担 HTTP 业务拒绝转换，不持有业务状态；前端 ReferencePrice 只持有显示计时器，不复制报价。未新增队列、存储或交易处理阶段。

## 验证与交付边界

HotSpot Corretto 27+33、Maven 3.9.16，测试前可用磁盘 389GiB。

- 第一组：CoreMessageCodecTest 9、RuntimeStateBusinessRulesTest 32、CoreDeliveryOptionFinancialMatrixTest 18、ClusterCommandPipelineTest 272；无失败，后者有 1 项 skipped，未计为通过。覆盖六产品线、现有资金/持仓/恢复边界。
- 第二组：CoreResultCodeTest 114、四衍生品带挂单的杠杆拒绝及撤单后重试 4、Gateway 6 个测试类共 83，全通过。拒绝前后 businessStateHash 相同；没有因展示修复改变资金/持仓。
- CoreBusinessRejectionCodeTest 1 通过，扫描生产字面量拒绝码，防止遗漏再次变为 INVALID_COMMAND。
- 补充未知结果/未受理处理：明确 ResultUnknownException 与响应超时/中断使用 RESULT_UNKNOWN，未受理使用 REQUEST_NOT_ACCEPTED，保留“结果未知”与“已拒绝”的业务差别；Gateway 四类测试共 67 项通过。首次新测试因 Mockito 重设抛错 mock 的写法错误失败，修正为 doThrow 后上述 67 项全部通过。
- 前端共享工作区 128 项、隔离提交 127 项测试通过，隔离 lint/build 与桌面/手机暗亮主题验证通过，详情记录在前端 README_CN.md。

本次不部署或重启测试机。新 wire code 需要 Core 与相关客户端一起升级；旧客户端不能解码新增码，不允许先只部署 Core。线上只读观察不等于真实交易资金守恒验收；本次没有运行钱包服务。

清理状态：本轮两条探针结束、两台 Vite 已停止、浏览器已关闭；已删除本轮临时诊断目录 /private/tmp/surprising-price-leverage-20261009 及 24 份本轮测试报告。采样摘要与测试结论已归档，构建产物保留，其他任务目录未删除。
