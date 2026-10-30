import collections,datetime,hashlib,json,math,pathlib,re,subprocess,sys,concurrent.futures
OUT=pathlib.Path(__file__).parent
JFR='/Users/atomex/.sdkman/candidates/java/27.0.0-amzn/bin/jfr'
def stamp(s):return datetime.datetime.fromisoformat(s.replace('Z','+00:00')).timestamp()*1000

def duration(s):
 m=re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:([\d.]+)S)?',str(s))
 return (int(m[1] or 0)*3600+int(m[2] or 0)*60+float(m[3] or 0)) if m else 0

def distribution(values):
 a=sorted(values)
 return {'samples':len(a),**{name:(a[min(len(a)-1,max(0,math.ceil(q*len(a))-1))] if a else None) for name,q in [('p50',.5),('p90',.9),('p95',.95),('p99',.99),('p999',.999),('max',1)]}}

def thread(v):return (v.get('sampledThread') or v.get('eventThread') or v.get('thread') or {}).get('javaName','unknown')
def frames(v):
 return [f.get('method',{}).get('type',{}).get('name','?')+'.'+f.get('method',{}).get('name','?')+':'+str(f.get('lineNumber','?')) for f in (v.get('stackTrace') or {}).get('frames',[])]

def compact(value):
 if isinstance(value,list):return [compact(x) for x in value]
 if not isinstance(value,dict):return value
 result={}
 for k,v in value.items():
  if k in ['classLoader','package','modifiers','hidden','truncated','group','osName','state','compiler','bytecodeIndex','frameType']:continue
  if k in ['type','objectClass','monitorClass'] and isinstance(v,dict) and 'name' in v:result[k]={'name':v['name']}
  else:result[k]=compact(v)
 return result

def streamed_events(args):
 p=subprocess.Popen(args,stdout=subprocess.PIPE,text=True)
 decoder=json.JSONDecoder();buffer='';started=False
 while True:
  chunk=p.stdout.read(65536)
  if chunk:buffer+=chunk
  if not started:
   match=re.search(r'"events"\s*:\s*\[',buffer)
   if match:buffer=buffer[match.end():];started=True
   elif not chunk:break
   else:continue
  while True:
   buffer=buffer.lstrip(' \r\n\t,')
   if not buffer or buffer.startswith(']'):break
   try:event,end=decoder.raw_decode(buffer)
   except json.JSONDecodeError:break
   yield event;buffer=buffer[end:]
  if not chunk:break
 p.stdout.close()
 if p.wait()!=0:raise RuntimeError('jfr print failed')

def analyze(recording,result):
 role=recording.stem;d=recording.parent;start=result['windowStartMs'];end=result['windowEndMs']
 summary=subprocess.check_output([JFR,'summary',str(recording)],text=True);(d/(role+'-jfr-summary.txt')).write_text(summary)
 counts={k:int(v) for k,v in re.findall(r'^\s*(\S+)\s+(\d+)\s+\d+\s*$',summary,re.M)}
 info={'role':role,'sha256':hashlib.file_digest(recording.open('rb'),'sha256').hexdigest(),'bytes':recording.stat().st_size,'windowStartMs':start,'windowEndMs':end,'eventCountsFullRecording':counts}
 def events(names,full=False):
  names=[n for n in names if counts.get(n,0)>0]
  if not names:return []
  print('reading '+role+' '+','.join(names),flush=True)
  return [compact(e) for e in streamed_events([JFR,'print','--json','--stack-depth','12','--events',','.join(names),str(recording)]) if full or start<=stamp(e['values']['startTime'])<=end]
 info['dataLossEvents']=len(events(['jdk.DataLoss'],True))
 pool=concurrent.futures.ThreadPoolExecutor(max_workers=3)
 memory_future=pool.submit(events,['jdk.ThreadAllocationStatistics','jdk.ObjectAllocationSample','jdk.DirectBufferStatistics','jdk.NativeMemoryUsage','jdk.GCHeapSummary','jdk.GCPhasePause','jdk.GarbageCollection'])
 io_future=pool.submit(events,['jdk.FileRead','jdk.FileWrite','jdk.SocketRead','jdk.SocketWrite'])
 other_future=pool.submit(events,['jdk.JavaMonitorEnter','jdk.ThreadPark','jdk.SafepointBegin','jdk.Compilation','jdk.Deoptimization','jdk.JavaExceptionThrow','jdk.JavaErrorThrow'])
 boundary_future=pool.submit(events,['surprising.ClientTransportBoundary'])
 cpu=events(['jdk.ExecutionSample','jdk.NativeMethodSample','jdk.CPULoad','jdk.ThreadCPULoad']);stacks=collections.Counter();perthread=collections.Counter();load=[]
 for e in cpu:
  v=e['values']
  if e['type'] in ['jdk.ExecutionSample','jdk.NativeMethodSample']:
   fs=frames(v);stacks[(thread(v),' | '.join(fs[:5]))]+=1;perthread[thread(v)]+=1
  elif e['type']=='jdk.CPULoad':load.append({'epochMillis':stamp(v['startTime']),**{k:v[k] for k in ['jvmUser','jvmSystem','machineTotal']}})
 info['cpu']={'samplesByThread':dict(perthread),'topStacks':[{'thread':k[0],'stack':k[1],'samples':n} for k,n in stacks.most_common(25)],'load':load}
 memory=memory_future.result()
 alloc=collections.defaultdict(list);classes=collections.Counter();gc=[];direct=[];native=[];heap=[]
 for e in memory:
  v=e['values'];t=e['type']
  if t=='jdk.ThreadAllocationStatistics':alloc[thread(v)].append((stamp(v['startTime']),v['allocated']))
  elif t=='jdk.ObjectAllocationSample':classes[v['objectClass']['name']]+=v['weight']
  elif t=='jdk.GCPhasePause':gc.append(duration(v['duration'])*1000)
  elif t=='jdk.DirectBufferStatistics':direct.append(v)
  elif t=='jdk.NativeMemoryUsage':native.append(v)
  elif t=='jdk.GCHeapSummary':heap.append(v['heapUsed'])
 allocrows=[]
 for name,vals in alloc.items():
  vals.sort()
  if len(vals)>1:allocrows.append({'thread':name,'bytes':vals[-1][1]-vals[0][1],'coveredMs':vals[-1][0]-vals[0][0],'firstMs':vals[0][0],'lastMs':vals[-1][0]})
 info['memory']={'threadAllocationPartialWindow':allocrows,'partialWindowAllocatedBytes':sum(x['bytes'] for x in allocrows),'allocationSampleWeightsTop':classes.most_common(15),'gcPauseMs':sum(gc),'gcPauseCount':len(gc),'gcPauseMaxMs':max(gc,default=0),'gcPausePercent':sum(gc)/(end-start)*100,'directBufferStats':direct,'nativeMemoryEvents':native,'heapUsedMin':min(heap,default=None),'heapUsedMax':max(heap,default=None),'allocationLimit':'periodic first/last counters inside measurement omit edges; not an exact full-window B/item measurement'}
 io=io_future.result();groups=collections.defaultdict(lambda:{'count':0,'bytes':0,'durationMs':0,'maxMs':0});owner=[]
 for e in io:
  v=e['values'];name=thread(v);g=groups[(e['type'],name)];ms=duration(v['duration'])*1000
  g['count']+=1;g['bytes']+=v.get('bytesWritten',v.get('bytesRead',0));g['durationMs']+=ms;g['maxMs']=max(ms,g['maxMs'])
  if 'clustered-service' in name:owner.append({'type':e['type'],'thread':name,'durationMs':ms,'bytes':v.get('bytesWritten',v.get('bytesRead',0)),'path':v.get('path'),'stack':frames(v)})
 info['io']={'byThread':[{'type':k[0],'thread':k[1],**v} for k,v in groups.items()],'ownerEvents':owner,'scope':'Java JFR events; native UDP/JNI I/O may not be visible'}
 other=other_future.result();types=collections.Counter(e['type'] for e in other)
 locks=collections.defaultdict(lambda:{'count':0,'totalMs':0,'maxMs':0})
 for e in other:
  if e['type']=='jdk.JavaMonitorEnter':
   v=e['values'];g=locks[(thread(v),v.get('monitorClass',{}).get('name','?'))];ms=duration(v['duration'])*1000;g['count']+=1;g['totalMs']+=ms;g['maxMs']=max(g['maxMs'],ms)
 info['other']={'eventCountsStableWindow':dict(types),'locks':[{'thread':k[0],'class':k[1],**v} for k,v in sorted(locks.items(),key=lambda x:-x[1]['totalMs'])[:15]]}
 boundary=boundary_future.result();pool.shutdown();paired=collections.defaultdict(dict)
 for e in boundary:
  v=e['values'];paired[(v['commandIdHigh'],v['commandIdLow'])][v['stage']]=v['observedNanos']
 full=[v for v in paired.values() if all(k in v for k in ['queued','offered','delivered'])]
 info['latencyBoundariesMicros']={name:distribution([(v[b]-v[a])/1000 for v in full]) for name,a,b in [('queuedToOffered','queued','offered'),('offeredToDelivered','offered','delivered'),('queuedToDelivered','queued','delivered')]};info['boundaryUnpaired']=len(paired)-len(full)
 info['cpuCoverage']={'firstMs':min([stamp(e['values']['startTime']) for e in cpu],default=None),'lastMs':max([stamp(e['values']['startTime']) for e in cpu],default=None)}
 (d/(role+'-jfr-analysis.json')).write_text(json.dumps(info,indent=2)+'\n')
 for view in ['hot-methods','allocation-by-site','contention-by-thread','gc','safepoints']:
  with (d/(role+'-jfr-'+view+'.txt')).open('w') as f:subprocess.run([JFR,'view','--width','220',view,str(recording)],stdout=f,stderr=subprocess.STDOUT,check=True)
 print(json.dumps({'role':role,'dataLoss':info['dataLossEvents'],'gcPauseMs':sum(gc),'allocationPartialBytes':info['memory']['partialWindowAllocatedBytes'],'topStacks':info['cpu']['topStacks'][:4]}),flush=True)
 return info

if __name__=='__main__':
 d=OUT/sys.argv[1];result=json.loads((d/'result.json').read_text())
 for role in ['node','client']:analyze(d/(role+'.jfr'),result)
