package com.surprising.account.provider.service;

import com.surprising.account.provider.model.PositionState;
import com.surprising.account.provider.repository.OpenInterestShardRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PositionOpenInterestService {

    private final PositionRepository positionRepository;
    private final OpenInterestShardRepository openInterestShardRepository;

    public PositionOpenInterestService(PositionRepository positionRepository,
                                       OpenInterestShardRepository openInterestShardRepository) {
        this.positionRepository = positionRepository;
        this.openInterestShardRepository = openInterestShardRepository;
    }

    @Transactional
    public void update(ProductLine productLine,
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
            return;
        }
        int shardId = OpenInterestShardRepository.shardId(userId);
        int rows = openInterestShardRepository.seed(productLine, symbol, shardId, now);
        if (rows < 0 || rows > 1) {
            throw new IllegalStateException("unexpected open interest shard seed rows: " + rows);
        }
        rows = positionRepository.update(productLine, userId, symbol, marginMode, positionSide, state, now);
        requireSingleRow(rows, "account position update");
        rows = openInterestShardRepository.adjust(
                productLine, symbol, shardId, longDelta, shortDelta, now);
        requireSingleRow(rows, "open interest shard update");
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
