# 生产级交易系统独立安全测试方案

## 1. 目标与独立边界

安全测试是独立于性能测试和业务回归测试的模块。它验证外部攻击面、认证授权、消息边界、敏感信息、资源耗尽、并发竞态和故障下的安全不变量；不把“接口返回 200”或“压测没有报错”当作安全通过。

安全测试默认针对未上线测试环境或隔离的云测试环境。任何主动扫描、漏洞利用、fuzz、资源耗尽和并发攻击都必须得到书面授权，并且目标必须在白名单中。生产域名、未知公网地址、第三方服务、钱包服务和不属于本轮产品线的服务一律禁止作为目标。

本模块不修改交易事实、不注入真实资金、不绕过账户或产品线边界。每次只选择一条产品线，测试账户使用可回收的测试资金；所有主动测试结束后必须执行账户、订单、持仓、冻结、手续费、保险基金和 outbox 清理及对账。

## 2. 安全档位与执行门禁

安全套件使用与性能脚本相同的 `TEST_PROFILE`：

| 档位 | 默认行为 | 允许范围 |
|---|---|---|
| `local-low` | 被动检查、单接口少量认证/授权用例、低并发竞态计划 | 不运行主动网络扫描和资源耗尽 |
| `local-standard` | 测试环境 API/WS 被动验证、低速授权 fuzz、有限并发安全用例 | 仅 allowlist 内的本机/隔离环境 |
| `cloud-capacity` | 隔离云测试环境的授权 DAST、HTTP fuzz、连接/并发矩阵 | 需明确目标、时间窗和限速 |
| `cloud-production` | 只允许经过变更审批的低风险 canary 检查 | 禁止破坏性攻击、禁止压力型 DoS |

默认 `SECURITY_EXECUTE=false`，只生成计划和 manifest。主动模式必须同时满足：

```text
SECURITY_EXECUTE=true
SECURITY_AUTHORIZED=true
SECURITY_TARGET_ALLOWLIST=https://test-gateway.example.com
SECURITY_TARGET=<allowlist 中的完整 URL>
```

脚本还必须校验：目标 URL 使用 `http`/`https`，不能包含换行、shell 元字符或通配符；`SECURITY_TARGET` 必须与 allowlist 的 origin 完全匹配；`cloud-production` 只能运行被动和 canary 场景；并发、fuzz、扫描和资源耗尽用例要逐项显式开启。任何一个条件不满足都必须在攻击开始前以非零状态退出。

授权记录至少包含：授权人、工单/变更号、目标 origin、允许的测试类型、开始/结束时间、速率上限、联系人、停止条件、数据清理方式和回滚方式。授权记录写入 manifest，但不得写入密码、JWT、API key 或个人敏感信息。

## 3. 威胁模型与攻击面

### 3.1 外部攻击面

- Gateway HTTP/HTTPS 路由、健康检查误暴露、Actuator、Swagger、错误页和调试端点。
- 登录、JWT/会话、用户标识 header、query fallback、刷新/注销、密码重置、邮箱验证和重放。
- 下单、撤单、改单、批量订单、查询、转账、余额、持仓、触发单、管理员和内部 RPC 误暴露。
- WebSocket 握手、Origin、匿名连接、公共/私有订阅、跨用户订阅、订阅数量、出站队列和重连。
- Kafka bootstrap、Topic、consumer group、DLT、管理端口、Schema/JSON 边界和错误消息回显。
- 配置、日志、Actuator、JFR、heap dump、线程 dump、环境变量、容器元数据和源码路径泄露。

### 3.2 交易安全不变量

对四条产品线分别验证，不允许跨线复用订单、账户、Topic、instrument 或风险模型：

1. 用户只能读取和修改自己的订单、余额、持仓和 WebSocket 私有频道。
2. 非管理员不能调用管理员余额调整、内部触发、结算、强平、保险基金或运维接口。
3. `X-User-Id`、query user id、JWT subject 和请求主体必须一致；不一致时拒绝，不能以后到字段覆盖前到字段。
4. `clientOrderId` 幂等键、请求 nonce、事件 id 和结算批次不能被重放造成重复冻结、成交、手续费、资金费、强平、行权或交割。
5. 任何拒绝、超时、重试、取消、重平衡、重启和重复消息都不能造成资金增加、资金丢失、负冻结、负持仓或越权状态。
6. 订单与事件必须使用正确的 product line、instrument、symbol key、Topic 和 consumer group；伪造 key 或跨线消息必须被拒绝并可审计。
7. 敏感数据不出现在响应、错误、日志、Topic、指标标签、trace、heap dump 或测试报告中。

## 4. 测试层次和用例

### 4.1 被动配置和供应链检查

- 检查外部暴露端口、监听地址、TLS 版本/证书、CORS、Cookie 属性、HSTS、CSRF 和安全响应头。
- 检查 Actuator 只暴露允许的 health/info/metrics/prometheus，禁止 env、configprops、beans、heapdump、threaddump 等敏感端点公开。
- 检查默认密钥、默认密码、测试 token、JWT secret、Kafka/数据库凭据、私钥和生产域名不进入仓库或构建产物。
- 检查依赖漏洞、容器基础镜像、JDK、Kafka client、Netty/Spring 版本，并按 CVSS、可利用性和交易影响分级。
- 检查日志脱敏、异常堆栈、trace id、用户 id、订单 id 和 IP 的留存策略；凭据必须不可逆或完全不记录。
- 检查 Topic ACL、Kafka TLS/SASL、consumer group 权限、DLT 写权限、管理端口隔离和 producer/consumer client id。

### 4.2 认证与授权

每个产品线至少执行以下正反用例：

| 用例 | 预期结果 |
|---|---|
| 无 token 访问用户接口 | 401/403，不产生交易事实 |
| 过期、错误签名、错误 issuer、错误 audience、算法降级 token | 401/403 |
| 用户 A 携带用户 B 的路径、query、header、JWT subject | 403/400，不能读写 B |
| 普通用户访问 admin、内部、结算、强平和保险基金接口 | 403，审计记录完整 |
| 缺少或不一致的 `X-Product-Line`/account type/instrument | 400/403，不能回退到其他产品线 |
| 重放同一 token、nonce、验证码、密码重置和行权请求 | 幂等拒绝或返回同一安全结果 |
| 超过登录、验证码、密码重置和下单限速 | 429/安全策略结果，服务可用 |

### 4.3 HTTP 输入和资源耗尽

只在授权目标上逐步增加风险：请求行、header、JSON 深度、数组长度、字符串长度、非法数值、超大 decimal、NaN/Infinity、负数、整数溢出、Unicode、重复字段、未知字段和压缩包炸弹防护。

验证边界：返回 4xx/413/429；CPU、堆、线程、连接和 Kafka lag 在停止攻击后回到基线；无 OOM、Full GC、线程泄漏、请求队列无界增长、错误堆栈泄露或交易事实产生。云生产 canary 只允许极小样本和低速率，不执行 DoS。

### 4.4 WebSocket 安全

- 无 token、错误 token、错误 Origin、伪造 user id、跨用户订阅和超过最大订阅数均拒绝。
- 公共频道只能看到公共事件；私有频道只能看到当前用户的订单、成交、余额、持仓和风险事件。
- 订阅、取消订阅、重复订阅、快速重连、ping/pong、慢消费者和发送队列满时不能跨用户泄露或阻塞其他用户。
- product line、symbol 和 account type 必须隔离；关闭连接后 token、订阅和缓存状态必须释放。

### 4.5 Kafka、Topic 和事件安全

- 伪造、缺失或不匹配的 Kafka key、product line、symbol、event id、sequence、版本和时间戳必须拒绝或进入隔离 DLT。
- 重复、乱序、跨 consumer group 和跨产品线事件不得重复应用资金事实。
- 未授权 producer 不能写订单命令、账户命令、结算、强平、保险基金和 DLT；未授权 consumer 不能读取私有事件。
- 重平衡、断连、重试和 DLT 回放后，业务幂等、顺序、fencing 和资金核对结果不变。

### 4.6 并发操作安全性

并发安全测试不以吞吐为主，而以“竞态下仍满足不变量”为 PASS 条件。使用独立测试用户、唯一 run id 和可追踪 request/event id，所有操作完成后逐项资金核对。

| 场景 | 并发动作 | 必须验证 |
|---|---|---|
| 幂等下单 | 同一 `clientOrderId` 并发提交 2/10/100 次 | 最多一个订单事实、最多一次冻结和手续费 |
| 撤单竞态 | 成交、撤单、重复撤单并发 | 订单终态单一、冻结只解冻一次、成交不丢失 |
| 改单竞态 | 改价、改量、撤单和撮合并发 | 版本/fencing 生效，不出现幽灵订单或超额数量 |
| 余额竞态 | 转账、下单冻结、成交扣减、解冻并发 | available/locked/equity 与流水守恒，无负数 |
| 双向持仓 | 同一用户同时开仓、平仓、反向开仓 | position version 单调，不能重复占用保证金 |
| 风控强平 | 正常平仓、强平、爆仓、ADL 并发 | 只有一个强平事实，保险基金/ADL 只记一次 |
| 交割/行权 | 到期、撤单、行权、重复回放并发 | 批次幂等，持仓/权利金/交割流水正确 |
| WS 隔离 | A/B 同时订阅、重连、慢消费 | 事件不串户，单用户背压不拖垮其他用户 |
| 节点竞态 | Owner/Matching/Account/Risk 单节点重启与重试 | fencing、生效版本、WAL replay 和最终状态一致 |

每个场景至少使用并发度 1、2、8、32、128；低配 `local-low` 只执行 1、2、8。记录成功/拒绝/超时/重试、锁或 mailbox 等待、CAS 冲突、版本冲突、重复事件、越权响应、资金差异和恢复时间。所有并发场景必须有负例：篡改 user id、重复 nonce、错误 product line、错误 Kafka key 或错误版本。

## 5. 四产品线安全矩阵

| 产品线 | 核心安全重点 |
|---|---|
| 现货 | 买卖资产冻结/解冻、余额越权、资产 symbol 注入、手续费和并发转账/下单守恒 |
| 永续 | 保证金账户隔离、标记价/资金费输入、强平/保险基金/ADL 越权与重复执行 |
| 交割 | 到期时间篡改、交割批次重放、持仓归零、交割流水与权限隔离 |
| 期权 | CALL/PUT 与行权方向、权利金、买卖方权益、重复行权/到期失效和风险边界 |

一次只选择一个 `PRODUCT_LINE`，每条线分别生成证据和资金对账；不得用一条线的成功替代其他三条线。

## 6. 工具和执行分层

安全套件可以调用已安装工具，但必须记录版本和实际命令：

- 被动：`curl`、`openssl s_client`、`nmap -sT` 的授权低风险端口检查、依赖/secret scanner、配置检查。
- DAST：OWASP ZAP baseline、Nuclei 安全模板、受限 `ffuf`/HTTP corpus；只使用白名单 URL、低速率和非破坏性 payload。
- 并发安全：现有模拟用户 API 流程加并发 barrier、固定 request id 和资金对账；必要时使用 k6/Gatling/JMeter，但不把性能结果替代安全结果。
- 运行时：Actuator/Prometheus、Kafka lag、JVM/GC、线程、连接、日志、审计和 outbox 指标。

工具不可用时生成 `SKIPPED_TOOL`，不能伪造 PASS。主动工具失败、目标不可达、证据缺失或安全不变量无法核对都标记 FAIL/INCONCLUSIVE，不能降级为通过。

安全套件的 Kafka 结果必须来自授权 harness 生成的 `SECURITY_KAFKA_RESULT_FILE`，至少包含
`unauthorized_write_rejected=PASS`、`unauthorized_read_rejected=PASS`、
`key_mismatch_rejected=PASS`、`cross_product_line_rejected=PASS` 和
`replay_idempotent=PASS`。并发结果必须来自 `SECURITY_CONCURRENCY_RESULT_FILE`，至少包含重复订单/冻结/流水为 0、`funds_reconcile=PASS`、`positions_reconcile=PASS`、串户为 0，以及按场景验证的单一幂等订单事实和单一撤单终态。没有这些业务 oracle 时，脚本只能输出 `INCONCLUSIVE` 或失败。

外部端口检查还必须提供 `SECURITY_PORT_RESULT_FILE`，确认 `public_exposure_review=PASS`、`unexpected_ports=0`、`tls_review=PASS`；HTTP 模糊测试必须提供 `SECURITY_FUZZ_RESULT_FILE`，确认 `resource_recovered=PASS`、`business_facts_created=0`、`authz_boundary=PASS`。只有工具退出码而没有这些结果文件时，不得判定为 PASS。

## 7. 安全门禁与证据

每轮目录至少包含：

```text
manifest.env
authorization.env.redacted
target-allowlist.txt
commands.txt
passive-config.txt
authz-results.json
http-fuzz-results.json
websocket-results.json
kafka-security-results.json
concurrency-results.json
resource-recovery.tsv
funds-reconcile.txt
findings.json
summary.md
```

安全门禁：严重/高危漏洞为 0；认证授权越权为 0；敏感信息泄露为 0；Topic/WS 串线为 0；并发场景重复资金事实、负余额、重复冻结、重复结算、重复强平和最终资金差异为 0；停止攻击后资源回到基线；所有主动用例都有授权和 allowlist 证据。

报告必须区分 `PASS`、`FAIL`、`INCONCLUSIVE`、`SKIPPED_TOOL`，并写入 Git SHA、产品线、profile、JDK/GC、目标 origin、授权工单、工具版本、限速、时间窗、结果 hash 和清理回执。禁止写入 token、密码、JWT、API key、完整个人信息和真实资金信息。

## 8. 执行顺序

```text
脚本语法与安全契约
 -> 被动配置/依赖/secret 检查
 -> 认证与授权负例
 -> HTTP 输入边界与低速 fuzz
 -> WebSocket 隔离
 -> Kafka ACL/key/重放
 -> 单产品线并发竞态
 -> 资金守恒与审计核对
 -> 授权 DAST 汇总
 -> 修复后回归与人工 Go/No-Go
```

本轮只交付文档、脚本和干跑/静态校验，不启动服务、不执行外部扫描、不运行资源耗尽、不发真实交易流量。实际执行必须由安全负责人确认授权、目标、时间窗和停止条件后单独进行。
