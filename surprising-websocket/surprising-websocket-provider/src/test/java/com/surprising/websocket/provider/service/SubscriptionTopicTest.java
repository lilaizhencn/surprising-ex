package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.api.model.WsClientCommand;
import org.junit.jupiter.api.Test;

class SubscriptionTopicTest {

    @Test
    void publicCandleSubscriptionNormalizesSymbolAndPeriod() {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(
                new WsClientCommand("subscribe", "req-1", "candles", "btc-usdt", "1M", null),
                null);

        assertThat(topic.channel()).isEqualTo(WsChannel.CANDLES);
        assertThat(topic.symbol()).isEqualTo("BTC-USDT");
        assertThat(topic.period()).isEqualTo("1m");
        assertThat(topic.userId()).isNull();
    }

    @Test
    void publicDepthSubscriptionNormalizesSymbol() {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(
                new WsClientCommand("subscribe", "req-depth", "depth", "btc-usdt", null, null),
                null);

        assertThat(topic.channel()).isEqualTo(WsChannel.ORDER_BOOK_DEPTH);
        assertThat(topic.symbol()).isEqualTo("BTC-USDT");
        assertThat(topic.period()).isNull();
        assertThat(topic.userId()).isNull();
    }

    @Test
    void privateChannelUsesAuthenticatedUserAndAllowsWildcardSymbol() {
        SubscriptionTopic topic = SubscriptionTopic.fromCommand(
                new WsClientCommand("subscribe", "req-2", "positions", null, null, null),
                42L);

        assertThat(topic.channel()).isEqualTo(WsChannel.POSITIONS);
        assertThat(topic.symbol()).isEqualTo(SubscriptionTopic.WILDCARD);
        assertThat(topic.userId()).isEqualTo(42L);
    }

    @Test
    void privateRiskChannelsUseAuthenticatedUser() {
        SubscriptionTopic accountRisk = SubscriptionTopic.fromCommand(
                new WsClientCommand("subscribe", "req-risk-1", "accountRisk", null, null, null),
                42L);
        SubscriptionTopic positionRisk = SubscriptionTopic.fromCommand(
                new WsClientCommand("subscribe", "req-risk-2", "positionRisk", "btc-usdt", null, null),
                42L);

        assertThat(accountRisk.channel()).isEqualTo(WsChannel.ACCOUNT_RISK);
        assertThat(accountRisk.symbol()).isEqualTo(SubscriptionTopic.WILDCARD);
        assertThat(accountRisk.userId()).isEqualTo(42L);
        assertThat(positionRisk.channel()).isEqualTo(WsChannel.POSITION_RISK);
        assertThat(positionRisk.symbol()).isEqualTo("BTC-USDT");
        assertThat(positionRisk.userId()).isEqualTo(42L);
    }

    @Test
    void privateChannelRejectsMissingAuthentication() {
        WsClientCommand command = new WsClientCommand("subscribe", "req-3", "orders", "BTC-USDT", null, null);

        assertThatThrownBy(() -> SubscriptionTopic.fromCommand(command, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("authenticated user");
    }

    @Test
    void privateChannelRejectsUserIdMismatch() {
        WsClientCommand command = new WsClientCommand("subscribe", "req-4", "orders", "BTC-USDT", null, 7L);

        assertThatThrownBy(() -> SubscriptionTopic.fromCommand(command, 8L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match");
    }
}
