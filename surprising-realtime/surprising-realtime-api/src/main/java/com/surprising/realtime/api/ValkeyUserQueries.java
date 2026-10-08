package com.surprising.realtime.api;

import com.surprising.product.api.ProductLine;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "surprising.realtime.enabled", havingValue = "true")
public final class ValkeyUserQueries {
    private final ValkeyReadViewStore views;
    private final ValkeySnapshotRequests requests;

    public ValkeyUserQueries(StringRedisTemplate redis) {
        views = new ValkeyReadViewStore(redis);
        requests = new ValkeySnapshotRequests(redis);
    }

    public UserReadView require(ProductLine product, long user, Long minExportSequence) {
        return require(product, user, minExportSequence, null);
    }

    public java.util.Optional<com.surprising.aeron.protocol.CoreBalanceView> balance(
            ProductLine product, long user, String asset, Long minExportSequence) {
        var account = requireAccount(require(product, user, minExportSequence,
                java.util.List.of("BALANCE:" + asset)), product, user);
        return account.balances().stream().filter(v -> v.asset().equals(asset)).findFirst();
    }

    public java.util.Optional<com.surprising.aeron.protocol.CorePositionView> position(
            ProductLine product, long user, String instrumentId,
            com.surprising.aeron.protocol.CoreMarginMode marginMode,
            com.surprising.aeron.protocol.CorePositionSide side, Long minExportSequence) {
        var account = requireAccount(require(product, user, minExportSequence,
                java.util.List.of("POSITION:" + instrumentId + ":" + side)), product, user);
        return account.positions().stream().filter(v -> v.instrumentId().equals(instrumentId)
                && v.marginMode() == marginMode && v.positionSide() == side).findFirst();
    }

    private static com.surprising.aeron.protocol.CoreUserStateView requireAccount(
            UserReadView view, ProductLine product, long user) {
        var account = view.account();
        if (account == null || account.productLine() != product || account.userId() != user)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "READ_VIEW_ACCOUNT_UNAVAILABLE");
        return account;
    }

    private UserReadView require(ProductLine product, long user, Long minExportSequence,
                                 java.util.List<String> fields) {
        long now = System.currentTimeMillis();
        try {
            requests.renew(product, user, now + 30000);
            var source = fields == null ? views.read(product, user, now, 15000)
                    : views.readFields(product, user, now, 15000, fields);
            if (!source.status().equals("READY"))
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "READ_VIEW_" + source.status());
            if (minExportSequence != null && source.exportSequence() < minExportSequence)
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE, "READ_VIEW_BEHIND");
            return UserReadView.from(source);
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "READ_VIEW_UNAVAILABLE");
        }
    }
}
