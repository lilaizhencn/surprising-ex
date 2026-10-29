import os,sys,time,json,subprocess,pathlib,datetime
root=pathlib.Path('/tmp/surprising-affiliate-teams-20261029');label=sys.argv[1];teams=int(sys.argv[2]);clients=int(sys.argv[3]);profile='jfr' in sys.argv[4:];selected='SPOT' if 'SPOT' in sys.argv[4:] else None
cp=(root/'classpath').read_text().strip();java=['java','--enable-native-access=ALL-UNNAMED','--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED','--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED','-Xms256m','-Xmx2g']
if profile:java+=['-XX:StartFlightRecording=filename='+str(root/(label+'.jfr'))+',settings=profile,maxsize=64m,dumponexit=true']
java+=['-cp','target/test-classes:target/classes:'+cp,'com.surprising.affiliate.AffiliateTeamWorkload',str(teams),str(clients),str(root/(label+'.json')),'1200']
if selected:java += [selected]
def command(args):return subprocess.run(args,text=True,capture_output=True).stdout.strip()
start_utc=datetime.datetime.now(datetime.timezone.utc).isoformat();start=time.monotonic();before=command(['vm_stat']);samples=[]
env=os.environ.copy();env.update(INSTRUMENT_TEST_JDBC_URL='jdbc:postgresql://localhost:5432/surprising_affiliate_teams_20261029',INSTRUMENT_TEST_DB_USER='atomex',INSTRUMENT_TEST_DB_PASSWORD='')
with (root/(label+'.log')).open('w') as log:
 process=subprocess.Popen(java,cwd='/Users/atomex/Desktop/surprising/surprising-ex/surprising-gateway',env=env,stdout=log,stderr=subprocess.STDOUT)
 while process.poll() is None:
  samples.append({'seconds':time.monotonic()-start,'rss_kib_and_process_cpu_percent':command(['ps','-p',str(process.pid),'-o','rss=,pcpu=']),'thermal':command(['pmset','-g','therm'])})
  if time.monotonic()-start>1700:process.terminate();break
  time.sleep(2)
 code=process.wait(timeout=30)
report={'run':label,'pid':process.pid,'exit_code':code,'elapsed_seconds':time.monotonic()-start,'teams':teams,'clients_per_team':clients,'jfr':profile,'selected_product':selected,'start_utc':start_utc,'end_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'vm_before':before,'vm_after':command(['vm_stat']),'samples':samples,'command':'java --enable-native-access=ALL-UNNAMED --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED -Xms256m -Xmx2g '+('JFR profile maxsize64m ' if profile else '')+'-cp <current gateway test/main/dependencies> com.surprising.affiliate.AffiliateTeamWorkload '+str(teams)+' '+str(clients)+' <result.json> 1200'}
(root/(label+'-resources.json')).write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k!='samples' and not k.startswith('vm_')}));sys.exit(code)
