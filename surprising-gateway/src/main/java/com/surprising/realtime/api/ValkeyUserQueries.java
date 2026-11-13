package com.surprising.realtime.api;

import com.surprising.product.api.ProductLine;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import com.surprising.realtime.api.exception.ReadViewUnavailableException;

@Component
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

    /** Orders are absolute entity values; a point read does not materialize the user's account. */
    public com.surprising.aeron.protocol.CoreOrderStateView order(
            ProductLine product, long user, long orderId, Long minExportSequence) {
        var source = requireSource(product, user, minExportSequence,
                java.util.List.of("ORDER:" + orderId));
        for (var frame : source.frames()) {
            if (frame.kind() != com.surprising.aeron.protocol.RealtimeFrame.Kind.ORDER) continue;
            var order = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeOrderState(frame.payload());
            if (frame.productLine() != product || frame.userId() != user
                    || order.productLine() != product || order.userId() != user || order.orderId() != orderId)
                throw new ReadViewUnavailableException("READ_VIEW_ORDER_UNAVAILABLE");
            return "OPEN".equals(order.status()) ? order : null;
        }
        return null;
    }

    public java.util.Optional<com.surprising.aeron.protocol.CoreBalanceView> balance(
            ProductLine product, long user, String asset, Long minExportSequence) {
        var account = requireAccount(require(product, user, minExportSequence,
                java.util.List.of("USER:user", "BALANCE:" + asset)), product, user);
        return account.balances().stream().filter(v -> v.asset().equals(asset)).findFirst();
    }

    public java.util.Optional<com.surprising.aeron.protocol.CorePositionView> position(
            ProductLine product, long user, String instrumentId,
            com.surprising.aeron.protocol.CoreMarginMode marginMode,
            com.surprising.aeron.protocol.CorePositionSide side, Long minExportSequence) {
        var account = requireAccount(require(product, user, minExportSequence,
                java.util.List.of("USER:user", "POSITION:" + instrumentId + ":" + side)), product, user);
        return account.positions().stream().filter(v -> v.instrumentId().equals(instrumentId)
                && v.marginMode() == marginMode && v.positionSide() == side).findFirst();
    }

    private static com.surprising.aeron.protocol.CoreUserStateView requireAccount(
            UserReadView view, ProductLine product, long user) {
        var account = view.account();
        if (account == null || account.productLine() != product || account.userId() != user)
            throw new ReadViewUnavailableException("READ_VIEW_ACCOUNT_UNAVAILABLE");
        return account;
    }

    private UserReadView require(ProductLine product, long user, Long minExportSequence,
                                 java.util.List<String> fields) {
        return UserReadView.from(requireSource(product, user, minExportSequence, fields));
    }

    private ValkeyReadViewStore.ReadView requireSource(ProductLine product, long user, Long minExportSequence,
                                                       java.util.List<String> fields) {
        long now = System.currentTimeMillis();
        try {
            requests.renew(product, user, now + 30000);
            var source = fields == null ? views.read(product, user, now, 15000)
                    : views.readFields(product, user, now, 15000, fields);
            if (!source.status().equals("READY"))
                throw new ReadViewUnavailableException("READ_VIEW_" + source.status());
            if (minExportSequence != null && source.exportSequence() < minExportSequence)
                throw new ReadViewUnavailableException("READ_VIEW_BEHIND");
            return source;
        } catch (org.springframework.dao.DataAccessException unavailable) {
            throw new ReadViewUnavailableException("READ_VIEW_UNAVAILABLE");
        }
    }
}
