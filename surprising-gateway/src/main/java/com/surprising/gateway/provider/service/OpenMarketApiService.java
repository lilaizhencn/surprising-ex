package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.instrument.provider.service.InstrumentService;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.OrderBookLevel;
import com.surprising.trading.api.model.OrderBookSnapshotResponse;
import com.surprising.trading.matching.service.MatchingMarketDataService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * 公开 Open API 行情与基础端点处理（ping、time、exchangeInfo、depth、bookTicker）。
 */
public class OpenMarketApiService {

    private final GatewayProperties properties;
    private final InstrumentService instrumentService;
    private final MatchingMarketDataService marketDataService;
    private final OpenApiSupport support;

    public OpenMarketApiService(GatewayProperties properties,
                                InstrumentService instrumentService,
                                MatchingMarketDataService marketDataService,
                                OpenApiSupport support) {
        this.properties = properties;
        this.instrumentService = instrumentService;
        this.marketDataService = marketDataService;
        this.support = support;
    }

    public ResponseEntity<byte[]> ping() {
        return support.json(HttpStatus.OK, Map.of());
    }

    public ResponseEntity<byte[]> time() {
        return support.json(HttpStatus.OK, Map.of("serverTime", System.currentTimeMillis()));
    }

    public ResponseEntity<byte[]> exchangeInfo(HttpServletRequest request) {
        ProductLine product = support.productLine(request);
        return support.json(HttpStatus.OK, instrumentService.publicCatalog(product, null, null, false).instruments());
    }

    public ResponseEntity<byte[]> depth(HttpServletRequest request) {
        String symbol = support.requiredParameter(request, "symbol");
        int limit = support.capped(request.getParameter("limit"));
        String instrumentId = properties.getBinanceApi().backendInstrumentId(symbol, support.productLine(request));
        OrderBookSnapshotResponse snapshot = marketDataService.orderBookSnapshot(instrumentId, limit);
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        return support.json(HttpStatus.OK, Map.of(
                "lastUpdateId", snapshot.sequence(),
                "bids", levels(snapshot.bids(), scale),
                "asks", levels(snapshot.asks(), scale)));
    }

    public ResponseEntity<byte[]> bookTicker(HttpServletRequest request) {
        String symbol = support.requiredParameter(request, "symbol");
        String instrumentId = properties.getBinanceApi().backendInstrumentId(symbol, support.productLine(request));
        OrderBookSnapshotResponse snapshot = marketDataService.orderBookSnapshot(instrumentId, 5);
        GatewayProperties.SymbolScale scale = properties.getBinanceApi().scale(symbol);
        OrderBookLevel bid = snapshot.bids().isEmpty() ? null : snapshot.bids().getFirst();
        OrderBookLevel ask = snapshot.asks().isEmpty() ? null : snapshot.asks().getFirst();
        return support.json(HttpStatus.OK, Map.of(
                "symbol", symbol,
                "bidPrice", support.decimalString(bid != null ? bid.priceTicks() : 0L, scale.getPriceScale()),
                "bidQty", support.decimalString(bid != null ? bid.quantitySteps() : 0L, scale.getQuantityScale()),
                "askPrice", support.decimalString(ask != null ? ask.priceTicks() : 0L, scale.getPriceScale()),
                "askQty", support.decimalString(ask != null ? ask.quantitySteps() : 0L, scale.getQuantityScale())));
    }

    private List<List<String>> levels(List<OrderBookLevel> levels, GatewayProperties.SymbolScale scale) {
        if (levels == null) return List.of();
        List<List<String>> result = new ArrayList<>();
        for (OrderBookLevel level : levels) {
            result.add(List.of(support.decimalString(level.priceTicks(), scale.getPriceScale()),
                    support.decimalString(level.quantitySteps(), scale.getQuantityScale())));
        }
        return result;
    }
}
