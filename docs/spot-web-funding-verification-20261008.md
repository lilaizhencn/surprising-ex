# 现货充值在用户 Web 不显示：修复与验收（2026-10-08）

## 原因与改动

交易服务器同时运行 SPOT `9194` 和 LINEAR_PERPETUAL `9094`。托管充值和认证已代理到 `9194`，但资产查询及所有 WebSocket 默认进入 `9094`；现货测试实例还关闭了实时推送。用户资产页依赖实时账户快照，因而不能看到现货入账。

`lilaizhencn@gmail.com` 对应用户 1。修复前该用户已有专属 BTC 地址，但没有已确认的 BTC 充值；此前最近的 RBF 测试资金属于用户 37、38、39，不能把它们当作用户 1 的余额。

- `deployment/test-single-node/ex-api.nginx.conf`：按 `X-Product-Line` / `productLine` 查询参数选择运行时；保留认证、充提、Webhook 的现货入口。运行时目录只声明当前部署的 SPOT 和 LINEAR_PERPETUAL，包含浏览器 CORS 预检（包括 `X-Device-Id`）。
- Web `src/lib/config.ts`：WebSocket URL 带产品线参数，让连接在 Upgrade 前进入正确实例。协议帧仍保留产品线校验。
- 现货测试启动器在 gateway 就绪后启动现有 realtime 服务；`spot-custody-realtime.patch` 记录服务器测试启动器的精确变更。此启动器是服务器上已有的隔离测试脚本，不是本仓库通用启动器。
- `spot-realtime.env.example` 记录隔离的 22010/22020/22030 通道及 9195 端口；保留现有凭据、RUN_ID、Archive 和持久化数据库。`surprising-spot-test.service` 已启用开机启动。
- Web `src/hooks/useRealtimeAssets.ts`：账户现金数量就绪与美元估值分开；缺少估值报价显示“— / Valuation unavailable”，不继续隐藏已同步的币数量，也不制造价格。

没有直接改余额、复制账本或改写已处理的 Webhook 状态。未改 Java 业务代码或资金状态转换。

## 真实充值

通过公网 API，以用户 1 身份申请 BTC 网络地址：

`bcrt1qh6zy34lqvlpqjfl6lprn5z58k88an9deu9eyvzpq6rgn9dux4nmsmw5yxt`

随后仅发送一次 regtest BTC 0.001，并挖出 6 个区块。发送操作不自动重试，交易哈希立即保存到本轮检查点。

| 项目 | 结果 |
| --- | --- |
| 链上交易 | `3b7aee3d42fa27e6a223afcdd8e8182838dbaea6f3f3abd6f989759b6564d487` |
| 充值区块 | 124；确认后链高 129，6 个确认 |
| Wallet custody | `user:1`，0.001 BTC，CONFIRMED，仅一条记录 |
| Webhook event | `d85c58d6-4c98-4258-9059-24b41ec2cdce` |
| Wallet 公网投递 | DELIVERED，HTTP 204，attempt_count=1 |
| Exchange Webhook | PROCESSED，attempts=1 |
| Exchange SPOT | availableUnits=100000，lockedUnits=0，equityUnits=100000 |
| LINEAR_PERPETUAL | 原有 USDT 可用 9854834039745、冻结 142100024738、权益 9996934064483 units 不变 |

对该原始 Webhook 的字节内容先比对数据库 SHA-256，然后通过交易服务本机 HTTP 入口并发重放 16 次，全部返回 204；同一事件仍为 PROCESSED/attempts=1，余额仍为 100000 units。该并发重复测试在 loopback 运行；原始 Wallet→公网→Exchange 投递另有真实 204 验证。从交易服务器尝试公网重放被边缘 403 拦截，未将它计作成功投递。

## 验证范围

- Nginx `nginx -t` 通过，热加载后公网认证、现货余额、合约余额、SPOT realtime state 返回 200。
- 六个原有测试用户（34–39）的全部资产可用、冻结和权益与重启前一致；37–39 各保留 0.002 BTC。
- 真实网站 `https://ex.tokdou.com/assets?account=spot`，用户 1 的审计会话：1440×1000 桌面、390×844 手机，表格均显示 BTC / SPOT / 可用 0.001；页面无 JavaScript 异常。检查了实际 WebSocket 地址分别带 SPOT 与 LINEAR_PERPETUAL，并再次查询公网充值记录和余额。
- Web 构建通过；最近一轮 4 个受影响测试文件 37 项通过。新增 WebSocket 产品线路由测试通过。最终提交的 GitHub quality 与 Cloudflare Workers 自动构建发布通过。
- 本地完整 lint 还包含此前未提交文件的格式错误；本次文件检查通过。远端提交的完整 quality 已通过，未把其他工作区改动混入本次提交。

## 部署与边界

Web 提交：`bdcb195`（连接路由）、`cfde791`（说明已有 Ant Design 主题覆盖规则）、`23b121e`（现金数量不等待估值）。

服务器修改前备份：`/root/spot-web-fix-1791429800`，含 Nginx、现货 env、测试启动器及用户余额基线；其中 env 是敏感配置，只保留在服务器，不提交。

现货核心重放历史日志耗时数分钟。排查时曾尝试先启动 realtime，因依赖未就绪的 instrument HTTP 失败，最终恢复 gateway→realtime 顺序；也尝试过 C1 JVM 编译配置。当前恢复进程使用该诊断 JVM 配置，未来启动 env 已移除该项；没有把它作为业务修复或性能验收。后续维护重启仍应监控日志恢复时长并再次对账。本次不宣称验证了后续重启性能。

测试环境没有 SPOT 报价市场，因此 BTC 美元估值仍不可用；实际币余额可正常查看。本次没有新增提现、交易或归集测试，不把此前测试范围扩大到本次验收。

验收截图位于 `docs/validation/spot-web-funding-20261008/`，不含访问令牌或密钥。本轮临时访问令牌、重放脚本和诊断中间文件在验收后清理；服务器持久化账本、服务和回滚备份保留。
