package com.surprising.aeron.benchmarks.workload;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.*;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** 独立 benchmarks 模块中的三 JVM 少量命令验证入口；与 JMH 共用场景，不进入交易服务包。 */
public final class SmallControlReproMain {
    private static final ProductLine PRODUCT = ProductLine.LINEAR_PERPETUAL;
    private static final List<String> HOSTS = List.of("127.0.0.1", "127.0.0.1", "127.0.0.1");
    private long sequence;
    private final SurprisingAeronClient client;

    private SmallControlReproMain(SurprisingAeronClient client) { this.client = client; }

    public static void main(String[] args) {
        if (args.length != 1 || !List.of("pool", "ready", "orders", "verify-orders", "trigger", "verify-trigger").contains(args[0]))
            throw new IllegalArgumentException("expected pool|ready|orders|verify-orders|trigger|verify-trigger");
        if (args[0].equals("pool")) {
            try (var pool = new AeronClientPool("small-control-repro", PRODUCT, HOSTS,
                    "127.0.0.1", Duration.ofSeconds(10), 2)) {
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
        try (var client = SurprisingAeronClient.connect(PRODUCT, HOSTS, "127.0.0.1", Duration.ofSeconds(10))) {
            var run = new SmallControlReproMain(client);
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
            for (int i = 0; i < 2; i++) {
                if (args[0].equals("verify-trigger")) run.verifyTrigger(i);
                else run.trigger(i);
            }
            System.out.println("smallTrigger=PASS");
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

    private void trigger(int index) {
        String symbol = "REPRO-TRIGGER-" + index;
        long maker = 1001 + index * 2L, user = maker + 1, triggerId = 9001 + index;
        command(CoreMessageType.UPSERT_INSTRUMENT, 1, TradingCommandCodec.encodeUpsertInstrument(instrument(symbol)));
        command(CoreMessageType.APPLY_MARK_PRICE, 0, TradingCommandCodec.encodeApplyMarkPrice(
                new ApplyMarkPriceCommand(symbol, 1, 100, 1, System.currentTimeMillis())));
        for (long account : new long[]{maker, user}) command(CoreMessageType.ADJUST_BALANCE, account,
                TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 10_000)));
        order(maker, symbol, 2001 + index * 10, CoreOrderSide.SELL, 10);
        order(user, symbol, 2002 + index * 10, CoreOrderSide.BUY, 10);
        if (index == 1) order(maker, symbol, 2003 + index * 10, CoreOrderSide.BUY, 1);
        var trigger = new CoreTriggerOrderStateView(triggerId, PRODUCT, user, "repro-trigger-" + triggerId,
                "", symbol, CoreOrderSide.SELL, CoreTriggerOrderType.TAKE_PROFIT,
                CoreTriggerCondition.GREATER_OR_EQUAL, 100, 0, 0, 0, 0, 0,
                CoreOrderType.LIMIT, CoreTimeInForce.IOC, index == 0 ? 110 : 100, 1,
                CoreMarginMode.CROSS, CorePositionSide.NET, CoreTriggerOrderStatus.PENDING,
                0, 0, 0, "", "repro", 0, 0, 0, 0, 1, 1, 0, 0);
        command(CoreMessageType.PLACE_TRIGGER_ORDER, user, CoreTriggerOrderCodec.encodeState(trigger));
        command(CoreMessageType.EXECUTE_TRIGGER_ORDER, 0,
                CoreTriggerOrderCodec.encodeExecute(triggerId, 1, 100, System.currentTimeMillis()));
        verifyTrigger(index);
    }

    private void verifyTrigger(int index) {
        String symbol = "REPRO-TRIGGER-" + index;
        long maker = 1001 + index * 2L, user = maker + 1, triggerId = 9001 + index;
        var state = CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, user, new byte[0]).data());
        long quantity = state.positions().stream().filter(p -> p.symbol().equals(symbol))
                .mapToLong(CorePositionView::signedQuantitySteps).sum();
        if (quantity != (index == 0 ? 10 : 9)) throw new IllegalStateException("trigger position mismatch: " + quantity);
        var terminals = CoreTriggerOrderCodec.decodeList(query(CoreMessageType.TRIGGER_ORDER_QUERY, user,
                CoreTriggerOrderCodec.encodeQuery(new CoreTriggerOrderQuery(triggerId, symbol, 0, 1))).data());
        if (terminals.size() != 1 || terminals.getFirst().status() != CoreTriggerOrderStatus.TRIGGERED)
            throw new IllegalStateException("trigger terminal mismatch: " + terminals);
        if (terminals.getFirst().placedOrderId() != triggerId * 2 + 1)
            throw new IllegalStateException("trigger child identity mismatch");
        long funds = 0;
        for (long account : new long[]{maker, user}) {
            var accountState = CoreStateQueryCodec.decodeUserState(query(CoreMessageType.USER_STATE_QUERY, account, new byte[0]).data());
            long signed = accountState.positions().stream().mapToLong(CorePositionView::signedQuantitySteps).sum();
            if (signed != (account == user ? quantity : -quantity)) throw new IllegalStateException("counterparty position mismatch");
            for (var balance : accountState.balances()) {
                if (balance.lockedUnits() != quantity * 10 || balance.availableUnits() != 10_000 - quantity * 10)
                    throw new IllegalStateException("trigger margin/reservation mismatch");
                funds = Math.addExact(funds, Math.addExact(balance.availableUnits(), balance.lockedUnits()));
            }
        }
        if (funds != 20_000) throw new IllegalStateException("trigger funds mismatch");
        System.out.println("triggerCase=" + index + " PASS quantity=" + quantity + " fundsDiff=0 margin=true");
    }

    private void order(long user, String symbol, long id, CoreOrderSide side, long quantity) {
        command(CoreMessageType.PLACE_ORDER, user, TradingCommandCodec.encodePlaceOrder(new PlaceOrderCommand(
                id, symbol, 1, side, 100, quantity, false, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreOrderType.LIMIT, CoreTimeInForce.GTC, false, "repro-order-" + id)));
    }

    private void command(CoreMessageType type, long user, byte[] payload) {
        long id = ++sequence;
        System.out.println("SEND sequence=" + id + " type=" + type);
        var response = client.submit(new CoreMessage(CoreMessageHeader.command(type, new UUID(990099, id), PRODUCT,
                CommandSource.OPERATIONS, 990099, id, user, System.currentTimeMillis(), id), payload));
        System.out.println("RESULT sequence=" + id + " status=" + response.commandStatus() + " code=" + response.resultCode());
        requireApplied(response);
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
