package com.surprising.price.index.service;

import com.surprising.price.api.model.IndexPriceEvent;
import com.surprising.price.index.repository.IndexPriceComponentRepository;
import com.surprising.price.index.repository.IndexPriceTickRepository;
import com.surprising.price.index.repository.IndexPriceTickRepository.TickKey;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 编排指数价格审计快照的写入和清理。
 *
 * <p>清理由 Service 在同一事务内先锁定主记录，再删除明细和主记录；两个 Repository
 * 始终只访问各自负责的物理表。</p>
 */
@Service
public class IndexPriceAuditService {

    private final IndexPriceTickRepository tickRepository;
    private final IndexPriceComponentRepository componentRepository;

    public IndexPriceAuditService(IndexPriceTickRepository tickRepository,
                                  IndexPriceComponentRepository componentRepository) {
        this.tickRepository = tickRepository;
        this.componentRepository = componentRepository;
    }

    @Transactional
    public void saveBatch(List<IndexPriceEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        tickRepository.saveBatch(events);
        componentRepository.saveBatch(events);
    }

    @Transactional
    public int deleteBefore(Instant cutoff, int limit) {
        List<TickKey> keys = tickRepository.findExpiredForDeletion(cutoff, limit);
        if (keys.isEmpty()) {
            return 0;
        }
        componentRepository.deleteByKeys(keys);
        return tickRepository.deleteByKeys(keys);
    }
}
