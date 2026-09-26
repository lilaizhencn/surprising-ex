# 2026-09-26 盘口增量与固定布局验证

## 改动与入口

- `SubscriptionRegistry.publishDepth` 为每个连接保存最后入队的不可变盘口引用；首次发送 SNAPSHOT，后续由 `DepthUpdate.between` 生成绝对数量增量，零数量删除。退订/断线清除基线，背压断开连接。Core 内部盘口传输未改。
- 前端 `TradePage.OrderBook` 在未收到快照及空快照时都保留两侧表头、中间成交价与固定容器；移除 unavailable 提示。Recent trades 保留滚动但隐藏滚动条。
- 前端逐条应用 depth 增量；正常不轮询 REST，全量查询仅用于序号断档恢复。
- `MarketMakerService.reconcile` 按当前挂单数 10%（1–20 单）撤单并立即补单，再进入下一批；不限制一轮总订单操作数。撤单不确定或补单拒绝时停止进一步撤单。风险缩减目标时仍可撤掉不允许的方向。

## 已验证

- HotSpot Corretto JDK 27、Maven 3.9.16。
- maker：MarketMakerServiceTest、QuotePlannerTest，共 31 项通过；包括换完整梯子过程中剩余挂单数、撤单失败和补单失败。
- gateway：DepthUpdate、SubscriptionRegistry、ClientConnectionIsolation、ClientWebSocketHandler、KafkaFanout、SubscriptionTopic、WebSocket 配置等，共 42 项通过；覆盖首条快照、增删改、独立订阅基线、乱序拒绝和产品隔离。
- 前端 13 个测试文件、49 项通过；lint、build 通过。空盘口渲染测试及 previousSequence=null 解析测试通过。
- 实际 WS 20 秒采样：1 条快照、50 条增量，序号断档 0、交叉盘口 0，买 49 档/卖 50 档。该窗口中做市未恢复，因此没有发生档位删除；删除正确性由单元测试验证。
- Chrome 1440×900：30 次采样盘口区域坐标与尺寸完全一致，99 档显示，正常运行 REST orderbook 请求 0；无 JS 异常。空盘口时也实测区域稳定、两侧表头保留且无 unavailable 文案。
- Recent trades 的 scrollbar-width=none、overflow-y=auto；390×844 移动端 scrollWidth=390，没有横向溢出。

## 环境与未完成范围

运行环境为 local-final4-20260926。联调发现 Core 连接异常：网关查询返回 NOT_CONNECTED，独立只读 ClusterProbe 在 AWAIT_PUBLICATION_CONNECTED 超时；Core 重启恢复为 LEADER 后该问题仍存在，20 个 maker 策略处于 DEGRADED。已重启网关、maker、Core 与 Realtime，未清空数据库、Archive 或账户状态。WS 首条快照及空增量已实测，但高频真实换单、资金守恒及用户成交回归尚未通过本轮运行验证，不能据此宣称全链路健康。

没有更改资金计算、持仓和撮合逻辑；本轮没有运行 wallet，也没有重跑六产品线全量测试。运行采样仅覆盖 U 本位永续；其他产品覆盖共享 WS 单元测试，未做运行验证。

## 清理与交付

检查磁盘剩余约 446 GiB；临时采样程序均已退出，没有新建集群。为保留用户要求的页面与服务，六服务及前端继续保留。本轮没有删除运行中的 Archive、日志或已有环境。测试构建产物未加入 Git；保留运行脚本供后续连接排查。前端提交 957e4cd，后端功能提交 7b9bfba7、fe5bf5d1，均已推送。
