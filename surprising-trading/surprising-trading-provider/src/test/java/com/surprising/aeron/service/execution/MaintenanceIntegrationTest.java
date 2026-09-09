package com.surprising.aeron.service.execution;

import com.surprising.trading.maintenance.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.execution.TradingCoreRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.order.config.TradingOrderProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Real PostgreSQL task transactions + real Core/matcher. Only the network boundary is in-process. */
@EnabledIfEnvironmentVariable(named="MAINTENANCE_TEST_JDBC_URL",matches=".+")
class MaintenanceIntegrationTest {
    private long sequence;
    private TradingCoreRuntime state;
    private ProductLine line;
    private final AtomicBoolean dropSettlementReply = new AtomicBoolean();
    private final AtomicBoolean dropGateReply = new AtomicBoolean();

    @ParameterizedTest @EnumSource(ProductLine.class)
    void cancelsOrdersAndTriggersRestartsFromPersistedActionsAndReleases(ProductLine product) throws Exception {
        try (var fixture = fixture(product)) {
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(111,CoreOrderSide.SELL,false)));
            submit(22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(112,CoreOrderSide.BUY,false)));
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(113,CoreOrderSide.SELL,false)));
            long before = total();
            long baseBefore = state.tradingState().users().values().stream().mapToLong(u -> u.totalUnits("BTC")).sum();
            var trigger = new CoreTriggerOrderStateView(511,line,22,"maintenance-trigger","","BTC-USDT",line==ProductLine.SPOT?CoreOrderSide.BUY:CoreOrderSide.SELL,
                    CoreTriggerOrderType.STOP_LOSS,CoreTriggerCondition.GREATER_OR_EQUAL,200,0,0,0,0,0,
                    CoreOrderType.LIMIT,CoreTimeInForce.GTC,90,1,CoreMarginMode.CROSS,CorePositionSide.NET,CoreTriggerOrderStatus.PENDING,
                    0,0,0,"","test",0,0,0,0,1,1,0,0);
            submit(22,CoreMessageType.PLACE_TRIGGER_ORDER,CoreTriggerOrderCodec.encodeState(trigger));
            var request = new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.CANCEL,"0","upgrade test");
            var task = fixture.service.create("1",request);
            assertThat(fixture.service.create("1",request).id()).isEqualTo(task.id());
            fixture.service.tick(); fixture.service.tick(); // gate and durable trigger cancellation intent
            assertThat(fixture.repository.pendingAction(fixture.service.get(task.taskId()))).isNotNull();
            restartCore();
            var resumed = fixture.newService();
            run(resumed,task.taskId());
            assertThat(resumed.get(task.taskId()).status()).as(resumed.get(task.taskId()).error()).isEqualTo("COMPLETED");
            var preview = resumed.preview("BTC-USDT",0,0);
            assertThat(preview.orderIds()).isEmpty(); assertThat(preview.triggerOrderIds()).isEmpty();
            assertThat(preview.gateMode()).isEqualTo("HALTED");
            assertThat(total()).isEqualTo(before);
            if (line == ProductLine.SPOT) {
                assertThat(state.tradingState().users().values().stream().mapToLong(u -> u.totalUnits("BTC")).sum()).isEqualTo(baseBefore);
                for (long userId : new long[] {11,22}) {
                    assertThat(fixture.gateway.userState(userId).balances()).allSatisfy(b -> assertThat(b.lockedUnits()).isZero());
                }
            }
            assertThat(resumed.release(task.taskId()).status()).isEqualTo("RUNNING");
            run(resumed,task.taskId());
            assertThat(resumed.get(task.taskId()).status()).isEqualTo("RELEASED");
            assertThat(resumed.preview("BTC-USDT",0,0).gateMode()).isEqualTo("TRADING");
        }
    }

    @ParameterizedTest @EnumSource(value=ProductLine.class,names="SPOT",mode=EnumSource.Mode.EXCLUDE)
    void clearsAtApprovedPriceDespiteLostReplyAndProviderAndCoreRestart(ProductLine product) throws Exception {
        try (var fixture = fixture(product)) {
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(111,CoreOrderSide.SELL,false)));
            submit(22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(112,CoreOrderSide.BUY,false)));
            long initial = total();
            var task = fixture.service.create("1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.SETTLEMENT,"120","delisting test"));
            dropSettlementReply.set(true);
            run(fixture.service,task.taskId());
            assertThat(fixture.service.get(task.taskId()).status()).isEqualTo("BLOCKED");
            assertThat(fixture.repository.pendingAction(fixture.service.get(task.taskId()))).isNotNull();
            restartCore();
            var resumed = fixture.newService(); resumed.retry(task.taskId()); run(resumed,task.taskId());
            assertThat(resumed.get(task.taskId()).status()).isEqualTo("COMPLETED");
            assertThat(resumed.preview("BTC-USDT",0,0).positions()).isEmpty();
            assertThat(resumed.preview("BTC-USDT",0,0).gateMode()).isEqualTo("CLOSED");
            assertThat(total()).isEqualTo(initial);
            assertThatThrownBy(() -> resumed.release(task.taskId())).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest @EnumSource(value=ProductLine.class,names="SPOT",mode=EnumSource.Mode.EXCLUDE)
    void noLiquidityNeverMarksClosingCompleteAndPreservesThePosition(ProductLine product) throws Exception {
        try (var fixture = fixture(product)) {
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(111,CoreOrderSide.SELL,false)));
            submit(22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(112,CoreOrderSide.BUY,false)));
            long initial = total();
            var task = fixture.service.create("1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","22",MaintenanceRequest.Mode.MARKET,"0","partial close test"));
            run(fixture.service,task.taskId());
            assertThat(fixture.service.get(task.taskId()).status()).isEqualTo("BLOCKED");
            assertThat(fixture.service.get(task.taskId()).phase()).isEqualTo("VERIFY");
            assertThat(fixture.service.preview("BTC-USDT",22,0).positions()).isNotEmpty();
            assertThat(total()).isEqualTo(initial);
            fixture.service.retry(task.taskId()); run(fixture.service,task.taskId());
            assertThat(fixture.service.get(task.taskId()).status()).isEqualTo("BLOCKED");
            assertThat(fixture.service.actions(task.taskId(),"")).hasSize(2);
            fixture.service.release(task.taskId());
            run(fixture.service,task.taskId());
        }
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.MethodSource("matchingModes")
    void matchingCloseReportsPartialFillThenRetriesOnlyTheRemainingPosition(ProductLine product,MaintenanceRequest.Mode mode) throws Exception {
        try (var fixture = fixture(product)) {
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(111,CoreOrderSide.SELL,false)));
            submit(22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(112,CoreOrderSide.BUY,false)));
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(113,CoreOrderSide.BUY,true,2)));
            long initial = total();
            var task = fixture.service.create("1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","22",mode,mode==MaintenanceRequest.Mode.LIMIT?"100":"0","partial matching close"));
            run(fixture.service,task.taskId());
            assertThat(fixture.service.get(task.taskId()).status()).as(fixture.service.get(task.taskId()).error()).isEqualTo("BLOCKED");
            assertThat(fixture.service.preview("BTC-USDT",22,0).positions()).singleElement().satisfies(p -> assertThat(p.signedQuantitySteps()).isEqualTo("2"));
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(114,CoreOrderSide.BUY,true,2)));
            fixture.service.retry(task.taskId()); run(fixture.service,task.taskId());
            assertThat(fixture.service.get(task.taskId()).status()).as(fixture.service.get(task.taskId()).error()).isEqualTo("COMPLETED");
            assertThat(fixture.service.preview("BTC-USDT",22,0).positions()).isEmpty();
            assertThat(total()).isEqualTo(initial);
            assertThat(fixture.service.actions(task.taskId(),"")).hasSize(2);
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> matchingModes() {
        return java.util.Arrays.stream(ProductLine.values()).filter(p->p!=ProductLine.SPOT)
                .flatMap(p->java.util.stream.Stream.of(MaintenanceRequest.Mode.MARKET,MaintenanceRequest.Mode.LIMIT)
                        .map(m->org.junit.jupiter.params.provider.Arguments.of(p,m)));
    }

    @ParameterizedTest @EnumSource(value=ProductLine.class,names="SPOT",mode=EnumSource.Mode.EXCLUDE)
    void fixedPriceClearanceClosesBothHedgedLegs(ProductLine product) throws Exception {
        try(var fixture=fixture(product)) {
            submit(11,CoreMessageType.UPDATE_POSITION_MODE,TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(CorePositionMode.HEDGE)));
            submit(22,CoreMessageType.UPDATE_POSITION_MODE,TradingCommandCodec.encodeUpdatePositionMode(new UpdatePositionModeCommand(CorePositionMode.HEDGE)));
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(hedgedOrder(111,CoreOrderSide.SELL,CorePositionSide.SHORT,4)));
            submit(22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(hedgedOrder(112,CoreOrderSide.BUY,CorePositionSide.LONG,4)));
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(hedgedOrder(113,CoreOrderSide.BUY,CorePositionSide.LONG,3)));
            submit(22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(hedgedOrder(114,CoreOrderSide.SELL,CorePositionSide.SHORT,3)));
            assertThat(fixture.service.preview("BTC-USDT",0,0).positions()).hasSize(4);
            long initial=total();
            var task=fixture.service.create("1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.SETTLEMENT,"120","hedged clearance"));
            run(fixture.service,task.taskId());
            assertThat(fixture.service.get(task.taskId()).status()).as(fixture.service.get(task.taskId()).error()).isEqualTo("COMPLETED");
            assertThat(fixture.service.preview("BTC-USDT",0,0).positions()).isEmpty();
            assertThat(total()).isEqualTo(initial);
        }
    }

    @Test void knownGateRejectionCanBeRetriedAfterFundingFinishes() throws Exception {
        try(var fixture=fixture(ProductLine.LINEAR_PERPETUAL)) {
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(111,CoreOrderSide.SELL,false)));
            submit(22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(112,CoreOrderSide.BUY,false)));
            var progress=CoreFundingProgressCodec.decode(submit(0,CoreMessageType.APPLY_FUNDING,
                    TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(51,"BTC-USDT",1,10_000,0,1))).data());
            assertThat(progress.complete()).isFalse();
            var task=fixture.service.create("1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.CANCEL,"0","wait for funding"));
            fixture.service.tick();
            assertThat(fixture.service.get(task.taskId()).status()).isEqualTo("BLOCKED");
            assertThat(fixture.service.get(task.taskId()).roundNo()).isEqualTo(1);
            for(int n=0;!progress.complete();n++) {
                assertThat(n).isLessThan(10);
                progress=CoreFundingProgressCodec.decode(submit(0,CoreMessageType.APPLY_FUNDING,
                        TradingCommandCodec.encodeApplyFunding(new ApplyFundingCommand(51,"BTC-USDT",1,10_000,progress.nextCursorUserId(),1))).data());
            }
            fixture.service.retry(task.taskId()); run(fixture.service,task.taskId());
            assertThat(fixture.service.get(task.taskId()).status()).as(fixture.service.get(task.taskId()).error()).isEqualTo("COMPLETED");
        }
    }

    @Test void existingLiquidationRejectsClearanceWithoutFreezingRiskAndTaskCanBeAbandoned() throws Exception {
        try(var fixture=fixture(ProductLine.LINEAR_PERPETUAL)) {
            submit(11,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(111,CoreOrderSide.SELL,false)));
            submit(22,CoreMessageType.PLACE_ORDER,TradingCommandCodec.encodePlaceOrder(order(112,CoreOrderSide.BUY,false)));
            submit(0,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("BTC-USDT",1,10_000,2,1_700_000_000_100L)));
            submit(0,CoreMessageType.CONTINUE_RISK_SCAN,TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(16)));
            assertThat(state.tradingState().riskState().liquidations()).isNotEmpty();
            var task=fixture.service.create("1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.SETTLEMENT,"120","wait for liquidation"));
            fixture.service.tick();
            assertThat(fixture.service.get(task.taskId()).phase()).isEqualTo("GATE_REJECTED");
            assertThat(fixture.service.preview("BTC-USDT",0,0).gateMode()).isEqualTo("TRADING");
            assertThat(fixture.service.release(task.taskId()).status()).isEqualTo("RELEASED");
            assertThat(state.tradingState().riskState().liquidations()).isNotEmpty();
            assertThat(fixture.service.create("1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.CANCEL,"0","new task")).id()).isNotEqualTo(task.id());
        }
    }

    @Test void unknownGateAndReleaseOutcomesResumeTheirOwnPhaseAndIdentity() throws Exception {
        try(var fixture=fixture(ProductLine.LINEAR_PERPETUAL)) {
            var task=fixture.service.create("1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.CANCEL,"0","gate recovery"));
            dropGateReply.set(true); fixture.service.tick();
            assertThat(fixture.service.get(task.taskId()).status()).isEqualTo("BLOCKED");
            assertThat(fixture.service.get(task.taskId()).roundNo()).isZero();
            assertThatThrownBy(()->fixture.service.release(task.taskId())).isInstanceOf(IllegalArgumentException.class);
            restartCore();
            var resumed=fixture.newService(); resumed.retry(task.taskId()); run(resumed,task.taskId());
            assertThat(resumed.get(task.taskId()).status()).isEqualTo("COMPLETED");
            resumed.release(task.taskId()); dropGateReply.set(true); resumed.tick();
            assertThat(resumed.get(task.taskId()).status()).isEqualTo("BLOCKED");
            assertThat(resumed.get(task.taskId()).phase()).isEqualTo("RELEASE");
            assertThat(resumed.preview("BTC-USDT",0,0).gateMode()).isEqualTo("TRADING");
            resumed.retry(task.taskId()); run(resumed,task.taskId());
            assertThat(resumed.get(task.taskId()).status()).isEqualTo("RELEASED");
        }
    }

    @Test void providerReplicasSkipLockedTasksAndCannotMixProductLines() throws Exception {
        try(var fixture=fixture(ProductLine.LINEAR_PERPETUAL);
            var executor=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first=fixture.repository.create(line,"1",new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.CANCEL,"0","first"));
            var second=fixture.repository.create(line,"1",new MaintenanceRequest(UUID.randomUUID(),"ETH-USDT","",MaintenanceRequest.Mode.CANCEL,"0","second"));
            assertThatThrownBy(()->fixture.repository.get(ProductLine.SPOT,first.taskId(),false)).isInstanceOf(IllegalArgumentException.class);
            var locked=new java.util.concurrent.CountDownLatch(1);
            var release=new java.util.concurrent.CountDownLatch(1);
            var transaction=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(fixture.source));
            var owner=executor.submit(()->transaction.execute(status->{
                var selected=fixture.repository.next(line); locked.countDown();
                try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("test lock timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return selected.id();
            }));
            try {
                assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String otherId=transaction.execute(status->fixture.repository.next(line).id());
                assertThat(otherId).isEqualTo(second.id());
            } finally { release.countDown(); }
            assertThat(owner.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first.id());
        }
    }

    @Test void rejectsUnsafeScopesAndPrices() {
        assertThatThrownBy(() -> new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","22",MaintenanceRequest.Mode.SETTLEMENT,"120","test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.LIMIT,"0","test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MaintenanceRequest(UUID.randomUUID(),"BTC-USDT","",MaintenanceRequest.Mode.MARKET,"0","test").validate(ProductLine.SPOT)).isInstanceOf(IllegalArgumentException.class);
    }

    private Fixture fixture(ProductLine product) throws Exception {
        line=product; sequence=0; state=new TradingCoreRuntime(line);
        var type=ContractType.valueOf(line.contractTypeCode()); String asset=type.isInverse()?"BTC":"USDT";
        submit(1,CoreMessageType.UPSERT_INSTRUMENT,TradingCommandCodec.encodeUpsertInstrument(new UpsertInstrumentCommand("BTC-USDT",1,type.ordinal(),"BTC","USDT",asset,1,1,type.isInverse()?1000:1,100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,type.isOption()?0:-1,type.isOption()?100:0)));
        submit(1,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(type.isOption()?new ApplyMarkPriceCommand("BTC-USDT",1,100,100,100,1,1_700_000_000_000L):new ApplyMarkPriceCommand("BTC-USDT",1,100,1,1_700_000_000_000L)));
        submit(11,CoreMessageType.ADJUST_BALANCE,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(line==ProductLine.SPOT?"BTC":asset,20_000)));
        submit(22,CoreMessageType.ADJUST_BALANCE,TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset,20_000)));
        return new Fixture();
    }
    private class Fixture implements AutoCloseable {
        final PGSimpleDataSource source = new PGSimpleDataSource();
        final TradingOrderProperties properties = new TradingOrderProperties();
        final MaintenanceAeronGateway gateway = mock(MaintenanceAeronGateway.class);
        final MaintenanceRepository repository;
        final MaintenanceService service;
        Fixture() throws Exception {
            source.setUrl(System.getenv("MAINTENANCE_TEST_JDBC_URL")); source.setUser("maintenance");
            var jdbc = new JdbcTemplate(source);
            // Dedicated test database must be initialized once with the root init.sql.
            jdbc.execute("TRUNCATE trading_maintenance_action,trading_maintenance_task RESTART IDENTITY CASCADE");
            repository = new MaintenanceRepository(jdbc); properties.getKafka().setProductLine(line);
            when(gateway.maintenance(anyString(),anyLong(),anyInt())).thenAnswer(i -> CoreMaintenanceCodec.decodePage(query(0,CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY,CoreMaintenanceCodec.encodeQuery(new CoreMaintenanceCodec.Query(i.getArgument(0),i.getArgument(1),i.getArgument(2)))).data()));
            when(gateway.userState(anyLong())).thenAnswer(i -> CoreStateQueryCodec.decodeUserState(query(i.getArgument(0),CoreMessageType.USER_STATE_QUERY,new byte[0]).data()));
            when(gateway.openOrders(anyLong(),anyString(),anyLong(),anyInt())).thenAnswer(i -> CoreStateQueryCodec.decodeOpenOrders(query(i.getArgument(0),CoreMessageType.USER_OPEN_ORDERS_QUERY,CoreStateQueryCodec.encodeOpenOrdersQuery(new CoreOpenOrdersQuery(i.getArgument(1),i.getArgument(2),i.getArgument(3)))).data()).orders());
            when(gateway.orderState(anyLong(),anyLong())).thenAnswer(i -> CoreStateQueryCodec.decodeOrderState(query(i.getArgument(0),CoreMessageType.ORDER_STATE_QUERY,TradingCommandCodec.encodeOrderStateQuery(i.getArgument(1))).data()));
            when(gateway.settlementProgress(anyString())).thenAnswer(i -> CoreSettlementProgressCodec.decode(query(0,CoreMessageType.SETTLEMENT_PROGRESS_QUERY,CoreStateQueryCodec.encodeSettlementProgressQuery(i.getArgument(0))).data()));
            when(gateway.command(any(),any(),anyLong(),any())).thenAnswer(i -> {
                var response = apply((CoreMessageType)i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));
                if (i.getArgument(0)==CoreMessageType.SETTLE_INSTRUMENT && dropSettlementReply.getAndSet(false)) throw new IllegalStateException("injected lost reply after Core commit");
                if(response.commandStatus()!=ResponseStatus.APPLIED) throw new IllegalStateException(response.resultCode().name()); return response;
            });
            when(gateway.commandOutcome(any(),any(),anyLong(),any())).thenAnswer(i -> {
                var response=apply((CoreMessageType)i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3));
                if(i.getArgument(0)==CoreMessageType.UPDATE_INSTRUMENT_MAINTENANCE && dropGateReply.getAndSet(false)) return new CoreCommandOutcome.ResultUnknown(i.getArgument(1));
                return new CoreCommandOutcome.Terminal(response);
            });
            when(gateway.openTriggers(anyLong(),anyString(),anyLong(),anyInt())).thenAnswer(i -> CoreTriggerOrderCodec.decodeList(query(i.getArgument(0),CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY,CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(0,i.getArgument(1),i.getArgument(2),i.getArgument(3)))).data()));
            when(gateway.triggerState(anyLong(),anyLong())).thenAnswer(i -> CoreTriggerOrderCodec.decodeList(query(i.getArgument(0),CoreMessageType.TRIGGER_ORDER_QUERY,CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(i.getArgument(1),"",0,1))).data()).getFirst());
            service=newService();
        }
        MaintenanceService newService() { return new MaintenanceService(properties,repository,gateway,new ObjectMapper(),new DataSourceTransactionManager(source)); }
        @Override public void close() { state.close(); }
    }
    private void restartCore() { byte[] snapshot=state.snapshot(900); state.close(); state=TradingCoreRuntime.fromSnapshot(line,snapshot); }
    private void run(MaintenanceService service,long id) { int steps=0; while(service.get(id).status().equals("RUNNING")) { assertThat(++steps).isLessThan(150); service.tick(); } }
    private CoreResponse submit(long user,CoreMessageType type,byte[] bytes) { var response=apply(type,UUID.randomUUID(),user,bytes); assertThat(response.commandStatus()).as("%s",response.resultCode()).isEqualTo(ResponseStatus.APPLIED); return response; }
    private CoreResponse apply(CoreMessageType type,UUID id,long user,byte[] bytes) {
        long seq=++sequence; var message=new CoreMessage(CoreMessageHeader.command(type,id,line,CommandSource.OPERATIONS,998,seq,user,1_700_000_000_000L+seq,seq),bytes);
        var response=state.apply(message);
        if(response.resultCode()==CoreResultCode.MATCHING_PENDING) response=state.commits.completeMatchingSynchronously(state.matchingSequence(id),message.header().submittedAtEpochMillis(),seq);
        while(state.firstPendingMatchingSequence()!=0) state.commits.completeMatchingSynchronously(state.firstPendingMatchingSequence(),message.header().submittedAtEpochMillis(),seq);
        return response;
    }
    private CoreResponse query(long user,CoreMessageType type,byte[] bytes) {
        var header=CoreMessageHeader.query(type,UUID.randomUUID(),line,CommandSource.OPERATIONS,998,++sequence,user,1_700_000_000_000L+sequence,sequence);
        var response=state.apply(new CoreMessage(header,bytes)); assertThat(response.status()).as("%s: %s",type,response.resultCode()).isEqualTo(ResponseStatus.OK); return response;
    }
    private static PlaceOrderCommand order(long id,CoreOrderSide side,boolean reduce) { return new PlaceOrderCommand(id,"BTC-USDT",1,side,100,4,reduce,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"maintenance-fixture-"+id); }
    private static PlaceOrderCommand order(long id,CoreOrderSide side,boolean reduce,long quantity) { return new PlaceOrderCommand(id,"BTC-USDT",1,side,100,quantity,reduce,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"maintenance-fixture-"+id); }
    private static PlaceOrderCommand hedgedOrder(long id,CoreOrderSide side,CorePositionSide positionSide,long quantity) { return new PlaceOrderCommand(id,"BTC-USDT",1,side,100,quantity,false,CoreMarginMode.CROSS,positionSide,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"maintenance-fixture-"+id); }
    private long total() { var s=state.tradingState(); String asset=ContractType.valueOf(line.contractTypeCode()).isInverse()?"BTC":"USDT"; var t=s.treasuryState(); return s.users().values().stream().mapToLong(u -> u.totalUnits(asset)).sum()+t.feeBalances().getOrDefault(asset,0L)+t.insuranceBalances().getOrDefault(asset,0L)+t.clearingPnlBalances().getOrDefault(asset,0L)+t.roundingResidualBalances().getOrDefault(asset,0L); }
}
