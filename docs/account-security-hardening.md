# 账户安全设置与验证器恢复

## 数据库部署

升级已有环境前，先备份数据库，再执行 `deployment/migrations/20261006-account-security-hardening.sql`。新建环境使用 `init.sql`。迁移增加提现安全限制时间、恢复验证码用途和验证器恢复审核表。

## 验证器绑定和安全设置

`GET /api/v1/security/login-verification` 返回已绑定验证方式状态。`POST /api/v1/security/login-verification/{method}/bind` 发起绑定、启用、停用或更换挑战；`POST /api/v1/security/login-verification/{method}/confirm` 在密码及挑战因子全部通过后提交变更。首次绑定 TOTP 时，挑战响应临时包含密钥、`otpauth` URI 和二维码；确认后查询接口只返回绑定状态，不返回密钥。停用验证器会清空验证状态，下一次绑定生成新密钥。专用端点 `/api/v1/security/mfa/enroll`、`/mfa/confirm`、`/mfa/disable` 和 `/mfa/disable/confirm` 提供相同的 TOTP 操作。

邮箱、手机或验证器变更成功后，账户的 `withdrawal_restricted_until` 延后 24 小时。提现入口应通过 `ComplianceService.requireWithdrawalEligibility`；限制生效时返回 HTTP 423，并带可读的恢复时间。客户端状态接口也返回限制截止时间供页面提示。

## 丢失验证器恢复

用户必须先完成 KYC 并通过人工审核。流程为：

1. `POST /api/v1/security/mfa/recovery/challenge`：提交当前密码，网关向已绑定且有效的邮箱或手机号发送 5 分钟验证码。
2. `POST /api/v1/security/mfa/recovery`：提交挑战 ID、密码、验证码和恢复原因，创建待审核请求。原验证器在审核通过前保持启用。
3. `GET /api/v1/security/mfa/recovery`：查询申请状态；Web 和客户端在待审期间自动刷新。
4. 后台合规人员使用 `GET /api/v1/admin/compliance/mfa-recovery` 查看待审队列，并通过 `POST /api/v1/admin/compliance/mfa-recovery/{requestId}/decision` 审核。

审核通过会移除旧验证器并启动 24 小时提现限制；拒绝不会改变原 MFA 状态。审核决定会写入管理审计记录，并尝试邮件通知用户。邮件暂时不可用不会回滚审核决定。

## Sumsub / Veriff KYC

网关通过 `GATEWAY_KYC_PROVIDER` 在 `SUMSUB`、`VERIFF`、`SELF` 间切换，provider 由服务端决定，客户端不能自行指定。开发环境默认 `SUMSUB` + `GATEWAY_KYC_SIMULATION_ENABLED=true`；模拟流程不发第三方请求，提交后客户端可以模拟自动通过、拒绝或转人工复核。生产配置固定关闭模拟，启动校验会拒绝模拟模式或当前 Provider 缺少密钥的配置。

Sumsub 配置 `GATEWAY_KYC_SUMSUB_APP_TOKEN`、`GATEWAY_KYC_SUMSUB_SECRET_KEY`、`GATEWAY_KYC_SUMSUB_WEBHOOK_SECRET`、`GATEWAY_KYC_SUMSUB_LEVEL_NAME` 和可选 `GATEWAY_KYC_SUMSUB_BASE_URL`。API 密钥用于创建 Applicant/短效 SDK access token；Webhook Secret 用于验签。Sumsub 控制台的 webhook URL 配为 `https://<gateway-host>/api/v1/compliance/kyc/webhooks/SUMSUB`，通知至少启用审核完成事件。

Veriff 配置 `GATEWAY_KYC_VERIFF_API_KEY`、`GATEWAY_KYC_VERIFF_SHARED_SECRET`、可选 `GATEWAY_KYC_VERIFF_BASE_URL` 和 `GATEWAY_KYC_VERIFF_CALLBACK_URL`。控制台 webhook URL 配为 `https://<gateway-host>/api/v1/compliance/kyc/webhooks/VERIFF`。API key 创建会话；Shared Secret 验证 `X-HMAC-SIGNATURE`。

Web 与 Flutter 在第三方模式不要求先把证件/人脸上传到本平台：Sumsub SDK token 或 Veriff hosted session 负责采集，内部材料上传入口保留给升级人工复核。Provider 的 `VERIFIED` / `REJECTED` 结果只有通过验签、当前用户和当前会话引用匹配、并通过幂等事件检查后才更新 KYC；`onHold`、`review`、重传等状态继续为 `PENDING`。状态变化会触发现有邮件通知和客户端轮询。运营后台可对待复核档案进行人工处理；`SELF` 是 Provider 全局切换的紧急人工通道。

Webhook 更新表 `gateway_kyc_provider_events` 通过 `deployment/migrations/20261007-kyc-provider-events.sql` 创建，新环境使用 `init.sql`。生产接入仍需在两家平台控制台配置核验等级、允许的国家/证件类型、SDK 域名/应用和审核 webhook；切换环境时 Sumsub sandbox 与 production 密钥、Veriff endpoint 与 webhook secret 必须成套使用。

手机号验证码依赖运行环境中的 `SmsMessageSender` Bean；若未配置，手机号恢复挑战会明确失败，邮箱渠道仍可用。
