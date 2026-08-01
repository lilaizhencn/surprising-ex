package com.surprising.account.provider.service;

import com.surprising.account.api.model.OpenInterestShardSnapshot;
import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.repository.OpenInterestShardRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionOpenInterestService {

    private final PositionRepository positionRepository;
    private final OpenInterestShardRepository openInterestShardRepository;
    private final AccountOutboxService accountOutboxService;

    public PositionOpenInterestService(PositionRepository positionRepository,
                                       OpenInterestShardRepository openInterestShardRepository) {
        this(positionRepository, openInterestShardRepository, null);
    }

    @Autowired
    public PositionOpenInterestService(PositionRepository positionRepository,
                                       OpenInterestShardRepository openInterestShardRepository,
                                       AccountOutboxService accountOutboxService) {
        this.positionRepository = positionRepository;
        this.openInterestShardRepository = openInterestShardRepository;
        this.accountOutboxService = accountOutboxService;
    }

    @Transactional
    public OpenInterestShardRepository.OpenInterestShardState update(ProductLine productLine,
                                                                      long userId,
                                                                      String symbol,
                                                                      MarginMode marginMode,
                                                                      PositionSide positionSide,
                                                                      PositionState state,
                                                                      long previousSignedQuantitySteps,
                                                                      Instant now) {
        long longDelta = Math.subtractExact(longQuantitySteps(state.signedQuantitySteps()),
                longQuantitySteps(previousSignedQuantitySteps));
        long shortDelta = Math.subtractExact(shortQuantitySteps(state.signedQuantitySteps()),
                shortQuantitySteps(previousSignedQuantitySteps));
        if (longDelta == 0L && shortDelta == 0L) {
            int rows = positionRepository.update(
                    productLine, userId, symbol, marginMode, positionSide, state, now);
            requireSingleRow(rows, "account position update");
            return null;
        }
        int shardId = OpenInterestShardRepository.shardId(userId);
        int rows = openInterestShardRepository.seed(productLine, symbol, shardId, now);
        if (rows < 0 || rows > 1) {
            throw new IllegalStateException("unexpected open interest shard seed rows: " + rows);
        }
        rows = positionRepository.update(productLine, userId, symbol, marginMode, positionSide, state, now);
        requireSingleRow(rows, "account position update");
        var snapshotResult = openInterestShardRepository.adjustAndSnapshot(
                productLine, symbol, shardId, longDelta, shortDelta, now);
        if (snapshotResult.isEmpty()) {
            // 兼容迁移期间的旧仓储替身；生产实现会始终通过 RETURNING 返回快照。
            requireSingleRow(openInterestShardRepository.adjust(
                    productLine, symbol, shardId, longDelta, shortDelta, now),
                    "open interest shard update");
            return null;
        }
        OpenInterestShardRepository.OpenInterestShardState snapshot = snapshotResult.get();
        if (accountOutboxService != null) {
            accountOutboxService.enqueueOpenInterestUpdated(
                    new OpenInterestShardSnapshot(snapshot.productLine(), snapshot.symbol(), snapshot.shardId(),
                            snapshot.longQuantitySteps(), snapshot.shortQuantitySteps(), snapshot.revision(),
                            snapshot.updatedAt()), now);
        }
        return snapshot;
    }

    private static long longQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps > 0 ? signedQuantitySteps : 0L;
    }

    private static long shortQuantitySteps(long signedQuantitySteps) {
        return signedQuantitySteps < 0 ? Math.negateExact(signedQuantitySteps) : 0L;
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }
}
