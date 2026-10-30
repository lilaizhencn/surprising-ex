import collections,hashlib,json,pathlib,re,sys
OUT=pathlib.Path(__file__).parent

def metric(d):
 r=json.loads((d/('parsed-workload.json' if d.name=='load-4' else 'result.json')).read_text());w=(r.get('windowStartMs'),r.get('windowEndMs'))
 allrows=[json.loads(s) for s in (d/'resources.jsonl').read_text().splitlines()]
 rows=[x for x in allrows if not w[0] or w[0]<=x['epochMillis']<=w[1]]
 r['environment']={'sampleCount':len(rows)}
 if rows:
  def value(x,name):
   m=re.search(re.escape(name)+r':\s*(\d+)',x['vm']);return int(m[1]) if m else None
  speeds=[int(m[1]) for x in rows if (m:=re.search(r'CPU_Speed_Limit\s*=\s*(\d+)',x['thermal']))]
  before=[x for x in allrows if x['epochMillis']<=w[0]] if w[0] else []
  after=[x for x in allrows if x['epochMillis']>=w[1]] if w[1] else []
  if before and after:r['environment']['bracket']={'firstMs':before[-1]['epochMillis'],'lastMs':after[0]['epochMillis'],'swapinsPages':value(after[0],'Swapins')-value(before[-1],'Swapins'),'swapoutsPages':value(after[0],'Swapouts')-value(before[-1],'Swapouts')}
  r['environment'].update({'firstSampleMs':rows[0]['epochMillis'],'lastSampleMs':rows[-1]['epochMillis'],'swapinsPages':value(rows[-1],'Swapins')-value(rows[0],'Swapins'),'swapoutsPages':value(rows[-1],'Swapouts')-value(rows[0],'Swapouts'),'cpuSpeedLimitMin':min(speeds,default=None),'cpuSpeedLimitMax':max(speeds,default=None),'swapAtFirstSample':rows[0]['swap'],'swapAtLastSample':rows[-1]['swap']})
  proc=collections.defaultdict(list)
  for x in rows:
   for line in x['ps'].splitlines():
    vals=line.split()
    if len(vals)>=3:proc[int(vals[0])].append((int(vals[1]),float(vals[2])))
  for role in ['node','client','observer']:
   vals=proc.get(r.get(role+'Pid'),[])
   if vals:r['environment'][role]={'rssMaxKiB':max(a for a,b in vals),'cpuPercentAvg':sum(b for a,b in vals)/len(vals),'cpuPercentMax':max(b for a,b in vals)}
  r['performanceValidity']='INVALID' if r['environment']['swapinsPages'] or r['environment']['swapoutsPages'] or min(speeds,default=100)<100 else 'ENVIRONMENT_SAMPLES_ONLY'
 q=[json.loads(s) for s in (d/'queues.jsonl').read_text().splitlines() if s.startswith('{')]
 q=[x for x in q if not w[0] or w[0]<=x['epochMillis']<=w[1]]
 r['queues']={'samples':len(q),'nonzeroDispatch':sum(x['dispatchDepth']>0 for x in q),'nonzeroCompletion':sum(x['completionDepth']>0 for x in q),'nonzeroContext':sum(x['contextDepth']>0 for x in q),'nonzeroAccount':sum(any(y>0 for y in x['accountDepths']) for x in q),'maxQueryNanos':max((x['queryNanos'] for x in q),default=None),'limitations':['100ms target query polling; actual delays include transport/CPU stalls','queue snapshots may miss short bursts; zero samples do not prove no backlog','account execution time metrics returned zero; not evidence of zero execution time']}
 if len(q)>1:
  elapsed=(q[-1]['epochMillis']-q[0]['epochMillis'])/1000
  changes=[b-a for a,b in zip(q[0]['accountOperations'],q[-1]['accountOperations'])]
  r['clearingObserved']={'firstMs':q[0]['epochMillis'],'lastMs':q[-1]['epochMillis'],'seconds':elapsed,'countsByLaneAndType':changes,'operationTypes':['COMMAND','SETTLEMENT','QUERY','RISK'],'completedPerSecond':sum(changes)/elapsed,'committedCoreSequenceDelta':q[-1].get('committedCoreSequence',0)-q[0].get('committedCoreSequence',0),'committedCoreSequencePerSecond':(q[-1].get('committedCoreSequence',0)-q[0].get('committedCoreSequence',0))/elapsed,'settlementPerSecond':sum(changes[1::4])/elapsed,'scope':'difference between periodic query snapshots inside measurement, not the exact stable-window total'}
 if q:r['queues']['peaks']={key:max(x.get(key,0) for x in q) for key in ('dispatchDepth','completionDepth','contextDepth')}
 if r.get('pass'):
  # In a balanced stream every fill consumes two orders; window edges may split pairs.
  r['businessView']={'ordersPerSecond':r['businessOpsPerSec'],'stableOrders':r['terminalBusinessOperations'],'steadyMatchedTradesPerSecond':None,'matchedTradesNote':'actual fill count reported for measurement+drain only; stable-window fill delta unavailable; do not divide order TPS by two and call it measured trades TPS','matchedTradesIncludingDrain':r['matches'],'cancelPerSecond':0,'businessOpsPerSecondIncludingMark':None,'aeronAppMessagesPerSecondStable':None,'aeronAppMessagesIncludingDrain':r['totalTerminalCoreMessages'],'payloadMBPerSecond':None}
 else:
  text=(d/'client.log').read_text();r['failureReasons']=sorted(set(re.findall(r'resultCode=([A-Z_]+)',text)))
  r['performanceValidity']='NO_STABLE_WINDOW'
 return r


if __name__ == '__main__':
 rows=[metric(p) for p in sorted(OUT.iterdir()) if p.is_dir() and (p/'result.json').exists()]
 result={'runId':OUT.name,'plan':json.loads((OUT/'plan.json').read_text()),'stages':rows,'scope':'current master, one real loopback Aeron member, one matcher, two account lanes; one pass per stage'}
 (OUT/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 for r in rows:print(json.dumps({'stage':r['name'],'ordersPerSecond':r.get('businessOpsPerSec'),'environment':r['environment'],'queues':r['queues'],'status':r['status'],'failureReasons':r.get('failureReasons')},ensure_ascii=False))
