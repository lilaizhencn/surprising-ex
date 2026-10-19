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

## 现货批量撤单与固定目标恢复（2026-10-19）

业务顺序：用户确认“当前交易对”或“全部现货” → 网关从 realtime 用户完整活动视图一次读取固定订单 ID → Web/client 在发送前保存全部目标、每批键和进度 → 原生批次最多 50 笔，逐批提交 → 按逐项结果统计 → 响应丢失时查询原批次，确认后仅继续尚未发送的批次。新产生的委托不进入原请求；计划查询没有列表分页或 1000 笔截断。

- `GET /api/v1/gateway/trading/cancellation-plan`：当前认证用户，`instrumentId` 可选；返回 `CancellationPlanResponse`（用户、产品、范围、完整订单 ID）。`OrderService.cancellationPlan` 只读 `ValkeyUserQueries`，视图未就绪拒绝，不查询 Core。
- `POST /api/v1/gateway/trading/batch-cancel`：必须显式携带 `batchKey`，以及同一账户的 `orders`，每批 1–50 笔。原生顶层 `NONE` 仍可能包含逐项失败；批次包含其他账户订单时整批 `ORDER_OWNER_MISMATCH`，不能按单项跳过归属校验。
- `GET /api/v1/gateway/trading/cancellation-batches?batchKey=...`：只读 realtime 提交投影，以认证用户、产品和原生 `batch:cancel:` 命名空间定位请求，返回原目标与逐项业务结果，`Cache-Control: no-store`。结果未知返回 202；已提交的业务拒绝按现有 `commandResponse` 状态契约返回，客户端必须检查 outcome/code/逐项结果。

`CommittedTradeReplay` 在原有撮合、账户 Lane 与账本提交边界之后，提取最多 50 个 index/orderId/success/code；不复制订单或账户快照。`CommittedOrderProjectionRepository` 将这些事实与原命令回执、订单和水位写入同一事务。已有回执只能由确定性提交回放补充 NULL 字段；非空内容冲突会回滚，不能覆盖原结果。`CancellationPlanResponse` 是只读 HTTP 契约，不增加交易业务状态；两端保存的是尚未确认的用户操作目标和发送断点，不作为账户或订单权威视图。

发布顺序：

1. 确保此前命令回执表已存在，执行 `deployment/migrations/20261019-cancel-batch-results.sql`。
2. 更新 realtime 导出程序；需要恢复旧批次时，按上面的 `CommittedCommandResultRepair` 六参数流程新建独立 `*.command-repair` checkpoint，从保留日志前缀回补到指定截止位置。此前已经完成的回补 checkpoint 不会自动重新处理旧命令。不可复制、清零或覆盖 live checkpoint，不重发 Kafka 成交。
3. 核对所需批次 `cancel_batch_results` 及回补截止位置后，发布新 gateway，再发布 Web/client。无需更改在线 Core 协议、交易代码或 snapshot 格式。

恢复按钮“确认并继续撤单”只查询已发送的当前批；确认结果后可以继续 POST 原计划中尚未发送的后续批次。已发送批次不会再次 POST。初始保存失败不提交；进度保存失败停在原断点；查询失败、回执目标/命令号/计数不符均保持锁定；换账户不会继续发送原账户后续批次。Web 使用同账户 Web Locks 串行恢复，不支持该能力的浏览器在发送前明确报错。client 随安全设置保存断点，重启后恢复。

本轮范围为普通现货批量撤单。旧直接 `cancel-open` 聚合端点、client 的旧底层直接聚合调用、算法/触发单批量操作、批量下单/改单及其他产品 UI 的完整异常恢复不在此次验收范围。没有部署或重跑完整 FUSED 服务栈，也没有安装原生 iOS/Android；实际验证见 [批量撤单记录](validation/spot-bulk-cancellation-20261019.json)。

## 现货 FUSED 两端实链路验收（2026-10-19 后续）

本轮在全新隔离数据库初始化时复现了 `cancel_batch_results` 缺少专用业务说明导致的 schema gate 失败。`init.sql` 的目录注释映射与 `20261019-cancel-batch-results.sql` 现均说明逐项事实、50 笔边界以及 NULL 的未决语义；完整初始化和运行时重放通过，没有绕过门禁。

`SixProductLineContractLiveTest` 先使用后台接入 API 启用目标产品，再验证热连接；回执查询接受异步投影的 202，有限等待 GET 终态，不重发下单。可选 `SIX_LINE_LIVE_UI_FIXTURE` 将现货用户和交易对交接给两端验收，真实 maker 在此期间持续挂单；凭据文件限制为 0600，交接限时四分钟，finally 删除，成功或失败都清理本测试创建的做市策略。每次仅启一条产品，fixture JDBC 只允许既有测试库前缀。

两端使用真实网关 HTTP 和 WebSocket，仅在已确认属于本轮的 realtime 进程上短暂停顿，然后恢复：

- Web 在 1440 深色和 390 浅色页面各处理 51 个固定 ID；原批次一笔先由另一会话撤掉，回放显示 50 成功、1 失败。暂停导出时收到真实 202，恢复并重新加载页面后只发送后续 1 笔批次；计划之后新增的订单保留，验收后另行撤掉。
- Flutter 使用真实 `AppState`/`ApiClient`/订阅与安全设置接口的测试存储，52 笔原目标分成 50+2；代理在网关收到并处理首批后断开响应，不伪造服务结果。暂停时 GET 未决，恢复并重建 AppState 后原键保留；总计两个不同批次，51 成功、1 已结束。真实盘口两侧存在，私有冻结清零，320px 暗色面板可查看失败详情。
- 做市暂停后，Core 对账验证用户、做市无预留/冻结，无现货持仓，用户+做市+财库逐资产守恒差额 0；六笔基础成交的 REST/WS tradeId 对齐，账户和订单查询与权威状态一致。全过程五个应用进程不重启。

复现需先按当前脚本启动单节点 SPOT、realtime 导出、独立数据库/Kafka/Valkey 和测试外部 ticker。启动 `SixProductLineContractLiveTest` 时同时显式设置原 `SIX_LINE_LIVE_*` 参数及 `SIX_LINE_LIVE_UI_FIXTURE=/tmp/surprising-spot-fused-<run>/client-fixture.json`。在四分钟交接窗口内，将该路径交给两端的 `SPOT_FUSED_LIVE_FIXTURE`，并把所属运行目录的 `pids/realtime.pid` 交给 `SPOT_FUSED_REALTIME_PID_FILE`；两个消费者都校验 PID 的 launcher identity 后才注入暂停。Web 的 `scripts/spot-fused-live.mjs` 使用网关允许的本地开发地址 127.0.0.1:5173，需要现有 Playwright 包（可用 `SPOT_FUSED_PLAYWRIGHT_MODULE` 指定模块路径）和 Chromium（可用 `SPOT_FUSED_CHROMIUM` 指定已有二进制）。Flutter 使用 `flutter test test/spot_fused_live_test.dart --reporter expanded`。两端均退出 0 后，向 fixture 路径加 `.done` 的文件写入 PASS，父验收继续核对余额、成交和进程身份。未设置显式 fixture 时 Flutter 实栈用例默认跳过，普通回归不会连接外部服务。

该结果补充了上轮受控 API/WS 的证据；尚未在 iOS/Android 设备安装运行，也未验收交割、期权或批量下单/改单的全部异常恢复。本轮只修改 schema 说明与验收代码，未增加 Core 查询或更改交易协议、在线状态。详见 [实际运行与清理记录](validation/spot-fused-clients-20261019.json)。

## U 本位交割结算中减仓与恢复（2026-10-19）

本轮只验证 `LINEAR_DELIVERY`。`InstrumentRequestService` 的公开列表与按 ID 查询需保留 SETTLING 合约，否则刷新后仍有持仓却无法找到合约。可见性仅提供元数据，不授予交易权限；普通订单由既有交易校验决定，SETTLING 仅允许直接 reduceOnly，HALT 禁止下单，结算中不新增 TP/SL。

Web `TradePage` 的开/平按钮按此规则启用；client `ApiClient.linearDeliveryInstruments` 额外取得该产品的可见生命周期目录，`AppState.refreshInstruments` 成功后统一发布，目录失败保留上次完整结果。client 减仓校验持仓方向、保证金模式和剩余数量，HEDGE 平仓显式发送 reduceOnly；320px 票据显示“买入平空/卖出平多”，距到期使用 expiryTime。

两端沿用现货已有的单笔恢复存储，扩展至 U 本位交割：POST 前保存 user/product/instrument/clientOrderId 或原 cancelOrderId，保存失败不提交。响应不完整、丢失或查询未知均保留待确认；恢复只 GET 原提交或撤单回执，不重复 POST，不靠订单是否存在推断撤单结果。其他产品与批量下单、改单的恢复需继续逐项验收。

实栈入口仍为 `SixProductLineContractLiveTest`，设置 `SIX_LINE_LIVE_PRODUCT=LINEAR_DELIVERY`、`SIX_LINE_LIVE_TRADING=true`、隔离 JDBC 和 `SIX_LINE_LIVE_UI_FIXTURE=/tmp/surprising-linear-delivery-fused-<run>/delivery-fixture.json`。依照仓库启动器，仅启该产品的单节点 FUSED、gateway、price、realtime、derivatives-lifecycle、maker。显式建一个未来到期合约、开两张真实持仓，再经管理 API 转 SETTLING；真实做市账户提供两张 reduceOnly 平仓流动性。此阶段不等于实际到期交割结算。

交接窗口限四分钟；凭据 0600，父用例 finally 删除。两个消费者设置 `LINEAR_DELIVERY_LIVE_FIXTURE` 和 `LINEAR_DELIVERY_REALTIME_PID_FILE`：Web 执行 `node scripts/linear-delivery-fused-live.mjs`，使用允许的 127.0.0.1:5173，已有 Playwright/Chromium 可由 `LINEAR_DELIVERY_PLAYWRIGHT_MODULE`/`LINEAR_DELIVERY_CHROMIUM` 指定；client 执行 `flutter test test/linear_delivery_fused_live_test.dart --reporter expanded`。Web 先平一张，client 再平剩余一张；都校验所属 launcher identity 后才能暂停 realtime。仅丢弃真实已处理响应，不伪造结果。两个消费者退出 0 后才向 fixture 同名加 `.done` 写入 PASS，父用例继续核对资金和进程身份。普通 Flutter 回归没有显式 fixture 时跳过实栈测试。

实测还发现两个后端阻塞点：`IndexInstrumentConfigLoader` 在 SETTLING 过滤指数源，导致新配置版本的市价单报 `mark price unavailable`；现已为 U 本位交割保留指数源至 CLOSED，不放宽市价保护校验。`LeverageService.get` 现从 `ValkeyUserQueries` 读取认证用户和产品的杠杆覆盖值，删除 `OrderAeronGateway.leverage` 的 Core 查询入口；就绪账户没有覆盖值时使用合约定义的默认设置，未就绪/过期或身份不匹配返回 503。GET 允许本产品 SETTLING，杠杆 POST 仍要求 TRADING。

Web 在结算中禁止调整杠杆，直接减仓不依赖开仓杠杆设置；保证金模式能独立切换，并与持仓行平仓入口保持一致。平仓摘要显示对应保证金模式与持仓方向的可平张数；U 本位交割手续费估算按 ticks × contracts × notionalMultiplierUnits × feeRatePpm 向上取整到结算资产单位，保留小额费用精度，不再把一张合约当一整枚基础币。

风险验收先有限等待账户与持仓风险的盈亏/维持保证金一致，再按返回标记价独立核算，避免跨两次价格更新比较。不会取消盈亏、权益、保证金或资金守恒断言。共享杠杆 GET 的六产品隔离与未就绪行为有定向测试；完整运行仍只启动 LINEAR_DELIVERY，其他产品风险、算法/管理/生命周期的剩余 Core 读取和批量写入需后续继续检查，不能据此宣称全部查询迁移或全部交割功能验收完成。

发布时必须同时包含本轮 price 与 gateway 修复，然后发布 Web/client；仅更新 gateway 仍会因旧 price 停止结算中指数源而拒绝市价平仓。本轮无需新增 SQL 迁移，但此前 realtime 回执迁移、导出和必要历史回补仍是前置条件。


## OPTION 两端恢复及真实到期验收（2026-10-20）

期权单笔下单和撤单沿用原持久化意图与 realtime 回执，保存失败不发送，未知结果保留待确认。Web 移除不存在的 `/api/v1/options/quote` 请求；client 移除按价内外猜测 Delta。Greeks/IV 定价接口尚未实现，两端显示暂无可信报价。权利金按 ticks × 张数 × notionalMultiplierUnits 全额计算，再按结算资产精度展示手续费；没有杠杆除法。卖方保证金由提交时 Core 风险核定，前端不编造精确保证金。

`SixProductLineContractLiveTest` 可用 `SIX_LINE_LIVE_PRODUCT=OPTION`、`SIX_LINE_LIVE_TRADING=true`、`SIX_LINE_LIVE_EXPIRY=true`、本地隔离 JDBC 及 `/tmp/surprising-option-fused-<run>/ui-fixture.json` 交接两端。主合约在真实做市下开平、撤单、核对 WS/REST 成交标识、风险及资金。Web 买一张、Flutter 平一张，分别丢真实响应；Flutter 还暂停该 run 的 realtime、查询未知、恢复并重建 AppState，再查询原结果，每个独立意图只 POST 一次。四分钟后自动退出交接，finally 删除凭据。

到期矩阵同一 OPTION 集群创建 CALL/PUT 各三种行权价，在同一时钟到期。每组两张、权利金 10 USDT；本轮标的结算价 52000，行权价为 50000/52000/54000，价内每组现金 4 USDT。普通挂单、OCO TP/SL、做市报价均经已有生命周期排空，双人审批确认后读取 realtime 产品账本。重复确认不产生新结算版本，零现金不造流水；所有参与账户与财库资金守恒。client 的现有两个实栈测试通过 DELIVERY_LIVE_PRODUCT/DELIVERY_EXPIRY_PRODUCT=OPTION 选择此产品，未设置隔离 fixture 时仍跳过。

发布顺序和分类迁移见 realtime README。既有 Core 日志中价格单位错误的结算不会因读端更新而自动改写，必须独立对账。此次没有原生设备安装、生产部署、希腊值报价、非本轮产品实栈或全面强平/ADL 验收；[完整验证记录](validation/option-trading-20261020.json)。

### 期权 OCO 原请求（2026-10-20）

`GET /api/v1/gateway/trading-trigger/oco-submission` 使用原 takeProfitClientId、stopLossClientId 和当前用户/产品，查询同一次 `PLACE_TRIGGER_OCO_PAIR` 的已有已提交回执。不存在、尚未导出或查询异常不能作原请求负确认。Web/client 在一次原子写入前持久保存两腿身份，刷新/重建后只读恢复；其中一腿已触发、另一腿已撤销也不重发。详情见 [期权保护委托](option-protection.md) 和 `docs/validation/option-protection-20261020.json`。


## 客户端确认资料保存失败（2026-10-20）

现货 client 的普通下单、单笔撤单、改单及原结果查询原先在删除内存恢复记录后忽略设置存储失败，导致当前界面提前解锁，重启却恢复旧请求。已完成的批量撤单也有相同问题。现在由原 `AppState._writeSettings` 串行边界先保存排除原请求的设置，成功后再解除内存锁定；失败保留同一请求，仅允许查询确认，已完成批次不重新 POST。独立条件单/OCO 的已有清理入口共用这一写入边界，不新增恢复状态容器。

Web 的持久锁定原本已保留；本轮将删除失败的底层存储错误改为明确的中英文查询提示，并修正 320px 长提示宽度。受控 UI 回归涵盖交易页与订单页；client 回归涵盖成功、明确拒绝、确认期间、重建及批量计划。

没有后端源码、SQL、消息协议、Core 交易或资金模型变更；查询继续使用本文已有 realtime 已提交回执。没有启动新的后端服务，因此本轮不声称新增 FUSED 成交或资金对账证据，也未安装原生设备。验证及临时文件清理见 [记录](validation/spot-confirmation-storage-20261020.json)。
