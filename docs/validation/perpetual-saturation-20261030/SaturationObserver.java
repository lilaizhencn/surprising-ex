import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.nio.file.*;
import java.util.*;

public class SaturationObserver {
 static long seq=1;
 static SurprisingAeronClient client;
 static CoreResponse query(CoreMessageType type,long user){
  long now=System.currentTimeMillis();
  var header=CoreMessageHeader.query(type,new UUID(887733,seq++),ProductLine.LINEAR_PERPETUAL,CommandSource.OPERATIONS,887733,now,user,now,now);
  var response=client.submit(new CoreMessage(header,CoreMessage.EMPTY_PAYLOAD));
  if(response.status()!=ResponseStatus.OK)throw new IllegalStateException(type+" "+response.resultCode());
  return response;
 }
 public static void main(String[] args)throws Exception{
  try(var connected=SurprisingAeronClient.connect(ProductLine.LINEAR_PERPETUAL,List.of("127.0.0.1"),"127.0.0.1",Duration.ofSeconds(30))){
   client=connected;
   if(args[0].equals("metrics")){
    Path stop=Path.of(args[1]);
    while(!Files.exists(stop)){
     long start=System.nanoTime(),epoch=System.currentTimeMillis();
     var m=CoreLaneMetricsCodec.decode(query(CoreMessageType.LANE_METRICS_QUERY,0).data());
     System.out.println("{\"epochMillis\":"+epoch+",\"queryNanos\":"+(System.nanoTime()-start)+",\"matchers\":"+m.matchingEngineCount()+",\"lanes\":"+m.accountLaneCount()+",\"dispatchDepth\":"+m.matcherDispatchDepth()+",\"completionDepth\":"+m.matchingCompletionDepth()+",\"contextDepth\":"+m.commandContextDepth()+",\"committedCoreSequence\":"+m.committedCoreSequence()+",\"oldestPending\":"+Arrays.toString(m.accountLaneOldestPendingSequences())+",\"accountHighWater\":"+Arrays.toString(m.accountLaneQueueHighWaterMarks())+",\"accountDepths\":"+Arrays.toString(m.accountLaneQueueDepths())+",\"accountOperations\":"+Arrays.toString(m.accountLaneCompletedOperations())+",\"accountExecutionNanos\":"+Arrays.toString(m.accountLaneTotalLatencyNanos())+",\"accountRejected\":"+Arrays.toString(m.accountLaneRejectedSubmissions())+"}");
     long remaining=100_000_000L-(System.nanoTime()-start);if(remaining>0)Thread.sleep(Duration.ofNanos(remaining));
    }
   }else if(args[0].equals("audit")){
    long funds=0,longs=0,shorts=0,positions=0,locked=0;Map<String,Long> net=new TreeMap<>();
    for(int i=0;i<10000;i++){
     long uid=50_000_000_000L+9901L*1000+i;
     var u=CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY,uid).data());
     if(!u.reservations().isEmpty())throw new IllegalStateException("reservations "+uid);
     long total=0,userLocked=0,margin=0;
     for(var b:u.balances())if(b.asset().equals("USDT")){
      if(b.availableUnits()<0||b.lockedUnits()<0)throw new IllegalStateException("negative funds "+uid);
      total=Math.addExact(total,Math.addExact(b.availableUnits(),b.lockedUnits()));userLocked+=b.lockedUnits();
     }
     if(total!=1_000_000_000_000L)throw new IllegalStateException("user funds "+uid+" "+total);
     funds=Math.addExact(funds,total);locked+=userLocked;
     for(var p:u.positions()){
      long q=p.signedQuantitySteps();if(q==0)continue;
      if(p.entryPriceTicks()!=100||p.entryValueTicks()!=Math.abs(q)*100||p.realizedPnlUnits()!=0)throw new IllegalStateException("position values "+uid);
      if((i%2==0&&q>0)||(i%2==1&&q<0))throw new IllegalStateException("position side "+uid);
      margin+=p.positionMarginUnits();positions++;net.merge(p.instrumentId(),q,Math::addExact);
      if(q>0)longs+=q;else shorts-=q;
     }
     if(margin!=userLocked)throw new IllegalStateException("frozen/margin mismatch "+uid+" "+userLocked+" "+margin);
    }
    if(longs!=shorts||net.values().stream().anyMatch(x->x!=0))throw new IllegalStateException("position conservation");
    var m=CoreLaneMetricsCodec.decode(query(CoreMessageType.LANE_METRICS_QUERY,0).data());
    if(m.matchingEngineCount()!=1||m.accountLaneCount()!=2||m.matcherDispatchDepth()!=0||m.matchingCompletionDepth()!=0||m.commandContextDepth()!=0||Arrays.stream(m.accountLaneQueueDepths()).anyMatch(x->x!=0))throw new IllegalStateException("topology/backlog");
    long hash=CoreStateQueryCodec.decodeStateHash(query(CoreMessageType.BUSINESS_STATE_HASH_QUERY,0).data());
    System.out.println("AUDIT {\"users\":10000,\"funds\":"+funds+",\"fundsDiff\":0,\"longQuantity\":"+longs+",\"shortQuantity\":"+shorts+",\"positions\":"+positions+",\"locked\":"+locked+",\"unfinished\":0,\"backlog\":0,\"businessHash\":\""+Long.toHexString(hash)+"\"}");
   }
  }
 }
}
