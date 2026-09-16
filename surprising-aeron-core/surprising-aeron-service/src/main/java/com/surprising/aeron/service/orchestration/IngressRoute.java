package com.surprising.aeron.service.orchestration;

/**
 * Fixed routing metadata captured by the Owner before a matching command enters
 * the asynchronous pipeline.
 *
 * <p>The route deliberately contains only information that can be derived from
 * the command and the persisted topology.  Potential maker accounts are not an
 * ingress dependency; the Matcher discovers those accounts from its own order
 * book when it publishes the settlement fact.</p>
 */
public record IngressRoute(long userId, int matcherShard, long userLaneBit, long commandSequence) {
    public IngressRoute {
        if (userId <= 0 || matcherShard < 0 || userLaneBit == 0 || commandSequence < 0) {
            throw new IllegalArgumentException("invalid ingress route");
        }
    }

    public IngressRoute withCommandSequence(long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("command sequence must be non-negative");
        return new IngressRoute(userId, matcherShard, userLaneBit, sequence);
    }
}
