# 钱包 / custody 提现闭环验证（2026-10-30）

本记录对应本轮修复；[10 月 29 日审计](../wallet-custody-audit-20261029/README.md) 是修复前的历史快照。本地资金、契约、并发与界面验证结果如下。上述旧审计阻断项已按下述范围完成修复与验证；生产历史对账和全链实测边界单列说明。

## 金额与资金来源

用户确认采用租户本金池：Core SPOT 可用余额授权实际用户；custody 按租户 / 链 / 资产出款，不要求提现用户持有原充值地址余额。其他产品余额须先转入 SPOT，产品线之间仍隔离。新交易所意图固定 TENANT_POOL、custodyAddressId=null、subject=user:<认证用户>，控制台 ADDRESS 出款独立填写提现用户。

以后台费用 2.5 USDT、申请 25 USDT 为例：Core 扣 25，custody 冻结并发送 22.5；确认后唯一登记平台收入 2.5。可信永久拒绝或已释放本金的明确失败退回 25，平台收入为零。实际链费由独立 Gas 余额与用量核算，资产可以是 ETH，不从用户净到账再次扣除。Native 界面夹具另用 0.25 / 24.75 报价验证精度与展示。

## 原审计闭环

| 问题 | 修复与证据 |
| --- | --- |
| A01 后续 HTTP 拒绝错误退款 | CREATE / RESOLVE 竞争同一永久键；原受理先重放；缺失旧回执但已有业务订单时禁止签发拒绝。只有完整身份绑定的永久拒绝或已释放本金终态可退款。PG 创建 / 拒绝并发与回滚、Gateway 拒绝分类和退款竞争用例通过。 |
| A02 平台费未执行 | 服务端报价、配置版本与锁、不可变费用 / 净额快照、确认后唯一平台收入、失败退总额；真实 PG 管理配置费用变更和锁验证，25 / 2.5 / 22.5 完整流程用例通过。 |
| A03 网络开关 / 最低额仅在界面限制 | 首次业务创建强制校验币种上架、网络、最低额、整数单位与链精度；创建事务重新锁定报价。已有意图先按原身份恢复。 |
| A04 实时估值改变幂等身份 | 指纹只包含稳定业务意图；估值保留首次快照。新出款采用租户本金池，删除按用户充值地址选择来源的路径。 |
| A05 历史协议不完整 | custody_withdrawal 独立 subject，精确用户与 externalReference 在 SQL 分页前过滤；Gateway 合并本地报价 / 状态与链上 Gas / 哈希，金额保留十进制字符串。不同用户与前缀相似用户验证通过。 |
| A06 长期 / 未知恢复不完整 | 永久受理凭据；租约覆盖 PROCESSING、DEBIT_UNKNOWN、DEBITED、BROADCAST_UNKNOWN、SUBMITTED、FAILED_PENDING、REFUND_PENDING。EVM nonce/raw/hash 与业务绑定先提交，再广播；只重复同一字节。规范失败回执原子释放本金，并独立结算真实 Gas。 |
| 新增：盈利用户无法提现 | 多个非 Gas 本金账户按所需差额借记到唯一租户池；来源借方、池贷方流水、冻结、订单、Gas、回执一并提交或回滚。无本人充值地址用户、多账户出资、Gas 排除、并发、回滚、释放后复用、独立历史归属均通过。资产看板包含虚拟池。 |
| 新增：UTXO 已释放但无退款凭据 | 未签名失败同事务写 funds_released；已签名 / 未知广播不释放；缺失 debitAccountId 拒绝猜测 userId 账户。实际 PG 与双签流水线验证通过，链 RPC 为隔离模拟。 |
| 新增：控制台缺幂等 / 点击竞态 | 每租户 / 操作员持久原单、Web Locks、原单查询 / 重试 / 永久拒绝；非法标识和不支持精度在保存前拒绝。生产构建实际按住 Retry，另一标签页受理后松开，旧按钮节点会误触 Start another 清除原单；不同动作使用独立 key 后，同一回归通过。见 [修复前](logs/console-race-red.log)、[修复后](logs/console-final-browser.log)。 |

R01 历史隔离与 R02 回调十进制精度回归保留。Web/App 的总扣款、平台费、净额、原请求恢复、只读查询、完整哈希与地址、生命周期和用户归属已同步。安全验证码不保存在本地。

## 验证环境与结果

HotSpot Corretto JDK 27 / Maven 3.9.16；已有本机 PostgreSQL 18，127.0.0.1:5432；隔离测试数据库。没有 Docker、Testcontainers、独立数据库进程或生产资金。Hardhat 为本机隔离链 11155111，RPC 18545；测试签名密钥在内存临时生成，不归档节点私钥日志。

| 范围 | 结果 | 记录 |
| --- | --- | --- |
| Gateway | 100 项通过，0 失败 / 错误 / 跳过；含真实 PG 35 项、费用配置 PG 3 项 | exchange-pool-suite-3、gateway-webhook-tests、gateway-funding-tests |
| Core | 29 项通过；近期缓存淘汰、快照恢复后同引用不重复入账 | core-withdrawal-recovery |
| custody | 181 项唯一用例通过；147 项主回归 + schema 2 项 + security/nonce/recovery 32 项；EVM 7 项另以临时签名密钥重跑通过 | wallet-pool-suite-2、wallet-signer-schema-tests、wallet-security-recovery-tests |
| Web | 556 项、typecheck、build、改动 8 文件 lint 通过；浏览器 13 场景 + 60 秒 | web-final-* |
| Admin | 147 项、lint、build；浏览器 7 场景 + 60 秒 | admin-* |
| Console | 45 项、typecheck/build 通过；生产构建真实按压 / 跨标签页回归，1 次支付 + 1 次永久拒绝 | console-verified-*、console-final-browser |
| App | 65 项相关测试、analyze 无问题；iOS 72 秒 / Android 71 秒，分别 20 次生命周期切换通过 | client-final-*、native-*-final |
| 数据库 | 全新 root init；两个旧基线 → 带历史资金行 → 升级 → 断言均通过 | gateway-seeded-upgrade、custody-seeded-upgrade |

Web 全量 lint **未通过**：HEAD 独立源码副本为 90 项错误 / 699 条 info，当前为 81 项错误 / 704 条 info；本轮改动文件检查为零错误。保留其他任务的依赖 / 安全工作，没有批量修改无关 Hook。详见全量与基线日志；不得将本记录解读为全项目 lint 已清零。

设备交互覆盖：报价确认、受理回应丢失、重新进入且规则 / 余额不可用、状态查询失败、按原键恢复、深色与放大文字、20 次生命周期切换、费用与完整交易哈希。浏览器 / 原生端使用明确隔离的 HTTP 夹具；真实数据库与本地 EVM 付款故障注入另行验证，并非已部署的 Core + Gateway + custody + 全链一体生产演练。Native 脚本用 MemorySettings 保存序列化意图并销毁 / 重建 AppState；没有把该步骤冒称为操作系统杀进程或平台存储故障演练。

升级夹具验证旧金额 / 冻结 / SENT 或 BROADCAST_UNKNOWN / 原引用和原受理哈希不变，旧记录平台费为零、资金来源为 ADDRESS；七天旧受理转换为永久凭据。没有凭空产生收入、退款或“可安全换 nonce”标记。夹具限制数据库名必须是隔离的 test_upgrade_日期，见 fixtures/。

## 复现命令

下面连接名均为本轮独占测试库，先从对应 init 基线创建；命令不能指向开发 / 生产库。日志记录最终成功执行，部分首次失败的旧夹具、环境初始化与点击竞态另有说明。

```sh
# Gateway：完整编译后执行 83 项
SURPRISING_WITHDRAWAL_IT_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/surprising_exchange_test_wallet_20261030 \
SURPRISING_WITHDRAWAL_IT_DATABASE_USER=atomex \
SURPRISING_ASSET_IT_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/surprising_exchange_test_wallet_init_20261030 \
mvn -pl surprising-gateway -am test \
  -Dtest=CustodyWithdrawalServiceTest,CustodyWithdrawalReconciliationPostgresTest,CustodySubmissionReceiptTest,CustodyWalletClientTest,CustodyWalletControllerTest,BinanceApiControllerTest,SpotAccountClientTest,WithdrawalQuoteTest,WithdrawalQuotePostgresTest \
  -Dsurefire.failIfNoSpecifiedTests=false
# 另外执行 CustodyWalletWebhookServiceTest（9）和 WalletFundingServiceTest（8）
mvn -pl surprising-aeron-core/surprising-aeron-service -am test \
  -Dtest=AsyncAccountBalanceCommandTest,CoreFundsIdempotencyTest -Dsurefire.failIfNoSpecifiedTests=false

# custody：完整编译的 147 项（本机 Hardhat RPC 必须先就绪）
SW_TEST_CUSTODY_DB_URL=jdbc:postgresql://127.0.0.1:5432/surprising_wallet_test_closure_20261030 \
mvn -pl wallet-api -am test \
  -Dtest=CustodyWithdrawalCreationIntegrationTest,CustodyControlPlaneQueriesIntegrationTest,CustodyGasAccountingIntegrationTest,WithdrawalSettlementIntegrationTest,CustodyWithdrawalProjectionIntegrationTest,CustodyDepositProjectionIntegrationTest,CustodyTenantIsolationIntegrationTest,CustodyAssetRecoveryIntegrationTest,EvmSubmissionIntegrationTest,UtxoSigningPipelineIntegrationTest,UtxoSigningSubmissionIntegrationTest,AccountChainWorkflowServiceTest,WalletRpcAuthenticationTest,ArchitectureBoundaryTest,CustodyMoneyHttpTest,WalletConfigManagementContractTest,Evm7702WithdrawalAuthorizationTest,CustodyWithdrawalAddressValidationTest,TransactionQueueSafetyTest \
  -Dsw.test.evm.localRpc=http://127.0.0.1:18545 -Dsurefire.failIfNoSpecifiedTests=false
# 另外执行 CustodySchemaContractTest（2）及四个安全 / nonce / 恢复类（32）。
# 安全/nonce/恢复32项与Gateway目录8项复用刚完成完整编译且未修改的 main。
# EVM/Schema9项执行完整compile；所有复用在日志明确记录 maven.main.skip。

# Web
pnpm lint
pnpm typecheck
pnpm exec vitest run --maxWorkers=1
pnpm build
# Console
pnpm exec vitest run --pool=forks --maxWorkers=1
pnpm build
# Admin
pnpm run lint
pnpm test
pnpm build
# App
flutter analyze
flutter test test/withdrawal_security_test.dart test/wallet_state_test.dart test/widget_test.dart test/funding_metadata_test.dart
python3 integration_test/wallet_closure_host.py --device <本轮专用模拟器> --platform ios --output <隔离证据目录>
# Android 同一脚本使用 --platform android；仅此命令以 JBR17 运行，不更改全局 JDK：
JAVA_HOME=/Users/atomex/.sdkman/candidates/java/17.0.14-jbr \
GRADLE_OPTS=-Dorg.gradle.java.home=/Users/atomex/.sdkman/candidates/java/17.0.14-jbr \
ANDROID_SDK_ROOT=/Users/atomex/Library/Android/sdk \
python3 integration_test/wallet_closure_host.py --device emulator-5582 --platform android --output <隔离证据目录>
```

## 边界与发布

实际 EVM 测试接收签名交易后丢弃 RPC 回应，恢复重播相同 raw/hash/nonce，收款地址只增加一笔 1 ETH；规范失败与孤块失败分支分别验证本金释放、Gas 与不误退款。没有执行其他公链主网 / 测试网实链验收、真实硬件签名或生产网络断电演练，不能据此承诺所有链和任意故障都绝不重复出款。

已有历史异常提现须核对真实链上结果及原 Core 引用；迁移不猜测退款，不修改已入账历史事件或追加收费。部署顺序、暂停新单、迁移、回滚限制及历史核对见 [协议说明](../../wallet-custody-closure.md)。未执行生产数据库迁移、钱包服务发布或真实资金操作；未改默认不启动 wallet 的约定。

首次扩大回归的地址校验夹具没有设置新网络字段，修正后 147 项全组重跑通过；网络身份保护对 family 大小写误判的实现也已修正。原生 Android 初次冷启动出现系统 UI ANR，取消该轮并等待自有 AVD 温启动就绪后通过，未通过的启动不算验收。测试时间与内存记录只代表本次短流程观察，不作生产吞吐或无泄漏承诺。

控制台测试隔离清理 API 缓存，惰性路由查询等待 15 秒；多轮模态 / Select 的 Webhook 用例使用独立 180 秒测试渲染预算。生产资金请求仍采用原有超时，未因测试调整而放宽。

所有成功日志在 [logs](logs/)，逐类统计见 [结构化摘要](verification-summary.json)，代码摘要见 [源码哈希](source-hashes.json)。关键截图：[Web 报价](surfaces/web/withdraw-review-mobile-light.png)、[后台审批](surfaces/admin/platform-withdrawal-approval-mobile-light.png)、[Console 原单](surfaces/console/console-original-mobile.png)、[iOS 明细](surfaces/ios/native-wallet-history.png)、[Android 明细](surfaces/android/native-wallet-history.png)。

本轮独占测试库、4 个测试服务、专用 iOS 克隆、两个自建 Android AVD 与私有临时目录均已清理；原有 AVD 与其他任务改动保留。见 [清理记录](cleanup.json) 与 [关联组件提交](component-commits.json)。
