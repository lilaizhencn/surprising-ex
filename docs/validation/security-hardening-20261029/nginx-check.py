import http.client,json,os,pathlib,socket,ssl,subprocess,time,uuid
root=pathlib.Path(os.environ['SECURITY_QA_DIR'])
context=ssl._create_unverified_context()
steps=[]
def call(method,path,source=None,headers=None):
 c=http.client.HTTPSConnection('127.0.0.1',17445,context=context,timeout=5,source_address=(source,0) if source else None)
 c.request(method,path,headers=headers or {});r=c.getresponse();data=r.read();result=(r.status,dict((k.lower(),v)for k,v in r.getheaders()),data);c.close();return result
status,headers,_=call('GET','/healthz');assert status==503,status;steps.append({'case':'local readiness status preserved','status':status})
source='127.0.0.2'
try:
 with socket.socket() as s:s.bind((source,0))
except OSError:
 source=subprocess.check_output(['/usr/sbin/ipconfig','getifaddr','en0'],text=True).strip()
status,_,_=call('GET','/healthz',source);assert status==403,status;steps.append({'case':'non-loopback peer denied health','status':status})
status,_,_=call('POST','/api/v1/auth/login',headers={'Content-Length':str(1024*1024+1)});assert status==413,status;steps.append({'case':'ordinary request body limit','status':status})
for path in ['/internal/test','/api/v1/accounts/test','/actuator/env']:
 status,_,_=call('GET',path);assert status==404,(path,status)
steps.append({'case':'internal routes not published','status':404})
status,_,_=call('GET','/api/v1/%61dmin/users/1');assert status==400,status;steps.append({'case':'encoded path rejected through proxy','status':status})
status,headers,_=call('GET','/admin/');assert status==200,status
for key in ['content-security-policy','strict-transport-security','x-content-type-options','referrer-policy']:
 assert key in headers,key
assert "script-src 'self';" in headers['content-security-policy'];steps.append({'case':'admin CSP and response headers','status':status})
status,headers,_=call('GET','/__qa_rate_limit',headers={'Origin':'https://ex.tokdou.com'});assert status==429,status
assert headers.get('access-control-allow-origin')=='https://ex.tokdou.com'
assert headers.get('access-control-allow-credentials')=='true'
assert headers.get('retry-after')=='1'
steps.append({'case':'429 handler supports credentialed browser CORS and Retry-After (forced single response)','status':status})
marker='security-query-'+uuid.uuid4().hex
status,_,_=call('GET','/api/v1/time?signature='+marker,headers={'Authorization':'Bearer '+marker,'Cookie':'qa='+marker})
time.sleep(.2)
for path in (root/'logs').glob('ex-api*'):
 assert marker not in path.read_text(),path.name
steps.append({'case':'query authorization cookie absent from access/error logs','status':status})
(root/'nginx-check.json').write_text(json.dumps({'passed':True,'nginx':'1.30.6','steps':steps},indent=2)+'\n');print(json.dumps(steps,indent=2))
