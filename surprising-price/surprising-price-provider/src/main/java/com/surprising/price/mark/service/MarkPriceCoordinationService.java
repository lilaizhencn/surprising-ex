package com.surprising.price.mark.service;

import com.surprising.price.mark.model.MarkPriceEncoding;
import com.surprising.price.mark.repository.MarkPriceLeaseRepository;
import com.surprising.price.mark.repository.MarkPriceSequenceRepository;
import java.time.Duration;
import org.springframework.stereotype.Service;

/**
 * 聚合标记价发布所需的序列、租约和编码能力。
 *
 * <p>底层 Repository 均保持单表职责，跨存储能力由本服务统一编排。
 */
@Service
public class MarkPriceCoordinationService {

    private final MarkPriceLeaseRepository leaseRepository;
    private final MarkPriceSequenceRepository sequenceRepository;
    private final MarkPriceEncodingService encodingService;

    public MarkPriceCoordinationService(MarkPriceLeaseRepository leaseRepository,
                                        MarkPriceSequenceRepository sequenceRepository,
                                        MarkPriceEncodingService encodingService) {
        this.leaseRepository = leaseRepository;
        this.sequenceRepository = sequenceRepository;
        this.encodingService = encodingService;
    }

    public long nextSequence(String module, String symbol) {
        return sequenceRepository.next(module, symbol);
    }

    public boolean acquireLease(String module, String symbol, String ownerId, Duration leaseDuration) {
        return leaseRepository.acquire(module, symbol, ownerId, leaseDuration);
    }

    public MarkPriceEncoding currentEncoding(String symbol) {
        return encodingService.currentEncoding(symbol);
    }
}
