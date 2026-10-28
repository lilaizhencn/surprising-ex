package com.surprising.aeron.service.core;

import static org.assertj.core.api.Assertions.*;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.replay.CommittedTradeReplay;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Real settlement replay, including a partially filled maker across a snapshot. */
class CommittedFeeObservationTest {

  private long sequence;

  private List<RealtimeFrame> apply(
    CommittedTradeReplay replay,
    CoreMessageType type,
    long user,
    byte[] payload
  ) {
    long current = ++sequence;
    var message = new CoreMessage(
      CoreMessageHeader.command(
        type,
        new UUID(71428, current),
        ProductLine.SPOT,
        CommandSource.GATEWAY,
        71428,
        current,
        user,
        1793180000000L + current,
        0
      ),
      payload
    );
    return replay.apply(
      CoreMessageCodec.encode(message),
      1793180000000L + current,
      current * 64
    );
  }

  private void setup(CommittedTradeReplay replay, long makerFee) {
    apply(
      replay,
      CoreMessageType.REGISTER_INSTRUMENT,
      0,
      TradingCommandCodec.encodeRegisterInstrument(
        new RegisterInstrumentCommand(
          "1",
          "BTC",
          "USDT",
          "USDT",
          1,
          1,
          1,
          null,
          makerFee,
          20000,
          0,
          -1,
          0
        ),
        ProductLine.SPOT
      )
    );
    apply(
      replay,
      CoreMessageType.ADJUST_BALANCE,
      1,
      TradingCommandCodec.encodeBalanceAdjustment(
        new BalanceAdjustmentCommand("BTC", 1000)
      )
    );
    apply(
      replay,
      CoreMessageType.ADJUST_BALANCE,
      1,
      TradingCommandCodec.encodeBalanceAdjustment(
        new BalanceAdjustmentCommand("USDT", 1000)
      )
    );
    apply(
      replay,
      CoreMessageType.ADJUST_BALANCE,
      2,
      TradingCommandCodec.encodeBalanceAdjustment(
        new BalanceAdjustmentCommand("USDT", 100000)
      )
    );
    order(replay, 1, 1001, CoreOrderSide.SELL, 10);
  }

  private List<RealtimeFrame> order(
    CommittedTradeReplay replay,
    long user,
    long id,
    CoreOrderSide side,
    long quantity
  ) {
    return apply(
      replay,
      CoreMessageType.PLACE_ORDER,
      user,
      TradingCommandCodec.encodePlaceOrder(
        new PlaceOrderCommand(
          id,
          "1",
          side,
          100,
          quantity,
          false,
          CoreMarginMode.CROSS,
          CorePositionSide.NET,
          CoreOrderType.LIMIT,
          CoreTimeInForce.GTC,
          false,
          "fee-" + id
        )
      )
    );
  }

  @Test
  void readsActualChargedAmountsAndRetainedCheckpointBaseline() {
    try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
      setup(replay, 10000);
      var first = replay.tradeFeeObservations(
        order(replay, 2, 1002, CoreOrderSide.BUY, 5)
      );
      assertThat(first)
        .extracting(CoreTradeFeeObservation::asset)
        .containsOnly("USDT");
      assertThat(first)
        .filteredOn(row -> row.userId() == 1)
        .singleElement()
        .satisfies(row -> assertThat(row.cumulativeFeeUnits()).isEqualTo(5));
      assertThat(first)
        .filteredOn(row -> row.userId() == 2)
        .singleElement()
        .satisfies(row -> assertThat(row.cumulativeFeeUnits()).isEqualTo(10));
      try (
        var restored = new CommittedTradeReplay(
          ProductLine.SPOT,
          replay.snapshot()
        )
      ) {
        assertThat(restored.retainedTradeFeeBaselines())
          .filteredOn(row -> row.orderId() == 1001)
          .singleElement()
          .satisfies(row -> assertThat(row.cumulativeFeeUnits()).isEqualTo(5));
        var second = restored.tradeFeeObservations(
          order(restored, 2, 1003, CoreOrderSide.BUY, 5)
        );
        assertThat(second)
          .filteredOn(row -> row.userId() == 1)
          .singleElement()
          .satisfies(row -> assertThat(row.cumulativeFeeUnits()).isEqualTo(10));
      }
    }
  }

  @Test
  void negativeMakerRebatesRemainNegativeInsteadOfBecomingCommissionableFees() {
    try (var replay = new CommittedTradeReplay(ProductLine.SPOT, null)) {
      setup(replay, -10000);
      var rows = replay.tradeFeeObservations(
        order(replay, 2, 1002, CoreOrderSide.BUY, 5)
      );
      assertThat(rows)
        .filteredOn(row -> row.userId() == 1)
        .singleElement()
        .satisfies(row -> assertThat(row.cumulativeFeeUnits()).isEqualTo(-5));
    }
  }
}
