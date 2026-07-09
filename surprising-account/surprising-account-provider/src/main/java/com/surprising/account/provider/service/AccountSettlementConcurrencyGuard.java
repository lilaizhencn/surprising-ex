package com.surprising.account.provider.service;

import com.surprising.account.provider.config.AccountProperties;
import com.surprising.trading.api.model.MatchTradeEvent;
import java.util.Arrays;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AccountSettlementConcurrencyGuard {

    private final ReentrantLock[] userLocks;

    @Autowired
    public AccountSettlementConcurrencyGuard(AccountProperties properties) {
        this(properties.getSettlement().getMatchTradeUserLockStripes());
    }

    AccountSettlementConcurrencyGuard(int userLockStripes) {
        if (userLockStripes <= 0) {
            throw new IllegalArgumentException("userLockStripes must be positive");
        }
        this.userLocks = new ReentrantLock[userLockStripes];
        for (int i = 0; i < userLocks.length; i++) {
            userLocks[i] = new ReentrantLock();
        }
    }

    public <T> T withTradeUserLocks(MatchTradeEvent trade, Supplier<T> operation) {
        int[] lockIndexes = tradeUserLockIndexes(trade);
        for (int lockIndex : lockIndexes) {
            userLocks[lockIndex].lock();
        }
        try {
            return operation.get();
        } finally {
            for (int i = lockIndexes.length - 1; i >= 0; i--) {
                userLocks[lockIndexes[i]].unlock();
            }
        }
    }

    private int[] tradeUserLockIndexes(MatchTradeEvent trade) {
        int takerLock = userLockIndex(trade.takerUserId());
        int makerLock = userLockIndex(trade.makerUserId());
        if (takerLock == makerLock) {
            return new int[] {takerLock};
        }
        int[] lockIndexes = new int[] {takerLock, makerLock};
        Arrays.sort(lockIndexes);
        return lockIndexes;
    }

    private int userLockIndex(long userId) {
        return Math.floorMod(Long.hashCode(userId), userLocks.length);
    }
}
