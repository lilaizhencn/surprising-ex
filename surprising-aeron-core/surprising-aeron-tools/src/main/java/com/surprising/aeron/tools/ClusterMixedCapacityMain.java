package com.surprising.aeron.tools;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.state.LaneTopology;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.HdrHistogram.Histogram;

/** Network counterpart of LinearPerpetualMixedWorkload's UNIFORM scale scenario.
 * All state changes and reads go through the actual cluster; no local Core is instantiated.
 */
public final class ClusterMixedCapacityMain implements AutoCloseable {
    static final int USERS = 1000, SYMBOLS = 256, BATCH = 20, WINDOW = 256;
    static final long BALANCE = 1_000_000_000L;
    private final List<Long> users = users();
    private final AeronClientPool client;
    private final ArrayList<CompletableFuture<?>> pending = new ArrayList<>(WINDOW);
    private final long seed = Long.getLong("surprising.aeron.capacity-seed", 92001);
    private final long[] markSequence = new long[SYMBOLS], markTime = new long[SYMBOLS], mark = new long[SYMBOLS];
    private final long[] fundingId = new long[SYMBOLS], fundingCursor = new long[SYMBOLS];
    private final boolean[] fundingComplete = new boolean[SYMBOLS];
    private final EnumMap<CoreMessageType, Stats> stats = new EnumMap<>(CoreMessageType.class);
    private long orderId, requestId, offered, terminal, coreOffered, coreTerminal, fills, peak, queries;
    private long totalCycles, measuredCycles, lastReport, reportTerminal, started, triggerExecutions;
    private int lifecycleCursor;
    private boolean measured, lossCompleted;
    private long adminRetriesBefore;

    public ClusterMixedCapacityMain() {
        if (Integer.getInteger("surprising.aeron.capacity-async-in-flight", WINDOW) != WINDOW)
            throw new IllegalArgumentException("mixed capacity requires global 256 in-flight");
        orderId = 200_000_000_000L + seed * 1_000_000;
        // A single FIFO command session preserves dependent place/cancel/IOC ordering without
        // waiting for responses between trading stages. A separate reserved session handles reads.
        client = new AeronClientPool("mixed", ProductLine.LINEAR_PERPETUAL,
                List.of(System.getProperty("surprising.aeron.hostnames").split(",")),
                System.getProperty("surprising.aeron.egress-hostname"), Duration.ofSeconds(30),
                1, "mixed-" + seed);
        for (int i=0;i<SYMBOLS;i++) { mark[i]=100; fundingId[i]=10_000+i; }
    }

    public static void main(String[] args) throws Exception {
        try (var run = new ClusterMixedCapacityMain()) {
            if (Boolean.getBoolean("surprising.aeron.mixed-verify-only")) {
                run.totalCycles = Long.getLong("surprising.aeron.mixed-expected-cycles", 0);
                run.lossCompleted = true;
                run.verify();
                return;
            }
            run.setup();
            run.runFor(Integer.getInteger("surprising.aeron.capacity-warmup-seconds", 30), false);
            run.runFor(Integer.getInteger("surprising.aeron.capacity-duration-seconds", 300), true);
            run.verify();
            run.print();
        }
    }

    static List<Long> users() {
        var topology=LaneTopology.configured(false);
        var result=new ArrayList<Long>(USERS+3*SYMBOLS+1);
        long candidate=100_000;
        for(int i=0;i<USERS+3*SYMBOLS+1;i++) {
            while(topology.accountLaneId(candidate)!=(i&3))candidate++;
            result.add(candidate++);
        }
        return List.copyOf(result);
    }

    private long retail(int i) { return users.get(i+1); }
    private long positionMaker(int i) { return users.get(USERS+1+i); }
    private long maker(int i) { return users.get(USERS+1+SYMBOLS+i); }
    private long taker(int i) { return users.get(USERS+1+2*SYMBOLS+(i+1)%SYMBOLS); }
    private String symbol(int i) { return "JMH-MIX-"+i+"-USDT"; }
    private long nextOrder() { return ++orderId; }
    private UUID nextRequest() { return new UUID(seed, ++requestId); }

    static PlaceOrderCommand order(long id,String symbol,CoreOrderSide side,long price,long quantity,CoreTimeInForce tif) {
        return new PlaceOrderCommand(id,symbol,1,side,price,quantity,false,CoreMarginMode.CROSS,
                CorePositionSide.NET,CoreOrderType.LIMIT,tif,false,"mixed-"+id);
    }

    private void setup() {
        for(int i=0;i<SYMBOLS;i++) {
            send(CoreMessageType.UPSERT_INSTRUMENT,0,TradingCommandCodec.encodeUpsertInstrument(
                    new UpsertInstrumentCommand(symbol(i),1,ContractType.LINEAR_PERPETUAL.ordinal(),"MIX"+i,
                            "USDT","USDT",1,1,1,100_000,50_000,0,0,0,-1,0)),1,null);
            price(i,100);
        }
        for(long user:users) send(CoreMessageType.ADJUST_BALANCE,user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",user==users.getFirst()?100:BALANCE)),1,null);
        send(CoreMessageType.ADJUST_INSURANCE_FUND,0,TradingCommandCodec.encodeAdjustInsuranceFund(
                new AdjustInsuranceFundCommand("USDT",25)),1,null);
        long[] quantities=new long[SYMBOLS];
        for(int u=0;u<USERS;u++)for(int p=0;p<1+u%5;p++)quantities[(u+p)%SYMBOLS]+=1+((u+p)&3);
        quantities[SYMBOLS-1]+=10;
        for(int i=0;i<SYMBOLS;i++) place(i,positionMaker(i),CoreOrderSide.SELL,100,quantities[i],CoreTimeInForce.GTC);
        for(int u=0;u<USERS;u++)for(int p=0;p<1+u%5;p++)place((u+p)%SYMBOLS,retail(u),CoreOrderSide.BUY,100,1+((u+p)&3),CoreTimeInForce.IOC);
        place(SYMBOLS-1,users.getFirst(),CoreOrderSide.BUY,100,10,CoreTimeInForce.IOC);
        for(int u=0;u<USERS;u++)for(int o=0;o<u%11;o++)place((u+o%(1+u%5))%SYMBOLS,retail(u),CoreOrderSide.BUY,90-o%10,1,CoreTimeInForce.GTC);
        price(SYMBOLS-1,1);
        drain();
        drainRisk();
        verifyPopulation();
        System.out.printf("mixedSetup=PASS users=%d retail=%d symbols=%d initialFunds=%d%n",users.size(),USERS,SYMBOLS,expectedFunds());
    }

    private void runFor(int seconds,boolean measure) {
        drain();
        measured=measure;
        if(measure) { offered=terminal=coreOffered=coreTerminal=fills=queries=peak=0;stats.clear();adminRetriesBefore=client.adminActionRetries(); }
        started=lastReport=System.nanoTime(); reportTerminal=0;
        long end=started+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<end) {
            cycle(); totalCycles++; if(measure)measuredCycles++;
        }
        drain();
        if(measure) elapsed=System.nanoTime()-started;
        measured=false;
    }
    private long elapsed;

    private void cycle() {
        // Complete any funding cut before trading the affected symbol; response-dependent pages
        // are control work. Trading itself is an independently submitted FIFO command stream.
        for(int i=0;i<SYMBOLS;i++)while(fundingCursor[i]!=0)funding(i);
        long[][] quotes=new long[SYMBOLS][];
        for(int i=0;i<SYMBOLS;i++)quotes[i]=batch(i,maker(i),CoreOrderSide.SELL,102,2,CoreTimeInForce.GTC);
        for(int i=0;i<SYMBOLS;i++)cancelBatch(maker(i),quotes[i]);
        long[] liquidity=new long[SYMBOLS];
        for(int i=0;i<SYMBOLS;i++)liquidity[i]=place(i,maker(i),CoreOrderSide.SELL,101,40,CoreTimeInForce.GTC);
        for(int i=0;i<SYMBOLS;i++)batch(i,taker(i),CoreOrderSide.BUY,101,1,CoreTimeInForce.IOC);
        for(int i=0;i<SYMBOLS;i++)cancel(maker(i),liquidity[i]);
        for(int i=0;i<SYMBOLS;i++)liquidity[i]=place(i,maker(i),CoreOrderSide.BUY,99,40,CoreTimeInForce.GTC);
        for(int i=0;i<SYMBOLS;i++)cancel(maker(i),liquidity[i]);
        // Preserve original mixed workload: bid cancellation precedes the sell IOC batch.
        for(int i=0;i<SYMBOLS;i++)batch(i,taker(i),CoreOrderSide.SELL,99,1,CoreTimeInForce.IOC);
        drain();
        for(int n=0;n<32;n++)trigger((lifecycleCursor+n)%SYMBOLS);
        drain();
        if(!lossCompleted)lossLifecycle();
        for(int n=0;n<32;n++) {
            int i=(lifecycleCursor+n)%SYMBOLS;
            funding(i);
            var work=work(CoreLiquidationWorkView.Purpose.EXECUTION);
            if(work.riskScanPending())send(CoreMessageType.CONTINUE_RISK_SCAN,0,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)),1,null);
            else price(i,99);
        }
        lifecycleCursor=(lifecycleCursor+32)%SYMBOLS;
        drain();
    }

    private void refresh(int i) { if(System.currentTimeMillis()-markTime[i]>=1000)price(i,mark[i]); }
    private void price(int i,long value) {
        mark[i]=value;markTime[i]=System.currentTimeMillis();
        send(CoreMessageType.APPLY_MARK_PRICE,0,TradingCommandCodec.encodeApplyMarkPrice(
                new ApplyMarkPriceCommand(symbol(i),1,value,++markSequence[i],markTime[i])),1,null);
    }
    private long place(int i,long user,CoreOrderSide side,long price,long quantity,CoreTimeInForce tif) {
        refresh(i);long id=nextOrder();
        send(CoreMessageType.PLACE_ORDER,user,TradingCommandCodec.encodePlaceOrder(order(id,symbol(i),side,price,quantity,tif)),1,null);
        return id;
    }
    private long[] batch(int i,long user,CoreOrderSide side,long price,long quantity,CoreTimeInForce tif) {
        refresh(i);var commands=new ArrayList<PlaceOrderCommand>(BATCH);long[] ids=new long[BATCH];
        for(int n=0;n<BATCH;n++) { ids[n]=nextOrder();commands.add(order(ids[n],symbol(i),side,price,quantity,tif)); }
        send(CoreMessageType.PLACE_ORDER_BATCH,user,TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(commands)),BATCH,
                r -> { long count=validateBatch(r,ids);if(measured)fills+=count; });
        return ids;
    }
    private void cancel(long user,long id) { send(CoreMessageType.CANCEL_ORDER,user,TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(id)),1,null); }
    private void cancelBatch(long user,long[] ids) {
        var commands=Arrays.stream(ids).mapToObj(CancelOrderCommand::new).toList();
        send(CoreMessageType.CANCEL_ORDER_BATCH,user,TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(commands)),ids.length,
                r -> validateBatch(r,ids));
    }
    static long validateBatch(CoreResponse response,long[] ids) {
        var items=TradingOrderBatchCodec.decodeResult(response.data()).items();
        if(items.size()!=ids.length)throw new IllegalStateException("mixed batch response count mismatch");
        long fills=0;
        for(int i=0;i<ids.length;i++) {
            var item=items.get(i);
            if(item.index()!=i || item.orderId()!=ids[i] || item.status()!=ResponseStatus.APPLIED)
                throw new IllegalStateException("mixed batch item rejected/identity mismatch: "+item);
            for(var execution:item.executions()) {
                if(execution.takerOrderId()!=ids[i])throw new IllegalStateException("mixed execution identity mismatch");
                fills++;
            }
        }
        return fills;
    }

    private void funding(int i) {
        if(fundingComplete[i]) { fundingId[i]+=SYMBOLS;fundingComplete[i]=false; }
        var r=execute(CoreMessageType.APPLY_FUNDING,0,TradingCommandCodec.encodeApplyFunding(
                new ApplyFundingCommand(fundingId[i],symbol(i),1,(i&1)==0?100_000:-100_000,fundingCursor[i],64)));
        var progress=CoreFundingProgressCodec.decode(r.data());
        fundingCursor[i]=progress.nextCursorUserId();fundingComplete[i]=progress.complete();
    }
    private void trigger(int i) {
        refresh(i);long id=nextOrder();boolean loss=i==SYMBOLS-1;
        var trigger=new CoreTriggerOrderStateView(id,ProductLine.LINEAR_PERPETUAL,taker(i),"mixed-trigger-"+id,"",symbol(i),
                CoreOrderSide.SELL,loss?CoreTriggerOrderType.STOP_LOSS:CoreTriggerOrderType.TAKE_PROFIT,
                loss?CoreTriggerCondition.LESS_OR_EQUAL:CoreTriggerCondition.GREATER_OR_EQUAL,mark[i],
                0,0,0,0,0,CoreOrderType.LIMIT,CoreTimeInForce.IOC,110,1,CoreMarginMode.CROSS,CorePositionSide.NET,
                CoreTriggerOrderStatus.PENDING,0,0,0,"","mixed-trace-"+id,0,0,0,0,1,1,0,0);
        send(CoreMessageType.PLACE_TRIGGER_ORDER,taker(i),CoreTriggerOrderCodec.encodeState(trigger),1,null);
        send(CoreMessageType.EXECUTE_TRIGGER_ORDER,0,CoreTriggerOrderCodec.encodeExecute(id,markSequence[i],mark[i],System.currentTimeMillis()),1,null);
        triggerExecutions++;
    }

    private CoreLiquidationWorkView work(CoreLiquidationWorkView.Purpose purpose) {
        return CoreLiquidationWorkCodec.decodeWork(query(CoreMessageType.LIQUIDATION_WORK_QUERY,0,
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL,purpose,0,1000,1_048_576)).data());
    }
    private void drainRisk() {
        for(int pages=0;pages<10000;pages++) {
            if(!work(CoreLiquidationWorkView.Purpose.EXECUTION).riskScanPending())return;
            execute(CoreMessageType.CONTINUE_RISK_SCAN,0,TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)));
        }
        throw new IllegalStateException("risk scan failed to drain");
    }
    private void lossLifecycle() {
        // Real-time price refresh can advance the risk generation after setup. Finish its
        // continuation before requesting executable liquidation work for that generation.
        drainRisk();
        var actions=work(CoreLiquidationWorkView.Purpose.EXECUTION).actions();
        if(actions.size()!=1 || actions.getFirst().userId()!=users.getFirst())throw new IllegalStateException("expected dedicated liquidation: "+actions);
        var a=actions.getFirst();
        execute(CoreMessageType.EXECUTE_LIQUIDATION_BATCH,0,TradingCommandCodec.encodeExecuteLiquidationBatch(
                new ExecuteLiquidationBatchCommand(List.of(new ExecuteLiquidationBatchAction(a.liquidationId(),a.userId(),a.symbol(),
                        a.instrumentChangeId(),a.triggerPriceSequence(),a.markPriceTicks(),a.cursorOrderId())),
                        ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS,0,null,0)));
        var insurance=work(CoreLiquidationWorkView.Purpose.INSURANCE).resolutions();
        if(insurance.size()!=1)throw new IllegalStateException("insurance work missing: "+insurance);
        long available=treasury().stream().filter(x->x.asset().equals("USDT")).findFirst().orElseThrow().insuranceBalanceUnits();
        long coverage=Math.min(available,insurance.getFirst().deficitUnits()-1);
        if(coverage<=0)throw new IllegalStateException("insurable deficit missing");
        execute(CoreMessageType.RESOLVE_LIQUIDATION,0,TradingCommandCodec.encodeResolveLiquidation(
                new ResolveLiquidationCommand(a.liquidationId(),ResolveLiquidationCommand.Resolution.INSURANCE,coverage)));
        var adl=work(CoreLiquidationWorkView.Purpose.ADL).resolutions();
        if(adl.size()!=1)throw new IllegalStateException("ADL work missing: "+adl);
        var p=user(positionMaker(SYMBOLS-1)).positions().stream().filter(x->x.symbol().equals(symbol(SYMBOLS-1))).findFirst().orElseThrow();
        long profit=p.entryPriceTicks()-1, deficit=adl.getFirst().deficitUnits();
        long quantity=Math.floorDiv(Math.addExact(deficit,profit-1),profit);
        execute(CoreMessageType.EXECUTE_ADL,0,TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(a.liquidationId(),
                positionMaker(SYMBOLS-1),symbol(SYMBOLS-1),CoreMarginMode.CROSS,CorePositionSide.NET,
                p.signedQuantitySteps(),p.entryPriceTicks(),adl.getFirst().triggerPriceSequence(),quantity,deficit)));
        if(!work(CoreLiquidationWorkView.Purpose.INSURANCE).resolutions().isEmpty()
                || !work(CoreLiquidationWorkView.Purpose.ADL).resolutions().isEmpty()
                || !flat(user(users.getFirst()).positions()))throw new IllegalStateException("loss lifecycle incomplete");
        lossCompleted=true;
        System.out.println("mixedLossLifecycle=PASS liquidation=true insurance=true adl=true");
    }

    static boolean flat(List<CorePositionView> positions) {
        // Closed positions can retain realized PnL history; economic exposure and margin must be zero.
        return positions.stream().allMatch(p->p.signedQuantitySteps()==0 && p.positionMarginUnits()==0);
    }

    private CompletableFuture<Completed> send(CoreMessageType type,long user,byte[] payload,int weight,Consumer<CoreResponse> validation) {
        space();
        long start=System.nanoTime();boolean count=measured;
        if(count){offered+=weight;coreOffered++;}
        // Fail on a not-accepted offer rather than retry it behind dependent later commands.
        var future=client.commandAsync(type,nextRequest(),user,payload).thenApply(r->{
            if(r.commandStatus()!=ResponseStatus.APPLIED)throw new IllegalStateException(type+" rejected "+r.resultCode());
            return new Completed(r,System.nanoTime());
        });
        // Completion processing belongs to this producer, never an egress callback thread.
        pending.add(future);
        tasks.put(future,new Task(type,weight,start,count,validation));
        peak=Math.max(peak,pending.size());
        reap();report();return future;
    }
    private final IdentityHashMap<CompletableFuture<?>,Task> tasks=new IdentityHashMap<>();
    private record Completed(CoreResponse response,long terminalNanos) {}
    private record Task(CoreMessageType type,int weight,long start,boolean counted,Consumer<CoreResponse> validation) {}
    private void space() { while(pending.size()>=WINDOW){reap();report();Thread.onSpinWait();} }
    private void reap() {
        for(int i=pending.size()-1;i>=0;i--) {
            var f=pending.get(i);if(!f.isDone())continue;
            var completion=(Completed)f.join();var r=completion.response();var task=tasks.remove(f);
            if(task.validation()!=null)task.validation().accept(r);
            if(task.counted()) {
                terminal+=task.weight();coreTerminal++;
                stats.computeIfAbsent(task.type(),ignored->new Stats()).record(task.weight(),completion.terminalNanos()-task.start());
            }
            pending.remove(i);
        }
    }
    private void drain() { while(!pending.isEmpty()){reap();report();Thread.onSpinWait();} }
    private CoreResponse execute(CoreMessageType type,long user,byte[] payload) {
        var future=send(type,user,payload,1,null);drain();return future.join().response();
    }
    private CoreResponse query(CoreMessageType type,long user,byte[] payload) {
        drain();if(measured)queries++;
        var r=client.query(type,nextRequest(),user,payload);
        if(r.status()!=ResponseStatus.OK)throw new IllegalStateException(type+" query failed "+r.resultCode());
        return r;
    }
    private CoreUserStateView user(long id) { return CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY,id,new byte[0]).data()); }
    private List<CoreTreasuryAssetView> treasury() { return CoreStateQueryCodec.decodeTreasuryState(query(CoreMessageType.TREASURY_STATE_QUERY,0,new byte[0]).data()); }
    static long expectedFunds() { return (USERS+3L*SYMBOLS)*BALANCE+100+25; }
    static long treasuryFunds(CoreTreasuryAssetView t) {
        long sum=Math.addExact(t.feeBalanceUnits(),t.insuranceBalanceUnits());
        sum=Math.addExact(sum,t.liquidationFeeBalanceUnits());sum=Math.addExact(sum,t.fundingResidualBalanceUnits());
        sum=Math.addExact(sum,t.roundingResidualBalanceUnits());sum=Math.addExact(sum,t.clearingPnlBalanceUnits());
        return Math.subtractExact(sum,t.insuranceDeficitUnits());
    }
    private void verifyPopulation() {
        for(int u=0;u<USERS;u++) {
            var state=user(retail(u));
            if(state.positions().size()!=1+u%5)throw new IllegalStateException("retail position count "+u);
            for(int p=0;p<1+u%5;p++) {
                String symbol=symbol((u+p)%SYMBOLS);long quantity=1+((u+p)&3);
                if(state.positions().stream().noneMatch(x->x.symbol().equals(symbol)&&x.signedQuantitySteps()==quantity))
                    throw new IllegalStateException("retail position quantity "+u);
            }
            var orders=CoreStateQueryCodec.decodeOpenOrders(query(CoreMessageType.USER_OPEN_ORDERS_QUERY,retail(u),
                    CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery("",0,1001))).data()).orders();
            if(orders.size()!=u%11)throw new IllegalStateException("retail order count "+u+" actual="+orders.size());
        }
    }
    private void verify() {
        drain();verifyPopulation();long total=0;
        for(long id:users)for(var b:user(id).balances()) {
            if(b.availableUnits()<0||b.lockedUnits()<0)throw new IllegalStateException("negative balance");
            if(b.asset().equals("USDT"))total=Math.addExact(total,Math.addExact(b.availableUnits(),b.lockedUnits()));
        }
        for(var t:treasury())if(t.asset().equals("USDT"))total=Math.addExact(total,treasuryFunds(t));
        if(total!=expectedFunds()||!lossCompleted)throw new IllegalStateException("mixed funds/lifecycle mismatch actual="+total);
        for(int i=0;i<SYMBOLS;i++)for(long id:new long[]{maker(i),taker(i)}) {
            String instrument=symbol(i);
            var s=user(id);long expected=(id==maker(i)?-1:1)*totalCycles*BATCH;
            long actual=s.positions().stream().filter(p->p.symbol().equals(instrument)).mapToLong(CorePositionView::signedQuantitySteps).sum();
            if(actual!=expected||!s.reservations().isEmpty())throw new IllegalStateException("HFT position/reservation mismatch user="+id+" expected="+expected+" actual="+actual);
        }
        if(offered!=terminal||coreOffered!=coreTerminal||peak>WINDOW)throw new IllegalStateException("mixed terminal counters disagree");
        if(measuredCycles>0) {
            if(fills!=measuredCycles*SYMBOLS*BATCH)throw new IllegalStateException("mixed actual fill count mismatch: "+fills);
            requireItems(CoreMessageType.PLACE_ORDER_BATCH,measuredCycles*SYMBOLS*BATCH*3);
            requireItems(CoreMessageType.CANCEL_ORDER_BATCH,measuredCycles*SYMBOLS*BATCH);
            requireItems(CoreMessageType.PLACE_ORDER,measuredCycles*SYMBOLS*2);
            requireItems(CoreMessageType.CANCEL_ORDER,measuredCycles*SYMBOLS*2);
            requireItems(CoreMessageType.PLACE_TRIGGER_ORDER,measuredCycles*32);
            requireItems(CoreMessageType.EXECUTE_TRIGGER_ORDER,measuredCycles*32);
        }
        long hash=query(CoreMessageType.BUSINESS_STATE_HASH_QUERY,0,new byte[0]).stateHash();
        System.out.printf("mixedVerify=PASS fundsDiff=0 population=true hftPositions=true reservations=true loss=true totalCycles=%d businessHash=%s%n",totalCycles,Long.toUnsignedString(hash,16));
    }
    private void requireItems(CoreMessageType type,long expected) {
        var s=stats.get(type);
        if(s==null||s.items!=expected)throw new IllegalStateException("mixed workload composition mismatch: "+type);
    }
    private void report() {
        long now=System.nanoTime();if(!measured||now-lastReport<TimeUnit.SECONDS.toNanos(10))return;
        System.out.printf(Locale.ROOT,"progress terminalBusinessOps=%d intervalBusinessOpsPerSec=%.3f requestsInFlight=%d%n",terminal,(terminal-reportTerminal)*1e9/(now-lastReport),pending.size());
        lastReport=now;reportTerminal=terminal;
    }
    private void print() {
        double seconds=elapsed/1e9;
        System.out.printf("adminActionRetries=%d%n",client.adminActionRetries()-adminRetriesBefore);
        System.out.printf(Locale.ROOT,"mixedCapacity=PASS elapsedSeconds=%.3f terminalBusinessOperations=%d offeredBusinessOperations=%d terminalCoreMessages=%d offeredCoreMessages=%d businessOpsPerSec=%.3f coreMessagesPerSec=%.3f fills=%d fillsPerSec=%.3f queries=%d unfinished=0 peakInFlight=%d measuredCycles=%d totalCycles=%d triggerExecutions=%d%n",
                seconds,terminal,offered,coreTerminal,coreOffered,terminal/seconds,coreTerminal/seconds,fills,fills/seconds,queries,peak,measuredCycles,totalCycles,triggerExecutions);
        stats.forEach((type,s)->System.out.printf(Locale.ROOT,"business=%s items=%d requests=%d p50us=%d p90us=%d p95us=%d p99us=%d p999us=%d maxus=%d%n",type,s.items,s.latency.getTotalCount(),s.latency.getValueAtPercentile(50)/1000,s.latency.getValueAtPercentile(90)/1000,s.latency.getValueAtPercentile(95)/1000,s.latency.getValueAtPercentile(99)/1000,s.latency.getValueAtPercentile(99.9)/1000,s.latency.getMaxValue()/1000));
    }
    private static final class Stats {
        long items;final Histogram latency=new Histogram(TimeUnit.MINUTES.toNanos(1),3);
        void record(int weight,long ns){items+=weight;latency.recordValue(Math.max(1,ns));}
    }
    @Override public void close(){client.close();}
}
