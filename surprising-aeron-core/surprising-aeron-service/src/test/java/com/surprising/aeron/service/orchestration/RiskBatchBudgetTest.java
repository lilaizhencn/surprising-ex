package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.assertj.core.api.Assertions.assertThat;

class RiskBatchBudgetTest {
    private long sequence;
    private static final long TIME = 1_700_000_000_000L;

    @ParameterizedTest
    @EnumSource(value=ProductLine.class, names={"LINEAR_PERPETUAL","INVERSE_PERPETUAL",
            "LINEAR_DELIVERY","INVERSE_DELIVERY","OPTION"})
    void heavySymbolCannotConsumeEverySliceOfSharedBudget(ProductLine line) throws Exception {
        try (var state = new TradingCoreRuntime(line)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            String asset=type.isInverse()?"BTC":"USDT";
            for (int i=0;i<2;i++) {
                String symbol="SYM"+i+"-USDT";
                applied(state,command(line,CoreMessageType.REGISTER_INSTRUMENT,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(symbol,
                                type.ordinal(),"BTC","USDT",asset,1,1,type.isInverse()?1000:1,
                                100_000,50_000,0,0,type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                                type.isOption()?0:-1,type.isOption()?100:0))));
            }
            for (int i=0;i<2;i++) applied(state,mark(line,"SYM"+i+"-USDT",1));
            for(long user=1;user<=50;user++) {
                applied(state,command(line,CoreMessageType.ADJUST_BALANCE,user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(asset,1_000_000))));
                applied(state,command(line,CoreMessageType.PLACE_ORDER,user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,
                                user<=48?"SYM0-USDT":"SYM1-USDT",
                                user%2==1?CoreOrderSide.SELL:CoreOrderSide.BUY,100,1,false,CoreMarginMode.CROSS,
                                CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"risk-"+user))));
            }
            long funds=com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState());
            var firstBatch = batch(line, work(state, line), 16);
            try (var reference = TradingCoreRuntime.fromSnapshot(line, state.snapshot(400))) {
                var expected = reference.apply(firstBatch);
                if (expected.resultCode() == CoreResultCode.MATCHING_PENDING)
                    expected = CoreTestCompletion.completeMatchingSynchronously(reference,
                            reference.matchingSequence(firstBatch.header().commandId()), TIME,
                            firstBatch.header().sourceSequence());
                // Batch execution still has an initial legacy preparation handoff. Its asynchronous
                // risk continuation must finish on the Lane without acquiring ownership a second time.
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!state.runtimeState.tryAcquireOwnerLaneAccess()) {
                    if (System.nanoTime() > deadline) throw new AssertionError("preparation handoff timeout");
                    Thread.onSpinWait();
                }
                var epoch = state.runtimeState.getClass().getDeclaredField("laneHandoffEpoch");
                epoch.setAccessible(true);
                long handoffBefore = epoch.getLong(state.runtimeState);
                var actual = apply(state, firstBatch);
                assertThat(epoch.getLong(state.runtimeState)).isEqualTo(handoffBefore);
                assertThat(actual.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                assertThat(actual.data()).isEqualTo(expected.data());
                assertThat(state.tradingState().businessStateHash())
                        .isEqualTo(reference.tradingState().businessStateHash());
            }
            assertThat(state.tradingState().riskState().scans().get("SYM0-USDT").riskComplete()).isFalse();
            assertThat(state.tradingState().riskState().scans().get("SYM1-USDT").lastScheduledRevision()).isPositive();
            try(var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(500))) {
                for(int i=0;i<30 && work(state,line).riskScanPending();i++) {
                    var next=batch(line,work(state,line),64);
                    applied(state,next);applied(restored,next);
                }
                assertThat(work(state,line).riskScanPending()).isFalse();
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value=ProductLine.class, names={"LINEAR_PERPETUAL","INVERSE_PERPETUAL",
            "LINEAR_DELIVERY","INVERSE_DELIVERY","OPTION"})
    void budgetSpansSymbolsIsBoundedAndRejectsStaleTokensAcrossRecovery(ProductLine line) {
        try (var state = new TradingCoreRuntime(line)) {
            var type = ContractType.valueOf(line.contractTypeCode());
            for (int i=0;i<5;i++) {
                String symbol="SYM"+i+"-USDT";
                applied(state, command(line, CoreMessageType.REGISTER_INSTRUMENT,
                        TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand(symbol,
                                type.ordinal(),"BTC","USDT",type.isInverse()?"BTC":"USDT",1,1,
                                type.isInverse()?1000:1,100_000,50_000,0,0,
                                type.isDelivery()||type.isOption()?2_000_000_000_000L:0,
                                type.isOption()?0:-1,type.isOption()?100:0))));
            }
            for (int i=0;i<5;i++) applied(state, mark(line,"SYM"+i+"-USDT",1));
            for(long user:new long[]{7,8}) applied(state,command(line,CoreMessageType.ADJUST_BALANCE,user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand(type.isInverse()?"BTC":"USDT",1_000_000))));
            for(long user:new long[]{7,8}) applied(state,command(line,CoreMessageType.PLACE_ORDER,user,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100+user,"SYM4-USDT",
                            user==7?CoreOrderSide.SELL:CoreOrderSide.BUY,100,1,false,CoreMarginMode.CROSS,
                            CorePositionSide.NET,CoreOrderType.LIMIT,CoreTimeInForce.GTC,false,"risk-"+user))));
            long funds=com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState());
            var first=work(state,line);
            applied(state, batch(line,first,2));
            assertThat(state.tradingState().riskState().scans().values().stream()
                    .filter(s->s.riskComplete()).count()).isEqualTo(2);
            long hash=state.tradingState().businessStateHash();
            assertThat(apply(state,batch(line,first,2)).commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(state.tradingState().businessStateHash()).isEqualTo(hash);
            applied(state,mark(line,first.riskScanContinuation().symbol(),2));
            var next=work(state,line);
            assertThat(next.riskScanContinuation().symbol()).isNotEqualTo(first.riskScanContinuation().symbol());
            try(var restored=TradingCoreRuntime.fromSnapshot(line,state.snapshot(500))) {
                assertThat(work(restored,line).riskScanContinuation()).isEqualTo(next.riskScanContinuation());
                var finish=batch(line,next,64);
                applied(state,finish);applied(restored,finish);
                assertThat(state.tradingState().riskState().scans().values()).allMatch(s->s.riskComplete());
                assertThat(restored.tradingState().businessStateHash()).isEqualTo(state.tradingState().businessStateHash());
                assertThat(com.surprising.aeron.service.state.RollingFundsStateHash.compute(state.tradingState())).isEqualTo(funds);
            }
        }
    }

    @org.junit.jupiter.api.Test
    void asynchronousRiskIdOverflowRollsBackAllLaneChangesAndFollowingCommandWorks() {
        ProductLine line = ProductLine.LINEAR_PERPETUAL;
        try (var state = new TradingCoreRuntime(line)) {
            applied(state, command(line, CoreMessageType.REGISTER_INSTRUMENT,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("BTC-USDT",
                            ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100_000, 50_000, 0, 0, 0, -1, 0))));
            applied(state, mark(line, "BTC-USDT", 1));
            for (long user : new long[]{7, 8}) {
                applied(state, command(line, CoreMessageType.ADJUST_BALANCE, user,
                        TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 100))));
                applied(state, command(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "BTC-USDT",
                                user == 7 ? CoreOrderSide.SELL : CoreOrderSide.BUY, 100, 1, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "overflow-" + user))));
            }
            applied(state, command(line, CoreMessageType.ADJUST_BALANCE, 8,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", -90))));
            // 仅功能测试将编号放到边界；通过随后正常的行情命令提交为恢复基线。
            state.runtimeState.setNextLiquidationId(Long.MAX_VALUE);
            applied(state, command(line, CoreMessageType.APPLY_MARK_PRICE,
                    TradingCommandCodec.encodeApplyMarkPrice(new ApplyMarkPriceCommand("BTC-USDT", 80, 2, TIME))));
            var before = state.tradingState();
            var scan = command(line, CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)));
            assertThat(state.requiresOwnerLaneAccessForPreparation(scan)).isFalse();
            var response = apply(state, scan);
            assertThat(response.commandStatus()).isEqualTo(ResponseStatus.REJECTED);
            assertThat(response.resultCode()).isEqualTo(CoreResultCode.ARITHMETIC_OVERFLOW);
            assertThat(state.tradingState()).isEqualTo(before);
            assertThat(state.tradingState().businessStateHash()).isEqualTo(before.businessStateHash());
            try (var restored = TradingCoreRuntime.fromSnapshot(line, state.snapshot(600))) {
                var normalPrice = mark(line, "BTC-USDT", 3);
                applied(state, normalPrice); applied(restored, normalPrice);
                var normalScan = command(line, CoreMessageType.CONTINUE_RISK_SCAN,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64)));
                applied(state, normalScan); applied(restored, normalScan);
                assertThat(state.tradingState().riskState().liquidations()).isEmpty();
                assertThat(state.tradingState().businessStateHash()).isEqualTo(restored.tradingState().businessStateHash());
                assertThat(state.tradingState().users()).isEqualTo(before.users());
            }
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void liquidationBatchResumesAcrossAccountsAndSnapshotWithinCancellationBudget(boolean asynchronous) {
        ProductLine line = ProductLine.LINEAR_PERPETUAL;
        try (var state = new TradingCoreRuntime(line)) {
            applied(state, command(line, CoreMessageType.REGISTER_INSTRUMENT,
                    TradingCommandCodec.encodeRegisterInstrument(new RegisterInstrumentCommand("BTC-USDT",
                            ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT", 1, 1, 1,
                            100_000, 50_000, 0, 0, 0, -1, 0))));
            applied(state, mark(line, "BTC-USDT", 1));
            for (long user : new long[]{1, 2, 3}) applied(state, command(line, CoreMessageType.ADJUST_BALANCE, user,
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", user == 2 ? 10000 : 110))));
            applied(state, command(line, CoreMessageType.PLACE_ORDER, 2,
                    TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100, "BTC-USDT",
                            CoreOrderSide.SELL, 100, 20, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                            CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "maker"))));
            for (long user : new long[]{1, 3}) {
                applied(state, command(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(100 + user, "BTC-USDT",
                                CoreOrderSide.BUY, 100, 10, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "taker-" + user))));
                for (int n = 0; n < 2; n++) applied(state, command(line, CoreMessageType.PLACE_ORDER, user,
                        TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(200 + user * 10 + n, "BTC-USDT",
                                CoreOrderSide.SELL, 110, 1, true, CoreMarginMode.CROSS, CorePositionSide.NET,
                                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "cancel-" + user + "-" + n))));
            }
            applied(state, command(line, CoreMessageType.APPLY_MARK_PRICE, TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand("BTC-USDT", 1, 2, TIME))));
            while (work(state, line).riskScanPending()) applied(state, command(line, CoreMessageType.CONTINUE_RISK_SCAN,
                    TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(64))));
            assertThat(work(state, line).actions()).hasSize(2);
            long funds = liquidationFunds(state);
            var firstWork = ExecuteLiquidationBatchCommand.fromWork(work(state, line), 0, 0);
            var first = command(line, CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                    TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                            firstWork.actions(), 1, 0, null, 0)));
            var firstResult = CoreLiquidationBatchResultCodec.decode(executeLiquidation(state, first, asynchronous).data());
            assertThat(firstResult.processedOrders()).isEqualTo(1);
            assertThat(firstResult.pendingActions()).isEqualTo(2);
            try (var restored = TradingCoreRuntime.fromSnapshot(line, state.snapshot(600))) {
                int pages = 1;
                while (!work(state, line).actions().isEmpty()) {
                    assertThat(++pages).isLessThanOrEqualTo(4);
                    var nextWork = ExecuteLiquidationBatchCommand.fromWork(work(state, line), 0, 0);
                    var next = command(line, CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                            TradingCommandCodec.encodeExecuteLiquidationBatch(new ExecuteLiquidationBatchCommand(
                                    nextWork.actions(), 1, 0, null, 0)));
                    var actual = executeLiquidation(state, next, asynchronous);
                    var recovered = apply(restored, next);
                    assertThat(actual.commandStatus()).isEqualTo(ResponseStatus.APPLIED);
                    assertThat(actual.data()).isEqualTo(recovered.data());
                    assertThat(CoreLiquidationBatchResultCodec.decode(actual.data()).processedOrders()).isEqualTo(1);
                }
                assertThat(pages).isEqualTo(4);
                assertThat(state.tradingState().businessStateHash()).isEqualTo(restored.tradingState().businessStateHash());
                assertThat(liquidationFunds(state)).isEqualTo(funds);
            }
        }
    }

    private static CoreResponse executeLiquidation(TradingCoreRuntime state, CoreMessage command, boolean asynchronous) {
        if (asynchronous) return apply(state, command);
        state.activate();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!state.runtimeState.tryAcquireOwnerLaneAccess()) {
            if (System.nanoTime() > deadline) throw new AssertionError("borrowed ownership timeout");
            Thread.onSpinWait();
        }
        CoreResponse response = state.apply(command);
        if (response.resultCode() == CoreResultCode.MATCHING_PENDING)
            response = CoreTestCompletion.completeMatchingSynchronously(state,
                    state.matchingSequence(command.header().commandId()), command.header().submittedAtEpochMillis(),
                    command.header().sourceSequence());
        try {
            var field = state.runtimeState.getClass().getDeclaredField("ownerLaneAccess");
            field.setAccessible(true);
            assertThat(field.getBoolean(state.runtimeState)).isFalse();
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        return response;
    }

    private static long liquidationFunds(TradingCoreRuntime state) {
        var core = state.tradingState();
        long total = 0;
        for (var user : core.users().values()) {
            var balance = user.balances().get("USDT");
            total = Math.addExact(total, Math.addExact(balance.availableUnits(), balance.lockedUnits()));

        }
        total = Math.addExact(total, core.treasuryState().insuranceBalances().getOrDefault("USDT", 0L));
        total = Math.addExact(total, core.treasuryState().clearingPnlBalances().getOrDefault("USDT", 0L));
        return Math.subtractExact(total, core.treasuryState().insuranceDeficits().getOrDefault("USDT", 0L));
    }

    private CoreLiquidationWorkView work(TradingCoreRuntime state,ProductLine line) {
        var response=apply(state,command(line,CoreMessageType.LIQUIDATION_WORK_QUERY,
                CoreLiquidationWorkCodec.encodeQuery(line,CoreLiquidationWorkView.Purpose.EXECUTION,0,1000,1_048_576)));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }
    private CoreMessage batch(ProductLine line,CoreLiquidationWorkView work,int budget) {
        return command(line,CoreMessageType.EXECUTE_LIQUIDATION_BATCH,
                TradingCommandCodec.encodeExecuteLiquidationBatch(ExecuteLiquidationBatchCommand.fromWork(work,0,budget)));
    }
    private CoreMessage mark(ProductLine line,String symbol,long priceSequence) {
        return command(line,CoreMessageType.APPLY_MARK_PRICE,TradingCommandCodec.encodeApplyMarkPrice(
                line==ProductLine.OPTION ? new ApplyMarkPriceCommand(symbol,100,100,100,priceSequence,TIME)
                        :new ApplyMarkPriceCommand(symbol,100,priceSequence,TIME)));
    }
    private CoreMessage command(ProductLine line,CoreMessageType type,byte[] payload) {
        return command(line,type,0,payload);
    }
    private CoreMessage command(ProductLine line,CoreMessageType type,long user,byte[] payload) {
        long seq=++sequence;
        var id=UUID.randomUUID();
        return new CoreMessage(type==CoreMessageType.LIQUIDATION_WORK_QUERY
                ? CoreMessageHeader.query(type,id,line,CommandSource.OPERATIONS,982,seq,user,TIME+seq,seq)
                : CoreMessageHeader.command(type,id,line,CommandSource.OPERATIONS,982,seq,user,TIME+seq,seq),payload);
    }
    private static CoreResponse apply(TradingCoreRuntime state,CoreMessage message) {
        return CoreTestCompletion.applyAsynchronously(state, message);
    }
    private static void applied(TradingCoreRuntime state,CoreMessage message) {
        var response=apply(state,message);
        assertThat(response.commandStatus()).as("%s %s",message.header().messageType(),response.resultCode())
                .isEqualTo(ResponseStatus.APPLIED);
    }
}
