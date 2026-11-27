package com.surprising.marketmaker.provider.service;

import com.surprising.marketmaker.provider.client.MakerAeronTradingGateway;
import com.surprising.marketmaker.provider.config.MarketMakerProperties;
import com.surprising.marketmaker.provider.model.DesiredQuote;
import com.surprising.marketmaker.provider.model.QuotePlan;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.AmendOrderBatchResponse;
import com.surprising.trading.api.model.AmendOrderRequest;
import com.surprising.trading.api.model.BatchAmendOrdersRequest;
import com.surprising.trading.api.model.BatchCancelOrdersRequest;
import com.surprising.trading.api.model.BatchPlaceOrderRequest;
import com.surprising.trading.api.model.CancelOrderRequest;
import com.surprising.trading.api.model.OrderBatchResponse;
import com.surprising.trading.api.model.OrderCommandReceipt;
import com.surprising.trading.api.model.OrderResponse;
import com.surprising.trading.api.model.OrderSide;
import com.surprising.trading.api.model.OrderStatus;
import com.surprising.trading.api.model.OrderType;
import com.surprising.trading.api.model.PlaceOrderRequest;
import com.surprising.trading.api.model.PositionSide;
import com.surprising.trading.api.model.TimeInForce;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;

/** 做市报价对账、批量改单、补单与撤单执行协作器。 */
@Slf4j
final class MarketMakerQuoteReconciler {
    private static final int MAX_BATCH_PLACE_ORDERS = 20;
    private static final int MAX_BATCH_CANCEL_ORDERS = 50;

    private final MarketMakerService service;
    private final MarketMakerProperties properties;
    private final MakerAeronTradingGateway aeronGateway;
    private final MarketMakerMarketFeed marketFeed;
    private final String orderNonce;
    private final AtomicLong quoteRequestSequence = new AtomicLong();

    MarketMakerQuoteReconciler(MarketMakerService service,
                               MarketMakerProperties properties,
                               MakerAeronTradingGateway aeronGateway,
                               MarketMakerMarketFeed marketFeed,
                               String orderNonce) {
        this.service = service;
        this.properties = properties;
        this.aeronGateway = aeronGateway;
        this.marketFeed = marketFeed;
        this.orderNonce = orderNonce;
    }

    ReconcileResult reconcile(MarketMakerProperties.Strategy strategy, long accountId, String instrumentId,
                              QuotePlan plan, List<OrderResponse> openOrders, long cycleSequence) {
        String accountPrefix = service.accountPrefix(strategy, instrumentId, accountId);
        var quoting = properties.getQuoting();
        List<OrderResponse> owned = openOrders.stream()
                .filter(order -> service.ownsOrder(accountPrefix, order))
                .filter(MakerQuoteMatchingSupport::isLive)
                .sorted(Comparator.comparing(OrderResponse::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<OrderResponse> kept = new ArrayList<>(owned);
        long highestBid = plan.quotes().stream().filter(q -> q.side() == OrderSide.BUY)
                .mapToLong(DesiredQuote::priceTicks).max().orElse(0L);
        long lowestAsk = plan.quotes().stream().filter(q -> q.side() == OrderSide.SELL)
                .mapToLong(DesiredQuote::priceTicks).min().orElse(Long.MAX_VALUE);
        long previousBid = owned.stream().filter(order -> order.side() == OrderSide.BUY)
                .mapToLong(OrderResponse::priceTicks).max().orElse(0);
        long previousAsk = owned.stream().filter(order -> order.side() == OrderSide.SELL)
                .mapToLong(OrderResponse::priceTicks).min().orElse(Long.MAX_VALUE);
        List<OrderResponse> drifted = owned.stream()
                .filter(order -> !MakerQuoteMatchingSupport.shouldKeep(order, plan.quotes(), accountPrefix, quoting))
                .toList();
        List<OrderResponse> crossing = new ArrayList<>();
        List<OrderResponse> nonCrossing = new ArrayList<>();
        for (OrderResponse order : drifted) {
            boolean wouldCross = order.side() == OrderSide.SELL && order.priceTicks() <= highestBid
                    || order.side() == OrderSide.BUY && order.priceTicks() >= lowestAsk;
            if (wouldCross) crossing.add(order);
            else nonCrossing.add(order);
        }
        int maxUpdates = quoting.getMaxQuoteUpdatesPerCycle();
        if (maxUpdates > 0 && nonCrossing.size() > maxUpdates) {
            Collections.shuffle(nonCrossing, ThreadLocalRandom.current());
            nonCrossing = nonCrossing.subList(0, maxUpdates);
        }
        List<OrderResponse> toUpdate = new ArrayList<>(crossing.size() + nonCrossing.size());
        toUpdate.addAll(crossing);
        toUpdate.addAll(nonCrossing);
        List<CancelOrderRequest> cancelRequests = toUpdate.stream()
                .sorted(Comparator.<OrderResponse>comparingInt(order ->
                        order.side() == OrderSide.SELL && order.priceTicks() <= highestBid
                                || order.side() == OrderSide.BUY && order.priceTicks() >= lowestAsk ? 0 : 1)
                        .thenComparingLong(order -> (order.side() == OrderSide.BUY
                                ? highestBid > previousBid : lowestAsk < previousAsk)
                                ? -order.remainingQuantitySteps() : order.remainingQuantitySteps()))
                .map(order -> new CancelOrderRequest(accountId, order.orderId()))
                .toList();
        int replacementBatchSize = Math.max(1, Math.min(MAX_BATCH_PLACE_ORDERS, owned.size() / 10));
        long canceled = 0L;
        long submitted = 0L;
        long rejected = 0L;
        String rejectionReason = null;
        ReconcileResult refill = placeMissingQuotes(strategy, accountId, instrumentId, plan,
                kept, accountPrefix, cycleSequence);
        submitted += refill.submitted();
        rejected += refill.rejected();
        rejectionReason = refill.rejectionReason();
        if (cancelRequests.isEmpty()) return new ReconcileResult(submitted, canceled, rejected, rejectionReason);
        var keptById = new HashMap<Long, OrderResponse>(kept.size() * 2);
        for (OrderResponse order : kept) {
            keptById.put(order.orderId(), order);
        }
        for (int start = 0; start < Math.max(1, cancelRequests.size()); start += replacementBatchSize) {
            List<CancelOrderRequest> batch = cancelRequests.subList(start,
                    Math.min(start + replacementBatchSize, cancelRequests.size()));
            List<CancelOrderRequest> cancellations = new ArrayList<>();
            Map<Long, DesiredQuote> amendments = new LinkedHashMap<>();
            for (CancelOrderRequest request : batch) {
                OrderResponse old = keptById.get(request.orderId());
                DesiredQuote best = quoteReplacement(strategy, plan, old, accountPrefix, highestBid, lowestAsk, quoting);
                if (best == null) cancellations.add(request);
                else amendments.put(request.orderId(), best);
            }
            CancelResult result = cancelOrders(strategy.getProductLine(), accountId, instrumentId, cancellations);
            canceled += result.completed();
            for (CancelOrderRequest request : cancellations) {
                if (!result.failed().contains(request)) {
                    kept.removeIf(order -> order.orderId() == request.orderId());
                }
            }
            if (!amendments.isEmpty() && !result.failed().isEmpty()) {
                rejected += result.failed().size();
                rejectionReason = "cancellation is uncertain; preserve the deep quotes";
                break;
            }
            List<OrderResponse> updated = amendQuotes(strategy, accountId, instrumentId, amendments, cycleSequence);
            kept.removeIf(order -> amendments.containsKey(order.orderId()));
            updated.stream().filter(MakerQuoteMatchingSupport::isLive).forEach(kept::add);
            canceled += amendments.size();
            submitted += amendments.size();
            ReconcileResult replacement = placeMissingQuotes(strategy, accountId, instrumentId, plan,
                    kept, accountPrefix, cycleSequence);
            submitted += replacement.submitted();
            rejected += replacement.rejected();
            if (replacement.rejectionReason() != null) rejectionReason = replacement.rejectionReason();
            if (replacement.rejected() > 0 || !result.failed().isEmpty()) break;
        }
        return new ReconcileResult(submitted, canceled, rejected, rejectionReason);
    }

    private DesiredQuote quoteReplacement(MarketMakerProperties.Strategy strategy, QuotePlan plan,
            OrderResponse old, String prefix, long bid, long ask, MarketMakerProperties.Quoting quoting) {
        if (old == null) return null;
        int level = MakerQuoteMatchingSupport.quoteLevel(old.clientOrderId(), prefix, old.side());
        if (level < 0) return null;
        boolean linearLiquidity = quoting.getLinearLiquidityTargetNotionalUnits() != 0
                && (strategy.getProductLine() == ProductLine.LINEAR_PERPETUAL
                || strategy.getProductLine() == ProductLine.LINEAR_DELIVERY);
        if (!linearLiquidity && !quoting.isPreferInPlaceAmend()) return null;
        long best = old.side() == OrderSide.BUY ? bid : ask;
        long tolerance = linearLiquidity ? MakerQuoteMatchingSupport.ppmFloor(best, quoting.getLiquiditySlippagePpm()) : 0L;
        for (var quote : plan.quotes()) {
            if (quote.side() != old.side() || quote.level() != level) continue;
            if (!linearLiquidity || Math.abs(quote.priceTicks() - best) <= tolerance) return quote;
        }
        return null;
    }

    private List<OrderResponse> amendQuotes(MarketMakerProperties.Strategy strategy, long accountId,
            String instrumentId, Map<Long, DesiredQuote> amendments, long cycleSequence) {
        if (amendments.isEmpty()) return List.of();
        List<AmendOrderRequest> requests = amendments.entrySet().stream().map(entry -> {
            var quote = quoteRequest(strategy, accountId, instrumentId, entry.getValue(), cycleSequence);
            return new AmendOrderRequest(accountId, entry.getKey(), quote.clientOrderId(),
                    quote.priceTicks(), quote.quantitySteps(), TimeInForce.GTX, true);
        }).toList();
        List<OrderResponse> updated = new ArrayList<>();
        try {
            for (int start = 0; start < requests.size(); start += 20) {
                var batch = requests.subList(start, Math.min(start + 20, requests.size()));
                var receipt = aeronGateway.amendBatch(strategy.getProductLine(), new BatchAmendOrdersRequest(batch));
                var result = MakerQuoteMatchingSupport.receiptResult(receipt, AmendOrderBatchResponse.class);
                if (result == null) {
                    throw new IllegalStateException("quote amendments not confirmed: " + MakerQuoteMatchingSupport.receiptMessage(receipt));
                }
                if (result.failed() != 0 || result.completed() != batch.size()
                        || result.results().size() != batch.size()) {
                    throw new PartialAmendException("quote amendments not confirmed: " + MakerQuoteMatchingSupport.receiptMessage(receipt));
                }
                boolean[] confirmed = new boolean[batch.size()];
                List<OrderResponse> replacements = new ArrayList<>(batch.size());
                for (var item : result.results()) {
                    if (!item.success() || item.index() < 0 || item.index() >= batch.size()
                            || confirmed[item.index()] || item.amend() == null
                            || item.amend().replacementOrder() == null)
                        throw new IllegalStateException("invalid quote amendment result");
                    var replacement = item.amend().replacementOrder();
                    var request = batch.get(item.index());
                    if (replacement.status() == OrderStatus.REJECTED
                            || !request.newClientOrderId().equals(replacement.clientOrderId()))
                        throw new IllegalStateException("quote amendment identity or status mismatch");
                    confirmed[item.index()] = true;
                    replacements.add(replacement);
                }
                marketFeed.updateCachedOrders(strategy.getProductLine(), accountId, instrumentId,
                        batch.stream().map(AmendOrderRequest::orderId).toList(), replacements);
                updated.addAll(replacements);
            }
            return updated;
        } catch (PartialAmendException ex) {
            marketFeed.invalidateOrderSnapshot(strategy.getProductLine(), accountId, instrumentId);
            throw new PartialAmendException("quote amendment outcome requires an actual order query: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            marketFeed.invalidateOrderSnapshot(strategy.getProductLine(), accountId, instrumentId);
            throw new IllegalStateException("quote amendment outcome requires an actual order query: " + ex.getMessage(), ex);
        }
    }

    private ReconcileResult placeMissingQuotes(MarketMakerProperties.Strategy strategy, long accountId,
                                               String instrumentId, QuotePlan plan, List<OrderResponse> kept,
                                               String accountPrefix, long cycleSequence) {
        long submitted = 0L;
        long rejected = 0L;
        String rejectionReason = null;
        int maxOpenOrders = properties.getQuoting().getMaxOpenOrdersPerAccountSymbol();
        List<DesiredQuote> missingQuotes = new ArrayList<>();
        long plannedBids = 0L, plannedAsks = 0L, restingBids = 0L, restingAsks = 0L;
        for (DesiredQuote quote : plan.quotes()) {
            if (quote.side() == OrderSide.BUY) plannedBids = Math.addExact(plannedBids, quote.quantitySteps());
            else if (quote.side() == OrderSide.SELL) plannedAsks = Math.addExact(plannedAsks, quote.quantitySteps());
        }
        for (OrderResponse order : kept) {
            if (order == null) continue;
            if (order.side() == OrderSide.BUY) restingBids = Math.addExact(restingBids, order.remainingQuantitySteps());
            else if (order.side() == OrderSide.SELL) restingAsks = Math.addExact(restingAsks, order.remainingQuantitySteps());
        }
        long bidCapacity = Math.subtractExact(plannedBids, restingBids);
        long askCapacity = Math.subtractExact(plannedAsks, restingAsks);
        for (DesiredQuote quote : plan.quotes()) {
            if (kept.size() >= maxOpenOrders) {
                break;
            }
            if (MakerQuoteMatchingSupport.occupiedOrCrossingQuote(kept, quote, accountPrefix)) {
                continue;
            }
            if (quote.side() == OrderSide.BUY && quote.quantitySteps() > bidCapacity
                    || quote.side() == OrderSide.SELL && quote.quantitySteps() > askCapacity) {
                continue;
            }
            missingQuotes.add(quote);
            kept.add(null);
            if (quote.side() == OrderSide.BUY) bidCapacity -= quote.quantitySteps();
            else askCapacity -= quote.quantitySteps();
        }
        if (!missingQuotes.isEmpty()) {
            List<PlaceOrderRequest> requests = missingQuotes.stream()
                    .map(quote -> quoteRequest(strategy, accountId, instrumentId, quote, cycleSequence))
                    .toList();
            for (int start = 0; start < requests.size(); start += MAX_BATCH_PLACE_ORDERS) {
                List<PlaceOrderRequest> batchRequests = requests.subList(start,
                        Math.min(start + MAX_BATCH_PLACE_ORDERS, requests.size()));
                ReconcileResult batchOutcome = placeQuoteBatch(strategy, accountId, instrumentId, batchRequests, kept);
                submitted += batchOutcome.submitted();
                rejected += batchOutcome.rejected();
                rejectionReason = MakerQuoteMatchingSupport.firstReason(rejectionReason, batchOutcome.rejectionReason());
            }
        }
        kept.removeIf(Objects::isNull);
        return new ReconcileResult(submitted, 0L, rejected, rejectionReason);
    }

    private ReconcileResult placeQuoteBatch(MarketMakerProperties.Strategy strategy,
                                            long accountId,
                                            String instrumentId,
                                            List<PlaceOrderRequest> batchRequests,
                                            List<OrderResponse> kept) {
        long submitted = 0L;
        long rejected = 0L;
        String rejectionReason = null;
        try {
            OrderCommandReceipt receipt = aeronGateway.placeBatch(strategy.getProductLine(), new BatchPlaceOrderRequest(batchRequests));
            OrderBatchResponse batch = MakerQuoteMatchingSupport.receiptResult(receipt, OrderBatchResponse.class);
            if (batch == null) {
                throw new IllegalStateException(MakerQuoteMatchingSupport.receiptMessage(receipt));
            }
            List<OrderResponse> confirmedOrders = new ArrayList<>(batchRequests.size());
            for (int i = 0; i < batchRequests.size(); i++) {
                int resultIndex = i;
                var item = batch.results().stream()
                        .filter(result -> result.index() == resultIndex)
                        .findFirst()
                        .orElse(null);
                OrderResponse response = item == null ? null : item.order();
                if (item == null || item.success() && response == null) {
                    throw new IllegalStateException("quote batch outcome is unknown; reconcile live orders before retry");
                }
                if (!item.success() || response.status() == OrderStatus.REJECTED) {
                    rejected++;
                    rejectionReason = MakerQuoteMatchingSupport.firstReason(rejectionReason,
                            MakerQuoteMatchingSupport.firstReason(item.message(),
                                    response == null ? null : response.rejectReason()));
                    continue;
                }
                confirmedOrders.add(response);
                submitted++;
                int placeholder = kept.indexOf(null);
                if (placeholder >= 0) {
                    kept.set(placeholder, response);
                } else {
                    kept.add(response);
                }
            }
            marketFeed.updateCachedOrders(strategy.getProductLine(), accountId, instrumentId, List.of(), confirmedOrders);
            return new ReconcileResult(submitted, 0L, rejected, rejectionReason);
        } catch (RuntimeException ex) {
            marketFeed.invalidateOrderSnapshot(strategy.getProductLine(), accountId, instrumentId);
            throw new IllegalStateException("quote replenishment outcome is unknown", ex);
        }
    }

    CancelResult cancelOrders(ProductLine productLine, long accountId, String instrumentId,
                              List<CancelOrderRequest> requests) {
        if (requests.isEmpty()) {
            return new CancelResult(0L, List.of());
        }
        long canceled = 0L;
        List<CancelOrderRequest> failed = new ArrayList<>();
        for (int start = 0; start < requests.size(); start += MAX_BATCH_CANCEL_ORDERS) {
            List<CancelOrderRequest> batchRequests = requests.subList(start,
                    Math.min(start + MAX_BATCH_CANCEL_ORDERS, requests.size()));
            try {
                OrderCommandReceipt receipt = aeronGateway.cancelBatch(productLine, new BatchCancelOrdersRequest(batchRequests));
                OrderBatchResponse response = MakerQuoteMatchingSupport.receiptResult(receipt, OrderBatchResponse.class);
                if (response == null) {
                    log.warn("做市批量撤单未返回终态结果 accountId={} instrumentId={} receipt={}", accountId, instrumentId,
                            MakerQuoteMatchingSupport.receiptMessage(receipt));
                    failed.addAll(batchRequests);
                    continue;
                }
                List<Long> confirmedIds = new ArrayList<>(batchRequests.size());
                for (int i = 0; i < batchRequests.size(); i++) {
                    int requestIndex = i;
                    boolean success = response.results().stream()
                            .anyMatch(item -> item != null && item.index() == requestIndex && item.success());
                    CancelOrderRequest request = batchRequests.get(i);
                    if (success) {
                        canceled++;
                        confirmedIds.add(request.orderId());
                    } else {
                        failed.add(request);
                    }
                }
                marketFeed.updateCachedOrders(productLine, accountId, instrumentId, confirmedIds, List.of());
            } catch (RuntimeException ex) {
                log.warn("做市批量撤单状态不确定 accountId={} instrumentId={} error={}", accountId, instrumentId, ex.getMessage());
                failed.addAll(batchRequests);
            }
        }
        return new CancelResult(canceled, List.copyOf(failed));
    }

    private PlaceOrderRequest quoteRequest(MarketMakerProperties.Strategy strategy, long accountId,
                                           String instrumentId, DesiredQuote quote, long cycleSequence) {
        String accountPrefix = service.accountPrefix(strategy, instrumentId, accountId);
        String clientOrderId = service.quotePrefix(accountPrefix, quote.side(), quote.level())
                + cycleSequence + "-" + Long.toUnsignedString(quoteRequestSequence.incrementAndGet(), 36) + "-" + orderNonce;
        return new PlaceOrderRequest(accountId, clientOrderId, instrumentId, quote.side(),
                OrderType.LIMIT, TimeInForce.GTX, quote.priceTicks(), quote.quantitySteps(),
                strategy.getMarginMode(), PositionSide.NET, false, true);
    }

    static final class PartialAmendException extends IllegalStateException {
        PartialAmendException(String message) { super(message); }
        PartialAmendException(String message, Throwable cause) { super(message, cause); }
    }

    record ReconcileResult(long submitted, long canceled, long rejected, String rejectionReason) {
    }

    record CancelResult(long completed, List<CancelOrderRequest> failed) {
    }
}
