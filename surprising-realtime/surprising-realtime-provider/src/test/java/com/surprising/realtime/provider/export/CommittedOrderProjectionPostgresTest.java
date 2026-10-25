package com.surprising.realtime.provider.export;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import com.surprising.realtime.provider.export.service.CommittedOrderProjectionService;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "INSTRUMENT_TEST_JDBC_URL", matches = ".+")
class CommittedOrderProjectionPostgresTest {
    private JdbcTemplate jdbc;
    private DriverManagerDataSource source;
    private CommittedOrderProjectionService repository;

    @BeforeEach void prepare() {
        String url = System.getenv("INSTRUMENT_TEST_JDBC_URL");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url,
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD")));
        admin.execute("DROP SCHEMA IF EXISTS order_projection_it CASCADE");
        admin.execute("CREATE SCHEMA order_projection_it");
        source = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=order_projection_it&reWriteBatchedInserts=true",
                System.getenv("INSTRUMENT_TEST_DB_USER"), System.getenv("INSTRUMENT_TEST_DB_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE core_order_projection (LIKE public.core_order_projection INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_projection_watermark (LIKE public.core_projection_watermark INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_command_result_projection (LIKE public.core_command_result_projection INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_trigger_order_projection (LIKE public.core_trigger_order_projection INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_algo_order_projection (LIKE public.core_algo_order_projection INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_algo_projection_initialization (LIKE public.core_algo_projection_initialization INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_trigger_projection_initialization (LIKE public.core_trigger_projection_initialization INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_execution_projection (LIKE public.core_execution_projection INCLUDING ALL)");
        jdbc.execute("CREATE TABLE core_execution_projection_initialization (LIKE public.core_execution_projection_initialization INCLUDING ALL)");
        for(var name:List.of("core_trading_command_statistics","core_trading_statistics_cursor","core_trading_statistics_initialization","core_trading_statistics_snapshot"))
            jdbc.execute("CREATE TABLE "+name+" (LIKE public."+name+" INCLUDING ALL)");
        jdbc.execute("CREATE TABLE market_maker_strategies (product_line text, account_ids bigint[])");
        jdbc.execute("CREATE TABLE market_maker_business_settings (product_line text, settings jsonb)");
        repository = ProjectionTestFixtures.orders(jdbc, new DataSourceTransactionManager(source));
    }

    @AfterEach void clean() { if (jdbc != null) jdbc.execute("DROP SCHEMA order_projection_it CASCADE"); }

    @Test
    void springPersistenceContextRollsBackReceiptsStatisticsAndWatermarkTogether() {
        try (var context = com.surprising.realtime.provider.export.config.CommittedProjectionConfiguration.openPersistence(source)) {
            var stores = context.getBeansWithAnnotation(org.springframework.stereotype.Repository.class);
            assertThat(stores).isNotEmpty();
            assertThat(stores.values()).allSatisfy(store ->
                    assertThat(org.springframework.aop.support.AopUtils.isAopProxy(store)).isTrue());
            var service = context.getBean(CommittedOrderProjectionService.class);
            var product = ProductLine.OPTION;
            long now = System.currentTimeMillis();
            var first = new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(
                    product, 7, new java.util.UUID(0, 1), CoreMessageType.PLACE_ORDER, "first", "604",
                    ResponseStatus.APPLIED, CoreResultCode.NONE, 10, 10, now, List.of());
            service.persist(product, List.of(), List.of(first), 10);
            jdbc.execute("ALTER TABLE core_projection_watermark ADD CONSTRAINT mvc_watermark_fault CHECK(last_export_sequence < 20)");
            var second = new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(
                    product, 7, new java.util.UUID(0, 2), CoreMessageType.PLACE_ORDER, "second", "604",
                    ResponseStatus.APPLIED, CoreResultCode.NONE, 20, 20, now, List.of());
            assertThatThrownBy(() -> service.persist(product, List.of(), List.of(second), 20))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core_command_result_projection", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT sum(commands) FROM core_trading_command_statistics", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_command_position FROM core_trading_statistics_cursor", Long.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(10);
        }
    }

    @Test void springRepositoryProxyDelegatesStatisticsToTheSameTransactionalTarget() {
        var factory=new org.springframework.aop.framework.ProxyFactory(repository);factory.setProxyTargetClass(true);
        factory.addAdvice((org.aopalliance.intercept.MethodInterceptor)invocation->invocation.proceed());
        var proxy=(CommittedOrderProjectionService)factory.getProxy();
        assertThat(org.springframework.aop.support.AopUtils.isCglibProxy(proxy)).isTrue();
        var result=new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(ProductLine.OPTION,7,new java.util.UUID(0,1),
                CoreMessageType.PLACE_ORDER,"client","604",ResponseStatus.APPLIED,CoreResultCode.NONE,10,10,System.currentTimeMillis(),List.of());
        proxy.persist(ProductLine.OPTION,List.of(),List.of(result),10);
        proxy.statistics().publish(ProductLine.OPTION,new com.surprising.aeron.service.query.state.CommittedTradingActivity(0,0,0,0,0,List.of()),10,System.currentTimeMillis());
        assertThat(jdbc.queryForObject("SELECT sum(commands) FROM core_trading_command_statistics",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_trading_statistics_snapshot",Integer.class)).isEqualTo(4);
    }

    @Test void conflictingRetryCountsAsRejectedButCannotReplaceTheOriginalSuccessfulReceipt() {
        var line=ProductLine.OPTION;long now=System.currentTimeMillis();
        var original=new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(line,7,new java.util.UUID(0,1),
                CoreMessageType.PLACE_ORDER,"client","604",ResponseStatus.APPLIED,CoreResultCode.NONE,10,10,now,List.of());
        var conflict=new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(line,7,original.commandId(),
                CoreMessageType.PLACE_ORDER,"client","604",ResponseStatus.REJECTED,CoreResultCode.IDEMPOTENCY_CONFLICT,20,20,now,List.of());
        repository.persist(line,List.of(),List.of(original),10);repository.persist(line,List.of(),List.of(conflict),20);
        assertThat(jdbc.queryForObject("SELECT result_code FROM core_command_result_projection",String.class)).isEqualTo("NONE");
        assertThat(jdbc.queryForObject("SELECT sum(commands) FROM core_trading_command_statistics",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT commands FROM core_trading_command_statistics WHERE command_status='REJECTED'",Long.class)).isEqualTo(1);
    }

    private static RealtimeFrame trigger(long id, long user, CoreTriggerOrderStatus status, long revision) {
        var v=new CoreTriggerOrderStateView(id,ProductLine.OPTION,user,"client-"+id,"oco","74",CoreOrderSide.SELL,
                CoreTriggerOrderType.TAKE_PROFIT,CoreTriggerCondition.GREATER_OR_EQUAL,120,0,0,0,0,0,
                CoreOrderType.MARKET,CoreTimeInForce.IOC,0,1,CoreMarginMode.CROSS,CorePositionSide.NET,status,
                0,0,0,"",0,0,1000,1000+revision,revision,CoreTriggerPriceSource.LAST);
        return new RealtimeFrame(ProductLine.OPTION,RealtimeFrame.Kind.TRIGGER,user,10,0,1000,0,"74",Long.toString(id),CoreTriggerOrderCodec.encodeList(List.of(v)));
    }

    @Test void actualOptionCommittedReplayContainsTheExecutionMetadataNeededBySqlWithoutCoreReads() {
        var line = ProductLine.OPTION;
        try (var replay = new com.surprising.aeron.service.replay.CommittedTradeReplay(line,null)) {
            var frames = new java.util.ArrayList<RealtimeFrame>();
            long seq = 0;
            frames.addAll(replayed(replay,++seq,CoreMessageType.REGISTER_INSTRUMENT,0,TradingCommandCodec.encodeRegisterInstrument(
                    new RegisterInstrumentCommand(
                            "604", "BTC", "USDT", "USDT", 1, 1, 1,
                            new CoreInstrumentRiskLimits(
                                    100000, (1_000_000_000_000L / (100000)), Long.MAX_VALUE, 0, Long.MAX_VALUE,
                                    java.util.List.of(new CoreRiskLimitBracket(Long.MAX_VALUE, (1_000_000_000_000L / (100000)), 100000, 50000))),
                            0, 0, 2000000000000L, 0, 100), line)));
            for(long user:new long[]{7,8})frames.addAll(replayed(replay,++seq,CoreMessageType.ADJUST_BALANCE,user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT",1000000))));
            frames.addAll(replayed(replay,++seq,CoreMessageType.APPLY_MARK_PRICE,0,TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("604",100,100,100,1,1700000000000L), line)));
            for(boolean maker:new boolean[]{true,false})frames.addAll(replayed(replay,++seq,CoreMessageType.PLACE_ORDER,maker?7:8,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(maker?201:202,"604",maker?CoreOrderSide.SELL:CoreOrderSide.BUY,
                            100,2,false,CoreMarginMode.CROSS,CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,maker?"maker":"taker"))));
            assertThat(frames).anyMatch(f->f.kind()==RealtimeFrame.Kind.TRADE);
            long before = replay.businessHash();
            repository.persist(line,frames,List.of(),replay.exportSequence());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core_execution_projection",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT taker_order_id FROM core_execution_projection",Long.class)).isEqualTo(202);
            assertThat(jdbc.queryForObject("SELECT maker_order_id FROM core_execution_projection",Long.class)).isEqualTo(201);
            assertThat(jdbc.queryForObject("SELECT quantity_steps FROM core_execution_projection",Long.class)).isEqualTo(2);
            assertThat(replay.businessHash()).isEqualTo(before);
        }
    }

    private static List<RealtimeFrame> replayed(com.surprising.aeron.service.replay.CommittedTradeReplay replay,
            long seq,CoreMessageType type,long user,byte[] payload) {
        long time=1700000000000L+seq;
        var command=new CoreMessage(CoreMessageHeader.command(type,new java.util.UUID(99,seq),ProductLine.OPTION,
                CommandSource.OPERATIONS,991,seq,user,time,seq),payload);
        return replay.apply(CoreMessageCodec.encode(command),time,seq);
    }

    @Test void executionsKeepBothSidesEvenForMakerOnlyTradesAndReplayIdempotentlyAcrossSixProducts() {
        for (var line : ProductLine.values()) {
            jdbc.update("INSERT INTO market_maker_strategies VALUES (?,ARRAY[7,8]::bigint[])", line.name());
            var frames = matched(line, 100, 1000, 3);
            repository.persist(line, frames, List.of(), 1);
            repository.persist(line, frames, List.of(), 1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core_execution_projection WHERE product_line=?", Integer.class, line.name())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT taker_order_id FROM core_execution_projection WHERE product_line=?", Long.class, line.name())).isEqualTo(Long.MAX_VALUE);
            assertThat(jdbc.queryForObject("SELECT taker_fee_rate_ppm FROM core_execution_projection WHERE product_line=?", Long.class, line.name())).isEqualTo(4);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_execution_projection", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isZero();
    }

    @Test void executionConflictAndMissingLegCannotAdvanceTheOrderWatermark() {
        var line = ProductLine.OPTION;
        repository.persist(line, matched(line, 100, 1000, 3), List.of(), 10);
        assertThatThrownBy(() -> repository.persist(line, matched(line, 100, 1001, 3), List.of(), 11))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicting");
        assertThat(jdbc.queryForObject("SELECT occurred_at_epoch_ms FROM core_execution_projection", Long.class)).isEqualTo(1000);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(10);
        var missing = matched(line, 200, 1002, 4);
        assertThatThrownBy(() -> repository.persist(line, missing.subList(0, missing.size()-1), List.of(), 12)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_execution_projection", Integer.class)).isEqualTo(1);
    }

    @Test void executionRepairUsesEachCommandsFeeMetadataAndWritesNoOrdersReceiptsOrWatermark() {
        var frames = new java.util.ArrayList<>(matched(ProductLine.OPTION, 100, 1000, 3));
        frames.addAll(matched(ProductLine.OPTION, 200, 1001, 9));
        repository.repairExecutions(ProductLine.OPTION, frames);
        assertThat(jdbc.queryForList("SELECT taker_fee_rate_ppm FROM core_execution_projection ORDER BY export_sequence", Long.class)).containsExactly(4L, 10L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_command_result_projection", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_projection_watermark", Integer.class)).isZero();
        assertThat(repository.executionsInitialized(ProductLine.OPTION)).isFalse();
        repository.initializeExecutions(ProductLine.OPTION, 200);
        assertThat(repository.executionsInitialized(ProductLine.OPTION)).isTrue();
        assertThat(repository.executionsInitialized(ProductLine.SPOT)).isFalse();
    }

    private static List<RealtimeFrame> matched(ProductLine line, long position, long time, long fee) {
        long taker = Long.MAX_VALUE, maker = 9007199254740993L;
        String id = line+":"+position+":1:"+taker+":0";
        var frames = new java.util.ArrayList<RealtimeFrame>();
        for (boolean isMaker : new boolean[]{false,true}) {
            long order = isMaker ? maker : taker, user = isMaker ? 8 : 7;
            var side = isMaker ? CoreOrderSide.SELL : CoreOrderSide.BUY;
            var state = new CoreOrderStateView(order,line,user,"604",side,100,1,1,0,false,CoreMarginMode.CROSS,
                    CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"client-"+order,new java.util.UUID(0,order),
                    fee,fee+1,0,0,100,time,time,position,"FILLED",2);
            frames.add(RealtimeFrameCodec.decode(RealtimeFrameCodec.encodeOrder(state,position,frames.size(),time,0)));
        }
        frames.add(RealtimeFrameCodec.decode(RealtimeFrameCodec.encodeTradePayload(line,position,2,time,0,"604",id,100,1,1,(byte)0)));
        frames.add(RealtimeFrameCodec.decode(RealtimeFrameCodec.encodeExecutionPayload(line,7,position,3,time,0,"604",id,taker,100,1,(byte)0,false)));
        frames.add(RealtimeFrameCodec.decode(RealtimeFrameCodec.encodeExecutionPayload(line,8,position,4,time,0,"604",id,maker,100,1,(byte)1,true)));
        return frames;
    }

    @Test void triggerVersionsTerminalHistoryAndReceiptsCommitWithTheWatermark() {
        var first=trigger(Long.MAX_VALUE,7,CoreTriggerOrderStatus.PENDING,1);
        var terminal=trigger(Long.MAX_VALUE,7,CoreTriggerOrderStatus.CANCELED,2);
        repository.persist(ProductLine.OPTION,List.of(first,terminal),List.of(),10);
        repository.persist(ProductLine.OPTION,List.of(first),List.of(),11);
        assertThat(jdbc.queryForObject("SELECT status FROM core_trigger_order_projection",String.class)).isEqualTo("CANCELED");
        assertThat(jdbc.queryForObject("SELECT revision FROM core_trigger_order_projection",Long.class)).isEqualTo(2);
        assertThatThrownBy(()->repository.persist(ProductLine.OPTION,List.of(trigger(Long.MAX_VALUE,8,CoreTriggerOrderStatus.CANCELED,3)),List.of(),12))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicting");
        assertThat(jdbc.queryForObject("SELECT user_id FROM core_trigger_order_projection",Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark",Long.class)).isEqualTo(11);
        assertThatThrownBy(()->repository.persist(ProductLine.OPTION,List.of(trigger(Long.MAX_VALUE,7,CoreTriggerOrderStatus.TRIGGERED,2)),List.of(),12))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicting");
    }

    @Test void incompleteTriggerSnapshotIsHiddenAndReconstructionRetriesWithoutRegressingState() {
        var replay=org.mockito.Mockito.mock(com.surprising.aeron.service.replay.CommittedTradeReplay.class);
        var state=CoreTriggerOrderCodec.decodeList(trigger(90,7,CoreTriggerOrderStatus.CANCELED,2).payload()).getFirst();
        org.mockito.Mockito.when(replay.retainedTriggersBefore(0,512)).thenReturn(List.of(state));
        org.mockito.Mockito.when(replay.retainedTriggersBefore(90,512)).thenThrow(new IllegalStateException("incomplete snapshot"));
        org.mockito.Mockito.when(replay.exportSequence()).thenReturn(10L);
        assertThatThrownBy(()->repository.initializeTriggers(ProductLine.OPTION,replay,32)).hasMessage("incomplete snapshot");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_trigger_projection_initialization",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_trigger_order_projection",Integer.class)).isEqualTo(1);
        org.mockito.Mockito.reset(replay);
        org.mockito.Mockito.when(replay.retainedTriggersBefore(0,512)).thenReturn(List.of(state));
        org.mockito.Mockito.when(replay.retainedTriggersBefore(90,512)).thenReturn(List.of());
        org.mockito.Mockito.when(replay.exportSequence()).thenReturn(10L);
        repository.initializeTriggers(ProductLine.OPTION,replay,32);
        assertThat(jdbc.queryForObject("SELECT snapshot_log_position FROM core_trigger_projection_initialization",Long.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_trigger_order_projection",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_projection_watermark",Integer.class)).isZero();
    }

    @Test void makerProtectionReceiptsAreRetainedWhileTransientQuoteReceiptsStayFiltered() {
        jdbc.update("INSERT INTO market_maker_strategies VALUES ('OPTION', ARRAY[900]::bigint[])");
        var receipts=new java.util.ArrayList<com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult>();
        for(var type:List.of(CoreMessageType.PLACE_TRIGGER_OCO_PAIR,CoreMessageType.PLACE_TRIGGER_ORDER,CoreMessageType.CANCEL_TRIGGER_ORDER,CoreMessageType.CANCEL_ORDER)) {
            int i=receipts.size()+1;
            receipts.add(new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(
                    ProductLine.OPTION,900,new java.util.UUID(0,i),type,null,null,ResponseStatus.APPLIED,CoreResultCode.NONE,i,32L*i,1000L+i,List.of()));
        }
        repository.persist(ProductLine.OPTION,List.of(),receipts,10);
        assertThat(jdbc.queryForList("SELECT command_type FROM core_command_result_projection ORDER BY command_type",String.class))
                .containsExactly("CANCEL_TRIGGER_ORDER","PLACE_TRIGGER_OCO_PAIR","PLACE_TRIGGER_ORDER");
        jdbc.execute("DELETE FROM core_command_result_projection");
        repository.repairCommandResults(ProductLine.OPTION,receipts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_command_result_projection",Integer.class)).isEqualTo(3);
    }

    @Test void cancelBatchFactsReplayExactlyAndRejectDifferentOrMissingDetails() {
        var receipt = batchResult(false);
        repository.persist(ProductLine.SPOT, List.of(), List.of(receipt), 10);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(cancel_batch_results) FROM core_command_result_projection", Integer.class)).isEqualTo(2);
        repository.persist(ProductLine.SPOT, List.of(), List.of(receipt), 10);
        assertThat(jdbc.queryForObject("SELECT cancel_batch_results->1->>'code' FROM core_command_result_projection", String.class)).isEqualTo("ORDER_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark WHERE product_line='SPOT'", Long.class)).isEqualTo(10);
        assertThatThrownBy(() -> repository.persist(ProductLine.SPOT, List.of(), List.of(batchResult(true)), 11))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicting");
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark WHERE product_line='SPOT'", Long.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT cancel_batch_results->0->>'success' FROM core_command_result_projection", String.class)).isEqualTo("true");
        jdbc.update("UPDATE core_command_result_projection SET cancel_batch_results=NULL");
        assertThatThrownBy(() -> repository.repairCommandResults(ProductLine.SPOT, List.of(receipt)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("conflicting");
        assertThat(jdbc.queryForObject("SELECT cancel_batch_results FROM core_command_result_projection", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark WHERE product_line='SPOT'", Long.class)).isEqualTo(10);
    }

    private static com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult batchResult(boolean conflict) {
        return new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(
                ProductLine.SPOT, 7, new java.util.UUID(3, 4), CoreMessageType.CANCEL_ORDER_BATCH, null, null,
                ResponseStatus.APPLIED, CoreResultCode.NONE, 9, 288, 1700000000009L, List.of(
                    new com.surprising.aeron.service.replay.CommittedTradeReplay.CancelBatchItem(0, 91, !conflict, conflict ? "ORDER_NOT_FOUND" : "NONE"),
                    new com.surprising.aeron.service.replay.CommittedTradeReplay.CancelBatchItem(1, 92, false, "ORDER_NOT_FOUND")));
    }

    @Test void rejectedCommandsWithoutOrdersRemainQueryableAfterRestartAndMoreThan128OtherResults() {
        var result = result(1, 7, CoreResultCode.ORDER_NOT_FOUND);
        repository.persist(ProductLine.SPOT, List.of(), List.of(result), 0);
        repository = ProjectionTestFixtures.orders(jdbc, new DataSourceTransactionManager(source));
        repository.persist(ProductLine.SPOT, List.of(), List.of(result), 0);
        var later = new java.util.ArrayList<com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult>();
        for (int i=2;i<150;i++) later.add(result(i, 7, CoreResultCode.NONE));
        repository.persist(ProductLine.SPOT, List.of(), later, 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_command_result_projection", Integer.class)).isEqualTo(149);
        assertThat(jdbc.queryForObject("SELECT result_code FROM core_command_result_projection WHERE command_id=?",
                String.class, result.commandId())).isEqualTo("ORDER_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isZero();
    }

    @Test void receiptBackfillDoesNotCreateOrdersOrAdvanceTheirVisibilityWatermark() {
        repository.persist(ProductLine.SPOT, List.of(), List.of(), 3);
        repository.repairCommandResults(ProductLine.SPOT, List.of(result(999, 7, CoreResultCode.ORDER_NOT_FOUND)));
        repository.repairCommandResults(ProductLine.SPOT, List.of(result(999, 7, CoreResultCode.ORDER_NOT_FOUND)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_command_result_projection", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isZero();
    }

    @Test void conflictingCommandOutcomeRollsBackItsOrdersAndWatermark() {
        repository.persist(ProductLine.SPOT, List.of(), List.of(result(1, 7, CoreResultCode.NONE)), 1);
        assertThatThrownBy(() -> repository.persist(ProductLine.SPOT,
                List.of(frame(ProductLine.SPOT, 91, "OPEN", 1)),
                List.of(result(1, 7, CoreResultCode.ORDER_NOT_FOUND)), 2))
                .isInstanceOf(IllegalStateException.class).hasMessage("conflicting committed command result");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result_code FROM core_command_result_projection", String.class)).isEqualTo("NONE");
    }

    @Test void filtersMakerReceiptsAndKeepsUserOutcomesIsolatedByProduct() {
        jdbc.update("INSERT INTO market_maker_strategies VALUES ('SPOT', ARRAY[900]::bigint[])");
        repository.persist(ProductLine.SPOT, List.of(), List.of(result(1, 900, CoreResultCode.NONE), result(2, 7, CoreResultCode.NONE)), 1);
        assertThat(jdbc.queryForList("SELECT user_id FROM core_command_result_projection", Long.class)).containsExactly(7L);
        assertThatThrownBy(() -> repository.persist(ProductLine.OPTION, List.of(), List.of(result(3,7,CoreResultCode.NONE)), 2))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("cross-product command result");
    }

    private static com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult result(
            long id, long user, CoreResultCode code) {
        return new com.surprising.aeron.service.replay.CommittedTradeReplay.CommittedCommandResult(
                ProductLine.SPOT, user, new java.util.UUID(0, id), CoreMessageType.CANCEL_ORDER,
                null, null, code == CoreResultCode.NONE ? ResponseStatus.APPLIED : ResponseStatus.REJECTED,
                code, id, id * 32, 1700000000000L + id, List.of());
    }

    @Test void emptyOrderBatchesAdvanceOnlyWatermarkWithoutDependingOnMakerCatalog() {
        // An empty order batch must not query either catalog table.
        jdbc.execute("DROP TABLE market_maker_strategies, market_maker_business_settings");
        for (var product : ProductLine.values()) {
            repository.persist(product, List.of(), List.of(), 10);
            repository.persist(product, List.of(), List.of(), 9);
            assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark WHERE product_line=?",
                    Long.class, product.name())).isEqualTo(10);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isZero();
    }

    @Test void idempotentRestartAndOldRevisionsCannotReplaceTerminalOrdersAcrossSixLines() {
        for (var line : ProductLine.values()) {
            repository.persist(line, List.of(frame(line, 91, "OPEN", 1)), List.of(), 10);
            repository.persist(line, List.of(frame(line, 91, "FILLED", 2)), List.of(), 11);
            var restarted = ProjectionTestFixtures.orders(jdbc, new DataSourceTransactionManager(source));
            restarted.persist(line, List.of(frame(line, 91, "FILLED", 2)), List.of(), 11);
            restarted.persist(line, List.of(frame(line, 91, "OPEN", 1)), List.of(), 10);
            var raw = jdbc.queryForObject("SELECT raw_order_state FROM core_order_projection WHERE product_line=? AND order_id=91", byte[].class, line.name());
            assertThat(CoreStateQueryCodec.decodeOrderState(raw).status()).isEqualTo("FILLED");
            assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark WHERE product_line=?", Long.class, line.name())).isEqualTo(11);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isEqualTo(6);
    }

    @Test void failedBatchRollsBackOrdersAndWatermarkThenCanBeRetried() {
        var line = ProductLine.LINEAR_PERPETUAL;
        repository.persist(line, List.of(frame(line, 91, "OPEN", 1)), List.of(), 10);
        var batch = new java.util.ArrayList<RealtimeFrame>();
        // The first JDBC batch is already executed before the final row violates the SQL column limit.
        for (int id = 92; id < 604; id++) batch.add(frame(line, id, "FILLED", 1));
        batch.add(frame(line, 604, "X".repeat(33), 1));
        assertThatThrownBy(() -> repository.persist(line, batch, List.of(), 11))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(10);
        repository.persist(line, List.of(frame(line, 92, "FILLED", 1)), List.of(), 11);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isEqualTo(2);
    }

    @Test void coalescesOnlyTheLatestVersionWithinOneSqlBatchAndRejectsCrossProductFrames() {
        var line = ProductLine.LINEAR_PERPETUAL;
        repository.persist(line, List.of(frame(line, 91, "OPEN", 1), frame(line, 91, "FILLED", 2),
                frame(line, 91, "OPEN", 1)), List.of(), 10);
        assertThat(jdbc.queryForObject("SELECT status FROM core_order_projection", String.class)).isEqualTo("FILLED");
        assertThatThrownBy(() -> repository.persist(line, List.of(frame(ProductLine.SPOT, 92, "OPEN", 1)), List.of(), 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT last_export_sequence FROM core_projection_watermark", Long.class)).isEqualTo(10);
    }

    @Test void onlyUserFacingMakerOrdersPersistAcrossBatchesAndRestartForEveryProduct() {
        for (var line : ProductLine.values()) {
            jdbc.update("INSERT INTO market_maker_strategies VALUES (?, ARRAY[900]::bigint[])", line.name());
            jdbc.update("INSERT INTO market_maker_business_settings VALUES (?, '{\"trade\":{\"accountIds\":[901]}}'::jsonb)", line.name());
        }
        for (var line : ProductLine.values()) {
            // Unfilled quotes and cancellations are omitted. Ordinary-user orders always remain.
            repository.persist(line, List.of(order(line, 10, 900, "OPEN", 0, 1),
                    order(line, 11, 900, "CANCELED", 0, 1), order(line, 12, 7, "CANCELED", 0, 1)), List.of(), 1);
            // Different maker accounts and the same maker account both count as internal trading.
            repository.persist(line, List.of(order(line, 20, 900, "FILLED", 2, 2),
                    order(line, 21, 901, "FILLED", 2, 2), execution(line, "internal", 20, 900, true),
                    execution(line, "internal", 21, 901, false)), List.of(), 2);
            repository.persist(line, List.of(order(line, 22, 900, "FILLED", 2, 2),
                    order(line, 23, 900, "FILLED", 2, 2), execution(line, "self", 22, 900, true),
                    execution(line, "self", 23, 900, false)), List.of(), 3);
            // This quote first fills internally, then with a user: store its full cumulative state.
            repository.persist(line, List.of(order(line, 30, 900, "OPEN", 1, 2),
                    execution(line, "first", 30, 900, true), execution(line, "first", 31, 901, false)), List.of(), 4);
            repository.persist(line, List.of(order(line, 30, 900, "OPEN", 2, 3),
                    order(line, 32, 7, "FILLED", 2, 2), execution(line, "user", 30, 900, true),
                    execution(line, "user", 32, 7, false)), List.of(), 5);
            // A maker account acting as taker against a user must also be retained.
            repository.persist(line, List.of(order(line, 40, 901, "FILLED", 2, 2),
                    execution(line, "maker-taker", 40, 901, false), execution(line, "maker-taker", 41, 7, true)), List.of(), 5);
            // Restart, then another internal fill and cancellation must still update the stored order.
            repository = ProjectionTestFixtures.orders(jdbc, new DataSourceTransactionManager(source));
            repository.persist(line, List.of(order(line, 30, 900, "CANCELED", 3, 4),
                    execution(line, "last", 30, 900, true), execution(line, "last", 33, 901, false)), List.of(), 6);
            repository.persist(line, List.of(order(line, 30, 900, "OPEN", 2, 3)), List.of(), 5);
            assertThat(jdbc.queryForList("SELECT order_id FROM core_order_projection WHERE product_line=? ORDER BY order_id",
                    Long.class, line.name())).containsExactly(12L, 30L, 32L, 40L);
            var saved = CoreStateQueryCodec.decodeOrderState(jdbc.queryForObject(
                    "SELECT raw_order_state FROM core_order_projection WHERE product_line=? AND order_id=30", byte[].class, line.name()));
            assertThat(saved.executedQuantitySteps()).isEqualTo(3);
            assertThat(saved.status()).isEqualTo("CANCELED");
        }
    }

    @Test void incompleteCounterpartyEvidenceFailsBeforeWritingOrAdvancingWatermark() {
        jdbc.update("INSERT INTO market_maker_strategies VALUES (?, ARRAY[900]::bigint[])", ProductLine.LINEAR_PERPETUAL.name());
        repository = ProjectionTestFixtures.orders(jdbc, new DataSourceTransactionManager(source));
        var line = ProductLine.LINEAR_PERPETUAL;
        assertThatThrownBy(() -> repository.persist(line, List.of(order(line, 30, 900, "FILLED", 2, 1),
                execution(line, "missing", 30, 900, true)), List.of(), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_order_projection", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core_projection_watermark", Integer.class)).isZero();
    }

    private static RealtimeFrame execution(ProductLine line, String trade, long order, long user, boolean maker) {
        var bytes = java.nio.ByteBuffer.allocate(26).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putLong(order).putLong(100).putLong(1).put((byte) (maker ? 0 : 1)).put((byte) (maker ? 1 : 0)).array();
        return new RealtimeFrame(line, RealtimeFrame.Kind.EXECUTION, user, 100, 0, 1000, 0, "604", trade, bytes);
    }

    private static RealtimeFrame order(ProductLine line, long id, long user, String status, long filled, long revision) {
        var state = new CoreOrderStateView(id, line, user, "604", CoreOrderSide.BUY, 100, 4,
                filled, 4 - filled, false, status, revision);
        return RealtimeFrameCodec.decode(RealtimeFrameCodec.encodeOrder(state, 100 + revision, 0, 1000, 0));
    }

    private static RealtimeFrame frame(ProductLine line, long id, String status, long revision) {
        var order = new CoreOrderStateView(id, line, 7, "604", CoreOrderSide.BUY, 100, 2,
                status.equals("FILLED") ? 2 : 0, status.equals("FILLED") ? 0 : 2, false, status, revision);
        return RealtimeFrameCodec.decode(RealtimeFrameCodec.encodeOrder(order, 100 + revision, 0, 1000, 0));
    }
}
