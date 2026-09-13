package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 独立 benchmarks 模块中的真实单成员少量命令验证入口；与 JMH 共用场景，不进入交易服务包。 */
public final class SmallControlReproMain {
    private static final int TRIGGER_SYMBOLS = 128;
    private static final ProductLine PRODUCT = ProductLine.LINEAR_PERPETUAL;
    private static final List<String> HOSTS = List.of(System.getProperty("surprising.aeron.hostnames",
            "127.0.0.1").split(","));
    private static final String EGRESS_HOST = System.getProperty("surprising.aeron.egress-hostname", "127.0.0.1");
    private long sequence;
    private final SurprisingAeronClient client;

    private boolean rejectTrigger;

    private SmallControlReproMain(SurprisingAeronClient client) { this.client = client; }

    public static void main(String[] args) {
        if (args.length != 1 || !List.of("pool", "ready", "orders", "verify-orders", "trigger", "trigger-scan", "verify-trigger", "trigger-reject", "verify-trigger-reject", "amend-reject", "verify-amend-reject", "amend-cycle", "verify-amend-cycle").contains(args[0]))
            throw new IllegalArgumentException("expected pool|ready|orders|verify-orders|trigger|trigger-scan|verify-trigger");
        if (args[0].equals("pool")) {
            try (var pool = new AeronClientPool("small-control-repro", PRODUCT, HOSTS,
                    EGRESS_HOST, Duration.ofSeconds(10), 2)) {
                var response = pool.command(CoreMessageType.UPSERT_INSTRUMENT, UUID.randomUUID(), 1,
                        TradingCommandCodec.encodeUpsertInstrument(instrument("REPRO-POOL")));
                requireApplied(response);
                System.out.println("poolCommand=PASS");
                var query = pool.query(CoreMessageType.TREASURY_STATE_QUERY, UUID.randomUUID(), 0, new byte[0]);
                if (query.status() != ResponseStatus.OK) throw new IllegalStateException("pool query failed");
                System.out.println("poolQuery=PASS");
            }
            return;
        }
        try (var client = SurprisingAeronClient.connect(PRODUCT, HOSTS, EGRESS_HOST, Duration.ofSeconds(10))) {
            var run = new SmallControlReproMain(client);
            run.rejectTrigger = args[0].contains("reject");
            if (args[0].equals("ready")) {
                run.query(CoreMessageType.TREASURY_STATE_QUERY, 0, new byte[0]);
                System.out.println("ready=PASS");
                return;
            }
            if (args[0].equals("orders")) {
                run.orders();
                return;
            }
            if (args[0].equals("verify-orders")) {
                run.verifyOrders();
                return;
            }
            if (args[0].contains("amend")) {
                for (int i = 0; i < TRIGGER_SYMBOLS; i++) {
                    if (args[0].contains("cycle")) {
                        if (args[0].startsWith("verify")) run.verifyAmendCycle(i);
                        else run.amendCycle(i);
                    } else if (args[0].startsWith("verify")) run.verifyAmendRejection(i);
                    else run.amendRejection(i);
                }
                System.out.println("smallAmend=PASS symbols=" + TRIGGER_SYMBOLS);
                return;
            }
            for (int i = 0; i < TRIGGER_SYMBOLS; i++) {
                if (args[0].startsWith("verify-trigger")) run.verifyTrigger(i);
                else run.trigger(i, args[0].equals("trigger-scan"));
            }
            System.out.println("smallTrigger=PASS symbols=" + TRIGGER_SYMBOLS);
        }
    }

    private static UpsertInstrumentCommand instrument(String symbol) {
        return new UpsertInstrumentCommand(symbol, 1, ContractType.LINEAR_PERPETUAL.ordinal(),
                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0);
    }

    private void orders() {
        String symbol = "REPRO-CATCHUP";
        command(CoreMessageType.UPSERT_INSTRUMENT, 1, TradingCommandCodec.encodeUpsertInstrument(instrument(symbol)));
        command(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                new ApplyMarkPriceCommand(symbol, 1, 100, 1, System.currentTimeMillis())));
        for (long user : new long[]{1001, 1002}) command(CoreMessageType.ADJUST_BALANCE, user,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
        for (int i = 0; i < 8; i++) {
            order(1001, symbol, 3001 + i * 2, CoreOrderSide.SELL, 1);
            order(1002, symbol, 3002 + i * 2, CoreOrderSide.BUY, 1);
        }
        verifyOrders();
        System.out.println("smallOrders=PASS orders=16 fills=8 fundsDiff=0");
    }

    private void verifyOrders() {
        String symbol = "REPRO-CATCHUP";
        long funds = 0;
        for (long user : new long[]{1001, 1002}) {
            var state = CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, user, new byte[0]).data());
            long quantity = state.positions().stream().filter(p -> p.symbol().equals(symbol))
                    .mapToLong(CorePositionView::signedQuantitySteps).sum();
            if (quantity != (user == 1001 ? -8 : 8)) throw new IllegalStateException("catchup position mismatch");
            for (var balance : state.balances()) funds = Math.addExact(funds,
                    Math.addExact(balance.availableUnits(), balance.lockedUnits()));
        }
        if (funds != 20_000) throw new IllegalStateException("catchup funds mismatch: " + funds);
        System.out.println("verifyOrders=PASS positions=-8/+8 fundsDiff=0");
    }

    private void amendCycle(int index) {
        amendRejection(index);
        String symbol = "REPRO-AMEND-" + index;
        long user = 1001 + index * 2L, base = 3001 + index * 10L;
        command(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                base + 4, symbol, 1, CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "cycle-old-" + index)));
        var amended = submitCommand(CoreMessageType.AMEND_ORDER, user, TradingCommandCodec.encodeAmendOrder(
                new AmendOrderCommand(base + 4, base + 5, "cycle-new-" + index, 90L, 1L, CoreTimeInForce.GTC, false)));
        requireApplied(amended);
        var result = CoreCommandResultCodec.decode(amended.data());
        if (result.orders().stream().noneMatch(order -> order.orderId() == base + 4 && order.status().equals("CANCELED"))
                || result.orders().stream().noneMatch(order -> order.orderId() == base + 5 && order.status().equals("OPEN")))
            throw new IllegalStateException("accepted amend lifecycle mismatch");
        var replaced = command(CoreMessageType.REPLACE_ORDER, user, TradingCommandCodec.encodeReplaceOrder(
                new ReplaceOrderCommand(base + 5, new PlaceOrderCommand(base + 8, symbol, 1,
                        CoreOrderSide.BUY, 90, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                        CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "cycle-replace-" + index))));
        var replaceResult = CoreCommandResultCodec.decode(replaced.data());
        if (replaceResult.orders().stream().noneMatch(order -> order.orderId() == base + 5 && order.status().equals("CANCELED"))
                || replaceResult.orders().stream().noneMatch(order -> order.orderId() == base + 8 && order.status().equals("OPEN")))
            throw new IllegalStateException("replacement lifecycle mismatch");
        var placedBatch = command(CoreMessageType.PLACE_ORDER_BATCH, user, TradingOrderBatchCodec.encodePlaceOrderBatch(
                new PlaceOrderBatchCommand(List.of(
                        new PlaceOrderCommand(base + 6, symbol, 1, CoreOrderSide.BUY, 80, 1, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "cycle-batch-a-" + index),
                        new PlaceOrderCommand(base + 7, symbol, 1, CoreOrderSide.BUY, 80, 1, false,
                                CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                                CoreTimeInForce.GTC, false, "cycle-batch-b-" + index)))));
        if (TradingOrderBatchCodec.firstNonAppliedItem(placedBatch, 2) != -1)
            throw new IllegalStateException("batch admission item failed");
        command(CoreMessageType.CANCEL_ORDER, user + 1,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(base + 2)));
        var canceledBatch = command(CoreMessageType.CANCEL_ORDER_BATCH, user, TradingOrderBatchCodec.encodeCancelOrderBatch(
                new CancelOrderBatchCommand(List.of(new CancelOrderCommand(base + 8),
                        new CancelOrderCommand(base + 6), new CancelOrderCommand(base + 7)))));
        if (TradingOrderBatchCodec.firstNonAppliedItem(canceledBatch, 3) != -1)
            throw new IllegalStateException("batch cancellation item failed");
        var missing = submitCommand(CoreMessageType.CANCEL_ORDER, user,
                TradingCommandCodec.encodeCancelOrder(new CancelOrderCommand(base + 9)));
        if (missing.commandStatus() != ResponseStatus.REJECTED || missing.resultCode() != CoreResultCode.ORDER_NOT_FOUND)
            throw new IllegalStateException("missing cancel must reject without modifying funds");
        verifyAmendCycle(index);
    }

    private void verifyAmendCycle(int index) {
        long user = 1001 + index * 2L;
        for (long account = user; account <= user + 1; account++) {
            var state = CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, account, new byte[0]).data());
            if (!state.reservations().isEmpty() || state.positions().stream().anyMatch(p -> p.signedQuantitySteps() != 0)
                    || state.balances().size() != 1 || state.balances().getFirst().lockedUnits() != 0
                    || state.balances().getFirst().availableUnits() != 10_000)
                throw new IllegalStateException("amend/cancel cycle left account state: " + account);
        }
        System.out.println("amendCycle=" + index + " PASS fundsDiff=0 positions=0 reservations=0");
    }

    private void amendRejection(int index) {
        String symbol = "REPRO-AMEND-" + index;
        long user = 1001 + index * 2L, maker = user + 1, base = 3001 + index * 10L;
        command(CoreMessageType.UPSERT_INSTRUMENT, 1, TradingCommandCodec.encodeUpsertInstrument(instrument(symbol)));
        command(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                new ApplyMarkPriceCommand(symbol, 1, 100, 1, System.currentTimeMillis())));
        for (long account : new long[]{user, maker}) command(CoreMessageType.ADJUST_BALANCE, account,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
        order(user, symbol, base, CoreOrderSide.SELL, 1);
        command(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                base + 1, symbol, 1, CoreOrderSide.BUY, 80, 1, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "amend-old-" + index)));
        order(maker, symbol, base + 2, CoreOrderSide.SELL, 1);
        var response = submitCommand(CoreMessageType.AMEND_ORDER, user, TradingCommandCodec.encodeAmendOrder(
                new AmendOrderCommand(base + 1, base + 3, "amend-new-" + index, 100L, 1L, CoreTimeInForce.GTX, true)));
        if (response.commandStatus() != ResponseStatus.REJECTED || response.resultCode() != CoreResultCode.MATCHING_REJECTED)
            throw new IllegalStateException("amend must reject the replacement");
        var result = CoreCommandResultCodec.decode(response.data());
        if (result.orders().stream().noneMatch(order -> order.orderId() == base + 1 && order.status().equals("CANCELED")))
            throw new IllegalStateException("missing canceled original order result");
        verifyAmendRejection(index);
    }

    private void verifyAmendRejection(int index) {
        long user = 1001 + index * 2L, maker = user + 1;
        long funds = 0;
        for (long account : new long[]{user, maker}) {
            var state = CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, account, new byte[0]).data());
            if (state.positions().stream().anyMatch(position -> position.signedQuantitySteps() != 0))
                throw new IllegalStateException("rejected amend traded");
            if (state.reservations().size() != (account == user ? 0 : 1))
                throw new IllegalStateException("rejected amend reservation mismatch");
            if (state.balances().size() != 1) throw new IllegalStateException("unexpected balance assets");
            var balance = state.balances().getFirst();
            // Linear sell reservation uses mark + 1%: ceil(101 * 10%) = 11.
            long locked = account == user ? 0 : 11;
            if (balance.lockedUnits() != locked || balance.availableUnits() != 10_000 - locked)
                throw new IllegalStateException("rejected amend funds not released: account=" + account + " balance=" + balance + " reservations=" + state.reservations());
            funds += balance.availableUnits() + balance.lockedUnits();
        }
        if (funds != 20_000) throw new IllegalStateException("amend funds mismatch");
        System.out.println("amendCase=" + index + " PASS fundsDiff=0 positions=0 reservations=true");
    }

    private void trigger(int index, boolean scan) {
        String symbol = "REPRO-TRIGGER-" + index;
        long maker = 1001 + index * 2L, user = maker + 1, triggerId = 9001 + index;
        command(CoreMessageType.UPSERT_INSTRUMENT, 1, TradingCommandCodec.encodeUpsertInstrument(instrument(symbol)));
        command(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                new ApplyMarkPriceCommand(symbol, 1, 100, 1, System.currentTimeMillis())));
        for (long account : new long[]{maker, user}) command(CoreMessageType.ADJUST_BALANCE, account,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
        order(maker, symbol, 2001 + index * 10, CoreOrderSide.SELL, 10);
        order(user, symbol, 2002 + index * 10, CoreOrderSide.BUY, 10);
        if (rejectTrigger || (index & 1) == 1) order(maker, symbol, 2003 + index * 10, CoreOrderSide.BUY, 1);
        var trigger = new CoreTriggerOrderStateView(triggerId, PRODUCT, user, "repro-trigger-" + triggerId,
                "repro-oco-" + index, symbol, CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                CoreTriggerCondition.GREATER_OR_EQUAL, 100, 0, 0, 0, 0, 0,
                CoreOrderType.LIMIT, rejectTrigger ? CoreTimeInForce.GTX : CoreTimeInForce.IOC, rejectTrigger ? 100 : (index & 1) == 0 ? 110 : 100, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", "repro", 0, 0, 0, 0, 1, 1, 0, 0);
        command(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(trigger));
        var sibling = new CoreTriggerOrderStateView(10001 + index, PRODUCT, user, "repro-sibling-" + index,
                "repro-oco-" + index, symbol, CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                CoreTriggerCondition.GREATER_OR_EQUAL, 200, 0, 0, 0, 0, 0,
                CoreOrderType.LIMIT, CoreTimeInForce.IOC, 200, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", "repro", 0, 0, 0, 0, 1, 1, 0, 0);
        command(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(sibling));
        if (scan) {
            command(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                    new ApplyMarkPriceCommand(symbol, 1, 100, 2, System.currentTimeMillis())));
            // Risk and trigger pages share the real continuation budget. Query terminal state only at this boundary.
            boolean complete = false;
            for (int page = 0; page < 64; page++) {
                command(CoreMessageType.CONTINUE_RISK_SCAN, 0,
                        TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(1)));
                var state = CoreTriggerOrderCodec.decodeList(query(CoreMessageType.TRIGGER_ORDER_QUERY, user,
                        CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(triggerId, symbol, 0, 1))).data());
                if (!state.isEmpty() && state.getFirst().status() == CoreTriggerOrderStatus.TRIGGERED) {
                    complete = true;
                    break;
                }
            }
            if (!complete) throw new IllegalStateException("internal trigger scan did not complete");
        } else command(CoreMessageType.EXECUTE_TRIGGER_ORDER, 0,
                CoreTriggerOrderCodec.encodeExecute(triggerId, 1, 100, System.currentTimeMillis()));
        verifyTrigger(index);
    }

    private void verifyTrigger(int index) {
        String symbol = "REPRO-TRIGGER-" + index;
        long maker = 1001 + index * 2L, user = maker + 1, triggerId = 9001 + index;
        var state = CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, user, new byte[0]).data());
        long quantity = state.positions().stream().filter(p -> p.symbol().equals(symbol))
                .mapToLong(CorePositionView::signedQuantitySteps).sum();
        if (quantity != (rejectTrigger || (index & 1) == 0 ? 10 : 9)) throw new IllegalStateException("trigger position mismatch: " + quantity);
        var terminals = CoreTriggerOrderCodec.decodeList(query(CoreMessageType.TRIGGER_ORDER_QUERY, user,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(triggerId, symbol, 0, 1))).data());
        if (terminals.size() != 1 || terminals.getFirst().status() != (rejectTrigger ? CoreTriggerOrderStatus.TRIGGER_FAILED : CoreTriggerOrderStatus.TRIGGERED))
            throw new IllegalStateException("trigger terminal mismatch: " + terminals);
        if (terminals.getFirst().placedOrderId() != (rejectTrigger ? 0 : triggerId * 2 + 1))
            throw new IllegalStateException("trigger child identity mismatch");
        var siblings = CoreTriggerOrderCodec.decodeList(query(CoreMessageType.TRIGGER_ORDER_QUERY, user,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(10001 + index, symbol, 0, 1))).data());
        if (siblings.size() != 1 || siblings.getFirst().status() != CoreTriggerOrderStatus.CANCELED)
            throw new IllegalStateException("OCO sibling was not canceled on trigger execution");
        long funds = 0;
        for (long account : new long[]{maker, user}) {
            var accountState = CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, account, new byte[0]).data());
            long signed = accountState.positions().stream().mapToLong(CorePositionView::signedQuantitySteps).sum();
            if (signed != (account == user ? quantity : -quantity)) throw new IllegalStateException("counterparty position mismatch");
            for (var balance : accountState.balances()) {
                if (balance.lockedUnits() != quantity * 10 + (rejectTrigger && account == maker ? 10 : 0)
                        || balance.availableUnits() != 10_000 - quantity * 10 - (rejectTrigger && account == maker ? 10 : 0))
                    throw new IllegalStateException("trigger margin/reservation mismatch");
                funds = Math.addExact(funds, Math.addExact(balance.availableUnits(), balance.lockedUnits()));
            }
        }
        if (funds != 20_000) throw new IllegalStateException("trigger funds mismatch");
        System.out.println("triggerCase=" + index + " PASS quantity=" + quantity + " fundsDiff=0 margin=true ocoCanceled=true");
    }

    private void order(long user, String symbol, long id, CoreOrderSide side, long quantity) {
        command(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                id, symbol, 1, side, 100, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "repro-order-" + id)));
    }

    private CoreResponse command(CoreMessageType type, long user, byte[] payload) {
        var response = submitCommand(type, user, payload);
        requireApplied(response);
        return response;
    }

    private CoreResponse submitCommand(CoreMessageType type, long user, byte[] payload) {
        long id = ++sequence;
        System.out.println("SEND sequence=" + id + " type=" + type);
        var response = client.submit(new CoreMessage(CoreMessageHeader.command(type, new UUID(990099, id), PRODUCT,
                CommandSource.OPERATIONS, 990099, id, user, System.currentTimeMillis(), id), payload));
        System.out.println("RESULT sequence=" + id + " status=" + response.commandStatus() + " code=" + response.resultCode());
        return response;
    }

    private CoreResponse query(CoreMessageType type, long user, byte[] payload) {
        long id = ++sequence;
        var response = client.submit(new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(), PRODUCT,
                CommandSource.OPERATIONS, 990099, 0, user, System.currentTimeMillis(), id), payload));
        if (response.status() != ResponseStatus.OK) throw new IllegalStateException("query failed " + response.resultCode());
        return response;
    }

    private static void requireApplied(CoreResponse response) {
        if (response.commandStatus() != ResponseStatus.APPLIED)
            throw new IllegalStateException("command failed " + response.resultCode());
    }
}
