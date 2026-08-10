# Ultrawork Notepad — 生产级交易系统测试工程化与独立安全测试模块
Started: 2026-08-04T23:58:46+08:00

## Plan (exhaustively detailed)
1. 发现并固定当前脚本、文档、产品线 provider、网关认证、WebSocket 和 Kafka 配置事实；不启动服务。
2. 补齐安全测试设计文档和 docs 索引，明确被动检查、授权渗透、认证授权、输入/资源耗尽、消息安全、WebSocket 安全、并发/竞态/幂等安全及四产品线隔离。
3. 修正现有 performance gate 的 smoke/performance 分支、热点负载传递和 dry-run 约束。
4. 增加 JVM/GC、Tomcat/线程池、恢复矩阵的 dry-run 编排，并在 provider 启动脚本中安全传递显式测试参数。
5. 增加独立 security suite，默认被动/dry-run；主动攻击必须有显式授权和目标白名单；加入并发安全场景定义和 evidence manifest。
6. 检查脚本语法、Node 语法、文档索引和 git diff；不执行真实测试工作负载。
7. 进行 HEAVY reviewer 审查并修复命中成功标准的问题，确认无本轮遗留进程/端口/临时状态。

## Success criteria + QA scenarios
见 create_goal 中的 5 项成功标准；本轮硬约束是只做文档、脚本和干跑/静态验证，不启动服务、不执行压测/混沌/真实交易。

## Now
已修复 reviewer 的高风险门禁问题，fresh 静态/dry-run/安全拒绝证据通过，正在提交同一 reviewer 复审。

## Todo
1. 捕获新增安全入口和新增矩阵入口不存在的 RED。已完成。
2. 完成安全测试文档与索引。已完成。
3. 修正 gate 并增加 GC/Tomcat/recovery 编排脚本。已完成初版，待静态验证。
4. 完成独立 security suite 和并发安全场景。已完成初版，待静态验证。
5. 静态/干跑验证并保存 evidence。已完成。
6. HEAVY reviewer 审查。首次 BLOCK 已修复，复审进行中。

## Findings
- AGENTS.md 要求单次只测一个产品线、做市进程保持运行、逐项资金守恒、优先复用既有脚本，且禁止未授权地启动 wallet。
- 当前已存在未提交的用户/本轮前改动，必须保留；工作区新增了 performance plan、profile、resource monitor、performance gate 和 product-line matrix。
- CodeGraph 可用；当前发现网关有 GatewayProperties.Security、WebSocket 有会话/Topic 隔离配置、账户 Controller 暴露余额/转账/管理员接口，FundingRateKafkaConsumer 校验 Kafka key 与 payload symbol。
- Bash 和 Java LSP 未安装；本轮将用 bash -n、node --check 和静态命令验证。
- 用户明确追加独立安全测试模块，不能把安全检查混入性能矩阵。
- RED 基线已记录在 .omo/evidence/security-test-plan/red-baseline.txt：安全套件、GC、Tomcat/线程池、恢复矩阵入口不存在，安全文档不存在。
- 新增 security suite 默认只写 manifest/plan；主动模式要求 SECURITY_EXECUTE=true、SECURITY_AUTHORIZED=true、SECURITY_TARGET 和 origin allowlist 完全匹配。
- 新增 concurrency helper 仅在授权目标执行相同 clientOrderId 并发下单/撤单竞态；并发测试如果没有资金对账证据会保持 INCONCLUSIVE/失败。
- 新增 JVM GC、Tomcat/thread pool、recovery matrix 默认 MATRIX_EXECUTE=false；local-low 的 GC/线程池只生成 smoke/planned-only，不产生生产容量结论。
- provider 启动支持按服务 heap、JFR、Tomcat 参数和通用 recovery provider；仍保留用户显式 SERVICES/JAVA_OPTS 优先级。
- 静态与 dry-run 证据：.omo/evidence/production-test-plan/final-static-dry-run-20260804/validation-and-dry-run.log；11 个 shell 文件 bash -n、Node WebSocket check、git diff --check 和五类 dry-run 均通过。
- profile 证据：.omo/evidence/production-test-plan/final-static-dry-run-20260804/profile-local-low.env 与 profile-cloud-production.env；低配 heap/process/TPS/concurrency 明显低于 cloud-production。
- 主动安全拒绝证据：.omo/evidence/production-test-plan/final-security-gate-20260804/，未授权与缺失 allowlist 均 exit=1，且未发起网络请求。
- 首次 reviewer 报告 .omo/evidence/production-test-plan-code-review.md 命中性能指标缺失、安全/Kafka 假 PASS、并发/恢复 oracle、JFR 分词和资源 override 问题；本轮已针对每项加门禁或改为缺证据即 INCONCLUSIVE/FAIL。
- 修复后证据：.omo/evidence/production-test-plan/reviewer-fix-final-20260805/validation-and-dry-run.log、security-gate/ 和 reviewer-fix-static-20260805/reviewer-fix-static.log。

## Learnings
- 低配机器必须只启动当前场景需要的 provider，并用显式 profile 限制 JVM heap、并发、symbols/users/TPS；云环境通过 cloud profile 放宽。
- 主动安全测试涉及外部攻击风险，脚本默认拒绝执行；必须同时验证 SECURITY_AUTHORIZED 和目标 allowlist。

## Final verification — 2026-08-05

- 安全套件修正：nmap 端口结果必须有 `SECURITY_PORT_RESULT_FILE`，ffuf 结果必须有 `SECURITY_FUZZ_RESULT_FILE`；工具退出码不能单独产生 PASS。Kafka 和并发仍分别要求授权 harness 的结果 oracle。
- Tomcat 矩阵默认 `THREADPOOL_REQUIRE_TOMCAT=true`，每个实际 case 必须核对 `THREADPOOL_CONFIG_RESULT_FILE` 的四项绑定值。
- fresh evidence：`.omo/evidence/production-test-plan/reviewer-fix-final2-20260805/results.tsv` 记录 Bash/Node/diff、四类矩阵与安全并发干跑、未授权/缺 allowlist 拒绝、profile 差异、JFR 空格路径和资源超限拒绝；`.omo/evidence/production-test-plan/reviewer-fix-final3-20260805/results.tsv` 记录 Tomcat 默认绑定门禁。
- 同一只只读 HEAVY reviewer 复审结论：`APPROVE`。没有启动服务、没有执行外部扫描、没有发真实交易流量、没有执行性能/混沌测试。
