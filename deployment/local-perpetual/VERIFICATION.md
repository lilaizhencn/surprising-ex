# 本机永续验证记录（2026-09-27）

## 范围与启动结果

仅启动 LINEAR_PERPETUAL：Core、gateway、price、realtime、derivatives-lifecycle、maker 六个 JAR，
以及 PostgreSQL、Redis、Kafka 和前端。wallet 未启动。
HotSpot Corretto JDK 27 / Maven 3.9.16；macOS 本机验证。

入口为 `scripts/local-perpetual.sh`：集中读取一次 `local.env`，准备托管依赖和首次数据库，
调用 `linear-perpetual-single-node.sh` 启动现有服务，初始化本机做市测试资金，
最后由 `check-local-perpetual.py` 核验市场。错误退出保留诊断，不靠修改每个 JAR 的配置启动。
运行目录为 `~/.local/share/surprising-ex/perpetual-20`，页面端口 5174。

六个进程运行，五个 HTTP 服务健康 UP；realtime 健康包含 Kafka Streams RUNNING。
重复 `up` 保留已有进程，市场检查 20/20。配置/令牌未写入仓库。

## 20 币对链路

BTC、ETH、SOL、XRP、DOGE、ADA、BNB、AVAX、LINK、DOT、LTC、BCH、UNI、AAVE、NEAR、SUI、
TRX、OP、ETC、FIL，均为 USDT-SWAP。

每币对核验：三路有效外部 WebSocket 指数源、有效标记价、双边做市盘口；
测试用户市价买入 FILLED、reduce-only 卖出平仓 FILLED、GTX 限价挂单 ACCEPTED、撤单 CANCELED。
用户 20 个持仓归零、冻结余额为零。做市保持运行，没有用静态报价替代链路。
WebSocket 捕获全部 20 币对的成交与 K 线，并收到盘口和私有执行/持仓/账户事件。
初始化目录中其他已导入合约没有配置本轮指数源或做市，不计入已验证上线范围。

Core 权威查询的 USDT 核对（单位 10^-8）：

| 项目 | 可用/余额 | 冻结 |
|---|---:|---:|
| 测试用户 1 | 9,999,505,223,013 | 0 |
| 做市 900001 | 9,989,655,711,372 | 10,505,233,187 |
| 做市 900002 | 9,989,470,022,516 | 10,505,229,487 |
| 手续费库 | 358,560,425 | — |
| clearing PnL | 20,000 | — |

其余国库余额为零。合计 30,000,000,000,000，与本轮三个账户各 100,000 USDT
测试注资总额一致，资金守恒差额 0。余额为验证时刻快照，持续交易后可变化。

## 前端

价格根据实际 tick/资产 scale 显示，覆盖页头、币对、盘口、逐笔、K 线 OHLC 和坐标轴；
BTC 两位、DOGE 五位，极小价格与相邻小价格档位有回归测试。
进入页面和切换币对各拉一次 index/mark 快照，随后使用 WebSocket，已移除一秒轮询。
币对列表去除十秒轮询，打开时初始化并订阅 trades，关闭撤销额外订阅。
资金费率也消费 funding 推送；慢快照不能覆盖已收到的新价格/资金费率。

Chrome 实机检查：初始化价格请求 2 次（各接口一次），切换后新增 2 次，
稳定运行及断网重连价格 REST 新增 0 次；index/mark 推送恢复，页面无 JS 异常。
K 线网络实测：首次 BTC/15m 仅 1 次；改为 BTC/1m、DOGE/1m、DOGE/15m 各新增 1 次；
没有其他币对/1h 历史请求，停留与重连没有新增请求。每次返回最近 120 根，后续通过推送更新。
不再为列表涨跌幅加载全部币对的 1h K 线，也不再另外加载当前币对的 1h K 线。
1440×1000 桌面、390×844 手机截图均检查；手机 scrollWidth=viewportWidth=390。

## 自动化与证据

- `mvn -pl surprising-realtime/surprising-realtime-provider -am test -q`：受影响 reactor 1179 项测试，0 失败/错误。
- 六服务及工具模块 Maven package 成功；打包 skipTests 不计作测试结果。
- 前端 `npm test`：15 文件 / 60 项通过；`npm run lint`、`npm run build` 通过。
- `bash -n`、`git diff --check` 通过；最终磁盘余量约 423 GiB。
- 运行目录保留 `market-readiness.json`、`trading-verification.json`、`ws-verification.json`、
  `frontend-network-verification.json`、桌面/手机 PNG；没有记录认证令牌到这些证据。

## 尚未验证与保留状态

本轮没有实机覆盖强平、ADL、保险基金支出、资金费结算时点、止盈止损触发、其他产品线、
高可用和长时间压力测试。因此这是本机启动与基础交易链路验收，不是全部生产功能验收。
运行抽查存在 17–19/20 的短暂行情未就绪，后续恢复到 20/20。
WebSocket 诊断确认个别 BINANCE/BYBIT 报价过期时 `validComponentCount=2`、
`status=INSUFFICIENT_SOURCES`，指数查询返回 503；还观察到 OKX 报价停滞后自动重连。
保留既有三源要求和过期阈值，没有用旧价格或放宽门槛掩盖问题。
因此可以确认 20 币对已配置并逐对交易验证，不能承诺外部行情源持续可用或生产级可用性。
Linux 启动路径和外部 PostgreSQL/Redis/Kafka 组合未实机验证。
前端构建仍提示单个 bundle 超过 500 kB；lint 既有提示不阻塞构建。

按用户查看页面的要求保留本轮服务、测试数据、日志和 Archive；无需测试的浏览器已退出，
没有运行 JFR 或堆转储录制。日志使用滚动限制，停机用 `./scripts/local-perpetual.sh down`，
不会删除交易数据。本轮未覆盖或删除用户原有 Core 源码改动及 Aeron 目录。
