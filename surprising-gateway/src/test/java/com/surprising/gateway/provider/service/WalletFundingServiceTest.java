package com.surprising.gateway.provider.service;

import com.surprising.asset.model.AssetConfiguration.*;
import com.surprising.asset.repository.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalletFundingServiceTest {
    private final AssetRepository assets = mock();
    private final AssetNetworkRepository networks = mock();
    private final CustodyWalletClient custody = mock();
    private final WalletFundingService service = new WalletFundingService(assets, networks, custody);
    private Asset asset() { return new Asset(1, "USDT", "Tether", "/assets/usdt.svg", 100000000, true, true, 1, Instant.now()); }
    private Network network(boolean deposit) { return new Network(1, 1, "ETH", "Ethereum", "0x123", false, 6, deposit, true,
        BigDecimal.ONE, BigDecimal.TEN, BigDecimal.ONE, 12, 1, Instant.now()); }
    private Map<String, Object> chain(boolean scan) { return Map.of("chain", "ETH", "status", "ACTIVE", "enabled", true,
        "scanEnabled", scan, "withdrawalEnabled", true, "assetSymbols", List.of("ETH", "USDT")); }
    private void setup() {
        when(assets.byAccountingCode("USDT")).thenReturn(Optional.of(asset()));
        when(networks.list(1)).thenReturn(List.of(network(true)));
        when(custody.chains()).thenReturn(List.of(chain(true)));
        when(custody.subject(42)).thenReturn("user:42");
    }
    @Test void catalogIntersectsAdminConfigurationAndCustodyCapabilities() {
        when(assets.list(true)).thenReturn(List.of(asset()));
        when(networks.enabled()).thenReturn(List.of(network(true)));
        when(custody.chains()).thenReturn(List.of(chain(false)));
        assertThat(service.catalog("deposit")).isEmpty();
        assertThat(service.catalog("withdraw")).hasSize(1);
        assertThatThrownBy(() -> service.catalog("unsupported")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void disabledOrWrongAssetNetworkCannotAllocateAnAddress() {
        setup();when(networks.list(1)).thenReturn(List.of(network(false)));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalArgumentException.class);
        verify(custody,never()).createAddress(anyLong(),anyString(),any());
        verify(custody,never()).chains();
    }
    @Test void unsupportedCustodyRouteCannotAllocateAnAddress() {
        setup();when(custody.chains()).thenReturn(List.of(chain(false)));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalStateException.class);
        verify(custody,never()).createAddress(anyLong(),anyString(),any());
    }
    @Test void usesStableUserAndVersionForAtomicCustodyGetOrCreate() {
        setup();when(custody.createAddress(42,"ETH",1L)).thenReturn(Map.of("address","0xabc","subject","user:42","chain","ETH","status","ACTIVE","memo","123"));
        var first=service.depositAddress(42,"USDT","ETH");
        assertThat(service.depositAddress(42,"USDT","ETH")).isEqualTo(first);
        assertThat(first.memo()).isEqualTo("123");
        verify(custody,times(2)).createAddress(42,"ETH",1L);
    }
    @Test void rejectsAddressBelongingToAnotherUser() {
        setup();when(custody.createAddress(42,"ETH",1L)).thenReturn(Map.of("address","0xabc","subject","user:43","chain","ETH","status","ACTIVE"));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsInactiveOrMismatchedChainAddress() {
        setup();when(custody.createAddress(42,"ETH",1L)).thenReturn(Map.of("address","0xabc","subject","user:42","chain","TRON","status","ACTIVE"));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalStateException.class);
        when(custody.createAddress(42,"ETH",1L)).thenReturn(Map.of("address","0xabc","subject","user:42","chain","ETH","status","DISABLED"));
        assertThatThrownBy(() -> service.depositAddress(42,"USDT","ETH")).isInstanceOf(IllegalStateException.class);
    }
}
