# 独立条件单的原请求恢复

期权 Web 和 client 在发送单笔条件单前持久保存账户、OPTION 产品、instrumentId 和
clientTriggerOrderId；撤销保存原 triggerOrderId。保存失败不发送。已有未知请求时，不能再发送新委托。
正常回包必须匹配原身份和状态；断网、超时、不完整响应不能当作拒绝或成功。

查询入口为 `GET /api/v1/gateway/trading-trigger/submission?userId=...&clientTriggerOrderId=...`
和 `GET /api/v1/gateway/trading-trigger/cancellations/{triggerOrderId}?userId=...`。
沿用 `TradingLocalRoutes` → `TriggerOrderRequestService` → `OrderService.commandResult`，
只读 realtime 已提交回执。`submissionCommandId` 与原下单共用产品、账户和归一化 client 身份；
撤销与 `TriggerOrderAeronGateway.cancellationCommandId` 共用原有撤销键，未改变既有 commandId。
网关绑定认证账户，不能用 query 参数查询另一账户。空身份、超过 64 字符的 client 身份或非正撤销 ID 返回 400。

`RESULT_UNKNOWN` 保留原请求，终态 `NONE` 表示命令成功，其余终态表示命令拒绝；它们不等同于最终成交。
账户/产品切换和进程重建后继续 GET 原结果，不重新 POST，不用在线 Core 查询补齐。
数据库或读视图故障保留待确认状态。沿用既有回执投影及 exporter，本轮没有 SQL、Core 协议或资金模型变化。
发布需配套 gateway 和两端，并确保此前回执迁移、realtime 导出和必要历史补齐已经就绪。

client 的独立档位按顺序分别提交；某档结果不明后停止剩余档位，该档转入持久恢复，不留在新请求草稿中。
已确认档位和已发送待确认档位移出表单，尚未发送的档位保留；查询确认后，用户可决定是否提交剩余档位。
初始保存和确认结果保存分开处理，后者失败仍保留原请求。界面在忙碌/待确认时禁用条件单撤销。

本轮升级限 OPTION；其他产品的独立条件单、批量条件单撤销和整体功能仍需按产品继续验收。
已提交的 OCO 双腿恢复保持原入口 `oco-submission`。
实测范围、恢复结果和环境清理见 `docs/validation/option-single-trigger-20261020.json`。
