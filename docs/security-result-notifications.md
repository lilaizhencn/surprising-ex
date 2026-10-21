# KYC 和 MFA 结果通知

KYC 审核和 MFA 恢复裁决先提交业务状态及邮件通知意图，再由后台任务投递邮件。邮件服务不可用时保留重试状态；审计或通知意图无法落库时，业务事务回滚。

## 业务入口和职责

- `gateway/provider/auth/SupportTicketService` 的管理员创建工单与修改状态入口负责工单、备注的共同事务。
- `gateway/provider/auth/ComplianceService.adminKycDocument` 在成功记录必需审计后才返回原件；读取失败和审计同时失败时保留原错误及附加异常。
- `gateway/provider/auth/MfaRecoveryService.decide` 聚合裁决、MFA 变更、提现安全等待期、必需审计和结果通知意图。Controller 只传入已认证管理员和请求元数据。
- `gateway/provider/auth/ComplianceService` 的管理员审核和供应商回调在同一事务保存 KYC 状态与通知意图。回调失败时去重事件也回滚，原事件可以重试。
- `gateway/provider/service/SecurityNotificationService` 生成结果通知和执行投递；`gateway/provider/repository/SecurityEmailNotificationRepository` 只访问 `gateway_security_email_notifications` 一张表。
- `gateway/provider/task/SecurityNotificationTask` 只负责定时触发，默认每 5 秒一次，可用 `surprising.gateway.security-notification-delay-ms` 调整。

以上路径均位于 `surprising-gateway/src/main/java/com/surprising/`。认证挑战使用的短效验证码发送流程保持原契约；通知表不保存验证码、密码或 KYC 原件。

## 通知意图的状态和生命周期

原有站内通知表的“已读”状态不表示外部邮件已送达，instrument outbox 则承载 Kafka 合约事件；两者都不承担安全邮件投递。新增表保存尚待投递的邮件意图，数据库是该状态的唯一来源，没有本地队列或第二份业务状态。

1. 业务事务内生成固定事件标识。MFA 使用恢复请求 ID；KYC 使用用户、已提交版本时间及结果。相同标识只允许相同收件人、标题和内容，重试不会覆写原意图。
2. 任务每轮最多处理 16 条，逐条获取 60 秒租约。获取租约的 SQL 独立提交后才调用邮件服务，避免一批邮件排队期间提前耗尽后续租约。
3. 投递入口使用 `NOT_SUPPORTED` 事务传播，暂停调用方可能持有的业务事务；因此读不到该事务尚未提交的通知意图，邮件调用也不占用它的数据库连接。
4. 成功标为 `SENT`；失败回到 `PENDING`，从 5 秒开始指数退避，最长 1 小时。只持久化异常类型，日志不记录收件人、邮件正文或提供商凭据。
5. 进程退出留下的 `SENDING` 意图在租约过期后可重新领取。完成和失败回写均核对租约 ID，旧执行者不能覆盖新租约。
6. 每轮最多清理 128 条已成功且超过 30 天的通知；未发送和重试中的意图持续保留。

外部提供商接收邮件后若进程在确认落库前退出，恢复后可能重复投递。这条链路提供至少一次投递；通知重复不会再次执行 KYC、MFA 或账户资金操作。

## 数据库升级

已有数据库在启动新 Gateway 前执行：

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 -f deployment/migrations/20261021-security-notification-outbox.sql
```

新建环境的 `init.sql` 已包含相同建表及索引。迁移只新增安全邮件意图表，不写业务余额、持仓或 Core 状态。

## 验证入口

`SupportTicketTransactionPostgresTest` 通过真实 Spring 代理注入第二次写入失败，验证工单与备注共同回滚。`MfaRecoveryDatabaseTest` 分别注入审计失败、通知意图失败，核对裁决、MFA、等待期及审计共同回滚。

`KycNotificationTransactionPostgresTest` 验证管理员审核和回调失败回滚、原供应商事件可重试，以及提交后由独立投递操作发送。`SecurityNotificationPostgresTest` 覆盖业务事务不可见、幂等、失败后新实例重试、过期租约恢复、旧执行者围栏及并发领取。

测试使用本轮独立 PostgreSQL 与模拟邮件发送器，不向真实收件人发送邮件。阶段测试命令、结果和清理状态见 [整改进度](java-mvc-remediation-20261021.md)。
