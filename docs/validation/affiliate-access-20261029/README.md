# 管理员开通 KOL 前的基础邀请返佣

本轮将未开通页面收敛为普通邀请返佣，并移除接受合作邀请自动开通 KOL 的行为。资金核算、逐层分配、成熟周期、付款协议与交易在线 Core 未修改；没有生产发布。

## 当前行为

- 未开通：模块入口“邀请返佣”，显示邀请码/复制链接、普通邀请、好友 UID 和邀请时间、每位好友为当前用户产生的已确认返佣，以及本人累计/待结算/在途/已到账、佣金明细和结算记录。币种与产品分开，累计返佣不代表已到账。
- 隐藏 KOL 标题、未开通提示、团队人数/身份、合作路径、上下级比例、管理表单、团队下钻和合作邀请列表。即使响应仍含历史团队字段，也不展示。注册页也不展示合作比例，合作码不能用于普通注册；应先普通注册，获批后再接受合作邀请。
- 开关取登录人本人 `/me.profile.kolEnabled`。下钻查看一个未开通成员时，已开通的查看人仍能正常查询；本人权限关闭后刷新，不再请求该分支，退回基础列表并清空合作目标、比例和新建链接。资料读取失败清除旧 KOL 内容，恢复后刷新可重试。
- 后端要求邀请人和接受人都已获平台开通。拒绝时不授予权限、不消耗邀请、不留下部分绑定；获批后原邀请可接受，重复接受仍幂等。普通邀请不要求开通。

旧版曾将合作接受记为“接受团队合作并继承分支管理权限”；本轮不根据文本猜测并批量撤销既有权限。若已运行旧版，发布前应由管理员复核已有开启账号，通过后台确认保留或关闭；历史资金不重算。

## 后端验证

起点 `5332e201f`。新增拒绝断言在修复前失败（期望抛错，实际未抛），确认原行为会自动授权。当前服务移除该授权写入，改为先检查后台开关。

工作区有另一项未提交的 WebSocket/开放平台改动；首次当前工作区标准 reactor 在其构造器参数变动处编译失败。本轮未改动该任务文件，使用起点提交的独立 detached worktree，仅复制本轮 AffiliateService/AffiliatePostgresTest 执行标准 Maven reactor。

```sh
INSTRUMENT_TEST_JDBC_URL=<isolated-pg> INSTRUMENT_TEST_DB_USER=<qa-user> INSTRUMENT_TEST_DB_PASSWORD=<qa-password> \
mvn -pl surprising-gateway -am test \
 -Dtest=AffiliatePostgresTest,AffiliateSafetyPostgresTest,AffiliateTeamIsolationPostgresTest,AffiliateResourceTest,CommissionAllocationTest,CommittedPayoutTransferTest,AffiliateHttpTest \
 -Dsurefire.failIfNoSpecifiedTests=false
```

55 项全部通过、无跳过；随后新增携合作码注册的事务回滚场景，单项通过（合计覆盖 56 个不同用例，非声称一次执行 56 项）。该新场景核对 Affiliate 资料/关系回滚、邀请未使用，随后同一用户可使用普通邀请码注册且保持非 KOL；没有把它称为真实注册 HTTP 全链路验证。

首轮独立运行的产品/币种结算测试错误地假定一次受 250ms 预算约束的调度必定排完三个组，在负载下只准备一组；改成至多三轮有界准备，仍严格核对结算单恰为三张和各资产金额。未增加生产批次预算或放宽资金断言。其后 55 项全绿。

结果和编译边界见 [verification.txt](verification.txt)。未运行全仓所有测试；并行工作区的整体构建不由本记录背书。

## Web 和 App

Web 执行 lint、typecheck、返佣两项输入测试、build；Flutter 执行 analyze、三项返佣输入测试。真实 Chrome 1440×1000 浅色、390×844 深色，检查普通/开启/成员开关不同/分支中关闭/失败恢复、复制、未开通合作接受禁用、注册页屏蔽及未发出合作注册 POST。页面无未捕获异常或文档横向溢出；窄屏好友表格换行展示金额，其他明细表保留容器内横向滚动。

原生设备流程运行在独立 iPhone 17e / iOS 26.5 模拟器，以及只读 Android AVD emulator-5582。检查实际滚动、点击、输入、复制及系统剪贴板回读、深浅主题、好友逐币种返佣、开通、成员下钻、关闭并清空草稿、503 恢复；设备结论按 [device-results.json](device-results.json) 的最终退出码记录。

UI 使用明确的本机 HTTP 契约夹具，不连接生产、不修改真实用户和资金。启用/停用控制入口仅在测试服务中；后端实际权限与事务另由真实 PostgreSQL 验证，不将 UI 夹具称为全栈财务验证。原真实 Gateway 注册/管理原生脚本已同步要求 QA 管理员会话进行开通；本轮未重跑该全流程。

验证过程保留：首次 iOS 夹具没有读取 chunked 请求体，修正测试服务后重跑通过；首次 Android 有系统 System UI ANR 弹窗，剪贴板无法回读，该轮失败不计通过。实际恢复模拟器后重跑，未修改生产复制逻辑绕过平台检查。截图与最终日志见本目录归档。

## 复现与清理

启动 Web `scripts/affiliate-access-fixture.py`（本机 19106）和 Vite（API 代理到该夹具）；执行 `scripts/affiliate-access-qa.mjs`，使用 `QA_PLAYWRIGHT_MODULE` 与 `QA_OUTPUT` 指定本机工具和输出。原生执行 `integration_test/affiliate_access_host.py <ios|android> --device <device> --output <artifacts>`。测试控制服务没有生产路由或依赖。

完整初始/最终检查摘要、截图和设备结果随本目录归档；临时数据库、隔离 worktree、测试服务、Vite、原始日志/工具、只读 Android 实例及新建 iOS 模拟器的清理见 [cleanup.json](cleanup.json)。未处理其他任务的源码、设备、数据库或本地工具。
