package com.surprising.marketmaker.provider.client;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.client.CoreCommandOutcome;
import com.surprising.aeron.client.SurprisingAeronClient;
import com.surprising.aeron.protocol.AmendOrderBatchCommand;
import com.surprising.aeron.protocol.AmendOrderCommand;
import com.surprising.aeron.protocol.CancelOrderBatchCommand;
import com.surprising.aeron.protocol.CancelOrderCommand;
import com.surprising.aeron.protocol.CoreBookLevelView;
import com.surprising.aeron.protocol.CoreCommandResultCodec;
import com.surprising.aeron.protocol.CoreCommandResultView;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOpenOrdersQuery;
import com.surprising.aeron.protocol.CoreOpenOrdersView;
import com.surprising.aeron.protocol.CoreOrderBatchResult;
import com.surprising.aeron.protocol.CoreOrderBookQuery;
import com.surprising.aeron.protocol.CoreOrderBookView;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderStateView;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.PlaceOrderBatchCommand;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AmendOrderBatchItemResponse;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.AmendOrderResponse;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.OrderBatchItemResponse;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import io.aeron.CommonContext;
import io.aeron.driver.MediaDriver;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 做市商直连 Aeron Cluster 网关。
 */
@Component
@Slf4j
public class MakerAeronTradingGateway implements AutoCloseable {

    private final boolean enabled;
    private final List<String> hostnames;
    private final String egressHostname;
    private final Duration responseTimeout;
    private final int clientConnections;
    private final MediaDriver mediaDriver;
    private final Map<ProductLine, AeronClientPool> pools = new ConcurrentHashMap<>();
    private final Map<ProductLine, Boolean> unavailable = new ConcurrentHashMap<>();

    @Autowired
    public MakerAeronTradingGateway(
            Environment env,
            @Autowired(required = false) @Qualifier("makerMediaDriver") MediaDriver mediaDriver) {
        this.enabled = env.getProperty("surprising.market-maker.aeron.enabled", Boolean.class, true);
        String rawHosts = env.getProperty("AERON_CLUSTER_HOSTNAMES",
                env.getProperty("AERON_HOSTNAMES",
                        env.getProperty("surprising.aeron.hostnames", "127.0.0.1")));
        this.hostnames = parseHostnames(rawHosts);
        this.egressHostname = env.getProperty("AERON_EGRESS_HOSTNAME",
                env.getProperty("surprising.aeron.egress-hostname", "127.0.0.1"));
        this.responseTimeout = Duration.ofMillis(
                env.getProperty("surprising.market-maker.aeron.response-timeout-ms", Long.class, 3000L));
        this.clientConnections = Math.max(1,
                env.getProperty("surprising.market-maker.aeron.client-connections", Integer.class, 1));
        this.mediaDriver = this.enabled && !this.hostnames.isEmpty() ? mediaDriver : null;
        if (this.enabled && this.mediaDriver == null) {
            log.warn("做市商直连 Aeron 网关未就绪: hosts={} mediaDriver=null", this.hostnames);
        }
    }

    public boolean isReady(ProductLine productLine) {
        if (!enabled || mediaDriver == null || productLine == null || Boolean.TRUE.equals(unavailable.get(productLine))) {
            return false;
        }
        return poolFor(productLine) != null;
    }

    public OrderBookSnapshotResponse orderBook(ProductLine productLine, String instrumentId, int depth) {
        AeronClientPool pool = requirePool(productLine);
        byte[] payload = CoreStateQueryCodec.encodeOrderBookQuery(new CoreOrderBookQuery(instrumentId, depth));
        CoreResponse response = pool.query(CoreMessageType.BOOK_STATE_QUERY, UUID.randomUUID(), 0L, payload);
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException("Aeron orderbook query failed: " + response.resultCode());
        }
        CoreOrderBookView view = CoreStateQueryCodec.decodeOrderBookView(response.data());
        List<OrderBookLevel> bids = new ArrayList<>();
        List<OrderBookLevel> asks = new ArrayList<>();
        for (CoreBookLevelView level : view.levels()) {
            OrderBookLevel mapped = new OrderBookLevel(level.priceTicks(), level.quantitySteps(), level.orderCount());
            if (level.side() == CoreOrderSide.BUY) {
                bids.add(mapped);
            } else {
                asks.add(mapped);
            }
        }
        bids.sort(Comparator.comparingLong(OrderBookLevel::priceTicks).reversed());
        asks.sort(Comparator.comparingLong(OrderBookLevel::priceTicks));
        return new OrderBookSnapshotResponse(instrumentId, view.exportSequence(), depth, bids, asks, Instant.now());
    }

    public List<OrderResponse> openOrders(ProductLine productLine, long userId, String instrumentId, int limit) {
        AeronClientPool pool = requirePool(productLine);
        byte[] payload = CoreStateQueryCodec.encodeOpenOrdersQuery(
                new CoreOpenOrdersQuery(instrumentId, 0L, limit));
        CoreResponse response = pool.query(CoreMessageType.USER_OPEN_ORDERS_QUERY, UUID.randomUUID(), userId, payload);
        if (response.status() == ResponseStatus.REJECTED && response.resultCode() == CoreResultCode.ENTITY_NOT_FOUND) {
            return List.of();
        }
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException("Aeron open orders query failed: " + response.resultCode());
        }
        CoreOpenOrdersView view = CoreStateQueryCodec.decodeOpenOrders(response.data());
        return view.orders().stream().map(MakerAeronTradingGateway::toOrderResponse).toList();
    }

    public CoreUserStateView userState(ProductLine productLine, long userId) {
        AeronClientPool pool = requirePool(productLine);
        CoreResponse response = pool.query(CoreMessageType.USER_STATE_QUERY, UUID.randomUUID(), userId, new byte[0]);
        if (response.status() == ResponseStatus.REJECTED && response.resultCode() == CoreResultCode.ENTITY_NOT_FOUND) {
            return null;
        }
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException("Aeron user state query failed: " + response.resultCode());
        }
        return CoreStateQueryCodec.decodeUserState(response.data());
    }

    public OrderCommandReceipt place(ProductLine productLine, PlaceOrderRequest request) {
        AeronClientPool pool = requirePool(productLine);
        long orderId = stableOrderId(productLine, request.userId(), request.clientOrderId());
        UUID commandId = stableCommandId(productLine, request.userId(), "place", request.clientOrderId());
        PlaceOrderCommand command = toPlaceCommand(orderId, request);
        byte[] payload = TradingCommandCodec.encodePlaceOrder(command);
        CoreCommandOutcome outcome = pool.commandOutcome(CoreMessageType.PLACE_ORDER, commandId, request.userId(), payload);
        CoreResponse response = terminalAppliedResponse(outcome);
        if (response == null) {
            return failedReceipt(commandId, List.of(orderId), outcome);
        }
        CoreCommandResultView decoded = CoreCommandResultCodec.decode(response.data());
        OrderResponse order = decoded.orders().stream()
                .filter(item -> item.orderId() == orderId)
                .findFirst()
                .or(() -> decoded.orders().stream().findFirst())
                .map(MakerAeronTradingGateway::toOrderResponse)
                .orElse(null);
        return new OrderCommandReceipt(commandId, "APPLIED", "APPLIED", "APPLIED",
                null, List.of(orderId), order, null);
    }

    public OrderCommandReceipt placeBatch(ProductLine productLine, BatchPlaceOrderRequest request) {
        AeronClientPool pool = requirePool(productLine);
        long userId = request.orders().getFirst().userId();
        List<PlaceOrderCommand> commands = new ArrayList<>(request.orders().size());
        List<Long> prospectiveIds = new ArrayList<>(request.orders().size());
        for (PlaceOrderRequest item : request.orders()) {
            long orderId = stableOrderId(productLine, item.userId(), item.clientOrderId());
            prospectiveIds.add(orderId);
            commands.add(toPlaceCommand(orderId, item));
        }
        UUID commandId = stableCommandId(productLine, userId, "place-batch", request.batchKey());
        byte[] payload = TradingOrderBatchCodec.encodePlaceOrderBatch(new PlaceOrderBatchCommand(commands));
        CoreCommandOutcome outcome = pool.commandOutcome(CoreMessageType.PLACE_ORDER_BATCH, commandId, userId, payload);
        CoreResponse response = terminalAppliedResponse(outcome);
        if (response == null) {
            return failedReceipt(commandId, prospectiveIds, outcome);
        }
        CoreOrderBatchResult batch = TradingOrderBatchCodec.decodeResult(response.data());
        List<OrderBatchItemResponse> items = batch.items().stream()
                .map(item -> new OrderBatchItemResponse(item.index(), item.status() == ResponseStatus.APPLIED,
                        item.resultCode().name(), item.order() == null ? null : toOrderResponse(item.order())))
                .toList();
        int completed = (int) items.stream().filter(OrderBatchItemResponse::success).count();
        OrderBatchResponse result = new OrderBatchResponse(items.size(), completed, items.size() - completed, items);
        return new OrderCommandReceipt(commandId, "APPLIED", "APPLIED", "APPLIED",
                null, prospectiveIds, result, null);
    }

    public OrderCommandReceipt amendBatch(ProductLine productLine, BatchAmendOrdersRequest request) {
        AeronClientPool pool = requirePool(productLine);
        long userId = request.orders().getFirst().userId();
        List<AmendOrderCommand> commands = new ArrayList<>(request.orders().size());
        List<Long> prospectiveIds = new ArrayList<>(request.orders().size());
        for (AmendOrderRequest item : request.orders()) {
            long replacementOrderId = stableOrderId(productLine, item.userId(), "replace:" + item.newClientOrderId());
            prospectiveIds.add(replacementOrderId);
            commands.add(new AmendOrderCommand(
                    item.orderId(),
                    replacementOrderId,
                    item.newClientOrderId(),
                    item.priceTicks(),
                    item.quantitySteps(),
                    item.timeInForce() == null ? null : toCoreTimeInForce(item.timeInForce()),
                    item.postOnly()));
        }
        UUID commandId = stableCommandId(productLine, userId, "amend-batch", request.batchKey());
        byte[] payload = TradingOrderBatchCodec.encodeAmendOrderBatch(new AmendOrderBatchCommand(commands));
        CoreCommandOutcome outcome = pool.commandOutcome(CoreMessageType.AMEND_ORDER_BATCH, commandId, userId, payload);
        CoreResponse response = terminalAppliedResponse(outcome);
        if (response == null) {
            return failedReceipt(commandId, prospectiveIds, outcome);
        }
        CoreOrderBatchResult batch = TradingOrderBatchCodec.decodeResult(response.data());
        List<AmendOrderBatchItemResponse> items = batch.items().stream()
                .map(item -> new AmendOrderBatchItemResponse(item.index(), item.status() == ResponseStatus.APPLIED,
                        item.resultCode().name(),
                        item.order() == null ? null : new AmendOrderResponse(
                                null, toOrderResponse(item.order()), true, item.resultCode().name())))
                .toList();
        int completed = (int) items.stream().filter(AmendOrderBatchItemResponse::success).count();
        AmendOrderBatchResponse result = new AmendOrderBatchResponse(items.size(), completed, items.size() - completed, items);
        return new OrderCommandReceipt(commandId, "APPLIED", "APPLIED", "APPLIED",
                null, prospectiveIds, result, null);
    }

    public OrderCommandReceipt cancelBatch(ProductLine productLine, BatchCancelOrdersRequest request) {
        AeronClientPool pool = requirePool(productLine);
        long userId = request.orders().getFirst().userId();
        List<Long> prospectiveIds = request.orders().stream().map(CancelOrderRequest::orderId).toList();
        List<CancelOrderCommand> commands = prospectiveIds.stream().map(CancelOrderCommand::new).toList();
        UUID commandId = stableCommandId(productLine, userId, "cancel-batch", request.batchKey());
        byte[] payload = TradingOrderBatchCodec.encodeCancelOrderBatch(new CancelOrderBatchCommand(commands));
        CoreCommandOutcome outcome = pool.commandOutcome(CoreMessageType.CANCEL_ORDER_BATCH, commandId, userId, payload);
        CoreResponse response = terminalAppliedResponse(outcome);
        if (response == null) {
            return failedReceipt(commandId, prospectiveIds, outcome);
        }
        CoreOrderBatchResult batch = TradingOrderBatchCodec.decodeResult(response.data());
        List<OrderBatchItemResponse> items = batch.items().stream()
                .map(item -> new OrderBatchItemResponse(item.index(), item.status() == ResponseStatus.APPLIED,
                        item.resultCode().name(), item.order() == null ? null : toOrderResponse(item.order())))
                .toList();
        int completed = (int) items.stream().filter(OrderBatchItemResponse::success).count();
        OrderBatchResponse result = new OrderBatchResponse(items.size(), completed, items.size() - completed, items);
        return new OrderCommandReceipt(commandId, "APPLIED", "APPLIED", "APPLIED",
                null, prospectiveIds, result, null);
    }

    private AeronClientPool requirePool(ProductLine productLine) {
        AeronClientPool pool = poolFor(productLine);
        if (pool == null) {
            throw new IllegalStateException("Aeron direct pool unavailable for " + productLine);
        }
        return pool;
    }

    private AeronClientPool poolFor(ProductLine productLine) {
        if (!enabled || mediaDriver == null || productLine == null || Boolean.TRUE.equals(unavailable.get(productLine))) {
            return null;
        }
        return pools.computeIfAbsent(productLine, line -> {
            try {
                return new AeronClientPool("maker", line, hostnames, egressHostname,
                        responseTimeout, clientConnections, "maker-" + line.name(), mediaDriver);
            } catch (Exception ex) {
                unavailable.put(line, Boolean.TRUE);
                log.warn("做市商直连 Aeron 池创建失败 productLine={}, 回退 Gateway RPC: {}", line, ex.getMessage());
                return null;
            }
        });
    }

    private static CoreResponse terminalAppliedResponse(CoreCommandOutcome outcome) {
        if (outcome instanceof CoreCommandOutcome.Terminal terminal
                && terminal.response().commandStatus() == ResponseStatus.APPLIED) {
            return terminal.response();
        }
        return null;
    }

    private static OrderCommandReceipt failedReceipt(UUID commandId, List<Long> prospectiveIds, CoreCommandOutcome outcome) {
        if (outcome instanceof CoreCommandOutcome.Terminal terminal) {
            CoreResponse response = terminal.response();
            String code = response.resultCode() == null ? "REJECTED" : response.resultCode().name();
            return new OrderCommandReceipt(commandId, "REJECTED", code, code, null, prospectiveIds, null, null);
        }
        if (outcome instanceof CoreCommandOutcome.NotAccepted notAccepted) {
            String code = notAccepted.reason().name();
            return new OrderCommandReceipt(commandId, "NOT_ACCEPTED", code, code, null, prospectiveIds, null, notAccepted.rawOfferResult());
        }
        return new OrderCommandReceipt(commandId, "RESULT_UNKNOWN", "RESULT_UNKNOWN", "RESULT_UNKNOWN", null, prospectiveIds, null, null);
    }

    private static PlaceOrderCommand toPlaceCommand(long orderId, PlaceOrderRequest request) {
        return new PlaceOrderCommand(
                orderId,
                request.instrumentId(),
                request.side() == OrderSide.BUY ? CoreOrderSide.BUY : CoreOrderSide.SELL,
                request.priceTicks(),
                request.quantitySteps(),
                Boolean.TRUE.equals(request.reduceOnly()),
                request.marginMode() == MarginMode.ISOLATED ? CoreMarginMode.ISOLATED : CoreMarginMode.CROSS,
                toCorePositionSide(request.positionSide()),
                request.orderType() == OrderType.MARKET ? CoreOrderType.MARKET : CoreOrderType.LIMIT,
                toCoreTimeInForce(request.timeInForce()),
                Boolean.TRUE.equals(request.postOnly()),
                request.clientOrderId());
    }

    private static CorePositionSide toCorePositionSide(PositionSide side) {
        if (side == null) return CorePositionSide.NET;
        return switch (side) {
            case LONG -> CorePositionSide.LONG;
            case SHORT -> CorePositionSide.SHORT;
            case NET -> CorePositionSide.NET;
        };
    }

    private static CoreTimeInForce toCoreTimeInForce(TimeInForce tif) {
        return tif == null ? CoreTimeInForce.GTC : CoreTimeInForce.valueOf(tif.name());
    }

    private static OrderResponse toOrderResponse(CoreOrderStateView view) {
        return new OrderResponse(
                view.orderId(),
                view.userId(),
                view.clientOrderId(),
                view.instrumentId(),
                view.side() == CoreOrderSide.BUY ? OrderSide.BUY : OrderSide.SELL,
                view.orderType() == CoreOrderType.MARKET ? OrderType.MARKET : OrderType.LIMIT,
                toApiTimeInForce(view.timeInForce()),
                view.priceTicks(),
                view.quantitySteps(),
                view.executedQuantitySteps(),
                view.remainingQuantitySteps(),
                view.marginMode() == CoreMarginMode.ISOLATED ? MarginMode.ISOLATED : MarginMode.CROSS,
                toApiPositionSide(view.positionSide()),
                view.makerFeeRatePpm(),
                view.takerFeeRatePpm(),
                view.reduceOnly(),
                view.postOnly(),
                toApiOrderStatus(view),
                null,
                Instant.ofEpochMilli(view.createdAtEpochMillis()),
                Instant.ofEpochMilli(view.updatedAtEpochMillis()),
                view.cumulativeFeeUnits(),
                "0",
                "0");
    }

    private static OrderStatus toApiOrderStatus(CoreOrderStateView view) {
        if ("OPEN".equals(view.status())) {
            return view.executedQuantitySteps() == 0 ? OrderStatus.ACCEPTED : OrderStatus.PARTIALLY_FILLED;
        }
        return OrderStatus.valueOf(view.status());
    }

    private static TimeInForce toApiTimeInForce(CoreTimeInForce tif) {
        return tif == null ? TimeInForce.GTC : TimeInForce.valueOf(tif.name());
    }

    private static PositionSide toApiPositionSide(CorePositionSide side) {
        if (side == null) return PositionSide.NET;
        return switch (side) {
            case LONG -> PositionSide.LONG;
            case SHORT -> PositionSide.SHORT;
            case NET -> PositionSide.NET;
        };
    }

    private static long stableOrderId(ProductLine productLine, long userId, String clientKey) {
        byte[] digest = sha256("order:" + productLine.name() + ":" + userId + ":" + clientKey);
        long value = ByteBuffer.wrap(digest).getLong() & Long.MAX_VALUE;
        return value == 0L ? 1L : value;
    }

    private static UUID stableCommandId(ProductLine productLine, long userId, String kind, String key) {
        return UUID.nameUUIDFromBytes(("maker-cmd:" + productLine.name() + ":" + userId + ":" + kind + ":" + key)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> parseHostnames(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @PreDestroy
    @Override
    public void close() {
        for (AeronClientPool pool : pools.values()) {
            try {
                if (pool != null) pool.close();
            } catch (Exception ex) {
                log.debug("Closing maker AeronClientPool: {}", ex.getMessage());
            }
        }
        pools.clear();
    }
}
