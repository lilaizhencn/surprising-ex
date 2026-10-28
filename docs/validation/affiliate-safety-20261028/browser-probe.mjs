const {chromium}=await import(process.env.PLAYWRIGHT_MODULE ?? 'playwright');
import fs from 'node:fs/promises';
const output=new URL('.',import.meta.url).pathname;
const now=new Date().toISOString(),future=new Date(Date.now()+86400000).toISOString();
const session={accessToken:'resource-limit-browser-fixture',refreshToken:'fixture-only',accessTokenExpiresAt:future,refreshTokenExpiresAt:future,user:{userId:'1',username:'resource-qa',status:'NORMAL',roles:['ADMIN'],createdAt:now}};
const settings={version:1,referralRatePpm:200000,kolRatePpm:200000,settlementHours:24};
const overview={settings,profile:{userId:'1',inviteCode:'SAFETYQA',kolEnabled:true,autoSettlement:true,ratePpm:null,createdAt:now},rootRatePpm:200000,teamActive:true,teamPath:[],counts:{directInviteCount:'0',teamInviteCount:'0'},balances:[],invitations:[]};
const message='返佣资料暂不可用，请稍后刷新或联系平台核对';
const browser=await chromium.launch({channel:'chrome',headless:true});
const results=[];
try {
 for(const kind of ['web','admin']) {
  const context=await browser.newContext({viewport:{width:kind==='web'?390:1440,height:kind==='web'?844:1000}});
  await context.addInitScript(({session,kind})=>localStorage.setItem(kind==='web'?'surprising-ex.session':'surprising-admin.session',JSON.stringify(session)),{session,kind});
  const page=await context.newPage(),errors=[];page.on('pageerror',e=>errors.push(e.message));
  let busy=true,affiliateRequests=0;
  await context.route('**/api/**',async route=>{
   const path=new URL(route.request().url()).pathname;
   if(!path.startsWith('/api/')) {await route.continue();return;}
   let status=200,body={items:[]};
   if(path.includes('/affiliate')) {
    affiliateRequests++;
    if(busy) {status=503;body={message};}
    else if(kind==='admin') body={settings,profiles:[],budgets:[],sources:[],payouts:[]};
    else if(path.endsWith('/me')) body=overview;
    else if(path.endsWith('/members')||path.endsWith('/entries')) body={items:[],nextCursor:null};
   } else if(path.endsWith('/auth/me')) body={...session.user,expiresAt:future};
   else if(path.endsWith('/runtime')) body={productLines:['LINEAR_PERPETUAL']};
   else if(path.endsWith('/asset-scales')) body={USDT:'1000000'};
   await route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)});
  });
  await page.goto(kind==='web'?'http://127.0.0.1:5413/affiliate':'http://127.0.0.1:5414/#/affiliate');

  const error=kind==='web'?page.locator('.affiliate-page [role=alert]').filter({hasText:/服务暂时不可用|temporarily unavailable/}):page.getByText(message,{exact:true});
  await error.waitFor();
  await page.screenshot({path:`${output}/${kind}-busy.png`,fullPage:true});
  const before=affiliateRequests;await page.waitForTimeout(2000);
  if(affiliateRequests-before>4)throw Error(`${kind}: unbounded retry ${affiliateRequests-before}`);
  busy=false;
  await page.getByRole('button',{name:'刷新',exact:true}).click();
  await error.waitFor({state:'hidden'});
  await page.getByRole('heading',{name:kind==='web'?'我的推广':'平台默认配置',exact:true}).waitFor();
  if(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth))throw Error(`${kind} page overflow`);
  await page.screenshot({path:`${output}/${kind}-recovered.png`,fullPage:true});
  if(errors.length)throw Error(`${kind}: ${errors.join(';')}`);
  results.push({kind,viewport:page.viewportSize(),errorMessageShown:true,refreshRecovered:true,unboundedRetry:false,pageOverflow:false,affiliateRequests,pageErrors:errors,backend:'explicit HTTP fixtures; controller/DB/Core verified separately'});
  await context.close();
 }
 await fs.writeFile(`${output}/browser-result.json`,JSON.stringify(results,null,2));
 console.log(JSON.stringify(results));
} finally {await browser.close();}
