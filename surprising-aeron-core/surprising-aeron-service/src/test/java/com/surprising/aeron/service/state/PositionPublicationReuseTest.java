package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PositionPublicationReuseTest {
    private static PositionRuntime position(long user, long quantity) {
        return new PositionRuntime(user, 0, 1, CoreMarginMode.CROSS, CorePositionSide.NET,
                CoreStateTestFixtures.runtimeInstrument(), quantity, 100, Math.abs(quantity) * 100, 0, 40);
    }

    @Test void laneCopiesStayStableAcrossHandoffAndReusableCellsFollowTheirPhysicalBuffer() {
        var lane = new RuntimePositionChangeBuffer();
        var owner = new RuntimePositionChangeBuffer();
        var first = position(7, 2);
        var stable = first.snapshot();
        lane.put(1, first);
        var publication = lane.get(1);
        assertThat(publication).isNotSameAs(first);
        owner.swapWith(lane);
        first.applyInPlace(first.instrument(), 3, 110, 330, 9, 50);
        lane.put(1, first);
        assertThat(owner.get(1).signedQuantitySteps()).isEqualTo(2);
        assertThat(lane.get(1).signedQuantitySteps()).isEqualTo(3);
        owner.drainTo((key, value) -> assertThat(value.signedQuantitySteps()).isEqualTo(2));
        assertThat(publication.instrument()).isNull();
        owner.put(2, position(9, -4));
        assertThat(owner.get(2)).isSameAs(publication);
        assertThat(owner.get(2).userId()).isEqualTo(9);
        assertThat(owner.get(2).signedQuantitySteps()).isEqualTo(-4);
        assertThat(lane.get(1).realizedPnlUnits()).isEqualTo(9);
        assertThat(stable.signedQuantitySteps()).isEqualTo(2);
        assertThatThrownBy(() -> first.copyIntoPublicationSlot(stable)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void repeatedUpdatesAndGrowthDoNotOverwriteOtherPublishedKeys() {
        var values = new RuntimePositionChangeBuffer();
        for (int i = 1; i <= 33; i++) values.put(i, position(i, i));
        var cell = values.get(5);
        values.put(5, position(5, -8));
        assertThat(values.get(5)).isSameAs(cell);
        assertThat(cell.signedQuantitySteps()).isEqualTo(-8);
        assertThat(values.get(33).signedQuantitySteps()).isEqualTo(33);
        values.putPrepared(5, new RuntimePositionIndexValue(5, "1", "USDT", CorePositionSide.NET, -8));
        values.clear();
        assertThat(cell.instrument()).isNull();
        values.put(1, position(99, 9));
        values.forEachIndexed((key, value, prepared, index) -> assertThat(prepared).isFalse());
    }
}
