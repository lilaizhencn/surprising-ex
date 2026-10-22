# 最新开发规范审查问题清单

日期：2026-10-22。状态：**审查完成，本轮未修复业务代码**。

本文件合并上一轮 7 类发现及本轮深入检查结果，共 **25 项**；同一根因的多处表现合并记录。覆盖交易后端 `surprising-ex`、用户 Web、管理 Web 和 Flutter Client。钱包独立工程、社交工程及 Ourbit 不在本次范围内。

依据：[后端规范](AGENTS.md)、[简化规范](AGENTS-simplify.md)、[性能规范](AGENTS-performance.md)、[用户 Web 规范](../surprising-ex-web/AGENTS.md)、[管理 Web 规范](../surprising-admin-web/AGENTS.md)、[Client 规范](../surprising-client/AGENTS.md)。

审查基线：后端 `cc9df94a8`，用户 Web `b6c22965d`，管理 Web `6e5769653`，Client `609055a01`。Client 的 5 个已修改文件和新增 `spot_algo_order_sheet.dart` 按当前工作区检查，包含未提交代码；后端两个已暂存的工具文件删除不属于本轮。本次覆盖重点链路与异常场景，不表示已经逐行穷尽全仓库。

**证据口径：**“已复现”包含本地 HTTP、真实方法调用、故障注入或上一轮浏览器操作，不等同于生产环境事故；“源码确认”表示存在明确代码路径，尚未完成对应环境联调。P1 优先修复资金、产品线、鉴权与可用性问题，P2 随后处理健壮性和架构，P3 为低风险维护清理。未测出的性能、泄漏、布局问题放在文末，不计为确定缺陷。

## 问题索引

| 编号 | 优先级 | 范围 | 问题 | 证据 |
| --- | --- | --- | --- | --- |
| D01 | P1 | 管理 Web | 调账金额、用户 ID 提交前经过不安全 Number 转换 | 转换表达式复现、源码确认 |
| D02 | P1 | Client | 金额整数解析把异常变成零、舍入或饱和值 | 已复现 |
| D03 | P1 | 用户 Web / Client | 不完整私有快照被标为 READY | 两端已复现 |
| D04 | P1 | Client | 未知合约类型被归入可交易的 U 本位永续 | 已复现 |
| D05 | P1 | Client | bookTicker 固定按 1e8 换算价格 | 已复现 |
| D06 | P1 | 后端 | 已订阅 WebSocket 私有流缺少会话撤销后的复核 | 本地鉴权桩复现 |
| D07 | P1 | 三个前端 | HTTP 超时没有覆盖完整请求生命周期 | 用户 Web / Client 已复现，管理端源码确认 |
| D08 | P1 | 用户 Web | 启动存储异常、懒加载失败可导致白屏 | 上一轮浏览器复现，本轮源码复核 |
| D09 | P2 | 管理 Web | 本地会话只做类型断言，坏数据可进入渲染 | 已复现消费表达式 |
| D10 | P2 | 两个 Web | 刷新令牌遇到临时服务故障也清除会话 | 两端已复现 |
| D11 | P2 | Client | 异常 depth 参数可绕过盘口容量限制 | 已复现 |
| D12 | P2 | Client | 盘口断档恢复缺少截止时间与再次恢复机制 | 源码确认 |
| D13 | P2 | 用户 Web | 估值错误未被资产页使用，旧汇率缺少过期判断 | 源码确认 |
| D14 | P2 | 后端 | 全局订阅锁覆盖外部 Redis 调用 | 阻塞传播已复现 |
| D15 | P2 | 后端 | 接收线程启动后构造失败，缺少资源回滚 | 源码确认 |
| D16 | P2 | 后端 | 实时帧处理异常仅计数，缺少定位与恢复信号 | 源码确认 |
| D17 | P2 | 后端 | Controller 被作为业务服务继续调用 | 源码确认 |
| D18 | P2 | 后端 | 运行时状态、线程生命周期及可变组件暴露耦合 | 源码确认 |
| D19 | P2 | 三个前端 | 页面、状态与业务责任集中，Client 根节点订阅全局变化 | 源码确认，性能未测 |
| D20 | P2 | 后端 | 部分运行配置仍散落在系统属性与静态初始化中 | 源码确认 |
| D21 | P2 | 后端 | 共享 API 默认传递 Feign / Spring Web 适配依赖 | POM 确认 |
| D22 | P2 | 后端 / Client | 遗留无业务引用的配置、状态、方法和批次类型 | 仓库引用检查 |
| D23 | P2 | 后端 / 管理 Web | symbol 与 instrumentId 命名、标签不一致 | 源码确认 |
| D24 | P3 | 后端 | 无边界的转发方法、重复协议常量 | 源码确认 |
| D25 | P2 | 用户 Web | 登录输入框按 Enter 不提交 | 上一轮浏览器复现，本轮源码复核 |

## 资金、产品线与鉴权

### D01：管理端写请求丢失整数精度

- **位置：**[App.tsx](../surprising-admin-web/src/App.tsx#L2844) 的 `AccountsPage.adjustBalance`；同文件约 4934 行的保险资金调整、2400 行的批量撤单用户过滤、5590 行的做市用户选择也有类似转换。
- **问题：**`userId: Number(userId)`、`amountUnits: Number(adjust.amountUnits)` 在序列化之前丢失精度。输入 `9007199254740993`，实际 JSON 为 `9007199254740992`；空字符串还会被转换成零。后端 [BalanceAdjustmentRequest](surprising-gateway/src/main/java/com/surprising/account/api/model/BalanceAdjustmentRequest.java) 使用 `long`，无法还原前端已经舍入的值。
- **影响：**管理员确认的金额、用户范围可能与发送值不同。审批绑定的是转换后的请求，不能补救该精度丢失。本轮没有发送实际调账请求。
- **建议与验收：**金额、ID 保留十进制字符串，提交前校验格式、符号和 int64 范围；核对后端字符串绑定方式。覆盖安全整数边界、long 边界、负调整、空输入、审批正文与最终请求一致性。

### D02：Client 的通用解析掩盖资金异常

- **位置：**[models.dart](../surprising-client/lib/src/models.dart#L2199) 的 `asInt/asNullableInt/asDouble`、约 817 行的 `ProductBalance.fromJson`；[app_state.dart](../surprising-client/lib/src/app_state.dart#L4828) 的私有余额物化。
- **证据：**`availableUnits: "invalid"`、`lockedUnits: null` 被转成 0；缺账户、资产时生成 `SPOT/USDT`。零金额即使缺少 scale 也返回 0。`asInt("1.5") == 2`；`asInt("9223372036854775808") == 9223372036854775807`；`NaN/Infinity` 抛 `UnsupportedError`。
- **影响：**未知资金可能显示为真实零，整数错误被掩盖；异常数值也可能中断消息处理。不能据此断言整台 App 必然崩溃，实时入口已有 catch。
- **建议与验收：**资金整数采用严格、有限、范围明确的解析；未知值保留不可用状态，禁止舍入、饱和及默认资产。覆盖上述输入和私有快照到页面的完整链路。

### D03：不完整的账户快照也能 READY

- **位置：**[Web PrivateView](../surprising-ex-web/src/realtime.ts#L49)、[Client PrivateView](../surprising-client/lib/src/realtime_state.dart#L36)。
- **证据：**输入 `{op:"snapshot", data:{status:"READY", snapshotVersion:"0000000000000000001:0000000001", account:{}}}`，两端均接受并标为 ready。Web 的 `accountEquity(view, [], "SPOT")` 进一步返回 `complete:true, balances:[]`。快照处理没有先确认必需字段和数组完整性。
- **影响：**缺失账户内容可被当作正常空账户，还可能用更高 fence 清除已知状态。
- **建议与验收：**先整体校验再原子替换；明确合法空数组与缺字段的区别。异常快照保持不可用或触发重同步，不能推进有效 fence。覆盖缺账户、错误类型、缺数组、合法空账户及高低版本乱序。

### D04：未知产品类型默认映射到 U 本位永续

- **位置：**[Instrument.mode / fromJson](../surprising-client/lib/src/models.dart#L475)、[ApiClient._loadInstruments](../surprising-client/lib/src/api.dart#L954)。
- **证据：**`Instrument.fromJson({"symbol":"UNKNOWN-USDT","contractType":"UNRECOGNIZED"})` 得到 `mode=LINEAR_PERPETUAL, status=TRADING, canPlaceOrder=true`。缺字段还会补永续类型、币种和交易状态。普通目录加载会将结果按此 mode 放入目录。
- **影响：**异常或新增契约被误分类，展示、订阅和下单产品选择都可能建立在错误身份上；后端仍应拒绝不匹配命令，本轮没有证明能够越过 Core 校验。
- **建议与验收：**未知枚举明确拒绝或显示不可用，不能补成其他产品。六产品目录分别验证缺类型、未知类型、产品声明冲突和停牌状态。

### D05：bookTicker 忽略币对的报价精度

- **位置：**[AppState.handleRealtimeMessage](../surprising-client/lib/src/app_state.dart#L5081) 的 `bookTicker` 分支。
- **证据：**该分支使用 `ticks * priceTickUnits / 100000000`；同一模型的 `priceFromTicks` 使用 `quoteScaleUnits`。真实状态方法探针中，`quoteScaleUnits=1000000, priceTickUnits=10000, bid=100, ask=102`，应为 1.01，实际写入行情价为 0.0101。
- **影响：**最新价和使用该价的资产估值错误，同一币对在成交与 bookTicker 两条路径上的显示不一致。
- **建议与验收：**统一按 instrument 的单位换算；中间价也要避免先进行可能溢出的整数加法。覆盖不同报价精度、单边空盘口和两端同数据对照。

### D06：会话失效后，既有私有 WebSocket 订阅仍可推送

- **位置：**[ClientWebSocketHandler](surprising-gateway/src/main/java/com/surprising/websocket/provider/handler/ClientWebSocketHandler.java#L94)、[SubscriptionRegistry](surprising-gateway/src/main/java/com/surprising/websocket/provider/service/SubscriptionRegistry.java#L191)、[WebSocketJwtAuthenticator](surprising-gateway/src/main/java/com/surprising/websocket/provider/service/WebSocketJwtAuthenticator.java)。
- **证据：**鉴权发生在 authenticate 和新增私有订阅时；后续 fanout 依赖已登记连接。用真实 handler、registry 和模拟 WebSocket，先通过认证订阅，再令鉴权桩返回“session revoked”，私有消息仍送达，鉴权调用数保持 2。
- **影响：**服务端不能依靠前端主动退出实现撤销效果；令牌过期、后台撤销、冻结后的存量订阅缺少复核。此探针模拟鉴权失效，没有连接真实认证数据库。
- **建议与验收：**按会话身份和过期时间撤销连接或定期复核，并清除既有私有路由；避免逐条行情查数据库。验证撤销、冻结、到期、同用户多会话、重连和公共订阅保留语义。

## 异常、网络与持续运行

### D07：HTTP 超时未覆盖读完整响应

- **位置：**[Web request / requestBlob](../surprising-ex-web/src/api/client.ts#L111)、[Client _sendOnce](../surprising-client/lib/src/api.dart#L2272)、[管理端 request / requestBlob / refreshSessionOnce](../surprising-admin-web/src/api/client.ts#L71)。
- **证据：**本地服务器发出响应头和半截 JSON 后不结束。Web 配置 10 秒，11.5 秒后 Promise 仍 pending；Client 的 `request.close()` 配置 20 秒，22.1 秒后仍 pending。两端读取 body 的 `response.text()/utf8.decodeStream` 不在该超时内；管理端使用裸 fetch，没有默认应用级超时。显式 signal 可以取消部分 Web 请求，但不构成统一截止时间。
- **影响：**加载或提交状态可一直等待，重试和切页也可能保留未结束请求；响应体没有统一大小限制，超大数据还有内存风险，本轮未做内存极限试验。
- **建议与验收：**截止时间覆盖连接、响应头、body 和解码；超时真正取消底层请求，限制响应大小。写请求超时仍按结果未知及原幂等身份处理，禁止盲目重发。覆盖慢头、慢 body、不结束、切页取消和超大响应。

### D08：用户 Web 缺少启动与页面恢复边界

- **位置：**[main.tsx](../surprising-ex-web/src/main.tsx#L11)、[i18n/index.ts](../surprising-ex-web/src/i18n/index.ts#L9)、[App.tsx](../surprising-ex-web/src/app/App.tsx#L62)、[session.ts](../surprising-ex-web/src/state/session.ts#L11)。
- **证据：**上一轮在生产构建、真实 Chrome 中阻断 `TradePage-*.js`，动态导入失败后 root 内容为空；注入 `Storage.getItem` 抛 SecurityError，登录页 root 也为空。本轮确认源码仍直接读取存储，页面仅有 Suspense，缺少 ErrorBoundary。
- **影响：**发布后旧资源失效、临时资源请求失败或存储不可访问时，用户没有可恢复页面。Suspense 只覆盖加载中状态。
- **建议与验收：**启动前的存储读取独立容错，React 页面设置错误展示与可控重新加载；避免自动刷新死循环。复测存储拒绝、坏会话、懒加载 404/断网和恢复操作。

### D09：管理端从存储取出的会话未经结构校验

- **位置：**[loadSession](../surprising-admin-web/src/api/client.ts#L25)、[App](../surprising-admin-web/src/App.tsx#L415)。
- **证据：**`JSON.parse(raw) as AuthSession` 只验证 JSON 语法。本地值 `{"user":{"userId":1},"accessToken":"audit"}` 被接受，页面后续 `session.user.roles.some(...)` 抛 TypeError。探针运行了实际 loadSession 和同一消费表达式，未启动管理端浏览器复现整页。
- **影响：**旧版或损坏存储可阻断进入后台；TypeScript 类型断言没有运行时校验效果。
- **建议与验收：**在边界验证会话完整性、有效期和用户身份；坏数据清理并返回登录入口。验证空对象、null、缺 roles、错误角色类型和过期会话。

### D10：临时刷新失败会把有效登录状态清除

- **位置：**[Web refreshStoredSession](../surprising-ex-web/src/api/client.ts#L39)、[AppRoutes 初始化刷新](../surprising-ex-web/src/app/App.tsx#L81)、[管理端 refreshSessionOnce](../surprising-admin-web/src/api/client.ts#L147)。
- **证据：**Web 的 catch 对所有异常执行 `saveSession(null)`，初始化还有无条件清会话的 catch；管理端 refresh 对所有非成功状态清会话。本地将刷新接口设为 503，两端存储会话均变成 null。
- **影响：**服务抖动被当作凭证确定失效，用户被动退出。Client 在 401/403 与网络错误之间已有区分，可参考其语义，但不能直接复制实现。
- **建议与验收：**区分确定失效与暂不可用，恢复期间暂停私有操作并显示原因；保留账号切换检查。覆盖 401/403、503、断网、超时、刷新轮换和恢复后重试。

### D11：Client 的 depth 上限由消息直接决定

- **位置：**[AppState._applyDepthUpdate](../surprising-client/lib/src/app_state.dart#L5125)、[\_mergeDepthLevels](../surprising-client/lib/src/app_state.dart#L5288)。
- **证据：**增量直接读取服务器 `depth`，只有 `depth > 0` 才截断。真实状态方法输入一个正常基线，再发 `depth:-1` 和 100 个新价档，最终保留 101 档。负价格、超大 depth 也缺少与快照一致的入口限制。
- **影响：**坏数据能突破每侧 50 档约束；持续消息可能放大排序、分配和内存占用，增长程度未长稳测试。
- **建议与验收：**按订阅契约固定上限、严格校验价格和数量，错误消息触发基线恢复。覆盖负值、0、超大 depth、超量增量、零档删除、空盘口和正常 50 档。

### D12：Client 盘口恢复可能一直等待丢失的快照

- **位置：**[AppState._applyDepthUpdate](../surprising-client/lib/src/app_state.dart#L5147)、[RealtimeClient](../surprising-client/lib/src/api.dart#L2438)。
- **问题：**发现 previousSequence 断档后只重订阅一次，设置 `_depthRecoveryKey`，之后忽略增量直到快照；该等待没有截止时间或再次请求机制。socket 也没有应用层存活/进度检查或 `pingInterval` 配置，重连主要依靠 onDone/onError。
- **影响：**连接看似存活但基线未恢复时，旧盘口可持续停留。这里确认的是恢复机制缺口，没有把静态检查写成已完成弱网长稳复现。
- **建议与验收：**为恢复基线和连接存活设置有界检测，合并重复恢复请求。模拟首次恢复无结果、静默半开、后台切前台，确认能恢复且不会订阅风暴。

### D13：资产页没有使用估值链路的错误状态

- **位置：**[useRealtimeAssets](../surprising-ex-web/src/hooks/useRealtimeAssets.ts#L126)、[AssetsPage](../surprising-ex-web/src/features/assets/AssetsPage.tsx#L25)。
- **问题：**hook 在汇率失败时设置 error，但不清除旧 fx，也不记录过期时间；资产页判断 `hasUsdValuation` 和错误展示只使用页面自己的 asset-scales error，没有使用 `realtime.error`。行情初始价同样缺少这里可见的时效判断。
- **影响：**已有汇率随后持续失败时，仍可能展示旧 USD 估值；首次失败时则可能长期只显示骨架，没有对应失败原因。不能由“保留旧值”直接推断每次旧值都不合法，缺的是明确时效和陈旧标记。
- **建议与验收：**把估值可用性、时效、失败和最后成功时间纳入同一状态；过期数值标记陈旧或停止汇总。验证初次失败、成功后失败、恢复和无持仓场景。

### D14：一个慢订阅回调可阻塞其他用户深度发布

- **位置：**[SubscriptionRegistry.subscribe / publishDepth](surprising-gateway/src/main/java/com/surprising/websocket/provider/service/SubscriptionRegistry.java#L96)、[RealtimeWebSocketBridge.subscribed / heartbeat](surprising-gateway/src/main/java/com/surprising/websocket/provider/service/RealtimeWebSocketBridge.java#L57)。
- **证据：**registry 的 synchronized 方法在持锁时调用 lifecycle；bridge 的同步回调执行 Redis renew、heartbeat、register。用 latch 暂停订阅回调，另一任务对已订阅币对调用真实 publishDepth，必须等待回调释放才能完成。
- **影响：**外部 I/O 的等待扩大为全局深度发布等待；后台 lease heartbeat 持有 bridge 锁时也可延长该链路。探针只证明锁传播，没有据此给出生产吞吐或延迟数字。
- **建议与验收：**把外部调用移出全局状态锁，同时保留订阅登记、失败回滚和退订的顺序语义。验证 Redis 延迟/故障下其他连接可继续工作、引用计数平衡、首条基线正确。

### D15：启动中途失败缺少已经启动资源的回滚

- **位置：**[RealtimeWebSocketBridge 构造器](surprising-gateway/src/main/java/com/surprising/websocket/provider/service/RealtimeWebSocketBridge.java#L33)、[AeronRealtimeReceiver 构造器及 close](surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronRealtimeReceiver.java#L20)。
- **问题：**bridge 构造期间创建 receiver，receiver 构造器立即启动平台线程；随后 bridge 调用依赖 Redis 的 heartbeat。若此处抛异常，构造器没有关闭 receiver，未成功创建的 Bean 也不能依赖其 PreDestroy 清理。receiver.close 等待 5 秒后没有确认线程已退出。
- **影响：**部分启动失败可能留下接收线程和连接重试；生命周期不满足“失败也能完整释放”。这是构造时序确认，本轮未故意制造真实节点资源泄漏。
- **建议与验收：**在现有 bootstrap/lifecycle 内明确创建、启动和失败清理顺序；关闭失败可观察。验证 Redis 不可用、启动取消、重复启动失败及所有本轮线程退出。

### D16：Aeron 接收回调静默丢失异常上下文

- **位置：**[AeronRealtimeReceiver](surprising-aeron-core/surprising-aeron-client/src/main/java/com/surprising/aeron/client/AeronRealtimeReceiver.java#L24)。
- **问题：**同一个 catch 捕获解码和 `handler.accept` 的 RuntimeException，仅递增 failures。缺少异常类型、产品/帧身份和恢复信号；bridge 没有把该计数接入这里可见的健康检查。异常后的 receiver 仍可能保持 ready。
- **影响：**帧或下游发布处理失败后，运行状态与业务完整性脱节，难以定位丢掉的是坏数据还是系统故障；不能把单纯累计计数等同于可恢复处理。
- **建议与验收：**保留有界、限频且不泄露敏感数据的诊断；区分坏帧和处理故障，对受影响基线触发恢复或降级。验证解码异常、发布异常、恢复和故障计数可观测性。

## 架构与配置

### D17：Controller 继续被当作 Service 调用

- **位置：**[AccountAssetsController](surprising-gateway/src/main/java/com/surprising/gateway/provider/controller/AccountAssetsController.java#L32)、[AccountRequestController](surprising-gateway/src/main/java/com/surprising/account/provider/controller/AccountRequestController.java#L110)、[AccountLocalRoutes](surprising-gateway/src/main/java/com/surprising/gateway/provider/local/AccountLocalRoutes.java)。
- **证据：**余额查询为 `AccountAssetsController → products.service(...AccountRequestController.class) → AccountService`；LocalRoutes 也持有 RequestController。简单 balances 方法只是转发，但同一 Controller 的其他方法还承担内部协议绑定和错误转换。
- **影响：**协议入口和业务能力名称混在 service locator 中，新增入口需要依赖另一个入口对象，MVC 边界不直观。
- **建议与验收：**入口共用实际查询/命令 Service；确有作用的内部协议绑定保留在清楚的适配边界。只消除无职责转发，不机械删所有 RequestController。验证 HTTP、本地路由、校验和错误码等价。

### D18：TradingRuntimeState 的名称和责任范围不匹配

- **位置：**[TradingRuntimeState](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingRuntimeState.java#L3)、[TradingCoreRuntime](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java#L631)。
- **证据：**前者不仅存状态，还创建并关闭 bootstrap 的 SettlementLaneWorker、处理线程交接、派发查询及账户业务；当前 8514 行。后者把 pendingMatching、matcherPipeline 等可变内部组件公开，调用方依靠注释遵守 Owner 边界。
- **影响：**启动、所有权与业务修改交织，类名“State”不能表达其实际责任；移动文件后仍依靠跨包公开内部对象协作。行数只是定位线索，问题依据是具体责任和依赖方向。
- **建议与验收：**在线程启停、Owner 调度、账户状态和现有业务类之间重新划清边界，收窄可变对象访问；不为每个字段增加转发接口，不复制权威状态。必须验证交接、异常停机、资金、快照及恢复。

### D19：前端责任集中，Client 高频状态传播到根节点

- **位置：**[Web TradePage](../surprising-ex-web/src/features/trading/TradePage.tsx#L173) 3640 行；[Client AppState](../surprising-client/lib/src/app_state.dart#L186) 5313 行、[app.dart](../surprising-client/lib/src/app.dart#L185) 11656 行；[管理 App](../surprising-admin-web/src/App.tsx#L383) 7699 行。
- **证据：**Web 交易页同时组织行情、账户、订单提交和终态恢复；AppState 同时管理登录、安全、钱包、交易、估值、公告和实时；管理 App 聚集多个业务页面。Client 根部 AnimatedBuilder 监听同一 AppState，通知会重新执行 MaterialApp/Theme 构建逻辑。
- **影响：**修改一条业务需要跨大量无关状态，异常、测试和生命周期边界难维护；全局通知存在高频渲染成本风险，尚无帧率数据证明已卡顿。
- **建议与验收：**按订单、行情、会话、资产等已有业务拆责任，页面保留展示编排；用局部状态和有界合批更新缩小通知范围。保留共用设计组件，不按固定行数拆文件；以完整流程和实际渲染数据验收。

### D20：配置入口尚未统一

- **位置：**[TradingExecutionMode.configured](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/config/TradingExecutionMode.java#L16)、[OwnerIdleStrategy](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/bootstrap/OwnerIdleStrategy.java#L95)、[TradingCoreRuntime](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java#L599)、[TradingRuntimeState](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingRuntimeState.java#L180)。
- **问题：**execution-mode、owner-wait-strategy、快照超时、max-pending-matching、lane-completion-spins 等仍直接读取 System 属性，有的在类加载时固定，有的在对象创建时再次读取。现有 typed CoreConfiguration 未覆盖这些入口。
- **影响：**YAML、Spring 配置优先级与 JVM 参数呈现多套规则，参数校验和测试覆盖分散；`MAX_PENDING_MATCHING` 名称也没有表达它是启动配置而非协议常量。
- **建议与验收：**配置值在 config 统一绑定、校验后注入，明确静态常量与运行参数。非 Spring 工具的 standalone Binder 明确不隐式加载 YAML，这是已说明的工具边界，不再次当作 Bean 调用问题；原 proxyBeanMethods=false 直接调用问题已修复。

### D21：共享 API 默认带入适配框架

- **位置：**[surprising-trading-api/pom.xml](surprising-trading-api/pom.xml#L24)、[surprising-account-api/pom.xml](surprising-account-api/pom.xml#L29)。
- **问题：**Feign Core、Spring Web 为普通传递依赖，account-api 又依赖 trading-api。仅需要模型的消费者会继承 RPC/HTTP 适配依赖，和 instrument-api 已做的适配依赖按需引入不一致。
- **影响：**公共契约和应用适配边界未完全分离，扩大依赖图；这不是已复现的编译错误。
- **建议与验收：**核对真正使用方后调整 optional 或适配边界；模型上的 validation/Jackson 注解按实际加载需求保留，不能一律删依赖。用纯模型消费者和实际适配消费者分别验证编译、启动与序列化。

## 命名、无用设计与冗余

### D22：残留状态、配置和清理模型没有业务使用者

| 位置 | 定义 | 当前证据 |
| --- | --- | --- |
| [TradingRuntimeState](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingRuntimeState.java#L180) | PARALLEL_SETTLEMENT_MIN_LANE_OPERATIONS | 只有声明和读属性表达式，无生产或测试使用；调整对应参数不会改变调度 |
| [ClusterCommandWindow](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/ClusterCommandWindow.java#L24) | ORDER_INDEX_ROTATION_THRESHOLD | private 常量只有声明，注释已说明实际阈值另算 |
| [TradingCoreRuntime](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java#L582) | MAX_STORED_RESPONSE_BYTES | 当前仓库只有声明；公共字段删除前仍需核对外部使用者 |
| [RuntimeOrderCommitStateTransitions](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/state/RuntimeOrderCommitStateTransitions.java#L24) | TerminalPruneBatch、empty、size、isEmpty、pruneTerminalState | 批次仅被同文件无人调用的 package-private 清理方法使用，未发现业务入口 |
| [AppState](../surprising-client/lib/src/app_state.dart#L868) | _triggerOrderEventVersions | 仅声明和 logout.clear，没有写入、版本比较或读取 |

- **影响：**形成假的配置能力和状态所有权，阅读者会以为另有终态或版本链路。不能因此推断实际 TerminalStateRetention 不工作，它有自己的现行路径。
- **建议与验收：**核对反射、配置、协议和外部引用后删除完整死分支；公开常量需额外确认 API 使用。不要删除快照测试仍使用的 `PHASE_TRAILING_LESS_OR_EQUAL`，也不要把 padding 字段当无用字段。

### D23：instrumentId 与 symbol 的字面含义混用

| 位置 | 当前写法 | 具体歧义与调整方向 |
| --- | --- | --- |
| [管理 DashboardPage](../surprising-admin-web/src/App.tsx#L665) | `[instrumentId, setSymbol]`，输入标签为 Symbol | 实际按 instrumentId 查找并发送 instrumentId；操作人员输入 BTC-USDT 会找不到 ID。改为明确的合约 ID 选择器，或显式把 symbol 解析到 ID |
| [管理 OrdersPage](../surprising-admin-web/src/App.tsx#L2432) | cancelBySymbol，提示“按 Symbol 撤单” | 实际读 filters.instrumentId 并调用 cancel-by-instrument；方法和文案应表达按合约 ID 撤单 |
| [SubscriptionTopic](surprising-gateway/src/main/java/com/surprising/websocket/api/model/SubscriptionTopic.java#L41) | withSymbol(String instrumentId)、normalizeSymbol | 对象字段和校验都是永久 instrumentId；改名为 withInstrumentId / normalizeInstrumentId，并同步调用方 |
| [TradingRuntimeState](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingRuntimeState.java#L156) | CoreLeverageKey 解构变量 symbol1 | 比较的是 instrumentId，不是交易对展示名称；用业务含义明确的变量名 |

- **影响：**这里的命名已经延伸到用户输入和请求含义，超过单纯风格问题。类名方面同时参见 D18：State 实际承担启动与派发。
- **建议与验收：**统一展示 symbol、永久 instrumentId、内部稠密 symbolId 的含义，不把它们机械全局替换成一种名称；检查界面标签、请求路径、日志、README 和测试。

### D24：纯转发方法与重复协议值增加跳转

- **位置：**[CoreMatchingFlow.refreshCommittedCoreSequence](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/order/CoreMatchingFlow.java#L119)、[TradingCoreRuntime.matchingPendingCode](surprising-aeron-core/surprising-aeron-service/src/main/java/com/surprising/aeron/service/core/TradingCoreRuntime.java#L3093)、[CoreResultCode](surprising-aeron-core/surprising-aeron-protocol/src/main/java/com/surprising/aeron/protocol/CoreResultCode.java#L60)。
- **证据：**前者唯一内部调用只转发到 owner 同名方法，不增加校验、事务、线程交接或转换；后者通过重复定义的 66 再调用 fromWireCode 得到已经存在的 `CoreResultCode.MATCHING_PENDING`。
- **影响：**简单意图需要额外导航，协议值多处维护。没有据此声称这些方法导致性能下降，JIT 可能内联。
- **建议与验收：**内联无职责转发，直接引用已有协议枚举；不删除真实 admission、commit、Owner/Lane 边界。验证等待收据、重复命令及终态恢复等价。

## 交互

### D25：登录输入完成后按 Enter 无提交反馈

- **位置：**[AuthPage](../surprising-ex-web/src/features/auth/AuthPage.tsx#L127)。
- **证据：**输入区域是 div，提交只绑定按钮点击，没有 form/onSubmit 或对应键盘处理。上一轮真实 Chrome 390×844 下输入账号、密码后按 Enter，登录请求数为 0；点击按钮则发送请求。本轮复核此结构未变。
- **影响：**常用键盘流程中断，用户难判断输入或页面是否生效。
- **建议与验收：**使用语义化表单，提交、加载禁用和错误反馈走同一入口；实际浏览器验证 Enter、点击、防重复提交、错误凭证及安全验证流程。

## 本轮验证与限制

| 验证 | 命令或方式 | 结果与边界 |
| --- | --- | --- |
| Java 环境 | java -version；mvn -version；df -h . | HotSpot Corretto JDK 27、Maven 3.9.16；检查时可用约 239 GiB |
| 后端既有测试 | mvn -q -pl surprising-gateway -am -Dtest=SubscriptionRegistryTest,ClientConnectionIsolationTest -Dsurefire.failIfNoSpecifiedTests=false test | 退出 0；目标测试 20 项通过，无失败/跳过；未跑全量后端测试 |
| 后端故障探针 | JDK 27 编译执行临时 RegistryProbe，使用上述构建 classpath | 既有订阅在鉴权桩撤销后仍送达；阻塞订阅回调时其他深度发布等待；使用假 socket，不是真实认证数据库或生产 Redis |
| 用户 Web | bun run lint；bun run test；bun run build | 均退出 0；44 文件、385 测试通过；build 含 TypeScript 检查；lint 另有 551 条 info，不是 551 个已确认缺陷 |
| 用户 Web HTTP / 状态探针 | 临时 Bun bundle + Node，本地 HTTP 服务直接调用当前 request、PrivateView、accountEquity | 503 清会话、半截 body 超时缺口、空快照 READY 均复现 |
| 管理 Web | npm run lint；npm run test；npm run build | 均退出 0；16 测试通过。lint 脚本实际仅执行 tsc，不能替代命名与重复设计审查 |
| 管理 Web 探针 | 直接调用 loadSession、request；运行当前调账 Number 转换表达式 | 坏会话消费 TypeError、503 清会话、整数舍入复现；未实际调账 |
| Client 静态检查 | flutter analyze --no-pub | 退出 0，No issues found |
| Client 数据 / HTTP 探针 | dart run 临时探针，本地 HttpServer | 非法数字、溢出、空快照 READY、body 等待超过 22 秒复现 |
| Client 状态探针 | flutter test --no-pub 临时 client-state-audit_test.dart --reporter expanded | 3 项诊断断言通过，确认 D04/D05/D11 当前缺陷；这些断言验证故障存在，不代表已修复 |
| 上一轮浏览器证据 | 生产构建、Chrome；390×844 登录，1440 宽度检查、脚本/存储故障注入 | 登录 Enter、动态加载失败与存储异常已观察；正常登录页两种宽度未发现水平溢出。截图与本轮临时资源不作为永久文件保留 |

结构定位先使用现有 CodeGraph CLI；当前会返回部分旧路径及错误源码片段，MCP 结构工具未出现在可用列表中。因此本报告的源码位置和引用以当前工作区文件为准，图的“未找到”没有作为死代码证据；死分支另外核对实际源码、字面引用和现有测试。

测试通过说明现有检查没有捕获全部异常路径，不能推断这些缺陷不存在。本轮没有修改生产代码，也没有新增永久测试文件。临时探针和测试日志已清理，本轮未启动常驻应用服务。

## 尚需验证的风险，不按确定缺陷计数

| 方向 | 已观察到的线索 | 下一步需要的证据 |
| --- | --- | --- |
| 首屏与大包 | 用户端 DeviceManagementPage 594.22 kB / gzip 189.76 kB；管理端主 JS 525.30 kB / gzip 146.20 kB，构建有 500 kB 提示 | 实际网络、解析执行、首屏和交互耗时；不能只凭包大小认定页面卡死 |
| 高频渲染与长稳 | D19 的根部通知；Web 资产计算多次遍历目录；后端每条成功 WebSocket 发送有 INFO 日志 | 记录行情频率、帧率、CPU、内存、日志 I/O、订阅数和持续运行趋势，定位后再改 |
| 热路径分配、装箱、扩容 | 存在 List<Long>、Object[]、集合和状态复制入口；是否进入验收交易热路径取决于场景 | 按 AGENTS-performance 量化 B/op、resize/rehash、尾延迟；不能以源码出现 new 就判整体零分配失败 |
| 缓存行与多线程 | 已有 padding、Owner/Lane、Matcher 线程交接 | 实际对象布局、共享写入、缓存行证据与同负载测试；不能说 padding 必定生效或多线程必定更快 |
| I/O 与内核切换 | D14 已确认外部等待传播，其他交易阶段尚未完整采样 | 区分 Owner、其他工作线程与发压端，计入系统调用、堆/堆外复制、I/O 和队列等待 |
| 视觉一致性 | 本轮没有逐页审阅所有产品、管理页面及 Flutter 设备布局 | 桌面/窄屏、深浅主题、长文本、字体放大、键盘、安全区的实际截图与点击；不凭代码颜色推断遮挡或配色错误 |
| 原生与真实业务 | 未启动 iOS/Android 设备或六产品完整环境，未做长时间弱网试验 | 真机/模拟器点击、切页、前后台、重连；下单到终态、资金、风控、强平、触发、撤单和恢复核对 |

## 建议处理顺序

1. 先处理 D01–D07 的金额、产品身份、鉴权和请求生命周期；同步修正受影响的后端、两个 Web 与 Client 契约和测试。
2. 处理 D08–D16、D25 的页面恢复、连接恢复、错误可观察性及异常清理。
3. 删除 D22 已核实的死分支，修正 D23/D24 的语义和转发；先做小范围等价简化。
4. 再沿实际业务重整 D17–D21，保留资金状态和 Owner/Lane 边界。任何性能结论单独按性能规范验收。

本次报告记录问题及验收方向，不代表上述缺陷已修复，也不替代完整业务与长期运行验收。
