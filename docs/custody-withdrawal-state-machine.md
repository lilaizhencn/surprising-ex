# 提现状态机与资金安全边界

网关在调用 custody wallet 之前先创建本地提现意图，并使用同一个幂等键贯穿本地记录、现货账本扣款和 custody wallet 请求。现货账户是用户可提现余额的唯一资金来源，合约等其他产品线仍通过既有划转接口进入现货账户。

## 状态

| 状态 | 含义 | 是否自动解冻 |
| --- | --- | --- |
| `PENDING_APPROVAL` | 折算 USDT 后达到单笔审批阈值，等待后台放行 | 否 |
| `PROCESSING` | 已创建意图，正在执行现货扣款 | 否 |
| `DEBIT_UNKNOWN` | 现货扣款请求结果未知，使用原引用号重试 | 否 |
| `DEBITED` | 现货扣款已被确认 | 否 |
| `SUBMITTED` | custody wallet 已接受提现 | 否 |
| `BROADCAST_UNKNOWN` | custody wallet 广播结果未知 | 否 |
| `COMPLETED` | 收到 `WITHDRAWAL.CONFIRMED` | 否 |
| `REJECTED` | 扣款前或后台拒绝 | 否 |
| `REFUND_PENDING` | 失败后的现货退款结果未知 | 否 |
| `REFUNDED` | 失败提现已自动退回现货账户 | 是 |

`BROADCAST_UNKNOWN`、`DEBIT_UNKNOWN` 和 `REFUND_PENDING` 不会因为请求超时而退款。只有收到明确的 custody wallet 失败事件，或明确的 custody wallet 拒绝响应后退款成功，才会进入 `REFUNDED`。

## 用户接口

- `POST /api/v1/wallet/withdrawals`
- `POST /sapi/v1/capital/withdraw/apply`
- `GET /api/v1/wallet/withdrawals`
- `GET /sapi/v1/capital/withdraw/history`

两条提现创建入口共享同一状态机、幂等规则、USDT 估值、单日额度和 KYC/安全校验。重复提交相同幂等键会返回已有本地意图；同一幂等键提交不同内容会被拒绝。

## 后台接口

- `GET /api/v1/admin/wallet/withdrawals`，权限 `admin.wallet.read`
- `POST /api/v1/admin/wallet/withdrawals/{withdrawalId}/approve`，权限 `admin.wallet.write`
- `POST /api/v1/admin/wallet/withdrawals/{withdrawalId}/reject`，权限 `admin.wallet.write`
- `POST /api/v1/admin/wallet/withdrawals/{withdrawalId}/retry`，权限 `admin.wallet.write`

后台动作会保存管理员身份、理由和状态变更时间；网关现有后台操作审计链路继续记录 HTTP 操作。

## 生产配置

- `GATEWAY_WITHDRAWAL_SINGLE_APPROVAL_THRESHOLD_USDT`
- `GATEWAY_WITHDRAWAL_DAILY_LIMIT_USDT`
- `GATEWAY_WITHDRAWAL_VALUATION_BASE_URL`
- `GATEWAY_WITHDRAWAL_VALUATION_MAX_AGE`

USDT 估值必须来自价格服务且不能超过最大有效期。数据库表 `gateway_wallet_withdrawals` 记录请求哈希、金额最小单位、USDT 估值、账本引用、wallet 引用、状态、错误和后台操作人。
