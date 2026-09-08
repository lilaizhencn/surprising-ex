package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** A separate control producer; its response dependencies never drain the trading producer. */
final class OperationalLifecycle implements AutoCloseable {
    private static final String ACTIVE="OPS-ACT-USDT", RISK="OPS-RISK-USDT";
    private final OperationalEndpoint endpoint;
    private final ClusterOperationalSideLoad owner;
    private final long maker,user,riskMaker,victim;
    private long id,activeSequence,riskSequence,fundingId=1,deposits,cycles,nextRiskScanAtNanos;

    OperationalLifecycle(long seed,ClusterOperationalSideLoad owner) {
        this.owner=owner;
        endpoint=new OperationalEndpoint(seed,"operational-lifecycle",8,owner);
        maker=10_000_000L+seed*4;user=maker+1;riskMaker=maker+2;victim=maker+3;
        id=800_000_000_000L+seed*1_000_000;
    }
    long netDeposits(){return deposits;}
    void restoreAudit(long cycles,long deposits) {
        if(cycles<=0 || deposits!=expectedDeposits(cycles))throw new IllegalArgumentException("invalid operational audit ledger");
        this.cycles=cycles;this.deposits=deposits;
    }
    static long expectedDeposits(long cycles) {
        if(cycles<0)throw new IllegalArgumentException("negative cycles");
        return Math.addExact(3*ClusterMixedCapacityMain.BALANCE,Math.multiplyExact(cycles,125));
    }
    void setup() {
        for(String symbol:List.of(ACTIVE,RISK))endpoint.command(CoreMessageType.UPSERT_INSTRUMENT,0,
                TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand(symbol,1,
                        ContractType.LINEAR_PERPETUAL.ordinal(),"OPS","USDT","USDT",1,1,1,
                        100_000,50_000,0,0,0,-1,0)));
        for(long account:new long[]{maker,user,riskMaker})deposit(account,ClusterMixedCapacityMain.BALANCE);
        price(ACTIVE,100);price(RISK,100);
    }
    private void deposit(long account,long amount) {
        endpoint.command(CoreMessageType.ADJUST_BALANCE,account,TradingCommandCodec.encodeBalanceAdjustment(
                new BalanceAdjustmentCommand("USDT",amount)));
        deposits=Math.addExact(deposits,amount);
    }
    private void price(String symbol,long value) {
        long seq=symbol.equals(ACTIVE)?++activeSequence:++riskSequence;
        endpoint.command(CoreMessageType.APPLY_MARK_PRICE,0,TradingCommandCodec.encodeApplyMarkPrice(
                new ApplyMarkPriceCommand(symbol,1,value,seq,GeneratedPriceClock.timestamp())));
    }
    private void place(long account,String symbol,CoreOrderSide side,long price,long quantity,
                       CoreTimeInForce tif,boolean reduceOnly) {
        endpoint.command(CoreMessageType.PLACE_ORDER,account,TradingCommandCodec.encodePlaceOrder(
                new PlaceOrderCommand(++id,symbol,1,side,price,quantity,reduceOnly,CoreMarginMode.CROSS,
                        CorePositionSide.NET,CoreOrderType.LIMIT,tif,false,"ops-"+id)));
    }
    void cycle() {
        long started=System.nanoTime();
        price(ACTIVE,100);
        openActive();
        long cursor=0;
        do {
            var response=endpoint.command(CoreMessageType.APPLY_FUNDING,0,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(
                            fundingId,ACTIVE,1,100_000,cursor,64)));
            cursor=CoreFundingProgressCodec.decode(response.data()).nextCursorUserId();
        }while(cursor!=0);
        fundingId++;
        place(maker,ACTIVE,CoreOrderSide.BUY,100,1,CoreTimeInForce.GTC,false);
        place(user,ACTIVE,CoreOrderSide.SELL,100,1,CoreTimeInForce.IOC,true);
        requireFlat(user);
        owner.record("MANUAL_CLOSE_CONFIRMED",started,System.nanoTime());

        price(ACTIVE,100);
        openActive();
        place(maker,ACTIVE,CoreOrderSide.BUY,100,1,CoreTimeInForce.GTC,false);
        long trigger=++id;
        var state=new CoreTriggerOrderStateView(trigger,ProductLine.LINEAR_PERPETUAL,user,"ops-trigger-"+trigger,
                "",ACTIVE,CoreOrderSide.SELL,CoreTriggerOrderType.TAKE_PROFIT,CoreTriggerCondition.GREATER_OR_EQUAL,
                100,0,0,0,0,0,CoreOrderType.LIMIT,CoreTimeInForce.IOC,100,1,CoreMarginMode.CROSS,
                CorePositionSide.NET,CoreTriggerOrderStatus.PENDING,0,0,0,"","ops",0,0,0,0,1,1,0,0);
        endpoint.command(CoreMessageType.PLACE_TRIGGER_ORDER,user,CoreTriggerOrderCodec.encodeState(state));
        var pending=CoreTriggerOrderCodec.decodeList(endpoint.query(CoreMessageType.TRIGGER_ORDER_QUERY,0,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(0,ACTIVE,0,1000,CoreTriggerOrderStatus.PENDING))).data());
        if(pending.stream().noneMatch(t->t.triggerOrderId()==trigger))throw new IllegalStateException("trigger scanner missed order");
        long triggerStarted=System.nanoTime();
        endpoint.command(CoreMessageType.EXECUTE_TRIGGER_ORDER,0,CoreTriggerOrderCodec.encodeExecute(
                trigger,activeSequence,100,System.currentTimeMillis()));
        requireFlat(user);
        var terminal=CoreTriggerOrderCodec.decodeList(endpoint.query(CoreMessageType.TRIGGER_ORDER_QUERY,0,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(trigger,ACTIVE,0,1))).data());
        if(terminal.size()!=1 || terminal.getFirst().status()!=CoreTriggerOrderStatus.TRIGGERED)
            throw new IllegalStateException("trigger did not reach successful terminal state: "+terminal);
        // Exactly one one-unit maker bid existed and the one-unit position was closed.
        owner.recordFills(triggerStarted,1);
        owner.record("TRIGGER_CLOSE_CONFIRMED",started,System.nanoTime());
        liquidation();
        cycles++;
        owner.record("LIFECYCLE_CONFIRMED",started,System.nanoTime());
    }
    private void openActive() {
        place(maker,ACTIVE,CoreOrderSide.SELL,100,1,CoreTimeInForce.GTC,false);
        place(user,ACTIVE,CoreOrderSide.BUY,100,1,CoreTimeInForce.IOC,false);
        long quantity=endpoint.user(user).positions().stream().filter(p->p.symbol().equals(ACTIVE))
                .mapToLong(CorePositionView::signedQuantitySteps).sum();
        if(quantity!=1)throw new IllegalStateException("manual/trigger position not opened");
    }
    private CoreLiquidationWorkView work(CoreLiquidationWorkView.Purpose purpose) {
        return CoreLiquidationWorkCodec.decodeWork(endpoint.query(CoreMessageType.LIQUIDATION_WORK_QUERY,0,
                CoreLiquidationWorkCodec.encodeQuery(ProductLine.LINEAR_PERPETUAL,purpose,0,1000,1_048_576)).data());
    }
    private void liquidation() {
        long started=System.nanoTime();
        deposit(victim,100);
        endpoint.command(CoreMessageType.ADJUST_INSURANCE_FUND,0,TradingCommandCodec.encodeAdjustInsuranceFund(
                new AdjustInsuranceFundCommand("USDT",25)));
        deposits=Math.addExact(deposits,25);
        price(RISK,100);
        place(riskMaker,RISK,CoreOrderSide.SELL,100,10,CoreTimeInForce.GTC,false);
        place(victim,RISK,CoreOrderSide.BUY,100,10,CoreTimeInForce.IOC,false);
        price(RISK,1);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        long liquidationId=0;
        long queries=0,batches=0,requeries=0,scanWork=0,scanDelayMs=0;
        while(liquidationId==0) {
            if(System.nanoTime()>deadline)throw new IllegalStateException(
                    "liquidation starved by concurrent risk work: queries="+queries+" batches="+batches
                            +" requeries="+requeries+" scanWork="+scanWork+" scanDelayMs="+scanDelayMs);
            var work=work(CoreLiquidationWorkView.Purpose.EXECUTION);
            queries++;
            var action=work.actions().stream().filter(a->a.userId()==victim).findFirst();
            int scanBudget=0;
            if(work.riskScanPending()) {
                var control=CoreRiskScanControlCodec.decodeView(endpoint.query(
                        CoreMessageType.RISK_SCAN_CONTROL_QUERY,0,new byte[0]).data());
                scanDelayMs=control.scanDelayMs();
                long now=System.nanoTime();
                if(control.enabled() && now>=nextRiskScanAtNanos) {
                    scanBudget=control.scanBatchSize();
                    nextRiskScanAtNanos=Math.addExact(now,TimeUnit.MILLISECONDS.toNanos(control.scanDelayMs()));
                }
            }
            if(!work.actions().isEmpty() || scanBudget>0) {
                batches++;
                var result=endpoint.liquidationBatch(work,scanBudget);
                if(result==null)requeries++;
                else scanWork+=result.riskScanContinuedUsers();
                if(result!=null && result.pendingActions()==0 && result.obsoleteActions()==0
                        && action.isPresent()) liquidationId=action.get().liquidationId();
            }
        }
        System.out.printf("liquidationScan queries=%d batches=%d requeries=%d scanWork=%d scanDelayMs=%d elapsedMs=%d%n",
                queries,batches,requeries,scanWork,scanDelayMs,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        long expectedId=liquidationId;
        var insurance=work(CoreLiquidationWorkView.Purpose.INSURANCE).resolutions().stream()
                .filter(a->a.liquidationId()==expectedId).findFirst().orElseThrow();
        long coverage=insurance.recommendedCoveredUnits();
        if(coverage>=insurance.deficitUnits())throw new IllegalStateException("expected residual ADL deficit: "+insurance);
        endpoint.command(CoreMessageType.RESOLVE_LIQUIDATION,0,TradingCommandCodec.encodeResolveLiquidation(
                new ResolveLiquidationCommand(liquidationId,ResolveLiquidationCommand.Resolution.INSURANCE,coverage)));
        var adl=work(CoreLiquidationWorkView.Purpose.ADL).resolutions().stream()
                .filter(a->a.liquidationId()==expectedId).findFirst().orElseThrow();
        var position=endpoint.user(riskMaker).positions().stream().filter(p->p.symbol().equals(RISK)).findFirst().orElseThrow();
        long profit=position.entryPriceTicks()-1;
        long quantity=Math.floorDiv(Math.addExact(adl.deficitUnits(),profit-1),profit);
        endpoint.command(CoreMessageType.EXECUTE_ADL,0,TradingCommandCodec.encodeExecuteAdl(new ExecuteAdlCommand(
                liquidationId,riskMaker,RISK,CoreMarginMode.CROSS,CorePositionSide.NET,position.signedQuantitySteps(),
                position.entryPriceTicks(),adl.triggerPriceSequence(),quantity,adl.deficitUnits())));
        requireFlat(victim);
        if(work(CoreLiquidationWorkView.Purpose.ADL).resolutions().stream().anyMatch(a->a.liquidationId()==expectedId))
            throw new IllegalStateException("ADL lifecycle unfinished");
        owner.record("BANKRUPTCY_INSURANCE_ADL_CONFIRMED",started,System.nanoTime());
    }
    private void requireFlat(long account) {
        var state=endpoint.user(account);
        if(!ClusterMixedCapacityMain.flat(state.positions()) || !state.reservations().isEmpty())
            throw new IllegalStateException("closed account retains exposure/reservations: "+account);
    }
    long verifyAndBalance() {
        if(cycles==0)throw new IllegalStateException("no operational lifecycle completed");
        if(deposits!=expectedDeposits(cycles))throw new IllegalStateException("operational deposit ledger mismatch");
        requireFlat(user);requireFlat(victim);requireFlat(maker);
        long total=0;
        for(long account:new long[]{maker,user,riskMaker,victim})for(var b:endpoint.user(account).balances()) {
            if(b.availableUnits()<0 || b.lockedUnits()<0)throw new IllegalStateException("negative operational balance");
            if(b.asset().equals("USDT"))total=Math.addExact(total,Math.addExact(b.availableUnits(),b.lockedUnits()));
        }
        return total;
    }
    void print(){System.out.printf("operationalLifecycle=PASS cycles=%d netDeposits=%d manualClose=true triggerClose=true liquidation=true insurance=true adl=true%n",cycles,deposits);}
    @Override public void close(){endpoint.close();}
}
