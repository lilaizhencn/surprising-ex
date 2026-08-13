package com.surprising.eventstore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record UserMutationBatch(List<UserMutation> mutations) {

    public UserMutationBatch {
        if (mutations == null || mutations.isEmpty()) {
            throw new IllegalArgumentException("user mutation batch must not be empty");
        }
        List<UserMutation> copy = new ArrayList<>(mutations.size());
        Map<String, UserMutation> commandById = new LinkedHashMap<>();
        Map<String, Integer> indexByCommandId = new LinkedHashMap<>();
        for (int index = 0; index < mutations.size(); index++) {
            UserMutation mutation = mutations.get(index);
            Objects.requireNonNull(mutation, "user mutation must not be null");
            UserMutation existing = commandById.putIfAbsent(mutation.commandId(), mutation);
            if (existing != null) {
                throw new IllegalArgumentException("duplicate user mutation commandId: " + mutation.commandId());
            }
            indexByCommandId.put(mutation.commandId(), index);
            copy.add(mutation);
        }
        for (UserMutation mutation : copy) {
            if (mutation.dependsOnCommandId() == null) {
                continue;
            }
            UserMutation dependency = commandById.get(mutation.dependsOnCommandId());
            if (dependency != null) {
                if (!dependency.userPartition().equals(mutation.userPartition())) {
                    throw new IllegalArgumentException("user mutation dependency crosses partition: "
                            + mutation.commandId());
                }
                if (indexByCommandId.get(dependency.commandId()) >= indexByCommandId.get(mutation.commandId())) {
                    throw new IllegalArgumentException("user mutation dependency must precede command: "
                            + mutation.commandId());
                }
            }
        }
        mutations = List.copyOf(copy);
    }

    public Map<UserPartitionKey, List<UserMutation>> byPartition() {
        Map<UserPartitionKey, List<UserMutation>> grouped = new LinkedHashMap<>();
        for (UserMutation mutation : mutations) {
            grouped.computeIfAbsent(mutation.userPartition(), ignored -> new ArrayList<>()).add(mutation);
        }
        Map<UserPartitionKey, List<UserMutation>> result = new LinkedHashMap<>();
        grouped.forEach((partition, values) -> result.put(partition, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }
}
