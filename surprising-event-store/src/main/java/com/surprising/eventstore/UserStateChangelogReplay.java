package com.surprising.eventstore;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UserStateChangelogReplay {

    private final Map<UserPartitionKey, Entry> latest = new ConcurrentHashMap<>();

    public Decision observe(UserStateChangelog changelog) {
        if (changelog == null) {
            throw new IllegalArgumentException("user state changelog must not be null");
        }
        UserPartitionKey partition = changelog.userPartition();
        Entry incoming = new Entry(changelog.sequence(), changelog.stateChecksum());
        for (;;) {
            Entry current = latest.get(partition);
            if (current != null) {
                if (incoming.sequence() < current.sequence()) {
                    return Decision.STALE;
                }
                if (incoming.sequence() == current.sequence()) {
                    if (!incoming.checksum().equals(current.checksum())) {
                        throw new IllegalStateException("conflicting user state changelog sequence: "
                                + partition.value() + " sequence=" + incoming.sequence());
                    }
                    return Decision.DUPLICATE;
                }
            }
            if (current == null ? latest.putIfAbsent(partition, incoming) == null
                    : latest.replace(partition, current, incoming)) {
                return Decision.APPLY;
            }
        }
    }

    public enum Decision {
        APPLY,
        DUPLICATE,
        STALE
    }

    private record Entry(long sequence, String checksum) {
    }
}
