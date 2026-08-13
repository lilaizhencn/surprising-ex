package com.surprising.eventstore;

import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserMutationBatchTest {

    @Test
    void groupsMutationsByProductLineAndUserInPollOrder() {
        UserMutation first = mutation("first", ProductLine.SPOT, 7L);
        UserMutation second = mutation("second", ProductLine.LINEAR_PERPETUAL, 8L);
        UserMutation third = mutation("third", ProductLine.SPOT, 7L);

        UserMutationBatch batch = new UserMutationBatch(List.of(first, second, third));

        assertThat(batch.byPartition()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                new UserPartitionKey(ProductLine.SPOT, 7L), List.of(first, third),
                new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 8L), List.of(second)));
    }

    @Test
    void rejectsDuplicateCommandIdsWithinOnePoll() {
        UserMutation first = mutation("same", ProductLine.SPOT, 7L);

        assertThat(new UserMutationBatch(List.of(first, first)).mutations())
                .containsExactly(first);
        UserMutation conflicting = mutation("same", ProductLine.SPOT, 8L);
        assertThatThrownBy(() -> new UserMutationBatch(List.of(first, conflicting)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private UserMutation mutation(String commandId, ProductLine productLine, long userId) {
        return new UserMutation(UserMutation.CURRENT_SCHEMA_VERSION, commandId, productLine, userId,
                "ORDER_PLACE", "ORDER", commandId, null, "{}", Instant.parse("2026-01-01T00:00:00Z"), null);
    }
}
