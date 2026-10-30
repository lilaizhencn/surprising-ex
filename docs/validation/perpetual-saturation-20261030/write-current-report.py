from pathlib import Path
import json,shutil,gzip,hashlib,datetime
ROOT=Path('/Users/atomex/Desktop/surprising/surprising-ex')
OUT=Path(__file__).parent
DOC=ROOT/'docs/validation/perpetual-saturation-20261030'
DOC.mkdir(exist_ok=True)
s=json.loads((OUT/'summary.json').read_text());r={x['name']:x for x in s['stages']}
names=['ordinary-4-after-boundary-fix','batch-4','batch-8','diagnostic-8']
labels=['普通单，4线程','batch20，4线程','batch20，8线程','batch20，8线程，JFR独立配对']
for name in names:
 x=r[name];assert x['pass'] and x['status']=='PASS' and x['offered']==x['accepted']==x['finalized'] and x['submittedRequests']==x['completedRequests'] and x['failures']==x['unfinishedRequests']==0
 assert x['auditBefore']['fundsDiff']==x['auditBefore']['unfinished']==x['auditBefore']['backlog']==0
for name in ['batch-4','diagnostic-8']:assert r[name]['recovery']=='PASS' and r[name]['auditBefore']==r[name]['auditAfter']
j={role:json.loads((OUT/'diagnostic-8'/f'{role}-jfr-analysis.json').read_text()) for role in ['node','client']}
assert all(v['dataLossEvents']==0 for v in j.values())
s['conclusion']={'businessAndRecovery':'PASS for completed stages','formalPerformance':'INVALID: OS CPU speed limit below 100%; ordinary/batch-4 also have system swap-in','coreSaturation':'UNCONFIRMED','initialWarmupFailure':'retained; fixed bounded boundary drain observation before completed measurements','bestUnprofiledOrdersPerSecond':r['batch-8']['businessOpsPerSec']}
(OUT/'summary.json').write_text(json.dumps(s,ensure_ascii=False,indent=2)+'\n')
for file in ['summary.json','plan.json','commands.json','environment-and-checksums.json','post-fix-checksums.json','run.py','run-initial.py','run-before-snapshot-args-fix.py','resume-recovery.py','SaturationObserver.java','SaturationObserver-initial.java','SnapshotEvidence.java','analyze-jfr.py','summarize.py','write-current-report.py','owner-commit-profile.jfc','build-and-harness-tests.log','boundary-fix-build-tests.log']:
 shutil.copy2(OUT/file,DOC/file)
for name in ['ordinary-4',*names]:
 d=DOC/name;d.mkdir(exist_ok=True)
 for file in (OUT/name).iterdir():
  if file.is_file() and file.suffix in ['.json','.log','.jsonl','.txt'] and file.stat().st_size<10_000_000:
   if file.suffix=='.jsonl':
    with gzip.open(d/(file.name+'.gz'),'wb') as f:f.write(file.read_bytes())
   else:shutil.copy2(file,d/file.name)
def f(v,d=3):return f'{v:,.{d}f}'
lines=['# 单 Aeron 节点 USDT 永续持续发压（2026-10-30）','','## 1. 结论与场景','','**部分验证：完成档位的资金、终态及两次快照恢复通过；正式速度验收无效；Core 业务饱和未确认。**',
'普通单观测约 3.97 万单/秒；每报文 20 单的批量档最高观测约 28.54 万单/秒。测量期间 OS CPU 速度上限为 68%–77%，普通单及 4 线程批量档还存在系统换入页，故这些数字只作本机诊断，不能当作 Core 容量上限或生产容量。',
'',f'初始 master `{s["plan"]["initialSourceCommit"]}`，修正边界排空观察后的测量 master `{s["plan"]["sourceCommit"]}`。唯一已有未跟踪文件为 `AERON_CORE_REVIEW.md`。生产交易代码未修改。',
'本轮主体是**纯批量交易基准**，另列普通单基线；保留真实 loopback UDP、单成员 Consensus、Archive/WAL、Core、1 个 Matcher 和 2 个 Account Lane。不是纯撮合微基准，也未覆盖生产混合风控全链路。',
'', '环境：MacBookPro16,1 / Intel i9-9880H / 8核16线程 / 16 GiB / 64B缓存行；macOS 26.7.2，Corretto HotSpot JDK 27，Maven 3.9.16，G1。节点堆 512m–1536m，客户端 128m–512m，观察器 64m–128m。开始磁盘可用约 526 GiB，已有 swap 761.75 MiB；判定依据是实际测量窗口换页增量和限频，而非已有 swap 大小。',
'依赖基线：exchange-core 0.5.19-emporia、Aeron 1.54.0 / Agrona 2.7.0、Spring Boot 4.1.2。JAR/JFC SHA-256 与版本输出见 [环境](environment-and-checksums.json)、[修正后校验](post-fix-checksums.json)。',
'', '执行前锁定：LINEAR_PERPETUAL，10,000 预充值账户、256 币对、seed=9901、GTC 买卖各半、无外部做市；不限速持续异步发压，全局 256 请求窗口。普通单 MATCH_STREAM / batchSize=1；批量 MATCH_BATCH_STREAM / batchSize=20，即最多 5,120 项业务订单在途。每档新节点，预热30秒 / 稳定测量60秒 / 排空最多30秒，各一次。批量档只将 worker 从4增到8，连接数固定4。选择最快成功批量档8线程独立做相同参数 JFR/GC 配对。',
'资源每2秒采样，Core队列按100ms目标周期查询；JFR Java/native 栈周期20ms。主吞吐不带 profiler。每次复测间停止自有节点，后续批量与诊断档启动时确认 CPU_Speed_Limit=100。',
'闭环窗口存在 coordinated omission，没有固定到达率补偿；每档仅一次，没有置信区间。观察器的只读查询增加少量负载，各完整档保持相同。',
'', '## 2. 核心性能看板','','以下吞吐只用固定60秒稳定窗终态增量；排空、预热和账户审计均单列。全部为受环境影响的诊断观测。','','|档位|稳定窗完成委托|报单处理（单/秒）|窗口后排空委托|含排空成交对|','|---|---:|---:|---:|---:|']
for name,label in zip(names,labels):
 x=r[name];lines.append(f'|{label}|{x["terminalBusinessOperations"]:,}|{f(x["businessOpsPerSec"])}|{x["drain_terminalBusinessOperations"]:,}|{x["matches"]:,}|')
lines+=['','成交对为测量加排空的实际计数，未单独采稳定窗成交增量，因此不把报单TPS除2冒充实测成交TPS。撤单/改单为0，该场景不发此类指令。','', '|层次|指标|普通单4线程|批量4线程|批量8线程|', '|---|---|---:|---:|---:|',
'|业务交易|委托报单（单/秒）|'+ '|'.join(f(r[n]['businessOpsPerSec']) for n in names[:3])+'|',
'|综合业务|含行情、资金费、强平的综合吞吐|未完整计量|未完整计量|未完整计量|',
'|清算记账|分片完成任务（次/秒，周期差分）|'+'|'.join(f(r[n]['clearingObserved']['completedPerSecond']) for n in names[:3])+'|',
'|底座协议|Core已提交应用请求（条/秒，周期差分）|'+'|'.join(f(r[n]['clearingObserved']['committedCoreSequencePerSecond']) for n in names[:3])+'|',
'|底座协议|物理网络/落盘带宽|未独立采集|未独立采集|未独立采集|',
'', '分片数字是内部任务次数，单个任务可包含多个账本变化；不能等同逐单账本流水。Core应用请求使用服务端已提交序号差分，包含行情/观察影响，不能当作UDP物理包或fsync次数。两者覆盖约59.8秒周期采样区间，实际起止、计数及类型见 [summary.json](summary.json)。每报文批处理系数分别为1/20/20。',
'', '|档位|队列样本|完成队列非零样本|派发/上下文/账户队列非零样本|完成队列观测峰值|', '|---|---:|---:|---|---:|']
for name,label in zip(names,labels):
 q=r[name]['queues'];lines.append(f'|{label}|{q["samples"]}|{q["nonzeroCompletion"]}|{q["nonzeroDispatch"]}/{q["nonzeroContext"]}/{q["nonzeroAccount"]}|{q["peaks"]["completionDepth"]}|')
lines+=['',f'批量4→8线程的本次吞吐观测增加 {f((r["batch-8"]["businessOpsPerSec"]/r["batch-4"]["businessOpsPerSec"]-1)*100,2)}%；仍没有持续服务端积压的证据。全局在途峰值均为256；测量加排空的1秒采样均值分别为254 / 234 / 219.068，诊断档216.881。短突发可能被100ms轮询漏采，零样本不等于从未排队。',
'', '## 3. 延迟与抖动','','请求边界：价源就绪后开始请求 → 编码/客户端排队 → Aeron/Consensus/Archive → Core/Matcher/Lane → 客户端终态回调，单位ms。排除等待在途空位和价源前置依赖。batch20 将批请求终态延迟按20项计权，不代表逐项独立完成时间。',
'', '|档位|p50|p90|p95|p99|p99.9|max|', '|---|---:|---:|---:|---:|---:|---:|']
for name,label in zip(names,labels):
 x=r[name];lines.append('|'+label+'|'+'|'.join(f(x['requestToTerminal'+k+'Micros']/1000) for k in ['P50','P90','P95','P99','P999','Max'])+'|')
lines+=['', '独立JFR客户端按命令ID约1/64抽样，取完整稳窗配对，单位µs，nearest-rank分位。以下是客户端三段边界，不能替代Owner、Matcher、Account Lane的独立执行时延。', '', '|边界|样本|p50|p90|p95|p99|p99.9|max|','|---|---:|---:|---:|---:|---:|---:|---:|']
for key,label in [('queuedToOffered','客户端排队→成功提交'),('offeredToDelivered','提交→终态送达'),('queuedToDelivered','排队→终态送达')]:
 v=j['client']['latencyBoundariesMicros'][key];lines.append('|'+label+'|'+str(v['samples'])+'|'+'|'.join(f(v[k]) for k in ['p50','p90','p95','p99','p999','max'])+'|')
lines+=['',f'窗口边缘不完整配对 {j["client"]["boundaryUnpaired"]} 个；没有据此填补或推导服务端各阶段延迟。',
'', '## 4. 内存、GC、CPU与饱和证据','','以下为稳定窗资源采样；换页是系统全局4KiB页，不能归因全部到Core。CPU为ps进程CPU采样口径，每逻辑CPU约100%；RSS包含heap/native/mmap。包围完整稳窗的相邻资源点换页差分也保存在JSON中。',
'', '|档位|CPU速度上限|换入/换出页|Core/客户端平均CPU|Core/客户端最大RSS（GiB）|','|---|---|---:|---|---|']
for name,label in zip(names,labels):
 e=r[name]['environment'];lines.append(f'|{label}|{e["cpuSpeedLimitMin"]}–{e["cpuSpeedLimitMax"]}%|{e["swapinsPages"]}/{e["swapoutsPages"]}|{f(e["node"]["cpuPercentAvg"],1)}% / {f(e["client"]["cpuPercentAvg"],1)}%|{f(e["node"]["rssMaxKiB"]/1048576)} / {f(e["client"]["rssMaxKiB"]/1048576)}|')
lines+=['','GC、分配和DirectBuffer来自独立JFR稳窗，不与主吞吐混用。','', '|角色|GC次数 / 总暂停 / 占比 / 最大暂停|线程分配区间增量|DirectBuffer最大容量|DataLoss|','|---|---|---:|---:|---:|']
for role,label in [('node','Core'),('client','客户端')]:
 x=j[role];m=x['memory'];direct=max(v['totalCapacity'] for v in m['directBufferStats']);lines.append(f'|{label}|{m["gcPauseCount"]} / {f(m["gcPauseMs"])}ms / {f(m["gcPausePercent"],4)}% / {f(m["gcPauseMaxMs"])}ms|{m["partialWindowAllocatedBytes"]:,} B|{direct:,} B|{x["dataLossEvents"]}|')
lines+=['','精确B/item未采；周期线程分配首末点位于稳窗内部，遗漏边缘和部分短命线程，不能当作严格全窗分配或0B/item证据。DirectBuffer不包含全部mmap/RocksDB native内存。60秒样本不证明无泄漏，未采硬件PMU/缓存失效。',
'', 'JFR稳窗主要证据：',
'- Matcher有2378/2826个Java/native栈样本落在空输入分支的 `Thread.onSpinWait()` / `continue`；两个Account Lane相应等待环样本1954/2825、1392/2823。样本占比不是墙钟CPU时间占比。',
'- 客户端大量样本位于 `ClusterCapacityMain.streamMatch:439` 的在途槽轮询和UDP接收。4→8线程仅观测到约5.3%的报单增长；结合服务端低积压、窗口限制及CPU限频，不能据此认定Core业务已饱和或精确定位某个业务方法上限。',
'- JFR独立档稳窗Core/客户端分别记录66/121次编译、10/25次去优化和7/244次safepoint事件；客户端有2次超过1ms的Java monitor进入事件。完整锁与等待视图保留，不以少量事件证明不存在竞争。',
'- Core稳窗仅采到Archive conductor的52次超过1ms的Java FileWrite，合计1,378,496 B，最长23.488ms；阈值、JNI及UDP盲区使其不能替代完整磁盘/网络带宽。未采到Owner超过1ms的Java同步I/O，不能证明所有native同步I/O不存在。',
'', '两份录制覆盖稳窗，DataLoss均为0。CPU样本首末覆盖范围、GC/分配/锁/I/O与三段边界见 [Core分析](diagnostic-8/node-jfr-analysis.json)、[客户端分析](diagnostic-8/client-jfr-analysis.json)。同目录保留summary/view、录制SHA-256和配置；view覆盖完整录制，JSON按实际稳窗筛选。',
'', '## 5. 正确性、恢复、失败记录与清理','','所有完整档位均为委托offered=accepted=terminal，请求提交=完成，拒绝/超时业务失败=0；单独排空后unfinished=backlog=0，盘口为空。独立审计每档10,000账户，每户总资金1,000,000,000,000整数单位，总额10,000,000,000,000,000；余额非负，多空逐币对守恒，冻结等于持仓保证金，预约为空，成本/入场价/已实现盈亏一致。',
'', '|档位|请求提交=完成（含排空/行情）|全部运行多头=空头数量（含预热）|非零持仓记录|冻结整数单位|恢复|','|---|---:|---:|---:|---:|---|']
for name,label in zip(names,labels):
 x=r[name];a=x['auditBefore'];lines.append(f'|{label}|{x["submittedRequests"]:,}|{a["longQuantity"]:,}|{a["positions"]:,}|{a["locked"]:,}|'+(f'前后哈希 `{a["businessHash"]}` 一致' if x.get('recovery')=='PASS' else '本档未重启')+'|')
lines+=['','批量4线程与JFR配对8线程均通过真实service/consensus同位置快照配对、原Archive停止重启、等待Leader、全账户状态/业务哈希一致及资金/空盘口复核。Funding、强平、准备金和ADL未在本轮触发，不填deficit=0或自愈通过。',
'', '保留的失败与修正：',
'- 初始普通单预热结束时一次性队列观察未排空而退出，无稳定窗。发压工具增加有30秒上限的边界排空等待，拓扑错误和持续积压仍拒绝；不改变稳定窗业务处理。补充延迟发布/持续积压测试后17项工具测试通过，再从当前master开始完整档位。初次失败目录 `ordinary-4/` 保留。',
'- 批量4线程已完成负载和资金核对后，新的SnapshotEvidence辅助工具漏了JDK27导出参数，抛IllegalAccessError。保留初次错误、完整argv和原结果；补齐参数后只重读既有快照并恢复核验，没有重新发压。恢复通过。',
'', '构建/回归命令：', '```sh', 'mvn -o -B -ntp -pl surprising-aeron-core/surprising-aeron-benchmarks -am \\', '  -Dtest=ClusterCapacityMetricsTest,ClusterAsyncPairTest,ClusterStreamFillCountTest \\', '  -Dsurefire.failIfNoSpecifiedTests=false package', '```',
'初始15项、边界修正后17项，均0失败/错误/跳过，构建退出0；不是本轮全仓回归。完整节点和客户端argv含时间戳保存在 [commands.json](commands.json)，实际入口：',
'```sh', 'python3 run.py ordinary-4-after-boundary-fix 4 --ordinary', 'python3 run.py batch-4 4 --recovery', 'python3 resume-recovery.py batch-4  # 辅助工具参数修复后的恢复续验', 'python3 run.py batch-8 8', 'python3 run.py diagnostic-8 8 --profile --recovery', '```',
'源文件与观察器一并归档；复现应使用新的输出目录、重新编译两个Java辅助类并使用相应当前构建JAR，避免复用已经有账户的数据目录。',
'', '未测：无换页/无限频的正式容量、三成员或跨机网络、生产混合风控/自愈、HTTP/WebSocket及前端、长稳泄漏、完整native与PMU/缓存行证据。不把这一次观测外推到生产，也不与历史不同环境比较依赖升级收益。',
'', '下一步应在无换页/无限频环境、独立发压供给下复测相同拓扑和负载，单因素检查发压端与窗口限制，再判断Core业务饱和。',
'', f'原始运行路径：`{OUT}`。摘要、配置、源码、命令、压缩资源/队列样本及JFR summary/view/SHA-256已归档；原始JFR、节点数据、mmap/Archive与临时编译类的实际清理状态见 [cleanup.json](cleanup.json)。']
(DOC/'REPORT.md').write_text('\n'.join(lines)+'\n')
print(DOC/'REPORT.md')
