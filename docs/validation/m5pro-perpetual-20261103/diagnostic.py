import pathlib,shlex,subprocess,time,os
repo=pathlib.Path('/Users/atomex/Desktop/surprising/surprising-ex')
base=repo/'surprising-aeron-core/surprising-aeron-benchmarks/target/aeron-async-stages'
old=base/'m5pro-20261103-orders/window-256/end_to_end'
root=base/'m5pro-20261103-diagnostic';root.mkdir(exist_ok=True)
for mode in ['MATCH_BATCH_STREAM']:
 d=root/mode;d.mkdir(exist_ok=True)
 args=[s.replace(str(old),str(d)) for s in shlex.split((old/'node.command').read_text())]
 args.insert(1,f'-XX:StartFlightRecording=filename={d}/node.jfr,settings=profile,dumponexit=true,maxsize=128m')
 for name in ['data','tmp','aeron']: (d/name).mkdir(exist_ok=True)
 (d/'node.command').write_text(shlex.join(args))
 with open(d/'node.log','w') as out:
  node=subprocess.Popen(args,stdout=out,stderr=subprocess.STDOUT,cwd=d)
  try:
   time.sleep(10)
   java=args[0]; flags=[a for a in args if a.startswith('--')]
   client=[java,*flags,'-Xms128m','-Xmx512m','-XX:+UseG1GC','-Dsurprising.aeron.product-line=LINEAR_PERPETUAL','-Dsurprising.aeron.hostnames=127.0.0.1','-Dsurprising.aeron.egress-hostname=127.0.0.1',f'-Daeron.dir={d}/client-aeron','-Dsurprising.aeron.capacity-async-in-flight=256','-Dsurprising.aeron.capacity-session-in-flight=256','-Dsurprising.aeron.capacity-workers=1','-Dsurprising.aeron.capacity-connections=1','-Dsurprising.aeron.capacity-instrumentId-count=256','-Dsurprising.aeron.capacity-user-count=100','-Dsurprising.aeron.capacity-warmup-seconds=30','-Dsurprising.aeron.capacity-duration-seconds=30',f'-Dsurprising.aeron.capacity-workload={mode}','-cp',str(repo/'surprising-aeron-core/surprising-aeron-benchmarks/target/product-core-benchmarks.jar'),'com.surprising.aeron.benchmarks.workload.ClusterCapacityMain']
   (d/'client.command').write_text(shlex.join(client))
   with open(d/'client.log','w') as log: result=subprocess.run(client,stdout=log,stderr=subprocess.STDOUT)
   print(mode,result.returncode,flush=True)
  finally:
   node.terminate()
   try:node.wait(timeout=15)
   except subprocess.TimeoutExpired:node.kill();node.wait()
