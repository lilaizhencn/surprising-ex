# 邀请、KOL 与返佣验收（2026-10-28）

## 范围与环境

本轮修改 Gateway `com.surprising.affiliate`、认证注册边界、通用历史费用导出和 Web / Admin / Flutter 页面。没有向在线下单、撮合、保证金或手续费计算链路加入营销字段、调用或状态。资金发放复用既有账户划转，未直接修改余额表。

环境：macOS x86_64；Corretto HotSpot JDK 27、Maven 3.9.16、PostgreSQL 本机隔离库；Chrome 1440×1000 与 390×844；Android API 36 模拟器 emulator-5580；iOS 26.5 iPhone 17 Pro 模拟器。Android AGP 构建单独使用现有 JBR 17，不改变后端 JDK。所有注册、合作和页面查询均调用本机真实 Gateway 19094，Web 5173、Admin 5174；没有向生产发布或操作生产资金。

测试数据位于 `surprising_affiliate_qa_20261028`。会 TRUNCATE 用户的既有认证回归专用第二个隔离库 `surprising_affiliate_auth_qa_20261028`，不对页面联调库或既有业务库执行清空。

## 验证结果

| 验证 | 结果与证据 |
| --- | --- |
| Gateway 资金与关系专项 | 18 项通过，0 失败/跳过；[摘要](gateway-tests.txt) |
| 真实 Core 费用观察 | 2 项通过；实际撮合正手续费、负 maker rebate、部分成交快照恢复；[摘要](core-fee-tests.txt) |
| 通用费用 PostgreSQL 投影 | 2 项通过；基线、正负增量、重复、事务回滚与产品隔离；[摘要](fee-projection-tests.txt) |
| 注册、认证、资料与审计回归 | 77 项运行，0 失败、2 项依赖环境跳过；[摘要](auth-tests.txt) |
| 后端构建 | Gateway + Realtime 及依赖共 13 模块 package 通过（跳过测试）；后续 Gateway package 再次通过；[构建摘要](backend-package.txt)、[Gateway 摘要](gateway-package.txt) |
| 用户 Web | lint / typecheck / build 通过；65 文件、550 测试通过；[测试摘要](web-checks.txt) |
| Admin | lint（tsc）/ build 通过，139 测试通过；[检查摘要](admin-checks.txt)、[测试摘要](admin-tests.txt) |
| Flutter | analyze 无问题；750 通过、12 项依赖环境跳过；[摘要](flutter-checks.txt) |
| 浏览器交互 | 管理端开通 KOL、合作链接、注册自动填码并接受、比例修改、贡献下钻和返回；桌面/窄屏无页面横向溢出；[结果](browser-result.json) |
| 浏览器恢复与持续运行 | 3 分钟、18 轮资产页/邀请页切换；真实断网错误后恢复；72 次 Affiliate 读取，无 pageerror。强制 GC 后 JS 堆从 20457184 到 20213712 字节；仅为此短窗口观察，不代表无泄漏或性能容量结论；[采样](stability-result.json) |
| Android / iOS | 两端各 2 个真实业务场景通过（驱动另计 tearDownAll），含注册、合作接受、下钻/返回、比例 60%→50%、原生深链和注册自动填码；[Android](android-device.log)、[iOS](ios-device.log) |

Gateway 专项覆盖普通用户无需 KOL 计佣、唯一归属/循环/越权拒绝、合作接受后自动获得分支管理权限、平台停用优先、分支贡献隐私、三级分配守恒、成员自交易排除、历史比例不重写、碎片费用累计取整、重复事实冲突、同日志位置不同订单、注册事务回滚、并发接受与并发计佣、多产品多资产隔离、自动开关和周期变更、未知划转原身份恢复、明确拒绝只生成一张替代单。

其中资金恢复使用真实 `CommittedTradeReplay`：预算 10000、佣金 2000，首次划出后故意丢失响应；快照重建后恢复同一笔结算，最终预算 8000、收款人 2000，没有重复扣/加。测试替换的是传输与首次响应丢失，不替换 Core 账户结算或快照实现。未连接真实 Aeron 网络/生产预算，不把此项描述为生产全链路压测。

## 关键命令

```bash
# 后端主源构建（JDK 27）
mvn -pl surprising-gateway,surprising-realtime/surprising-realtime-provider -am -Dmaven.test.skip=true package
# 联合测试：本轮在 Gateway 既有测试编译阶段受下述外部重构阻塞
mvn -pl surprising-gateway,surprising-realtime/surprising-realtime-provider -am test -Dtest=CommittedFeeObservationTest,CommittedFeePostgresTest,CommissionAllocationTest,AffiliatePostgresTest,CommittedPayoutTransferTest,AuthServiceTest -Dsurefire.failIfNoSpecifiedTests=false
# 专项分别执行；PG 测试传 INSTRUMENT_TEST_JDBC_URL / DB_USER / DB_PASSWORD 指向隔离库
mvn -pl surprising-gateway surefire:test -Dtest=CommissionAllocationTest,AffiliatePostgresTest,CommittedPayoutTransferTest
mvn -pl surprising-aeron-core/surprising-aeron-service antrun:run@package-test-fault-agent surefire:test -Dtest=CommittedFeeObservationTest
mvn -pl surprising-realtime/surprising-realtime-provider surefire:test -Dtest=CommittedFeePostgresTest
# Web
npm run lint && npm run typecheck && npm run test && npm run build
# Admin
npm run lint && npm run test && npm run build
# Client
flutter --suppress-analytics analyze --no-pub
flutter --suppress-analytics test --no-pub
python3 integration_test/affiliate_host.py android --device emulator-5580
python3 integration_test/affiliate_host.py ios --device <本轮独立模拟器 UUID>
```

因为 Gateway 的无关旧测试无法整体编译，专项先以 `mvn dependency:build-classpath` 的测试依赖和当前 `target/classes` 为 classpath，使用 `javac -proc:none --release 27` 显式编译本轮 3 个 Affiliate 测试与 5 个受影响 Auth 测试，然后执行上面的 Surefire 目标。没有把跳过编译的旧 class 当成新源码测试结果。Core 费用测试和 Realtime 投影测试也单独编译和执行；联合测试已经执行到 Core 与 Realtime 新测试通过后，才在 Gateway 旧测试编译阶段停止。

浏览器复现脚本为 [browser-qa.mjs](browser-qa.mjs) 和 [browser-stability.mjs](browser-stability.mjs)，需要已安装 Playwright 和 Chrome；可以通过 `PLAYWRIGHT_MODULE` 指向本机 Playwright 模块。脚本里的邮箱/口令仅为已删除的本地 QA 数据夹具，不是生产凭证。原生测试使用 Client 的 `integration_test/affiliate_host.py`；它只读本机 QA 邀请码、调用真实 OS URI、同步截图并等待设备确认，未 mock 深链或业务 HTTP。iOS 系统确认由既有 Maestro 工具点击“打开”，无确认框时允许继续，实际是否进入邀请页仍由 Flutter 断言决定。

## 截图与人工检查

- [管理后台桌面](admin-desktop.png)：配置、开关、预算和来源均可见，金额表头说明最小单位。
- [用户桌面](owner-desktop.png)、[用户窄屏](owner-mobile.png)、[新成员窄屏](member-mobile.png)：字段、长链接、表格横向滚动限制在面板内，页面无横向溢出。
- [断网错误](offline-mobile.png)、[恢复后的暗色窄屏](owner-mobile-dark.png)：错误可识别，恢复后正常读取，主题与平台一致。
- 原生截图按 `affiliate-android-*` / `affiliate-ios-*` 归档；由 OS 截图后确认，再允许测试切页，避免截图与断言错位。iOS Simulator 后台不能写 macOS Desktop，先写临时目录再由测试宿主复制；不对图片做生成或加工。

## 已知限制与明确未测范围

- 后端联合测试未通过：既有 `CoreTestCompletion` 仍调用删除的 `placeAdmissionOutstanding`，`MaintenanceIntegrationTest` 和 `MultiProductMoneyIntegrationTest` 仍引用删除的 `totalUnits`、异步命令作用域方法。它们属于工作区正在进行的另一项 Core 重构；本轮保留其工作，没有恢复被删接口或改动这 3 个测试来让结果变绿。[原始编译错误摘要](reactor-test-block.txt)。因此不能宣称后端全量测试通过。
- 没有生产发布、真实三节点 Aeron、真实资金充值、资金费/交割/期权全产品实际成交、生产吞吐/JFR/JMH 或数小时设备长稳测试。六产品路由使用同一权威费用契约，产品/资产隔离以 PostgreSQL 专项验证；设备实际流程验证的是邀请与团队管理。
- 真实手续费验证采用 SPOT 撮合；真实资金恢复采用 U 本位永续账户。没有把其他产品的实际成交路径冒称为已测。
- 未配置 HTTPS 域名关联文件，分享采用 Web 链接 + 显式 App scheme；没有宣称原生 Universal Links 自动唤起。
- 为满足既有检查，修正 4 处 Web 基线 lint（等价 optional chaining 与一处已有 CSS 结构的精确说明），以及 Flutter 一处旧测试：统一错误已使用中文安全文案，测试现在同时验证中文错误和不泄露内部投影原文。未改变这些业务行为。
- 全量后台写操作都有权限与审计；本轮未扩展财务冲正、组织企业认证、跨团队迁移或多人共享资金账户。

## 清理与提交

最终提交仅包含本任务文件，保留工作区其他交易核心改动；独立服务、设备、端口和两个 QA 数据库在归档后清理。最终清理及提交状态以本节追加记录为准。


最终结果：Android / iOS 两端各 2 个场景均通过，覆盖注册自动填写、合作接受、团队下钻/返回、60%→50% 比例修改与真实 OS 深链。最后 13 模块 package 于 21:26 通过；安装当前 Core 依赖后，21:28 再次通过真实费用观察 2 项与 Gateway 专项 18 项，包含实际资金快照恢复。代码之外的旧测试编译阻塞保持如上所述。

已清理本轮 Gateway 19094、Web 5173/15173、Admin 5174/15174、Android emulator-5580 实例、独立 iOS 设备及两个隔离 PostgreSQL 库；保留预先存在的 AVD 和其他项目/设备。截图、测试摘要与复现脚本保留在本目录。所有测试服务均为临时环境，没有生产部署。

已推送：用户 Web `7781f42`，Admin `889d299`，Flutter Client `e9e174f`。后端本次提交可按 `feat(affiliate): add hierarchical commissions in gateway` 在本仓库历史定位；只包含本功能和此验收目录，不包含同时进行的其他 Core 重构。
