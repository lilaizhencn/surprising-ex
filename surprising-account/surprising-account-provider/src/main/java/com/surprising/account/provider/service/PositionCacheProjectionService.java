package com.surprising.account.provider.service;

import com.surprising.account.api.model.PositionCacheEvent;
import com.surprising.account.provider.repository.PositionCacheProjectionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.util.List;
import org.springframework.stereotype.Service;

/** 为 Redis 读模型提供 PostgreSQL 最终状态快照、启动扫描和核对扫描。 */
@Service
public class PositionCacheProjectionService {

    private final PositionCacheProjectionRepository repository;

    public PositionCacheProjectionService(PositionCacheProjectionRepository repository) {
        this.repository = repository;
    }

    public List<PositionCacheEvent> page(ProductLine productLine, Cursor after, int limit) {
        Cursor cursor = after == null ? Cursor.start() : after;
        return repository.page(productLine, cursor.userId(), cursor.symbol(), cursor.marginMode(),
                cursor.positionSide(), limit);
    }

    /**
     * 单次数据库往返读取一个持仓的最终形态。该方法必须与持仓变更处于同一事务，
     * 返回的快照只能在事务提交后写入 Redis。
     */
    public PositionCacheEvent captureFinalSnapshot(ProductLine productLine,
                                                   long userId,
                                                   String symbol,
                                                   MarginMode marginMode,
                                                   PositionSide positionSide) {
        return repository.capture(productLine, userId, symbol, marginMode, positionSide);
    }

    public Cursor cursor(PositionCacheEvent event) {
        return new Cursor(
                event.userId(), event.symbol(), event.marginMode().name(), event.positionSide().name());
    }

    public record Cursor(long userId, String symbol, String marginMode, String positionSide) {
        public static Cursor start() {
            return new Cursor(0L, "", "", "");
        }
    }

}
