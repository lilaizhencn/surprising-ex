import datetime,json,pathlib,re,subprocess,sys,time,threading,hashlib,signal
REPO=pathlib.Path('/Users/atomex/Desktop/surprising/surprising-ex')
OUT=pathlib.Path(__file__).parent
JAVA=pathlib.Path('/Users/atomex/.sdkman/candidates/java/27.0.0-amzn/bin/java')
SERVICE=REPO/'surprising-aeron-core/surprising-aeron-service/target/surprising-aeron-service.jar'
BENCH=REPO/'surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar'
PROFILE=OUT/'owner-commit-profile.jfc'
BASE=['--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED','--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED','--add-opens=java.base/sun.nio.ch=ALL-UNNAMED','--add-opens=java.base/java.util.zip=ALL-UNNAMED','--enable-native-access=ALL-UNNAMED','-XX:+UseG1GC','-XX:+DisableExplicitGC','-Dsurprising.aeron.product-line=LINEAR_PERPETUAL','-Dsurprising.aeron.hostnames=127.0.0.1','-Dsurprising.aeron.egress-hostname=127.0.0.1','-Dsurprising.aeron.account-lanes=2','-Dsurprising.aeron.matching-engines=1']
commands=json.loads((OUT/'commands.json').read_text()) if (OUT/'commands.json').exists() else []; processes=[]
def output(msg):print(msg,flush=True)
def capture(args):
 p=subprocess.run(args,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=15);return p.stdout

def launch(argv,log,cwd=None):
 commands.append({'argv':list(map(str,argv)),'log':str(log),'epochMillis':time.time_ns()//1000000})
 (OUT/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
 h=open(log,'w');p=subprocess.Popen(list(map(str,argv)),stdout=h,stderr=subprocess.STDOUT,cwd=cwd);p._log=h;processes.append(p);return p

def stop(p,timeout=30):
 if p is None:return
 if p.poll() is None:
  p.terminate()
  try:p.wait(timeout=timeout)
  except subprocess.TimeoutExpired:p.kill();p.wait();output('FORCED_STOP '+str(p.pid))
 p._log.close()

def wait(p,timeout):
 try:code=p.wait(timeout=timeout)
 except subprocess.TimeoutExpired:stop(p);raise RuntimeError('timeout pid='+str(p.pid))
 p._log.close()
 if code:raise RuntimeError('process failed '+str(code)+' pid='+str(p.pid))

def monitor(d,pids,done):
 with (d/'resources.jsonl').open('w') as f:
  while not done.is_set():
   record={'epochMillis':time.time_ns()//1000000,'vm':capture(['vm_stat']),'swap':capture(['sysctl','vm.swapusage']),'thermal':capture(['pmset','-g','therm']),'ps':capture(['ps','-p',','.join(str(p) for p in pids if p),'-o','pid=,rss=,%cpu=,etime='])}
   f.write(json.dumps(record)+'\n');f.flush();done.wait(2)

def node_args(d,profile=False):
 args=[str(JAVA),*BASE,'-Xms512m','-Xmx1536m','-XX:+AlwaysPreTouch','-Dsurprising.aeron.node-id=0','-Dsurprising.aeron.owner-command-window=256','-Dsurprising.aeron.owner-input-batch-size=64','-Dsurprising.aeron.core.threading-mode=SHARED_NETWORK','-Dsurprising.aeron.service.idle-strategy=YIELDING','-Dsurprising.aeron.settlement-wait-strategy=BUSY_SPIN','-Dsurprising.aeron.matcher-pipeline-wait-strategy=BUSY_SPIN','-Dsurprising.aeron.owner-wait-strategy=BUSY_SPIN',f'-Dsurprising.aeron.data-dir={d}/data',f'-Daeron.dir={d}/aeron',f'-Djava.io.tmpdir={d}/tmp']
 if profile:args+=['-XX:NativeMemoryTracking=summary',f'-XX:StartFlightRecording=settings={PROFILE},filename={d}/node.jfr,maxsize=256m,dumponexit=true',f'-Xlog:gc*,safepoint:file={d}/node-gc.log:time,uptime,level,tags']
 return [*args,'-jar',str(SERVICE)]

def client_args(d,workers,profile=False,verify=False):
 args=[str(JAVA),*BASE,'-Xms128m','-Xmx512m',f'-Daeron.dir={d}/client-aeron',f'-Djava.io.tmpdir={d}/tmp','-Dsurprising.aeron.capacity-workload='+('MATCH_STREAM' if '--ordinary' in sys.argv else 'MATCH_BATCH_STREAM'),'-Dsurprising.aeron.capacity-batch-size=20','-Dsurprising.aeron.capacity-instrumentId-count=256','-Dsurprising.aeron.capacity-user-count=10000',f'-Dsurprising.aeron.capacity-workers={workers}','-Dsurprising.aeron.capacity-connections=4','-Dsurprising.aeron.capacity-async-in-flight=256','-Dsurprising.aeron.capacity-seed=9901','-Dsurprising.aeron.capacity-warmup-seconds=30','-Dsurprising.aeron.capacity-duration-seconds=60','-Dsurprising.aeron.capacity-offered-commands-per-second=0']
 if verify:args+=['-Dsurprising.aeron.capacity-mode=verify']
 if profile:args+=['-XX:NativeMemoryTracking=summary',f'-XX:StartFlightRecording=settings={PROFILE},filename={d}/client.jfr,maxsize=256m,dumponexit=true',f'-Xlog:gc*,safepoint:file={d}/client-gc.log:time,uptime,level,tags']
 return [*args,'-cp',str(BENCH),'com.surprising.aeron.benchmarks.workload.ClusterCapacityMain']

def observer_args(d,mode):
 return [str(JAVA),*BASE,'-Xms64m','-Xmx128m',f'-Daeron.dir={d}/observer-aeron','-cp',str(OUT)+':'+str(BENCH),'SaturationObserver',mode,str(d/'observer.stop')]

def parse(log):
 text=log.read_text();row={}
 for line in text.splitlines():
  if any(s in line for s in ['capacity=PASS','steadyCapacity ','drain elapsed','marketDataCommands=','totalWindow=','requestOccupancySample','warmupMatches=','actualLaneMetrics','transientOfferRetries=','ordersPerRequest=']):
   prefix='drain_' if 'drain elapsed' in line else ''
   for k,v in re.findall(r'([A-Za-z][A-Za-z0-9]*)=([^\s]+)',line):
    k=prefix+k
    try:row[k]=float(v) if '.' in v else int(v)
    except ValueError:row[k]=v
 start=re.search(r'measurementStartEpochMillis=(\d+) measurementEndEpochMillis=(\d+)',text)
 if start:row['windowStartMs'],row['windowEndMs']=map(int,start.groups())
 row['pass']='capacity=PASS' in text and 'steadyCapacity ' in text
 return row

def audit(d,suffix):
 p=launch(observer_args(d,'audit'),d/('audit-'+suffix+'.log'),d);wait(p,240)
 m=re.search(r'AUDIT (\{.*\})',(d/('audit-'+suffix+'.log')).read_text())
 if not m:raise RuntimeError('audit output missing')
 return json.loads(m.group(1))

def run_stage(name,workers,profile=False,recovery=False):
 d=OUT/name;d.mkdir()
 for sub in ['data','aeron','tmp']:(d/sub).mkdir()
 row={'name':name,'workers':workers,'profile':profile,'startEpochMillis':time.time_ns()//1000000,'workload':'MATCH_STREAM' if '--ordinary' in sys.argv else 'MATCH_BATCH_STREAM','batchSize':1 if '--ordinary' in sys.argv else 20,'connections':4,'sourceCommit':capture(['git','-C',str(REPO),'rev-parse','HEAD']).strip()}
 core=None;obs=None;done=threading.Event();mon=None
 try:
  output('START '+name)
  core=launch(node_args(d,profile),d/'node.log',d);time.sleep(10)
  if core.poll() is not None:raise RuntimeError('node start failed')
  obs=launch(observer_args(d,'metrics'),d/'queues.jsonl',d)
  client=launch(client_args(d,workers,profile),d/'client.log')
  row.update({'nodePid':core.pid,'clientPid':client.pid,'observerPid':obs.pid})
  mon=threading.Thread(target=monitor,args=(d,[core.pid,client.pid,obs.pid],done),daemon=True);mon.start()
  wait(client,300);row.update(parse(d/'client.log'))
  if not row['pass']:raise RuntimeError('capacity PASS missing')
  (d/'observer.stop').touch();wait(obs,40);obs=None
  row['auditBefore']=audit(d,'before')
  if row['auditBefore']['longQuantity']!=row['totalRunMatches']:raise RuntimeError('actual quantity vs fill counters')
  if profile:
   for label,pid in [('node',core.pid)]:
    for cmd in [['Thread.print','-l'],['VM.native_memory','summary'],['GC.heap_info']]:
     (d/(label+'-'+cmd[0]+'.txt')).write_text(capture([str(JAVA.parent/'jcmd'),str(pid),*cmd]))
  if recovery:
   clusters=list((d/'data').rglob('recording.log'))
   if len(clusters)!=1:raise RuntimeError('cannot identify cluster recording log '+str(clusters))
   cluster=clusters[0].parent
   tool=launch([str(JAVA),*BASE,'-cp',str(SERVICE),'io.aeron.cluster.ClusterTool',str(cluster),'snapshot'],d/'snapshot-request.log');wait(tool,30)
   time.sleep(15)
   row['snapshotRequestLog']=(d/'snapshot-request.log').read_text()
   evidence=launch([str(JAVA),'-cp',str(OUT)+':'+str(BENCH),'SnapshotEvidence',str(cluster)],d/'snapshot-evidence.json');wait(evidence,30)
   stop(core);core=None;time.sleep(12)
   core=launch(node_args(d,False),d/'node-restart.log',d)
   ready=time.monotonic()+180
   while time.monotonic()<ready:
    if core.poll() is not None:raise RuntimeError('restarted node exited')
    if 'role-change productLine=LINEAR_PERPETUAL role=LEADER' in (d/'node-restart.log').read_text():break
    time.sleep(1)
   else:raise RuntimeError('restart did not reach leader within 180 seconds')
   row['auditAfter']=audit(d,'after')
   if row['auditAfter']!=row['auditBefore']:raise RuntimeError('recovery audit mismatch')
   verify=launch(client_args(d,workers,verify=True),d/'verify-recovery.log');wait(verify,180)
   if 'capacityVerify=PASS' not in (d/'verify-recovery.log').read_text():raise RuntimeError('recovery funds/book missing')
   row['recovery']='PASS'
  row['status']='PASS'
 except Exception as e:
  row['status']='FAIL';row['error']=str(e);output('FAIL '+name+' '+str(e))
 finally:
  (d/'observer.stop').touch()
  if obs:
   try:wait(obs,35)
   except Exception:stop(obs)
  stop(core)
  done.set()
  if mon:mon.join(timeout=20)
  row['finishEpochMillis']=time.time_ns()//1000000
  (d/'result.json').write_text(json.dumps(row,ensure_ascii=False,indent=2)+'\n')
  output(json.dumps(row,ensure_ascii=False))
 return row

if __name__=='__main__':
 if sys.argv[1]=='compile':
  subprocess.run([str(JAVA.parent/'javac'),'-cp',str(BENCH),'-d',str(OUT),str(OUT/'SaturationObserver.java')],check=True)
 else:
  name=sys.argv[1];workers=int(sys.argv[2]);result=run_stage(name,workers,'--profile' in sys.argv,'--recovery' in sys.argv)
  sys.exit(0 if result['status']=='PASS' else 1)
