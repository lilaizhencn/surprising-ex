import pathlib,shlex,subprocess,time,os
repo=pathlib.Path('/Users/atomex/Desktop/surprising/surprising-ex')
base=repo/'surprising-aeron-core/surprising-aeron-benchmarks/target/aeron-async-stages'
old=base/'m5pro-20261103-orders/window-256/end_to_end'
root=base/'m5pro-20261103-window-sweep';root.mkdir(exist_ok=True)
for mode, core, window in [(m,c,w) for m in ['MATCH_STREAM','MATCH_BATCH_STREAM'] for c,w in [(256,1024),(1024,1024),(2048,2048)]]:
 d=root/f'{mode}-core{core}-client{window}';d.mkdir(exist_ok=True)
 args=[s.replace(str(old),str(d)) for s in shlex.split((old/'node.command').read_text())]
 args=[a.replace('owner-command-window=256',f'owner-command-window={core}') for a in args]
 for name in ['data','tmp','aeron']: (d/name).mkdir(exist_ok=True)
 (d/'node.command').write_text(shlex.join(args))
 with open(d/'node.log','w') as out:
  node=subprocess.Popen(args,stdout=out,stderr=subprocess.STDOUT,cwd=d)
  try:
   time.sleep(10)
   java=args[0]; flags=args[1:7]
   client=[java,*flags,'-Xms128m','-Xmx512m','-XX:+UseG1GC','-Dsurprising.aeron.product-line=LINEAR_PERPETUAL','-Dsurprising.aeron.hostnames=127.0.0.1','-Dsurprising.aeron.egress-hostname=127.0.0.1',f'-Daeron.dir={d}/client-aeron',f'-Dsurprising.aeron.capacity-async-in-flight={window}',f'-Dsurprising.aeron.capacity-session-in-flight={window}','-Dsurprising.aeron.capacity-workers=1','-Dsurprising.aeron.capacity-connections=1','-Dsurprising.aeron.capacity-instrumentId-count=256','-Dsurprising.aeron.capacity-user-count=100','-Dsurprising.aeron.capacity-warmup-seconds=30','-Dsurprising.aeron.capacity-duration-seconds=60',f'-Dsurprising.aeron.capacity-workload={mode}','-cp',str(repo/'surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar'),'com.surprising.aeron.benchmarks.workload.ClusterCapacityMain']
   (d/'client.command').write_text(shlex.join(client))
   with open(d/'client.log','w') as log: result=subprocess.run(client,stdout=log,stderr=subprocess.STDOUT)
   print(mode,core,window,result.returncode,flush=True)
   if result.returncode: raise RuntimeError('client failed')
  finally:
   node.terminate()
   try:node.wait(timeout=15)
   except subprocess.TimeoutExpired:node.kill();node.wait()
