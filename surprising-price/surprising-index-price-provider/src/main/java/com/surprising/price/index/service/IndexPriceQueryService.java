package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexPriceResponse;
import com.surprising.price.index.repository.IndexPriceComponentRepository;
import com.surprising.price.index.repository.IndexPriceTickRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 聚合指数价格主记录与成分明细。 */
@Service
public class IndexPriceQueryService {

    private final IndexPriceTickRepository tickRepository;
    private final IndexPriceComponentRepository componentRepository;

    public IndexPriceQueryService(IndexPriceTickRepository tickRepository,
                                  IndexPriceComponentRepository componentRepository) {
        this.tickRepository = tickRepository;
        this.componentRepository = componentRepository;
    }

    public List<IndexPriceResponse> history(String symbol, Instant startTime, Instant endTime, int limit) {
        return tickRepository.history(symbol, startTime, endTime, limit).stream()
                .map(tick -> new IndexPriceResponse(
                        tick.symbol(),
                        tick.indexPrice(),
                        tick.sequence(),
                        tick.status(),
                        tick.componentCount(),
                        tick.validComponentCount(),
                        tick.eventTime(),
                        componentRepository.find(tick.symbol(), tick.sequence())))
                .toList();
    }
}
