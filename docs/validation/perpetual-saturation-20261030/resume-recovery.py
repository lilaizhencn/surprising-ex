import importlib.util,pathlib,json,time,sys,subprocess
root=pathlib.Path(__file__).parent
spec=importlib.util.spec_from_file_location("runner",root/"run.py");r=importlib.util.module_from_spec(spec);spec.loader.exec_module(r)
d=root/sys.argv[1];result=json.loads((d/"result.json").read_text());(d/"result-before-recovery-resume.json").write_text(json.dumps(result,indent=2)+"\n")
cluster=next((d/"data").rglob("recording.log")).parent
old=d/"snapshot-evidence.json"
if old.exists():old.rename(d/"snapshot-evidence-first-attempt.log")
e=r.launch([str(r.JAVA),*r.BASE,"-cp",str(root)+":"+str(r.BENCH),"SnapshotEvidence",str(cluster)],d/"snapshot-evidence.json");r.wait(e,30)
core=None
try:
 core=r.launch(r.node_args(d,False),d/"node-restart.log",d)
 deadline=time.monotonic()+180
 while time.monotonic()<deadline:
  if core.poll() is not None:raise RuntimeError("restart exited")
  if "role-change productLine=LINEAR_PERPETUAL role=LEADER" in (d/"node-restart.log").read_text():break
  time.sleep(1)
 else:raise RuntimeError("leader deadline")
 result["auditAfter"]=r.audit(d,"after")
 if result["auditAfter"]!=result["auditBefore"]:raise RuntimeError("recovery business-state mismatch")
 v=r.launch(r.client_args(d,result["workers"],verify=True),d/"verify-recovery.log");r.wait(v,180)
 if "capacityVerify=PASS" not in (d/"verify-recovery.log").read_text():raise RuntimeError("funds/book verification missing")
 result["recovery"]="PASS";result["recoveryToolRetry"]={"initialError":result.get("error"),"resolution":"add JDK exports to read-only SnapshotEvidence helper; no offered load rerun"};result["status"]="PASS";result.pop("error",None)
finally:
 r.stop(core)
 (d/"result.json").write_text(json.dumps(result,indent=2)+"\n")
print(json.dumps({"stage":d.name,"recovery":result.get("recovery"),"hash":result.get("auditAfter",{}).get("businessHash")}),flush=True)
