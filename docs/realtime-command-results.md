# realtime 已提交命令回执（2026-10-19）

## 业务流程与状态归属

现货 POST 响应丢失后，订单可能已成交，也可能在资金准入阶段被拒绝，后者没有订单实体。只查订单或 Core 的短期结果缓存无法覆盖这个区别。

1. `surprising-aeron-service/.../orchestration/CommittedTradeReplay` 在 realtime 的独立 Archive 回放中，等原有撮合、账户与账本提交全部完成，再取该命令的最终结果。每个回放步骤只交出一个不可变回执，不向在线 Core 增加查询、状态或回调。
2. `surprising-realtime-provider/.../export/CommittedTradeExporter.ExportBatch` 在原有有限合批中暂存回执；`CommittedOrderProjectionRepository` 将普通用户回执、订单投影和可见性水位放入同一数据库事务，成功后才发送既有 Kafka 成交并推进 checkpoint。拒单没有订单事件、exportSequence 不变时，也必须落库。
3. `core_command_result_projection` 按产品和 commandId 保留第一次已提交结果；重复回放校验用户、命令类型、客户订单号、交易对、状态、结果码及 appliedCommandCount 一致。矛盾会回滚整批，不能推进 checkpoint。重放位置和时间允许因同一命令的重复提交不同，不覆盖首次记录。冲突请求的 IDEMPOTENCY_CONFLICT 不替换原请求结果。
4. 网关 `AeronOrderProjectionRepository` 按认证用户、产品和 commandId 查询该表；`OrderService.submission` 使用已有 StableOrderIdentity 从 clientOrderId 推导原命令号。查询没有 Core 回退，也不依赖其 128 条内存结果保留窗口。

新表只保存命令处理事实，不复制订单、账户或资金状态；写入者是 realtime 的 committed exporter，读取者是网关。回执目前不自动过期。沿用订单投影的做市账号目录，过滤策略账号的高频内部报价回执，包含目录中的已停用策略；不能据此承诺做市内部请求的用户端恢复。目录本身必须可用且完整。

新增 `OrderSubmissionResponse` 只承担 HTTP 身份与结果契约；`CommittedCommandResultRepair` 是独立、有截止位置的历史回补入口。现有导出类承担批次、事务和 checkpoint，无新增线程、通用框架或业务状态镜像。在线 Core 协议与 snapshot 格式不变。

## 查询契约

用户入口：

- `GET /api/v1/gateway/trading/submission?clientOrderId=<原请求号>`：带原 productLine 和认证身份；可带匹配认证身份的 userId。返回 userId、productLine、clientOrderId、instrumentId、commandId、outcome、code。
- `GET /api/v1/gateway/trading/commands/{commandId}`：已有入口改读同一 realtime SQL 投影，并绑定认证用户。

已提交回执返回 HTTP 200 / TERMINAL，code=NONE 表示命令处理成功，其他 Core 结果码表示拒绝。未查到返回 HTTP 202 / RESULT_UNKNOWN，不能证明原请求从未受理。两者均 `Cache-Control: no-store`。数据库不可用或投影内容非法返回 503，不查询 Core。命令处理成功不等于订单已经成交，成交与撤单终态仍查订单视图。

Web `src/api/endpoints.ts` 和 client `lib/src/app_state.dart` 先读 submission；终态校验原用户、产品、请求号、交易对及已知命令号后解锁，不需要订单视图已就绪。只有显式 RESULT_UNKNOWN 才继续读取 realtime 的 by-client-order-id 活动/终态订单。404、503、资料不匹配或查询本身未被接受均保留待确认，只发 GET，不重发原 POST。

## 部署与历史回补

按以下顺序发布，避免前端调用尚不存在的接口：

1. 在目标交易数据库应用 `deployment/migrations/20261019-committed-command-results.sql`；新环境已同步 `init.sql`。
2. 更新 realtime，保持 REALTIME 与 TRADE_EXPORT 启用，核对导出追上提交位置。原 live checkpoint 无需重置；它只会覆盖升级后的新回执。
3. 在资源受限的运维窗口执行历史回补，覆盖所需普通用户请求所在的 retained Archive 日志。使用与部署相同的 HotSpot JDK 27、运行依赖 classpath 和 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`，入口为 `com.surprising.realtime.provider.export.CommittedCommandResultRepair`，六个参数依次为：

   ```text
   SPOT <cluster-directory> <aeron-directory> <archive-control-channel> <独立路径>.command-repair <有限的已提交end-position>
   ```

   每次只回补一条产品线，外层限制运行时间、堆和磁盘。第一次必须使用不存在的独立 checkpoint，从零开始；只可续用同一回补任务自己的 checkpoint。`.command-repair` 有独立文件标识，复制或重命名 live checkpoint 会报错；live exporter 也拒绝使用回补 checkpoint。不能用现有 snapshot 冒充其之前请求的回执。若最早日志已裁剪而缺少前缀，必须停止并核对覆盖范围，不能把缺失结果当成拒单。
4. 核对 `COMMAND_RESULT_REPAIR_COMPLETE`、截止位置和所需用户的回执后，再更新 gateway、Web、client。回补会正常重放交易状态以计算结果，但只写回执投影；不写订单/水位/资金费投影、不连接 Kafka、不向在线 Core 提交交易，也不覆盖在线 checkpoint。无需重置正常导出或重发旧成交。

本轮仅验证本地迁移与回补机制，没有部署线上或执行线上历史回补。回执不存在时仍然待确认：请求根本未到 Core、尚在途中、或不在可回补日志内，都不能据此作负确认。网关提交前拒绝的持久负确认尚未实现。

## 验证范围

HotSpot JDK 27 定向测试按测试类去重共 174 项，全部通过，无跳过。覆盖六产品确定性回放；SPOT 无订单实体的资金拒单；140 条后续命令超过 Core 缓存窗口；真实隔离 PostgreSQL 幂等、冲突回滚、重启读取、用户/产品隔离及非法内容；真实 Archive/嵌入 Kafka 的崩溃窗口和有限回补、checkpoint 用途与续跑；以及 12 项 SPOT 原生撮合资金用例（LIMIT IOC、MARKET IOC/FOK 的碎片成交、快照恢复、买卖冻结、手续费、撤单解冻和用户/做市/财库资金守恒）。gateway 与 realtime 及依赖打包通过。

Web 172 项、lint/build 和 1440/390px 深浅色四组浏览器交互通过；client analyze 无问题、148 项测试通过，包含 320px 深浅色交互与本机 HTTP 接收 POST 后断开连接的恢复。浏览器使用受控 API/WS，不能替代真实 FUSED 交易验收。本轮没有启动完整 FUSED 栈、wallet、原生设备或其他产品 UI；算法、后台及生命周期专用查询仍须继续按范围审计。

命令、逐类数量、浏览器结果和临时资源清理见 [验证记录](validation/committed-command-results-20261019.json)。

## 撤单响应丢失恢复（2026-10-19）

新增 `GET /api/v1/gateway/trading/cancellations/{orderId}?userId=<认证用户>`，带原 productLine。`OrderService.cancellation` 使用与实际撤单相同的 StableOrderIdentity（产品、用户、`cancel:<orderId>`）推导命令号；`AeronOrderProjectionRepository.cancellation` 只读取 realtime 已提交回执，还要求记录类型为 CANCEL_ORDER。返回既有 OrderCommandReceipt，prospectiveOrderIds 为原订单号；客户端据此核对恢复身份，不能用下单回执冒充撤单结果。

TERMINAL 返回 200，未查到返回 202 / RESULT_UNKNOWN，均 no-store。参数非法 400，数据源不可用或命令类型矛盾 503。不读 Core、不依据订单从活动列表消失推断撤单成功，也不重发撤单。NONE 只确认撤单命令处理成功，实际成交或撤销状态仍由最新订单视图展示。网关的 MVC、本地 FUSED 路由和静态路径清单同步更新；本轮未改 Core、撮合、资金、导出或 SQL 表结构，没有增加状态容器或服务进程。

Web `api/endpoints.ts.cancelOrder` 和 client `AppState.cancelOrder` 在现货 POST 前持久保存用户、产品、原订单号；存储失败不发送。断网、异常响应、资料不匹配或服务异常保留锁定；刷新/冷启动后只查原撤单。Web 交易页与订单页同步禁用重复撤单，client 在订单列表旁增加查询入口。收到明确回执后解除锁定并刷新账户，拒单提示与下单拒单区分。沿用同一份 pending 记录，不维护订单状态副本；迟到响应不得修改另一用户或已 dispose 的界面。

先保证上一节的回执迁移、realtime 更新与所需历史回补已完成，再发布本轮 gateway，最后更新 Web/client。无需追加迁移或重置导出 checkpoint。请求未到 Core、尚在途中或不在已回补范围内时仍未决，不能强制解锁；本轮只扩展普通现货单笔撤单，批量、改单等写入的完整异常恢复仍需继续验收。

本轮 145 项后端定向测试无失败或跳过（gateway 133 + 原生 SPOT 资金 12），Web 181 项、lint/build 和 client 155 项、analyze 通过。真实隔离 PostgreSQL 验证原命令身份、用户/产品隔离及命令类型不符时关闭查询；真实本机 HTTP 在接收撤单 POST 后断开连接，再以两次 GET 恢复，包含 64 位订单号。受控 API/WS 浏览器检查覆盖交易页、订单页各 1440/390px 深浅主题，共八组，每组仅一条 POST；Flutter 覆盖 320px 深浅色撤单锁定与查询。本轮未启动完整 FUSED 服务栈、wallet 或真机，也未验收其他产品 UI。详见 [撤单验证与清理记录](validation/spot-cancel-recovery-20261019.json)。

## 现货改单与恢复（2026-10-19）

现货单笔改单现在从 Web 交易页、订单页和 client 订单行进入。编辑的是新委托价格及新委托数量，默认取原单剩余数量；不会把原单已成交数量再次加到新单。两端按真实资产精度和步长做整数转换、检查数量/名义价值范围，保留原有效方式及 Post Only。Web 高级操作的单笔改单也补齐请求身份；批量改单另行验收。

`AmendOrderRequest.clientRequestId` 原本就是必填字段，两端旧调用漏传会在网关校验时返回 400。当前为一次改单生成随机请求号和新客户订单号，发送前保存在原有 pending 集合；保存失败不发送。client 的 API 方法在调用方只指定新客户订单号时，也会显式生成相应请求身份。响应需要包含匹配的已撤原单与有效新单，不能把空结果或未决回执当成成功。

新增用户查询 `GET /api/v1/gateway/trading/amendments?clientRequestId=<原请求号>`，带原 productLine 和认证身份；`OrderService.amendment` 使用实际改单同一 `StableOrderIdentity.replacementCommandId`，`AeronOrderProjectionRepository.amendment` 读取 realtime 的既有 SQL 回执并验证类型为 AMEND_ORDER。200 / TERMINAL、202 / RESULT_UNKNOWN、503 不可用，no-store；认证用户不能被 query 覆盖。没有访问 Core，也没有重新发送改单。该查询确认的是命令处理结果，不返回新的订单镜像；原单与新单的最终状态仍从 realtime 账户视图读取。

响应中断、资料错误或服务异常保持待确认，刷新/冷启动后按原请求号查询；确认成功或拒绝才解除锁定。新建的两个界面文件只拥有编辑器输入、弹窗及控制器生命周期，使用原有 Dialog/BottomSheet 和状态模块，不增加订单状态副本、服务进程或 Core 热路径逻辑。Web 弹窗补充局部样式，避免继承订单表格的 nowrap 和对齐方式导致横向溢出。

本轮未改 Core 交易生产代码、导出、Topic、snapshot 或 SQL 表。仍依赖此前 ae61e530d 的回执迁移、realtime 更新和所需历史回补；先发布支持该查询的 gateway，再更新两端，不重置正常导出 checkpoint。请求未到 Core 或回执尚不可见时仍不能作负确认；批量下单/改单/撤单、其他产品 UI 及全栈验收继续进行。

HotSpot JDK 27 下 162 项后端定向测试无失败或跳过：gateway 143、SPOT 原生资金 16、替换响应编码 3。原生新增四组覆盖未成交/部分成交及改单前快照恢复：资金不足保留原单冻结，改单成功取消原单并按新数量预留，做市对手成交后的用户/做市/财库资金守恒。部分成交前的原始手续费缓冲直到替换/撤单才释放，测试按初始预留减实际成交支出核对，而非机械按剩余数量重算。

Web 188 项、lint/build，client 166 项及 analyze 通过。浏览器对交易页、订单页各做 1440/390px 深浅主题八组受控 API/WS 检查，含弹窗边界、非法精度、原剩余数量、64 位订单号、发送前保存、断响应/刷新、未决锁定及两次 GET 恢复；每组仅一条 POST。Flutter 覆盖 320px 深浅主题、真实本机 HTTP 的改单字段/两张订单解码，以及丢失 POST 响应后只读恢复。本轮未启动完整 FUSED 交易服务栈、wallet 或原生真机，没有调试其他产品界面。见 [改单验证及清理记录](validation/spot-amendment-20261019.json)。
