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
        long now = System.currentTimeMillis();
        try {
            requests.renew(product, user, now + 30000);
            var source = views.read(product, user, now, 15000);
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
